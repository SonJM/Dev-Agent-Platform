package io.github.lib0823.devagent.issue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 이슈 본문 "참고 저장소" 절 파싱 — 다른 프로젝트를 읽기 전용으로 준비하는 입력. */
class ReferenceRepoParseTest {

    @Test
    void parsesRepoAndBranchVariants() {
        String desc = """
                ## 배경
                설명 acme/shop/board 는 여기선 무시.

                ## 참고 저장소 (읽기 전용, 수정 금지)
                - `acme/shop/batch` **main** 브랜치. 원본 위치 `src/main/java/com/acme/batch/application/`:
                  - `controller/` — `@Call(name, title)` 메서드
                - group/other@release/1.2
                - group/third develop 브랜치
                - 이 저장소: `docs/03-job-contract.md`, `jobs/java/sample`

                ## 해야 할 일
                - group/ignored 는 다른 절
                """;
        List<String[]> refs = IssueTexts.parseReferenceRepos(desc);
        assertEquals(3, refs.size(), () -> String.join(",", refs.stream().map(r -> r[0]).toList()));
        assertEquals("acme/shop/batch", refs.get(0)[0]);
        assertEquals("main", refs.get(0)[1]);
        assertEquals("group/other", refs.get(1)[0]);
        assertEquals("release/1.2", refs.get(1)[1]);
        assertEquals("group/third", refs.get(2)[0]);
        assertEquals("develop", refs.get(2)[1]);
    }

    @Test
    void branchDefaultsToNullAndDuplicatesCollapse() {
        String desc = "# 참고저장소\n- grp/repo\n- grp/repo@main\n";
        List<String[]> refs = IssueTexts.parseReferenceRepos(desc);
        assertEquals(1, refs.size());
        assertNull(refs.get(0)[1]);
    }

    @Test
    void noSectionMeansNothing() {
        assertTrue(IssueTexts.parseReferenceRepos("본문에 grp/repo 가 있어도 절이 없으면 무시").isEmpty());
        assertTrue(IssueTexts.parseReferenceRepos(null).isEmpty());
    }
}

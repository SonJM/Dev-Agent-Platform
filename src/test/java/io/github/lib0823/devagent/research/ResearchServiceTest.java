package io.github.lib0823.devagent.research;

import io.github.lib0823.devagent.admin.SettingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 질의 워크플로우의 순수 함수: 메신저 명령 파싱, 결과 문서 절 추출, 평문 변환. */
class ResearchServiceTest {

    @Test
    void parsesProjectPrefixAndQuestion() {
        ResearchService.Parsed p = ResearchService.parseMessengerCommand("batch: 스케줄 job 목록 정리해줘", List.of("acme/shop/batch", "acme/scheduler"));
        assertEquals(List.of("batch"), p.projects());
        assertEquals("스케줄 job 목록 정리해줘", p.question());

        p = ResearchService.parseMessengerCommand("acme/shop/batch, scheduler : 두 프로젝트의 job 계약 비교", List.of("acme/shop/batch", "acme/scheduler"));
        assertEquals(List.of("acme/shop/batch", "scheduler"), p.projects());
        assertEquals("두 프로젝트의 job 계약 비교", p.question());
    }

    @Test
    void branchSuffixIsCarriedAndSplitAtFirstAt() {
        ResearchService.Parsed p = ResearchService.parseMessengerCommand("batch@feature/123458, scheduler@develop: 브랜치 차이 정리",
                List.of("acme/shop/batch", "acme/scheduler"));
        assertEquals(List.of("batch@feature/123458", "scheduler@develop"), p.projects());
        assertEquals("브랜치 차이 정리", p.question());

        assertEquals("acme/shop/batch", ResearchService.splitEntry("acme/shop/batch@feature/123458")[0]);
        assertEquals("feature/123458", ResearchService.splitEntry("acme/shop/batch@feature/123458")[1]);
        assertEquals("batch", ResearchService.splitEntry(" batch ")[0]);
        assertNull(ResearchService.splitEntry("batch")[1]);
        assertNull(ResearchService.splitEntry("batch@")[1]);          // 빈 브랜치는 기본 브랜치
    }

    @Test
    void singleProjectAllowsOmittingPrefix() {
        ResearchService.Parsed p = ResearchService.parseMessengerCommand("Batch job 목록 정리해줘", List.of("acme/shop/batch"));
        assertEquals(List.of("acme/shop/batch"), p.projects());
        assertEquals("Batch job 목록 정리해줘", p.question());
        // 프로젝트가 여럿인데 접두어가 없으면 사용법 안내(null)
        assertNull(ResearchService.parseMessengerCommand("Batch job 목록 정리해줘", List.of("a/b", "c/d")));
        assertNull(ResearchService.parseMessengerCommand("", List.of("a/b")));
    }

    @Test
    void sectionAndPlain() {
        String md = "# 제목\n## 요약\n- **총 55개** job\n- `stat` 계열 20개\n## 내용\n| a | b |\n|---|---|\n| 1 | 2 |\n";
        assertEquals("- **총 55개** job\n- `stat` 계열 20개", ResearchService.section(md, "요약"));
        assertEquals("", ResearchService.section(md, "근거"));
        String plain = ResearchService.plain(ResearchService.section(md, "요약"));
        assertTrue(plain.contains("총 55개 job"));
        assertTrue(plain.contains("stat 계열"));
        assertEquals("", ResearchService.plain("|---|---|"));
    }

    /** TC-026(T-028): 질의 대상은 대상 프로젝트 목록 안으로만 제한된다(목록 밖 저장소는 거부). */
    @Test
    void tc026_researchRejectsProjectsOutsideTargetList() {
        SettingService settings = mock(SettingService.class);
        when(settings.getIssueProjects()).thenReturn(new java.util.ArrayList<>(List.of("own/repo")));
        ResearchService svc = new ResearchService(null, null, null, null, null, null, null, settings, null, null, null, null, 8777);

        assertEquals(List.of("own/repo"), svc.normalizeProjects(List.of("OWN/repo"), false));
        assertThrows(IllegalArgumentException.class, () -> svc.normalizeProjects(List.of("evil/repo"), false));
    }
}

package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.GitLabProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueAgentHelpersTest {

    private static GitLabProperties props() {
        return new GitLabProperties(true, List.of("own/repo"), "ai-fix",
                1000, 1000, "work", "bot", "bot@x", true, true, true, 0L,
                new GitLabProperties.Agent("opus", "high", 1000, 10, List.of("Read"), List.of("Read"), "", 3000, 0, 0, 0),
                new GitLabProperties.Verify(true, 2, true, "", "medium", 1000, "", List.of("Read"), 0),
                new GitLabProperties.Convention(List.of("feature", "fix", "refactor", "doc", "hotfix")), 2);
    }

    @Test
    void sectionExtractsHeadingBody() {
        String t = "## 변경 요약\nA.java 수정\nB.java 추가\n## 검증 결과\nok\n## 커밋 메시지\nfix: x\n\nResolves #3";
        assertEquals("A.java 수정\nB.java 추가", IssueTexts.section(t, "변경 요약"));
        assertEquals("fix: x\n\nResolves #3", IssueTexts.section(t, "커밋 메시지"));
        assertEquals(t.strip(), IssueTexts.section(t, "없는 절"));
    }

    /** TC-017: 작업 유형·이슈 번호로 브랜치 이름 생성, 기본 브랜치 푸시 거부. */
    @Test
    void tc017_branchNameIsTypeSlashIssueNumber() {
        GitRunner git = new GitRunner(props(), new GitHubProperties("t", null));
        assertEquals("fix/12", git.branchName("fix", 12));
        assertEquals("feature/7", git.branchName("feature", 7));
        assertEquals("hotfix/12", git.branchName("hotfix", 12));              // hotfix 도 같은 규칙(기본 브랜치 기준)
        assertEquals("fix/12", git.branchName("bogus", 12));                   // 허용되지 않은 유형은 fix
        assertEquals("fix/12", git.branchName(null, 12));
        assertTrue(git.isPushable("fix/12"));
        assertTrue(git.isPushable("hotfix/12-ai"));
        assertTrue(git.isPushable("fix/12-ai-r11251030"));
        assertFalse(git.isPushable("develop"));
        assertFalse(git.isPushable("main"));
        assertFalse(git.isPushable("master"));
        assertFalse(git.isPushable("fix/"));
        assertFalse(git.isPushable("ai/issue-7"));
    }

    @Test
    void workIdAndTypeParsing() {
        String pat = "#?(\\d{5,7})";
        List<String> allowed = List.of("feature", "fix", "refactor", "doc", "hotfix");
        assertEquals("fix", IssueTexts.parseWorkType("## 작업 유형\nfix — 표시 버그\n## 원인\n…", allowed));
        assertEquals("hotfix", IssueTexts.parseWorkType("## 작업 유형\n**hotfix** (운영 장애)\n## 원인\n…", allowed));
        assertEquals("feature", IssueTexts.parseWorkType("## 작업 유형\n기능 추가입니다\n", allowed));
        assertEquals("doc", IssueTexts.parseWorkType("## 작업 유형\ndocs\n", allowed));
        assertNull(IssueTexts.parseWorkType("## 원인\n절이 없음", allowed));
        assertEquals("hotfix", IssueTexts.feedbackTypeLine("설계는 좋은데 작업 유형: hotfix 로 가자"));
        assertEquals("", IssueTexts.feedbackTypeLine("그냥 진행"));
    }

    @Test
    void commitTitleIsConventional() {
        assertEquals("fix: 기준정보 표시", IssueTexts.conventionalTitle("fix: 기준정보 표시", "fix"));
        assertEquals("feat(api): 추가", IssueTexts.conventionalTitle("feat(api): 추가", "feature"));
        assertEquals("fix: 행위 상세 사용자지표 기준정보 사용자명 표시", IssueTexts.conventionalTitle("행위 상세 사용자지표 기준정보 사용자명 표시", "fix"));
        assertEquals("feat: 지표 추가", IssueTexts.conventionalTitle("지표 추가", "feature"));
        assertEquals("docs: 가이드", IssueTexts.conventionalTitle("Docs : 가이드", "doc"));   // 공백 섞인 타입은 정규화
        assertEquals("fix: 긴급", IssueTexts.conventionalTitle("긴급", "hotfix"));
        assertEquals("fix", IssueTexts.commitTypeFor("hotfix"));
    }

    @Test
    void closingKeywordIsDetectedForExactIssueOnly() {
        assertTrue(IssueTexts.closesIssue("fix: 제목\n\nResolves #1", 1));
        assertTrue(IssueTexts.closesIssue("fix: 제목\n본문\nCloses #7", 7));
        assertTrue(IssueTexts.closesIssue("fixes: #3", 3));
        assertFalse(IssueTexts.closesIssue("fix: 제목 (#1)\n\nRefs #1", 1));      // 언급만 — 닫지 않음
        assertFalse(IssueTexts.closesIssue("fix: 제목\n\nResolves #12", 1));      // 다른 번호
        assertFalse(IssueTexts.closesIssue(null, 1));
    }

    @Test
    void sessionDirIsSiblingWorktreeOfBaseClone() {
        GitRunner git = new GitRunner(props(), new GitHubProperties("t", null));
        var base = git.repoDir("grp/sub/repo");
        var dir = git.sessionDir("grp/sub/repo", 12);
        assertEquals(base.getParent(), dir.getParent().getParent());
        assertEquals("grp__sub__repo__wt", dir.getParent().getFileName().toString());
        assertEquals("issue-12", dir.getFileName().toString());
    }

    @Test
    void pushRefusesNonPrefixedBranch() {
        GitRunner git = new GitRunner(props(), new GitHubProperties("t", null));
        assertThrows(IllegalStateException.class,
                () -> git.commitAndPush(java.nio.file.Path.of("."), "main", "msg"));
    }

    @Test
    void repoDirIsSanitized() {
        GitRunner git = new GitRunner(props(), new GitHubProperties("t", null));
        assertEquals("grp__repo", git.repoDir("grp/repo").getFileName().toString());
    }

    @Test
    void selfReplyAcceptsOnlyCommandsAndPrefixedFeedback() {
        assertEquals("진행", IssueTexts.parseSelfReply("진행"));
        assertEquals("승인해줘", IssueTexts.parseSelfReply("승인해줘"));
        assertEquals("중단", IssueTexts.parseSelfReply(" 중단 "));
        assertEquals("DIFF", IssueTexts.parseSelfReply("DIFF"));   // 원문 그대로(정규화는 handleReply 가 다시 함)
        assertEquals("이슈 상태", IssueTexts.parseSelfReply("이슈 상태"));
        assertEquals("null 체크를 빼고 기본값으로", IssueTexts.parseSelfReply("피드백: null 체크를 빼고 기본값으로"));
        assertEquals("두 줄\n피드백", IssueTexts.parseSelfReply("FB: 두 줄\n피드백"));
        assertNull(IssueTexts.parseSelfReply("피드백:"));
        assertNull(IssueTexts.parseSelfReply("(충격_우람)"));
        assertNull(IssueTexts.parseSelfReply("ㅋㅋㅋ"));
        assertNull(IssueTexts.parseSelfReply("오늘 점심 뭐 먹지"));
        assertNull(IssueTexts.parseSelfReply("이 설계 괜찮아 보이는데 진행하자"));   // 문장 속 키워드는 인정하지 않음
        assertNull(IssueTexts.parseSelfReply(""));
    }

    @Test
    void sentenceHeuristicSkipsStickersAndShortNoise() {
        assertFalse(IssueTexts.looksLikeSentence("(충격_우람)"));
        assertFalse(IssueTexts.looksLikeSentence("ㅋㅋㅋ"));
        assertFalse(IssueTexts.looksLikeSentence("ok"));
        assertTrue(IssueTexts.looksLikeSentence("이 설계 괜찮아 보이는데 진행하자"));
        assertTrue(IssueTexts.looksLikeSentence("please change the default value"));
    }
}

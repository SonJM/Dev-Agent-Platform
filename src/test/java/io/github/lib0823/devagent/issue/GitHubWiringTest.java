package io.github.lib0823.devagent.issue;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.IssueProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** git 인증 헤더(GitHub 호스트 한정), 저장소·이슈 참조 해석, 브랜치 충돌 접미사, 주소 파생. */
class GitHubWiringTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값

    @Test
    void authEnvScopesHeaderToGithubHostOnly() {
        Map<String, String> env = GitRunner.authEnv(new GitHubProperties(TOKEN, null));

        assertEquals("1", env.get("GIT_CONFIG_COUNT"));
        assertEquals("http.https://github.com/.extraheader", env.get("GIT_CONFIG_KEY_0"));   // 전역 http.extraheader 아님
        String value = env.get("GIT_CONFIG_VALUE_0");
        assertTrue(value.startsWith("Authorization: Basic "));
        String decoded = new String(Base64.getDecoder().decode(value.substring("Authorization: Basic ".length())), StandardCharsets.UTF_8);
        assertEquals("x-access-token:" + TOKEN, decoded);
        assertEquals(Set.of("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0"), env.keySet());
    }

    @Test
    void authEnvFollowsEnterpriseHostAndIsEmptyWithoutToken() {
        Map<String, String> ghe = GitRunner.authEnv(new GitHubProperties(TOKEN, "https://git.example.com/api/v3"));
        assertEquals("http.https://git.example.com/.extraheader", ghe.get("GIT_CONFIG_KEY_0"));
        assertTrue(GitRunner.authEnv(new GitHubProperties("", null)).isEmpty());
        assertTrue(GitRunner.authEnv(new GitHubProperties("  ", null)).isEmpty());
        assertTrue(GitRunner.authEnv(null).isEmpty());
    }

    @Test
    void propertiesDeriveWebAndCloneUrls() {
        GitHubProperties pub = new GitHubProperties("t", "");
        assertEquals("https://api.github.com", pub.apiBase());
        assertEquals("https://github.com", pub.webBase());
        assertEquals("https://github.com/own/repo.git", pub.cloneUrl("own/repo"));
        assertEquals("https://github.com/own/repo", pub.repoUrl("own/repo"));
        assertEquals("https://git.example.com", new GitHubProperties("t", "https://git.example.com/api/v3/").webBase());
    }

    @Test
    void issueReferencesUseOwnerRepoAndGithubUrls() {
        Matcher url = IssueAgentService.URL_REF.matcher("봐 주세요 https://github.com/own/my-repo/issues/12 입니다");
        assertTrue(url.find());
        assertEquals("own/my-repo", url.group(1));
        assertEquals("12", url.group(2));

        Matcher ref = IssueAgentService.REF.matcher("own/repo#34");
        assertTrue(ref.find());
        assertEquals("own/repo", ref.group(1));
        assertEquals("34", ref.group(2));

        assertFalse(IssueAgentService.URL_REF.matcher("https://github.com/own/repo/pull/12").find());   // PR 주소는 이슈가 아니다
        assertFalse(IssueAgentService.URL_REF.matcher("https://gitea.example/g/p/-/issues/3").find());  // GitHub 형식은 더는 받지 않는다
    }

    /** TC-017: 원격에 같은 이름이 있으면 -ai, 또 있으면 -ai-r<MMddHHmm>. */
    @Test
    void tc017_branchCollisionAddsSuffixes() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 25, 10, 30);

        assertEquals("fix/12", IssueTexts.uniqueBranch("fix/12", b -> false, t));
        assertEquals("fix/12-ai", IssueTexts.uniqueBranch("fix/12", b -> b.equals("fix/12"), t));
        assertEquals("fix/12-ai-r11251030", IssueTexts.uniqueBranch("fix/12", b -> true, t));
    }

    @Test
    void tc017_defaultBranchPushIsRefusedByTheRunner() {
        IssueProperties props = new IssueProperties(true, List.of("own/repo"), "ai-fix", 1000, 1000, "work", "bot", "bot@x",
                true, true, true, 0L, null, null, new IssueProperties.Convention(List.of()), 2);
        GitRunner git = new GitRunner(props, new GitHubProperties(TOKEN, null));

        assertEquals("hotfix/12", git.branchName("hotfix", 12));   // 기본 접두어 허용, hotfix 도 {유형}/{번호}
        assertFalse(git.isPushable("main"));
        assertFalse(git.isPushable("develop"));
    }
}

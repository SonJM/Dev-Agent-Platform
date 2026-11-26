package io.github.lib0823.devagent.ops;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.issue.FakeGitHubServer;
import io.github.lib0823.devagent.issue.FakeGitHubServer.Reply;
import io.github.lib0823.devagent.issue.GitHubClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doctor 의 GitHub 점검: 토큰 없음·401·저장소 404·rate limit·만료 임박/없음/해석 불가. 가짜 HTTP 서버만 쓴다. */
class GitHubCheckTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private static final DateTimeFormatter HEADER_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final String EXP = GitHubClient.EXPIRATION_HEADER;

    private FakeGitHubServer server;

    @BeforeEach
    void setUp() {
        server = new FakeGitHubServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static IssueProperties agent(boolean enabled) {
        return new IssueProperties(enabled, List.of(), "ai-fix", 1000, 1000, "work", "bot", "bot@x", true, true, true, 0L,
                null, null, null, 2);
    }

    private Map<String, Object> run(boolean enabled, String token, List<String> projects) {
        GitHubProperties gh = new GitHubProperties(token, server.url());
        GitHubClient client = new GitHubClient(RestClient.builder(), gh);
        return GitHubCheck.run(agent(enabled), gh, client, projects, NOW);
    }

    private Map<String, Object> runWith(IssueProperties agent, GitHubProperties gh, List<String> projects) {
        return GitHubCheck.run(agent, gh, new GitHubClient(RestClient.builder(), gh), projects, NOW);
    }

    private static IssueProperties agentWithLabel(String label) {
        return new IssueProperties(true, List.of(), label, 1000, 1000, "work", "bot", "bot@x", true, true, true, 0L,
                null, null, null, 2);
    }

    @Test
    void blankLabelIsFailWithHowToFixAndNoScanning() {
        Map<String, Object> c = runWith(agentWithLabel("  "), new GitHubProperties(TOKEN, server.url()), List.of("own/repo"));

        assertEquals("fail", c.get("status"));
        assertTrue(text(c).contains("ISSUE_LABEL"));
        assertTrue(text(c).contains("재시작"));
    }

    @Test
    void localHttpApiUrlIsWarnMentioningHttps() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}"));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));
        assertTrue(text(c).contains("https"));
    }

    @Test
    void nonLocalHttpApiUrlIsFailWithoutCallingAnything() {
        Map<String, Object> c = runWith(agentWithLabel("ai-fix"), new GitHubProperties(TOKEN, "http://git.example.invalid"), List.of("own/repo"));

        assertEquals("fail", c.get("status"));
        assertTrue(text(c).contains("GITHUB_API_URL"));
        assertTrue(text(c).contains("https"));
        assertFalse(text(c).contains(TOKEN));
    }

    private static String header(long days) {
        return HEADER_FORMAT.format(NOW.plus(Duration.ofDays(days)).plus(Duration.ofHours(1)));
    }

    private void healthyRepo() {
        server.on("GET /repos/own/repo", Reply.json(200, "{\"id\": 1}"));
    }

    private static String text(Map<String, Object> c) {
        return c.get("detail") + " | " + c.get("hint");
    }

    @Test
    void disabledIsWarnWithoutCallingGithub() {
        Map<String, Object> c = run(false, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void missingTokenIsFailWithHowToFix() {
        Map<String, Object> c = run(true, "", List.of("own/repo"));

        assertEquals("fail", c.get("status"));
        assertTrue(text(c).contains("GITHUB_TOKEN"));
        assertTrue(text(c).contains("Contents"));
        assertTrue(text(c).contains("Pull requests"));
        assertTrue(text(c).contains("재시작"));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void unauthorizedIsFailAskingForReissue() {
        server.on("GET /user", Reply.json(401, "{\"message\": \"Bad credentials\"}"));

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("fail", c.get("status"));
        assertTrue(text(c).contains("무효") || text(c).contains("만료"));
        assertTrue(text(c).contains("다시 발급"));
        assertTrue(text(c).contains("재시작"));
        assertFalse(text(c).contains(TOKEN));
    }

    @Test
    void repoNotFoundIsFailNamingTheRepoAndMentioningOrganization() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, header(60)))
                .on("GET /repos/own/hidden", Reply.json(404, "{\"message\": \"Not Found\"}"));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo", "own/hidden"));

        assertEquals("fail", c.get("status"));
        assertTrue(c.get("detail").toString().contains("own/hidden"));
        assertFalse(c.get("detail").toString().contains("접근 불가 저장소: own/repo"));
        assertTrue(c.get("hint").toString().contains("저장소 선택"));
        assertTrue(c.get("hint").toString().contains("조직"));
    }

    @Test
    void rateLimitIsWarnNotFail() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, header(60)))
                .on("GET /repos/own/repo", Reply.json(403, "{\"message\": \"API rate limit exceeded\"}").header("x-ratelimit-remaining", "0"));

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));
        assertTrue(c.get("detail").toString().contains("한도"));
    }

    @Test
    void rateLimitOnUserProbeIsWarn() {
        server.on("GET /user", Reply.json(429, "{\"message\": \"slow down\"}"));

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));
    }

    @Test
    void expiryWithinFourteenDaysIsWarnWithRemainingDays() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, header(14)));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));
        assertTrue(c.get("detail").toString().contains("남은 14일"));
        assertTrue(c.get("hint").toString().contains("재발급"));
        assertTrue(c.get("hint").toString().contains("재시작"));
    }

    @Test
    void expiryBeyondThresholdIsOkAndShowsDate() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, header(15)));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));                 // 시험용 가짜 서버가 로컬 http 라서 그 경고만 남는다
        assertTrue(c.get("detail").toString().contains("남은 15일"));
        assertTrue(c.get("detail").toString().contains("읽기"));
        assertTrue(c.get("hint").toString().contains("https"));
    }

    @Test
    void missingExpiryHeaderShowsNoExpiryAndIsOk() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}"));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));                 // 로컬 http 경고만
        assertTrue(c.get("detail").toString().contains("만료일 없음"));
    }

    @Test
    void unreadableExpiryHeaderIsWarnWithoutException() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, "someday-soon"));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("warn", c.get("status"));
        assertTrue(c.get("detail").toString().contains("읽지 못했습니다"));
    }

    @Test
    void alreadyExpiredDateIsFail() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, header(-3)));
        healthyRepo();

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertEquals("fail", c.get("status"));
    }

    @Test
    void noTargetReposIsWarn() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(EXP, header(60)));

        Map<String, Object> c = run(true, TOKEN, List.of());

        assertEquals("warn", c.get("status"));
        assertTrue(text(c).contains("owner/repo"));
    }

    @Test
    void resultNeverContainsTokenOrAuthorizationHeader() {
        server.on("GET /user", Reply.json(401, "{\"message\": \"bad " + TOKEN + "\"}"));

        Map<String, Object> c = run(true, TOKEN, List.of("own/repo"));

        assertFalse(c.toString().contains(TOKEN));
        assertFalse(c.toString().toLowerCase().contains("bearer"));
    }

    @Test
    void expiryHeaderParsesUtcAndIsoFormats() {
        assertEquals(Instant.parse("2026-12-31T03:46:04Z"), GitHubCheck.parseExpiry("2026-12-31 03:46:04 UTC").orElseThrow());
        assertEquals(Instant.parse("2026-12-31T03:46:04Z"), GitHubCheck.parseExpiry("2026-12-31T03:46:04Z").orElseThrow());
        assertTrue(GitHubCheck.parseExpiry("몰라").isEmpty());
        assertEquals(14, GitHubCheck.TOKEN_EXPIRY_WARN_DAYS);
    }
}

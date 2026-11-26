package io.github.lib0823.devagent.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.gitlab.FakeGitHubServer.Reply;
import io.github.lib0823.devagent.gitlab.GitHubException.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-015(저장소·라벨 이슈·401), TC-016(Draft PR). 가짜 HTTP 서버만 쓴다(실제 GitHub 호출 없음). */
class GitHubClientTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeGitHubServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        server = new FakeGitHubServer();
        client = new GitHubClient(RestClient.builder(), new GitHubProperties(TOKEN, server.url()));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void tc015_projectResolvesDefaultBranchAndUrls() {
        server.on("GET /repos/own/repo", Reply.json(200, """
                {"id": 42, "full_name": "own/repo", "default_branch": "trunk",
                 "clone_url": "https://github.com/own/repo.git", "html_url": "https://github.com/own/repo"}"""));

        GitHubClient.Project p = client.project("own/repo");

        assertEquals("own/repo", p.path());
        assertEquals("trunk", p.defaultBranch());
        assertEquals("https://github.com/own/repo.git", p.httpUrl());
        assertEquals("https://github.com/own/repo", p.webUrl());
        assertEquals("Bearer " + TOKEN, server.last().authorization());
    }

    @Test
    void tc015_openIssuesExcludesPullRequestsAndSendsLabel() {
        server.on("GET /repos/own/repo/issues", Reply.json(200, """
                [{"number": 1, "title": "이슈", "body": null, "html_url": "https://github.com/own/repo/issues/1",
                  "labels": [{"name": "ai-fix"}, {"name": "bug"}], "user": {"login": "alice"}, "created_at": "2026-01-01T00:00:00Z"},
                 {"number": 2, "title": "PR 입니다", "html_url": "https://github.com/own/repo/pull/2",
                  "pull_request": {"url": "x"}, "labels": [{"name": "ai-fix"}], "user": {"login": "bob"}},
                 {"number": 3, "title": "다음 이슈", "body": "본문", "html_url": "https://github.com/own/repo/issues/3",
                  "labels": [], "user": {"login": "carol"}}]"""));

        List<GitHubClient.Issue> issues = client.openIssues("own/repo", "ai-fix");

        assertEquals(List.of(1L, 3L), issues.stream().map(GitHubClient.Issue::iid).toList());   // PR(#2) 제외
        assertEquals("", issues.get(0).description());                                         // body: null → 빈 문자열
        assertEquals(List.of("ai-fix", "bug"), issues.get(0).labels());
        assertEquals("alice", issues.get(0).author());
        assertTrue(server.last().pathAndQuery().contains("state=open"));
        assertTrue(server.last().pathAndQuery().contains("labels=ai-fix"));
    }

    @Test
    void tc015_issueThatIsPullRequestIsRejected() {
        server.on("GET /repos/own/repo/issues/9", Reply.json(200,
                "{\"number\": 9, \"title\": \"PR\", \"pull_request\": {\"url\": \"x\"}}"));

        assertThrows(IllegalStateException.class, () -> client.issue("own/repo", 9));
    }

    @Test
    void tc015_unauthorizedShowsCauseWithoutToken() {
        server.on("GET /repos/own/repo", Reply.json(401, "{\"message\": \"Bad credentials\"}"));

        GitHubException e = assertThrows(GitHubException.class, () -> client.project("own/repo"));

        assertEquals(Kind.UNAUTHORIZED, e.kind());
        assertEquals(401, e.status());
        assertTrue(e.getMessage().contains("401"));
        assertTrue(e.getMessage().contains("Bad credentials"));
        assertFalse(e.getMessage().contains(TOKEN));
    }

    @Test
    void tc015_tokenEchoedByServerIsMasked() {
        server.on("GET /repos/own/repo", Reply.json(401, "{\"message\": \"invalid " + TOKEN + " given\"}"));

        GitHubException e = assertThrows(GitHubException.class, () -> client.project("own/repo"));

        assertFalse(e.getMessage().contains(TOKEN));
        assertTrue(e.getMessage().contains("***"));
    }

    @Test
    void tc015_notFoundAndForbiddenAreNotAccessibleButRateLimitIsSeparate() {
        server.on("GET /repos/own/hidden", Reply.json(404, "{\"message\": \"Not Found\"}"))
                .on("GET /repos/own/limited", Reply.json(403, "{\"message\": \"API rate limit exceeded\"}")
                        .header("x-ratelimit-remaining", "0"))
                .on("GET /repos/own/secondary", Reply.json(429, "{\"message\": \"slow down\"}"))
                .on("GET /repos/own/denied", Reply.json(403, "{\"message\": \"Resource not accessible by personal access token\"}"));

        assertEquals(Kind.NOT_ACCESSIBLE, assertThrows(GitHubException.class, () -> client.project("own/hidden")).kind());
        assertEquals(Kind.RATE_LIMITED, assertThrows(GitHubException.class, () -> client.project("own/limited")).kind());
        assertEquals(Kind.RATE_LIMITED, assertThrows(GitHubException.class, () -> client.project("own/secondary")).kind());
        assertEquals(Kind.NOT_ACCESSIBLE, assertThrows(GitHubException.class, () -> client.project("own/denied")).kind());
    }

    @Test
    void branchExistsIsFalseOn404() {
        server.on("GET /repos/own/repo/branches/main", Reply.json(200, "{\"name\": \"main\"}"));

        assertTrue(client.branchExists("own/repo", "main"));
        assertFalse(client.branchExists("own/repo", "nope"));
        assertFalse(client.branchExists("own/repo", " "));
    }

    @Test
    void branchesAreFilteredBySearch() {
        server.on("GET /repos/own/repo/branches", Reply.json(200, "[{\"name\":\"main\"},{\"name\":\"feature/Login\"},{\"name\":\"fix/1\"}]"));

        assertEquals(List.of("feature/Login"), client.branches("own/repo", "login"));
        assertEquals(3, client.branches("own/repo", null).size());
    }

    @Test
    void createIssueSendsLabelsAsArray() throws Exception {
        server.on("POST /repos/own/repo/issues", Reply.json(201,
                "{\"number\": 5, \"title\": \"t\", \"html_url\": \"https://github.com/own/repo/issues/5\", \"labels\": [{\"name\":\"ai-fix\"}]}"));

        GitHubClient.Issue created = client.createIssue("own/repo", "t", "본문", "ai-fix, bug");

        assertEquals(5, created.iid());
        JsonNode body = JSON.readTree(server.last().body());
        assertEquals("t", body.get("title").asText());
        assertEquals("본문", body.get("body").asText());
        assertEquals(List.of("ai-fix", "bug"), List.of(body.get("labels").get(0).asText(), body.get("labels").get(1).asText()));
    }

    @Test
    void issueNotesAndCommentUseIssueEndpoints() throws Exception {
        server.on("GET /repos/own/repo/issues/7/comments", Reply.json(200, "[{\"user\": {\"login\": \"alice\"}, \"author_association\": \"OWNER\", \"body\": \"재현됨\"}]"))
                .on("POST /repos/own/repo/issues/7/comments", Reply.json(201, "{\"id\": 1}"));

        assertEquals(List.of("alice: 재현됨"), client.issueNotes("own/repo", 7, 30));
        client.comment("own/repo", 7, "푸시했습니다");

        assertEquals("푸시했습니다", JSON.readTree(server.last().body()).get("body").asText());
    }

    @Test
    void tc016_pullRequestIsDraftAndTargetsGivenBase() throws Exception {
        server.on("POST /repos/own/repo/pulls", Reply.json(201, "{\"html_url\": \"https://github.com/own/repo/pull/8\"}"));

        String url = client.createPullRequest("own/repo", "fix/12", "trunk", "Draft: fix: 제목", "본문");

        assertEquals("https://github.com/own/repo/pull/8", url);
        JsonNode body = JSON.readTree(server.last().body());
        assertTrue(body.get("draft").asBoolean());
        assertEquals("trunk", body.get("base").asText());
        assertEquals("fix/12", body.get("head").asText());
        assertEquals("Draft: fix: 제목", body.get("title").asText());
    }

    @Test
    void tc016_pullRequestFailure422KeepsCauseAndThrows() {
        server.on("POST /repos/own/repo/pulls", Reply.json(422,
                "{\"message\": \"Validation Failed\", \"errors\": [{\"message\": \"A pull request already exists\"}]}"));

        GitHubException e = assertThrows(GitHubException.class,
                () -> client.createPullRequest("own/repo", "fix/12", "main", "t", "b"));

        assertEquals(Kind.REJECTED, e.kind());
        assertEquals(422, e.status());
        assertTrue(e.getMessage().contains("Validation Failed"));
        assertFalse(e.getMessage().contains(TOKEN));
    }

    @Test
    void probeReportsExpirationHeaderAndNeverThrows() {
        server.on("GET /user", Reply.json(200, "{\"login\": \"alice\"}").header(GitHubClient.EXPIRATION_HEADER, "2026-12-31 03:46:04 UTC"))
                .on("GET /repos/own/gone", Reply.json(404, "{\"message\": \"Not Found\"}"));

        GitHubClient.Probe ok = client.probe("/user");
        GitHubClient.Probe gone = client.probe("/repos/own/gone");

        assertTrue(ok.ok());
        assertEquals("2026-12-31 03:46:04 UTC", ok.tokenExpiration());
        assertFalse(gone.ok());
        assertEquals(Kind.NOT_ACCESSIBLE, gone.kind());
        assertNull(gone.tokenExpiration());
    }

    @Test
    void requestsTimeOutInsteadOfHangingForever() {
        server.on("GET /repos/own/slow", Reply.json(200, "{\"id\": 1}").delayed(3_000));
        GitHubClient fast = GitHubClient.withTimeouts(RestClient.builder(), new GitHubProperties(TOKEN, server.url()), 300, 300);

        long t0 = System.nanoTime();
        GitHubException e = assertThrows(GitHubException.class, () -> fast.project("own/slow"));

        assertEquals(Kind.NETWORK, e.kind());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_500, "제한 시간 안에 실패해야 한다");
        assertFalse(e.getMessage().contains(TOKEN));
    }

    @Test
    void repoNameIsValidatedBeforeAnyCall() {
        assertThrows(IllegalArgumentException.class, () -> client.project("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> client.project("justname"));
        assertThrows(IllegalArgumentException.class, () -> client.project("a/b/c"));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void noAuthorizationHeaderWhenTokenIsEmpty() {
        server.on("GET /repos/own/repo", Reply.json(200, "{\"id\": 1, \"full_name\": \"own/repo\"}"));
        GitHubClient anonymous = new GitHubClient(RestClient.builder(), new GitHubProperties("", server.url()));

        anonymous.project("own/repo");

        assertNull(server.last().authorization());
    }
}

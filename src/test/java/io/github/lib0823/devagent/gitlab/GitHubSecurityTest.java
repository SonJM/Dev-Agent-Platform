package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.gitlab.FakeGitHubServer.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 보안 재작업: 신뢰할 수 없는 작성자 걸러내기, 참고 저장소 대상 제한, 응답 크기 상한, api-url 정책, 경로 세그먼트. */
class GitHubSecurityTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값

    private FakeGitHubServer server;

    @BeforeEach
    void setUp() {
        server = new FakeGitHubServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private GitHubClient client(boolean trustedOnly) {
        return new GitHubClient(RestClient.builder(), new GitHubProperties(TOKEN, server.url(), trustedOnly));
    }

    // ---- MEDIUM 1: author_association ----

    @Test
    void trustedAssociationsAreOwnerMemberCollaboratorOnly() {
        assertTrue(GitHubClient.isTrustedAssociation("OWNER"));
        assertTrue(GitHubClient.isTrustedAssociation("member"));
        assertTrue(GitHubClient.isTrustedAssociation("COLLABORATOR"));
        assertFalse(GitHubClient.isTrustedAssociation("CONTRIBUTOR"));
        assertFalse(GitHubClient.isTrustedAssociation("NONE"));
        assertFalse(GitHubClient.isTrustedAssociation("FIRST_TIMER"));
        assertFalse(GitHubClient.isTrustedAssociation(null));
        assertFalse(GitHubClient.isTrustedAssociation(""));
    }

    @Test
    void issueCarriesAuthorAssociation() {
        server.on("GET /repos/own/repo/issues/3", Reply.json(200, """
                {"number": 3, "title": "t", "body": "b", "html_url": "u", "labels": [], "user": {"login": "x"},
                 "author_association": "NONE", "created_at": "2026-01-01T00:00:00Z"}"""));

        GitHubClient.Issue is = client(true).issue("own/repo", 3);

        assertEquals("NONE", is.authorAssociation());
    }

    private void notes() {
        server.on("GET /repos/own/repo/issues/3/comments", Reply.json(200, """
                [{"user": {"login": "owner"}, "author_association": "OWNER", "body": "믿는 글"},
                 {"user": {"login": "stranger"}, "author_association": "NONE", "body": "주입 시도"},
                 {"user": {"login": "contrib"}, "author_association": "CONTRIBUTOR", "body": "기여자 글"},
                 {"user": {"login": "member"}, "author_association": "MEMBER", "body": "멤버 글"}]"""));
    }

    @Test
    void issueNotesKeepOnlyTrustedAuthorsByDefault() {
        notes();

        List<String> out = client(true).issueNotes("own/repo", 3, 30);

        assertEquals(List.of("owner: 믿는 글", "member: 멤버 글"), out);
    }

    @Test
    void issueNotesKeepEverythingWhenTrustCheckIsOff() {
        notes();

        assertEquals(4, client(false).issueNotes("own/repo", 3, 30).size());
    }

    @Test
    void issueAuthorTrustRequirementFollowsSetting() {
        assertTrue(client(true).requiresTrustedAuthor());
        assertFalse(client(false).requiresTrustedAuthor());
        assertTrue(new GitHubProperties(TOKEN, null).trustedAuthorsOnly());   // 두 인자 생성자는 기본 켜짐
    }

    // ---- MEDIUM 2: 참고 저장소는 대상 프로젝트만 ----

    @Test
    void referenceRepoMustBeInTargetProjects() {
        List<String> targets = List.of("own/repo", "Own/Other");

        assertTrue(IssueReferences.isTarget(targets, "own/other"));
        assertTrue(IssueReferences.isTarget(targets, "own/repo"));
        assertFalse(IssueReferences.isTarget(targets, "evil/repo"));
        assertFalse(IssueReferences.isTarget(List.of(), "own/repo"));
        assertFalse(IssueReferences.isTarget(null, "own/repo"));
    }

    // ---- MEDIUM 3: 라벨이 비면 처리하지 않는다 ----

    @Test
    void blankLabelMeansNoProcessing() {
        assertFalse(IssueTexts.hasScanLabel(null));
        assertFalse(IssueTexts.hasScanLabel("  "));
        assertTrue(IssueTexts.hasScanLabel("ai-fix"));
    }

    // ---- LOW: 경로 세그먼트, 응답 상한, api-url ----

    @Test
    void ownerRepoRejectsDotSegments() {
        assertThrows(IllegalArgumentException.class, () -> GitHubClient.ownerRepo("../repo"));
        assertThrows(IllegalArgumentException.class, () -> GitHubClient.ownerRepo("own/.."));
        assertThrows(IllegalArgumentException.class, () -> GitHubClient.ownerRepo("./."));
        assertEquals("own", GitHubClient.ownerRepo("own/repo.v2")[0]);
    }

    @Test
    void probeRepoRejectsMalformedRepoWithoutCallingGithub() {
        GitHubClient.Probe p = client(true).probeRepo("own/repo/../../user");

        assertFalse(p.ok());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void oversizedResponseIsRejected() {
        String huge = "[" + "1,".repeat(GitHubClient.MAX_RESPONSE_BYTES / 2 + 10) + "1]";
        server.on("GET /repos/own/repo/issues/3/comments", Reply.json(200, huge));

        GitHubException e = assertThrows(GitHubException.class, () -> client(true).issueNotes("own/repo", 3, 30));

        assertEquals(GitHubException.Kind.NETWORK, e.kind());
    }

    @Test
    void apiUrlPolicy() {
        assertEquals(GitHubProperties.ApiUrlPolicy.OK, new GitHubProperties(TOKEN, null).apiUrlPolicy());
        assertEquals(GitHubProperties.ApiUrlPolicy.OK, new GitHubProperties(TOKEN, "https://git.example.com/api/v3").apiUrlPolicy());
        assertEquals(GitHubProperties.ApiUrlPolicy.LOCAL_HTTP, new GitHubProperties(TOKEN, "http://127.0.0.1:1234").apiUrlPolicy());
        assertEquals(GitHubProperties.ApiUrlPolicy.LOCAL_HTTP, new GitHubProperties(TOKEN, "http://localhost:1234").apiUrlPolicy());
        assertEquals(GitHubProperties.ApiUrlPolicy.INSECURE, new GitHubProperties(TOKEN, "http://git.example.com").apiUrlPolicy());
        assertEquals(GitHubProperties.ApiUrlPolicy.INSECURE, new GitHubProperties(TOKEN, "ftp://git.example.com").apiUrlPolicy());
    }

    @Test
    void insecureApiUrlNeverReceivesTokenOrRequests() {
        GitHubProperties insecure = new GitHubProperties(TOKEN, "http://git.example.invalid");
        GitHubClient c = new GitHubClient(RestClient.builder(), insecure);

        GitHubException e = assertThrows(GitHubException.class, () -> c.project("own/repo"));

        assertEquals(GitHubException.Kind.NETWORK, e.kind());
        assertTrue(e.getMessage().contains("https"));
        assertTrue(GitRunner.authEnv(insecure).isEmpty());
    }
}

package io.github.lib0823.devagent.issue;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.issue.FakeGitHubServer.Reply;
import io.github.lib0823.devagent.llm.UsageService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.ops.AlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-030(T-032): 대상 아닌 내 저장소의 이슈 목록 조회와 「시작 = 검사 → 대상 추가 → 시작」. 가짜 HTTP 서버와 메모리 세션 저장소만 쓴다.
 */
class IssueStartTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값
    private static final String REPOS_KEY = "GET /user/repos?affiliation=owner&sort=updated&per_page=100&page=1";

    private FakeGitHubServer server;
    private List<String> store;
    private Map<String, IssueSession> sessionStore;
    private IssueAgentService service;
    private IssueProperties props;
    private IssueSessionMapper sessionsMapper;
    private IssueEventBus eventBus;

    @BeforeEach
    void setUp() {
        server = new FakeGitHubServer();
        store = new ArrayList<>();
        sessionStore = new LinkedHashMap<>();
        service = newService(TOKEN);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        server.close();
    }

    private IssueAgentService newService(String token) {
        GitHubClient client = new GitHubClient(RestClient.builder(), new GitHubProperties(token, server.url()));
        SettingService settings = mock(SettingService.class);
        when(settings.getIssueProjects()).thenAnswer(inv -> new ArrayList<>(store));
        doAnswer(inv -> {
            store = new ArrayList<>(inv.<List<String>>getArgument(0));
            return null;
        }).when(settings).setIssueProjects(anyList());
        props = mock(IssueProperties.class);
        when(props.enabled()).thenReturn(true);
        when(props.concurrencyOr()).thenReturn(1);
        when(props.label()).thenReturn("ai-fix");
        IssueSessionMapper sessions = mock(IssueSessionMapper.class);
        AtomicLong ids = new AtomicLong();
        when(sessions.findByIssue(anyString(), anyLong())).thenAnswer(inv -> sessionStore.get(inv.getArgument(0) + "#" + inv.getArgument(1)));
        when(sessions.insertIgnore(any())).thenAnswer(inv -> {
            IssueSession s = inv.getArgument(0);
            if (sessionStore.containsKey(s.ref())) {
                return 0;
            }
            s.setId(ids.incrementAndGet());
            sessionStore.put(s.ref(), s);
            return 1;
        });
        when(sessions.findActiveAll()).thenAnswer(inv -> sessionStore.values().stream().filter(IssueSession::isActive).toList());
        // 조건부 갱신(restartIfFinished)을 메모리에서 흉내: 종료 상태일 때만 NEW 로 되돌리고 1, 아니면 0
        when(sessions.restartIfFinished(anyLong(), anyString())).thenAnswer(inv -> {
            IssueSession s = sessionStore.values().stream().filter(x -> x.getId() == inv.<Long>getArgument(0)).findFirst().orElse(null);
            if (s == null || s.isActive()) {
                return 0;
            }
            s.setState(IssueSession.NEW);
            s.setClaudeSessionId(null);
            s.setLastError(null);
            s.setIssueTitle(inv.getArgument(1));
            return 1;
        });
        sessionsMapper = sessions;
        IssueProjects projects = new IssueProjects(props, client, settings, sessions);
        return new IssueAgentService(props, client, mock(GitRunner.class), mock(CodingAgent.class), sessions,
                mock(MessengerService.class), mock(AlertService.class), settings, eventBus = mock(IssueEventBus.class), mock(VerifyService.class),
                mock(IssueReferences.class), projects, mock(IssueNotifier.class), mock(IssuePrompts.class), mock(UsageService.class), 0);
    }

    private static String repoJson(String path, String ownerType, boolean archived) {
        return "{\"full_name\":\"" + path + "\",\"default_branch\":\"main\",\"private\":true,\"archived\":" + archived
                + ",\"fork\":false,\"updated_at\":\"2026-11-20T01:02:03Z\",\"html_url\":\"https://github.com/" + path
                + "\",\"owner\":{\"type\":\"" + ownerType + "\"}}";
    }

    private void stubRepos(String... repoJsons) {
        server.on(REPOS_KEY, Reply.json(200, "[" + String.join(",", repoJsons) + "]"));
    }

    private void stubProject(String path, String ownerType) {
        server.on("GET /repos/" + path, Reply.json(200, "{\"id\":7,\"full_name\":\"" + path
                + "\",\"default_branch\":\"main\",\"owner\":{\"type\":\"" + ownerType + "\"}}"));
    }

    private static String issueJson(long n, String state, String assoc, boolean pr, String labels) {
        return "{\"number\":" + n + ",\"title\":\"제목 " + n + "\",\"body\":\"본문\",\"state\":\"" + state
                + "\",\"html_url\":\"https://github.com/x/y/issues/" + n + "\",\"labels\":[" + labels
                + "],\"user\":{\"login\":\"writer\"},\"author_association\":\"" + assoc + "\"" + (pr ? ",\"pull_request\":{}" : "") + "}";
    }

    private void stubIssue(String path, long n, String json) {
        server.on("GET /repos/" + path + "/issues/" + n, Reply.json(200, json));
    }

    private long countRequests(String prefix) {
        return server.requests().stream().filter(r -> r.pathAndQuery().startsWith(prefix)).count();
    }

    private static void assertNoToken(Map<String, Object> res) {
        assertFalse(String.valueOf(res).contains(TOKEN), res.toString());
    }

    // ---- ① issues-list ----

    @Test
    void tc030_1_listTargetRepoHasTargetTrueAndBlockedMarkOnlyOnUntrustedIssue() {
        store.add("me/mine");
        stubProject("me/mine", "User");
        server.on("GET /repos/me/mine/issues", Reply.json(200, "[" + issueJson(1, "open", "OWNER", false, "")
                + "," + issueJson(2, "open", "NONE", false, "") + "," + issueJson(3, "open", "OWNER", true, "") + "]"));

        Map<String, Object> res = service.openIssueList("me/mine");

        assertEquals(true, res.get("ok"));
        assertEquals(true, res.get("target"));
        assertEquals(false, res.get("archived"));
        List<Map<String, Object>> issues = issuesOf(res);
        assertEquals(2, issues.size());                                   // PR 제외
        assertFalse(issues.get(0).containsKey("blocked"));                // ② 처리 가능하면 넣지 않음
        assertTrue(String.valueOf(issues.get(1).get("blocked")).contains("OWNER/MEMBER/COLLABORATOR"));
        assertNoToken(res);
    }

    @Test
    void tc030_1_listOwnRepoNotTargetWorksAndKeepsTargetListAndUsesRepoCache() {
        stubRepos(repoJson("me/Other", "User", false));
        server.on("GET /repos/me/Other/issues", Reply.json(200, "[" + issueJson(5, "open", "OWNER", false, "") + "]"));

        Map<String, Object> first = service.openIssueList("me/other");     // 대소문자 무시
        Map<String, Object> second = service.openIssueList("ME/OTHER");

        assertEquals(true, first.get("ok"));
        assertEquals(false, first.get("target"));
        assertEquals("me/Other", first.get("project"));                  // 정규 경로
        assertEquals(true, second.get("ok"));
        assertEquals(1, countRequests("/user/repos"));                    // 목록 확인은 60초 캐시
        assertTrue(store.isEmpty());                                      // 조회만으로 대상이 늘지 않는다
        assertEquals(0, countRequests("/repos/me/Other") - countRequests("/repos/me/Other/issues"));   // 저장소 단건 조회(projectCache) 없음
    }

    @Test
    void tc030_1_listOtherPersonsRepoRejectedWithoutIssueLookup() {
        stubRepos(repoJson("me/mine", "User", false));

        Map<String, Object> res = service.openIssueList("someone/else");

        assertEquals(false, res.get("ok"));
        assertTrue(String.valueOf(res.get("result")).contains("someone/else"));
        assertEquals(0, countRequests("/repos/someone"));
    }

    @Test
    void tc030_1_listReportsRepoListFailureReasonWhenNoToken() {
        service.shutdown();
        service = newService("");

        Map<String, Object> res = service.openIssueList("me/other");

        assertEquals(false, res.get("ok"));
        assertTrue(String.valueOf(res.get("result")).contains("GITHUB_TOKEN"));
        assertEquals(0, server.requests().size());
    }

    @Test
    void tc030_1_listArchivedFlagComesFromRepoList() {
        stubRepos(repoJson("me/old", "User", true));
        server.on("GET /repos/me/old/issues", Reply.json(200, "[]"));

        Map<String, Object> res = service.openIssueList("me/old");

        assertEquals(true, res.get("ok"));
        assertEquals(true, res.get("archived"));
    }

    // ---- ③ issues-start 정상 ----

    @Test
    void tc030_3_startOnNonTargetOwnRepoAddsTargetAndQueuesSession() {
        stubRepos(repoJson("me/other", "User", false));
        stubProject("me/other", "User");
        stubIssue("me/other", 5, issueJson(5, "open", "OWNER", false, ""));
        server.on("GET /repos/me/other/issues", Reply.json(200, "[]"));

        Map<String, Object> res = service.startIssue("me/other", 5);

        assertEquals(true, res.get("ok"));
        assertEquals(true, res.get("added"));
        assertEquals("me/other#5", res.get("ref"));
        assertEquals(List.of("me/other"), store);
        assertEquals(1, sessionStore.size());
        assertEquals(IssueSession.NEW, sessionStore.get("me/other#5").getState());
        assertNoToken(res);
    }

    // ---- ③ issues-start 실패: 대상 목록이 그대로여야 한다 ----

    @Test
    void tc030_3_startRejectsArchivedRepoAndKeepsTargets() {
        store.add("me/keep");
        stubRepos(repoJson("me/old", "User", true));

        Map<String, Object> res = service.startIssue("me/old", 1);

        assertRejected(res, "보관");
        assertEquals(0, countRequests("/repos/me/old"));
    }

    @Test
    void tc030_3_startRejectsMissingIssue() {
        store.add("me/keep");
        stubRepos(repoJson("me/other", "User", false));        // 이슈 응답 없음 → 가짜 서버 404

        assertRejected(service.startIssue("me/other", 99), "이슈 조회 실패");
    }

    @Test
    void tc030_3_startRejectsClosedIssue() {
        stubRepos(repoJson("me/other", "User", false));
        stubIssue("me/other", 5, issueJson(5, "closed", "OWNER", false, ""));

        assertRejected(service.startIssue("me/other", 5), "닫힌");
    }

    @Test
    void tc030_3_startRejectsPullRequest() {
        stubRepos(repoJson("me/other", "User", false));
        stubIssue("me/other", 5, issueJson(5, "open", "OWNER", true, ""));

        assertRejected(service.startIssue("me/other", 5), "풀 리퀘스트");
    }

    @Test
    void tc030_3_startRejectsUntrustedAuthor() {
        stubRepos(repoJson("me/other", "User", false));
        stubIssue("me/other", 5, issueJson(5, "open", "NONE", false, ""));

        assertRejected(service.startIssue("me/other", 5), "OWNER/MEMBER/COLLABORATOR");
    }

    @Test
    void tc030_3_startRejectsIssueWithActiveSession() {
        stubRepos(repoJson("me/other", "User", false));
        stubIssue("me/other", 5, issueJson(5, "open", "OWNER", false, ""));
        IssueSession running = new IssueSession();
        running.setProjectPath("me/other");
        running.setIssueNumber(5);
        running.setState(IssueSession.ANALYZING);
        sessionStore.put("me/other#5", running);

        assertRejected(service.startIssue("me/other", 5), "이미 진행 중");
    }

    @Test
    void tc030_3_startRejectsOrganizationOwnedRepoEvenIfListSaysUser() {
        stubRepos(repoJson("me/other", "User", false));
        stubProject("me/other", "Organization");
        stubIssue("me/other", 5, issueJson(5, "open", "OWNER", false, ""));

        assertRejected(service.startIssue("me/other", 5), "조직");
        assertTrue(sessionStore.isEmpty());
    }

    @Test
    void tc030_3_startRejectsWhenTargetLimitReached() {
        for (int i = 0; i < IssueProjects.MAX_PROJECTS; i++) {
            store.add("me/t" + i);
        }
        stubRepos(repoJson("me/other", "User", false));
        stubProject("me/other", "User");
        stubIssue("me/other", 5, issueJson(5, "open", "OWNER", false, ""));

        Map<String, Object> res = service.startIssue("me/other", 5);

        assertRejected(res, "최대 50");
        assertEquals(IssueProjects.MAX_PROJECTS, store.size());
        assertTrue(sessionStore.isEmpty());
    }

    @Test
    void tc030_3_startRejectsOtherPersonsRepoAndBadNumber() {
        stubRepos(repoJson("me/mine", "User", false));

        assertRejected(service.startIssue("someone/else", 1), "someone/else");
        assertRejected(service.startIssue("me/mine", 0), "번호");
        assertRejected(service.startIssue("not-a-path", 1), "not-a-path");
        assertEquals(0, countRequests("/repos/"));
    }

    @Test
    void tc030_3_startWithoutTokenFailsWithReasonAndKeepsTargets() {
        service.shutdown();
        service = newService("");
        store.add("me/keep");

        Map<String, Object> res = service.startIssue("me/other", 5);

        assertRejected(res, "GITHUB_TOKEN");
        assertNoToken(res);
    }

    // ---- ④ 대상 저장소 ----

    @Test
    void tc030_4_startOnTargetRepoDoesNotAddAndRestartResetsFinishedSession() {
        store.add("me/mine");
        stubProject("me/mine", "User");
        stubIssue("me/mine", 7, issueJson(7, "open", "OWNER", false, ""));

        Map<String, Object> first = service.startIssue("me/mine", 7);
        assertEquals(true, first.get("ok"));
        assertEquals(false, first.get("added"));
        assertEquals(List.of("me/mine"), store);

        IssueSession s = sessionStore.get("me/mine#7");
        s.setState(IssueSession.DONE);
        s.setLastError("old");
        Map<String, Object> again = service.startIssue("me/mine", 7);

        assertEquals(true, again.get("ok"));
        assertEquals(false, again.get("added"));
        assertEquals(IssueSession.NEW, s.getState());
        assertNull(s.getLastError());
        assertEquals(1, sessionStore.size());
    }

    // ---- ⑤ 스캔과 겹쳐도 세션은 하나 ----

    @Test
    void tc030_5_scanAfterAddPicksSameIssueButSessionStaysSingle() throws Exception {
        stubRepos(repoJson("me/other", "User", false));
        stubProject("me/other", "User");
        String labeled = issueJson(5, "open", "OWNER", false, "{\"name\":\"ai-fix\"}");
        stubIssue("me/other", 5, labeled);
        server.on("GET /repos/me/other/issues", Reply.json(200, "[" + labeled + "]"));

        Map<String, Object> res = service.startIssue("me/other", 5);
        service.scan();                                                   // 추가 직후 걸린 스캔과 같은 일을 직접 한 번 더

        assertEquals(true, res.get("ok"));
        assertEquals(1, sessionStore.size());
        assertEquals(1, countStarted());
    }

    // ---- R1: 종료 세션 재시작은 조건부 갱신 ----

    @Test
    void tc030_r1_restartRejectedWhenConditionalUpdateHitsNothing() {
        store.add("me/mine");
        stubProject("me/mine", "User");
        stubIssue("me/mine", 7, issueJson(7, "open", "OWNER", false, ""));
        IssueSession s = new IssueSession();
        s.setId(9);
        s.setProjectPath("me/mine");
        s.setIssueNumber(7);
        s.setState(IssueSession.DONE);
        s.setClaudeSessionId("keep-me");
        sessionStore.put("me/mine#7", s);
        when(sessionsMapper.restartIfFinished(anyLong(), anyString())).thenReturn(0);   // 그 사이 다른 요청이 먼저 재시작

        Map<String, Object> res = service.startIssue("me/mine", 7);

        assertEquals(false, res.get("ok"));
        assertTrue(String.valueOf(res.get("result")).contains("이미 진행 중"), res.toString());
        verify(eventBus, never()).clear(anyLong());
        assertEquals("keep-me", s.getClaudeSessionId());
        verify(sessionsMapper, never()).update(any());
    }

    @Test
    void tc030_r1_restartFinishedSessionWhenConditionalUpdateHits() {
        store.add("me/mine");
        stubProject("me/mine", "User");
        stubIssue("me/mine", 7, issueJson(7, "open", "OWNER", false, ""));
        IssueSession s = new IssueSession();
        s.setId(9);
        s.setProjectPath("me/mine");
        s.setIssueNumber(7);
        s.setState(IssueSession.FAILED);
        sessionStore.put("me/mine#7", s);

        Map<String, Object> res = service.startIssue("me/mine", 7);

        assertEquals(true, res.get("ok"), res.toString());
        assertEquals(IssueSession.NEW, s.getState());
        verify(eventBus).clear(9L);
        verify(sessionsMapper, never()).update(any());
    }

    // ---- R2: 목록 조회 실패도 30초 캐시 ----

    @Test
    void tc030_r2_repoListFailureIsCachedForThirtySeconds() {
        server.on(REPOS_KEY, Reply.json(500, "{\"message\":\"boom\"}"));

        Map<String, Object> a = service.openIssueList("me/other");
        Map<String, Object> b = service.openIssueList("me/other");

        assertEquals(false, a.get("ok"));
        assertEquals(a.get("result"), b.get("result"));
        assertEquals(1, countRequests("/user/repos"));
        assertNoToken(a);
    }

    // ---- R3: ④~⑤ 사이 스캔이 먼저 큐에 넣은 NEW 세션 ----

    @Test
    void tc030_r3_newSessionSneakedInBetweenAddAndStartIsOkAlreadyQueued() {
        stubRepos(repoJson("me/other", "User", false));
        stubProject("me/other", "User");
        stubIssue("me/other", 5, issueJson(5, "open", "OWNER", false, ""));
        // ③ 의 findByIssue(없음) 이후 ④ 추가 사이에 스캔이 NEW 세션을 만든 상황: 두 번째 조회부터 NEW 세션을 돌려준다
        IssueSession neu = new IssueSession();
        neu.setId(3);
        neu.setProjectPath("me/other");
        neu.setIssueNumber(5);
        neu.setState(IssueSession.NEW);
        when(sessionsMapper.findByIssue(anyString(), anyLong())).thenReturn(null).thenReturn(neu);

        Map<String, Object> res = service.startIssue("me/other", 5);

        assertEquals(true, res.get("ok"), res.toString());
        assertEquals(true, res.get("added"));
        assertTrue(String.valueOf(res.get("result")).contains("이미 큐에 있습니다"), res.toString());
        verify(sessionsMapper, never()).insertIgnore(any());
    }

    private long countStarted() {
        return sessionStore.values().stream().filter(IssueSession::isActive).count();
    }

    // ---- 도우미 ----

    private void assertRejected(Map<String, Object> res, String messagePart) {
        assertEquals(false, res.get("ok"), res.toString());
        assertEquals(false, res.get("added"), res.toString());
        assertTrue(String.valueOf(res.get("result")).contains(messagePart), res.toString());
        assertTrue(store.stream().noneMatch(p -> p.equals("me/other") || p.equals("me/Other") || p.equals("me/old")),
                "대상 목록이 바뀌었다: " + store);
        assertTrue(store.size() == 0 || store.size() == 1 && store.get(0).equals("me/keep") || store.size() == IssueProjects.MAX_PROJECTS,
                "대상 목록이 바뀌었다: " + store);
        assertNoToken(res);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issuesOf(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("issues");
    }
}

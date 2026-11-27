package io.github.lib0823.devagent.issue;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.issue.FakeGitHubServer.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TC-026(T-028): 내 저장소 목록 조회(쪽 이어 붙이기·상한), 오류 구분, 대상 프로젝트 일괄 저장. 가짜 HTTP 서버만 쓴다. */
class RepoPickerTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값
    private static final String PAGE_KEY = "GET /user/repos?affiliation=owner&sort=updated&per_page=100&page=";

    private FakeGitHubServer server;
    private List<String> store;
    private AtomicInteger scans;
    private AtomicLong now;
    private IssueProjects projects;

    @BeforeEach
    void setUp() {
        server = new FakeGitHubServer();
        store = new ArrayList<>();
        scans = new AtomicInteger();
        now = new AtomicLong(1_000_000L);
        projects = newProjects(TOKEN);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private IssueProjects newProjects(String token) {
        GitHubClient client = new GitHubClient(RestClient.builder(), new GitHubProperties(token, server.url()));
        SettingService settings = mock(SettingService.class);
        when(settings.getIssueProjects()).thenAnswer(inv -> new ArrayList<>(store));
        doAnswer(inv -> {
            store = new ArrayList<>(inv.<List<String>>getArgument(0));
            return null;
        }).when(settings).setIssueProjects(anyList());
        IssueProjects p = new IssueProjects(mock(IssueProperties.class), client, settings, mock(IssueSessionMapper.class));
        p.setOnAdded(scans::incrementAndGet);
        p.clock = now::get;
        return p;
    }

    private static String repoJson(String path, String ownerType, boolean archived, boolean fork, boolean isPrivate) {
        return "{\"full_name\":\"" + path + "\",\"default_branch\":\"trunk\",\"private\":" + isPrivate + ",\"archived\":" + archived
                + ",\"fork\":" + fork + ",\"updated_at\":\"2026-11-20T01:02:03Z\",\"html_url\":\"https://github.com/" + path
                + "\",\"owner\":{\"type\":\"" + ownerType + "\"}}";
    }

    private static String page(String prefix, int count) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(repoJson("me/" + prefix + i, "User", false, false, false));
        }
        return "[" + String.join(",", items) + "]";
    }

    private static Reply withNext(Reply r) {
        return r.header("Link", "<https://api.github.com/user/repos?page=2>; rel=\"next\", <https://api.github.com/user/repos?page=9>; rel=\"last\"");
    }

    private void stubProject(String path, int id) {
        stubProject(path, id, "User");
    }

    private void stubProject(String path, int id, String ownerType) {
        server.on("GET /repos/" + path, Reply.json(200, "{\"id\":" + id + ",\"full_name\":\"" + path
                + "\",\"default_branch\":\"main\",\"owner\":{\"type\":\"" + ownerType + "\"}}"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> repos(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("repos");
    }

    // ---- ① 목록 조회 ----

    @Test
    void tc026_listConcatenatesPagesAndFiltersOrgAndMarksSelected() {
        List<String> first = new ArrayList<>();
        first.add(repoJson("me/priv", "User", false, false, true));
        first.add(repoJson("org/orgrepo", "Organization", false, false, false));
        first.add(repoJson("me/old", "User", true, true, false));
        for (int i = 0; i < 97; i++) {
            first.add(repoJson("me/r" + i, "User", false, false, false));
        }
        server.on(PAGE_KEY + "1", withNext(Reply.json(200, "[" + String.join(",", first) + "]")));
        server.on(PAGE_KEY + "2", Reply.json(200, "[" + repoJson("Me/Second", "User", false, false, false) + "]"));
        store.add("me/PRIV");

        Map<String, Object> res = projects.repoList(false);

        assertEquals(true, res.get("ok"));
        assertEquals(2, server.requests().size());
        assertTrue(server.requests().get(0).pathAndQuery().contains("affiliation=owner"));
        assertTrue(server.requests().get(0).pathAndQuery().contains("per_page=100"));
        List<Map<String, Object>> list = repos(res);
        assertEquals(100, list.size());                                  // 99 + 1 (조직 1개 제외)
        assertTrue(list.stream().noneMatch(r -> "org/orgrepo".equals(r.get("path"))));
        Map<String, Object> priv = list.get(0);
        assertEquals("me/priv", priv.get("path"));
        assertEquals("trunk", priv.get("defaultBranch"));
        assertEquals(true, priv.get("private"));
        assertEquals("2026-11-20T01:02:03Z", priv.get("updatedAt"));
        assertEquals("https://github.com/me/priv", priv.get("webUrl"));
        assertEquals(true, priv.get("selected"));                        // 대소문자 무시
        assertEquals(true, list.get(1).get("archived"));
        assertEquals(true, list.get(1).get("fork"));
        assertEquals(false, list.get(1).get("selected"));
        assertEquals(false, res.get("truncated"));
        assertEquals(50, res.get("max"));
    }

    @Test
    void tc026_stopsAtTenPagesAndMarksTruncated() {
        for (int p = 1; p <= 12; p++) {
            server.on(PAGE_KEY + p, withNext(Reply.json(200, page("p" + p + "_", 100))));
        }

        Map<String, Object> res = projects.repoList(false);

        assertEquals(10, server.requests().size());
        assertEquals(1000, repos(res).size());
        assertEquals(true, res.get("truncated"));
    }

    @Test
    void tc026_stopsWhenNoNextLinkEvenWithFullPage() {
        server.on(PAGE_KEY + "1", Reply.json(200, page("a", 100)));

        Map<String, Object> res = projects.repoList(false);

        assertEquals(1, server.requests().size());
        assertEquals(100, repos(res).size());
        assertEquals(false, res.get("truncated"));
    }

    @Test
    void tc026_cacheIsUsedFor60SecondsAndRefreshBypassesIt() {
        server.on(PAGE_KEY + "1", Reply.json(200, page("a", 2)));

        projects.repoList(false);
        projects.repoList(false);
        assertEquals(1, server.requests().size());

        store.add("me/a0");
        Map<String, Object> third = projects.repoList(false);                 // 캐시여도 selected 는 현재 목록으로
        assertEquals(1, server.requests().size());
        assertEquals(true, repos(third).get(0).get("selected"));

        now.addAndGet(11_000L);                                               // R4: 마지막 시도 10초 뒤부터 refresh 허용
        projects.repoList(true);
        assertEquals(2, server.requests().size());

        now.addAndGet(61_000L);
        projects.repoList(false);
        assertEquals(3, server.requests().size());
    }

    // ---- ② 오류 ----

    @Test
    void tc026_noTokenFailsWithoutCallingGitHub() {
        IssueProjects noToken = newProjects("");

        Map<String, Object> res = noToken.repoList(false);

        assertEquals(false, res.get("ok"));
        assertTrue(res.get("reason").toString().contains("토큰"));
        assertEquals(0, server.requests().size());
        assertTrue(repos(res).isEmpty());
    }

    @Test
    void tc026_unauthorizedAndForbiddenAreDistinguishedAndTokenNeverLeaks() {
        server.on(PAGE_KEY + "1", Reply.json(401, "{\"message\":\"Bad credentials " + TOKEN + "\"}"));
        Map<String, Object> r401 = projects.repoList(true);
        assertEquals(false, r401.get("ok"));
        assertTrue(r401.get("reason").toString().contains("유효하지 않거나 만료"));

        now.addAndGet(11_000L);
        server.on(PAGE_KEY + "1", Reply.json(403, "{\"message\":\"Resource not accessible " + TOKEN + "\"}"));
        Map<String, Object> r403 = projects.repoList(true);
        assertEquals(false, r403.get("ok"));
        assertTrue(r403.get("reason").toString().contains("권한이 부족"));
        assertTrue(r403.get("reason").toString().contains("***"));

        now.addAndGet(11_000L);
        server.on(PAGE_KEY + "1", Reply.json(500, "{\"message\":\"boom\"}"));
        Map<String, Object> r500 = projects.repoList(true);
        assertEquals(false, r500.get("ok"));
        assertTrue(r500.get("reason").toString().contains("가져오지 못했습니다"));

        for (Map<String, Object> r : List.of(r401, r403, r500)) {
            assertFalse(r.toString().contains(TOKEN), r.toString());
            assertFalse(r.toString().toLowerCase().contains("bearer"), r.toString());
        }
    }

    @Test
    void tc026_failuresAreNotCached() {
        server.on(PAGE_KEY + "1", Reply.json(401, "{\"message\":\"x\"}"));
        projects.repoList(false);
        server.on(PAGE_KEY + "1", Reply.json(200, page("a", 1)));

        Map<String, Object> res = projects.repoList(false);

        assertEquals(true, res.get("ok"));
    }

    // ---- ③ 일괄 저장 ----

    @Test
    @SuppressWarnings("unchecked")
    void tc026_setAddsNewRemovesMissingRejectsBadAndScansOnce() {
        store.addAll(List.of("me/keep", "me/drop"));
        stubProject("me/newone", 7);
        stubProject("me/another", 8);

        Map<String, Object> res = projects.setProjects(List.of("ME/keep", "me/newone", "me/ghost", "not a repo", "me/another", "me/newone"));

        assertEquals(List.of("me/keep", "me/newone", "me/another"), store);
        assertEquals(List.of("me/newone", "me/another"), res.get("added"));
        assertEquals(List.of("me/drop"), res.get("removed"));
        List<Map<String, String>> rejected = (List<Map<String, String>>) res.get("rejected");
        assertEquals(List.of("not a repo", "me/ghost"), rejected.stream().map(m -> m.get("path")).toList());
        assertEquals(1, scans.get());                                       // 추가가 둘이어도 스캔은 한 번
        assertTrue(res.get("result").toString().contains("추가 2"));
        // 기존 대상(me/keep)은 GitHub 확인을 다시 하지 않는다
        assertTrue(server.requests().stream().noneMatch(r -> r.pathAndQuery().contains("me/keep")));
    }

    @Test
    void tc026_setOverLimitRejectsEverythingWithoutCallingGitHub() {
        store.add("me/keep");
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i <= IssueProjects.MAX_PROJECTS; i++) {
            wanted.add("me/r" + i);
        }

        Map<String, Object> res = projects.setProjects(wanted);

        assertEquals(List.of("me/keep"), store);
        assertEquals(0, server.requests().size());
        assertEquals(0, scans.get());
        assertTrue(((List<?>) res.get("added")).isEmpty());
        assertTrue(res.get("result").toString().contains("최대 50개"));
    }

    @Test
    void tc026_okFalseWhenValidOverLimitRejectsAll() {
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i <= IssueProjects.MAX_PROJECTS; i++) {
            wanted.add("me/r" + i);
        }

        assertEquals(Boolean.FALSE, projects.setProjects(wanted).get("ok"));
    }

    @Test
    void tc026_okFalseWhenRequestOverMaxPathsRejectsAll() {
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i <= IssueProjects.MAX_REQUEST_PATHS; i++) {
            wanted.add("me/r" + i);
        }

        assertEquals(Boolean.FALSE, projects.setProjects(wanted).get("ok"));
    }

    @Test
    void tc026_okTrueForNormalPartialRejectAndNoChange() {
        store.add("me/keep");
        stubProject("me/newone", 7);

        assertEquals(Boolean.TRUE, projects.setProjects(List.of("me/keep", "me/newone")).get("ok"));
        assertEquals(Boolean.TRUE, projects.setProjects(List.of("me/keep", "me/newone", "me/ghost")).get("ok"));
        assertEquals(Boolean.TRUE, projects.setProjects(List.of("me/keep", "me/newone")).get("ok"));
    }

    @Test
    void tc026_setWithExactlyLimitIsAccepted() {
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < IssueProjects.MAX_PROJECTS; i++) {
            wanted.add("me/r" + i);
            stubProject("me/r" + i, i + 1);
        }

        Map<String, Object> res = projects.setProjects(wanted);

        assertEquals(50, store.size());
        assertEquals(50, ((List<?>) res.get("added")).size());
        assertEquals(1, scans.get());
    }

    @Test
    void tc026_setWithNoPathsClearsTargetsWithoutScan() {
        store.addAll(List.of("me/a", "me/b"));

        Map<String, Object> res = projects.setProjects(null);

        assertTrue(store.isEmpty());
        assertEquals(List.of("me/a", "me/b"), res.get("removed"));
        assertEquals(0, scans.get());
    }

    @Test
    void tc026_setWithOnlyRejectedChangesNothingAndDoesNotScan() {
        store.add("me/keep");

        Map<String, Object> res = projects.setProjects(List.of("me/keep", "me/ghost"));

        assertEquals(List.of("me/keep"), store);
        assertEquals(0, scans.get());
        assertEquals(1, ((List<?>) res.get("rejected")).size());
        assertTrue(((List<?>) res.get("removed")).isEmpty());
    }

    @Test
    void tc026_singleAddIsAlsoCappedAtLimit() {
        for (int i = 0; i < IssueProjects.MAX_PROJECTS; i++) {
            store.add("me/r" + i);
        }
        stubProject("me/extra", 99);

        String msg = projects.addProject("me/extra");

        assertTrue(msg.contains("최대 50개"));
        assertEquals(50, store.size());
        assertEquals(0, scans.get());
    }

    // ---- R1 구성 묶음 합치기 ----

    @Test
    void tc026_r1_mergeKeepsExistingAddsValidRejectsRestAndScansOnce() {
        store.add("me/local");
        stubProject("me/a", 1);
        stubProject("me/b", 2);
        stubProject("org/o", 3, "Organization");

        String note = projects.mergeProjects(List.of("me/a", "a/b/c", "org/o", "me/ghost", "ME/LOCAL", "me/b"));

        assertEquals(List.of("me/local", "me/a", "me/b"), store);           // 기존 대상은 지우지 않는다
        assertEquals(1, scans.get());
        assertTrue(note.contains("추가 2") && note.contains("거부 3"), note);
        assertTrue(server.requests().stream().noneMatch(r -> r.pathAndQuery().contains("me/local")));
    }

    @Test
    void tc026_r1_mergeOverLimitChangesNothing() {
        for (int i = 0; i < IssueProjects.MAX_PROJECTS; i++) {
            store.add("me/r" + i);
        }
        stubProject("me/extra", 99);

        String note = projects.mergeProjects(List.of("me/extra"));

        assertEquals(50, store.size());
        assertFalse(store.contains("me/extra"));
        assertEquals(0, scans.get());
        assertTrue(note.contains("최대 50개"), note);
    }

    @Test
    void tc026_r1_mergeWithoutTokenRejectsNewAndKeepsExisting() {
        IssueProjects noToken = newProjects("");
        store.add("me/local");

        String note = noToken.mergeProjects(List.of("me/new"));

        assertEquals(List.of("me/local"), store);
        assertEquals(0, scans.get());
        assertTrue(note.contains("거부 1"), note);
    }

    // ---- R2 조직 저장소 ----

    @Test
    void tc026_r2_organizationRepoRejectedByAddAndSetButExistingKept() {
        stubProject("org/o", 3, "Organization");
        stubProject("me/ok", 4);
        store.add("org/old");                                              // 이미 저장된 것은 다시 검사하지 않는다

        String msg = projects.addProject("org/o");
        Map<String, Object> res = projects.setProjects(List.of("org/old", "org/o", "me/ok"));

        assertTrue(msg.contains("조직 저장소"), msg);
        assertEquals(List.of("org/old", "me/ok"), store);
        assertEquals(List.of("me/ok"), res.get("added"));
        assertTrue(res.get("rejected").toString().contains("조직 저장소는 대상으로 할 수 없습니다."));
        assertTrue(server.requests().stream().noneMatch(r -> r.pathAndQuery().contains("org/old")));
    }

    // ---- R3 요청 크기 ----

    @Test
    void tc026_r3_setRejectsOversizedRequestBeforeAnyProcessing() {
        store.add("me/keep");
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i <= IssueProjects.MAX_REQUEST_PATHS; i++) {
            wanted.add("x" + (i % 3));                                     // 표기도 틀리고 중복 — 처리되기 전에 거부돼야 한다
        }

        Map<String, Object> res = projects.setProjects(wanted);

        assertEquals(List.of("me/keep"), store);
        assertTrue(((List<?>) res.get("rejected")).isEmpty());
        assertTrue(res.get("result").toString().contains("최대 100개"));
        assertEquals(0, server.requests().size());
        assertEquals(100, IssueProjects.MAX_REQUEST_PATHS);
    }

    // ---- R4 새로고침 간격 ----

    @Test
    void tc026_r4_refreshWithinTenSecondsReturnsCacheOrAsksToWait() {
        server.on(PAGE_KEY + "1", Reply.json(200, page("a", 2)));
        projects.repoList(false);
        now.addAndGet(5_000L);

        Map<String, Object> cached = projects.repoList(true);              // 10초 안 — 캐시를 돌려주고 호출하지 않는다

        assertEquals(true, cached.get("ok"));
        assertEquals(1, server.requests().size());
        now.addAndGet(6_000L);
        projects.repoList(true);
        assertEquals(2, server.requests().size());
    }

    @Test
    void tc026_r4_failedAttemptAlsoCountsAndNoCacheMeansTryLater() {
        server.on(PAGE_KEY + "1", Reply.json(500, "{\"message\":\"boom\"}"));
        projects.repoList(true);
        now.addAndGet(3_000L);

        Map<String, Object> res = projects.repoList(true);

        assertEquals(false, res.get("ok"));
        assertTrue(res.get("reason").toString().contains("잠시 뒤"));
        assertEquals(1, server.requests().size());
    }
}

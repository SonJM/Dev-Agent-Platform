package io.github.lib0823.devagent.issue;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.IssueProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

import static io.github.lib0823.devagent.issue.IssueTexts.*;

/**
 * 이슈 에이전트 대상 프로젝트: 목록(DB 설정)·추가·삭제, 경로 해석(마지막 세그먼트 허용), GitHub 저장소 조회 캐시.
 * {@link IssueAgentService} 에서 분리.
 */
@Component
public class IssueProjects {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IssueProjects.class);
    /** 프로젝트가 추가됐을 때 부를 동작(이슈 에이전트가 즉시 스캔을 건다). */
    private volatile Runnable onAdded = () -> { };

    public void setOnAdded(Runnable onAdded) {
        this.onAdded = onAdded;
    }

    private final IssueProperties props;
    private final GitHubClient github;
    private final SettingService settings;
    private final IssueSessionMapper sessions;

    public IssueProjects(IssueProperties props, GitHubClient github, SettingService settings, IssueSessionMapper sessions) {
        this.props = props;
        this.github = github;
        this.settings = settings;
        this.sessions = sessions;
    }

    private final Map<String, GitHubClient.Project> projectCache = new ConcurrentHashMap<>();

    /** 대상 프로젝트 상한(OI-42). */
    public static final int MAX_PROJECTS = 50;
    /** 내 저장소 목록 캐시 유지 시간(밀리초). */
    static final long REPO_CACHE_MS = 60_000L;

    /** 일괄 저장 요청의 path 원소 수 상한(대상 상한의 두 배). 넘으면 표기 검사 전에 거부한다. */
    static final int MAX_REQUEST_PATHS = MAX_PROJECTS * 2;
    /** 목록 조회 시도 사이 최소 간격(밀리초) — refresh=true 로 GitHub 호출 한도를 소진하지 못하게. */
    static final long REPO_REFRESH_MIN_MS = 10_000L;
    static final String ORG_REJECTED = "조직 저장소는 대상으로 할 수 없습니다.";

    private record RepoCache(long at, GitHubClient.RepoList list) {
    }

    /** refresh 간격 제한에 걸렸을 때(캐시 없음). */
    private static final class RefreshTooSoon extends RuntimeException {
        RefreshTooSoon() {
            super("too soon", null, false, false);
        }
    }

    private volatile RepoCache repoCache;
    private long lastAttemptAt;
    /** 시험용으로 바꿀 수 있는 시계. */
    LongSupplier clock = System::currentTimeMillis;

    public String resolveProject(String path) {
        if (projects() == null) {
            return null;
        }
        if (path == null || path.isBlank()) {
            return projects().size() == 1 ? projects().get(0).strip() : null;
        }
        String p = path.strip().replaceAll("^/+|/+$", "");
        for (String cfg : projects()) {
            String c = cfg.strip();
            if (c.equalsIgnoreCase(p) || c.toLowerCase(Locale.ROOT).endsWith("/" + p.toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    public GitHubClient.Project project(String path) {
        return projectCache.computeIfAbsent(path, github::project);
    }

    /** 대상 프로젝트 목록(DB 우선, 없으면 ISSUE_PROJECTS). 관리 페이지/메신저 명령으로 추가·삭제되며 다음 스캔부터 반영. */
    public List<String> projects() {
        return settings.getIssueProjects();
    }

    /** 대상 프로젝트 현황(관리 페이지). 각 항목: path, id/webUrl(조회 성공 시), error, sessions(이 프로젝트의 세션 수). */
    public List<Map<String, Object>> projectList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String path : projects()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            try {
                GitHubClient.Project p = project(path);
                m.put("id", p.id());
                m.put("webUrl", p.webUrl());
                m.put("defaultBranch", p.defaultBranch());
            } catch (RuntimeException e) {
                m.put("error", e.getMessage());
            }
            out.add(m);
        }
        return out;
    }

    /** 대상 프로젝트 추가: GitHub 에서 조회해 실제 경로(full_name)로 정규화해 저장. 반환: 결과 메시지. */
    public synchronized String addProject(String path) {
        String p = path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
        if (p.isEmpty() || !p.contains("/")) {
            return "프로젝트 경로는 owner/repo 형식이어야 합니다.";
        }
        GitHubClient.Project gp;
        try {
            gp = github.project(p);
        } catch (RuntimeException e) {
            return "GitHub 에서 '" + p + "' 를 찾지 못했습니다: " + e.getMessage();
        }
        List<String> cur = projects();
        if (cur.stream().anyMatch(c -> c.equalsIgnoreCase(gp.path()))) {
            return gp.path() + " 는 이미 대상 프로젝트입니다.";
        }
        if (!gp.isUserOwned()) {
            return gp.path() + ": " + ORG_REJECTED;
        }
        if (cur.size() >= MAX_PROJECTS) {
            return "대상 프로젝트는 최대 " + MAX_PROJECTS + "개입니다. 먼저 일부를 빼세요.";
        }
        cur.add(gp.path());
        settings.setIssueProjects(cur);
        projectCache.put(gp.path(), gp);
        log.info("이슈 에이전트 대상 프로젝트 추가: {} (id={})", gp.path(), gp.id());
        onAdded.run();
        return gp.path() + " 를 대상 프로젝트에 추가했습니다. 라벨 '" + props.label() + "' 이슈를 바로 스캔합니다. (현재 " + cur.size() + "개)";
    }

    /** 대상 프로젝트 삭제. 진행 중/대기 중 세션은 건드리지 않는다(끝까지 진행). 새 이슈 스캔·수동 시작만 막힌다. */
    public synchronized String removeProject(String path) {
        String p = path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
        List<String> cur = projects();
        String hit = cur.stream().filter(c -> c.equalsIgnoreCase(p)).findFirst().orElse(null);
        if (hit == null) {
            return "'" + p + "' 는 대상 프로젝트가 아닙니다: " + cur;
        }
        cur.remove(hit);
        settings.setIssueProjects(cur);
        projectCache.remove(hit);
        log.info("이슈 에이전트 대상 프로젝트 삭제: {}", hit);
        String note = sessions.findActiveAll().stream().filter(a -> hit.equalsIgnoreCase(a.getProjectPath())).map(IssueSession::ref)
                .reduce((a, b) -> a + ", " + b).map(refs -> " 진행 중인 " + refs + " 는 끝까지 진행됩니다(필요하면 중단).").orElse("");
        return hit + " 를 대상 프로젝트에서 뺐습니다. (현재 " + cur.size() + "개)" + note;
    }

    // ---------------------------------------------------------------- 내 저장소 고르기(T-028)

    /**
     * 내 GitHub 저장소 목록(화면 「GitHub에서 고르기」). 성공: ok=true, repos(selected 는 현재 대상 여부), truncated, max.
     * 실패: ok=false + 사람이 읽는 reason(토큰·헤더 값 없음). 성공 결과는 60초 캐시(refresh=true 면 무시), selected 는 매번 계산한다.
     */
    public Map<String, Object> repoList(boolean refresh) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("max", MAX_PROJECTS);
        if (!github.hasToken()) {
            return repoFailure(out, "GitHub 토큰이 설정되지 않았습니다. 서버 .env 의 GITHUB_TOKEN 을 확인하세요.");
        }
        GitHubClient.RepoList list;
        try {
            list = cachedRepos(refresh);
        } catch (RefreshTooSoon e) {
            return repoFailure(out, "잠시 뒤 다시 시도하세요.");
        } catch (GitHubException e) {
            log.warn("GitHub 저장소 목록 조회 실패: kind={} status={}", e.kind(), e.status());
            return repoFailure(out, repoReason(e));
        } catch (RuntimeException e) {
            log.warn("GitHub 저장소 목록 조회 실패: {}", e.getClass().getSimpleName());
            return repoFailure(out, "GitHub 저장소 목록을 가져오지 못했습니다.");
        }
        List<String> selected = projects();
        List<Map<String, Object>> repos = new ArrayList<>();
        for (GitHubClient.Repo r : list.repos()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.path());
            m.put("defaultBranch", r.defaultBranch());
            m.put("private", r.isPrivate());
            m.put("archived", r.archived());
            m.put("fork", r.fork());
            m.put("updatedAt", r.updatedAt());
            m.put("webUrl", r.webUrl());
            m.put("selected", selected.stream().anyMatch(c -> c.equalsIgnoreCase(r.path())));
            repos.add(m);
        }
        out.put("ok", true);
        out.put("repos", repos);
        out.put("truncated", list.truncated());
        return out;
    }

    private synchronized GitHubClient.RepoList cachedRepos(boolean refresh) {
        RepoCache c = repoCache;
        long now = clock.getAsLong();
        if (!refresh && c != null && now - c.at() < REPO_CACHE_MS) {
            return c.list();
        }
        if (refresh && lastAttemptAt != 0 && now - lastAttemptAt < REPO_REFRESH_MIN_MS) {
            if (c != null) {
                return c.list();
            }
            throw new RefreshTooSoon();
        }
        lastAttemptAt = now;
        GitHubClient.RepoList fresh = github.ownRepos();
        repoCache = new RepoCache(now, fresh);
        return fresh;
    }

    private static Map<String, Object> repoFailure(Map<String, Object> out, String reason) {
        out.put("ok", false);
        out.put("reason", reason);
        out.put("repos", List.of());
        out.put("truncated", false);
        return out;
    }

    private static String repoReason(GitHubException e) {
        return switch (e.kind()) {
            case UNAUTHORIZED -> "GitHub 토큰이 유효하지 않거나 만료됐습니다.";
            case NOT_ACCESSIBLE, RATE_LIMITED -> "권한이 부족하거나 호출 한도를 넘었습니다: " + e.getMessage();
            default -> "GitHub 저장소 목록을 가져오지 못했습니다: " + e.getMessage();
        };
    }

    /**
     * 대상 프로젝트를 보낸 묶음으로 한 번에 바꾼다. 새로 들어오는 저장소만 GitHub 에서 확인·정규화하고, 실패한 것은 rejected 로 돌려주며
     * 나머지는 저장한다. 빠진 기존 대상은 removed(진행 중 세션은 removeProject 처럼 끝까지 진행). 상한 초과면 아무것도 저장하지 않는다.
     * 추가가 있으면 스캔은 한 번만 건다. 반환: result(한 줄), added, removed, rejected[{path, reason}].
     */
    public synchronized Map<String, Object> setProjects(List<String> wanted) {
        if (wanted != null && wanted.size() > MAX_REQUEST_PATHS) {
            return setResult(false, "한 번에 보낼 수 있는 저장소는 최대 " + MAX_REQUEST_PATHS + "개입니다(" + wanted.size()
                    + "개 받음). 저장하지 않았습니다.", List.of(), List.of(), List.of());
        }
        List<Map<String, String>> rejected = new ArrayList<>();
        List<String> valid = new ArrayList<>();
        for (String p : distinctRequested(wanted)) {
            if (validPath(p)) {
                valid.add(p);
            } else {
                rejected.add(Map.of("path", p, "reason", "owner/repo 형식이 아닙니다."));
            }
        }
        if (valid.size() > MAX_PROJECTS) {
            return setResult(false, "대상 프로젝트는 최대 " + MAX_PROJECTS + "개입니다(" + valid.size() + "개 선택). 저장하지 않았습니다.",
                    List.of(), List.of(), rejected);
        }
        List<String> before = projects();
        List<String> after = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String p : valid) {
            String known = before.stream().filter(c -> c.equalsIgnoreCase(p)).findFirst().orElse(null);
            if (known != null) {
                addIfAbsent(after, known);
            } else {
                resolveNew(p, after, added, rejected);
            }
        }
        List<String> removed = before.stream().filter(c -> after.stream().noneMatch(a -> a.equalsIgnoreCase(c))).toList();
        if (!added.isEmpty() || !removed.isEmpty()) {
            settings.setIssueProjects(after);
            removed.forEach(projectCache::remove);
            log.info("이슈 에이전트 대상 프로젝트 일괄 저장: 추가 {}개, 삭제 {}개, 거부 {}개 (현재 {}개)",
                    added.size(), removed.size(), rejected.size(), after.size());
        }
        if (!added.isEmpty()) {
            onAdded.run();
        }
        String result = "대상 프로젝트 " + after.size() + "개: 추가 " + added.size() + " · 삭제 " + removed.size() + " · 거부 " + rejected.size()
                + (removed.isEmpty() ? "" : " (진행 중인 세션은 끝까지 진행됩니다)");
        return setResult(true, result, added, removed, rejected);
    }

    /**
     * 구성 묶음 가져오기용: 기존 대상 ∪ 들어온 대상. 기존 대상은 지우지 않고, 새로 들어오는 것만 setProjects 와 같은 규칙으로
     * 확인한다(표기·GitHub 확인·개인 저장소). 합계가 상한을 넘으면 목록을 바꾸지 않는다. 추가가 있으면 스캔은 한 번.
     * 반환: 가져오기 응답에 실을 한 줄(추가·거부 수, 상한 초과 사실).
     */
    public synchronized String mergeProjects(List<String> incoming) {
        if (incoming != null && incoming.size() > MAX_REQUEST_PATHS) {
            return "대상 프로젝트 " + incoming.size() + "개가 한 번에 받을 수 있는 " + MAX_REQUEST_PATHS + "개를 넘어 합치지 않았습니다";
        }
        List<Map<String, String>> rejected = new ArrayList<>();
        List<String> before = projects();
        List<String> after = new ArrayList<>(before);
        List<String> added = new ArrayList<>();
        for (String p : distinctRequested(incoming)) {
            if (before.stream().anyMatch(c -> c.equalsIgnoreCase(p))) {
                continue;
            }
            if (validPath(p)) {
                resolveNew(p, after, added, rejected);
            } else {
                rejected.add(Map.of("path", p, "reason", "owner/repo 형식이 아닙니다."));
            }
        }
        if (after.size() > MAX_PROJECTS) {
            return "대상 프로젝트가 합쳐 " + after.size() + "개가 되어 최대 " + MAX_PROJECTS + "개를 넘으므로 합치지 않았습니다(거부 "
                    + rejected.size() + ")";
        }
        if (!added.isEmpty()) {
            settings.setIssueProjects(after);
            log.info("구성 묶음 가져오기: 대상 프로젝트 추가 {}개, 거부 {}개 (현재 {}개)", added.size(), rejected.size(), after.size());
            onAdded.run();
        }
        String why = rejected.isEmpty() ? "" : " — " + rejected.get(0).get("reason");
        return "대상 프로젝트 추가 " + added.size() + " · 거부 " + rejected.size() + why;
    }

    private void resolveNew(String p, List<String> after, List<String> added, List<Map<String, String>> rejected) {
        try {
            GitHubClient.Project gp = github.project(p);
            if (!gp.isUserOwned()) {
                rejected.add(Map.of("path", p, "reason", ORG_REJECTED));
                return;
            }
            if (addIfAbsent(after, gp.path())) {
                projectCache.put(gp.path(), gp);
                added.add(gp.path());
            }
        } catch (RuntimeException e) {
            String reason = e instanceof GitHubException ? e.getMessage() : "GitHub 에서 찾지 못했습니다.";
            rejected.add(Map.of("path", p, "reason", reason));
        }
    }

    private static boolean addIfAbsent(List<String> list, String path) {
        if (list.stream().anyMatch(c -> c.equalsIgnoreCase(path))) {
            return false;
        }
        list.add(path);
        return true;
    }

    private static List<String> distinctRequested(List<String> wanted) {
        List<String> out = new ArrayList<>();
        if (wanted == null) {
            return out;
        }
        for (String w : wanted) {
            String p = w == null ? "" : w.strip().replaceAll("^/+|/+$", "");
            if (!p.isEmpty()) {
                addIfAbsent(out, p);
            }
        }
        return out;
    }

    private static boolean validPath(String p) {
        try {
            GitHubClient.ownerRepo(p);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Object> setResult(boolean ok, String result, List<String> added, List<String> removed,
                                                 List<Map<String, String>> rejected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("result", result);
        m.put("added", added);
        m.put("removed", removed);
        m.put("rejected", rejected);
        return m;
    }
}

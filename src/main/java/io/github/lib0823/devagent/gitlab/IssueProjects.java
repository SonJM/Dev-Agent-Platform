package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.GitLabProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

import static io.github.lib0823.devagent.gitlab.IssueTexts.*;

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

    private final GitLabProperties props;
    private final GitHubClient github;
    private final SettingService settings;
    private final IssueSessionMapper sessions;

    public IssueProjects(GitLabProperties props, GitHubClient github, SettingService settings, IssueSessionMapper sessions) {
        this.props = props;
        this.github = github;
        this.settings = settings;
        this.sessions = sessions;
    }

    private final Map<String, GitHubClient.Project> projectCache = new ConcurrentHashMap<>();

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

    /** 대상 프로젝트 목록(DB 우선, 없으면 GITLAB_PROJECTS). 관리 페이지/메신저 명령으로 추가·삭제되며 다음 스캔부터 반영. */
    public List<String> projects() {
        return settings.getGitLabProjects();
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
    public String addProject(String path) {
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
        cur.add(gp.path());
        settings.setGitLabProjects(cur);
        projectCache.put(gp.path(), gp);
        log.info("이슈 에이전트 대상 프로젝트 추가: {} (id={})", gp.path(), gp.id());
        onAdded.run();
        return gp.path() + " 를 대상 프로젝트에 추가했습니다. 라벨 '" + props.label() + "' 이슈를 바로 스캔합니다. (현재 " + cur.size() + "개)";
    }

    /** 대상 프로젝트 삭제. 진행 중/대기 중 세션은 건드리지 않는다(끝까지 진행). 새 이슈 스캔·수동 시작만 막힌다. */
    public String removeProject(String path) {
        String p = path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
        List<String> cur = projects();
        String hit = cur.stream().filter(c -> c.equalsIgnoreCase(p)).findFirst().orElse(null);
        if (hit == null) {
            return "'" + p + "' 는 대상 프로젝트가 아닙니다: " + cur;
        }
        cur.remove(hit);
        settings.setGitLabProjects(cur);
        projectCache.remove(hit);
        log.info("이슈 에이전트 대상 프로젝트 삭제: {}", hit);
        String note = sessions.findActiveAll().stream().filter(a -> hit.equalsIgnoreCase(a.getProjectPath())).map(IssueSession::ref)
                .reduce((a, b) -> a + ", " + b).map(refs -> " 진행 중인 " + refs + " 는 끝까지 진행됩니다(필요하면 중단).").orElse("");
        return hit + " 를 대상 프로젝트에서 뺐습니다. (현재 " + cur.size() + "개)" + note;
    }
}

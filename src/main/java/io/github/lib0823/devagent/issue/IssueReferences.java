package io.github.lib0823.devagent.issue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static io.github.lib0823.devagent.issue.IssueTexts.*;

/**
 * 이슈 본문 "참고 저장소" 절로 지정된 다른 프로젝트를 읽기 전용 worktree 로 준비하고(세션별), 프롬프트 안내문을 만들고,
 * 실행 뒤 변경이 생겼으면 되돌린다. {@link IssueAgentService} 에서 분리.
 */
@Component
public class IssueReferences {

    private static final Logger log = LoggerFactory.getLogger(IssueReferences.class);

    private final GitHubClient github;
    private final GitRunner git;
    private final IssueEventBus events;
    private final IssueProjects projects;

    public IssueReferences(GitHubClient github, GitRunner git, IssueEventBus events, IssueProjects projects) {
        this.github = github;
        this.git = git;
        this.events = events;
        this.projects = projects;
    }

    /** 세션별 참고 저장소(읽기 전용) worktree — 이슈 본문 "참고 저장소" 로 지정된 다른 프로젝트. 프롬프트 안내 + 실행 후 변경 검사용 */
    private final Map<Long, List<ReferenceRepo>> references = new ConcurrentHashMap<>();

    /** 이슈 본문에서 지정한 참고 저장소: GitHub 경로 + 브랜치 + 로컬 worktree. */
    record ReferenceRepo(String path, String branch, Path dir) {
    }

    /**
     * 이슈 본문의 참고 저장소를 읽기 전용 worktree 로 준비하고 세션에 기억한다(프롬프트 안내 + 실행 후 변경 검사).
     * {@code description} 이 null 이면 이슈를 다시 조회한다. 실패는 이벤트로만 남기고 진행한다(참고일 뿐 필수 아님).
     */
    public void ensureReferences(IssueSession s, String description) {
        final long id = s.getId();
        try {
            if (description == null) {
                description = github.issue(s.getProjectPath(), s.getIssueNumber()).description();
            }
        } catch (RuntimeException e) {
            log.debug("참고 저장소 확인용 이슈 조회 실패(무시): {}", e.toString());
            return;
        }
        List<String[]> wanted = parseReferenceRepos(description);
        List<ReferenceRepo> refs = new ArrayList<>();
        List<String> targets = projects.projects();
        List<String> ignored = new ArrayList<>();
        for (String[] w : wanted) {
            String path = w[0];
            if (path.equalsIgnoreCase(s.getProjectPath())) {
                continue;
            }
            if (!isTarget(targets, path)) {
                ignored.add(path);                              // 이슈 본문은 신뢰할 수 없는 입력 — 대상 프로젝트가 아니면 받아 오지 않는다
                continue;
            }
            GitHubClient.Project p;
            try {
                p = projects.project(path);                    // 본문의 `config/job/` 같은 디렉터리 표기도 걸리므로 GitHub 에 없으면 조용히 건너뛴다
            } catch (RuntimeException e) {
                log.debug("참고 저장소 후보 {} 는 GitHub 저장소가 아님(무시): {}", path, e.toString());
                continue;
            }
            try {
                String branch = w[1] != null ? w[1] : p.defaultBranch();
                Path dir = git.prepareReference(p.path(), p.httpUrl(), branch);
                refs.add(new ReferenceRepo(p.path(), branch, dir));
                events.emit(id, IssueEvent.GIT, "참고 저장소 준비(읽기 전용): " + p.path() + " @" + branch + " → " + dir);
            } catch (RuntimeException e) {
                events.emit(id, IssueEvent.ERROR, "참고 저장소 준비 실패(무시하고 진행): " + path + " — " + abbreviate(e.getMessage(), 200));
            }
        }
        if (!ignored.isEmpty()) {
            events.emit(id, IssueEvent.INFO, "참고 저장소 후보가 대상 프로젝트가 아니라 무시했습니다: " + abbreviate(String.join(", ", ignored), 200));
        }
        if (refs.isEmpty()) {
            references.remove(id);
        } else {
            references.put(id, refs);
        }
    }

    /** 참고 저장소가 대상 프로젝트 목록(설정)에 있는지(대소문자 무시). 목록 밖 저장소는 clone 하지 않는다. */
    static boolean isTarget(List<String> targets, String path) {
        if (targets == null || path == null) {
            return false;
        }
        return targets.stream().anyMatch(t -> t != null && t.strip().equalsIgnoreCase(path.strip()));
    }

    /** 시스템 프롬프트에 붙일 참고 저장소 안내(없으면 빈 문자열). */
    public String referenceNote(long sessionId) {
        List<ReferenceRepo> refs = references.get(sessionId);
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n참고 저장소(읽기 전용) — 이슈가 지정한 다른 프로젝트의 소스가 아래 경로에 있습니다. "
                + "Read/Grep/Glob 으로 읽어 참고하되 그 안의 파일은 절대 수정·생성하지 마세요(수정 대상은 현재 작업 디렉터리뿐). "
                + "그 저장소로 cd 하거나 빌드를 돌리지 마세요.");
        for (ReferenceRepo r : refs) {
            sb.append("\n- ").append(r.path()).append(" (").append(r.branch()).append(" 브랜치): ")
                    .append(r.dir().toAbsolutePath().toString().replace('\\', '/'));
        }
        return sb.toString();
    }

    /** 실행 후 참고 worktree 에 변경이 생겼으면 되돌리고 알린다(에이전트가 읽기 전용 약속을 어긴 경우). */
    public void guardReferences(long sessionId) {
        List<ReferenceRepo> refs = references.get(sessionId);
        if (refs == null) {
            return;
        }
        for (ReferenceRepo r : refs) {
            try {
                String changes = git.referenceChanges(r.dir());
                if (!changes.isBlank()) {
                    git.resetReference(r.dir());
                    events.emit(sessionId, IssueEvent.ERROR, "⚠ 참고 저장소 " + r.path() + " 에 변경이 생겨 되돌렸습니다(읽기 전용)", changes);
                }
            } catch (RuntimeException e) {
                log.warn("참고 저장소 {} 검사 실패: {}", r.path(), e.toString());
            }
        }
    }
}

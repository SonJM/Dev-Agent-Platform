package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.platform.ChildEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 저장소 git 조작. 인증 토큰은 .git/config 에 남기지 않도록 명령별 환경변수(GIT_CONFIG_*)로
 * {@code http.extraheader=Authorization: Basic base64(oauth2:TOKEN)} 를 주입한다.
 * <p>
 * 디렉터리 구조: 프로젝트당 기준 clone 1개({@link #repoDir}) + 이슈 세션당 {@code git worktree} 1개({@link #sessionDir}).
 * 세션마다 독립 작업트리를 쓰므로 앞 건이 수정 중(FIXING)이어도 다음 건을 같은 프로젝트에서 선분석할 수 있고,
 * Claude 세션(cwd 기준 저장)도 세션 디렉터리에 고정된다.
 * <p>
 * 푸시는 {@code branchPrefix}(기본 ai/)로 시작하는 브랜치에만 허용 — 기본 브랜치 직접 푸시 차단.
 */
@Component
public class GitRunner {

    private static final Logger log = LoggerFactory.getLogger(GitRunner.class);
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final GitLabProperties props;

    public GitRunner(GitLabProperties props) {
        this.props = props;
    }

    /** 프로젝트 경로("group/repo") → 기준 clone 디렉터리. */
    public Path repoDir(String projectPath) {
        String safe = projectPath.replaceAll("[^A-Za-z0-9._-]", "__");
        return Path.of(props.workDir() == null ? "work" : props.workDir()).toAbsolutePath().resolve(safe);
    }

    /** 이슈 세션 전용 작업트리 디렉터리: work/&lt;project&gt;__wt/issue-&lt;iid&gt;. */
    public Path sessionDir(String projectPath, long iid) {
        Path base = repoDir(projectPath);
        return base.resolveSibling(base.getFileName() + "__wt").resolve("issue-" + iid);
    }

    /**
     * 세션 작업트리 준비: 기준 clone(최초 1회) → fetch → 기존 worktree 제거 → origin/기본브랜치에서 새 worktree 생성.
     * 반환: 세션 디렉터리.
     */
    public Path prepare(String projectPath, String httpUrl, String defaultBranch, long iid) {
        Path base = repoDir(projectPath);
        if (!Files.isDirectory(base.resolve(".git"))) {
            try {
                Files.createDirectories(base.getParent());
            } catch (IOException e) {
                throw new IllegalStateException("작업 디렉터리 생성 실패: " + base, e);
            }
            log.info("git clone {} -> {}", httpUrl, base);
            run(base.getParent(), "clone", "--quiet", httpUrl, base.getFileName().toString());
        }
        run(base, "fetch", "--prune", "--quiet", "origin");
        Path dir = sessionDir(projectPath, iid);
        cleanup(projectPath, iid);
        try {
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("worktree 디렉터리 생성 실패: " + dir, e);
        }
        run(base, "worktree", "add", "--force", "--detach", "--quiet", dir.toString(), "origin/" + defaultBranch);
        return dir;
    }

    /** 참고 저장소(읽기 전용) worktree 위치: work/<grp__repo>__ref/<branch>. 세션과 무관하게 공유하고 지우지 않는다. */
    public Path referenceDir(String projectPath, String branch) {
        Path base = repoDir(projectPath);
        return base.resolveSibling(base.getFileName() + "__ref").resolve(branch.replace('/', '_'));
    }

    /**
     * 참고 저장소 준비: 기준 clone(최초 1회) → fetch → origin/{branch} 를 가리키는 detached worktree 를 만들거나 그 커밋으로 맞춘다.
     * 이슈 본문의 "참고 저장소" 로 지정된 다른 프로젝트를 에이전트가 읽을 수 있게 하는 용도. 반환: worktree 디렉터리.
     */
    public Path prepareReference(String projectPath, String httpUrl, String branch) {
        Path base = repoDir(projectPath);
        if (!Files.isDirectory(base.resolve(".git"))) {
            try {
                Files.createDirectories(base.getParent());
            } catch (IOException e) {
                throw new IllegalStateException("작업 디렉터리 생성 실패: " + base, e);
            }
            log.info("git clone(참고) {} -> {}", httpUrl, base);
            run(base.getParent(), "clone", "--quiet", httpUrl, base.getFileName().toString());
        }
        run(base, "fetch", "--prune", "--quiet", "origin");
        Path dir = referenceDir(projectPath, branch);
        if (Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git"))) {
            run(dir, "checkout", "--quiet", "--force", "--detach", "origin/" + branch);
            run(dir, "clean", "-fdq");
            return dir;
        }
        try {
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("참고 worktree 디렉터리 생성 실패: " + dir, e);
        }
        run(base, "worktree", "prune");
        run(base, "worktree", "add", "--force", "--detach", "--quiet", dir.toString(), "origin/" + branch);
        return dir;
    }

    /** 참고 worktree 에 변경(수정·추가 파일)이 있는지. 에이전트가 읽기 전용 약속을 어겼는지 실행 후 검사한다. */
    public String referenceChanges(Path dir) {
        return run(dir, "status", "--porcelain").strip();
    }

    /** 참고 worktree 를 원래 커밋으로 되돌린다(변경 폐기 + 추가 파일 삭제). */
    public void resetReference(Path dir) {
        run(dir, "checkout", "--quiet", "--force", "--", ".");
        run(dir, "clean", "-fdq");
    }

    /** 세션 작업트리 제거(종료/재시작 시). 실패해도 예외 없이 로그만 남긴다. */
    public void cleanup(String projectPath, long iid) {
        Path base = repoDir(projectPath);
        Path dir = sessionDir(projectPath, iid);
        if (Files.isDirectory(base.resolve(".git"))) {
            try {
                run(base, "worktree", "prune");
                if (Files.exists(dir)) {
                    run(base, "worktree", "remove", "--force", dir.toString());
                }
            } catch (RuntimeException e) {
                log.debug("worktree remove 실패(디렉터리 직접 삭제 시도): {}", e.getMessage());
            }
        }
        if (Files.exists(dir)) {
            deleteRecursively(dir);
        }
        if (Files.exists(dir)) {
            // Windows: gradle 데몬 등이 파일을 잠그고 있으면 삭제가 안 된다 → 비켜두어 다음 worktree add 가 막히지 않게 한다
            Path stale = dir.resolveSibling(dir.getFileName() + ".stale-" + System.currentTimeMillis());
            try {
                Files.move(dir, stale);
                log.warn("세션 작업트리 삭제 불가(잠김) → {} 로 이동. 나중에 수동 삭제하세요.", stale.getFileName());
            } catch (IOException e) {
                log.warn("세션 작업트리 정리 실패 {}: {}", dir, e.toString());
            }
        }
        if (Files.isDirectory(base.resolve(".git"))) {
            try {
                run(base, "worktree", "prune");
            } catch (RuntimeException ignore) {
                // 다음 prepare 에서 다시 시도
            }
        }
    }

    /** 원격에 해당 브랜치가 이미 있는지(같은 이슈 재실행 시 기존 MR 브랜치를 덮지 않기 위해). */
    public boolean remoteBranchExists(Path dir, String branch) {
        return !run(dir, "ls-remote", "--heads", "origin", branch).isBlank();
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 잠긴 파일은 다음 prepare 의 worktree add --force 가 처리
                }
            });
        } catch (IOException e) {
            log.debug("디렉터리 삭제 실패 {}: {}", dir, e.toString());
        }
    }

    /**
     * 새로 만든(untracked) 파일도 diff 에 보이게 intent-to-add 로 올린다(내용은 스테이징하지 않음).
     * 없으면 `git diff --stat` 가 수정 파일만 세어 "4 files changed" 처럼 과소 집계된다(scheduler 마이그레이션에서 실측: 실제 157개).
     */
    private void intentToAdd(Path dir) {
        try {
            run(dir, "add", "-A", "-N");
        } catch (RuntimeException e) {
            log.debug("git add -N 실패(무시): {}", e.getMessage());
        }
    }

    public String diffStat(Path dir) {
        intentToAdd(dir);
        return run(dir, "diff", "--stat");
    }

    public String diff(Path dir, int maxChars) {
        intentToAdd(dir);
        String d = run(dir, "diff");
        return d.length() > maxChars ? d.substring(0, maxChars) + "\n... (생략)" : d;
    }

    /** 커밋 1건의 diff(통계 포함). 푸시 후 세션의 변경 내용을 모니터에서 다시 볼 때 사용. */
    public String show(Path dir, String sha, int maxChars) {
        String d = run(dir, "show", "--stat", "-p", "--format=commit %h %s%n", sha);
        return d.length() > maxChars ? d.substring(0, maxChars) + "\n... (생략)" : d;
    }

    public boolean hasChanges(Path dir) {
        return !run(dir, "status", "--porcelain").isBlank();
    }

    /** 작업트리에서 바뀐 파일(수정·추가·삭제 포함, 저장소 상대경로 '/' 구분). 이름 바뀐 경우 새 이름. */
    public List<String> changedFiles(Path dir) {
        List<String> out = new ArrayList<>();
        for (String line : run(dir, "status", "--porcelain", "--untracked-files=all").split("\r?\n")) {
            if (line.length() < 4) {
                continue;
            }
            String path = line.substring(3).strip();
            int arrow = path.indexOf(" -> ");
            if (arrow >= 0) {
                path = path.substring(arrow + 4);
            }
            if (path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
            }
            out.add(path.replace('\\', '/'));
        }
        return out;
    }

    public void discard(Path dir) {
        run(dir, "reset", "--hard", "--quiet");
        run(dir, "clean", "-fdq");
    }

    /** 현재 작업트리 변경을 새 브랜치로 커밋·푸시. 반환: 커밋 SHA. */
    public String commitAndPush(Path dir, String branch, String message) {
        if (!isPushable(branch)) {
            throw new IllegalStateException("허용되지 않은 브랜치 이름(컨벤션 접두어 "
                    + (props.convention() == null ? "" : props.convention().prefixesOr()) + "/… 만, 기준 브랜치 금지): " + branch);
        }
        run(dir, "checkout", "--quiet", "-B", branch);
        run(dir, "add", "-A");
        List<String> commit = new ArrayList<>(List.of("-c", "user.name=" + nvl(props.gitUserName(), "dev-agent"),
                "-c", "user.email=" + nvl(props.gitUserEmail(), "dev-agent@localhost"),
                "commit", "--quiet", "-m", message));
        run(dir, commit.toArray(new String[0]));
        run(dir, "push", "--quiet", "-u", "origin", branch);          // 강제 푸시 없음: 재실행은 호출측이 새 브랜치명을 쓴다
        return run(dir, "rev-parse", "--short", "HEAD").strip();
    }

    /** 브랜치 이름 생성: prefix + issue-<iid>-<slug>. */
    /**
     * 컨벤션 브랜치명 {유형}/{일감번호}. 유형은 허용 접두어(feature/fix/refactor/doc/hotfix) 중 하나여야 하며, 아니면 fix.
     * 예: fix/123456, feature/issue-7(일감번호가 없어 GitLab iid 사용).
     */
    public String branchName(String workType, String workId) {
        List<String> allowed = props.convention() == null ? List.of("feature", "fix", "refactor", "doc", "hotfix") : props.convention().prefixesOr();
        String type = workType == null ? "" : workType.strip().toLowerCase();
        if (!allowed.contains(type)) {
            type = allowed.contains("fix") ? "fix" : allowed.get(0);
        }
        String id = workId == null ? "" : workId.strip().toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return type + "/" + (id.isBlank() ? "issue" : id);
    }

    /** 푸시 가능한 브랜치인지: 허용 접두어로 시작하고 main/develop 같은 기준 브랜치가 아니어야 한다. */
    boolean isPushable(String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        GitLabProperties.Convention c = props.convention();
        List<String> allowed = c == null ? List.of("feature", "fix", "refactor", "doc", "hotfix") : c.prefixesOr();
        for (String protectedName : List.of("main", "master", "develop", c == null ? "" : nvl(c.baseBranch(), ""), c == null ? "" : nvl(c.hotfixBase(), ""))) {
            if (!protectedName.isBlank() && protectedName.equalsIgnoreCase(branch)) {
                return false;
            }
        }
        return allowed.stream().anyMatch(p -> branch.startsWith(p + "/") && branch.length() > p.length() + 1);
    }

    // ---- 실행 ----

    private String run(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.longpaths=true");               // Windows 260자 제한: scheduler 같은 깊은 경로 저장소는 이것 없이는 checkout 이 "Filename too long" 으로 실패
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = ChildEnv.clean(new ProcessBuilder(cmd).directory(cwd.toFile()));
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("LC_ALL", "C.UTF-8");
        if (props.token() != null && !props.token().isBlank()) {
            String basic = Base64.getEncoder().encodeToString(("oauth2:" + props.token()).getBytes(StandardCharsets.UTF_8));
            pb.environment().put("GIT_CONFIG_COUNT", "1");
            pb.environment().put("GIT_CONFIG_KEY_0", "http.extraheader");
            pb.environment().put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("git 실행 실패: " + e.getMessage(), e);
        }
        CompletableFuture<String> out = read(p.getInputStream());
        CompletableFuture<String> err = read(p.getErrorStream());
        try {
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("git 타임아웃: " + String.join(" ", redact(args)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IllegalStateException("git 대기 인터럽트", e);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", redact(args)) + " 실패(exit=" + p.exitValue()
                    + "): " + err.join().strip());
        }
        return out.join();
    }

    private static List<String> redact(String[] args) {
        List<String> r = new ArrayList<>();
        for (String a : args) {
            r.add(a != null && a.contains("@") && a.startsWith("http") ? "<url>" : a);
        }
        return r;
    }

    private static CompletableFuture<String> read(java.io.InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String nvl(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }
}

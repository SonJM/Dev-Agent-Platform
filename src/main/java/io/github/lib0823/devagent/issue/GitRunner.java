package io.github.lib0823.devagent.issue;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.IssueProperties;
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
 * {@code http.<GitHub 주소>/.extraheader=Authorization: Basic base64(x-access-token:TOKEN)} 를 주입한다.
 * 키를 GitHub 호스트로 한정하므로 참고 저장소·서브모듈 등 다른 호스트로는 토큰이 나가지 않는다.
 * Basic 의 사용자 이름은 GitHub 가 PAT 에 대해 값을 보지 않는다(비어 있지만 않으면 됨) — 관례적인 {@code x-access-token} 을 쓴다.
 * 인증 환경변수는 원격 명령(clone·fetch·push·ls-remote)에만 붙이고, 로컬 명령(status·add·commit 등)에는 붙이지 않는다.
 * 에이전트가 쓴 worktree 의 설정·훅이 토큰이 든 환경에서 실행되지 않도록 모든 호출에 훅·fsmonitor 를 끄고(HARDENING),
 * 세션 worktree 를 건드리는 호출 전에 {@code .git} 파일이 봇이 만든 값인지 확인한다({@link #verifyWorktree}).
 * <p>
 * 디렉터리 구조: 프로젝트당 기준 clone 1개({@link #repoDir}) + 이슈 세션당 {@code git worktree} 1개({@link #sessionDir}).
 * 세션마다 독립 작업트리를 쓰므로 앞 건이 수정 중(FIXING)이어도 다음 건을 같은 프로젝트에서 선분석할 수 있고,
 * Claude 세션(cwd 기준 저장)도 세션 디렉터리에 고정된다.
 * <p>
 * 푸시는 컨벤션 접두어({유형}/…)로 시작하는 브랜치에만 허용 — 기본 브랜치 직접 푸시 차단.
 */
@Component
public class GitRunner {

    private static final Logger log = LoggerFactory.getLogger(GitRunner.class);
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    /** Basic 인증의 사용자 이름 자리(PAT 에서는 값이 쓰이지 않는다). */
    static final String BASIC_USER = "x-access-token";

    /**
     * 모든 git 호출에 붙이는 실행 차단 설정(-c 는 저장소 설정보다 우선한다): 훅 경로를 비우고 fsmonitor·ssh 명령·credential helper 를 끈다.
     * core.longpaths 는 Windows 260자 제한 대응(scheduler 같은 깊은 경로 저장소).
     */
    static final List<String> HARDENING = List.of("-c", "core.longpaths=true", "-c", "core.hooksPath=/dev/null",
            "-c", "core.fsmonitor=false", "-c", "core.sshCommand=false", "-c", "credential.helper=");

    /** diff·show 에 붙이는 외부 프로그램 차단(diff.external·textconv 설정이 실행되지 않게). */
    static final List<String> DIFF_SAFE = List.of("--no-ext-diff", "--no-textconv");

    private final IssueProperties props;
    private final GitHubProperties github;

    public GitRunner(IssueProperties props, GitHubProperties github) {
        this.props = props;
        this.github = github;
    }

    /**
     * git 명령에 주입할 인증 환경변수. 토큰이 없으면 빈 맵. 키는 GitHub 호스트로 한정({@code http.<주소>/.extraheader}).
     * URL·설정 파일에는 토큰을 쓰지 않는다.
     */
    static java.util.Map<String, String> authEnv(GitHubProperties github) {
        if (github == null || !github.hasToken() || github.apiUrlPolicy() == GitHubProperties.ApiUrlPolicy.INSECURE) {
            return java.util.Map.of();
        }
        String basic = Base64.getEncoder().encodeToString((BASIC_USER + ":" + github.token().strip()).getBytes(StandardCharsets.UTF_8));
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "http." + github.webBase() + "/.extraheader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        return env;
    }

    /** 프로젝트 경로("group/repo") → 기준 clone 디렉터리. */
    public Path repoDir(String projectPath) {
        requireNoDotSegments(projectPath, "프로젝트 경로");
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
            runRemote(base.getParent(), "clone", "--quiet", httpUrl, base.getFileName().toString());
        }
        runRemote(base, "fetch", "--prune", "--quiet", "origin");
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
        requireNoDotSegments(branch, "브랜치 이름");
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
            runRemote(base.getParent(), "clone", "--quiet", httpUrl, base.getFileName().toString());
        }
        runRemote(base, "fetch", "--prune", "--quiet", "origin");
        Path dir = referenceDir(projectPath, branch);
        if (Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git"))) {
            verifyWorktree(dir);
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
        verifyWorktree(dir);
        return run(dir, "status", "--porcelain").strip();
    }

    /** 참고 worktree 를 원래 커밋으로 되돌린다(변경 폐기 + 추가 파일 삭제). */
    public void resetReference(Path dir) {
        verifyWorktree(dir);
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
        verifyWorktree(dir);
        return !runRemote(dir, "ls-remote", "--heads", "origin", branch).isBlank();
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
        verifyWorktree(dir);
        try {
            run(dir, "add", "-A", "-N");
        } catch (RuntimeException e) {
            log.debug("git add -N 실패(무시): {}", e.getMessage());
        }
    }

    public String diffStat(Path dir) {
        intentToAdd(dir);
        return run(dir, concat(List.of("diff"), DIFF_SAFE, List.of("--stat")));
    }

    public String diff(Path dir, int maxChars) {
        intentToAdd(dir);
        String d = run(dir, concat(List.of("diff"), DIFF_SAFE));
        return d.length() > maxChars ? d.substring(0, maxChars) + "\n... (생략)" : d;
    }

    /** 커밋 1건의 diff(통계 포함). 푸시 후 세션의 변경 내용을 모니터에서 다시 볼 때 사용. */
    public String show(Path dir, String sha, int maxChars) {
        String d = run(dir, concat(List.of("show"), DIFF_SAFE, List.of("--stat", "-p", "--format=commit %h %s%n", sha)));
        return d.length() > maxChars ? d.substring(0, maxChars) + "\n... (생략)" : d;
    }

    public boolean hasChanges(Path dir) {
        verifyWorktree(dir);
        return !run(dir, "status", "--porcelain").isBlank();
    }

    /** 작업트리에서 바뀐 파일(수정·추가·삭제 포함, 저장소 상대경로 '/' 구분). 이름 바뀐 경우 새 이름. */
    public List<String> changedFiles(Path dir) {
        verifyWorktree(dir);
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
        verifyWorktree(dir);
        run(dir, "reset", "--hard", "--quiet");
        run(dir, "clean", "-fdq");
    }

    /** 현재 작업트리 변경을 새 브랜치로 커밋·푸시. 반환: 커밋 SHA. */
    public String commitAndPush(Path dir, String branch, String message) {
        if (!isPushable(branch)) {
            throw new IllegalStateException("허용되지 않은 브랜치 이름(컨벤션 접두어 "
                    + (props.convention() == null ? "" : props.convention().prefixesOr()) + "/… 만, 기준 브랜치 금지): " + branch);
        }
        verifyWorktree(dir);
        run(dir, "checkout", "--quiet", "-B", branch);
        run(dir, "add", "-A");
        List<String> commit = new ArrayList<>(List.of("-c", "user.name=" + nvl(props.gitUserName(), "dev-agent"),
                "-c", "user.email=" + nvl(props.gitUserEmail(), "dev-agent@localhost"),
                "commit", "--quiet", "-m", message));
        run(dir, commit.toArray(new String[0]));
        runRemote(dir, "push", "--quiet", "-u", "origin", branch);          // 강제 푸시 없음: 재실행은 호출측이 새 브랜치명을 쓴다
        return run(dir, "rev-parse", "--short", "HEAD").strip();
    }

    /**
     * 컨벤션 브랜치명 {유형}/{이슈번호}. 유형은 허용 접두어(feature/fix/refactor/doc/hotfix) 중 하나여야 하며, 아니면 fix.
     * 모든 유형이 저장소 기본 브랜치에서 분기한다(hotfix 도 같다). 예: fix/12, hotfix/12.
     */
    public String branchName(String workType, long issueNumber) {
        List<String> allowed = props.convention() == null ? List.of("feature", "fix", "refactor", "doc", "hotfix") : props.convention().prefixesOr();
        String type = workType == null ? "" : workType.strip().toLowerCase();
        if (!allowed.contains(type)) {
            type = allowed.contains("fix") ? "fix" : allowed.get(0);
        }
        return type + "/" + issueNumber;
    }

    /** 푸시 가능한 브랜치인지: 허용 접두어로 시작하고 main/master/develop 같은 기준 브랜치가 아니어야 한다. */
    boolean isPushable(String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        IssueProperties.Convention c = props.convention();
        List<String> allowed = c == null ? List.of("feature", "fix", "refactor", "doc", "hotfix") : c.prefixesOr();
        for (String protectedName : List.of("main", "master", "develop")) {
            if (protectedName.equalsIgnoreCase(branch)) {
                return false;
            }
        }
        return allowed.stream().anyMatch(p -> branch.startsWith(p + "/") && branch.length() > p.length() + 1);
    }

    // ---- 보안 확인 ----

    private static void requireNoDotSegments(String value, String what) {
        if (value == null) {
            return;
        }
        for (String seg : value.split("[/\\\\]", -1)) {
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException(what + "에 '.'·'..' 세그먼트는 쓸 수 없습니다: " + value);
            }
        }
    }

    /**
     * 봇이 만든 세션·참고 worktree 의 {@code .git} 파일이 기준 clone 의 worktrees/&lt;이름&gt; 을 가리키는지 확인한다.
     * 에이전트가 {@code .git} 을 디렉터리로 바꾸거나 다른 gitdir 로 돌리면(설정·훅·필터를 심으려는 시도) 예외로 막는다.
     * 기준 clone 처럼 worktree 디렉터리가 아닌 곳은 확인하지 않는다(에이전트가 편집하는 곳이 아님).
     */
    void verifyWorktree(Path dir) {
        Path base = baseOf(dir);
        if (base == null) {
            return;
        }
        Path gitFile = dir.resolve(".git");
        if (!Files.isRegularFile(gitFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw tampered(dir, ".git 이 파일이 아님");
        }
        try {
            String content = Files.readString(gitFile, StandardCharsets.UTF_8).strip();
            if (!content.startsWith("gitdir:") || content.contains("\n")) {
                throw tampered(dir, ".git 파일 형식");
            }
            Path target = Path.of(content.substring("gitdir:".length()).strip());
            Path adminDir = (target.isAbsolute() ? target : dir.resolve(target)).toRealPath();
            Path expectedParent = base.resolve(".git").resolve("worktrees").toRealPath();
            if (!expectedParent.equals(adminDir.getParent())) {
                throw tampered(dir, "gitdir 이 기준 clone 의 worktrees 밖");
            }
            Path back = Path.of(Files.readString(adminDir.resolve("gitdir"), StandardCharsets.UTF_8).strip());
            if (!back.getParent().toRealPath().equals(dir.toRealPath())) {
                throw tampered(dir, "gitdir 이 다른 worktree 의 것");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw tampered(dir, "확인 실패");
        }
    }

    private static IllegalStateException tampered(Path dir, String why) {
        return new IllegalStateException("worktree 무결성 확인 실패(" + why + "): " + dir.getFileName());
    }

    /** 세션({@code <base>__wt/issue-N})·참고({@code <base>__ref/<branch>}) worktree 의 기준 clone. 그 외는 null. */
    private static Path baseOf(Path dir) {
        Path parent = dir.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getFileName() == null) {
            return null;
        }
        String name = parent.getFileName().toString();
        for (String suffix : List.of("__wt", "__ref")) {
            if (name.endsWith(suffix)) {
                return parent.resolveSibling(name.substring(0, name.length() - suffix.length()));
            }
        }
        return null;
    }

    // ---- 실행 ----

    /** 로컬 명령(인증 없음). */
    private String run(Path cwd, String... args) {
        return exec(cwd, false, args);
    }

    /** 원격 명령(clone·fetch·push·ls-remote): 인증 환경변수를 붙인다. */
    private String runRemote(Path cwd, String... args) {
        return exec(cwd, true, args);
    }

    /** 실행할 프로세스를 만든다(시작하지 않음). remote 일 때만 인증 환경변수가 든다. */
    ProcessBuilder processFor(Path cwd, boolean remote, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(HARDENING);
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = ChildEnv.clean(new ProcessBuilder(cmd).directory(cwd.toFile()));
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("LC_ALL", "C.UTF-8");
        pb.environment().remove("GIT_EXTERNAL_DIFF");
        if (remote) {
            pb.environment().putAll(authEnv(github));
        }
        return pb;
    }

    @SafeVarargs
    private static String[] concat(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all.toArray(new String[0]);
    }

    private String exec(Path cwd, boolean remote, String... args) {
        ProcessBuilder pb = processFor(cwd, remote, args);
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

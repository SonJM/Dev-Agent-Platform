package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitHubProperties;
import io.github.lib0823.devagent.config.GitLabProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보안 재작업(HIGH): 인증 환경변수는 원격 명령에만, 봇의 모든 git 호출에 훅·fsmonitor 무력화,
 * 커밋·푸시 전에 worktree 의 .git 파일이 봇이 만든 값인지 확인. 실제 git 과 로컬 bare 저장소를 쓴다(네트워크 없음).
 */
class GitRunnerHardeningTest {

    private static final String TOKEN = "test-token-value-123";   // 시험용 가짜 값

    @TempDir
    Path tmp;

    private GitRunner git;

    private GitLabProperties props() {
        return new GitLabProperties(true, List.of("own/repo"), "ai-fix", 1000, 1000, tmp.resolve("work").toString(), "bot", "bot@x",
                true, true, true, 0L, null, null, null, 2);
    }

    @BeforeEach
    void setUp() {
        git = new GitRunner(props(), new GitHubProperties(TOKEN, null));
    }

    // ---- ① 인증 환경변수는 원격 명령에만 ----

    @Test
    void localCommandsCarryNoAuthEnvironment() {
        ProcessBuilder local = git.processFor(tmp, false, "status", "--porcelain");

        assertFalse(local.environment().containsKey("GIT_CONFIG_COUNT"));
        assertFalse(local.environment().containsKey("GIT_CONFIG_KEY_0"));
        assertFalse(local.environment().containsKey("GIT_CONFIG_VALUE_0"));
    }

    @Test
    void remoteCommandsCarryHostScopedAuthEnvironment() {
        ProcessBuilder remote = git.processFor(tmp, true, "fetch", "origin");

        assertTrue(remote.environment().containsKey("GIT_CONFIG_COUNT"));
        assertTrue(remote.environment().get("GIT_CONFIG_KEY_0").startsWith("http."));
    }

    // ---- ② 모든 호출에 훅·fsmonitor 무력화 ----

    @Test
    void everyCommandDisablesHooksAndFsmonitor() {
        for (boolean remote : new boolean[]{false, true}) {
            List<String> cmd = git.processFor(tmp, remote, "add", "-A").command();

            assertTrue(cmd.contains("core.hooksPath=/dev/null"), "hooksPath 무력화 누락(remote=" + remote + ")");
            assertTrue(cmd.contains("core.fsmonitor=false"), "fsmonitor 무력화 누락(remote=" + remote + ")");
        }
    }

    @Test
    void diffAndShowDisableExternalDiffAndTextconv() {
        assertTrue(GitRunner.DIFF_SAFE.contains("--no-ext-diff"));
        assertTrue(GitRunner.DIFF_SAFE.contains("--no-textconv"));
    }

    // ---- 실제 git 으로: 저장소의 훅이 봇의 commit·push 에서 실행되지 않는다 ----

    private Path origin() throws Exception {
        Path bare = tmp.resolve("origin.git");
        sh(tmp, "git", "init", "--bare", "-b", "main", bare.toString());
        Path seed = tmp.resolve("seed");
        sh(tmp, "git", "clone", "-q", bare.toString(), seed.toString());
        Files.writeString(seed.resolve("a.txt"), "a\n");
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "-c", "user.name=t", "-c", "user.email=t@x", "commit", "-q", "-m", "init");
        sh(seed, "git", "push", "-q", "origin", "HEAD:main");
        return bare;
    }

    @Test
    void repositoryHooksDoNotRunOnCommitOrPush() throws Exception {
        Path bare = origin();
        Path dir = git.prepare("own/repo", bare.toString(), "main", 12);
        Path base = git.repoDir("own/repo");
        Path marker = tmp.resolve("hook-ran");
        String script = "#!/bin/sh\ntouch \"" + marker.toString().replace('\\', '/') + "\"\n";
        for (String hook : List.of("pre-commit", "commit-msg", "pre-push")) {
            Path h = base.resolve(".git/hooks").resolve(hook);
            Files.createDirectories(h.getParent());
            Files.writeString(h, script);
            h.toFile().setExecutable(true);
        }
        Files.writeString(dir.resolve("a.txt"), "changed\n");

        git.commitAndPush(dir, "fix/12", "fix: x");

        assertFalse(Files.exists(marker), "저장소 훅이 실행됐다");
        assertFalse(sh(tmp, "git", "--git-dir", bare.toString(), "branch", "--list", "fix/12").isBlank(), "푸시되지 않았다");
    }

    // ---- ③ .git 파일 검증 ----

    @Test
    void commitIsRefusedWhenWorktreeGitFileWasRedirected() throws Exception {
        Path dir = git.prepare("own/repo", origin().toString(), "main", 12);
        Path evil = tmp.resolve("evil-gitdir");
        Files.createDirectories(evil);
        Files.writeString(dir.resolve(".git"), "gitdir: " + evil.toString().replace('\\', '/') + "\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> git.commitAndPush(dir, "fix/12", "fix: x"));

        assertTrue(e.getMessage().contains("worktree"));
        assertThrows(IllegalStateException.class, () -> git.hasChanges(dir));
        assertThrows(IllegalStateException.class, () -> git.diff(dir, 1000));
    }

    @Test
    void untouchedWorktreePassesVerification() throws Exception {
        Path dir = git.prepare("own/repo", origin().toString(), "main", 12);

        assertDoesNotThrow(() -> git.verifyWorktree(dir));
        assertFalse(git.hasChanges(dir));
    }

    @Test
    void gitFileReplacedByDirectoryIsRefused() throws Exception {
        Path dir = git.prepare("own/repo", origin().toString(), "main", 12);
        Files.delete(dir.resolve(".git"));
        Files.createDirectories(dir.resolve(".git"));

        assertThrows(IllegalStateException.class, () -> git.verifyWorktree(dir));
    }

    // ---- LOW: 경로 세그먼트 ----

    @Test
    void dotSegmentsAreRejectedInPaths() {
        assertThrows(IllegalArgumentException.class, () -> git.repoDir("../escape"));
        assertThrows(IllegalArgumentException.class, () -> git.repoDir("own/.."));
        assertThrows(IllegalArgumentException.class, () -> git.referenceDir("own/repo", ".."));
        assertThrows(IllegalArgumentException.class, () -> git.referenceDir("own/repo", "a/../b"));
        assertThrows(IllegalArgumentException.class, () -> git.referenceDir("own/repo", "."));
        assertDoesNotThrow(() -> git.referenceDir("own/repo", "release/1.0"));
    }

    private static String sh(Path cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(new ArrayList<>(List.of(cmd))).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertTrue(p.exitValue() == 0, String.join(" ", cmd) + " 실패: " + out);
        return out;
    }
}

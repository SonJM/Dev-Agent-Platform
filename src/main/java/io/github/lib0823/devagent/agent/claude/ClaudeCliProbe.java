package io.github.lib0823.devagent.agent.claude;

import io.github.lib0823.devagent.platform.ChildEnv;
import io.github.lib0823.devagent.platform.PortHealth;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Claude Code CLI 실행 가능 여부 확인({@code claude --version}). LLM 호출이 아니라 토큰을 쓰지 않는다. */
public final class ClaudeCliProbe {

    private ClaudeCliProbe() {
    }

    /**
     * 시스템 프롬프트를 임시 파일로 쓴다({@code --append-system-prompt-file} 용). 인자로 넘기면 본문의 큰따옴표가
     * Windows 인자 파싱을 깨서 뒤쪽이 잘린다. 호출측이 실행 후 {@link #deleteQuietly} 로 지운다.
     */
    public static java.nio.file.Path writePromptFile(String text) {
        try {
            java.nio.file.Path f = java.nio.file.Files.createTempFile("claude-sys-", ".md");
            java.nio.file.Files.writeString(f, text, StandardCharsets.UTF_8);
            f.toFile().deleteOnExit();
            return f;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("시스템 프롬프트 파일 생성 실패: " + e.getMessage(), e);
        }
    }

    public static void deleteQuietly(java.nio.file.Path f) {
        if (f == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(f);
        } catch (java.io.IOException ignore) {
            // 임시 파일 — JVM 종료 때 다시 시도된다
        }
    }

    public static PortHealth.TestResult version(String command) {
        long t0 = System.currentTimeMillis();
        try {
            Process p = ChildEnv.clean(new ProcessBuilder(command, "--version")).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new PortHealth.TestResult(false, "claude --version 이 20초 안에 끝나지 않음", System.currentTimeMillis() - t0);
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            boolean ok = p.exitValue() == 0 && !out.isBlank();
            return new PortHealth.TestResult(ok, ok ? "Claude Code CLI " + out : "실행 실패(exit " + p.exitValue() + "): " + out,
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new PortHealth.TestResult(false, "실행 실패: " + e.getMessage(), System.currentTimeMillis() - t0);
        }
    }
}

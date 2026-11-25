package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claude Code CLI 실행 파일 설정 (prefix: claude). 코딩 에이전트가 CLI 를 띄울 때 쓴다 — 구독 로그인 사용, 별도 API 키 불필요.
 */
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String cliCommand,            // claude 실행 경로(기본 "claude" 면 자동 탐색)
        String exeCommand             // claude.exe 절대경로(cmd.exe 코드페이지 우회, 한글 인자 안전). 비면 cliCommand 기준 자동 탐색
) {

    /** 실행할 바이너리 (exeCommand 우선, 없으면 cliCommand 를 자동 탐색한 경로). */
    public String command() {
        return (exeCommand != null && !exeCommand.isBlank()) ? exeCommand.strip() : ClaudeCliLocator.find(cliCommand);
    }
}

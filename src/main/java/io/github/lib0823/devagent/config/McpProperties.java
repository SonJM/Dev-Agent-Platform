package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사용자 정의 MCP 서버 연동 설정 (prefix: mcp).
 * <p>
 * 관리 화면에서 등록한 사용자 정의 서버를 Claude Code CLI 에 {@code --mcp-config} 로 붙여 이슈 에이전트의
 * 분석/수정 단계에서 쓰게 한다. 내장 서버는 없다.
 * {@code enabled} 는 사용자 정의 서버 기능 전체(테이블 준비·설정 파일 생성), {@code agentEnabled} 는 에이전트 실행에 붙일지.
 */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(
        boolean enabled,
        boolean agentEnabled           // 이슈 에이전트(분석/수정)에 MCP 도구 제공
) {
}

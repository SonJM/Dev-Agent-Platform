package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 봇 동작 설정 (prefix: bot). 지금은 운영 알림(시작·오류)만 남았다.
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Ops ops
) {
    /**
     * 운영 설정. 시작/오류 알림을 "나에게 보내기" 로 전달.
     */
    public record Ops(
            boolean notifyOnStartup,
            int alertCooldownMinutes
    ) {
    }
}

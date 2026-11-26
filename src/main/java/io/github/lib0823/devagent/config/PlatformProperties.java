package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 플랫폼 운영 설정 (prefix: platform). 어댑터 선택 키(messenger, coding-agent)는 {@code @ConditionalOnProperty} 가 읽고,
 * 여기서는 운영 알림(시작·오류)만 묶는다.
 */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(
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

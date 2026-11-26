package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 접근 제어 설정 (prefix: access).
 * allowedOrigins 는 루프백 외에 상태 변경 요청을 허용할 Origin 목록(쉼표 구분, 정확히 일치).
 * 예: {@code https://<MagicDNS 이름>:8443}. 비우면 루프백 Origin 만 허용한다.
 */
@ConfigurationProperties(prefix = "access")
public record AccessProperties(String allowedOrigins) {

    /** 쉼표로 나눈 허용 Origin(공백·빈 항목 제외). */
    public List<String> allowedOriginList() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

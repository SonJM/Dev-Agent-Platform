package io.github.lib0823.devagent.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 포트 구현체(어댑터)의 연결 상태. 모든 포트({@code MessengerPort}, {@code CodingAgent})가
 * {@code health()} 로 보고하고 관리 페이지 "연동" 카드가 보여 준다. 네트워크·LLM 호출 없이 즉시 답해야 한다(실제 호출 검증은 {@code selfTest()}).
 *
 * @param status  ok(정상) / warn(동작하지만 주의) / down(최근 호출 실패·연결 없음)
 * @param summary 한 줄 상태 설명
 * @param details 화면에 그대로 나열할 부가 정보(비밀값 금지)
 */
public record PortHealth(String provider, String status, String summary, Map<String, Object> details) {

    public static final String OK = "ok";
    public static final String WARN = "warn";
    public static final String DOWN = "down";

    public static PortHealth of(String provider, String status, String summary) {
        return new PortHealth(provider, status, summary, new LinkedHashMap<>());
    }

    public PortHealth with(String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    /** 연결 테스트 결과. */
    public record TestResult(boolean ok, String message, long elapsedMs) {
    }
}

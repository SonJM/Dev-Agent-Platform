package io.github.lib0823.devagent.messenger.none;

import io.github.lib0823.devagent.messenger.MessengerPort;
import io.github.lib0823.devagent.messenger.SelfInbox;
import io.github.lib0823.devagent.platform.PortHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 메신저 없이 돌리는 어댑터({@code platform.messenger=none}). 받을 곳도 보낼 곳도 없다(전송은 버린다) — 화면(에이전트·질의·플랫폼)만 쓰는 설치나
 * 메신저 어댑터를 추가하지 않은 기본 설치에서 쓰인다(기본값).
 * 확인·알림은 대시보드로만 오간다.
 */
@Component
@ConditionalOnProperty(prefix = "platform", name = "messenger", havingValue = "none")
public class NoMessengerAdapter implements MessengerPort {

    public static final String PROVIDER = "none";

    private static final Logger log = LoggerFactory.getLogger(NoMessengerAdapter.class);

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public PortHealth health() {
        return PortHealth.of(PROVIDER, PortHealth.WARN, "메신저 미연결 — 알림·확인은 화면에서만. MessengerPort 어댑터를 추가하면 연결");
    }

    @Override
    public String selfId() {
        return "0";
    }

    @Override
    public void connect() {
        // 연결할 곳 없음
    }

    @Override
    public void reset() {
        // 세션 없음
    }

    @Override
    public SelfInbox receiveSelf(String cursor, Duration timeout) {
        return SelfInbox.empty(cursor);   // 받을 곳 없음(수신 루프는 none 이면 시작하지 않는다)
    }

    @Override
    public void sendSelf(String text) {
        log.debug("메신저 미연결 — 전송 생략({}자)", text == null ? 0 : text.length());
    }
}

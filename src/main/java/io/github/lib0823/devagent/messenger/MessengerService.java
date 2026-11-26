package io.github.lib0823.devagent.messenger;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.platform.CallStats;
import io.github.lib0823.devagent.platform.PortHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 메신저 애플리케이션 서비스(코어). 명령 라우터·에이전트·알림은 어댑터가 아니라 이 서비스를 쓴다.
 * 포트가 자기 대화만 다루므로(받기·보내기) 방을 고르거나 그룹에 보내는 경로가 없고, 알림 유형별 켜고 끄기만 여기서 적용한다.
 */
@Service
public class MessengerService {

    private static final Logger log = LoggerFactory.getLogger(MessengerService.class);

    private final MessengerPort port;
    private final SettingService settings;
    private final CallStats stats = new CallStats();
    /** 수신 루프 상태 문자열(running / stopped / died: 예외 클래스). 수신 루프가 갱신하고 health 가 그대로 보여 준다(외부 호출 없음). */
    private volatile String receiverState;

    public MessengerService(MessengerPort port, SettingService settings) {
        this.port = port;
        this.settings = settings;
        log.info("메신저 어댑터: {}", port.provider());
    }

    public String provider() {
        return port.provider();
    }

    public String selfId() {
        return port.selfId();
    }

    /** 어댑터 상태 + 최근 호출 성공/실패. 최근 호출이 실패였으면 down. */
    public PortHealth health() {
        PortHealth h = port.health();
        PortHealth mine = stats.health(h.provider(), h.status(), h.summary());
        h.details().forEach(mine::with);
        mine.with("receiver", receiverState);   // 수신 루프 생존 여부(없으면 표시 안 함)
        return mine;
    }

    /** 연결 테스트: 세션 확인 뒤 자기방에 한 줄 전송. */
    public PortHealth.TestResult selfTest(String text) {
        long t0 = System.currentTimeMillis();
        if ("none".equals(port.provider())) {
            return new PortHealth.TestResult(false, "메신저가 연결돼 있지 않습니다(메신저 어댑터를 추가하고 PLATFORM_MESSENGER 를 지정)", 0);
        }
        try {
            connect();
            sendToSelf(text);
            return new PortHealth.TestResult(true, "자기방으로 테스트 메시지를 보냈습니다", System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            return new PortHealth.TestResult(false, "전송 실패: " + e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    public void connect() {
        stats.track(port::connect);
    }

    public void reset() {
        port.reset();
    }

    // ---------------------------------------------------------------- 자기 대화 수신(라우터용)

    /** 수신 커서를 저장하는 {@code bot_setting} 키의 접두(값 하나. 어댑터가 정한 문자열을 그대로 보관). 실제 키는 {@link #cursorKey()}. */
    public static final String CURSOR_KEY = "messenger_cursor";

    /** 구현체별 커서 키 {@code messenger_cursor.<provider>} — 어댑터를 바꿔도 다른 어댑터의 커서를 잘못 해석하지 않는다. */
    public String cursorKey() {
        return CURSOR_KEY + "." + port.provider();
    }

    public void setReceiverState(String state) {
        this.receiverState = state;
    }

    /** 자기 대화의 새 메시지 수신(차단형, 최대 timeout). */
    public SelfInbox receiveSelf(String cursor, Duration timeout) {
        return stats.track(() -> port.receiveSelf(cursor, timeout));
    }

    /** 저장된 수신 커서(없으면 null). */
    public String loadCursor() {
        return settings.getRaw(cursorKey());
    }

    public void saveCursor(String cursor) {
        settings.setRaw(cursorKey(), cursor);
    }

    // ---------------------------------------------------------------- 플랫폼 알림(유형별 켜고 끄기)

    public static final String NOTICE_ISSUE = "issue";
    public static final String NOTICE_RESEARCH = "research";
    public static final String NOTICE_SYSTEM = "system";

    /**
     * 플랫폼이 스스로 자기방에 보내는 알림의 유형(키 → 화면 이름). 메신저 탭 "메신저 알림" 카드에서 유형별로 끌 수 있다.
     * 사용자가 메신저에서 직접 친 명령(이슈 …·질문 …)의 답은 알림이 아니므로 여기에 넣지 않는다(항상 전송).
     * 새 자기방 알림을 만들면 유형을 여기에 추가하고 {@link #notifySelf} 로 보낼 것 — sendToSelf 를 직접 쓰면 끌 수 없다.
     */
    public static final Map<String, String> NOTICES = notices();

    private static Map<String, String> notices() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(NOTICE_ISSUE, "이슈 에이전트");
        m.put(NOTICE_RESEARCH, "리서치");
        m.put(NOTICE_SYSTEM, "시스템(시작·오류)");
        return m;
    }

    /** 이 유형의 알림을 메신저로 보내는가(기본 켜짐). */
    public boolean notifyOn(String kind) {
        Set<String> off = settings.getMessengerNotifyOff();
        return off == null || !off.contains(kind);
    }

    /** 플랫폼 알림을 자기방으로. 유형이 꺼져 있으면 보내지 않고 false(화면 알림함·각 탭에는 그대로 남는다). */
    public boolean notifySelf(String kind, String message) {
        if (!notifyOn(kind)) {
            log.debug("[알림 꺼짐:{}] 전송 생략 <- {}", kind, preview(message));
            return false;
        }
        sendToSelf(message);
        return true;
    }

    // ---------------------------------------------------------------- 전송(자기 대화만)

    /** "나에게 보내기"(자기방). 항상 허용. */
    public void sendToSelf(String message) {
        log.info("[전송] SELF <- {}", preview(message));
        stats.track(() -> port.sendSelf(message));
    }

    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace("\n", " ");
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}

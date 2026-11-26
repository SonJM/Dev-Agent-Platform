package io.github.lib0823.devagent.messenger;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.platform.PortHealth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 포트가 자기 대화 하나만 다루고, 알림 유형 켜고 끄기가 코어(MessengerService)에서 적용되는지 — 가짜 포트로 확인. */
class MessengerServiceTest {

    /** 메모리 어댑터: 보낸 것을 기록만 한다. 새 메신저 어댑터가 구현해야 할 최소 표면이기도 하다. */
    static class FakePort implements MessengerPort {
        final List<String> sent = new ArrayList<>();
        public String provider() { return "fake"; }
        public PortHealth health() { return PortHealth.of("fake", PortHealth.OK, "ok"); }
        public String selfId() { return "U440"; }
        public void connect() { }
        public void reset() { }
        public SelfInbox receiveSelf(String cursor, java.time.Duration timeout) { return SelfInbox.empty(cursor); }
        public void sendSelf(String text) { sent.add(text); }
    }

    private final FakePort port = new FakePort();
    private final SettingService settings = mock(SettingService.class);
    private final MessengerService svc = new MessengerService(port, settings);

    @Test
    void sendToSelfGoesToSelfConversationOnly() {
        svc.sendToSelf("hi");
        assertEquals(List.of("hi"), port.sent);
    }

    @Test
    void tc011_platformNoticesCanBeTurnedOffPerKind() {
        when(settings.getMessengerNotifyOff()).thenReturn(Set.of(MessengerService.NOTICE_RESEARCH));
        assertFalse(svc.notifySelf(MessengerService.NOTICE_RESEARCH, "[질의] 완료"));
        assertTrue(svc.notifySelf(MessengerService.NOTICE_ISSUE, "[이슈] 확인"));
        svc.sendToSelf("[이슈] 명령의 답은 항상");            // 알림이 아닌 전송은 설정과 무관
        assertEquals(List.of("[이슈] 확인", "[이슈] 명령의 답은 항상"), port.sent);
        assertTrue(svc.notifyOn(MessengerService.NOTICE_SYSTEM));
    }

    @Test
    void tc011_noticeKindsAreIssueResearchSystemOnly() {
        assertEquals(List.of("issue", "research", "system"), List.copyOf(MessengerService.NOTICES.keySet()));
    }
}

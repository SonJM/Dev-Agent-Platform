package io.github.lib0823.devagent.messenger;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.BotProperties;
import io.github.lib0823.devagent.platform.PortHealth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 전송 안전 정책이 어댑터가 아니라 코어(MessengerService)에서 적용되는지 — 가짜 포트로 확인. */
class MessengerServiceTest {

    /** 메모리 어댑터: 보낸 것을 기록만 한다. 새 메신저 어댑터가 구현해야 할 최소 표면이기도 하다. */
    static class FakePort implements MessengerPort {
        final List<String> sent = new ArrayList<>();
        public String provider() { return "fake"; }
        public PortHealth health() { return PortHealth.of("fake", PortHealth.OK, "ok"); }
        public String selfId() { return "U440"; }
        public void connect() { }
        public void reset() { }
        public List<Room> rooms() { return List.of(); }
        public List<IncomingMessage> latest(Room room, int size) { return List.of(); }
        public List<IncomingMessage> after(Room room, String sinceMessageId, int size) { return List.of(); }
        public SelfInbox receiveSelf(String cursor, java.time.Duration timeout) { return SelfInbox.empty(cursor); }
        public void send(RoomKind kind, String roomId, String text) { sent.add(kind + ":" + roomId + ":" + text); }
    }

    private final FakePort port = new FakePort();
    private final SettingService settings = mock(SettingService.class);
    private final BotProperties bot = mock(BotProperties.class, RETURNS_DEEP_STUBS);
    private final MessengerService svc = new MessengerService(port, settings, bot);

    @Test
    void selfRoomIsAlwaysAllowed() {
        when(settings.getAutoRoomKeys()).thenReturn(Set.of());
        svc.sendToSelf("hi");
        svc.sendToRoomChecked("U440", "self", "[AI] ok");
        assertEquals(List.of("SELF:U440:hi", "SELF:U440:[AI] ok"), port.sent);
        assertEquals("self:U440", svc.selfKey());
    }

    @Test
    void platformNoticesCanBeTurnedOffPerKind() {
        when(settings.getMessengerNotifyOff()).thenReturn(Set.of(MessengerService.NOTICE_SUMMARY));
        assertEquals(false, svc.notifySelf(MessengerService.NOTICE_SUMMARY, "[방 요약] …"));
        assertTrue(svc.notifySelf(MessengerService.NOTICE_ISSUE, "[이슈] 확인"));
        svc.sendToSelf("[AI] 명령의 답은 항상");            // 알림이 아닌 전송은 설정과 무관
        assertEquals(List.of("SELF:U440:[이슈] 확인", "SELF:U440:[AI] 명령의 답은 항상"), port.sent);
        assertTrue(svc.notifyOn(MessengerService.NOTICE_SYSTEM));
        assertEquals(5, MessengerService.NOTICES.size());
    }

    @Test
    void otherRoomsNeedWhitelist() {
        when(settings.getAutoRoomKeys()).thenReturn(Set.of("group:C15428"));
        assertThrows(SendBlockedException.class, () -> svc.sendToRoomChecked("D586", "single", "x"));
        assertThrows(SendBlockedException.class, () -> svc.sendAutoReply("D586", "single", "x"));
        svc.sendAutoReply("C15428", "group", "답");
        svc.sendToRoomChecked("C15428", "group", "[AI] 답");
        assertEquals(List.of("GROUP:C15428:[자동응답] 답", "GROUP:C15428:[AI] 답"), port.sent);
    }

    @Test
    void roomModelSendBlocksGroupsAndUnknownSingles() {
        when(bot.safety().blockGroupSend()).thenReturn(true);
        when(bot.safety().allowedSingleRoomIds()).thenReturn(List.of("D586"));
        assertThrows(SendBlockedException.class, () -> svc.send(new Room(RoomKind.GROUP, "C1", "g", 5, null), "x"));
        assertThrows(SendBlockedException.class, () -> svc.send(new Room(RoomKind.SINGLE, "D2", "s", null, null), "x"));
        svc.send(new Room(RoomKind.SINGLE, "D586", "s", null, null), "x");
        assertTrue(port.sent.contains("SINGLE:D586:x"));
        assertEquals("group:C1", new Room(RoomKind.GROUP, "C1", "g", 5, null).key());
    }
}

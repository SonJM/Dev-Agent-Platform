package io.github.lib0823.devagent.command;

import io.github.lib0823.devagent.messenger.IncomingMessage;
import io.github.lib0823.devagent.messenger.SelfMessage;
import io.github.lib0823.devagent.messenger.SelfInbox;
import io.github.lib0823.devagent.messenger.MessengerPort;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.notify.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-011(재수신): 커서를 저장하고 그 뒤부터만 받는다 — 같은 메시지를 두 번 처리하지 않는다(재시작 포함, 글 단위).
 * 수신 루프(전용 스레드)가 예외·Error·어댑터의 즉시 응답에도 살아 있고 멈출 수 있는지.
 */
class MessengerReceiverTest {

    private static final Duration WAIT = Duration.ofMillis(10);
    private static final String CURSOR_KEY = "messenger_cursor.fake";

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final SettingService settings = mock(SettingService.class);
    private final FakeMessengerPort port = new FakeMessengerPort();
    private final MessengerCommandRouter router = mock(MessengerCommandRouter.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();
    private MessengerReceiver started;

    @BeforeEach
    void setUp() {
        when(settings.getRaw(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1))).when(settings).setRaw(anyString(), any());
    }

    @AfterEach
    void tearDown() {
        if (started != null) {
            started.stop();
        }
    }

    private MessengerService messenger(MessengerPort p) {
        return new MessengerService(p, settings);
    }

    private MessengerReceiver receiver(MessengerPort p) {
        return new MessengerReceiver(messenger(p), router, notifications);
    }

    /** 실제로 쉬지 않고(1ms) 요청한 대기 시간만 기록하는 가짜 sleep 으로 루프를 돌린다. */
    private MessengerReceiver startLoop(MessengerPort p, long minGapMs) {
        MessengerReceiver r = receiver(p);
        r.configure(WAIT, minGapMs, ms -> {
            sleeps.add(ms);
            Thread.sleep(1);
        });
        r.start();
        started = r;
        return r;
    }

    // ------------------------------------------------------------------ pollOnce: 커서

    @Test
    void tc011_newMessagesAreRoutedInOrderAndCursorIsSaved() {
        port.userSays("이슈 스캔", "진행");

        int handled = receiver(port).pollOnce(WAIT);

        assertEquals(2, handled);
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(router, times(2)).route(texts.capture());
        assertEquals(List.of("이슈 스캔", "진행"), texts.getAllValues());
        assertEquals("2", store.get(CURSOR_KEY));
    }

    @Test
    void tc011_cursorKeyIsPerProvider() {
        port.userSays("x");
        receiver(port).pollOnce(WAIT);
        assertEquals(java.util.Set.of(CURSOR_KEY), store.keySet());
    }

    @Test
    void tc011_secondPollWithSameCursorProcessesNothing() {
        port.userSays("이슈 스캔");
        MessengerReceiver r = receiver(port);

        r.pollOnce(WAIT);
        int again = r.pollOnce(WAIT);

        assertEquals(0, again);
        verify(router, times(1)).route(anyString());
    }

    @Test
    void tc011_restartKeepsCursorSoNothingIsProcessedTwice() {
        port.userSays("이슈 스캔");
        receiver(port).pollOnce(WAIT);

        MessengerReceiver afterRestart = receiver(port);   // 새 인스턴스, 같은 bot_setting
        port.userSays("질문 batch: x");
        afterRestart.pollOnce(WAIT);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(router, times(2)).route(texts.capture());
        assertEquals(List.of("이슈 스캔", "질문 batch: x"), texts.getAllValues());
    }

    @Test
    void tc011_redeliveryWithoutCursorAdvanceIsIgnored() {
        store.put(CURSOR_KEY, "5");
        MessengerPort stale = new FakeMessengerPort() {
            @Override
            public SelfInbox receiveSelf(String cursor, Duration timeout) {
                IncomingMessage m = new IncomingMessage("5", "U", "나", "이슈 스캔", OffsetDateTime.now(), null);
                return new SelfInbox(List.of(new SelfMessage(m, "5")), "5");
            }
        };

        assertEquals(0, receiver(stale).pollOnce(WAIT));
        verify(router, never()).route(anyString());
    }

    @Test
    void tc011_aFailingMessageDoesNotStopTheNext() {
        port.userSays("첫째", "둘째");
        doThrow(new IllegalStateException("boom")).when(router).route("첫째");

        receiver(port).pollOnce(WAIT);

        verify(router).route("둘째");
        assertEquals("2", store.get(CURSOR_KEY));
    }

    @Test
    void tc011_emptyInboxWithAdvancedCursorStoresCursor() {
        MessengerPort skipping = new FakeMessengerPort() {
            @Override
            public SelfInbox receiveSelf(String cursor, Duration timeout) {
                return new SelfInbox(List.of(), "9");
            }
        };

        assertEquals(0, receiver(skipping).pollOnce(WAIT));
        assertEquals("9", store.get(CURSOR_KEY));
    }

    @Test
    void tc011_cursorIsSavedPerMessage_deathBeforeSecondMessageKeepsItForNextReceive() {
        port.userSays("첫째", "둘째");
        doThrow(new OutOfMemoryError("simulated death")).when(router).route("첫째");   // 첫 글 처리 중 프로세스가 죽는 것을 흉내

        try {
            receiver(port).pollOnce(WAIT);
        } catch (OutOfMemoryError expected) {
            // 죽음
        }
        assertEquals("1", store.get(CURSOR_KEY));   // 묶음 끝(2)이 아니라 첫 글 뒤까지만 저장

        org.mockito.Mockito.reset(router);
        receiver(port).pollOnce(WAIT);              // 재시작 뒤 수신
        verify(router, never()).route("첫째");
        verify(router, times(1)).route("둘째");     // 아직 처리하지 않은 글은 유실되지 않는다
    }

    @Test
    void tc011_stopMidBatchLeavesRemainingMessagesUnprocessedAndCursorUnadvanced() {
        port.userSays("첫째", "둘째");
        MessengerReceiver r = receiver(port);
        doAnswer(i -> {
            r.stop();            // 첫 글 처리 중 종료 요청
            return null;
        }).when(router).route("첫째");

        r.pollOnce(WAIT);

        verify(router, never()).route("둘째");
        assertEquals("1", store.get(CURSOR_KEY));
    }

    // ------------------------------------------------------------------ 수신 루프

    @Test
    void tc011_loopRetriesAfterReceiveFailureWithBackoffAndReset() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch routed = new CountDownLatch(1);
        FakeMessengerPort flaky = new FakeMessengerPort() {
            @Override
            public SelfInbox receiveSelf(String cursor, Duration timeout) {
                if (calls.incrementAndGet() <= 2) {
                    throw new IllegalStateException("https://api.example/bot123456:SECRET/getUpdates 실패");
                }
                return super.receiveSelf(cursor, timeout);
            }
        };
        flaky.userSays("이슈 스캔");
        doAnswer(i -> {
            routed.countDown();
            return null;
        }).when(router).route("이슈 스캔");

        startLoop(flaky, 0);

        assertTrue(routed.await(5, TimeUnit.SECONDS));
        assertTrue(flaky.resets.get() >= 2);
        assertTrue(sleeps.contains(2_000L) && sleeps.contains(4_000L), "백오프 2초 → 4초: " + sleeps);
    }

    @Test
    void tc011_loopSurvivesErrorAndFailingReset() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch routed = new CountDownLatch(1);
        FakeMessengerPort broken = new FakeMessengerPort() {
            @Override
            public SelfInbox receiveSelf(String cursor, Duration timeout) {
                if (calls.incrementAndGet() == 1) {
                    throw new NoClassDefFoundError("x/Y");
                }
                return super.receiveSelf(cursor, timeout);
            }

            @Override
            public void reset() {
                super.reset();
                throw new IllegalStateException("reset 도 실패");
            }
        };
        broken.userSays("진행");
        doAnswer(i -> {
            routed.countDown();
            return null;
        }).when(router).route("진행");

        MessengerReceiver r = startLoop(broken, 0);

        assertTrue(routed.await(5, TimeUnit.SECONDS), "Error·reset 실패 뒤에도 루프가 산다");
        assertTrue(r.threadAlive());
    }

    @Test
    void tc011_stopEndsTheThreadAndHealthShowsReceiverState() {
        MessengerReceiver r = startLoop(port, 0);
        MessengerService svc = messenger(port);   // health 는 같은 서비스 인스턴스 기준이므로 아래에서 다시 만든다

        assertTrue(r.isRunning());
        assertTrue(r.threadAlive());

        r.stop();

        assertFalse(r.isRunning());
        assertFalse(r.threadAlive());
    }

    @Test
    void tc011_healthDetailsExposeReceiverLiveness() {
        MessengerService svc = messenger(port);
        MessengerReceiver r = new MessengerReceiver(svc, router, notifications);
        r.configure(WAIT, 0, ms -> Thread.sleep(1));
        r.start();
        started = r;

        assertEquals("running", svc.health().details().get("receiver"));

        r.stop();

        assertEquals("stopped", svc.health().details().get("receiver"));
    }

    @Test
    void tc011_noneProviderDoesNotStartTheLoop() {
        port.provider = "none";

        MessengerReceiver r = startLoop(port, 0);

        assertFalse(r.isRunning());
        assertFalse(r.threadAlive());
        assertEquals(0, port.receives.get());
    }

    @Test
    void tc011_adapterThatReturnsImmediatelyWithNothingMakesLoopWaitMinimumGap() throws Exception {
        long minGap = 500;
        startLoop(port, minGap);

        long deadline = System.currentTimeMillis() + 5_000;
        while (sleeps.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        assertFalse(sleeps.isEmpty(), "쉬지 않고 도는 중");
        long first = sleeps.get(0);
        assertTrue(first > 0 && first <= minGap, "최소 간격 이내에서 쉰다: " + first);
    }

    @Test
    void tc011_uncaughtHandlerLogsAndRecordsSystemNotification() {
        MessengerReceiver r = receiver(port);

        r.uncaughtHandler().uncaughtException(Thread.currentThread(), new IllegalStateException("fatal"));

        verify(notifications).add(eq("system"), eq("err"), anyString(), any(), any());
    }

    @Test
    void tc011_backoffLogDoesNotLeakExceptionMessage() {
        // 예외 메시지(토큰이 든 URL 등)는 로그·알림에 쓰지 않는다 — 클래스 이름만
        assertEquals("IllegalStateException", MessengerReceiver.describe(new IllegalStateException("bot123:SECRET")));
    }
}

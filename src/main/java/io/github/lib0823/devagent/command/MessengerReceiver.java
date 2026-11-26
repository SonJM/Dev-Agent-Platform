package io.github.lib0823.devagent.command;

import io.github.lib0823.devagent.messenger.SelfMessage;
import io.github.lib0823.devagent.messenger.SelfInbox;
import io.github.lib0823.devagent.messenger.MessengerPort;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 자기 대화 수신 루프. 어댑터의 차단형 {@link MessengerPort#receiveSelf} 를 <b>전용 스레드</b>에서 반복 호출해
 * 받은 글을 {@link MessengerCommandRouter} 로 넘긴다({@code @Scheduled} 스레드를 잡지 않는다).
 * <ul>
 *   <li><b>수명</b>: {@link SmartLifecycle}(기본 phase = 가장 늦게 시작, 가장 먼저 정지). 컨텍스트가 다 뜬 뒤에 시작하고,
 *       종료 때는 스레드가 끝나길 기다린 뒤(상한 {@value #STOP_JOIN_MS}ms) 이슈 워커 등 빈이 내려간다.</li>
 *   <li><b>커서</b>: 코어가 {@code bot_setting.messenger_cursor.<provider>} 에 저장한다. 글마다 그 글 뒤의 커서를
 *       <b>그 글을 처리하기 직전에</b> 저장하므로(글 단위 최대 한 번) 묶음 중간에 죽어도 아직 처리하지 않은 글은 다시 온다.</li>
 *   <li><b>생존</b>: 루프는 {@link Throwable} 까지 잡아 백오프로 재시도한다. 그래도 스레드가 끝나면 uncaught handler 가
 *       ERROR 로그와 시스템 알림을 남긴다. 상태는 {@link MessengerService#health()} 의 {@code receiver} 로 보인다.</li>
 *   <li><b>로그</b>: 어댑터 예외 메시지에는 자격증명이 든 URL 이 있을 수 있어 예외 클래스 이름만 남긴다.</li>
 * </ul>
 * 메신저가 {@code none} 이면 스레드를 띄우지 않는다.
 */
@Component
public class MessengerReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MessengerReceiver.class);

    /** 테스트에서 실제로 쉬지 않고 대기 시간만 기록하기 위한 자리. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** 한 번의 차단형 수신 대기 시간(Telegram getUpdates timeout 에 맞춘 값). */
    static final Duration LONG_POLL = Duration.ofSeconds(25);
    /** 어댑터가 timeout 을 지키지 않고 바로 빈 결과를 돌려줄 때 쉬지 않고 도는 것을 막는 최소 호출 간격. */
    static final long MIN_GAP_MS = 1_000;
    static final long STOP_JOIN_MS = 10_000;
    private static final String PROVIDER_NONE = "none";   // 어댑터 클래스를 참조하지 않고 이름으로만 판별
    private static final long BACKOFF_START_MS = 2_000;
    private static final long BACKOFF_MAX_MS = 60_000;
    private static final String STATE_RUNNING = "running";
    private static final String STATE_STOPPED = "stopped";

    private final MessengerService messenger;
    private final MessengerCommandRouter router;
    private final NotificationService notifications;
    private volatile boolean running;
    private volatile boolean stopping;
    private Thread thread;
    private Duration longPoll = LONG_POLL;
    private long minGapMs = MIN_GAP_MS;
    private Sleeper sleeper = Thread::sleep;

    public MessengerReceiver(MessengerService messenger, MessengerCommandRouter router, NotificationService notifications) {
        this.messenger = messenger;
        this.router = router;
        this.notifications = notifications;
    }

    /** 테스트용: 대기 시간·최소 간격·sleep 구현을 바꾼다(start 전에 호출). */
    void configure(Duration longPoll, long minGapMs, Sleeper sleeper) {
        this.longPoll = longPoll;
        this.minGapMs = minGapMs;
        this.sleeper = sleeper;
    }

    boolean threadAlive() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    /** 예외 클래스 이름만(메시지에는 토큰이 든 URL 이 있을 수 있다). */
    static String describe(Throwable t) {
        return t.getClass().getSimpleName();
    }

    Thread.UncaughtExceptionHandler uncaughtHandler() {
        return (t, e) -> {
            running = false;
            messenger.setReceiverState("died: " + describe(e));
            log.error("메신저 수신 루프가 예기치 않게 끝났습니다({}) — 재시작이 필요합니다", describe(e));
            notifications.add("system", "err", "메신저 수신 루프가 멈췄습니다", describe(e) + " — 앱을 재시작해 주세요", "/platform.html");
        };
    }

    // ------------------------------------------------------------------ SmartLifecycle

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (PROVIDER_NONE.equals(messenger.provider())) {
            log.info("메신저 없음 — 수신 루프를 시작하지 않습니다");
            return;
        }
        stopping = false;
        running = true;
        thread = new Thread(this::loop, "messenger-receiver");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler(uncaughtHandler());
        messenger.setReceiverState(STATE_RUNNING);
        thread.start();
        log.info("메신저 수신 루프 시작 ({})", messenger.provider());
    }

    @Override
    public synchronized void stop() {
        stopping = true;
        running = false;
        Thread t = thread;
        if (t == null) {
            return;
        }
        t.interrupt();
        if (t != Thread.currentThread()) {
            try {
                t.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                log.warn("메신저 수신 루프가 {}ms 안에 끝나지 않았습니다", STOP_JOIN_MS);
            }
        }
        thread = null;
        messenger.setReceiverState(STATE_STOPPED);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ 루프

    private void loop() {
        long backoff = BACKOFF_START_MS;
        while (!stopping) {
            long startedAt = System.nanoTime();
            try {
                messenger.connect();
                int handled = pollOnce(longPoll);
                backoff = BACKOFF_START_MS;
                if (handled == 0 && !waitMinimumGap(startedAt)) {
                    return;
                }
            } catch (Throwable t) {   // NoClassDefFoundError 같은 Error 도 루프를 끝내지 않는다
                if (stopping) {
                    return;
                }
                log.warn("메신저 수신 실패 — {}ms 뒤 재시도: {}", backoff, describe(t));
                resetQuietly();
                if (!pause(backoff)) {
                    return;
                }
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            }
        }
    }

    /** 받은 글이 없었는데 호출이 최소 간격보다 짧게 끝났으면 남은 만큼 쉰다. */
    private boolean waitMinimumGap(long startedAtNanos) {
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        return elapsedMs >= minGapMs || pause(minGapMs - elapsedMs);
    }

    private void resetQuietly() {
        try {
            messenger.reset();
        } catch (Throwable t) {
            log.warn("메신저 세션 초기화 실패: {}", describe(t));
        }
    }

    private boolean pause(long ms) {
        try {
            sleeper.sleep(ms);
            return !stopping;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 한 번 수신해 라우터로 넘긴다.
     *
     * @return 라우터에 넘긴 글 수. 커서가 앞으로 가지 않았으면(같은 글의 재수신) 0 — 처리하지 않는다
     */
    public int pollOnce(Duration timeout) {
        String cursor = messenger.loadCursor();
        SelfInbox inbox = messenger.receiveSelf(cursor, timeout);
        String next = inbox.nextCursor();
        if (next == null || Objects.equals(next, cursor)) {
            return 0;
        }
        int handled = 0;
        for (SelfMessage m : inbox.messages()) {
            if (stopping) {
                return handled;   // 남은 글과 묶음 끝 커서는 저장하지 않는다 — 다음 시작 때 다시 받는다
            }
            if (m.cursorAfter() != null) {
                messenger.saveCursor(m.cursorAfter());   // 처리 직전에 저장: 글 단위 최대 한 번
            }
            String body = m.message().body();
            if (body == null || body.isBlank()) {
                continue;
            }
            handled++;
            routeSafely(body);
        }
        messenger.saveCursor(next);
        return handled;
    }

    private void routeSafely(String body) {
        try {
            router.route(body);
        } catch (RuntimeException e) {
            log.warn("메신저 글 처리 실패(길이 {}): {}", body.length(), describe(e));   // 한 글의 실패가 다음 글을 막지 않는다
        }
    }
}

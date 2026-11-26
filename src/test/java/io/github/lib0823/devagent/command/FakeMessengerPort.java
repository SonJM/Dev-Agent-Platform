package io.github.lib0823.devagent.command;

import io.github.lib0823.devagent.messenger.IncomingMessage;
import io.github.lib0823.devagent.messenger.SelfMessage;
import io.github.lib0823.devagent.messenger.SelfInbox;
import io.github.lib0823.devagent.messenger.MessengerPort;
import io.github.lib0823.devagent.platform.PortHealth;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 수신 규약을 지키는 가짜 어댑터: 커서(정수 문자열) 뒤의 메시지만 돌려주고, 보낸 글을 기록한다. */
class FakeMessengerPort implements MessengerPort {

    final List<String> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger resets = new AtomicInteger();
    final AtomicInteger receives = new AtomicInteger();
    volatile String provider = "fake";
    private final List<String> inbox = new CopyOnWriteArrayList<>();

    /** 사용자가 보낸 글을 쌓는다(번호 = 순번). */
    void userSays(String... texts) {
        inbox.addAll(List.of(texts));
    }

    public String provider() { return provider; }
    public PortHealth health() { return PortHealth.of("fake", PortHealth.OK, "ok"); }
    public String selfId() { return "U440"; }
    public void connect() { }
    public void reset() { resets.incrementAndGet(); }

    public SelfInbox receiveSelf(String cursor, Duration timeout) {
        receives.incrementAndGet();
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        List<SelfMessage> out = new ArrayList<>();
        for (int i = from; i < inbox.size(); i++) {
            IncomingMessage m = new IncomingMessage(String.valueOf(i + 1), "U440", "나", inbox.get(i), OffsetDateTime.now(), null);
            out.add(new SelfMessage(m, String.valueOf(i + 1)));
        }
        return new SelfInbox(out, String.valueOf(inbox.size()));
    }

    public void sendSelf(String text) { sent.add(text); }
}

package io.github.lib0823.devagent.ops;

import io.github.lib0823.devagent.config.PlatformProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 운영 알림: 봇 시작 및 오류를 "나에게 보내기" 로 전달한다.
 * 오류 알림은 쿨다운으로 스팸을 방지한다. (메신저 자체 장애 시엔 전달이 안 될 수 있음 — 로그 병행)
 */
@Component
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MessengerService client;
    private final PlatformProperties bot;
    private final NotificationService notifications;
    private volatile long lastAlertMs = 0L;

    public AlertService(MessengerService client, PlatformProperties bot, NotificationService notifications) {
        this.notifications = notifications;
        this.client = client;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        notifications.add("system", "info", "Dev Agent 시작됨", null, "/platform.html");
        if (!bot.ops().notifyOnStartup() || !client.notifyOn(MessengerService.NOTICE_SYSTEM)) {
            return;
        }
        try {
            client.connect();
            client.sendToSelf("[봇] 시작됨 — " + now());
            log.info("시작 알림 전달");
        } catch (Exception e) {
            log.warn("시작 알림 실패(무시): {}", e.toString());
        }
    }

    /** 오류 알림 (쿨다운 적용). 전달 실패해도 예외를 던지지 않는다. */
    public void alert(String context, Throwable t) {
        long now = System.currentTimeMillis();
        long cooldown = bot.ops().alertCooldownMinutes() * 60_000L;
        if (now - lastAlertMs < cooldown) {
            return;
        }
        lastAlertMs = now;
        notifications.add("system", "err", "오류: " + context, String.valueOf(t), "/platform.html");
        if (!client.notifyOn(MessengerService.NOTICE_SYSTEM)) {
            return;
        }
        try {
            client.connect();
            client.sendToSelf("[봇 오류] " + context + " — " + now() + "\n" + String.valueOf(t));
        } catch (Exception e) {
            log.warn("오류 알림 전달 실패(무시): {}", e.toString());
        }
    }

    private String now() {
        return OffsetDateTime.now().atZoneSameInstant(KST).format(TS);
    }
}

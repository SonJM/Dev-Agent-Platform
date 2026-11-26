package io.github.lib0823.devagent.messenger;

import java.time.OffsetDateTime;

/**
 * 메신저 중립 수신 메시지.
 *
 * @param id      자기 대화 안에서 고유한 메시지 id(어댑터가 정하는 문자열)
 * @param rawJson 어댑터 원본(디버깅·재처리용, 없으면 null)
 */
public record IncomingMessage(String id, String senderId, String senderName, String body, OffsetDateTime sentAt, String rawJson) {
}

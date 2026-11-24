package io.github.lib0823.devagent.messenger;

import java.util.List;

/**
 * 자기 대화 수신 결과({@link MessengerPort#receiveSelf}).
 *
 * @param messages   커서 뒤에 사용자가 보낸 메시지(시간순, 글마다 그 글 뒤의 커서를 함께). 봇이 보낸 글·허용하지 않은 대화의 글은 어댑터가 걸러 낸다
 * @param nextCursor 다음 호출에 넘길 커서(어댑터가 정하는 문자열 — 코어는 해석하지 않고 저장·전달만 한다)
 */
public record SelfInbox(List<SelfMessage> messages, String nextCursor) {

    public SelfInbox {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** 새 메시지 없음. 커서는 그대로. */
    public static SelfInbox empty(String cursor) {
        return new SelfInbox(List.of(), cursor);
    }
}

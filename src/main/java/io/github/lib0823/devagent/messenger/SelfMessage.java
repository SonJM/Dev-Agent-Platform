package io.github.lib0823.devagent.messenger;

/**
 * 자기 대화에서 받은 글 하나와 "이 글까지 처리했다"는 커서.
 *
 * @param message     사용자가 보낸 글
 * @param cursorAfter 이 글 바로 뒤의 커서(Telegram 이면 update_id+1). <b>null 불가</b>(어댑터 규약). 코어는 이 글을 처리하기 직전에 저장한다 —
 *                    묶음 중간에 죽어도 아직 처리하지 않은 글은 다음 수신에서 다시 온다
 */
public record SelfMessage(IncomingMessage message, String cursorAfter) {
}

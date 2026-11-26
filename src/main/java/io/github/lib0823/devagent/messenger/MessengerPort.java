package io.github.lib0823.devagent.messenger;

import io.github.lib0823.devagent.platform.PortHealth;

import java.time.Duration;

/**
 * 메신저 아웃바운드 포트(헥사고날). 코어(명령 라우터·에이전트 알림)는 이 인터페이스만 알고,
 * 실제 메신저(Slack·Discord·Telegram 등)는 어댑터가 구현한다. 어댑터는 {@code platform.messenger} 값으로 하나만 활성화된다.
 * <p>
 * 이 포트는 <b>사용자 자신의 대화 하나</b>만 다룬다: 자기 대화의 새 메시지 받기({@link #receiveSelf})와
 * 자기 대화로 보내기({@link #sendSelf}). 방 목록 조회, 다른 방 읽기, 그룹·다른 사람에게 보내기는 포트에 없다.
 * <p>
 * 규약
 * <ul>
 *   <li>사용자·메시지 id 는 어댑터가 정하는 문자열이다(숫자, Slack 의 {@code 1700000000.000100}, Discord snowflake 등 그대로).
 *       코어는 id 를 같은지만 비교하고 크기·순서를 해석하지 않는다. 64자 이하, {@code :} 는 쓰지 않는다.</li>
 *   <li>{@link #sendSelf} 는 대상을 고르는 인자가 없다. 보낼 곳은 어댑터가 설정에서 아는 "자기 대화" 하나다.</li>
 *   <li>실패는 RuntimeException. 호출측이 {@link #reset()} 뒤 다음 주기에 {@link #connect()} 로 복구한다.</li>
 * </ul>
 */
public interface MessengerPort {

    /** 어댑터 이름(로그·관리 화면 표시). 예: slack, discord */
    String provider();

    /** 연결 상태(즉시 응답 — 네트워크 호출 없이 세션 유무 등 어댑터가 아는 것만). */
    PortHealth health();

    /** 봇 계정 자신의 사용자 id("내가 보낸 메시지" 판별). */
    String selfId();

    /** 세션 보장(없으면 로그인). 이미 연결돼 있으면 아무 일도 하지 않는다. */
    void connect();

    /** 세션 무효화 — 다음 {@link #connect()} 에서 다시 인증. */
    void reset();

    /**
     * 자기 대화의 새 메시지를 받는다(사용자가 보낸 것만, 시간순). 명령 라우터가 전용 스레드에서 반복 호출한다.
     * <ul>
     *   <li><b>차단형 호출을 허용한다</b>: 새 메시지가 없으면 최대 {@code timeout} 동안 기다렸다 빈 결과로 돌려준다
     *       (Telegram {@code getUpdates} 의 {@code offset}·{@code timeout} 에 맞춘 규약). 새 메시지가 오면 즉시 돌려준다.</li>
     *   <li>{@code cursor} 는 직전 결과의 {@link SelfInbox#nextCursor()}(처음이면 null — 처음 시작 정책은 어댑터가 정한다).
     *       코어는 커서를 해석하지 않고 {@code bot_setting.messenger_cursor} 에 저장·전달만 한다.</li>
     *   <li>자기가 보낸 글, 허용하지 않은 대화의 글을 걸러 내는 것은 어댑터 책임이다(코어는 접두어로 거르지 않는다).</li>
     *   <li>글마다 {@link SelfMessage#cursorAfter()} 로 "이 글 바로 뒤의 커서"를 함께 돌려준다(Telegram 은 update_id+1). <b>null 이면 안 된다</b> — null 이면 코어가 그 글의 커서를 저장하지 못해 묶음 중간 유실 방지(글 단위 최대 한 번)가 깨진다.
     *       코어가 글을 처리하기 직전에 그 커서를 저장하므로 묶음 중간에 죽어도 처리하지 않은 글은 유실되지 않는다.
     *       {@link SelfInbox#nextCursor()} 는 묶음 끝 커서다.</li>
     *   <li>메시지가 없어도 {@code nextCursor} 를 앞으로 보낼 수 있다(건너뛴 업데이트 등).</li>
     *   <li>커서는 구현체별로 저장된다(키 {@code messenger_cursor.<provider()>}).</li>
     * </ul>
     * 실패는 RuntimeException(수신 루프가 {@link #reset()} 후 잠시 쉬고 다시 시도한다).
     * <b>예외 메시지에 자격증명·토큰이 든 URL 을 넣지 않는다</b> — 코어는 예외 클래스 이름만 로그에 남기지만, 어댑터 자신의 로그도 같아야 한다.
     */
    SelfInbox receiveSelf(String cursor, Duration timeout);

    /** 자기 대화로 텍스트 전송(대상을 고르는 인자 없음). 정책 검사는 필요 없다 — 보낼 곳이 자기 대화뿐이다. */
    void sendSelf(String text);
}

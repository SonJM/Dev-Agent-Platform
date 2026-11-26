package io.github.lib0823.devagent.command;

import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.issue.IssueAgentService;
import io.github.lib0823.devagent.issue.IssueAgentService.ReplyOutcome;
import io.github.lib0823.devagent.research.ResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자기 대화로 온 글을 첫 단어로 분기하는 명령 라우터(06 §4.2). 저장 없이 어댑터가 넘긴 글을 바로 받는다.
 * <ul>
 *   <li>{@code 이슈 …} → {@link IssueAgentService#command}, 답은 {@code [이슈]} 접두어로 자기 대화에</li>
 *   <li>{@code 질문 …}·{@code 질의 …} → {@link ResearchService#submitFromMessenger}, 답은 {@code [질의]}</li>
 *   <li>그 외 → 확인 대기 답변 후보({@link IssueAgentService#deliverReply}). 대기 세션이 없으면 명령 안내 한 번</li>
 * </ul>
 * 사용자가 보낸 명령의 답은 알림이 아니므로 {@link MessengerService#sendToSelf} 로 항상 보낸다(알림 유형 끄기 대상 아님).
 * 채팅 모델·운영 MCP 에는 의존하지 않는다 — 알 수 없는 글에 모델이 답하는 일은 없다(FR-208).
 */
@Component
public class MessengerCommandRouter {

    private static final Logger log = LoggerFactory.getLogger(MessengerCommandRouter.class);

    static final String ISSUE_PREFIX = "[이슈] ";
    static final String RESEARCH_PREFIX = "[질의] ";
    static final String CMD_ISSUE = "이슈";
    static final String CMD_QUESTION = "질문";
    static final String CMD_QUESTION_ALIAS = "질의";

    static final String GUIDE = """
            [안내] 쓸 수 있는 명령입니다.
            • 이슈 스캔 · 이슈 상태 · 이슈 목록 · 이슈 owner/repo#12 — 이슈 에이전트
            • 이슈 중단 [repo#n] — 작업 중인 이슈 세션 멈춤
            • 질문 <저장소>: <질문> — 소스를 훑어 문서로 답하는 리서치 접수
            • (확인 대기 중일 때) 진행 · 중단 · diff · 상태 · 피드백: … — 대기가 2건 이상이면 앞에 repo#n""";

    static final String FAILURE = "명령을 처리하지 못했습니다. 로그를 확인해 주세요.";

    private static final Pattern FIRST_WORD = Pattern.compile("^(\\S+)(?:\\s+(.*))?$", Pattern.DOTALL);

    private final MessengerService messenger;
    private final IssueAgentService issues;
    private final ResearchService research;

    public MessengerCommandRouter(MessengerService messenger, IssueAgentService issues, ResearchService research) {
        this.messenger = messenger;
        this.issues = issues;
        this.research = research;
    }

    /** 자기 대화의 글 하나를 처리한다. 처리 실패는 예외로 새지 않고 일반 오류 안내로 답한다. */
    public void route(String text) {
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            return;
        }
        Matcher m = FIRST_WORD.matcher(body);
        String word = m.find() ? m.group(1) : body;
        String rest = m.group(2) == null ? "" : m.group(2).strip();
        try {
            switch (word) {
                case CMD_ISSUE -> messenger.sendToSelf(ISSUE_PREFIX + issues.command(rest));
                case CMD_QUESTION, CMD_QUESTION_ALIAS -> messenger.sendToSelf(RESEARCH_PREFIX + submitResearch(rest));
                default -> routeReply(body);
            }
        } catch (RuntimeException e) {
            // 글 내용은 남기지 않는다: 분류(명령 단어이면 그 단어, 아니면 기타)·길이·예외 클래스만
            log.warn("메신저 명령 처리 실패(분류 {}, 길이 {}): {}", kindOf(word), body.length(), MessengerReceiver.describe(e));
            sendQuietly(FAILURE);
        }
    }

    private static String kindOf(String word) {
        return switch (word) {
            case CMD_ISSUE, CMD_QUESTION, CMD_QUESTION_ALIAS -> word;
            default -> "기타";
        };
    }

    private String submitResearch(String rest) {
        try {
            return research.submitFromMessenger(rest);
        } catch (IllegalArgumentException e) {
            return e.getMessage();   // 사용법 오류는 사용자에게 보여 주는 문장이다
        }
    }

    private void routeReply(String body) {
        if (issues.deliverReply(body) == ReplyOutcome.NO_WAITING) {
            messenger.sendToSelf(GUIDE);
        }
    }

    private void sendQuietly(String message) {
        try {
            messenger.sendToSelf(message);
        } catch (RuntimeException e) {
            log.warn("메신저 오류 안내 전송 실패: {}", MessengerReceiver.describe(e));
        }
    }
}

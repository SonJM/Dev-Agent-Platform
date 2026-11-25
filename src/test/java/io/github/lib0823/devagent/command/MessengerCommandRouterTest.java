package io.github.lib0823.devagent.command;

import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.gitlab.IssueAgentService;
import io.github.lib0823.devagent.gitlab.IssueAgentService.ReplyOutcome;
import io.github.lib0823.devagent.gitlab.IssueNotifier;
import io.github.lib0823.devagent.notify.NotificationService;
import io.github.lib0823.devagent.research.ResearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TC-011: 자기 대화 글을 첫 단어로 분기한다(이슈 명령 / 리서치 접수 / 안내). 알림 유형 끄기는 그대로 지켜진다(FR-205). */
class MessengerCommandRouterTest {

    private final FakeMessengerPort port = new FakeMessengerPort();
    private final SettingService settings = mock(SettingService.class);
    private final MessengerService messenger = new MessengerService(port, settings);
    private final IssueAgentService issues = mock(IssueAgentService.class);
    private final ResearchService research = mock(ResearchService.class);
    private MessengerCommandRouter router;

    @BeforeEach
    void setUp() {
        router = new MessengerCommandRouter(messenger, issues, research);
        when(issues.deliverReply(anyString())).thenReturn(ReplyOutcome.NO_WAITING);
    }

    @Test
    void tc011_issueCommandGoesToIssueAgentAndAnswersToSelf() {
        when(issues.command("스캔")).thenReturn("이슈 스캔 완료 — 새 이슈 없음");

        router.route("이슈 스캔");

        verify(issues).command("스캔");
        assertEquals(List.of("[이슈] 이슈 스캔 완료 — 새 이슈 없음"), port.sent);
        verify(issues, never()).deliverReply(anyString());
    }

    @Test
    void tc011_bareIssueWordIsAlsoACommand() {
        when(issues.command("")).thenReturn("도움말");
        router.route("  이슈  ");
        verify(issues).command("");
    }

    @Test
    void tc011_questionCommandSubmitsResearch() {
        when(research.submitFromMessenger("batch: 스케줄 목록")).thenReturn("#7 접수");

        router.route("질문 batch: 스케줄 목록");

        verify(research).submitFromMessenger("batch: 스케줄 목록");
        assertEquals(List.of("[질의] #7 접수"), port.sent);
    }

    @Test
    void tc011_researchUsageErrorIsAnsweredNotThrown() {
        when(research.submitFromMessenger("모호한 글")).thenThrow(new IllegalArgumentException("프로젝트를 지정하세요"));

        router.route("질의 모호한 글");

        assertEquals(List.of("[질의] 프로젝트를 지정하세요"), port.sent);
    }

    @Test
    void tc011_unknownTextWithoutWaitingSessionGetsGuideOnce() {
        router.route("오늘 점심 뭐 먹지");

        assertEquals(1, port.sent.size());
        assertTrue(port.sent.get(0).contains("이슈"));
        assertTrue(port.sent.get(0).contains("질문"));
        assertTrue(port.sent.get(0).contains("이슈 중단"));   // 작업 중인 세션을 멈추는 명령도 안내한다
        verify(issues, never()).command(anyString());
        verify(research, never()).submitFromMessenger(anyString());
    }

    @Test
    void tc011_unknownTextWhileWaitingIsLeftToIssueAgentGuide() {
        when(issues.deliverReply("잡담입니다")).thenReturn(ReplyOutcome.IGNORED);

        router.route("잡담입니다");

        assertTrue(port.sent.isEmpty());   // 대기 단계당 1회 안내는 IssueAgentService 가 한다
    }

    @Test
    void tc011_replyTextIsHandedToIssueAgent() {
        when(issues.deliverReply("진행")).thenReturn(ReplyOutcome.DELIVERED);

        router.route("진행");

        verify(issues).deliverReply("진행");
        assertTrue(port.sent.isEmpty());
    }

    @Test
    void tc011_atAiKeywordIsNoLongerACommand() {
        router.route("@AI 이슈 스캔");

        verify(issues, never()).command(anyString());
        verify(issues).deliverReply("@AI 이슈 스캔");   // 키워드 처리가 없으니 일반 글이다
    }

    @Test
    void tc011_firstWordMustMatchWholeWord() {
        router.route("이슈목록 보여줘");
        verify(issues, never()).command(anyString());
    }

    @Test
    void tc011_commandFailureAnswersGenericMessageWithoutInternals() {
        when(issues.command("스캔")).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/db 연결 실패"));

        router.route("이슈 스캔");

        assertEquals(1, port.sent.size());
        assertFalse(port.sent.get(0).contains("secret-host"));
    }

    @Test
    void tc011_commandAnswersAreSentEvenWhenIssueNoticeIsOff() {
        when(settings.getMessengerNotifyOff()).thenReturn(Set.of(MessengerService.NOTICE_ISSUE));
        when(issues.command("상태")).thenReturn("진행 중 없음");

        router.route("이슈 상태");

        assertEquals(List.of("[이슈] 진행 중 없음"), port.sent);   // 사용자가 친 명령의 답은 알림이 아니다
    }

    @Test
    void tc011_noticeOffSkipsMessengerButKeepsNotificationBox() {
        when(settings.getMessengerNotifyOff()).thenReturn(Set.of(MessengerService.NOTICE_ISSUE));
        NotificationService box = mock(NotificationService.class);
        IssueNotifier notifier = new IssueNotifier(mock(GitLabProperties.class, RETURNS_DEEP_STUBS),
                messenger, null, null, box);

        notifier.sendSelf("[이슈] 확인 대기");

        verify(box).addIssue("[이슈] 확인 대기");
        assertTrue(port.sent.isEmpty());
    }
}

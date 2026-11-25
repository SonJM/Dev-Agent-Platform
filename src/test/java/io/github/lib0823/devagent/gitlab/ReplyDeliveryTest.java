package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.gitlab.IssueAgentService.ReplyOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TC-012: 메신저로 온 글이 확인 대기 세션에 전달되는 규칙(1건/2건, 답변 형식, 세션 참조, 안내 1회). */
class ReplyDeliveryTest {

    private final GitLabProperties props = mock(GitLabProperties.class, RETURNS_DEEP_STUBS);
    private final IssueSessionMapper sessions = mock(IssueSessionMapper.class);
    private final IssueEventBus events = mock(IssueEventBus.class);
    private final IssueNotifier notifier = mock(IssueNotifier.class);
    private IssueAgentService service;

    @BeforeEach
    void setUp() {
        when(props.enabled()).thenReturn(true);
        when(props.concurrencyOr()).thenReturn(1);
        service = new IssueAgentService(props, null, null, null, sessions, null, null, null, events, null,
                null, mock(IssueProjects.class), notifier, null, null, 8777);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();   // 답변 처리 워커 정리
    }

    private static IssueSession waiting(long id, String path, long iid, String state) {
        IssueSession s = new IssueSession();
        s.setId(id);
        s.setProjectPath(path);
        s.setIssueIid(iid);
        s.setState(state);
        return s;
    }

    private void waitingSessions(IssueSession... s) {
        when(sessions.findWaiting()).thenReturn(List.of(s));
    }

    @Test
    void tc012_oneWaitingSession_approveAndFeedbackAreDelivered_chatterIsIgnored() {
        waitingSessions(waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT));

        assertEquals(ReplyOutcome.DELIVERED, service.deliverReply("진행"));
        assertEquals(ReplyOutcome.DELIVERED, service.deliverReply("피드백: 로그를 한 줄 더 남겨 주세요"));
        assertEquals(ReplyOutcome.IGNORED, service.deliverReply("오늘 날씨가 정말 좋네요 여러분"));

        verify(events).emit(eq(1L), eq(IssueEvent.REPLY), anyString(), eq("진행"));
        verify(events).emit(eq(1L), eq(IssueEvent.REPLY), anyString(), eq("로그를 한 줄 더 남겨 주세요"));
        verify(events, times(2)).emit(eq(1L), eq(IssueEvent.REPLY), anyString(), anyString());
    }

    @Test
    void tc012_ignoredChatterGuidesOnlyOncePerWaitStage() {
        waitingSessions(waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT));

        service.deliverReply("오늘 날씨가 정말 좋네요 여러분");
        service.deliverReply("점심은 뭘 먹을까요 다들 같이");

        verify(notifier, times(1)).sendSelf(anyString());
        verify(events, never()).emit(eq(1L), eq(IssueEvent.REPLY), anyString(), anyString());
    }

    @Test
    void tc012_twoWaitingSessions_replyWithoutRefGetsGuideAndIsNotDelivered() {
        waitingSessions(waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT), waiting(2, "acme/api", 3, IssueSession.REVIEW_WAIT));

        assertEquals(ReplyOutcome.NEEDS_REF, service.deliverReply("진행"));

        verify(notifier, times(1)).sendSelf(anyString());
        verify(events, never()).emit(anyLong(), eq(IssueEvent.REPLY), anyString(), anyString());
    }

    @Test
    void tc012_twoWaitingSessions_replyWithRefGoesToThatSessionOnly() {
        waitingSessions(waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT), waiting(2, "acme/api", 3, IssueSession.REVIEW_WAIT));

        assertEquals(ReplyOutcome.DELIVERED, service.deliverReply("shop#7 진행"));
        assertEquals(ReplyOutcome.DELIVERED, service.deliverReply("#3 피드백: 이름을 바꿔 주세요"));

        verify(events).emit(eq(1L), eq(IssueEvent.REPLY), anyString(), eq("진행"));
        verify(events).emit(eq(2L), eq(IssueEvent.REPLY), anyString(), eq("이름을 바꿔 주세요"));
    }

    @Test
    void tc012_noWaitingSession() {
        when(sessions.findWaiting()).thenReturn(List.of());
        assertEquals(ReplyOutcome.NO_WAITING, service.deliverReply("진행"));
        verify(events, never()).emit(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void tc012_disabledAgentHasNoWaitingSession() {
        when(props.enabled()).thenReturn(false);
        assertEquals(ReplyOutcome.NO_WAITING, service.deliverReply("진행"));
    }

    // ------------------------------------------------------------------ 세대 검사(「피드백」 직후 「진행」 방지)

    private static final String STALE_NOTICE = "새 결과를 확인하고 다시 답해";

    /**
     * 워커가 handleReply 를 시작할 때 sessions.find 가 불린다. n 번째 워커 호출에서 "앞 답변 처리가 끝나 같은 대기 상태로 돌아온"
     * 것을 흉내 낸다(transition 으로 대기에 다시 들어가 세대가 오른다). 호출 스레드(테스트)의 find 는 세지 않는다.
     */
    private void stubFindReturningToWaitOnWorkerCall(IssueSession s, int n, String waitState) {
        java.util.concurrent.atomic.AtomicInteger workerCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(sessions.find(s.getId())).thenAnswer(inv -> {
            if (Thread.currentThread().getName().startsWith("issue-agent-") && workerCalls.incrementAndGet() == n) {
                service.transition(s, waitState);
            }
            return s;
        });
    }

    private void verifyApprovalApplied(boolean applied) {
        // 설계 승인이 적용되면 FIXING 으로 전이한다(IssueAgentService.fix 의 첫 동작)
        if (applied) {
            verify(events, timeout(3000)).emit(eq(1L), eq(IssueEvent.STATE), contains("FIXING"), eq("FIXING"));
        } else {
            verify(events, never()).emit(eq(1L), eq(IssueEvent.STATE), contains("FIXING"), eq("FIXING"));
        }
    }

    @Test
    void tc012_approveTwice_secondIsDiscarded() {
        IssueSession s = waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT);
        waitingSessions(s);
        when(sessions.find(1L)).thenReturn(s);

        service.deliverReply("진행");
        service.deliverReply("진행");

        verifyApprovalApplied(true);
        verify(sessions, timeout(3000).times(2)).find(1L);
        verify(events, timeout(3000).times(1)).emit(eq(1L), eq(IssueEvent.STATE), contains("FIXING"), eq("FIXING"));
    }

    @Test
    void tc012_feedbackThenApprove_backInSameWaitState_approvalIsDiscardedWithNotice() {
        IssueSession s = waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT);
        waitingSessions(s);
        stubFindReturningToWaitOnWorkerCall(s, 2, IssueSession.DESIGN_WAIT);   // 피드백 처리 뒤 다시 DESIGN_WAIT

        service.deliverReply("피드백: 이름을 바꿔 주세요");
        service.deliverReply("진행");

        verify(notifier, timeout(3000)).sendSelf(contains(STALE_NOTICE));
        verifyApprovalApplied(false);   // 사용자가 보지 못한 새 설계를 승인하지 않는다
    }

    @Test
    void tc012_dashboardReplyGoesThroughTheSameGenerationCheck() {
        IssueSession s = waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT);
        waitingSessions(s);
        stubFindReturningToWaitOnWorkerCall(s, 1, IssueSession.DESIGN_WAIT);   // 접수 뒤 처리 전에 대기에 다시 들어옴

        service.injectReply("진행", 1L);

        verify(notifier, timeout(3000)).sendSelf(contains(STALE_NOTICE));
        verifyApprovalApplied(false);
    }

    @Test
    void tc012_waitStateReEnteredWhileReadingState_staleApprovalIsDiscardedOnce() {
        IssueSession s = waiting(1, "acme/shop", 7, IssueSession.DESIGN_WAIT);
        when(sessions.find(1L)).thenReturn(s);
        // 상태 조회(findWaiting) 도중에 앞 답변 처리가 끝나 같은 대기 상태로 돌아온 경우: 세대를 조회보다 먼저 읽어야 걸러진다
        when(sessions.findWaiting()).thenAnswer(inv -> {
            service.transition(s, IssueSession.DESIGN_WAIT);
            return List.of(s);
        });

        service.deliverReply("진행");

        verify(notifier, timeout(3000).times(1)).sendSelf(contains(STALE_NOTICE));
        verifyApprovalApplied(false);
    }
}

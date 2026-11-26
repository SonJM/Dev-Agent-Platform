package io.github.lib0823.devagent.issue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 리뷰 대기에서 "검증만 다시" 를 피드백(재수정)이 아니라 검증 재실행으로 읽는지. 실제 사용자 문장이 수정 라운드로 들어갔던 사고(11-13)에서 추가. */
class VerifyOnlyReplyTest {

    @Test
    void recognizesVerifyOnlyPhrases() {
        assertTrue(IssueTexts.isVerifyOnly("검증"));
        assertTrue(IssueTexts.isVerifyOnly("재검증"));
        assertTrue(IssueTexts.isVerifyOnly("검증만 한번 더"));
        assertTrue(IssueTexts.isVerifyOnly("중요한 작업이니 검증만 한번 더 부탁해"));
        assertTrue(IssueTexts.isVerifyOnly("다시 검증 돌려줘"));
        assertTrue(IssueTexts.isVerifyOnly("verify again"));
    }

    @Test
    void feedbackThatMentionsChangesStaysFeedback() {
        assertFalse(IssueTexts.isVerifyOnly("검증 통과하게 테스트 수정해줘"));
        assertFalse(IssueTexts.isVerifyOnly("로그 레벨을 debug 로 바꾸고 검증"));
        assertFalse(IssueTexts.isVerifyOnly("진행"));
        assertFalse(IssueTexts.isVerifyOnly(""));
        assertFalse(IssueTexts.isVerifyOnly("검증 결과를 보니 null 처리가 빠졌다 그 부분 추가하고 StatService 도 같이 봐줘 길게 쓰는 문장은 피드백이다"));
    }

    @Test
    void selfRoomAcceptsVerifyWithoutPrefix() {
        assertEquals("검증만 한번 더", IssueTexts.parseSelfReply("검증만 한번 더"));
        assertEquals("재검증", IssueTexts.parseSelfReply("재검증"));
    }
}

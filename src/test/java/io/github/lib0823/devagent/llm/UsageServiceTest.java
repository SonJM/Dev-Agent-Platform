package io.github.lib0823.devagent.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 사용량 한도 메시지 감지와 리셋 시각 파싱(실측 문구: "You've hit your session limit · resets 2:20pm (Asia/Seoul)"). */
class UsageServiceTest {

    @Test
    void detectsLimitText() {
        assertTrue(UsageService.isLimitText("You've hit your session limit · resets 2:20pm (Asia/Seoul)"));
        assertTrue(UsageService.isLimitText("You've hit your usage limit. Your limit will reset at 9am"));
        assertFalse(UsageService.isLimitText("## 변경 요약\n- 파일 3개 수정"));
        assertFalse(UsageService.isLimitText(null));
    }

    @Test
    void parsesResetTimeTodayOrTomorrow() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Instant now = ZonedDateTime.of(2026, 11, 12, 12, 1, 0, 0, seoul).toInstant();
        Instant until = UsageService.parseReset("You've hit your session limit · resets 2:20pm (Asia/Seoul)", now);
        assertEquals(ZonedDateTime.of(2026, 11, 12, 14, 20, 0, 0, seoul).toInstant(), until);

        Instant late = ZonedDateTime.of(2026, 11, 12, 15, 0, 0, 0, seoul).toInstant();
        assertEquals(ZonedDateTime.of(2026, 11, 13, 14, 20, 0, 0, seoul).toInstant(),
                UsageService.parseReset("resets 2:20pm (Asia/Seoul)", late), "이미 지난 시각이면 내일");

        assertEquals(ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, seoul).toInstant(),
                UsageService.parseReset("limit will reset at 9am", late));
        assertNull(UsageService.parseReset("no time here", now));
    }

    // ---- TC-029: planError 에 로컬 경로가 실리지 않는다

    private static final String HOME = System.getProperty("user.home");
    private static final String SECRET_PATH = HOME + java.io.File.separator + ".claude" + java.io.File.separator + ".credentials.json";

    private static void assertNoPath(String text) {
        assertFalse(text.contains(HOME), text);
        assertFalse(text.contains(SECRET_PATH), text);
    }

    @Test
    void tc029_missingCredentialsFileHasFixedText() {
        String t = UsageService.planErrorText(new java.nio.file.NoSuchFileException(SECRET_PATH));
        assertNoPath(t);
        assertTrue(t.contains("~/.claude/.credentials.json") && t.contains("없음"), t);
    }

    @Test
    void tc029_unreadableCredentialsFileHasFixedText() {
        String t = UsageService.planErrorText(new java.nio.file.AccessDeniedException(SECRET_PATH));
        assertNoPath(t);
        assertTrue(t.contains("읽기 실패"), t);
    }

    @Test
    void tc029_malformedCredentialsJsonHasFixedText() {
        Exception e = new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character at " + SECRET_PATH);
        String t = UsageService.planErrorText(e);
        assertNoPath(t);
        assertTrue(t.contains("형식 오류"), t);
    }

    @Test
    void tc029_malformedApiResponseBodyIsNotReportedAsCredentialsError() {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> UsageService.parseUsageBody("not json {" + SECRET_PATH));
        String t = UsageService.planErrorText(e);
        assertNoPath(t);
        assertFalse(t.contains("인증 파일"), t);
        assertTrue(t.contains("응답 형식 오류"), t);
    }

    @Test
    void tc029_tokenMissingAndHttpStatusKeepCauseText() {
        assertEquals("IllegalStateException: credentials 에 accessToken 없음", UsageService.planErrorText(new IllegalStateException("credentials 에 accessToken 없음")));
        assertEquals("IllegalStateException: HTTP 401", UsageService.planErrorText(new IllegalStateException("HTTP 401")));
        assertTrue(UsageService.planErrorText(new IllegalStateException("HTTP 429")).contains("HTTP 429"));
    }

    @Test
    void tc029_otherExceptionsShowClassNameOnly() {
        String t = UsageService.planErrorText(new java.io.IOException("boom " + SECRET_PATH));
        assertNoPath(t);
        assertEquals("IOException", t);
    }

    @Test
    void tc029_backoffIsFiveMinutesOnlyFor429() {
        assertEquals(300_000, UsageService.retryDelayMs(new IllegalStateException("HTTP 429")));
        assertEquals(60_000, UsageService.retryDelayMs(new IllegalStateException("HTTP 401")));
        assertEquals(60_000, UsageService.retryDelayMs(new java.io.IOException()));
    }
}

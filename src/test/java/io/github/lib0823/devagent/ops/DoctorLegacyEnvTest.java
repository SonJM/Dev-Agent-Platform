package io.github.lib0823.devagent.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-011: Doctor 의 옛 GITLAB_* 키 점검 — 있으면 warn + 고치는 방법(키 이름만), 없으면 ok. */
class DoctorLegacyEnvTest {

    @Test
    void warnsWithKeyNamesOnly_T011() {
        Map<String, Object> c = Doctor.legacyEnvCheck(List.of("GITLAB_ENABLED"));
        assertEquals("warn", c.get("status"));
        assertTrue(String.valueOf(c.get("detail")).contains("GITLAB_ENABLED"));
        assertTrue(String.valueOf(c.get("hint")).contains("ISSUE_CREATE_PR"));
    }

    @Test
    void okWhenNone_T011() {
        Map<String, Object> c = Doctor.legacyEnvCheck(List.of());
        assertEquals("ok", c.get("status"));
        assertFalse(c.containsKey("hint"));
    }
}

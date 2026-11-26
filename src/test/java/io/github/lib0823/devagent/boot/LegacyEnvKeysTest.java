package io.github.lib0823.devagent.boot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-011: 업그레이드 뒤 .env 에 남은 옛 GITLAB_* 키 감지(키 이름만, 값은 다루지 않는다). */
class LegacyEnvKeysTest {

    @Test
    void findsOnlyLegacyKeysSortedAndDeduplicated_T011() {
        List<String> found = LegacyEnvKeys.find(
                Map.of("GITLAB_ENABLED", "true", "ISSUE_ENABLED", "true", "GITLAB_CREATE_MR", "true"),
                Map.of("GITLAB_ENABLED", "x", "PATH", "/bin"));
        assertEquals(List.of("GITLAB_CREATE_MR", "GITLAB_ENABLED"), found);
    }

    @Test
    void emptyWhenNoLegacyKeys_T011() {
        assertTrue(LegacyEnvKeys.find(Map.of("ISSUE_ENABLED", "true"), Map.of()).isEmpty());
    }

    @Test
    void hintNamesKeysButNeverValues_T011() {
        String hint = LegacyEnvKeys.hint(List.of("GITLAB_CREATE_MR", "GITLAB_ENABLED"));
        assertTrue(hint.contains("GITLAB_CREATE_MR") && hint.contains("ISSUE_CREATE_PR") && hint.contains("ISSUE_*"));
        assertFalse(hint.contains("true"));
    }
}

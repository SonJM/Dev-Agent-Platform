package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.config.IssueProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-011: bot_setting 키 개명(옛 키 → projects) 이어받기. */
class SettingServiceLegacyKeyTest {

    private final SettingMapper mapper = mock(SettingMapper.class);
    private final SettingService settings = new SettingService(mapper, mock(IssueProperties.class));

    @Test
    void newKeyWins_T011() {
        when(mapper.get("projects")).thenReturn("o/new");
        assertEquals(List.of("o/new"), settings.getIssueProjects());
        verify(mapper, never()).upsert(anyString(), anyString());
    }

    @Test
    void legacyKeyIsAdoptedOnce_T011() {
        when(mapper.get("projects")).thenReturn(null);
        when(mapper.get("gitlab_projects")).thenReturn("o/a,o/b");
        assertEquals(List.of("o/a", "o/b"), settings.getIssueProjects());
        verify(mapper).upsert("projects", "o/a,o/b");
        assertEquals(List.of("o/a", "o/b"), settings.getIssueProjects());   // 두 번째는 캐시
        verify(mapper).upsert("projects", "o/a,o/b");
    }
}

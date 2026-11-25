package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.config.GitLabProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TC-011: 예전 설치에 저장된 없어진 알림 유형(summary·mention)은 저장할 때 버리고 남은 3개 유형만 다룬다. */
class SettingServiceNotifyTest {

    private static final Set<String> KNOWN = Set.of("issue", "research", "system");

    private final SettingMapper mapper = mock(SettingMapper.class);
    private final SettingService settings = new SettingService(mapper, mock(GitLabProperties.class));

    @Test
    void tc011_storedValueIsReadAsIs() {
        when(mapper.get("messenger_notify_off")).thenReturn("summary,mention,research");

        assertEquals(Set.of("summary", "mention", "research"), settings.getMessengerNotifyOff());
    }

    @Test
    void tc011_savingDropsStaleKinds() {
        when(mapper.get("messenger_notify_off")).thenReturn("summary,issue");

        settings.setMessengerNotify("system", false, KNOWN);

        verify(mapper).upsert(eq("messenger_notify_off"), eq("issue,system"));
    }

    @Test
    void tc011_nothingStoredMeansAllOn() {
        when(mapper.get(anyString())).thenReturn(null);

        assertEquals(Set.of(), settings.getMessengerNotifyOff());
    }
}

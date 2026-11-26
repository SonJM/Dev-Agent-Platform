package io.github.lib0823.devagent.issue;

import io.github.lib0823.devagent.admin.SettingService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-011: kind=GITLAB → REMOTE 개명은 bot_setting 플래그로 한 번만 돈다(IssueAgentService·MaintainService 가 같은 경로). */
class IssueEventBusLegacyKindTest {

    private final IssueEventMapper mapper = mock(IssueEventMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final IssueEventBus bus = new IssueEventBus(mapper, settings);

    @Test
    void renamesOnceThenSetsFlag_T011() {
        when(settings.getRaw(IssueEventBus.KIND_RENAMED_KEY)).thenReturn(null);
        bus.ensureTable();
        verify(mapper).renameLegacyKind();
        verify(settings).setRaw(IssueEventBus.KIND_RENAMED_KEY, "1");
    }

    @Test
    void skipsWhenFlagSet_T011() {
        when(settings.getRaw(IssueEventBus.KIND_RENAMED_KEY)).thenReturn("1");
        bus.ensureTable();
        bus.ensureTable();
        verify(mapper, never()).renameLegacyKind();
        verify(mapper, times(2)).ensureTable();
        verify(mapper, times(2)).ensureIndex();
    }
}

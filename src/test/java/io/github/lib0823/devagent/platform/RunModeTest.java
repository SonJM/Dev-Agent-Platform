package io.github.lib0823.devagent.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunModeTest {

    @Test
    void tc005_systemdModeIsDetectedAndSupervised() {
        String mode = RunMode.detect(Map.of("DEV_AGENT_RUN_MODE", "systemd"));
        assertEquals("systemd", mode);
        assertTrue(RunMode.supervised(mode));
        assertFalse(RunMode.writesStopFlag(mode));
    }

    @Test
    void tc005_existingModesAreUnchanged() {
        assertEquals("launcher", RunMode.detect(Map.of("DEV_AGENT_LAUNCHER", "1")));
        assertEquals("run.cmd", RunMode.detect(Map.of("DEV_AGENT_SUPERVISED", "1")));
        assertEquals("direct", RunMode.detect(Map.of()));
        assertTrue(RunMode.supervised("launcher"));
        assertTrue(RunMode.supervised("run.cmd"));
        assertFalse(RunMode.supervised("direct"));
        assertTrue(RunMode.writesStopFlag("run.cmd"));
        assertFalse(RunMode.writesStopFlag("launcher"));
    }

    @Test
    void tc005_systemdValueToleratesCaseAndTrailingWhitespace() {
        assertEquals("systemd", RunMode.detect(Map.of("DEV_AGENT_RUN_MODE", "SYSTEMD")));
        assertEquals("systemd", RunMode.detect(Map.of("DEV_AGENT_RUN_MODE", "systemd\r")));
        assertEquals("systemd", RunMode.detect(Map.of("DEV_AGENT_RUN_MODE", " systemd ")));
    }

    @Test
    void tc005_launcherWinsOverSystemd() {
        assertEquals("launcher", RunMode.detect(Map.of("DEV_AGENT_LAUNCHER", "1", "DEV_AGENT_RUN_MODE", "systemd")));
    }

    @Test
    void tc005_unknownRunModeValueFallsBackToDirect() {
        assertEquals("direct", RunMode.detect(Map.of("DEV_AGENT_RUN_MODE", "whatever")));
        assertEquals("run.cmd", RunMode.detect(Map.of("DEV_AGENT_RUN_MODE", "whatever", "DEV_AGENT_SUPERVISED", "1")));
    }
}

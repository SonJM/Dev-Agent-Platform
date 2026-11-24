package io.github.lib0823.devagent.agent.claude;

import io.github.lib0823.devagent.config.PreviewProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentShimsTest {

    private static PreviewProperties preview(boolean enabled) {
        return new PreviewProperties(enabled, "", "", "", "", 8790, "", "", "", "", 0, 0, null, null, null, null, true);
    }

    private static List<String> names(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void tc001_linuxWithPreviewOffWritesNothingAndDoesNotCreateBin(@TempDir Path home) throws Exception {
        Path bin = home.resolve("bin");
        List<String> written = AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(false), false);
        assertTrue(written.isEmpty());
        assertFalse(Files.exists(bin));
        assertTrue(AgentShims.install(bin, home, 8777, null, false).isEmpty());
    }

    @Test
    void tc001_linuxNeverOverwritesUsersOwnFile(@TempDir Path home) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.writeString(bin.resolve("jscheck"), "#!/bin/sh\necho mine\n");
        List<String> written = AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(true), false);
        assertEquals("#!/bin/sh\necho mine\n", Files.readString(bin.resolve("jscheck")));
        assertEquals(List.of("jspcheck", "preview"), written.stream().sorted().toList());
    }

    @Test
    void tc001_linuxWithPreviewOnRewritesItsOwnShimIdempotently(@TempDir Path home) throws Exception {
        Path bin = home.resolve("bin");
        assertEquals(3, AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(true), false).size());
        assertEquals(List.of("jscheck", "jspcheck", "preview"), names(bin));
        assertTrue(Files.readString(bin.resolve("preview")).startsWith("#!/usr/bin/env bash\n"));
        // second run: same content is not written again
        assertTrue(AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(true), false).isEmpty());
        // our own shim is refreshed when the port changes
        assertEquals(3, AgentShims.install(bin, home.resolve("scripts/bin"), 9999, preview(true), false).size());
    }

    @Test
    void tc001_linuxDoesNotFollowDanglingSymlinkOutOfBin(@TempDir Path home) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path outside = home.resolve("outside/preview");
        try {
            Files.createSymbolicLink(bin.resolve("preview"), outside);
        } catch (Exception e) {
            assumeTrue(false, "이 환경에서는 심볼릭 링크를 만들 수 없다");
        }
        List<String> written = AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(true), false);
        assertFalse(Files.exists(outside));
        assertFalse(written.contains("preview"));
    }

    @Test
    void tc001_linuxLeavesUnreadableUserFileAndStillWritesTheRest(@TempDir Path home) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        byte[] binary = {(byte) 0xff, (byte) 0xfe, 0x00, (byte) 0xc3};
        Files.write(bin.resolve("preview"), binary);
        List<String> written = AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(true), false);
        assertArrayEquals(binary, Files.readAllBytes(bin.resolve("preview")));
        assertEquals(List.of("jscheck", "jspcheck"), written.stream().sorted().toList());
    }

    @Test
    void windowsStillOverwritesUnmarkedFileAsBefore(@TempDir Path home) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.writeString(bin.resolve("jscheck"), "#!/bin/sh\necho mine\n");
        AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(false), true);
        assertTrue(Files.readString(bin.resolve("jscheck")).contains(AgentShims.MARKER));
    }

    @Test
    void windowsKeepsWritingShimsEvenWithPreviewOff(@TempDir Path home) throws Exception {
        Path bin = home.resolve("bin");
        assertEquals(3, AgentShims.install(bin, home.resolve("scripts/bin"), 8777, preview(false), true).size());
        assertEquals(List.of("jscheck", "jspcheck", "preview"), names(bin));
    }
}

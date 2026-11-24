package io.github.lib0823.devagent.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lib0823.devagent.config.PreviewProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaywrightNodeOsTest {

    private static final PreviewProperties P = new PreviewProperties(true, "", "", "", "", 8790, "", "", "", "", 0, 0, null, null, "0.0.85", "chromium", true);

    @Test
    void tc001_npxIsCalledDirectlyOnLinuxAndWrappedOnWindows() {
        ObjectNode linux = McpConfigService.playwrightNode(P, Path.of("shots"), false);
        assertEquals("npx", linux.get("command").asText());
        assertEquals("-y", linux.get("args").get(0).asText());
        ObjectNode win = McpConfigService.playwrightNode(P, Path.of("shots"), true);
        assertEquals("cmd", win.get("command").asText());
        assertEquals("/c", win.get("args").get(0).asText());
        assertEquals("npx", win.get("args").get(1).asText());
    }
}

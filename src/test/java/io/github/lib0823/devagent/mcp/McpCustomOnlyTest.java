package io.github.lib0823.devagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.config.McpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-018 (TC-014): 내장 운영 MCP 를 지운 뒤에도 사용자 정의 MCP 서버가 에이전트 실행에 붙는다. */
class McpCustomOnlyTest {

    @TempDir
    Path workDir;

    private static McpServer http(String name, String tools) {
        McpServer s = new McpServer();
        s.setName(name);
        s.setType("http");
        s.setUrl("http://localhost:9/mcp");
        s.setTools(tools);
        s.setEnabled(true);
        return s;
    }

    private McpConfigService service(boolean enabled, boolean agentEnabled, McpServer... servers) {
        IssueProperties issueProps = mock(IssueProperties.class);
        when(issueProps.workDir()).thenReturn(workDir.toString());
        McpServerMapper mapper = mock(McpServerMapper.class);
        when(mapper.findAll()).thenReturn(List.of(servers));
        McpConfigService svc = new McpConfigService(new McpProperties(enabled, agentEnabled), issueProps, mapper, null);
        svc.init();
        return svc;
    }

    @Test
    void tc014_formerBuiltInNamesAreNoLongerReserved() {
        assertNull(McpConfigService.validate(http("opensearch", null)));
        assertNull(McpConfigService.validate(http("postgres", null)));
        assertNull(McpConfigService.validate(http("k3s-ops", null)));
        assertNull(McpConfigService.validate(http("playwright", null)));
    }

    @Test
    void tc014_customServerAloneIsAttachedToAgentRun() throws Exception {
        McpConfigService svc = service(true, true, http("opensearch", "search, get_mapping"));

        assertTrue(svc.available());
        assertTrue(svc.agentEnabled());
        assertEquals(List.of("mcp__opensearch__search", "mcp__opensearch__get_mapping"), svc.tools());
        assertEquals(List.of("opensearch"), svc.serverNames());
        Path cfg = svc.configPath();
        assertNotNull(cfg);
        JsonNode servers = new ObjectMapper().readTree(Files.readString(cfg)).path("mcpServers");
        assertEquals(1, servers.size());
        assertTrue(servers.has("opensearch"));
        assertEquals("http://localhost:9/mcp", servers.path("opensearch").path("url").asText());
        assertTrue(svc.usageHint().contains("opensearch"));
        assertFalse(svc.usageHint().contains("운영 데이터"));
    }

    @Test
    void tc014_configHasOnlyCustomServerAndNoPlaywright() throws Exception {
        McpConfigService svc = service(true, true, http("jira", null));

        Path session = svc.configPath();
        assertNotNull(session);
        JsonNode servers = new ObjectMapper().readTree(Files.readString(session)).path("mcpServers");
        assertEquals(1, servers.size());
        assertFalse(servers.has("playwright"));
        assertTrue(servers.has("jira"));
        assertEquals(List.of("mcp__jira"), svc.tools());
    }

    @Test
    void tc014_noCustomServerMeansNothingAttached() {
        McpConfigService svc = service(true, true);

        assertFalse(svc.available());
        assertFalse(svc.agentEnabled());
        assertNull(svc.configPath());
        assertEquals("", svc.usageHint());
    }

    @Test
    void tc014_agentFlagOffKeepsServerOutOfAgent() {
        McpConfigService svc = service(true, false, http("jira", null));

        assertTrue(svc.available());
        assertFalse(svc.agentEnabled());
    }

    @Test
    void tc014_disabledMcpIgnoresRegisteredServers() {
        McpConfigService svc = service(false, true, http("jira", null));

        assertFalse(svc.available());
        assertNull(svc.configPath());
    }
}

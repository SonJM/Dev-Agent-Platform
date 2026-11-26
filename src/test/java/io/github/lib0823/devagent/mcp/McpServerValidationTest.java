package io.github.lib0823.devagent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 사용자 정의 MCP 서버 검증: 이름·유형·URL, 비밀값은 ${ENV} 자리표시자만, 허용 도구 표기. */
class McpServerValidationTest {

    private static McpServer http(String name, String url, String headers, String tools) {
        McpServer s = new McpServer();
        s.setName(name);
        s.setType("http");
        s.setUrl(url);
        s.setHeaders(headers);
        s.setTools(tools);
        return s;
    }

    @Test
    void rejectsBadNames() {
        assertNotNull(McpConfigService.validate(http("Bad_Name", "http://x/mcp", null, null)));
        assertNotNull(McpConfigService.validate(http("jira", "ftp://x", null, null)));
        assertNull(McpConfigService.validate(http("jira", "http://x/mcp", null, null)));
    }

    @Test
    void secretsMustUseEnvPlaceholders() {
        assertNotNull(McpConfigService.validate(http("jira", "http://x/mcp", "Authorization: Bearer abc123", null)));
        assertNotNull(McpConfigService.validate(http("jira", "http://x/mcp", "X-Api-Key: abc123", null)));
        assertNull(McpConfigService.validate(http("jira", "http://x/mcp", "Authorization: Bearer ${JIRA_MCP_TOKEN}\nAccept: application/json", null)));
        assertEquals(List.of("JIRA_MCP_TOKEN", "HOST"), McpConfigService.placeholderNames("Bearer ${JIRA_MCP_TOKEN} http://${HOST}/x ${JIRA_MCP_TOKEN}"));
    }

    @Test
    void allowedToolsPerToolOrWholeServer() {
        McpServer whole = http("jira", "http://x/mcp", null, null);
        assertEquals(List.of("mcp__jira"), whole.allowedTools());
        McpServer some = http("jira", "http://x/mcp", null, "get_issue, search_issues\nlist_projects");
        assertEquals(List.of("mcp__jira__get_issue", "mcp__jira__search_issues", "mcp__jira__list_projects"), some.allowedTools());
        McpServer stdio = new McpServer();
        stdio.setName("files");
        stdio.setType("stdio");
        assertNotNull(McpConfigService.validate(stdio));                 // 명령 없음
        stdio.setCommand("cmd");
        stdio.setArgs("/c\nnpx\n-y\n@modelcontextprotocol/server-filesystem");
        assertNull(McpConfigService.validate(stdio));
        assertEquals(4, stdio.argList().size());
    }
}

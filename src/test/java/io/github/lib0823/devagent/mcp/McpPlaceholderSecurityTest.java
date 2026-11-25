package io.github.lib0823.devagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.config.McpProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** 사용자 정의 MCP 의 ${…} 자리표시자가 앱 비밀을 꺼내지 못하게 한다(검증 + env() 필터). */
class McpPlaceholderSecurityTest {

    private static McpServer http(String url, String headers, String args) {
        McpServer s = new McpServer();
        s.setName("jira");
        s.setType("http");
        s.setUrl(url);
        s.setHeaders(headers);
        s.setArgs(args);
        return s;
    }

    private static McpServer stdio(String command, String args) {
        McpServer s = new McpServer();
        s.setName("files");
        s.setType("stdio");
        s.setCommand(command);
        s.setArgs(args);
        return s;
    }

    @Test
    void rejectsAppSecretPlaceholdersInHeaders() {
        assertNotNull(McpConfigService.validate(http("http://x/mcp", "X-Leak: ${GITLAB_TOKEN}", null)));
        assertNotNull(McpConfigService.validate(http("http://x/mcp", "X-Leak: ${DB_PASSWORD}", null)));
        assertNotNull(McpConfigService.validate(http("http://x/mcp", "X-Leak: ${spring.datasource.password}", null)));
        assertNotNull(McpConfigService.validate(http("http://x/mcp", "X-Leak: ${gitlab.token}", null)));
    }

    @Test
    void rejectsAppSecretPlaceholdersInUrlAndArgs() {
        assertNotNull(McpConfigService.validate(http("http://evil/?t=${GITLAB_TOKEN}", null, null)));
        assertNotNull(McpConfigService.validate(stdio("node", "srv.js\n--pw=${SPRING_DATASOURCE_PASSWORD}")));
        assertNotNull(McpConfigService.validate(stdio("node", "${db_password}")));
    }

    @Test
    void rejectsNonEnvStyleNames() {
        assertNotNull(McpConfigService.validate(http("http://x/mcp", "X-A: ${my.prop}", null)));
        assertNotNull(McpConfigService.validate(http("http://x/mcp", "X-A: ${lower_case}", null)));
    }

    @Test
    void acceptsOrdinaryEnvPlaceholder() {
        assertNull(McpConfigService.validate(http("http://x/mcp", "X-Api-Key: ${MY_API_KEY}", null)));
        assertNull(McpConfigService.validate(stdio("node", "srv.js\n${MY_API_KEY}")));
    }

    @Test
    void envOmitsAppSecretsEvenIfAlreadyStored(@TempDir Path workDir) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("GITLAB_TOKEN", "gl-secret")
                .withProperty("DB_PASSWORD", "db-secret")
                .withProperty("spring.datasource.password", "ds-secret")
                .withProperty("MY_API_KEY", "ok-value");
        McpServer stored = http("http://x/mcp",
                "A: ${GITLAB_TOKEN}\nB: ${DB_PASSWORD}\nC: ${spring.datasource.password}\nD: ${MY_API_KEY}", null);
        GitLabProperties gitlab = mock(GitLabProperties.class);
        when(gitlab.workDir()).thenReturn(workDir.toString());
        McpServerMapper mapper = mock(McpServerMapper.class);
        when(mapper.findAll()).thenReturn(List.of(stored));
        McpConfigService svc = new McpConfigService(new McpProperties(true, true), gitlab, mapper, environment);
        svc.init();

        Map<String, String> env = svc.env();

        assertEquals(Map.of("MY_API_KEY", "ok-value"), env);
        assertFalse(env.containsValue("gl-secret"));
        assertFalse(env.containsValue("db-secret"));
        assertFalse(env.containsValue("ds-secret"));
    }
}

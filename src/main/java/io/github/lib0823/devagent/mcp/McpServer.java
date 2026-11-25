package io.github.lib0823.devagent.mcp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리 페이지에서 등록한 사용자 정의 MCP 서버. 사용 중인 서버가 {@code work/mcp-servers.json} 에 쓰인다.
 * 비밀값은 저장하지 않는다 — 헤더·URL·인자에는 {@code ${ENV_NAME}} 자리표시자만 쓰고 실제 값은 {@code .env} 에 둔다.
 */
public class McpServer {

    private String name;          // 서버 이름(도구 접두어 mcp__<name>__)
    private String type;          // http | sse | stdio
    private String url;           // http/sse
    private String command;       // stdio 실행 파일
    private String args;          // stdio 인자(한 줄에 하나)
    private String headers;       // http/sse 헤더("Key: Value" 한 줄에 하나)
    private String tools;         // 허용 도구 이름(쉼표·공백·줄바꿈 구분). 비우면 서버 단위 허용
    private String description;   // 모델에게 줄 한 줄 안내(무엇을 조회하는 서버인지)
    private boolean enabled = true;
    private OffsetDateTime updatedAt;

    public List<String> argList() {
        return lines(args);
    }

    public Map<String, String> headerMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : lines(headers)) {
            int i = l.indexOf(':');
            if (i > 0) {
                m.put(l.substring(0, i).strip(), l.substring(i + 1).strip());
            }
        }
        return m;
    }

    public List<String> toolList() {
        List<String> out = new ArrayList<>();
        if (tools != null) {
            for (String t : tools.split("[,\\s]+")) {
                if (!t.isBlank()) {
                    out.add(t.strip());
                }
            }
        }
        return out;
    }

    /** --allowedTools 항목: 도구를 지정했으면 도구별, 아니면 서버 단위. */
    public List<String> allowedTools() {
        List<String> ts = toolList();
        if (ts.isEmpty()) {
            return List.of("mcp__" + name);
        }
        return ts.stream().map(t -> "mcp__" + name + "__" + t).toList();
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String l : s.split("\\r?\\n")) {
                if (!l.isBlank()) {
                    out.add(l.strip());
                }
            }
        }
        return out;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args; }
    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }
    public String getTools() { return tools; }
    public void setTools(String tools) { this.tools = tools; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}

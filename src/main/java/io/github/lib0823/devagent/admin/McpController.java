package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.mcp.McpServer;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 사용자 정의 MCP 서버 등록·수정·삭제 API (localhost 전용). */
@RestController
public class McpController {

    private final McpConfigService mcp;
    private final Environment env;

    public McpController(McpConfigService mcp, Environment env) {
        this.mcp = mcp;
        this.env = env;
    }

    // ---------------------------------------------------------------- 사용자 정의 MCP 서버

    /** 등록된 사용자 정의 서버(사용 안 함 포함). 각 항목에 .env 에 없는 자리표시자(missingEnv)를 덧붙인다. */
    @GetMapping("/api/mcp-servers")
    public Map<String, Object> servers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpServer s : mcp.customServers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("type", s.getType());
            m.put("url", s.getUrl());
            m.put("command", s.getCommand());
            m.put("args", s.getArgs());
            m.put("headers", s.getHeaders());
            m.put("tools", s.getTools());
            m.put("description", s.getDescription());
            m.put("enabled", s.isEnabled());
            m.put("allowedTools", s.allowedTools());
            m.put("missingEnv", missingEnv(s));
            m.put("updatedAt", s.getUpdatedAt());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reserved", McpConfigService.RESERVED);
        out.put("servers", list);
        return out;
    }

    /** 등록·수정(이름이 같으면 덮어씀). 저장 즉시 설정 파일을 다시 만들고 다음 Claude 실행부터 반영된다. */
    @PostMapping("/api/mcp-servers")
    public Map<String, Object> save(@RequestParam String name, @RequestParam String type,
                                    @RequestParam(required = false) String url,
                                    @RequestParam(required = false) String command,
                                    @RequestParam(required = false) String args,
                                    @RequestParam(required = false) String headers,
                                    @RequestParam(required = false) String tools,
                                    @RequestParam(required = false) String description,
                                    @RequestParam(defaultValue = "true") boolean enabled) {
        McpServer s = new McpServer();
        s.setName(name);
        s.setType(type);
        s.setUrl(blankToNull(url));
        s.setCommand(blankToNull(command));
        s.setArgs(blankToNull(args));
        s.setHeaders(blankToNull(headers));
        s.setTools(blankToNull(tools));
        s.setDescription(blankToNull(description));
        s.setEnabled(enabled);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            mcp.saveCustom(s);
            List<String> missing = missingEnv(s);
            out.put("ok", true);
            out.put("result", "MCP 서버 '" + s.getName() + "' 저장 — 다음 실행부터 반영"
                    + (missing.isEmpty() ? "" : " (주의: .env 에 " + missing + " 가 없습니다. 추가 후 재시작)"));
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("result", e.getMessage());
        }
        return out;
    }

    @PostMapping("/api/mcp-servers/delete")
    public Map<String, Object> delete(@RequestParam String name) {
        boolean removed = mcp.deleteCustom(name);
        return Map.of("ok", removed, "result", removed ? "MCP 서버 '" + name + "' 삭제" : "'" + name + "' 서버가 없습니다");
    }

    private List<String> missingEnv(McpServer s) {
        List<String> missing = new ArrayList<>();
        for (String n : McpConfigService.placeholderNames(s.getUrl() + "\n" + s.getHeaders() + "\n" + s.getArgs())) {
            String v = env.getProperty(n);
            if (v == null || v.isBlank()) {
                missing.add(n);
            }
        }
        return missing;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}

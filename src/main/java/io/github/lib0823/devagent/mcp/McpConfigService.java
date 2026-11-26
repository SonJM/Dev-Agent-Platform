package io.github.lib0823.devagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.config.McpProperties;
import io.github.lib0823.devagent.platform.ChildEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자 정의 MCP 연동의 공통 부품: Claude Code CLI 용 {@code mcp-servers.json} 생성, 허용 도구 목록, 프로세스 env.
 * <p>
 * 설정 파일에는 비밀값을 적지 않고 {@code ${ENV}} 자리표시자만 둔다(Claude Code 가 env 를 치환 — 실제 호출로 확인).
 * 값은 {@link #env()} 로 자식 프로세스에만 전달한다. 파일 위치는 git 제외 디렉터리(work/).
 */
@Service
public class McpConfigService {

    private static final Logger log = LoggerFactory.getLogger(McpConfigService.class);

    private final McpProperties props;
    private final IssueProperties issueProps;
    private final ObjectMapper json = new ObjectMapper();

    private volatile Path configPath;
    private volatile List<String> tools = List.of();
    /** 관리 페이지에서 등록한 사용자 정의 서버(사용 중인 것만). rebuild() 가 갱신한다. */
    private volatile List<McpServer> custom = List.of();
    private final McpServerMapper serverMapper;
    private final org.springframework.core.env.Environment environment;

    public McpConfigService(McpProperties props, IssueProperties issueProps,
                            McpServerMapper serverMapper, org.springframework.core.env.Environment environment) {
        this.props = props;
        this.issueProps = issueProps;
        this.serverMapper = serverMapper;
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        if (props.enabled() && serverMapper != null) {
            try {
                serverMapper.ensureTable();
            } catch (RuntimeException e) {
                log.warn("mcp_server 테이블 준비 실패(사용자 정의 서버 없이 진행): {}", e.toString());
            }
        }
        rebuild();
    }

    /**
     * 설정 파일과 허용 도구 목록을 다시 만든다(기동 시, 사용자 정의 서버 등록·수정·삭제 뒤). 다음 Claude 실행부터 반영된다.
     * 사용자 정의 서버(DB, 사용 중인 것)만 대상. mcp.enabled=false 면 전부 끈다.
     */
    public synchronized void rebuild() {
        List<McpServer> cs = List.of();
        if (props.enabled() && serverMapper != null) {
            try {
                cs = serverMapper.findAll().stream().filter(McpServer::isEnabled).toList();
            } catch (RuntimeException e) {
                log.warn("사용자 정의 MCP 서버 조회 실패(서버 없이 진행): {}", e.toString());
            }
        }
        custom = cs;
        if (cs.isEmpty()) {
            configPath = null;
            tools = List.of();
            log.info("사용자 정의 MCP 비활성(mcp.enabled=false 또는 서버 없음)");
            return;
        }
        try {
            Path file = writeConfig();
            List<String> t = new ArrayList<>();
            for (McpServer s : cs) {
                t.addAll(s.allowedTools());
            }
            tools = t;
            configPath = file;
            log.info("사용자 정의 MCP 설정 생성 {} — servers={} tools={}", file, serverNames(), t.size());
        } catch (IOException e) {
            log.error("사용자 정의 MCP 설정 파일 생성 실패: {}", e.toString());
            configPath = null;
        }
    }

    /** 사용자 정의 서버 한 건을 설정 파일 노드로. 비밀값은 ${ENV} 자리표시자 그대로 둔다(Claude Code 가 env 로 치환). */
    private ObjectNode customNode(McpServer s) {
        ObjectNode n = json.createObjectNode();
        if ("stdio".equals(s.getType())) {
            n.put("command", s.getCommand().strip());
            var args = n.putArray("args");
            s.argList().forEach(args::add);
        } else {
            n.put("type", s.getType());
            n.put("url", s.getUrl().strip());
            Map<String, String> h = s.headerMap();
            if (!h.isEmpty()) {
                ObjectNode hn = n.putObject("headers");
                h.forEach(hn::put);
            }
        }
        return n;
    }

    /** 텍스트 안의 ${NAME} 자리표시자 이름들. */
    public static List<String> placeholderNames(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int i = 0;
        while ((i = text.indexOf("${", i)) >= 0) {
            int e = text.indexOf('}', i);
            if (e < 0) {
                break;
            }
            String name = text.substring(i + 2, e).strip();
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
            i = e + 1;
        }
        return out;
    }

    /** 등록·수정. 검증 실패는 IllegalArgumentException(메시지를 그대로 화면에). 저장 뒤 설정을 다시 만든다. */
    public void saveCustom(McpServer s) {
        String err = validate(s);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        serverMapper.upsert(s);
        rebuild();
    }

    public boolean deleteCustom(String name) {
        boolean removed = serverMapper.delete(name) > 0;
        rebuild();
        return removed;
    }

    /**
     * 사용자 정의 서버 검증. 문제가 없으면 null.
     * 비밀값 규칙: 인증·토큰·키류 헤더 값은 반드시 ${ENV_NAME} 자리표시자를 써야 한다(실제 값은 .env 에만).
     */
    static String validate(McpServer s) {
        String placeholderErr = validatePlaceholders(s);
        if (placeholderErr != null) {
            return placeholderErr;
        }
        String name = s.getName() == null ? "" : s.getName().strip();
        if (!name.matches("[a-z0-9][a-z0-9-]{1,30}")) {
            return "이름은 영소문자·숫자·하이픈 2~31자여야 합니다(도구 접두어 mcp__<이름>__ 로 쓰입니다).";
        }
        s.setName(name);
        String type = s.getType() == null ? "" : s.getType().strip().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("http", "sse", "stdio").contains(type)) {
            return "유형은 http, sse, stdio 중 하나여야 합니다.";
        }
        s.setType(type);
        if ("stdio".equals(type)) {
            if (s.getCommand() == null || s.getCommand().isBlank()) {
                return "stdio 서버는 실행 명령이 필요합니다(Windows 의 npx 는 명령 cmd, 인자 /c npx … 로).";
            }
        } else {
            String url = s.getUrl() == null ? "" : s.getUrl().strip();
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                return "URL 은 http:// 또는 https:// 로 시작해야 합니다.";
            }
            s.setUrl(url);
            for (Map.Entry<String, String> h : s.headerMap().entrySet()) {
                String k = h.getKey().toLowerCase(java.util.Locale.ROOT);
                boolean secret = k.contains("authorization") || k.contains("token") || k.contains("key") || k.contains("secret") || k.contains("cookie");
                if (secret && !h.getValue().contains("${")) {
                    return "헤더 '" + h.getKey() + "' 의 값에는 비밀값을 직접 쓰지 말고 ${ENV_NAME} 자리표시자를 쓰세요(실제 값은 .env).";
                }
            }
        }
        for (String t : s.toolList()) {
            if (!t.matches("[A-Za-z0-9_.-]+")) {
                return "도구 이름 '" + t + "' 에 쓸 수 없는 문자가 있습니다.";
            }
        }
        return null;
    }

    /** 자리표시자 이름 형식: 대문자·숫자·밑줄의 환경변수 이름만(점이 든 Spring 속성명 거부). */
    private static final java.util.regex.Pattern ENV_NAME = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

    /** 이 이름의 ${…} 를 자식 프로세스 env 로 풀어도 되는지: 환경변수 형식이고 앱 비밀 키가 아니어야 한다. */
    static boolean isAllowedPlaceholder(String name) {
        return ENV_NAME.matcher(name).matches() && !ChildEnv.isSecretKey(name);
    }

    /** url·headers·args·command 의 모든 ${…} 이름 검사. 문제가 없으면 null. */
    private static String validatePlaceholders(McpServer s) {
        String all = s.getUrl() + "\n" + s.getHeaders() + "\n" + s.getArgs() + "\n" + s.getCommand();
        for (String p : placeholderNames(all)) {
            if (!isAllowedPlaceholder(p)) {
                return "자리표시자 '${" + p + "}' 는 쓸 수 없습니다. 대문자·숫자·밑줄 이름의 환경변수만 쓸 수 있고, 앱 자신의 비밀(DB·GitHub 토큰 등)은 참조할 수 없습니다.";
            }
        }
        return null;
    }

    /** 사용자 정의 서버 목록(관리 화면용, 사용 안 함 포함). */
    public List<McpServer> customServers() {
        if (serverMapper == null) {
            return List.of();
        }
        return serverMapper.findAll();
    }

    /** 사용 가능 여부(설정 파일이 생성되었고 서버가 1개 이상). */
    public boolean available() {
        return configPath != null && !tools.isEmpty();
    }

    public Path configPath() {
        return configPath;
    }

    /** --allowedTools 에 추가할 사용자 정의 서버 도구 목록(도구를 지정했으면 도구별, 아니면 서버 단위). */
    public List<String> tools() {
        return tools;
    }

    /** 자식 프로세스(claude.exe)에 넣을 env — 사용자 정의 서버의 비밀값은 여기로만 흐른다. */
    public Map<String, String> env() {
        Map<String, String> m = new LinkedHashMap<>();
        // 사용자 정의 서버가 참조하는 ${NAME} 은 봇 설정(.env)에서 찾아 자식 env 로만 넘긴다
        for (McpServer s : custom) {
            for (String name : placeholderNames(s.getUrl() + "\n" + s.getHeaders() + "\n" + s.getArgs())) {
                if (!isAllowedPlaceholder(name)) {
                    // 이미 저장된 서버도 앱 비밀은 받지 못한다. 값은 로그에 쓰지 않는다.
                    log.warn("MCP 서버 '{}' 의 자리표시자 '{}' 는 허용되지 않아 빈 값으로 둔다", s.getName(), name);
                    continue;
                }
                String v = environment == null ? null : environment.getProperty(name);
                if (v != null && !v.isBlank()) {
                    m.put(name, v);
                }
            }
        }
        return m;
    }

    public List<String> serverNames() {
        List<String> n = new ArrayList<>();
        custom.forEach(s -> n.add(s.getName()));
        return n;
    }

    /** 이슈 에이전트에 사용자 정의 MCP 를 줄지. */
    public boolean agentEnabled() {
        return available() && props.agentEnabled();
    }

    private Path workDir() {
        return Path.of(issueProps.workDir() == null || issueProps.workDir().isBlank() ? "work" : issueProps.workDir()).toAbsolutePath();
    }

    /**
     * 모델에게 주는 MCP 도구 안내. 시스템 프롬프트에 그대로 붙인다.
     */
    public String usageHint() {
        if (!available()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("관리자가 등록한 MCP 도구를 쓸 수 있습니다: ");
        sb.append(String.join(", ", serverNames())).append(".\n");
        for (McpServer s : custom) {
            sb.append("- ").append(s.getName()).append(": ")
                    .append(s.getDescription() == null || s.getDescription().isBlank() ? "관리자가 등록한 MCP 서버" : s.getDescription().strip()).append("\n");
        }
        sb.append("도구 결과는 요약해서 전달하고, 조회에 실패하면 어떤 도구가 왜 실패했는지 한 줄로 적으세요.");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 내부

    private Path writeConfig() throws IOException {
        ObjectNode root = json.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        for (McpServer s : custom) {
            servers.set(s.getName(), customNode(s));
        }
        Path dir = Path.of(issueProps.workDir() == null || issueProps.workDir().isBlank() ? "work" : issueProps.workDir()).toAbsolutePath();
        Files.createDirectories(dir);
        Path file = dir.resolve("mcp-servers.json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        return file;
    }
}

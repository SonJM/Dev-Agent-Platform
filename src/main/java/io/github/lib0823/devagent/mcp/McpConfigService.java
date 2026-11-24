package io.github.lib0823.devagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.config.McpProperties;
import io.github.lib0823.devagent.config.PreviewProperties;
import io.github.lib0823.devagent.platform.Os;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영 MCP 연동의 공통 부품: Claude Code CLI 용 {@code mcp-servers.json} 생성, 허용 도구 목록, 프로세스 env, 헬스체크.
 * <p>
 * 설정 파일에는 k3s 토큰을 적지 않고 {@code ${K3S_MCP_TOKEN}} 자리표시자만 둔다(Claude Code 가 env 를 치환 — 실제 호출로 확인).
 * 토큰은 {@link #env()} 로 자식 프로세스에만 전달한다. 파일 위치는 git 제외 디렉터리(work/).
 */
@Service
public class McpConfigService {

    private static final Logger log = LoggerFactory.getLogger(McpConfigService.class);
    public static final String K3S_TOKEN_ENV = "K3S_MCP_TOKEN";

    private final McpProperties props;
    private final GitLabProperties gitlab;
    private final PreviewProperties preview;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Path> sessionConfigs = new java.util.concurrent.ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private volatile Path configPath;
    private volatile List<String> tools = List.of();
    /** 관리 페이지에서 등록한 사용자 정의 서버(사용 중인 것만). rebuild() 가 갱신한다. */
    private volatile List<McpServer> custom = List.of();
    private final McpServerMapper serverMapper;
    private final org.springframework.core.env.Environment environment;

    /** 내장 서버 이름 — 사용자 정의 서버가 쓸 수 없다. */
    public static final List<String> RESERVED = List.of("opensearch", "postgres", "k3s-ops", "playwright");

    public McpConfigService(McpProperties props, GitLabProperties gitlab, PreviewProperties preview,
                            McpServerMapper serverMapper, org.springframework.core.env.Environment environment) {
        this.props = props;
        this.gitlab = gitlab;
        this.preview = preview;
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
        if (props.k3sUrl() != null && !props.k3sUrl().isBlank() && !props.hasK3s()) {
            log.warn("k3s-ops MCP 는 토큰(K3S_MCP_TOKEN)이 없어 제외됨");
        }
    }

    /**
     * 설정 파일과 허용 도구 목록을 다시 만든다(기동 시, 사용자 정의 서버 등록·수정·삭제 뒤). 다음 Claude 실행부터 반영된다.
     * 내장 서버(설정값) + 사용자 정의 서버(DB, 사용 중인 것). mcp.enabled=false 면 전부 끈다.
     */
    public synchronized void rebuild() {
        List<McpServer> cs = List.of();
        if (props.enabled() && serverMapper != null) {
            try {
                cs = serverMapper.findAll().stream().filter(McpServer::isEnabled).filter(s -> !RESERVED.contains(s.getName())).toList();
            } catch (RuntimeException e) {
                log.warn("사용자 정의 MCP 서버 조회 실패(내장 서버만 사용): {}", e.toString());
            }
        }
        custom = cs;
        sessionConfigs.clear();
        if (!props.anyServer() && cs.isEmpty()) {
            configPath = null;
            tools = List.of();
            log.info("운영 MCP 비활성(mcp.enabled=false 또는 서버 없음)");
            // 운영 MCP 가 없어도 프리뷰(playwright)용 세션 설정은 configFor() 가 만든다
            return;
        }
        try {
            Path file = writeConfig();
            List<String> t = new ArrayList<>(buildTools());
            for (McpServer s : cs) {
                t.addAll(s.allowedTools());
            }
            tools = t;
            configPath = file;
            log.info("운영 MCP 설정 생성 {} — servers={} tools={}", file, serverNames(), t.size());
        } catch (IOException e) {
            log.error("운영 MCP 설정 파일 생성 실패: {}", e.toString());
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

    /** ${NAME} 을 실제 값으로 바꾼 문자열(헬스체크용 — 메모리에서만 쓰고 저장·로그하지 않는다). */
    private String resolve(String text) {
        String out = text;
        for (String name : placeholderNames(text)) {
            String v = environment == null ? null : environment.getProperty(name);
            if (v != null) {
                out = out.replace("${" + name + "}", v);
            }
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
        String name = s.getName() == null ? "" : s.getName().strip();
        if (!name.matches("[a-z0-9][a-z0-9-]{1,30}")) {
            return "이름은 영소문자·숫자·하이픈 2~31자여야 합니다(도구 접두어 mcp__<이름>__ 로 쓰입니다).";
        }
        if (RESERVED.contains(name)) {
            return "'" + name + "' 는 내장 서버 이름이라 쓸 수 없습니다.";
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

    /** --allowedTools 에 추가할 읽기 전용 MCP 도구 목록. opensearch/postgres 는 서버 단위(계정이 읽기 전용), k3s 는 조회 도구만. */
    public List<String> tools() {
        return tools;
    }

    /** 자식 프로세스(claude.exe)에 넣을 env — 토큰은 여기로만 흐른다. */
    public Map<String, String> env() {
        Map<String, String> m = new LinkedHashMap<>();
        if (props.hasK3s()) {
            m.put(K3S_TOKEN_ENV, props.k3sToken().strip());
        }
        // 사용자 정의 서버가 참조하는 ${NAME} 은 봇 설정(.env)에서 찾아 자식 env 로만 넘긴다
        for (McpServer s : custom) {
            for (String name : placeholderNames(s.getUrl() + "\n" + s.getHeaders() + "\n" + s.getArgs())) {
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
        if (props.hasOpensearch()) {
            n.add("opensearch");
        }
        if (props.hasPostgres()) {
            n.add("postgres");
        }
        if (props.hasK3s()) {
            n.add("k3s-ops");
        }
        custom.forEach(s -> n.add(s.getName()));
        return n;
    }

    /** 이슈 에이전트에 MCP 를 줄지. */
    public boolean agentEnabled() {
        return available() && props.agentEnabled();
    }

    public boolean playwrightEnabled() {
        return preview != null && preview.enabled();
    }

    /** playwright MCP 는 브라우저를 봇 호스트에서 띄우므로 서버 단위로 허용. */
    public List<String> playwrightTools() {
        return playwrightEnabled() ? List.of("mcp__playwright") : List.of();
    }

    /**
     * 이슈 세션용 설정 파일: 운영 서버(agentEnabled 일 때) + playwright(프리뷰 켜짐일 때, 스크린샷 출력 폴더 세션별).
     * 둘 다 없으면 null. 파일은 work/mcp/ 아래에 캐시된다.
     */
    public Path configFor(Path shotsDir) {
        boolean ops = agentEnabled();
        boolean pw = playwrightEnabled() && shotsDir != null;
        if (!ops && !pw) {
            return null;
        }
        String key = (ops ? "ops" : "noops") + "|" + (pw ? shotsDir.toAbsolutePath() : "");
        return sessionConfigs.computeIfAbsent(key, k -> {
            try {
                ObjectNode root = json.createObjectNode();
                ObjectNode servers = root.putObject("mcpServers");
                if (ops) {
                    servers.setAll((ObjectNode) json.readTree(Files.readString(configPath, StandardCharsets.UTF_8)).path("mcpServers"));
                }
                if (pw) {
                    Files.createDirectories(shotsDir);
                    servers.set("playwright", playwrightNode(preview, shotsDir));
                }
                Path dir = workDir().resolve("mcp");
                Files.createDirectories(dir);
                Path file = dir.resolve("mcp-session-" + Integer.toHexString(key.hashCode()) + ".json");
                Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
                return file;
            } catch (IOException e) {
                log.warn("세션 MCP 설정 생성 실패: {}", e.toString());
                return configPath;
            }
        });
    }

    /** Playwright MCP(stdio) 서버 정의. Windows 에서만 npx 를 cmd /c 로 감싼다(실제 호출로 확인). Linux/macOS 는 npx 를 직접. */
    public static ObjectNode playwrightNode(PreviewProperties p, Path shotsDir) {
        return playwrightNode(p, shotsDir, Os.isWindows());
    }

    static ObjectNode playwrightNode(PreviewProperties p, Path shotsDir, boolean windows) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        var args = n.putArray("args");
        if (windows) {
            n.put("command", "cmd");
            args.add("/c").add("npx");
        } else {
            n.put("command", "npx");
        }
        args.add("-y").add("@playwright/mcp@" + (p.playwrightVersion() == null || p.playwrightVersion().isBlank() ? "latest" : p.playwrightVersion().strip()));
        args.add("--browser").add(p.browser() == null || p.browser().isBlank() ? "msedge" : p.browser().strip());
        if (p.headless()) {
            args.add("--headless");
        }
        args.add("--isolated");
        args.add("--output-dir").add(shotsDir.toAbsolutePath().toString());
        return n;
    }

    private Path workDir() {
        return Path.of(gitlab.workDir() == null || gitlab.workDir().isBlank() ? "work" : gitlab.workDir()).toAbsolutePath();
    }

    /**
     * 모델에게 주는 운영 데이터 안내(가이드 5장 요약). 프롬프트에 그대로 붙인다.
     */
    public String usageHint() {
        if (!available()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("운영 데이터 조회용 MCP 도구(모두 읽기 전용)를 쓸 수 있습니다: ");
        sb.append(String.join(", ", serverNames())).append(".\n");
        if (props.hasOpensearch()) {
            sb.append("- opensearch: 검색 전에 IndexMappingTool 로 매핑을 먼저 확인. "
                    + "날짜 range 엔 \"format\":\"strict_date_optional_time||epoch_millis\" 포함. "
                    + "일 단위로 나뉜 인덱스는 와일드카드 대신 날짜로 지정.\n");
        }
        if (props.hasPostgres()) {
            sb.append("- postgres: restricted(읽기 전용) 모드, execute_sql 30초 제한. list_schemas 로 접속 대상을 먼저 확인.\n");
        }
        if (props.hasK3s()) {
            sb.append("- k3s-ops: 조회 도구만 허용(get_nodes/get_pods/get_logs/describe_pod/get_events 등). 변경 도구는 호출 불가.\n");
        }
        for (McpServer s : custom) {
            sb.append("- ").append(s.getName()).append(": ")
                    .append(s.getDescription() == null || s.getDescription().isBlank() ? "관리자가 등록한 MCP 서버" : s.getDescription().strip()).append("\n");
        }
        sb.append("도구 결과는 요약해서 전달하고, 조회에 실패하면 어떤 도구가 왜 실패했는지 한 줄로 적으세요.");
        return sb.toString();
    }

    /** 각 서버에 MCP initialize 를 보내 연결 가능 여부를 본다(관리 페이지 상태 카드용). */
    public List<Map<String, Object>> health() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (props.hasOpensearch()) {
            out.add(probeHttp("opensearch", props.opensearchUrl(), null));
        }
        if (props.hasPostgres()) {
            out.add(probeSse("postgres", props.postgresUrl()));
        }
        if (props.k3sUrl() != null && !props.k3sUrl().isBlank()) {
            if (props.hasK3s()) {
                out.add(probeHttp("k3s-ops", props.k3sUrl(), "Bearer " + props.k3sToken().strip()));
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", "k3s-ops");
                m.put("url", props.k3sUrl());
                m.put("ok", false);
                m.put("detail", "토큰 없음(K3S_MCP_TOKEN) — 제외됨");
                out.add(m);
            }
        }
        for (McpServer s : custom) {
            Map<String, Object> m;
            if ("stdio".equals(s.getType())) {
                m = new LinkedHashMap<>();
                m.put("name", s.getName());
                m.put("url", s.getCommand());
                m.put("ok", true);
                m.put("detail", "stdio — 실행 시 기동(연결 확인 생략)");
            } else if ("sse".equals(s.getType())) {
                m = probeSse(s.getName(), resolve(s.getUrl()));
                m.put("url", s.getUrl());
            } else {
                String auth = s.headerMap().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                        .map(e -> resolve(e.getValue())).findFirst().orElse(null);
                m = probeHttp(s.getName(), resolve(s.getUrl()), auth);
                m.put("url", s.getUrl());
            }
            m.put("custom", true);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> probeHttp(String name, String url, String auth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("url", url);
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"dev-agent\",\"version\":\"0\"}}}"));
            if (auth != null) {
                b.header("Authorization", auth);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = r.statusCode() == 200 && r.body().contains("serverInfo");
            m.put("ok", ok);
            m.put("detail", ok ? serverInfo(r.body()) : "HTTP " + r.statusCode());
        } catch (Exception e) {
            m.put("ok", false);
            m.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        return m;
    }

    private Map<String, Object> probeSse(String name, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("url", url);
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
                    .header("Accept", "text/event-stream").GET().build();
            HttpResponse<java.io.InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String s;
            try (java.io.InputStream in = r.body()) {
                // SSE 는 첫 이벤트("event: endpoint …") 뒤 스트림이 열린 채 멈추므로, 빈 줄(이벤트 끝)까지만 별도 스레드로 읽고 3초 내 못 받으면 포기
                StringBuffer sb = new StringBuffer();            // 리더 스레드와 공유(타임아웃 시 부분 내용 사용)
                java.util.concurrent.CompletableFuture<Void> first = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        int b;
                        while (sb.length() < 400 && (b = in.read()) >= 0) {
                            sb.append((char) b);
                            if (b == '\n' && sb.indexOf("endpoint") >= 0 && sb.indexOf("data:") >= 0) {
                                break;                          // "event: endpoint\r\ndata: /messages/?…\r\n" 까지 받으면 충분(CRLF/LF 무관)
                            }
                        }
                    } catch (IOException ignore) {
                        // 스트림 닫힘
                    }
                });
                try {
                    first.get(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    // 부분 수신분으로 판단
                }
                s = sb.toString();
            }
            boolean ok = r.statusCode() == 200 && s.contains("endpoint");
            m.put("ok", ok);
            m.put("detail", ok ? "SSE endpoint 수신" : "HTTP " + r.statusCode());
        } catch (Exception e) {
            m.put("ok", false);
            m.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        m.put("elapsedMs", System.currentTimeMillis() - t0);
        return m;
    }

    private String serverInfo(String body) {
        int i = body.indexOf("\"serverInfo\"");
        if (i < 0) {
            return "connected";
        }
        int end = body.indexOf('}', i);
        return end > i ? body.substring(i + 13, Math.min(end + 1, i + 120)).replace("\"", "") : "connected";
    }

    // ---------------------------------------------------------------- 내부

    private Path writeConfig() throws IOException {
        ObjectNode root = json.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        if (props.hasOpensearch()) {
            ObjectNode n = servers.putObject("opensearch");
            n.put("type", "http");
            n.put("url", props.opensearchUrl().strip());
        }
        if (props.hasPostgres()) {
            ObjectNode n = servers.putObject("postgres");
            n.put("type", "sse");
            n.put("url", props.postgresUrl().strip());
        }
        if (props.hasK3s()) {
            ObjectNode n = servers.putObject("k3s-ops");
            n.put("type", "http");
            n.put("url", props.k3sUrl().strip());
            n.putObject("headers").put("Authorization", "Bearer ${" + K3S_TOKEN_ENV + "}");   // 토큰은 env 로만 전달
        }
        for (McpServer s : custom) {
            servers.set(s.getName(), customNode(s));
        }
        Path dir = Path.of(gitlab.workDir() == null || gitlab.workDir().isBlank() ? "work" : gitlab.workDir()).toAbsolutePath();
        Files.createDirectories(dir);
        Path file = dir.resolve("mcp-servers.json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        return file;
    }

    /** 허용 도구 목록 생성(패키지 내 테스트용으로 분리). */
    List<String> buildTools() {
        return buildTools(props);
    }

    static List<String> buildTools(McpProperties p) {
        List<String> t = new ArrayList<>();
        if (p.hasOpensearch()) {
            t.add("mcp__opensearch");          // 서버 단위 허용(백엔드 계정이 읽기 전용) — 실제 호출로 허용 문법 확인
        }
        if (p.hasPostgres()) {
            t.add("mcp__postgres");            // restricted 모드(읽기 전용)
        }
        if (p.hasK3s() && p.k3sReadTools() != null) {
            for (String name : p.k3sReadTools()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String n = name.strip();
                if (n.startsWith("restart_") || n.startsWith("scale_") || n.startsWith("delete_") || n.startsWith("apply_") || n.startsWith("exec_")) {
                    log.warn("k3s-ops 변경 도구 '{}' 는 허용 목록에서 제외됩니다", n);
                    continue;
                }
                t.add("mcp__k3s-ops__" + n);
            }
        }
        return t;
    }
}

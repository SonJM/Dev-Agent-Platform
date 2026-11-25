package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.llm.ChatModel;
import io.github.lib0823.devagent.llm.ToolChatModel;
import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.platform.PortHealth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 연동(포트 구현체) 상태와 연결 테스트 — 관리 페이지 "연동" 카드.
 * 상태 조회는 각 포트의 {@code health()}(즉시 응답)만 모으고, 실제 호출은 테스트 버튼을 눌렀을 때만 한다.
 * 구현체 전환은 화면에서 하지 않는다(설정 {@code platform.*} + 재시작).
 */
@RestController
public class PlatformController {

    private final MessengerService messenger;
    private final ChatModel chat;
    private final ToolChatModel toolChat;
    private final CodingAgent agent;
    private final McpConfigService mcp;
    private final SettingService settings;

    public PlatformController(MessengerService messenger, ChatModel chat, ToolChatModel toolChat, CodingAgent agent,
                              McpConfigService mcp, SettingService settings) {
        this.messenger = messenger;
        this.chat = chat;
        this.toolChat = toolChat;
        this.agent = agent;
        this.mcp = mcp;
        this.settings = settings;
    }

    @GetMapping("/api/platform")
    public Map<String, Object> platform() {
        List<Map<String, Object>> ports = new ArrayList<>();
        ports.add(entry("messenger", "메신저", "자기 대화 수신 · 알림·답 전송", "PLATFORM_MESSENGER", messenger.health(),
                "자기방에 테스트 메시지 전송"));
        ports.add(entry("chat", "채팅 모델", "요약 · 멘션 초안 · @AI 답변", "PLATFORM_CHAT_MODEL", chat.health(),
                "짧은 프롬프트 1건 호출(토큰 소량)"));
        PortHealth tool = toolChat.health().with("mcpServers", mcp.available() ? String.join(", ", mcp.serverNames()) : "비활성");
        ports.add(entry("tool", "도구 모델", "운영 질의(@AI 운영 …, MCP 도구)", "PLATFORM_CHAT_MODEL", tool,
                "CLI 실행 확인 + MCP 서버 연결 확인(토큰 없음)"));
        SettingService.AgentModels m = settings.getAgentModels();
        PortHealth ag = agent.health()
                .with("models", "기획 " + m.designModel() + "/" + m.designEffort() + " · 개발 " + m.devModel() + "/" + m.devEffort()
                        + " · 테스트 " + m.verifyModel() + "/" + m.verifyEffort());
        ports.add(entry("agent", "코딩 에이전트", "이슈 분석 · 수정 · 검증, 질의", "PLATFORM_CODING_AGENT", ag,
                "CLI 실행 확인(토큰 없음)"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ports", ports);
        // 예전 형식(이름만)도 유지
        out.put("messenger", messenger.provider());
        out.put("chatModel", chat.provider());
        out.put("toolChatModel", toolChat.provider());
        out.put("codingAgent", agent.provider());
        return out;
    }

    /** 연결 테스트. port = messenger | chat | tool | agent */
    @PostMapping("/api/platform/test")
    public Map<String, Object> test(@RequestParam String port) {
        PortHealth.TestResult r;
        switch (port) {
            case "messenger" -> r = messenger.selfTest("[봇] 연동 테스트 — "
                    + OffsetDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            case "chat" -> r = chatTest();
            case "tool" -> r = toolTest();
            case "agent" -> r = agent.selfTest();
            default -> r = new PortHealth.TestResult(false, "알 수 없는 포트: " + port, 0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("message", r.message());
        out.put("elapsedMs", r.elapsedMs());
        return out;
    }

    private PortHealth.TestResult chatTest() {
        long t0 = System.currentTimeMillis();
        try {
            String a = chat.complete("연결 테스트입니다. 'pong' 한 단어만 답하세요.");
            String shown = a == null ? "" : a.strip();
            return new PortHealth.TestResult(!shown.isBlank(), "응답: " + (shown.length() > 80 ? shown.substring(0, 80) + "…" : shown),
                    System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            return new PortHealth.TestResult(false, "호출 실패: " + e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    private PortHealth.TestResult toolTest() {
        long t0 = System.currentTimeMillis();
        PortHealth.TestResult cli = toolChat.selfTest();
        if (!cli.ok() || !mcp.available()) {
            return new PortHealth.TestResult(cli.ok(), cli.message() + (mcp.available() ? "" : " · 운영 MCP 비활성"), System.currentTimeMillis() - t0);
        }
        List<Map<String, Object>> servers = mcp.health();
        long okCount = servers.stream().filter(s -> Boolean.TRUE.equals(s.get("ok"))).count();
        StringBuilder sb = new StringBuilder(cli.message()).append(" · MCP ").append(okCount).append("/").append(servers.size()).append(" 연결");
        for (Map<String, Object> s : servers) {
            if (!Boolean.TRUE.equals(s.get("ok"))) {
                sb.append(" · ").append(s.get("name")).append(" 실패(").append(s.get("detail")).append(")");
            }
        }
        return new PortHealth.TestResult(okCount == servers.size(), sb.toString(), System.currentTimeMillis() - t0);
    }

    private static Map<String, Object> entry(String port, String label, String role, String envKey, PortHealth h, String testHint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", port);
        m.put("label", label);
        m.put("role", role);
        m.put("envKey", envKey);
        m.put("provider", h.provider());
        m.put("status", h.status());
        m.put("summary", h.summary());
        m.put("details", h.details());
        m.put("testHint", testHint);
        return m;
    }
}

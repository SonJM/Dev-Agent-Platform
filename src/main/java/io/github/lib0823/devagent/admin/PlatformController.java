package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.agent.CodingAgent;
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
    private final CodingAgent agent;
    private final SettingService settings;

    public PlatformController(MessengerService messenger, CodingAgent agent, SettingService settings) {
        this.messenger = messenger;
        this.agent = agent;
        this.settings = settings;
    }

    @GetMapping("/api/platform")
    public Map<String, Object> platform() {
        List<Map<String, Object>> ports = new ArrayList<>();
        ports.add(entry("messenger", "메신저", "자기 대화 수신 · 알림·답 전송", "PLATFORM_MESSENGER", messenger.health(),
                "자기방에 테스트 메시지 전송"));
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
        out.put("codingAgent", agent.provider());
        return out;
    }

    /** 연결 테스트. port = messenger | agent */
    @PostMapping("/api/platform/test")
    public Map<String, Object> test(@RequestParam String port) {
        PortHealth.TestResult r;
        switch (port) {
            case "messenger" -> r = messenger.selfTest("[봇] 연동 테스트 — "
                    + OffsetDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            case "agent" -> r = agent.selfTest();
            default -> r = new PortHealth.TestResult(false, "알 수 없는 포트: " + port, 0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("message", r.message());
        out.put("elapsedMs", r.elapsedMs());
        return out;
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

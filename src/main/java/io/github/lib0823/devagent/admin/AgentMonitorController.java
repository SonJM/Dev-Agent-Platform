package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.issue.IssueAgentService;
import io.github.lib0823.devagent.issue.IssueEvent;
import io.github.lib0823.devagent.issue.IssueEventBus;
import io.github.lib0823.devagent.issue.IssueSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이슈 에이전트 모니터 화면(/agent.html) 전용 API (localhost 전용).
 * 세션 목록/상세/이벤트/diff 조회 + SSE 실시간 스트림. 답변·명령은 {@link AdminController} 의
 * {@code /api/issues-reply}, {@code /api/issues-command} 를 그대로 쓴다.
 */
@RestController
public class AgentMonitorController {

    private final IssueAgentService agent;
    private final IssueEventBus bus;
    private final io.github.lib0823.devagent.llm.UsageService usage;

    public AgentMonitorController(IssueAgentService agent, IssueEventBus bus, io.github.lib0823.devagent.llm.UsageService usage) {
        this.agent = agent;
        this.bus = bus;
        this.usage = usage;
    }

    /** 토큰 사용량 집계(최근 5h/24h/7d, 24h 모델별·출처별·시간대별) + 사용량 한도 상태. 구독제라 비용 대신 토큰. */
    @GetMapping("/api/usage")
    public Map<String, Object> usage() {
        return usage.summary();
    }

    /** 현황 + 최근 세션 목록. */
    @GetMapping("/api/agent/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", agent.enabled());
        m.put("busy", agent.isBusy());
        m.put("busyIds", agent.busySessionIds());                 // 워커가 작업 중인 세션(답변 버튼 잠금)
        m.put("concurrency", agent.concurrency());
        List<IssueSession> actives = agent.activeSessions();
        m.put("activeIds", actives.stream().map(IssueSession::getId).toList());
        m.put("activeRefs", actives.stream().map(IssueSession::ref).toList());
        m.put("running", agent.runningInfo());                     // 세션 id → {model, effort, stage, startedAt}: 지금 돌고 있는 Claude
        m.put("limit", usage.limitInfo());                         // 사용량 한도 상태(active/untilText)
        IssueSession active = agent.activeSession();               // 대표(확인 대기 우선) — 기존 필드 호환
        IssueSession next = agent.nextSession();
        m.put("activeId", active == null ? null : active.getId());
        m.put("activeState", active == null ? null : active.getState());
        m.put("activeRef", active == null ? null : active.ref());
        m.put("nextRef", next == null ? null : next.ref());
        m.put("subscribers", bus.subscribers());
        m.put("sessions", agent.enabled() ? agent.recentLight(Math.max(1, Math.min(limit, 200))) : List.of());
        return m;
    }

    /** 세션 상세 + 이벤트(afterId 이후) . */
    @GetMapping("/api/agent/sessions/{id}")
    public Map<String, Object> session(@PathVariable long id,
                                       @RequestParam(defaultValue = "0") long afterId,
                                       @RequestParam(defaultValue = "1000") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        IssueSession s = agent.session(id);
        m.put("session", s);
        m.put("events", s == null ? List.<IssueEvent>of() : agent.events(id, afterId, limit));
        m.put("busy", agent.isBusy());
        m.put("running", agent.runningInfo().get(id));
        return m;
    }

    @GetMapping("/api/agent/sessions/{id}/events")
    public List<IssueEvent> events(@PathVariable long id,
                                   @RequestParam(defaultValue = "0") long afterId,
                                   @RequestParam(defaultValue = "1000") int limit) {
        return agent.events(id, afterId, limit);
    }

    /** 이벤트 1건 전체(본문 포함) — 목록은 본문 없이 내려가고 클릭 시 조회. */
    @GetMapping("/api/agent/events/{id}")
    public IssueEvent event(@PathVariable long id) {
        return agent.event(id);
    }

    /** 세션의 diff(작업트리 또는 푸시된 커밋). 없으면 available=false. */
    @GetMapping("/api/agent/sessions/{id}/diff")
    public Map<String, Object> diff(@PathVariable long id, @RequestParam(defaultValue = "200000") int maxChars) {
        String d = agent.diffFor(id, Math.max(1000, Math.min(maxChars, 2_000_000)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", d != null);
        m.put("diff", d == null ? "" : d);
        return m;
    }

    /** 특정 세션 중단(선분석/대기열 포함). 확인 대기 중인 활성 세션은 /api/issues-reply?text=중단 과 동일 효과. */
    @PostMapping("/api/agent/sessions/{id}/abort")
    public Map<String, Object> abort(@PathVariable long id) {
        return Map.of("result", agent.abortSession(id));
    }

    /** 실시간 이벤트 스트림(SSE). 모든 세션 이벤트를 보내며 클라이언트가 sessionId 로 거른다. */
    @GetMapping(value = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return bus.subscribe();
    }
}

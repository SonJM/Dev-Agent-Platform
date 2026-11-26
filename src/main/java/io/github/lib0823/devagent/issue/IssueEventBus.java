package io.github.lib0823.devagent.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.admin.SettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 이슈 에이전트 이벤트 버스: DB(issue_event)에 기록하고 모니터 화면(SSE)으로 즉시 방송한다.
 * <p>
 * 기록 실패가 작업을 막지 않도록 모든 예외는 로그로만 남긴다. 도구 호출(TOOL) 같은 고빈도 이벤트도
 * 세션당 수백 건 수준이라 DB 에 그대로 남겨 과거 세션의 활동 로그도 다시 볼 수 있게 한다.
 */
@Component
public class IssueEventBus {

    private static final Logger log = LoggerFactory.getLogger(IssueEventBus.class);
    private static final long SSE_TIMEOUT_MS = 6 * 60 * 60 * 1000L;   // 6h (브라우저가 자동 재접속)
    private static final int MAX_MESSAGE = 400;
    private static final int MAX_DETAIL = 20000;

    private final IssueEventMapper mapper;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    /** SSE 전송 전용 스레드: 절전 중인 브라우저 소켓에 막혀도 Claude stdout 리더/스케줄러가 멈추지 않는다. */
    private final java.util.concurrent.ExecutorService sseExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "issue-sse");
        t.setDaemon(true);
        return t;
    });

    /** kind GITLAB → REMOTE 개명을 이미 마쳤다는 bot_setting 플래그(전체 스캔을 한 번만 돌리려고). */
    static final String KIND_RENAMED_KEY = "issue_event_kind_renamed";

    private final SettingService settings;

    public IssueEventBus(IssueEventMapper mapper, SettingService settings) {
        this.mapper = mapper;
        this.settings = settings;
    }

    /** 멱등. 호출처(IssueAgentService, MaintainService)가 모두 이 경로를 쓴다. */
    public void ensureTable() {
        mapper.ensureTable();
        if (!"1".equals(settings.getRaw(KIND_RENAMED_KEY))) {
            mapper.renameLegacyKind();
            settings.setRaw(KIND_RENAMED_KEY, "1");
        }
        mapper.ensureIndex();
    }

    public IssueEvent emit(long sessionId, String kind, String message) {
        return emit(sessionId, kind, message, null);
    }

    public IssueEvent emit(long sessionId, String kind, String message, String detail) {
        IssueEvent e = new IssueEvent(sessionId, kind, cut(message, MAX_MESSAGE), cut(detail, MAX_DETAIL));
        e.setCreatedAt(OffsetDateTime.now());
        try {
            mapper.insert(e);
        } catch (RuntimeException ex) {
            log.warn("issue_event 기록 실패({} {}): {}", kind, abbreviate(message, 60), ex.toString());
        }
        broadcast(e);
        return e;
    }

    /** 목록(본문 제외). 본문은 {@link #event(long)} 로 개별 조회. */
    public List<IssueEvent> events(long sessionId, long afterId, int limit) {
        return mapper.listAfterLight(sessionId, afterId, Math.max(1, Math.min(limit, 2000)));
    }

    /** 여러 세션 id 의 이벤트를 id 순으로(질의 스레드용). 비어 있으면 빈 목록. */
    public List<IssueEvent> events(List<Long> sessionIds, long afterId, int limit) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return mapper.listAfterLightIn(sessionIds, afterId, Math.max(1, Math.min(limit, 4000)));
    }

    public IssueEvent event(long id) {
        return mapper.findOne(id);
    }

    public void clear(long sessionId) {
        try {
            mapper.deleteBySession(sessionId);
        } catch (RuntimeException ex) {
            log.warn("issue_event 정리 실패 session={}: {}", sessionId, ex.toString());
        }
    }

    // ---------------------------------------------------------------- SSE

    public SseEmitter subscribe() {
        SseEmitter em = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(em);
        em.onCompletion(() -> emitters.remove(em));
        em.onTimeout(() -> emitters.remove(em));
        em.onError(t -> emitters.remove(em));
        try {
            em.send(SseEmitter.event().name("hello").data("{\"subscribers\":" + emitters.size() + "}"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(em);
        }
        return em;
    }

    public int subscribers() {
        return emitters.size();
    }

    /** 프록시/브라우저 유휴 종료 방지용 하트비트. */
    @Scheduled(fixedDelay = 20000, initialDelay = 20000)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        sseExec.execute(() -> deliver(SseEmitter.event().comment("ping")));
    }

    private void deliver(SseEmitter.SseEventBuilder event) {
        for (SseEmitter em : emitters) {
            try {
                em.send(event);
            } catch (IOException | IllegalStateException | IllegalArgumentException e) {
                emitters.remove(em);
            }
        }
    }

    private void broadcast(IssueEvent e) {
        if (emitters.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(toMap(e));
        } catch (IOException ex) {
            return;
        }
        final String id = String.valueOf(e.getId());
        sseExec.execute(() -> deliver(SseEmitter.event().name("event").id(id).data(payload)));
    }

    static Map<String, Object> toMap(IssueEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sessionId", e.getSessionId());
        m.put("kind", e.getKind());
        m.put("message", e.getMessage());
        m.put("detail", e.getDetail());
        m.put("hasDetail", e.isHasDetail());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return null;
        }
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String abbreviate(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }
}

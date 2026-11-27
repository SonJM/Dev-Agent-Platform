package io.github.lib0823.devagent.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude 토큰 사용량 기록·집계 + 구독 사용량 한도 상태.
 * <p>
 * 한도: CLI 가 "You've hit your session limit · resets 2:20pm (Asia/Seoul)" 같은 문장을 텍스트로 내보내고(오류가 아닌 경우도 있음),
 * 그 뒤로는 어떤 호출도 진행되지 않는다. 그래서 그 문장을 보면 리셋 시각을 파싱해 {@link #limitUntil()} 로 두고,
 * 이슈 에이전트는 그때까지 새 분석을 시작하지 않는다(진행 중 수정은 변경을 보존한 채 리뷰 대기로).
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern LIMIT = Pattern.compile(
            "hit your (?:session|usage|weekly|daily) limit|usage limit reached|you(?:'ve| have) reached your .*limit|limit will reset|rate limit(?:ed)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESET = Pattern.compile("resets?\\s+(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZONE = Pattern.compile("\\(([A-Za-z_]+/[A-Za-z_]+)\\)");

    private static final String KEY_CAPACITY = "usage_window_capacity";

    private final ClaudeUsageMapper mapper;
    private final io.github.lib0823.devagent.admin.SettingService settings;
    private final long defaultBudget;
    private volatile Instant limitUntil;
    private volatile Instant lastHitAt;
    private volatile String lastLimitText;

    /**
     * @param defaultBudget 5시간 창의 토큰 예산 추정치(입력+출력). 구독 한도는 API 로 알 수 없어 설정값으로 시작하고,
     *                      실제 한도에 걸리면 그 시점의 5시간 사용량을 용량으로 학습해 덮어쓴다(bot_setting.usage_window_capacity).
     */
    public UsageService(ClaudeUsageMapper mapper, io.github.lib0823.devagent.admin.SettingService settings,
                        @org.springframework.beans.factory.annotation.Value("${usage.window-budget-tokens:2000000}") long defaultBudget,
                        @org.springframework.beans.factory.annotation.Value("${usage.plan-api:true}") boolean planApi,
                        @org.springframework.beans.factory.annotation.Value("${usage.credentials-file:}") String credentialsFile) {
        this.mapper = mapper;
        this.settings = settings;
        this.defaultBudget = defaultBudget <= 0 ? 2_000_000L : defaultBudget;
        this.planApi = planApi;
        this.credentials = credentialsFile == null || credentialsFile.isBlank()
                ? java.nio.file.Path.of(System.getProperty("user.home"), ".claude", ".credentials.json")
                : java.nio.file.Path.of(credentialsFile);
    }

    // ---------------------------------------------------------------- 구독 사용률(실제값): Claude Code 의 /usage 가 쓰는 OAuth 사용량 API

    private static final String PLAN_USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final boolean planApi;
    private final java.nio.file.Path credentials;
    private volatile Map<String, Object> planCache;
    private volatile long planFetchedAt;
    private volatile String planError;
    /** 조회 실패 뒤 다시 시도할 시각(ms). 캐시가 비어 있어도 실패를 매 호출마다 되풀이하지 않게 한다(429 실측). */
    private volatile long planRetryAt;

    /**
     * 구독 사용률(5시간 창·7일 창 %, 리셋 시각). Claude Code 가 로그인 때 저장한 OAuth 토큰(`~/.claude/.credentials.json`,
     * CLI 가 스스로 갱신)으로 `/usage` 명령과 같은 API 를 호출한다. 60초 캐시. 실패하면 null(→ 토큰 집계 추정치로 폴백).
     * 토큰은 메모리에서만 쓰고 로그·응답에 싣지 않는다.
     */
    public Map<String, Object> planUsage() {
        if (!planApi) {
            return null;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> cached = planCache;
        if ((cached != null && now - planFetchedAt < PLAN_TTL_MS) || now < planRetryAt) {
            return fresh(cached);
        }
        synchronized (this) {
            if ((planCache != null && System.currentTimeMillis() - planFetchedAt < PLAN_TTL_MS) || System.currentTimeMillis() < planRetryAt) {
                return fresh(planCache);
            }
            try {
                com.fasterxml.jackson.databind.JsonNode cred = JSON.readTree(java.nio.file.Files.readString(credentials));
                String token = cred.path("claudeAiOauth").path("accessToken").asText("");
                if (token.isBlank()) {
                    throw new IllegalStateException("credentials 에 accessToken 없음");
                }
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(PLAN_USAGE_URL))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("anthropic-beta", "oauth-2025-04-20")
                        .header("Accept", "application/json")
                        .GET().build();
                java.net.http.HttpResponse<String> res = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + res.statusCode());
                }
                com.fasterxml.jackson.databind.JsonNode n = parseUsageBody(res.body());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("fiveHour", window(n.path("five_hour")));
                out.put("sevenDay", window(n.path("seven_day")));
                List<Map<String, Object>> limits = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode l : n.path("limits")) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", l.path("kind").asText(""));
                    m.put("percent", l.path("percent").asDouble(0));
                    m.put("severity", l.path("severity").asText(""));
                    m.put("resetsAt", l.path("resets_at").asText(null));
                    m.put("active", l.path("is_active").asBoolean(false));
                    limits.add(m);
                }
                out.put("limits", limits);
                out.put("fetchedAt", Instant.now().toString());
                planCache = out;
                planFetchedAt = System.currentTimeMillis();
                planError = null;
                saveSnapshot(out);
                return out;
            } catch (Exception e) {
                planError = planErrorText(e);                        // 응답에 실리므로 경로·내용 없이
                // 실패는 캐시 유무와 무관하게 물러선다: 429(과다 호출)는 5분, 그 외는 1분
                planRetryAt = System.currentTimeMillis() + retryDelayMs(e);
                log.debug("구독 사용률 조회 실패(추정치로 폴백): {}: {}", e.getClass().getSimpleName(), e.getMessage());
                if (planCache == null) {
                    planCache = loadSnapshot();                      // 재시작 직후 실패: 마지막으로 받아 둔 값(창이 아직 안 바뀌었으면)
                }
                return fresh(planCache);                             // 있으면 이전 값(5시간 창이 리셋됐으면 버린다)
            }
        }
    }

    /** 구독 API 응답 본문 해석. 실패는 인증 파일 단계와 구분되게 고정 문구로 바꿔 던진다(본문은 문구에 싣지 않고 cause 로만). */
    static com.fasterxml.jackson.databind.JsonNode parseUsageBody(String body) {
        try {
            return JSON.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("구독 API 응답 형식 오류", e);
        }
    }

    private static final String CREDENTIALS_LABEL ="인증 파일(~/.claude/.credentials.json)";

    /**
     * 화면에 보이는 조회 실패 문구. 인증 파일 단계의 실패는 예외 메시지(절대 경로·계정 이름이 든다)를 싣지 않고 고정 표기와
     * 원인 종류만, 이 클래스가 직접 던지는 IllegalStateException(accessToken 없음·HTTP 코드)은 그대로, 나머지는 클래스 이름만.
     */
    static String planErrorText(Exception e) {
        if (e instanceof java.nio.file.NoSuchFileException) {
            return CREDENTIALS_LABEL + " 없음";
        }
        if (e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return CREDENTIALS_LABEL + " 형식 오류";
        }
        if (e instanceof java.nio.file.FileSystemException) {
            return CREDENTIALS_LABEL + " 읽기 실패";
        }
        if (e instanceof IllegalStateException) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    /** 조회 실패 뒤 물러서는 시간: 429(과다 호출)는 5분, 그 외는 1분. 원래 예외 메시지로 판별한다. */
    static long retryDelayMs(Exception e) {
        return String.valueOf(e.getMessage()).contains("429") ? 300_000 : 60_000;
    }

    /** 구독 사용률 캐시 시간. API 가 자주 부르면 429 를 내므로 길게 잡는다(화면은 1분마다 묻지만 실제 호출은 3분에 한 번). */
    private static final long PLAN_TTL_MS = 180_000;
    private static final String KEY_PLAN_SNAPSHOT = "usage_plan_snapshot";

    /** 5시간 창이 이미 리셋된 옛 값은 쓰지 않는다(리셋 뒤에도 "거의 다 씀" 으로 보이는 것 방지). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fresh(Map<String, Object> plan) {
        if (plan == null) {
            return null;
        }
        try {
            Object r = ((Map<String, Object>) plan.get("fiveHour")).get("resetsAt");
            if (r != null && OffsetDateTime.parse(String.valueOf(r)).toInstant().isBefore(Instant.now())) {
                planCache = null;
                return null;
            }
        } catch (RuntimeException ignore) {
            // 형식을 모르면 그대로 쓴다
        }
        return plan;
    }

    private void saveSnapshot(Map<String, Object> plan) {
        try {
            settings.setRaw(KEY_PLAN_SNAPSHOT, JSON.writeValueAsString(plan));
        } catch (Exception e) {
            log.debug("구독 사용률 스냅샷 저장 실패(무시): {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSnapshot() {
        try {
            String raw = settings.getRaw(KEY_PLAN_SNAPSHOT);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            Map<String, Object> m = JSON.readValue(raw, LinkedHashMap.class);
            m.put("stale", true);                                    // 화면에 "이전 값" 으로 표시
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> window(com.fasterxml.jackson.databind.JsonNode w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("percent", w.path("utilization").asDouble(0));
        m.put("resetsAt", w.path("resets_at").asText(null));
        String r = w.path("resets_at").asText(null);
        String text = null;
        if (r != null) {
            try {
                text = OffsetDateTime.parse(r).atZoneSameInstant(DEFAULT_ZONE).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"));
            } catch (RuntimeException ignore) {
                // 형식 모름
            }
        }
        m.put("resetsAtText", text);
        return m;
    }

    /** 구독 API 가 "5시간 창 100%" 라고 하면 한도 상태(리셋 시각 포함). 아니면 null. */
    private Instant planLimitUntil() {
        Map<String, Object> p = planUsage();
        if (p == null) {
            return null;
        }
        Object fh = p.get("fiveHour");
        if (!(fh instanceof Map<?, ?> m)) {
            return null;
        }
        double pct = m.get("percent") instanceof Number n ? n.doubleValue() : 0;
        if (pct < 100) {
            return null;
        }
        try {
            return m.get("resetsAt") == null ? Instant.now().plusSeconds(1800) : OffsetDateTime.parse(String.valueOf(m.get("resetsAt"))).toInstant();
        } catch (RuntimeException e) {
            return Instant.now().plusSeconds(1800);
        }
    }

    /** 5시간 창 용량(토큰): 학습값이 있으면 그것, 없으면 설정 예산. */
    public long capacity() {
        try {
            String v = settings.getRaw(KEY_CAPACITY);
            if (v != null && !v.isBlank()) {
                long n = Long.parseLong(v.strip());
                if (n > 0) {
                    return n;
                }
            }
        } catch (RuntimeException ignore) {
            // 설정 조회 실패면 기본값
        }
        return defaultBudget;
    }

    public boolean capacityLearned() {
        try {
            String v = settings.getRaw(KEY_CAPACITY);
            return v != null && !v.isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 최근 5시간 사용량(입력+출력, 캐시 포함). */
    public long usedLast5h() {
        OffsetDateTime now = OffsetDateTime.now();
        long sum = 0;
        try {
            for (Map<String, Object> r : mapper.since(now.minusHours(5))) {
                sum += num(r.get("input_tokens")) + num(r.get("cache_read")) + num(r.get("cache_create")) + num(r.get("output_tokens"));
            }
        } catch (RuntimeException e) {
            log.debug("사용량 조회 실패(무시): {}", e.toString());
        }
        return sum;
    }

    /** 한도에 걸린 순간의 5시간 사용량을 용량으로 학습(0 이면 기록이 없는 것이라 건너뜀). */
    private void learnCapacity() {
        long used = usedLast5h();
        if (used <= 0) {
            return;
        }
        try {
            settings.setRaw(KEY_CAPACITY, String.valueOf(used));
            log.info("사용량 한도 용량 학습: 5시간 {} 토큰", used);
        } catch (RuntimeException e) {
            log.debug("용량 학습 저장 실패(무시): {}", e.toString());
        }
    }

    @PostConstruct
    void init() {
        try {
            mapper.ensureTable();
            mapper.ensureIndex();
        } catch (RuntimeException e) {
            log.warn("claude_usage 테이블 준비 실패(사용량 기록 없이 진행): {}", e.toString());
        }
    }

    /** 호출 1건 기록. 실패해도 호출 흐름을 막지 않는다. */
    public void record(String source, String stage, String model, String ref, long input, long output, long cacheRead, long cacheCreate, boolean limitHit) {
        try {
            mapper.insert(source, stage, model == null || model.isBlank() ? "default" : model, ref, input, output, cacheRead, cacheCreate, limitHit);
        } catch (RuntimeException e) {
            log.debug("사용량 기록 실패(무시): {}", e.toString());
        }
    }

    /** CLI 출력이 사용량 한도 메시지인지. */
    public static boolean isLimitText(String text) {
        return text != null && LIMIT.matcher(text).find();
    }

    /**
     * 한도 메시지를 봤다고 기록하고 리셋 시각을 돌려준다. "resets 2:20pm (Asia/Seoul)" 을 파싱하며,
     * 못 읽으면 30분 뒤로 둔다(그때 다시 시도해 보고 또 걸리면 다시 미룬다).
     */
    public Instant noteLimit(String text) {
        Instant until = parseReset(text, Instant.now());
        if (until == null) {
            until = Instant.now().plusSeconds(30 * 60);
        }
        limitUntil = until;
        lastHitAt = Instant.now();
        lastLimitText = text == null ? "" : text.strip();
        learnCapacity();
        log.warn("Claude 사용량 한도 도달 → {} 까지 새 실행 보류: {}", until.atZone(DEFAULT_ZONE).toLocalTime(), lastLimitText);
        return until;
    }

    static Instant parseReset(String text, Instant now) {
        if (text == null) {
            return null;
        }
        Matcher m = RESET.matcher(text);
        if (!m.find()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1)) % 12;
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if ("pm".equalsIgnoreCase(m.group(3))) {
            h += 12;
        }
        ZoneId zone = DEFAULT_ZONE;
        Matcher z = ZONE.matcher(text);
        if (z.find()) {
            try {
                zone = ZoneId.of(z.group(1));
            } catch (RuntimeException ignore) {
                // 모르는 지역명이면 기본 지역
            }
        }
        ZonedDateTime base = now.atZone(zone);
        ZonedDateTime at = ZonedDateTime.of(LocalDate.from(base), LocalTime.of(h, min), zone);
        if (!at.isAfter(base)) {
            at = at.plusDays(1);
        }
        return at.toInstant();
    }

    /**
     * 한도 상태. 구독 API 값이 있으면 그것이 우선(100% 면 한도, 아니면 정상 — CLI 메시지로 잡은 리셋 시각이 틀렸어도 바로잡힘),
     * 없으면 CLI 한도 메시지에서 읽은 리셋 시각까지.
     */
    public boolean isLimited() {
        Map<String, Object> p = planUsage();
        if (p != null) {
            Instant pu = planLimitUntil();
            if (pu != null && Instant.now().isBefore(pu)) {
                limitUntil = pu;
                return true;
            }
            return false;
        }
        Instant u = limitUntil;
        return u != null && Instant.now().isBefore(u);
    }

    public Instant limitUntil() {
        return limitUntil;
    }

    /** 사람이 읽는 리셋 시각(예: "14:20"). 한도 상태가 아니면 null. */
    public String limitUntilText() {
        Instant u = limitUntil;
        return u == null ? null : u.atZone(DEFAULT_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public Map<String, Object> limitInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", isLimited());
        m.put("until", limitUntil == null ? null : limitUntil.toString());
        m.put("untilText", limitUntilText());
        m.put("lastHitAt", lastHitAt == null ? null : lastHitAt.toString());
        m.put("message", lastLimitText);
        return m;
    }

    /** 대시보드용 집계: 최근 5시간/24시간/7일 합계, 24시간 모델별, 24시간 시간대별(오래된 → 최근), 한도 상태. */
    /** 요약 캐시(10초): 상단 배터리·홈이 화면마다 따로 묻고, 한 번 계산에 7일치 행을 읽는다. */
    private volatile Map<String, Object> summaryCache;
    private volatile long summaryAt;

    public Map<String, Object> summary() {
        Map<String, Object> c = summaryCache;
        if (c != null && System.currentTimeMillis() - summaryAt < 10_000) {
            return c;
        }
        Map<String, Object> fresh = buildSummary();
        summaryCache = fresh;
        summaryAt = System.currentTimeMillis();
        return fresh;
    }

    private Map<String, Object> buildSummary() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Map<String, Object>> rows;
        try {
            rows = mapper.since(now.minusDays(7));
        } catch (RuntimeException e) {
            rows = List.of();
        }
        Instant t5 = now.minusHours(5).toInstant(), t24 = now.minusHours(24).toInstant();
        Map<String, long[]> win = new LinkedHashMap<>();   // {calls, in, out}
        win.put("5h", new long[3]);
        win.put("24h", new long[3]);
        win.put("7d", new long[3]);
        Map<String, long[]> byModel = new LinkedHashMap<>();
        Map<String, long[]> bySource = new LinkedHashMap<>();
        long[] hourly = new long[24];
        int limitHits24h = 0;
        for (Map<String, Object> r : rows) {
            Instant at = toInstant(r.get("at"));
            long in = num(r.get("input_tokens")) + num(r.get("cache_read")) + num(r.get("cache_create"));
            long out = num(r.get("output_tokens"));
            add(win.get("7d"), in, out);
            if (at != null && !at.isBefore(t24)) {
                add(win.get("24h"), in, out);
                String model = String.valueOf(r.get("model"));
                add(byModel.computeIfAbsent(model, k -> new long[3]), in, out);
                add(bySource.computeIfAbsent(String.valueOf(r.get("source")), k -> new long[3]), in, out);
                int idx = 23 - (int) Math.min(23, Math.max(0, (now.toInstant().getEpochSecond() - at.getEpochSecond()) / 3600));
                hourly[idx] += in + out;
                if (Boolean.TRUE.equals(r.get("limit_hit"))) {
                    limitHits24h++;
                }
                if (!at.isBefore(t5)) {
                    add(win.get("5h"), in, out);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> windows = new LinkedHashMap<>();
        win.forEach((k, v) -> windows.put(k, Map.of("calls", v[0], "in", v[1], "out", v[2])));
        out.put("windows", windows);
        List<Map<String, Object>> models = new ArrayList<>();
        byModel.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1] + b.getValue()[2], a.getValue()[1] + a.getValue()[2]))
                .forEach(e -> models.add(Map.of("model", e.getKey(), "calls", e.getValue()[0], "in", e.getValue()[1], "out", e.getValue()[2])));
        out.put("byModel24h", models);
        List<Map<String, Object>> sources = new ArrayList<>();
        bySource.forEach((k, v) -> sources.add(Map.of("source", k, "calls", v[0], "in", v[1], "out", v[2])));
        out.put("bySource24h", sources);
        out.put("hourly24h", hourly);
        out.put("limitHits24h", limitHits24h);
        out.put("limit", limitInfo());
        // 배터리 게이지: 구독 API 의 5시간 창 사용률이 있으면 그것(실제값), 없으면 토큰 집계 / 용량(학습값 또는 설정 예산) 추정
        Map<String, Object> plan = planUsage();
        long cap = capacity();
        long used = win.get("5h")[1] + win.get("5h")[2];
        int pct;
        String source;
        if (plan != null && plan.get("fiveHour") instanceof Map<?, ?> fh && fh.get("percent") instanceof Number n) {
            pct = (int) Math.min(100, Math.round(n.doubleValue()));
            source = "plan";
        } else {
            pct = isLimited() ? 100 : (int) Math.min(100, Math.round(used * 100.0 / Math.max(1, cap)));
            source = "estimate";
        }
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("source", source);
        budget.put("capacity", cap);
        budget.put("used", used);
        budget.put("usedPct", pct);
        budget.put("remainingPct", 100 - pct);
        budget.put("learned", capacityLearned());
        out.put("budget", budget);
        out.put("plan", plan);
        out.put("planError", plan == null ? planError : null);
        return out;
    }

    private static void add(long[] acc, long in, long out) {
        acc[0]++;
        acc[1] += in;
        acc[2] += out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static Instant toInstant(Object o) {
        if (o instanceof OffsetDateTime t) {
            return t.toInstant();
        }
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof Instant t) {
            return t;
        }
        if (o instanceof java.time.LocalDateTime t) {
            return t.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (o != null) {
            try {
                return OffsetDateTime.parse(o.toString()).toInstant();
            } catch (RuntimeException ignore) {
                // 알 수 없는 형식
            }
        }
        return null;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}

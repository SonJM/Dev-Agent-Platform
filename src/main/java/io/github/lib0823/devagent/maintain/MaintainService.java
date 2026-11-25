package io.github.lib0823.devagent.maintain;

import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.MaintainProperties;
import io.github.lib0823.devagent.gitlab.IssueEvent;
import io.github.lib0823.devagent.gitlab.IssueEventBus;
import io.github.lib0823.devagent.llm.UsageService;
import io.github.lib0823.devagent.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 플랫폼 관리(자기 유지보수): 상단바 렌치 버튼의 채팅 창에서 받은 요청을, 이 플랫폼의 소스 저장소를 작업 디렉터리로 한
 * Claude 세션이 처리한다 — 버그 확인·수정, 테스트/빌드, 커밋, 스킬 등록. 터미널에서 Claude Code 를 켜는 일을 화면 안으로 옮긴 것.
 * <ul>
 *   <li>저장소: worktree 를 따로 두지 않고 소스 저장소에서 직접 고친다(실행 중인 jar 는 {@code run/} 의 사본이라 빌드해도 안전). 그래서 한 번에 한 요청만.</li>
 *   <li>세션: 대화(스레드)마다 Claude 세션 하나, 이어지는 요청은 {@code --resume}. 재시작 뒤에도 같은 세션으로 이어진다.</li>
 *   <li>푸시·재시작은 세션이 하지 않는다: 세션은 플랫폼의 자식 프로세스라 재시작하면 함께 끊기고, 푸시는 사람이 커밋을 보고 누른다
 *       ({@link #push()} / {@code /api/system/restart}).</li>
 *   <li>이벤트: 이슈 이벤트 버스를 쓰되 세션 id 공간을 {@link #EVENT_BASE} 만큼 띄운다(질의는 10억, 여기는 20억).</li>
 *   <li>배포판(실행 파일)에는 소스 저장소가 없으므로 {@code maintain.repo-dir} 를 주지 않으면 꺼져 있고 버튼도 보이지 않는다.</li>
 * </ul>
 */
@Service
public class MaintainService {

    private static final Logger log = LoggerFactory.getLogger(MaintainService.class);
    /** issue_event.session_id 공간 분리: 차례 id + 20억. */
    public static final long EVENT_BASE = 2_000_000_000L;
    public static final List<String> MODELS = List.of("fable", "opus", "sonnet", "haiku");
    private static final int MAX_QUESTION = 8000;
    private static final long GIT_TIMEOUT_MS = 20_000L;
    private static final long PUSH_TIMEOUT_MS = 90_000L;
    private static final long STATUS_CACHE_MS = 3_000L;

    private final MaintainProperties props;
    private final MaintainTurnMapper turns;
    private final CodingAgent agent;
    private final IssueEventBus events;
    private final SettingService settings;
    private final UsageService usage;
    private final NotificationService notifications;
    private final int adminPort;
    /** 플랫폼 소스 저장소. null 이면 비활성({@link #disabledReason}). */
    private final Path repo;
    private final String disabledReason;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maintain-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Long> runningId = new AtomicReference<>();
    private final Set<Long> abortRequested = ConcurrentHashMap.newKeySet();
    private volatile Map<String, Object> statusCache;
    private volatile long statusAt;

    public MaintainService(MaintainProperties props, MaintainTurnMapper turns, CodingAgent agent, IssueEventBus events,
                           SettingService settings, UsageService usage, NotificationService notifications,
                           @Value("${server.port:8777}") int adminPort) {
        this.props = props;
        this.turns = turns;
        this.agent = agent;
        this.events = events;
        this.settings = settings;
        this.usage = usage;
        this.notifications = notifications;
        this.adminPort = adminPort;
        String reason = null;
        Path dir = null;
        if (!props.enabled()) {
            reason = "꺼져 있습니다(MAINTAIN_ENABLED=false).";
        } else {
            boolean explicit = props.repoDir() != null && !props.repoDir().isBlank();
            Path cand = (explicit ? Path.of(props.repoDir().strip()) : Path.of("")).toAbsolutePath().normalize();
            if (isSourceRepo(cand)) {
                dir = cand;
            } else {
                reason = explicit
                        ? "MAINTAIN_REPO_DIR(" + cand + ") 가 플랫폼 소스 저장소가 아닙니다(.git · build.gradle · src/main/java 필요)."
                        : "실행 위치에 플랫폼 소스 저장소가 없습니다. 소스를 받아 둔 경로를 .env 의 MAINTAIN_REPO_DIR 에 지정하면 켜집니다.";
            }
        }
        this.repo = dir;
        this.disabledReason = reason;
    }

    /** 플랫폼 소스 저장소인가: git 저장소이고 Gradle 빌드 파일과 자바 소스가 있다. */
    static boolean isSourceRepo(Path dir) {
        return dir != null && Files.exists(dir.resolve(".git")) && Files.isRegularFile(dir.resolve("build.gradle"))
                && Files.isDirectory(dir.resolve("src").resolve("main").resolve("java"));
    }

    @PostConstruct
    void init() {
        if (!enabled()) {
            log.info("플랫폼 관리 비활성: {}", disabledReason);
            return;
        }
        turns.ensureTable();
        turns.ensureIndex();
        events.ensureTable();                         // GitLab 이 꺼져 있어도 이벤트 테이블은 필요하다(멱등)
        // 재기동 시 진행 중이던 요청: 중간 상태를 알 수 없으므로 실패로 닫는다. 작업트리의 변경과 세션은 남아 있어 이어서 요청할 수 있다
        for (MaintainTurn t : turns.findActive()) {
            boolean started = MaintainTurn.RUNNING.equals(t.getState());
            t.setState(MaintainTurn.FAILED);
            t.setLastError(started ? "플랫폼 재시작으로 중단되었습니다. 고치던 내용은 저장소에 남아 있으니 「이어서 진행해 줘」라고 보내면 같은 세션으로 계속합니다."
                    : "플랫폼 재시작으로 시작하지 못했습니다. 다시 보내 주세요.");
            t.setFinishedAt(OffsetDateTime.now());
            turns.update(t);
            events.emit(eventId(t.getId()), IssueEvent.ERROR, "플랫폼 재시작으로 중단됨");
        }
        log.info("플랫폼 관리 활성: 저장소 {}", repo);
    }

    public boolean enabled() {
        return repo != null;
    }

    /** 요청을 실행 중인가(재시작 가능 여부 판단용). */
    public boolean busy() {
        return runningId.get() != null;
    }

    public static long eventId(long turnId) {
        return EVENT_BASE + turnId;
    }

    // ---------------------------------------------------------------- 접수

    /**
     * 요청 접수. {@code threadId} 가 있으면 그 대화에 이어 붙인다(같은 Claude 세션). 저장소를 직접 고치므로 한 번에 한 요청만 받는다.
     * 검증 실패는 IllegalArgumentException(메시지를 그대로 사용자에게).
     */
    public synchronized MaintainTurn submit(String question, Long threadId, String model) {
        if (!enabled()) {
            throw new IllegalArgumentException("플랫폼 관리를 쓸 수 없습니다: " + disabledReason);
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("요청을 입력하세요.");
        }
        if (q.length() > MAX_QUESTION) {
            throw new IllegalArgumentException("요청이 너무 깁니다(" + MAX_QUESTION + "자 이내).");
        }
        if (!turns.findActive().isEmpty()) {
            throw new IllegalArgumentException("앞선 요청을 처리하는 중입니다. 끝난 뒤(또는 중단한 뒤) 보내 주세요.");
        }
        if (usage.isLimited()) {
            throw new IllegalArgumentException("Claude 사용량 한도에 걸려 있습니다. 리셋(" + usage.limitUntilText() + ") 뒤에 보내 주세요.");
        }
        if (threadId != null && turns.find(threadId) == null) {
            throw new IllegalArgumentException("이어갈 대화를 찾을 수 없습니다: #" + threadId);
        }
        MaintainTurn t = new MaintainTurn();
        t.setQuestion(q);
        t.setState(MaintainTurn.NEW);
        t.setModel(normalizeModel(model));
        t.setThreadId(threadId == null ? 0 : turns.find(threadId).getThreadId());
        turns.insert(t);
        if (threadId == null) {
            turns.fixThread(t.getId());
            t.setThreadId(t.getId());
        }
        events.emit(eventId(t.getId()), IssueEvent.STATE, "플랫폼 관리 요청 접수 → NEW", MaintainTurn.NEW);
        log.info("플랫폼 관리 요청 #{} thread={} model={}", t.getId(), t.getThreadId(), t.getModel() == null ? "(기본)" : t.getModel());
        final long id = t.getId();
        worker.submit(() -> runTurn(id));
        return t;
    }

    /** 빈 값이면 null(= 에이전트 설정의 '개발' 모델). */
    String normalizeModel(String model) {
        String m = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return props.model() == null || props.model().isBlank() ? null : props.model().strip();
        }
        if (!MODELS.contains(m)) {
            throw new IllegalArgumentException("모델은 " + MODELS + " 중 하나여야 합니다: " + model);
        }
        return m;
    }

    // ---------------------------------------------------------------- 실행

    private void runTurn(long id) {
        MaintainTurn t = turns.find(id);
        if (t == null || !MaintainTurn.NEW.equals(t.getState())) {
            return;
        }
        runningId.set(id);
        try {
            run(t);
        } catch (RuntimeException e) {
            log.warn("플랫폼 관리 요청 #{} 처리 오류: {}", id, e.toString());
            finish(t, MaintainTurn.FAILED, null, abbreviate(e.getMessage(), 500));
        } finally {
            runningId.set(null);
            abortRequested.remove(id);
            statusAt = 0;                             // 저장소 상태가 바뀌었을 수 있다
        }
    }

    private void run(MaintainTurn t) {
        final long eid = eventId(t.getId());
        if (abortRequested.contains(t.getId())) {
            finish(t, MaintainTurn.ABORTED, null, "사용자 중단(시작 전)");
            return;
        }
        String prev = turns.lastSession(t.getThreadId(), t.getId());
        boolean resume = prev != null && !prev.isBlank();
        String sid = resume ? prev : UUID.randomUUID().toString();
        t.setState(MaintainTurn.RUNNING);
        t.setStartedAt(OffsetDateTime.now());
        t.setClaudeSessionId(sid);
        turns.update(t);
        events.emit(eid, IssueEvent.STATE, "NEW → RUNNING", MaintainTurn.RUNNING);
        String sys = MaintainPrompts.system(repo, adminPort);
        CodingAgent.Overrides ov = new CodingAgent.Overrides(t.getModel(), props.effort(), props.timeoutOr(), props.maxTurnsOr(), "maintain");
        CodingAgent.Listener listener = (kind, message, detail) -> events.emit(eid, kind, message, detail);
        events.emit(eid, IssueEvent.CLAUDE, "▶ 플랫폼 관리 세션 " + (resume ? "이어서 " : "") + "시작 (상한 "
                + props.timeoutOr() / 60_000 + "분/" + props.maxTurnsOr() + "턴)", t.getQuestion());
        try {
            CodingAgent.Result r;
            try {
                r = agent.run(repo, sid, resume, props.toolsOr(), sys, t.getQuestion(), listener, CodingAgent.Stage.FIX, ov);
            } catch (CodingAgent.UsageLimitException | CodingAgent.AgentTimeoutException e) {
                throw e;
            } catch (RuntimeException e) {
                if (!resume || abortRequested.contains(t.getId()) || !sessionMissing(e.getMessage())) {
                    throw e;
                }
                // 세션 파일이 없어졌다(정리됨·경로 이동): 맥락 없이 새 세션으로 한 번 더
                sid = UUID.randomUUID().toString();
                t.setClaudeSessionId(sid);
                turns.update(t);
                events.emit(eid, IssueEvent.INFO, "이전 대화 세션을 찾지 못해 새 세션으로 시작합니다(앞선 대화 맥락 없음)");
                r = agent.run(repo, sid, false, props.toolsOr(), sys, t.getQuestion(), listener, CodingAgent.Stage.FIX, ov);
            }
            t.setTurns(r.turns());
            t.setInputTokens(r.inputTotal());
            t.setOutputTokens(r.outputTokens());
            t.setElapsedMs(r.elapsedMs());
            events.emit(eid, IssueEvent.CLAUDE, String.format("■ 완료 · %d턴 · %s · %ds%s", r.turns(), r.tokens(), r.elapsedMs() / 1000,
                    r.error() ? " · 오류" : ""), r.text());
            if (abortRequested.contains(t.getId())) {
                finish(t, MaintainTurn.ABORTED, r.text(), "사용자 중단");
                return;
            }
            String text = r.text() == null ? "" : r.text().strip();
            if (text.length() > props.maxAnswerCharsOr()) {
                text = text.substring(0, props.maxAnswerCharsOr()) + "\n\n…(답변이 길어 " + props.maxAnswerCharsOr() + "자에서 잘랐습니다)";
            }
            if (r.error()) {
                // 턴 상한 등: 작업은 저장소에 남아 있다
                finish(t, MaintainTurn.FAILED, text.isEmpty() ? null : text, "Claude 가 오류로 끝났습니다(턴 상한일 수 있음). 「이어서 진행해 줘」로 계속할 수 있습니다.");
                return;
            }
            finish(t, MaintainTurn.DONE, text, null);
        } catch (CodingAgent.UsageLimitException e) {
            finish(t, MaintainTurn.FAILED, null, "Claude 사용량 한도에 걸렸습니다(리셋 " + usage.limitUntilText()
                    + "). 고치던 내용은 저장소에 남아 있으니 리셋 뒤 「이어서 진행해 줘」라고 보내세요.");
        } catch (CodingAgent.AgentTimeoutException e) {
            finish(t, MaintainTurn.FAILED, null, e.getMessage() + " — 고치던 내용은 저장소에 남아 있습니다. 「이어서 진행해 줘」로 계속할 수 있습니다.");
        } catch (RuntimeException e) {
            if (abortRequested.contains(t.getId())) {
                finish(t, MaintainTurn.ABORTED, null, "사용자 중단 — 그때까지 고친 내용은 저장소에 남아 있습니다.");
                return;
            }
            log.warn("플랫폼 관리 요청 #{} 실패: {}", t.getId(), e.toString());
            finish(t, MaintainTurn.FAILED, null, abbreviate(e.getMessage(), 500));
        }
    }

    /** {@code --resume} 대상 세션이 없다는 CLI 오류인가. */
    static boolean sessionMissing(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("no conversation found");
    }

    private void finish(MaintainTurn t, String state, String answer, String error) {
        t.setState(state);
        t.setAnswerMd(answer);
        t.setLastError(error);
        t.setFinishedAt(OffsetDateTime.now());
        turns.update(t);
        long eid = eventId(t.getId());
        if (error != null) {
            events.emit(eid, IssueEvent.ERROR, error);
        }
        events.emit(eid, IssueEvent.STATE, "→ " + state, state);
        boolean done = MaintainTurn.DONE.equals(state);
        boolean aborted = MaintainTurn.ABORTED.equals(state);
        // 메신저와 무관하게 알림함에 남긴다(창을 닫아 두고 기다리는 경우). 링크 '#maintain' 은 화면(ui.js)이 채팅 창을 여는 신호
        notifications.add("maintain", done ? "ok" : aborted ? "warn" : "err",
                "플랫폼 관리 " + (done ? "완료" : aborted ? "중단" : "실패") + ": " + abbreviate(t.getQuestion(), 80), error, "#maintain");
    }

    public String abort(long id) {
        MaintainTurn t = turns.find(id);
        if (t == null) {
            return "요청을 찾을 수 없습니다: #" + id;
        }
        if (!t.isActive()) {
            return "이미 끝난 요청입니다 (" + t.getState() + ").";
        }
        abortRequested.add(id);
        boolean killed = agent.kill(t.getClaudeSessionId());
        if (killed) {
            events.emit(eventId(id), IssueEvent.INFO, "중단 요청 — 실행 중인 Claude 프로세스 종료");
        }
        return "중단을 요청했습니다." + (killed ? " 그때까지 고친 내용은 저장소에 남습니다." : "");
    }

    // ---------------------------------------------------------------- 조회

    /** 버튼·창의 기본 정보: 활성 여부, 실행 중인 요청, 대화 목록, 모델 선택지. 가볍다(git 호출 없음). */
    public Map<String, Object> overview(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        if (!enabled()) {
            out.put("reason", disabledReason);
            return out;
        }
        Long rid = runningId.get();
        MaintainTurn rt = rid == null ? null : turns.find(rid);
        out.put("runningId", rid);
        out.put("runningThread", rt == null ? null : rt.getThreadId());
        out.put("threads", turns.threads(Math.max(1, Math.min(limit, 100))));
        out.put("models", MODELS);
        SettingService.AgentModels m = settings.getAgentModels();
        out.put("defaultModel", props.model() == null || props.model().isBlank() ? m.devModel() : props.model().strip());
        out.put("repo", repo.toString());
        out.put("limited", usage.isLimited());
        out.put("limitUntil", usage.isLimited() ? usage.limitUntilText() : null);
        return out;
    }

    /** 대화 상세: 차례 전부(답변 포함) + 마지막 차례의 활동 로그(afterEventId 이후, 본문 제외). */
    public Map<String, Object> thread(long threadId, long afterEventId) {
        List<MaintainTurn> items = turns.findThread(threadId);
        if (items.isEmpty()) {
            return null;
        }
        MaintainTurn last = items.get(items.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("turns", items);
        out.put("liveId", last.getId());
        out.put("events", events.events(eventId(last.getId()), afterEventId, 500));
        CodingAgent.RunInfo ri = last.isActive() ? agent.runInfo(last.getClaudeSessionId()) : null;
        if (ri != null) {
            out.put("running", Map.of("model", ri.model(), "effort", ri.effort(), "startedAt", ri.startedAt()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 저장소 상태 · 푸시

    /** `git status --porcelain=v1 -b` 해석 결과. */
    record RepoState(String branch, String upstream, int ahead, int behind, List<String> files) {
    }

    private static final Pattern HEAD_LINE = Pattern.compile("^## (?:No commits yet on )?(.+?)(?:\\.\\.\\.(\\S+))?(?: \\[(.+)])?$");

    /** 첫 줄 "## main...origin/main [ahead 2, behind 1]" + 변경 파일 줄("XY 경로"). */
    static RepoState parseStatus(String porcelain) {
        String branch = "";
        String upstream = null;
        int ahead = 0;
        int behind = 0;
        List<String> files = new ArrayList<>();
        for (String line : (porcelain == null ? "" : porcelain).split("\n")) {
            String l = line.stripTrailing();
            if (l.isEmpty()) {
                continue;
            }
            if (l.startsWith("## ")) {
                Matcher m = HEAD_LINE.matcher(l);
                if (m.find()) {
                    branch = m.group(1);
                    upstream = m.group(2);
                    String ab = m.group(3) == null ? "" : m.group(3);
                    Matcher a = Pattern.compile("ahead (\\d+)").matcher(ab);
                    Matcher b = Pattern.compile("behind (\\d+)").matcher(ab);
                    ahead = a.find() ? Integer.parseInt(a.group(1)) : 0;
                    behind = b.find() ? Integer.parseInt(b.group(1)) : 0;
                }
                continue;
            }
            files.add(l.length() > 3 ? l.substring(3) : l);
        }
        return new RepoState(branch, upstream, ahead, behind, files);
    }

    /** 창 아래 상태 줄: 브랜치·변경 파일·올리지 않은 커밋·새 빌드 여부. 창이 열려 있을 때만 불린다(3초 캐시). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        if (!enabled()) {
            out.put("reason", disabledReason);
            return out;
        }
        Map<String, Object> c = statusCache;
        if (c == null || System.currentTimeMillis() - statusAt > STATUS_CACHE_MS) {
            c = readStatus();
            statusCache = c;
            statusAt = System.currentTimeMillis();
        }
        out.putAll(c);
        out.put("busy", busy());
        return out;
    }

    private Map<String, Object> readStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            GitOut st = git(GIT_TIMEOUT_MS, "status", "--porcelain=v1", "-b");
            if (st.exit() != 0) {
                m.put("error", abbreviate(st.err(), 300));
                return m;
            }
            RepoState s = parseStatus(st.out());
            m.put("branch", s.branch());
            m.put("upstream", s.upstream());
            m.put("ahead", s.ahead());
            m.put("behind", s.behind());
            m.put("dirty", s.files().size());
            m.put("files", s.files().size() > 30 ? s.files().subList(0, 30) : s.files());
            GitOut head = git(GIT_TIMEOUT_MS, "log", "-1", "--format=%h %s");
            m.put("head", head.exit() == 0 ? head.out().strip() : "");
            if (s.ahead() > 0) {
                GitOut un = git(GIT_TIMEOUT_MS, "log", "--format=%h %s", "@{u}..HEAD");
                m.put("unpushed", un.exit() == 0 ? un.out().strip().lines().limit(20).toList() : List.of());
            }
        } catch (RuntimeException e) {
            m.put("error", abbreviate(e.getMessage(), 300));
        }
        // 지금 떠 있는 JVM 보다 나중에 만들어진 jar 가 있으면 재시작해야 반영된다
        try {
            Path jar = repo.resolve("build").resolve("libs").resolve("dev-agent.jar");
            if (Files.isRegularFile(jar)) {
                long built = Files.getLastModifiedTime(jar).toMillis();
                m.put("builtAt", built);
                m.put("restartNeeded", built > ManagementFactory.getRuntimeMXBean().getStartTime());
            }
        } catch (IOException | RuntimeException e) {
            log.debug("빌드 산출물 확인 실패: {}", e.toString());
        }
        return m;
    }

    /** 현재 브랜치의 커밋을 origin 으로 올린다(강제 푸시 없음). 세션이 아니라 사람이 버튼으로 부른다. */
    public synchronized Map<String, Object> push() {
        if (!enabled()) {
            return result(false, "플랫폼 관리를 쓸 수 없습니다: " + disabledReason);
        }
        if (busy()) {
            return result(false, "요청을 처리하는 중입니다. 끝난 뒤 올리세요.");
        }
        try {
            GitOut st = git(GIT_TIMEOUT_MS, "status", "--porcelain=v1", "-b");
            if (st.exit() != 0) {
                return result(false, "git status 실패: " + abbreviate(st.err(), 300));
            }
            RepoState s = parseStatus(st.out());
            if (s.branch().isBlank() || s.branch().startsWith("HEAD")) {
                return result(false, "브랜치에 있지 않습니다(detached HEAD). 터미널에서 확인하세요.");
            }
            if (s.upstream() != null && s.ahead() == 0) {
                return result(false, "올릴 커밋이 없습니다.");
            }
            GitOut p = git(PUSH_TIMEOUT_MS, "push", "-u", "origin", s.branch());
            statusAt = 0;
            String text = (p.err().strip() + "\n" + p.out().strip()).strip();
            if (p.exit() != 0) {
                log.warn("플랫폼 저장소 푸시 실패(exit={}): {}", p.exit(), abbreviate(text, 400));
                return result(false, "푸시 실패: " + abbreviate(text, 600));
            }
            log.info("플랫폼 저장소 푸시: {} ({}건)", s.branch(), s.ahead());
            return result(true, s.branch() + " 브랜치를 origin 에 올렸습니다" + (s.ahead() > 0 ? " (커밋 " + s.ahead() + "건)." : "."));
        } catch (RuntimeException e) {
            return result(false, "푸시 실패: " + abbreviate(e.getMessage(), 400));
        }
    }

    record GitOut(int exit, String out, String err) {
    }

    private GitOut git(long timeoutMs, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-c", "core.longpaths=true"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");   // 자격증명이 없으면 묻지 말고 실패
        pb.environment().put("GCM_INTERACTIVE", "never");
        pb.environment().put("LC_ALL", "C.UTF-8");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("git 실행 실패: " + e.getMessage(), e);
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignore) {
            // 입력을 쓰지 않는다
        }
        CompletableFuture<String> out = read(p.getInputStream());
        CompletableFuture<String> err = read(p.getErrorStream());
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new IllegalStateException("git " + args[0] + " 타임아웃(" + timeoutMs / 1000 + "초)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IllegalStateException("git 대기 인터럽트", e);
        }
        return new GitOut(p.exitValue(), out.join(), err.join());
    }

    private static CompletableFuture<String> read(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static Map<String, Object> result(boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("result", message);
        return m;
    }

    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}

package io.github.lib0823.devagent.gitlab;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.ops.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.github.lib0823.devagent.gitlab.IssueTexts.*;

/**
 * GitLab 이슈 수정 에이전트 — 상태기계 + 메신저 대화.
 * <pre>
 * 이슈 스캔(라벨) → NEW 큐 → [ANALYZING] 소스 준비 + 원인분석/설계 → 자기방 전송 → DESIGN_WAIT
 *   ↳ 답변 "진행" → [FIXING] 코드 수정 → 변경 요약 전송 → REVIEW_WAIT
 *   ↳ 답변 "진행" → [PUSHING] ai/ 브랜치 커밋·푸시 → 이슈 코멘트·Draft MR → DONE
 *   ↳ 그 외 답변 = 피드백 → 같은 Claude 세션에 이어서 재설계/재수정
 *   ↳ "중단" → ABORTED (작업트리 원복)
 * </pre>
 * 동시에 1건만 진행한다. 긴 작업(분석/수정/푸시)은 단일 워커 스레드에서 실행해 폴러를 막지 않는다.
 * 사용자 확인은 <b>자기방에서만</b> 받는다(그룹방 무시).
 */
@Service
public class IssueAgentService {

    private static final Logger log = LoggerFactory.getLogger(IssueAgentService.class);
    private static final String PFX = "[이슈] ";

    private static final String SELF_REPLY_GUIDE = "자기방 답변은 「진행」「중단」「diff」「상태」「검증」(리뷰 대기에서 수정 없이 검증만 다시) 또는 「피드백: …」 만 인정합니다. 그 외 메시지는 무시합니다(상세 피드백은 대시보드에서).";

    private static final Pattern REF = Pattern.compile("([A-Za-z0-9_.\\-/]+)?#(\\d+)");
    private static final Pattern URL_REF = Pattern.compile("https?://[^\\s]+?/([A-Za-z0-9_.\\-/]+?)/-/issues/(\\d+)");

    private final GitLabProperties props;
    private final GitLabClient gitlab;
    private final GitRunner git;
    private final CodingAgent agent;
    private final IssueSessionMapper sessions;
    private final MessengerService client;
    private final AlertService alert;
    private final SettingService settings;
    private final IssueEventBus events;
    private final VerifyService verifier;
    /** 분리한 협력 객체: 참고 저장소 / 대상 프로젝트 / 자기방 알림 / 프롬프트. */
    private final IssueReferences refs;
    private final IssueProjects projectReg;
    private final IssueNotifier notifier;
    private final IssuePrompts prompts;
    /** 검증 중인 세션의 리뷰어 Claude 세션 id(중단 시 함께 종료). */
    private final Map<Long, String> reviewerSessions = new ConcurrentHashMap<>();
    private final io.github.lib0823.devagent.llm.UsageService usage;
    /** 사용량 한도로 멈춰 리뷰 대기에 둔 세션 → 리셋 뒤 자동 재개할 작업("FIX"=수정 이어서, "VERIFY"=검증 다시). 재기동하면 비워지므로 그땐 수동 재개. */
    private final Map<Long, String> limitPaused = new ConcurrentHashMap<>();
    private final int adminPort;

    /** 세션 작업 워커 풀(gitlab.concurrency 개). 같은 세션의 작업은 단일 워커 때처럼 순서대로 처리되도록 세션별 큐로 직렬화한다. */
    private final ExecutorService worker;
    private final int concurrency;
    /** 지금 워커가 작업 중인 세션 id(답변 수신 차단·대시보드 표시). */
    private final Set<Long> busySessions = ConcurrentHashMap.newKeySet();
    /** 세션별 직렬 실행 체인(같은 세션에 답변이 두 번 들어와도 순서 보장). */
    private final Map<Long, java.util.concurrent.CompletableFuture<Void>> sessionChains = new ConcurrentHashMap<>();
    /** 선분석(prefetch) 전용 워커: 메인 파이프라인이 사용자 확인을 기다리는 동안 다음 이슈를 미리 분석한다. */
    private final ExecutorService prefetchWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "issue-prefetch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean prefetchBusy = new AtomicBoolean(false);
    /** 중단 요청된 세션(HTTP/스케줄러 스레드가 표시, 워커가 Claude 실패를 중단으로 해석). */
    private final Set<Long> abortRequested = ConcurrentHashMap.newKeySet();
    /** 세션별 마지막 확인 리마인더 시각(메모리; 재기동 시 초기화되어 다시 한 번 알림). */
    private final Map<Long, Long> lastRemindAt = new ConcurrentHashMap<>();
    /** 자기방 무시 안내를 보낸 "세션id:상태" — 대기 단계당 1회만 안내. */
    private final Set<String> guidedStates = ConcurrentHashMap.newKeySet();
    /**
     * 세션별 "확인 대기 세대": 대기 상태(DESIGN_WAIT/REVIEW_WAIT)에 들어갈 때마다 1 올라간다. 답변은 접수 때 세대를 붙잡고
     * handleReply 가 비교한다 — 「피드백」 처리가 끝나 같은 대기 상태로 돌아왔을 때, 그 전에 접수된 「진행」이 사용자가 보지 못한
     * 새 결과를 승인하는 것을 막는다(상태 이름만으로는 구분되지 않는다). 메모리에만 둔다: 재기동하면 처리 중이던 답변도 함께 사라지고,
     * 이후 접수되는 답변은 그때의 값을 붙잡으므로 DB 컬럼 없이도 안전하다.
     */
    private final Map<Long, Long> waitGenerations = new ConcurrentHashMap<>();

    public IssueAgentService(GitLabProperties props, GitLabClient gitlab, GitRunner git,
                             CodingAgent agent, IssueSessionMapper sessions,
                             MessengerService client, AlertService alert, SettingService settings, IssueEventBus events, VerifyService verifier,
                             IssueReferences refs, IssueProjects projectReg, IssueNotifier notifier, IssuePrompts prompts,
                             io.github.lib0823.devagent.llm.UsageService usage,
                             @org.springframework.beans.factory.annotation.Value("${server.port:8777}") int adminPort) {
        this.usage = usage;
        this.verifier = verifier;
        this.refs = refs;
        this.projectReg = projectReg;
        this.notifier = notifier;
        this.prompts = prompts;
        this.concurrency = props.concurrencyOr();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        this.worker = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "issue-agent-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.props = props;
        this.gitlab = gitlab;
        this.git = git;
        this.agent = agent;
        this.sessions = sessions;
        this.client = client;
        this.alert = alert;
        this.settings = settings;
        this.events = events;
        this.adminPort = adminPort;
    }

    @PostConstruct
    void init() {
        projectReg.setOnAdded(() -> submit(this::scan));   // 대상 프로젝트가 추가되면 바로 스캔
        if (!props.enabled()) {
            log.info("GitLab 이슈 에이전트 비활성(gitlab.enabled=false)");
            return;
        }
        sessions.ensureTable();
        sessions.ensureColumns();
        events.ensureTable();
        // 재기동 시 작업 중(ANALYZING/FIXING/VERIFYING/PUSHING)이던 세션은 중간 상태를 알 수 없으므로 실패 처리 → 재시작 가능
        for (IssueSession active : sessions.findActiveAll()) {
            if (!active.isWaiting()) {
                active.setLastError("봇 재기동으로 중단됨(상태 " + active.getState() + ")");
                events.emit(active.getId(), IssueEvent.ERROR, "봇 재기동으로 중단됨 (상태 " + active.getState() + ")");
                transition(active, IssueSession.FAILED);
                log.warn("이슈 세션 {} 재기동으로 FAILED 처리", active.ref());
                git.cleanup(active.getProjectPath(), active.getIssueIid());
            } else {
                events.emit(active.getId(), IssueEvent.INFO, "봇 재기동 — 대기 상태 유지, 답변을 계속 받습니다");
            }
        }
        // 선분석 중이던 세션은 아직 사용자에게 보여준 게 없으므로 조용히 NEW 로 되돌린다
        IssueSession preparing = sessions.findPreparing();
        if (preparing != null) {
            git.cleanup(preparing.getProjectPath(), preparing.getIssueIid());
            events.emit(preparing.getId(), IssueEvent.INFO, "봇 재기동 — 선분석 중단, 큐로 복귀");
            transition(preparing, IssueSession.NEW);
        }
        log.info("GitLab 이슈 에이전트 활성 — projects={} label={} prefetch={}", projectReg.projects(), props.label(), props.prefetch());
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        prefetchWorker.shutdownNow();
    }

    public boolean enabled() {
        return props.enabled();
    }

    // ---------------------------------------------------------------- 폴링 진입점

    /** 라벨 이슈 스캔 → NEW 세션 등록 → 유휴면 다음 건 시작. */
    public int scan() {
        if (!props.enabled() || projectReg.projects() == null) {
            return 0;
        }
        int added = 0;
        for (String path : projectReg.projects()) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                GitLabClient.Project p = projectReg.project(path.strip());
                for (GitLabClient.Issue is : gitlab.openIssues(p.id(), props.label())) {
                    if (sessions.findByIssue(p.path(), is.iid()) != null) {
                        continue;
                    }
                    IssueSession s = newSession(p, is);
                    if (sessions.insertIgnore(s) > 0) {
                        added++;
                        events.emit(s.getId(), IssueEvent.INFO, "라벨 '" + props.label() + "' 스캔으로 큐 추가");
                        notifier.sendSelf(PFX + "큐 추가: " + s.ref() + " — " + is.title() + "\n" + is.webUrl());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("GitLab 이슈 스캔 실패 project={}: {}", path, e.toString());
            }
        }
        if (added > 0) {
            log.info("이슈 스캔 — {}건 큐 추가", added);
        }
        startNextIfIdle();
        return added;
    }

    /** 메신저 명령 라우터가 받은 확인 답변 후보의 처리 결과. */
    public enum ReplyOutcome {
        /** 대기 세션에 전달했다(작업은 워커에서 진행). */
        DELIVERED,
        /** 답변 형식이 아니라 무시했다(대기 단계당 1회 안내는 이미 보냈다). */
        IGNORED,
        /** 대기 세션이 2건 이상인데 세션 참조(repo#n)가 없어 전달하지 않았다(안내는 이미 보냈다). */
        NEEDS_REF,
        /** 답변을 기다리는 세션이 없다 — 호출측(라우터)이 명령 안내를 한다. */
        NO_WAITING
    }

    /**
     * 메신저 명령 라우터가 넘긴 글을 확인 답변으로 전달한다(도착하는 순간 — 대시보드 입력과 같은 {@code handleReply} 진입점).
     * 대기 세션이 1건이면 그 세션으로, 2건 이상이면 글 앞에 세션 참조(#1, shop#1, group/repo#1)가 있어야 한다.
     * 답변으로 인정하는 것은 명령(진행/승인/중단/diff/상태/검증)과 「피드백: …」 뿐이다 — 스티커·잡담이 설계 피드백으로 소비돼
     * 재설계가 돌았던 부작용(2026-11-10) 방지. 무시·참조 누락 안내는 대기 단계당 1회 여기서 보낸다.
     */
    public ReplyOutcome deliverReply(String text) {
        if (!props.enabled()) {
            return ReplyOutcome.NO_WAITING;
        }
        // 세대는 상태 조회보다 먼저 읽는다 — 나중에 읽으면 "옛 상태 + 새 세대"가 검사를 통과한다
        final Map<Long, Long> generations = Map.copyOf(waitGenerations);
        // 워커가 앞선 답변을 처리하는 중인 세션도 대상이다: 세션별 직렬 체인이 순서를 지키고, 그 사이 단계가 바뀌면 handleReply 가 버린다
        List<IssueSession> waiting = sessions.findWaiting();
        if (waiting.isEmpty()) {
            return ReplyOutcome.NO_WAITING;
        }
        String body = text == null ? "" : text.strip();
        IssueSession target = waiting.size() == 1 ? waiting.get(0) : null;
        String rest = body;
        if (waiting.size() > 1) {
            String[] split = splitSessionRef(body, waiting);
            if (split == null) {
                return rejectWithoutTarget(waiting, body);
            }
            target = waiting.stream().filter(w -> w.getId() == Long.parseLong(split[0])).findFirst().orElse(null);
            rest = split[1];
        }
        String accepted = parseSelfReply(rest);
        if (accepted == null || target == null) {
            guideIgnored(waiting.get(0), body);
            return ReplyOutcome.IGNORED;
        }
        dispatchReply(target, accepted, generations.getOrDefault(target.getId(), 0L));
        return ReplyOutcome.DELIVERED;
    }

    /** 대기 세션이 여럿인데 참조가 없는 글: 답변 형식이면 참조를 요청하고, 아니면 무시한다. */
    private ReplyOutcome rejectWithoutTarget(List<IssueSession> waiting, String body) {
        IssueSession first = waiting.get(0);
        if (body.isEmpty() || parseSelfReply(body) == null) {
            guideIgnored(first, body);
            return ReplyOutcome.IGNORED;
        }
        String refs = waiting.stream().map(w -> "#" + w.getIssueIid() + "(" + w.getProjectPath().substring(w.getProjectPath().lastIndexOf('/') + 1) + ")")
                .collect(Collectors.joining(", "));
        events.emit(first.getId(), IssueEvent.INFO, "대상 세션이 없는 답변 무시: " + abbreviate(body, 160));
        if (guidedStates.add("multi:" + waiting.stream().map(w -> String.valueOf(w.getId())).collect(Collectors.joining(",")))) {
            notifier.sendSelf(PFX + "확인 대기 중인 이슈가 " + waiting.size() + "건이라 답변 앞에 대상을 붙여 주세요. 예) 「shop#1 진행」「#7 피드백: …」 (대기: " + refs + ")"
                    + "\n방금 메시지는 적용하지 않았습니다: 「" + abbreviate(body, 40) + "」");
        }
        return ReplyOutcome.NEEDS_REF;
    }

    /** 답변이 아닌 글을 무시했다는 기록. 문장처럼 보일 때만 대기 단계당 한 번 안내한다(스티커 같은 짧은 것은 조용히 무시). */
    private void guideIgnored(IssueSession first, String body) {
        if (body.isEmpty()) {
            return;
        }
        log.info("자기 대화 메시지 무시(명령/피드백 아님): {}", abbreviate(body, 60));
        events.emit(first.getId(), IssueEvent.INFO, "자기 대화 메시지 무시(명령/피드백: 접두어 아님): " + abbreviate(body, 200));
        String guideKey = first.getId() + ":" + first.getState();
        if (looksLikeSentence(body) && guidedStates.add(guideKey)) {
            notifier.sendSelf(PFX + SELF_REPLY_GUIDE + " 방금 메시지는 적용하지 않았습니다: 「" + abbreviate(body, 40) + "」");
        }
    }

    private void dispatchReply(IssueSession s, String reply, long generation) {
        final long id = s.getId();
        final String expected = s.getState();   // 같은 대기 단계·같은 세대(상태 조회 전에 읽은 값)의 답변만 적용
        log.info("이슈 {} 답변 수신: {}", s.ref(), abbreviate(reply, 80));
        events.emit(id, IssueEvent.REPLY, "자기 대화 답변: " + abbreviate(reply, 200), reply);
        submit(id, () -> handleReply(id, reply, expected, generation, null));
    }

    private long waitGeneration(long id) {
        return waitGenerations.getOrDefault(id, 0L);
    }

    /** 확인 대기가 길어진 세션을 상기시킨다(스케줄러가 주기적으로 호출). */
    public void remindWaiting() {
        if (!props.enabled()) {
            return;
        }
        sessions.findWaiting().stream().filter(w -> !isBusy(w.getId())).forEach(this::remindIfStale);
    }

    /** 확인 대기가 길어지면(기본 4시간) 주기적으로 자기방에 상기시킨다. 큐가 뒤에서 막히는 것을 사용자가 알 수 있게. */
    private void remindIfStale(IssueSession s) {
        long remindMs = props.waitRemindMs();
        if (remindMs <= 0 || s.getUpdatedAt() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long waitedMs = now - s.getUpdatedAt().toInstant().toEpochMilli();
        long last = lastRemindAt.getOrDefault(s.getId(), 0L);
        if (waitedMs < remindMs || now - last < remindMs) {
            return;
        }
        lastRemindAt.put(s.getId(), now);
        long hours = Math.max(1, waitedMs / 3_600_000L);
        IssueSession next = nextSession();
        String what = IssueSession.DESIGN_WAIT.equals(s.getState()) ? "설계안 확인" : "변경 내용 리뷰";
        events.emit(s.getId(), IssueEvent.INFO, "확인 대기 " + hours + "시간 경과 — 리마인더 발송");
        notifier.sendSelf(PFX + "⏰ " + s.ref() + " " + what + "을 " + hours + "시간째 기다리고 있습니다."
                + (next != null ? " 뒤에 " + next.ref() + " 이(가) 대기 중입니다." : "")
                + "\n답변: 진행 / 중단 / 피드백: …  (대시보드 :8777/agent.html#s=" + s.getId() + ")");
    }

    public String injectReply(String text) {
        return injectReply(text, null);
    }

    /**
     * 관리 API/페이지에서 넣는 답변 — 자기방 메시지와 동일하게 처리한다(진행/중단/diff/피드백).
     * sessionId 가 주어지면 화면이 보고 있던 세션과 현재 대기 세션이 같을 때만 받는다(세션이 바뀐 뒤의 오적용 방지).
     * 반환: 접수 결과 메시지.
     */
    public String injectReply(String text, Long sessionId) {
        return injectReply(text, sessionId, null);
    }

    /**
     * note: 「진행」과 함께 보낸 코멘트(대시보드 입력칸에 글을 쓴 채 진행을 누른 경우). 설계 확인에서는 설계대로 수정하되
     * 코멘트를 추가 지시로 넘기고, 리뷰 확인에서는 푸시하지 않고 코멘트를 반영해 추가 수정한다(확인 안 된 변경이 올라가지 않게).
     */
    public String injectReply(String text, Long sessionId, String note) {
        if (!props.enabled()) {
            return "이슈 에이전트가 비활성 상태입니다.";
        }
        final Map<Long, Long> generations = Map.copyOf(waitGenerations);   // 상태 조회보다 먼저 읽는다
        List<IssueSession> waiting = sessions.findWaiting();
        IssueSession s;
        if (sessionId != null) {
            s = sessions.find(sessionId);
            if (s == null || !s.isActive()) {
                return "세션 " + sessionId + " 은(는) 진행 중이 아닙니다. 화면을 새로고침해 주세요.";
            }
            if (!s.isWaiting()) {
                return s.ref() + " 는 지금 " + s.getState() + " 단계라 답변을 받지 않습니다.";
            }
        } else if (waiting.size() == 1) {
            s = waiting.get(0);
        } else if (waiting.isEmpty()) {
            return "확인을 기다리는 세션이 없습니다.";
        } else {
            return "확인 대기 중인 세션이 " + waiting.size() + "건입니다. 대상 세션을 지정해 주세요: "
                    + waiting.stream().map(IssueSession::ref).collect(Collectors.joining(", "));
        }
        if (isBusy(s.getId())) {
            return s.ref() + " 의 이전 답변을 처리하는 중입니다. 잠시 후 다시 시도하세요.";
        }
        final long id = s.getId();
        final String reply = text == null ? "" : text.strip();
        if (reply.isEmpty()) {
            return "답변 내용이 비어 있습니다.";
        }
        final String expected = s.getState();
        final long generation = generations.getOrDefault(id, 0L);   // 상태 조회 전에 읽어 둔 세대
        // 코멘트는 승인(진행)일 때만 따로 다룬다 — 그 외 답변은 본문 자체가 피드백이다
        final String extra = note == null || note.isBlank() || !APPROVE.contains(norm(reply)) ? null : note.strip();
        final String shown = extra == null ? reply : reply + " + 코멘트: " + extra;
        log.info("이슈 {} 답변 주입(관리 API): {}", s.ref(), abbreviate(shown, 80));
        notifier.sendSelf(PFX + "(관리 페이지 답변) " + abbreviate(shown, 200));
        events.emit(id, IssueEvent.REPLY, "모니터 답변: " + abbreviate(shown, 200), shown);
        submit(id, () -> handleReply(id, reply, expected, generation, extra));
        return s.ref() + " 에 답변을 전달했습니다: " + abbreviate(shown, 80);
    }

    /**
     * 에이전트 화면 "이슈 목록": 대상 프로젝트의 열린 이슈(최근 갱신 순 50건)와 각 이슈의 에이전트 세션 상태.
     * 화면이 창을 열 때만 부른다(주기 호출 없음). 반환: ok/project/label/issues 또는 ok=false/result.
     */
    public Map<String, Object> openIssueList(String project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (!props.enabled()) {
            out.put("result", "이슈 에이전트가 비활성 상태입니다 (GITLAB_ENABLED=false).");
            return out;
        }
        String resolved = projectReg.resolveProject(project == null ? "" : project.strip());
        if (resolved == null) {
            out.put("result", "대상 프로젝트에서 '" + project + "' 를 찾지 못했습니다.");
            return out;
        }
        try {
            GitLabClient.Project p = projectReg.project(resolved);
            List<Map<String, Object>> list = new ArrayList<>();
            for (GitLabClient.Issue is : gitlab.recentOpenIssues(p.id(), 50)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("iid", is.iid());
                m.put("title", is.title());
                m.put("labels", is.labels());
                m.put("author", is.author());
                m.put("webUrl", is.webUrl());
                IssueSession s = sessions.findByIssue(p.path(), is.iid());
                if (s != null) {
                    m.put("sessionId", s.getId());
                    m.put("state", s.getState());
                    m.put("active", s.isActive());
                }
                list.add(m);
            }
            out.put("ok", true);
            out.put("project", p.path());
            out.put("label", props.label());
            out.put("issues", list);
        } catch (RuntimeException e) {
            log.warn("GitLab 이슈 목록 조회 실패 project={}: {}", resolved, e.toString());
            out.put("result", "이슈 목록 조회 실패: " + e.getMessage());
        }
        return out;
    }

    /**
     * 플랫폼 화면에서 GitLab 이슈를 등록한다. 대상 프로젝트에 등록된 저장소만 받는다.
     * start 면 스캔 라벨을 붙여 만들고 곧바로 큐에 넣는다(스캔 주기를 기다리지 않음). 반환: ok/result/url/ref.
     */
    public Map<String, Object> createIssue(String project, String title, String description, boolean start) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (!props.enabled()) {
            out.put("result", "이슈 에이전트가 비활성 상태입니다 (GITLAB_ENABLED=false).");
            return out;
        }
        String t = title == null ? "" : title.strip();
        if (t.isEmpty() || t.length() > 255) {
            out.put("result", t.isEmpty() ? "제목을 입력해 주세요." : "제목은 255자까지입니다.");
            return out;
        }
        String resolved = projectReg.resolveProject(project == null ? "" : project.strip());
        if (resolved == null) {
            out.put("result", "대상 프로젝트에서 '" + project + "' 를 찾지 못했습니다: " + projectReg.projects());
            return out;
        }
        try {
            GitLabClient.Project p = projectReg.project(resolved);
            GitLabClient.Issue is = gitlab.createIssue(p.id(), t, description == null ? "" : description.strip(), start ? props.label() : null);
            String ref = p.path() + "#" + is.iid();
            out.put("ok", true);
            out.put("ref", ref);
            out.put("url", is.webUrl());
            out.put("result", start ? "이슈를 등록했습니다. " + startManual(ref)
                    : ref + " 이슈를 등록했습니다(라벨 없음 — 에이전트는 시작하지 않습니다).");
        } catch (RuntimeException e) {
            log.warn("GitLab 이슈 등록 실패 project={}: {}", resolved, e.toString());
            out.put("result", "이슈 등록 실패: " + e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------- 명령 (이슈 ...)

    /**
     * {@code 이슈 <하위명령>} 처리. 반환 문자열은 호출측이 그대로 전송.
     * 하위명령: 상태 | 중단 | 스캔 | 목록 | &lt;group/repo#iid | repo#iid | 이슈 URL&gt;
     */
    public String command(String rest) {
        if (!props.enabled()) {
            return "이슈 에이전트가 비활성 상태입니다 (GITLAB_ENABLED=false).";
        }
        String r = rest == null ? "" : rest.strip();
        String key = norm(r);
        if (key.isEmpty() || key.equals("도움말") || key.equals("help")) {
            return help();
        }
        if (STATUS.contains(key)) {
            return statusText();
        }
        if (key.equals("목록") || key.equals("list")) {
            return listText();
        }
        if (key.equals("스캔") || key.equals("scan")) {
            // 워커가 수정/검증 중이어도 기다리지 않도록 호출 스레드에서 바로 돈다(스케줄러 스캔과 같은 경로, 선점은 DB 원자 UPDATE)
            int added = scan();
            return added > 0 ? "이슈 스캔 완료 — " + added + "건 큐 추가." : "이슈 스캔 완료 — 새 이슈 없음(대상 " + projectReg.projects().size() + "개 프로젝트, 라벨 '" + props.label() + "').";
        }
        if (key.equals("프로젝트") || key.equals("projects")) {
            return "대상 프로젝트(" + projectReg.projects().size() + "개): " + String.join(", ", projectReg.projects())
                    + "\n추가: 이슈 프로젝트 추가 group/repo · 삭제: 이슈 프로젝트 삭제 group/repo";
        }
        if (r.startsWith("프로젝트 ")) {
            String[] parts = r.substring(5).strip().split("\\s+", 2);
            String sub = norm(parts[0]);
            String arg = parts.length > 1 ? parts[1] : "";
            if (sub.equals("추가") || sub.equals("add")) {
                return addProject(arg);
            }
            if (sub.equals("삭제") || sub.equals("제거") || sub.equals("remove")) {
                return removeProject(arg);
            }
        }
        if (ABORT.contains(key) || r.startsWith("중단 ")) {
            List<IssueSession> active = sessions.findActiveAll();
            if (active.isEmpty()) {
                return "진행 중인 이슈가 없습니다.";
            }
            String[] split = r.startsWith("중단 ") ? splitSessionRef(r.substring(3), active) : null;
            if (split != null) {
                return requestAbort(Long.parseLong(split[0]), "사용자 요청");
            }
            if (active.size() == 1) {
                return requestAbort(active.get(0).getId(), "사용자 요청");
            }
            return "진행 중인 이슈가 " + active.size() + "건입니다. 「중단 shop#1」처럼 대상을 지정하세요: "
                    + active.stream().map(IssueSession::ref).collect(Collectors.joining(", "));
        }
        return startManual(r);
    }

    private String help() {
        return """
                이슈 에이전트 명령:
                • 이슈 상태 — 진행 중 세션/큐 현황
                • 이슈 목록 — 최근 세션 10건
                • 이슈 <group/repo#123 | repo#123 | 이슈 URL> — 해당 이슈 수동 시작
                • 이슈 스캔 — 라벨 이슈 즉시 스캔
                • 이슈 프로젝트 [추가|삭제 group/repo] — 대상 프로젝트 보기/추가/삭제
                • 이슈 중단 — 진행 중 세션 중단
                확인 대기 중 자기방 답변: 진행 / 중단 / diff / 상태 / 피드백: (문장) — 그 외 메시지는 무시(대시보드에선 접두어 없이 피드백)""";
    }

    private String startManual(String spec) {
        String path = null;
        long iid;
        Matcher u = URL_REF.matcher(spec);
        Matcher m = REF.matcher(spec);
        if (u.find()) {
            path = u.group(1);
            iid = Long.parseLong(u.group(2));
        } else if (m.find()) {
            path = m.group(1);
            iid = Long.parseLong(m.group(2));
        } else {
            return "이슈를 인식하지 못했습니다. 형식: group/repo#123 또는 이슈 URL\n" + help();
        }
        String resolved = projectReg.resolveProject(path);
        if (resolved == null) {
            return "대상 프로젝트에서 '" + path + "' 를 찾지 못했습니다: " + projectReg.projects() + "\n추가: 이슈 프로젝트 추가 group/repo (또는 관리 페이지)";
        }
        try {
            GitLabClient.Project p = projectReg.project(resolved);
            GitLabClient.Issue is = gitlab.issue(p.id(), iid);
            IssueSession existing = sessions.findByIssue(p.path(), iid);
            if (existing != null) {
                if (existing.isActive()) {
                    return existing.ref() + " 는 이미 진행 중입니다 (상태 " + existing.getState() + ").";
                }
                // 종료된 세션 재시작: 상태만 NEW 로 되돌린다
                existing.setState(IssueSession.NEW);
                existing.setClaudeSessionId(null);
                existing.setBranchName(null);
                existing.setDesignText(null);
                existing.setChangeSummary(null);
                existing.setCommitSha(null);
                existing.setMrUrl(null);
                existing.setLastError(null);
                existing.setFeedbackRounds(0);
                existing.setIssueTitle(is.title());
                sessions.update(existing);
                events.clear(existing.getId());
                events.emit(existing.getId(), IssueEvent.INFO, "수동 재시작 (이슈 " + spec.strip() + ")");
            } else {
                IssueSession ns = newSession(p, is);
                sessions.insertIgnore(ns);
                if (ns.getId() > 0) {
                    events.emit(ns.getId(), IssueEvent.INFO, "수동 시작 (이슈 " + spec.strip() + ")");
                }
            }
            submit(this::startNextIfIdle);
            int active = activeCount();
            return p.path() + "#" + iid + " (" + is.title() + ") 를 큐에 넣었습니다."
                    + (active >= concurrency ? " 현재 " + active + "건 진행 중(동시 " + concurrency + "건)이라 자리가 나면 시작합니다." : " 곧 분석을 시작합니다.");
        } catch (RuntimeException e) {
            return "이슈 조회 실패: " + e.getMessage();
        }
    }

    public String statusText() {
        List<IssueSession> active = sessions.findActiveAll();
        StringBuilder sb = new StringBuilder();
        if (active.isEmpty()) {
            sb.append("진행 중인 이슈: 없음 (동시 ").append(concurrency).append("건)");
        } else {
            sb.append("진행 중 ").append(active.size()).append("/").append(concurrency).append("건");
            for (IssueSession s : active) {
                sb.append("\n• ").append(s.ref()).append(" — ").append(abbreviate(s.getIssueTitle(), 50))
                  .append("\n  상태: ").append(s.getState()).append(isBusy(s.getId()) ? " (작업 중)" : "")
                  .append(" · 피드백 ").append(s.getFeedbackRounds()).append("회");
                if (s.getBranchName() != null) {
                    sb.append(" · 브랜치 ").append(s.getBranchName());
                }
            }
        }
        IssueSession ready = sessions.findReady();
        IssueSession preparing = sessions.findPreparing();
        IssueSession next = ready != null ? ready : preparing != null ? preparing : sessions.findNextNew();
        sb.append("\n다음 대기: ").append(next == null ? "없음" : next.ref() + " — " + next.getIssueTitle()
                + (ready != null ? " (설계 준비됨)" : preparing != null ? " (선분석 중)" : ""));
        return sb.toString();
    }

    private String listText() {
        List<IssueSession> list = sessions.recent(10);
        if (list.isEmpty()) {
            return "세션 이력이 없습니다.";
        }
        StringBuilder sb = new StringBuilder("최근 세션:");
        for (IssueSession s : list) {
            sb.append("\n• ").append(s.ref()).append(" [").append(s.getState()).append("] ").append(s.getIssueTitle());
            if (s.getMrUrl() != null && !s.getMrUrl().isBlank()) {
                sb.append("\n   MR: ").append(s.getMrUrl());
            }
        }
        return sb.toString();
    }

    public List<IssueSession> recent(int limit) {
        return sessions.recent(limit);
    }

    /** 목록용(본문 제외). */
    public List<IssueSession> recentLight(int limit) {
        return sessions.recentLight(limit);
    }

    // ---------------------------------------------------------------- 상태 전이 (워커 스레드)

    /**
     * 메인 파이프라인이 비어 있으면 다음 건을 시작한다. 선분석이 끝난(READY) 세션이 있으면 분석 없이 바로 설계안을 제시하고,
     * 없으면 NEW 세션을 분석한다. 그 다음 선분석 가능 여부를 본다.
     */
    /** 자리(동시 처리 수)가 남으면 READY → NEW 순으로 다음 건을 시작한다. present/analyze 가 claimState 로 다시 선점 확인하므로 중복 시작은 없다. */
    private void startNextIfIdle() {
        if (usage.isLimited()) {
            log.info("Claude 사용량 한도(리셋 {}) — 새 세션 시작 보류", usage.limitUntilText());
            return;                                   // 리셋 뒤 스캔 주기(2분)에서 다시 시도한다
        }
        resumeAfterLimit();                           // 한도로 멈춰 둔 세션부터 이어간다(자리 계산 전에 — 재개된 세션도 활성 건)
        int slots = concurrency - activeCount();
        for (int i = 0; i < slots; i++) {
            IssueSession ready = sessions.findReady();
            if (ready != null) {
                final long id = ready.getId();
                submit(id, () -> present(id));
                break;                                    // READY 는 1건뿐이고 제시 직후 상태가 바뀌므로 다음 루프에서 다시 본다
            }
            IssueSession next = sessions.findNextNew();
            if (next == null) {
                break;
            }
            final long id = next.getId();
            if (sessions.claimState(id, IssueSession.NEW, IssueSession.ANALYZING) != 1) {
                break;
            }
            submit(id, () -> analyze(id));
        }
        prefetchIfPossible();
    }

    /**
     * 선분석: 메인 파이프라인이 한 건을 진행(특히 사용자 확인 대기) 중이고 큐에 NEW 가 있으면, 다음 1건을 백그라운드에서
     * 미리 분석해 READY 로 둔다. 사용자에게는 한 번에 1건만 확인을 요청하므로 READY 는 앞 건이 끝난 뒤 제시된다.
     */
    private void prefetchIfPossible() {
        if (!props.prefetch() || prefetchBusy.get() || usage.isLimited()) {
            return;
        }
        if (activeCount() < concurrency) {
            return;                                   // 자리가 남아 있으면 워커가 직접 처리한다
        }
        if (sessions.findReady() != null || sessions.findPreparing() != null) {
            return;                                   // 선분석은 1건만 앞서 간다
        }
        IssueSession next = sessions.findNextNew();
        if (next == null) {
            return;
        }
        final long id = next.getId();
        prefetchWorker.submit(() -> {
            prefetchBusy.set(true);
            try {
                preAnalyze(id);
            } catch (RuntimeException e) {
                log.error("선분석 작업 오류: {}", e.toString(), e);
            } finally {
                prefetchBusy.set(false);
            }
        });
    }

    private void analyze(long id) {
        IssueSession s = sessions.find(id);
        if (s == null || !IssueSession.ANALYZING.equals(s.getState())) {
            return;                                   // startNextIfIdle 이 선점(NEW→ANALYZING)하지 못했거나 그 사이 중단됨
        }
        events.emit(id, IssueEvent.STATE, IssueSession.NEW + " → " + IssueSession.ANALYZING, IssueSession.ANALYZING);
        notifier.sendSelf(PFX + "분석 시작: " + s.ref() + " — " + s.getIssueTitle() + "\n소스를 받아 원인/설계를 정리합니다. (수 분 소요)");
        try {
            CodingAgent.Result r = analyzeCore(s, "분석");
            s.setDesignText(r.text());
            toWait(s, IssueSession.DESIGN_WAIT);
            notifier.sendSelf(designMessage(s, r.text()));
        } catch (CodingAgent.UsageLimitException e) {
            // 한도: 실패가 아니라 큐로 되돌려 리셋 뒤 다시 분석한다
            notifier.notifyLimit(e);
            events.emit(id, IssueEvent.ERROR, e.getMessage() + " — 큐(NEW)로 되돌립니다. 리셋 후 자동 재시작", String.valueOf(e));
            transition(s, IssueSession.NEW);
        } catch (RuntimeException e) {
            fail(s, "분석 단계", e);
        }
    }

    /** 선분석(prefetch 워커). 성공하면 READY, 실패하면 FAILED(메시지 발송). */
    private void preAnalyze(long id) {
        if (sessions.findReady() != null || sessions.findPreparing() != null) {
            return;                                   // 선분석은 1건만 앞서 간다(제출 시점과 실행 시점 사이 변화 재확인)
        }
        if (sessions.claimState(id, IssueSession.NEW, IssueSession.PREPARING) != 1) {
            return;
        }
        IssueSession s = sessions.find(id);
        events.emit(id, IssueEvent.STATE, IssueSession.NEW + " → " + IssueSession.PREPARING, IssueSession.PREPARING);
        log.info("이슈 {} 선분석 시작", s.ref());
        try {
            CodingAgent.Result r = analyzeCore(s, "선분석");
            s.setDesignText(r.text());
            s.setState(IssueSession.READY);
            if (sessions.updateIf(s, IssueSession.PREPARING) != 1) {      // 그 사이 중단됐으면(ABORTED) 덮어쓰지 않는다
                log.info("이슈 {} 선분석 결과 폐기(상태가 PREPARING 이 아님)", s.ref());
                git.cleanup(s.getProjectPath(), s.getIssueIid());
                return;
            }
            events.emit(id, IssueEvent.STATE, IssueSession.PREPARING + " → " + IssueSession.READY, IssueSession.READY);
            notifier.sendSelf(PFX + "다음 이슈 선분석 완료: " + s.ref() + " — " + s.getIssueTitle()
                    + "\n현재 건이 끝나면 바로 설계안을 보여드립니다.");
        } catch (RuntimeException e) {
            IssueSession now = sessions.find(id);
            if (abortRequested.contains(id) || now == null || !IssueSession.PREPARING.equals(now.getState())) {
                log.info("이슈 {} 선분석 종료(중단됨): {}", s.ref(), e.getMessage());
                return;                               // 중단 처리는 메인 워커의 abort() 가 담당
            }
            if (e instanceof CodingAgent.UsageLimitException le) {
                notifier.notifyLimit(le);
                if (sessions.claimState(id, IssueSession.PREPARING, IssueSession.NEW) == 1) {
                    events.emit(id, IssueEvent.STATE, IssueSession.PREPARING + " → " + IssueSession.NEW + " (사용량 한도, 리셋 후 재시도)", IssueSession.NEW);
                }
                return;
            }
            fail(s, "선분석 단계", e);
            return;
        }
        startNextIfIdle();                            // 메인이 비어 있으면 즉시 제시
    }

    /** READY 세션을 사용자에게 제시(DESIGN_WAIT). */
    private void present(long id) {
        if (activeCount() >= concurrency || sessions.claimState(id, IssueSession.READY, IssueSession.DESIGN_WAIT) != 1) {
            return;
        }
        IssueSession s = sessions.find(id);
        s.setState(IssueSession.READY);               // transition 로그용 from 상태
        toWait(s, IssueSession.DESIGN_WAIT);
        events.emit(id, IssueEvent.INFO, "선분석 결과 제시 — 사용자 확인 대기");
        notifier.sendSelf(designMessage(s, s.getDesignText()));
        prefetchIfPossible();
    }

    /** 분석 공통: 세션 worktree 준비 → 이슈/코멘트 수집 → Claude 분석. */
    private CodingAgent.Result analyzeCore(IssueSession s, String label) {
        final long id = s.getId();
        GitLabClient.Project p = projectReg.project(s.getProjectPath());
        GitLabClient.Issue is = gitlab.issue(p.id(), s.getIssueIid());
        // 컨벤션: 라벨로 hotfix 가 확정되면 main, 아니면 develop(없으면 기본 브랜치) 기준으로 작업트리를 만든다.
        // 유형은 분석 결과("## 작업 유형")로 바뀔 수 있고, hotfix 로 바뀌면 수정 시작 때 main 기준으로 다시 받는다.
        String labelType = labelWorkType(is.labels());
        s.setWorkId(workIdOf(is.title(), s.getIssueIid(), conv().workIdPattern()));
        if (labelType != null) {
            s.setWorkType(labelType);
        }
        String base = baseBranchFor(p, "hotfix".equals(labelType) ? "hotfix" : null);
        s.setBaseBranch(base);
        sessions.update(s);
        events.emit(id, IssueEvent.GIT, "소스 준비: fetch → origin/" + base + " 기준 세션 worktree 생성 (일감 " + s.getWorkId() + ")");
        git.prepare(p.path(), p.httpUrl(), base, s.getIssueIid());
        refs.ensureReferences(s, is.description());
        List<String> notes = safeNotes(p.id(), s.getIssueIid());

        s.setClaudeSessionId(UUID.randomUUID().toString());
        sessions.update(s);                           // 실행 중에도 DB 의 세션 id 로 runInfo(실행 중 모델)를 찾을 수 있게 바로 저장
        CodingAgent.Result r = runClaude(s, label, false, props.agent().analyzeTools(), prompts.analyzePrompt(is, notes));
        if (r.error()) {
            throw new IllegalStateException(label + " 실패: " + abbreviate(r.text(), 300));
        }
        applyWorkType(s, r.text(), labelType);
        return r;
    }

    /** 분석/재설계 결과의 "## 작업 유형" 으로 유형을 정한다. 라벨로 hotfix 가 확정된 경우는 라벨이 우선. */
    private void applyWorkType(IssueSession s, String design, String labelType) {
        String type = labelType != null ? labelType : parseWorkType(design, conv().prefixesOr());
        if (type == null) {
            type = s.getWorkType() != null ? s.getWorkType() : "fix";
        }
        s.setWorkType(type);
        sessions.update(s);
        events.emit(s.getId(), IssueEvent.INFO, "작업 유형 " + type + " → 브랜치 " + git.branchName(type, s.getWorkId())
                + " (" + baseBranchFor(projectReg.project(s.getProjectPath()), type) + " 기준, MR → " + mrTargetFor(projectReg.project(s.getProjectPath())) + ")");
    }

    private GitLabProperties.Convention conv() {
        GitLabProperties.Convention c = props.convention();
        return c != null ? c : new GitLabProperties.Convention("develop", "main", "develop", List.of(), List.of(), "#?(\\d{5,7})");
    }

    /** 이슈 라벨에 hotfix 라벨이 있으면 "hotfix", 아니면 null. */
    private String labelWorkType(List<String> labels) {
        if (labels == null || conv().hotfixLabels() == null) {
            return null;
        }
        for (String l : labels) {
            for (String h : conv().hotfixLabels()) {
                if (l != null && l.strip().equalsIgnoreCase(h.strip())) {
                    return "hotfix";
                }
            }
        }
        return null;
    }

    /** 작업 유형에 따른 분기 기준 브랜치: hotfix → hotfix-base(main), 그 외 → base-branch(develop). 원격에 없으면 기본 브랜치. */
    private String baseBranchFor(GitLabClient.Project p, String workType) {
        String want = "hotfix".equals(workType) ? conv().hotfixBase() : conv().baseBranch();
        return remoteBranch(p, want);
    }

    private String mrTargetFor(GitLabClient.Project p) {
        return remoteBranch(p, conv().mrTarget());
    }

    private final Map<String, Boolean> branchExistsCache = new ConcurrentHashMap<>();

    /** 원격에 있으면 그 이름, 없으면 프로젝트 기본 브랜치(결과는 캐시). */
    private String remoteBranch(GitLabClient.Project p, String name) {
        if (name == null || name.isBlank() || name.equals(p.defaultBranch())) {
            return p.defaultBranch();
        }
        boolean exists = branchExistsCache.computeIfAbsent(p.id() + ":" + name, k -> {
            try {
                return gitlab.branchExists(p.id(), name);
            } catch (RuntimeException e) {
                log.debug("브랜치 존재 확인 실패 {} {}: {}", p.path(), name, e.toString());
                return false;
            }
        });
        return exists ? name : p.defaultBranch();
    }

    private String designMessage(IssueSession s, String design) {
        return PFX + "설계안 " + s.ref() + " — " + s.getIssueTitle() + "\n" + s.getIssueUrl()
                + "\n\n" + design + "\n\n" + branchPlan(s) + "\n\n" + askDesign();
    }

    /** 승인 때 확인할 브랜치 계획 한 줄(컨벤션). */
    private String branchPlan(IssueSession s) {
        GitLabClient.Project p = projectReg.project(s.getProjectPath());
        String type = s.getWorkType() == null ? "fix" : s.getWorkType();
        return "## 브랜치 계획\n" + git.branchName(type, s.getWorkId()) + " ← " + baseBranchFor(p, type) + " 기준, MR → " + mrTargetFor(p)
                + ("hotfix".equals(type) ? " (긴급: main 에서 분기, 배포 후 develop 병합)" : "")
                + "\n유형을 바꾸려면 피드백으로 '작업 유형: hotfix' 처럼 알려 주세요.";
    }

    private String dashboardUrl(IssueSession s) {
        return "http://localhost:" + adminPort + "/agent.html#s=" + s.getId();
    }

    /** 세션 작업 디렉터리. worktree 도입 전(기준 clone 에서 분석된) 세션은 기준 clone 으로 폴백한다. */
    private Path dirOf(IssueSession s) {
        Path dir = git.sessionDir(s.getProjectPath(), s.getIssueIid());
        if (Files.exists(dir.resolve(".git"))) {
            return dir;
        }
        Path base = git.repoDir(s.getProjectPath());
        return Files.isDirectory(base.resolve(".git")) && s.getClaudeSessionId() != null ? base : dir;
    }

    /** 특정 세션 중단(대시보드). 활성/선분석/대기열 어느 상태든 가능. */
    public String abortSession(long id) {
        IssueSession s = sessions.find(id);
        if (s == null) {
            return "세션을 찾을 수 없습니다.";
        }
        if (!s.isActive()) {
            return s.ref() + " 는 이미 종료된 세션입니다 (" + s.getState() + ").";
        }
        return requestAbort(id, "사용자 요청(대시보드)");
    }

    /**
     * 중단 요청(HTTP/스케줄러 스레드). 실행 중인 Claude 프로세스는 즉시 종료하고, 상태 전이는 워커에서 처리한다:
     * 대기 중이면 큐의 abort 가 바로 실행되고, 작업 중이면 Claude 실패를 {@link #fail} 이 중단으로 해석한다.
     */
    private String requestAbort(long id, String reason) {
        IssueSession s = sessions.find(id);
        if (s == null || !s.isActive()) {
            return "진행 중인 이슈가 아닙니다.";
        }
        abortRequested.add(id);
        boolean killed = agent.kill(s.getClaudeSessionId());
        String reviewer = reviewerSessions.get(id);
        if (reviewer != null) {
            killed |= agent.kill(reviewer);
        }
        if (killed) {
            events.emit(id, IssueEvent.INFO, "중단 요청 — 실행 중인 Claude 프로세스 종료");
        }
        submit(id, () -> abort(id, reason));
        return s.ref() + " 중단 요청을 접수했습니다." + (killed ? " (실행 중이던 작업을 종료합니다)" : "");
    }

    /** note: 「진행」과 함께 온 코멘트(없으면 null) — {@link #injectReply(String, Long, String)} 참고. */
    private void handleReply(long id, String reply, String expectedState, long expectedGeneration, String note) {
        IssueSession s = sessions.find(id);
        if (s == null || !s.isWaiting()) {
            return;
        }
        if (expectedState != null && !expectedState.equals(s.getState())) {
            // 같은 대기 단계에 답변이 두 번 들어온 경우(더블클릭, 메신저+대시보드 동시): 앞 답변으로 단계가 이미 바뀌었으므로 무시
            events.emit(id, IssueEvent.INFO, "답변 무시 — 대상 단계(" + expectedState + ")가 이미 " + s.getState() + " 로 바뀜: " + abbreviate(reply, 80));
            notifier.sendSelf(PFX + "앞선 답변으로 단계가 바뀌어 이 답변은 적용하지 않았습니다: 「" + abbreviate(reply, 60) + "」\n현재 " + s.getState()
                    + " — 필요하면 다시 답해 주세요.");
            return;
        }
        if (expectedGeneration != waitGeneration(id)) {
            // 앞 답변(피드백 등) 처리가 끝나 같은 대기 상태로 돌아왔다: 이 답변은 새 결과를 보기 전에 접수된 것이라 적용하지 않는다
            events.emit(id, IssueEvent.INFO, "답변 무시 — 앞 답변 처리 뒤 새 결과가 나옴: " + abbreviate(reply, 80));
            notifier.sendSelf(PFX + "앞 답변을 처리하는 사이 새 결과가 나와 이 답변은 적용하지 않았습니다: 「" + abbreviate(reply, 60) + "」\n"
                    + s.ref() + " 새 결과를 확인하고 다시 답해 주세요.");
            return;
        }
        String raw = reply.strip();
        String key = norm(raw);

        // 대시보드 입력이 「이슈 …」 로 시작하면 명령으로 처리(메신저의 이슈 명령은 라우터가 먼저 받아 여기로 오지 않는다)
        if (raw.startsWith("이슈 ") || raw.equals("이슈")) {
            notifier.sendSelf(PFX + command(raw.substring(2).strip()));
            return;
        }
        if (STATUS.contains(key)) {
            notifier.sendSelf(PFX + statusText());
            return;
        }
        if (DIFF.contains(key)) {
            Path dir = dirOf(s);
            String d = IssueSession.REVIEW_WAIT.equals(s.getState()) ? git.diff(dir, 6000) : "(아직 수정 전입니다)";
            notifier.sendSelf(PFX + s.ref() + " diff\n" + (d.isBlank() ? "(변경 없음)" : d));
            return;
        }
        if (ABORT.contains(key)) {
            abort(s.getId(), "사용자 요청");
            return;
        }
        if (IssueSession.REVIEW_WAIT.equals(s.getState()) && isVerifyOnly(raw)) {
            // 수정 없이 검증만 다시: 봇 검사 + 독립 리뷰어(새 세션). FAIL 이면 평소처럼 자동 재수정으로 이어진다
            events.emit(id, IssueEvent.INFO, "검증 재실행 요청(코드 수정 없음): " + abbreviate(raw, 80));
            try {
                verify(s);
            } catch (RuntimeException e) {
                fail(s, "검증 단계(재실행)", e);
            }
            return;
        }
        boolean approve = APPROVE.contains(key);
        try {
            if (IssueSession.DESIGN_WAIT.equals(s.getState())) {
                if (approve) {
                    fix(s, null, note);
                } else {
                    reviseDesign(s, raw);
                }
            } else { // REVIEW_WAIT
                if (approve && note != null) {
                    // 코멘트가 붙은 진행: 반영되지 않은 요청을 둔 채 푸시하지 않는다 → 추가 수정 후 다시 리뷰 확인
                    events.emit(id, IssueEvent.INFO, "진행과 함께 온 코멘트 — 푸시 전에 반영합니다(수정·검증 뒤 다시 리뷰 확인)");
                    fix(s, note);
                } else if (approve) {
                    push(s);
                } else {
                    fix(s, raw);
                }
            }
        } catch (RuntimeException e) {
            fail(s, s.getState() + " 단계", e);
        }
    }

    private void reviseDesign(IssueSession s, String feedback) {
        s.setFeedbackRounds(s.getFeedbackRounds() + 1);
        transition(s, IssueSession.ANALYZING);
        notifier.sendSelf(PFX + "피드백 반영해 설계를 다시 정리합니다…");
        CodingAgent.Result r = runClaude(s, "설계 수정 " + s.getFeedbackRounds(), true, props.agent().analyzeTools(),
                "사용자 피드백:\n" + feedback + "\n\n피드백을 반영해 설계안을 수정하고, 같은 출력 형식(## 작업 유형 / ## 원인 / ## 수정 설계 / ## 영향/위험 / ## 검증 방법 / ## 확인 필요)으로 전체를 다시 작성하세요. "
                        + "피드백이 질문이면 먼저 '## 답변' 절에 답한 뒤 설계안을 이어서 쓰세요. 코드는 아직 수정하지 마세요.");
        if (r.error()) {
            throw new IllegalStateException("설계 수정 실패: " + abbreviate(r.text(), 300));
        }
        s.setDesignText(r.text());
        // 사용자가 피드백에서 "작업 유형: hotfix" 처럼 직접 지정하면 그것이 우선, 아니면 라벨 → 설계안 순
        String forced = parseWorkType("## 작업 유형\n" + feedbackTypeLine(feedback), conv().prefixesOr());
        applyWorkType(s, r.text(), forced != null ? forced : labelWorkTypeOf(s));
        toWait(s, IssueSession.DESIGN_WAIT);
        notifier.sendSelf(PFX + "설계안(수정 " + s.getFeedbackRounds() + ") " + s.ref() + "\n\n" + r.text() + "\n\n" + branchPlan(s) + "\n\n" + askDesign());
    }

    /** 세션 이슈의 라벨로 hotfix 여부 재확인(재설계 때). 조회 실패면 null. */
    private String labelWorkTypeOf(IssueSession s) {
        try {
            return labelWorkType(gitlab.issue(s.getProjectId(), s.getIssueIid()).labels());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void fix(IssueSession s, String feedback) {
        fix(s, feedback, null);
    }

    /** approveNote: 설계 승인(첫 수정)과 함께 온 코멘트 — 설계안에 더해 따를 추가 지시로 프롬프트에 붙인다. */
    private void fix(IssueSession s, String feedback, String approveNote) {
        boolean first = feedback == null;
        if (!first) {
            s.setFeedbackRounds(s.getFeedbackRounds() + 1);
        }
        transition(s, IssueSession.FIXING);
        notifier.sendSelf(PFX + (first ? "설계 승인 — 코드 수정을 시작합니다. (수 분 소요)" : "피드백 반영해 코드를 추가 수정합니다…"));
        refs.ensureReferences(s, null);                    // 재기동 뒤 피드백 라운드여도 참고 저장소 worktree·안내를 복원

        Path dir = dirOf(s);
        String rebased = null;
        if (first) {
            // 컨벤션 기준 브랜치가 분석 때와 달라졌으면(예: 설계에서 hotfix 로 판정 → main) 작업트리를 그 기준으로 다시 받는다
            GitLabClient.Project p = projectReg.project(s.getProjectPath());
            String want = baseBranchFor(p, s.getWorkType());
            if (s.getBaseBranch() == null || !want.equals(s.getBaseBranch())) {
                events.emit(s.getId(), IssueEvent.GIT, "기준 브랜치 변경 " + s.getBaseBranch() + " → " + want + " (작업 유형 " + s.getWorkType() + "): 작업트리 재생성");
                git.prepare(p.path(), p.httpUrl(), want, s.getIssueIid());
                s.setBaseBranch(want);
                sessions.update(s);
                rebased = want;
            }
        }
        String prompt = first ? prompts.fixPrompt(s) + (rebased == null ? "" : "\n\n참고: 작업트리를 origin/" + rebased + " 기준으로 새로 받았습니다(분석 때와 기준 브랜치가 다름). 파일 내용이 분석 때와 다를 수 있으니 수정 전 다시 읽으세요.")
                + (approveNote == null ? "" : "\n\n사용자가 설계를 승인하면서 남긴 추가 지시(설계안과 어긋나면 이 지시가 우선):\n" + approveNote) :
                "사용자 피드백:\n" + feedback + "\n\n피드백을 반영해 코드를 추가 수정하고, 같은 출력 형식(## 변경 요약 / ## 검증 결과 / ## 커밋 메시지)으로 전체 변경 기준으로 다시 출력하세요. "
                        + "피드백이 질문이면 먼저 '## 답변' 절에 답하세요. git 명령으로 커밋/브랜치를 만들지 마세요.";
        CodingAgent.Result r;
        try {
            r = runClaude(s, first ? "코드 수정" : "추가 수정 " + s.getFeedbackRounds(), true, props.agent().fixTools(), prompt, true);
        } catch (CodingAgent.AgentTimeoutException e) {
            if (timeoutToReview(s, e)) {
                return;
            }
            throw e;
        } catch (CodingAgent.UsageLimitException e) {
            notifier.notifyLimit(e);
            if (interruptedToReview(s, e.getMessage())) {
                limitPaused.put(s.getId(), "FIX");   // 리셋 뒤 자동으로 「이어서 진행」
                return;
            }
            // 아직 아무것도 못 고쳤으면 설계 확인 대기로 되돌려 리셋 뒤 「진행」으로 다시 시작할 수 있게
            events.emit(s.getId(), IssueEvent.ERROR, e.getMessage() + " — 변경 없음, 설계 확인 대기로 되돌립니다", String.valueOf(e));
            toWait(s, IssueSession.DESIGN_WAIT);
            notifier.sendSelf(PFX + "사용량 한도로 수정을 시작하지 못했습니다 " + s.ref() + ". 리셋(" + usage.limitUntilText() + ") 후 「진행」으로 다시 시작하세요.");
            return;
        }
        afterFix(s, r);
    }

    /**
     * 검증 실패 → 자동 재수정(사용자 피드백 라운드로 세지 않는다). 같은 Claude 세션을 이어가므로 맥락이 유지된다.
     */
    private void fixAuto(IssueSession s, String failureReport) {
        transition(s, IssueSession.FIXING);
        notifier.sendSelf(PFX + "검증 실패 → 자동 재수정 " + s.getVerifyRounds() + "/" + verifier.cfg().maxRetries() + " (" + s.ref() + ")");
        String prompt = "자동 검증에서 실패했습니다. 아래 검사 결과와 리뷰어의 수정 요청을 반영해 코드를 고치세요.\n\n" + failureReport
                + "\n\n같은 출력 형식(## 변경 요약 / ## 검증 결과 / ## 커밋 메시지)으로 전체 변경 기준으로 다시 출력하세요. "
                + "git 명령으로 커밋/브랜치를 만들지 마세요.";
        CodingAgent.Result r;
        try {
            r = runClaude(s, "검증 반영 수정 " + s.getVerifyRounds(), true, props.agent().fixTools(), prompt, true);
        } catch (CodingAgent.AgentTimeoutException e) {
            if (timeoutToReview(s, e)) {
                return;
            }
            throw e;
        } catch (CodingAgent.UsageLimitException e) {
            notifier.notifyLimit(e);
            if (interruptedToReview(s, e.getMessage())) {
                limitPaused.put(s.getId(), "FIX");
                return;
            }
            throw e;
        }
        afterFix(s, r);
    }

    /**
     * 사용량 한도가 풀리면(리셋 시각 경과) 한도 때문에 리뷰 대기에 멈춰 둔 세션을 자동 재개한다 — "FIX" 는 같은 수정 세션을
     * 「이어서 진행」으로, "VERIFY" 는 검증을 다시. 사용자가 그 사이 직접 답했거나(상태가 바뀜) 작업 중이면 건너뛴다.
     */
    private void resumeAfterLimit() {
        if (limitPaused.isEmpty() || usage.isLimited()) {
            return;
        }
        for (Map.Entry<Long, String> en : new ArrayList<>(limitPaused.entrySet())) {
            final long id = en.getKey();
            IssueSession s = sessions.find(id);
            if (s == null || !IssueSession.REVIEW_WAIT.equals(s.getState())) {
                limitPaused.remove(id);               // 이미 사람이 진행/중단했거나 끝남
                continue;
            }
            if (busySessions.contains(id)) {
                continue;
            }
            limitPaused.remove(id);
            final String kind = en.getValue();
            events.emit(id, IssueEvent.REPLY, "자동 재개: 사용량 한도 리셋 → " + ("VERIFY".equals(kind) ? "검증 다시 실행" : "수정 이어서 진행"));
            notifier.sendSelf(PFX + "사용량 한도가 풀려 " + s.ref() + " 을(를) 자동으로 이어갑니다 (" + ("VERIFY".equals(kind) ? "검증 재실행" : "수정 계속") + ").");
            submit(id, () -> {
                IssueSession cur = sessions.find(id);
                if (cur == null || !IssueSession.REVIEW_WAIT.equals(cur.getState())) {
                    return;
                }
                try {
                    if ("VERIFY".equals(kind)) {
                        verify(cur);
                    } else {
                        fix(cur, "이어서 진행 — 사용량 한도로 중단됐던 작업을 중단 지점부터 계속하세요. 이미 반영된 변경은 다시 하지 말고, 남은 항목을 마저 처리한 뒤 전체 기준으로 보고하세요.");
                    }
                } catch (RuntimeException e) {
                    fail(cur, cur.getState() + " 단계(자동 재개)", e);
                }
            });
        }
    }

    /** 수정 세션 결과 처리: diff 기록 → (검증 단계) → 리뷰 대기. */
    private void afterFix(IssueSession s, CodingAgent.Result r) {
        if (r.error()) {
            // 사용량 한도·API 오류 등으로 세션이 끊겨도 작업트리에 변경이 있으면 버리지 않고 리뷰 대기로(「피드백: 이어서 진행」으로 재개)
            if (interruptedToReview(s, "수정 세션 오류: " + abbreviate(r.text(), 200))) {
                return;
            }
            throw new IllegalStateException("수정 실패: " + abbreviate(r.text(), 300));
        }
        Path dir = dirOf(s);
        String stat = git.diffStat(dir);
        events.emit(s.getId(), IssueEvent.GIT, "git diff --stat: " + abbreviate(lastLine(stat), 120), stat);
        s.setChangeSummary(r.text());
        sessions.update(s);
        if (verifier.enabled() && !stat.isBlank()) {
            verify(s);
            return;
        }
        toReviewWait(s, null);
    }

    /**
     * 검증 단계: 봇이 직접 돌리는 결정적 검사(JS 문법·빌드) + 독립 리뷰어 세션(새 Claude 세션, 읽기 전용).
     * 실패면 실패 보고를 피드백으로 자동 재수정(상한 gitlab.verify.max-retries), 그래도 실패면 보고와 함께 리뷰 대기로 올린다.
     */
    private void verify(IssueSession s) {
        final long id = s.getId();
        transition(s, IssueSession.VERIFYING);
        s.setVerifyRounds(s.getVerifyRounds() + 1);
        sessions.update(s);
        int round = s.getVerifyRounds();
        Path dir = dirOf(s);
        notifier.sendSelf(PFX + "수정 완료 — 검증 " + round + "회차 (" + s.ref() + "): 빌드·정적검사 후 독립 리뷰어가 확인합니다. (수 분)");

        // 1) 결정적 검사
        List<String> changed = git.changedFiles(dir);
        events.emit(id, IssueEvent.INFO, "검증 " + round + "회차 시작 — 변경 파일 " + changed.size() + "개", String.join("\n", changed));
        List<VerifyService.Check> checks = verifier.runChecks(dir, s.getProjectPath(), changed, () -> abortRequested.contains(id));
        for (VerifyService.Check c : checks) {
            events.emit(id, c.ok() || c.skipped() ? IssueEvent.INFO : IssueEvent.ERROR,
                    "[" + c.status() + "] " + c.name() + (c.skipped() ? "" : " (" + c.elapsedMs() / 1000 + "s)"), c.output());
        }
        if (abortRequested.contains(id)) {
            throw new IllegalStateException("검증 중 중단 요청");
        }
        boolean checksOk = VerifyService.allOk(checks);

        // 2) 독립 리뷰어
        VerifyService.Verdict verdict = VerifyService.Verdict.UNKNOWN;
        String reviewText = "";
        if (verifier.cfg().reviewer()) {
            String desc = "";
            try {
                desc = gitlab.issue(s.getProjectId(), s.getIssueIid()).description();
            } catch (RuntimeException e) {
                log.debug("이슈 본문 재조회 실패(무시): {}", e.toString());
            }
            String prompt = verifier.reviewerPrompt(s, desc, git.diff(dir, 20000), checks, round);
            String reviewerSession = UUID.randomUUID().toString();
            reviewerSessions.put(id, reviewerSession);
            SettingService.AgentModels am = settings.getAgentModels();
            String model = am.verifyModel() + "/" + am.verifyEffort();
            events.emit(id, IssueEvent.CLAUDE, "▶ 독립 리뷰 시작 (" + model + ", 새 세션" + ")", prompt);
            CodingAgent.Result r = null;
            try {
                r = agent.run(dir, reviewerSession, false, verifier.cfg().tools(), prompts.systemPrompt(s), prompt,
                        (kind, message, detail) -> events.emit(id, kind, message, detail),
                        CodingAgent.Stage.VERIFY);
            } catch (CodingAgent.UsageLimitException e) {
                notifier.notifyLimit(e);                       // 리뷰어는 생략하고(판정 없음) 봇 검사 결과만으로 리뷰 대기로 올린다
                events.emit(id, IssueEvent.ERROR, "독립 리뷰 생략: " + e.getMessage() + " — 리셋 뒤 검증을 자동으로 다시 돌립니다", String.valueOf(e));
                limitPaused.put(id, "VERIFY");
            } finally {
                reviewerSessions.remove(id);
            }
            if (r == null) {
                reviewText = "(리뷰어 실행 불가 — 사용량 한도 도달, 판정 없음. 리셋 " + usage.limitUntilText() + ")";
            } else {
                events.emit(id, IssueEvent.CLAUDE, String.format("■ 독립 리뷰 완료 · %d턴 · 도구 %d회 · %s · %ds%s", r.turns(), r.toolCalls(),
                        r.tokens(), r.elapsedMs() / 1000, r.error() ? " · 오류" : ""), r.text());
                if (r.error()) {
                    if (abortRequested.contains(id)) {
                        throw new IllegalStateException("검증 중 중단 요청");
                    }
                    reviewText = "(리뷰어 실행 오류 — 판정 없음) " + abbreviate(r.text(), 300);
                } else {
                    reviewText = r.text();
                    verdict = VerifyService.parseVerdict(r.text());
                }
            }
        }

        // 3) 종합
        boolean pass = checksOk && verdict != VerifyService.Verdict.FAIL;
        String report = "## 봇 검사\n" + (checks.isEmpty() ? "(없음)" : VerifyService.report(checks))
                + (verifier.cfg().reviewer() ? "\n\n## 독립 리뷰" + (verdict == VerifyService.Verdict.UNKNOWN ? " (판정 불명)" : "") + "\n" + reviewText : "");
        String head = (pass ? "✅ 검증 통과" : "❌ 검증 실패") + " (" + round + "회차)";
        s.setVerifyResult(head + "\n\n" + report);
        sessions.update(s);
        events.emit(id, pass ? IssueEvent.INFO : IssueEvent.ERROR, head + (checksOk ? "" : " — 봇 검사 실패") + (verdict == VerifyService.Verdict.FAIL ? " — 리뷰어 FAIL" : ""), report);

        if (pass) {
            toReviewWait(s, head + (verdict == VerifyService.Verdict.UNKNOWN && verifier.cfg().reviewer() ? " — 리뷰어 판정을 읽지 못해 봇 검사 결과만 반영" : ""));
            return;
        }
        if (round <= verifier.cfg().maxRetries()) {
            fixAuto(s, report);
            return;
        }
        toReviewWait(s, head + " — 자동 재수정 상한(" + verifier.cfg().maxRetries() + "회) 도달. 실패 내역을 보고 피드백을 주거나 중단해 주세요.");
    }

    /** 리뷰 대기로 전이 + 자기방 보고(변경 요약 · 검증 결과 · diff stat). */
    private void toReviewWait(IssueSession s, String verifyLine) {
        Path dir = dirOf(s);
        String stat = git.diffStat(dir);
        toWait(s, IssueSession.REVIEW_WAIT);
        String verifyBlock = "";
        if (verifyLine != null) {
            String review = section(s.getVerifyResult(), "독립 리뷰");
            String reason = section(review, "근거");
            verifyBlock = "\n\n## 검증\n" + verifyLine
                    + (reason.isBlank() || reason.equals(review.strip()) ? "" : "\n리뷰어 근거: " + abbreviate(reason.strip(), 600))
                    + "\n자세한 검사 결과: " + dashboardUrl(s) + " (검증 탭)";
        }
        notifier.sendSelf(PFX + "수정 완료 " + s.ref() + "\n\n" + s.getChangeSummary()
                + verifyBlock
                + "\n\n## git diff --stat\n" + (stat.isBlank() ? "(변경 없음)" : stat.strip())
                + "\n\n" + askReview());
    }

    private void push(IssueSession s) {
        Path dir = dirOf(s);
        if (!git.hasChanges(dir)) {
            toWait(s, IssueSession.REVIEW_WAIT);
            notifier.sendSelf(PFX + "작업트리에 변경이 없어 푸시할 내용이 없습니다. 피드백을 주시거나 '중단'해 주세요.");
            return;
        }
        transition(s, IssueSession.PUSHING);
        GitLabClient.Project p = projectReg.project(s.getProjectPath());
        String workType = s.getWorkType() == null ? "fix" : s.getWorkType();
        if (s.getWorkId() == null) {
            s.setWorkId(workIdOf(s.getIssueTitle(), s.getIssueIid(), conv().workIdPattern()));
        }
        String branch = git.branchName(workType, s.getWorkId());        // 컨벤션: {유형}/{일감번호}
        // 컨벤션 도입 전에 분석된 세션(base_branch 없음)은 기본 브랜치 기준 작업트리이므로 MR 도 기본 브랜치로(기준 불일치 방지)
        String mrTarget = s.getBaseBranch() == null ? p.defaultBranch() : mrTargetFor(p);
        if (s.getBaseBranch() == null) {
            s.setBaseBranch(p.defaultBranch());
        }
        ensureChangeSummary(s);                       // 중단·한도로 보고가 없으면 diff 로 요약·커밋 메시지를 새로 만든다
        String commitMsg = commitMessage(s);
        String sha;
        try {
            if (git.remoteBranchExists(dir, branch)) {   // 사람이 같은 일감 브랜치를 이미 쓰고 있거나 재실행: 덮지 않고 -ai(-r시각) 로 구분
                String alt = branch + "-ai";
                branch = git.remoteBranchExists(dir, alt)
                        ? alt + "-r" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMddHHmm")) : alt;
            }
            events.emit(s.getId(), IssueEvent.GIT, "브랜치 " + branch + " 생성 · 커밋 · 푸시", commitMsg);
            sha = git.commitAndPush(dir, branch, commitMsg);
        } catch (RuntimeException e) {
            // 푸시 실패(네트워크/권한)는 세션을 죽이지 않는다 — 변경은 작업트리에 그대로 있으니 리뷰 대기로 돌아가 다시 시도할 수 있게
            events.emit(s.getId(), IssueEvent.ERROR, "푸시 실패: " + abbreviate(e.getMessage(), 300), String.valueOf(e));
            toWait(s, IssueSession.REVIEW_WAIT);
            notifier.sendSelf(PFX + "⚠️ 푸시 실패 " + s.ref() + "\n" + abbreviate(e.getMessage(), 500)
                    + "\n변경은 보존되어 있습니다. 「진행」으로 다시 시도하거나 「중단」해 주세요.");
            return;
        }
        s.setBranchName(branch);
        s.setCommitSha(sha);
        sessions.update(s);
        events.emit(s.getId(), IssueEvent.GIT, "푸시 완료 " + sha + " → origin/" + branch);

        String branchUrl = p.webUrl() + "/-/tree/" + branch;
        String summary = section(s.getChangeSummary(), "변경 요약");
        String mrUrl = null;
        StringBuilder extra = new StringBuilder();
        if (props.createMr()) {
            try {
                String title = "Draft: " + firstLine(commitMsg);
                String verify = section(s.getChangeSummary(), "검증 결과");
                String desc = "Closes #" + s.getIssueIid()
                        + "\n일감: " + s.getWorkId() + " · 브랜치 `" + branch + "` (" + s.getBaseBranch() + " 기준) → `" + mrTarget + "`"
                        + ("hotfix".equals(workType) ? "\n**Hotfix**: main 에서 분기한 긴급 수정 — 브랜치 산출물로 즉시 배포 후 develop 에 병합(컨벤션)" : "")
                        + "\n\n## 변경 요약\n" + summary
                        + (verify.isBlank() || verify.equals(s.getChangeSummary().strip()) ? "" : "\n\n## 검증 결과\n" + verify)
                        + "\n\n---\n_" + agentName() + " 이슈 에이전트가 생성한 MR입니다. 리뷰 후 Draft 를 해제하세요._";
                mrUrl = gitlab.createMergeRequest(p.id(), branch, mrTarget, title, desc);
                s.setMrUrl(mrUrl);
                sessions.update(s);
                events.emit(s.getId(), IssueEvent.GITLAB, "Draft MR 생성: " + mrUrl);
            } catch (RuntimeException e) {
                events.emit(s.getId(), IssueEvent.ERROR, "MR 생성 실패: " + abbreviate(e.getMessage(), 200));
                log.warn("MR 생성 실패 {}: {}", s.ref(), e.toString());
                extra.append("\n⚠️ MR 생성 실패: ").append(e.getMessage());
            }
        }
        if (props.commentOnIssue()) {
            try {
                String body = ":robot: 수정 브랜치를 푸시했습니다: `" + branch + "` (" + sha + ")\n\n"
                        + "## 변경 요약\n" + summary
                        + (mrUrl != null && !mrUrl.isBlank() ? "\n\nMR: " + mrUrl : "\n\n브랜치: " + branchUrl)
                        + "\n\n_" + agentName() + " 이슈 에이전트_";
                gitlab.comment(p.id(), s.getIssueIid(), body);
                events.emit(s.getId(), IssueEvent.GITLAB, "이슈 #" + s.getIssueIid() + " 에 코멘트 남김");
            } catch (RuntimeException e) {
                events.emit(s.getId(), IssueEvent.ERROR, "이슈 코멘트 실패: " + abbreviate(e.getMessage(), 200));
                log.warn("이슈 코멘트 실패 {}: {}", s.ref(), e.toString());
                extra.append("\n⚠️ 이슈 코멘트 실패: ").append(e.getMessage());
            }
        }
        transition(s, IssueSession.DONE);
        git.cleanup(s.getProjectPath(), s.getIssueIid());
        notifier.sendSelf(PFX + "완료 " + s.ref() + " — " + s.getIssueTitle()
                + "\n브랜치: " + branch + " (" + sha + ")\n" + branchUrl
                + (mrUrl != null && !mrUrl.isBlank() ? "\nMR: " + mrUrl : "")
                + extra);
        startNextIfIdle();
    }

    private void abort(long id, String reason) {
        abortRequested.remove(id);
        IssueSession s = sessions.find(id);
        if (s == null || !s.isActive()) {
            return;
        }
        git.cleanup(s.getProjectPath(), s.getIssueIid());
        s.setLastError(reason);
        events.emit(s.getId(), IssueEvent.INFO, "중단 (" + reason + ") — 세션 작업트리 제거");
        transition(s, IssueSession.ABORTED);
        notifier.sendSelf(PFX + "중단됨 " + s.ref() + " (" + reason + "). 작업트리를 정리했습니다. 다시 시작: 이슈 " + s.ref());
        startNextIfIdle();
    }

    private void fail(IssueSession s, String stage, Exception e) {
        if (abortRequested.contains(s.getId())) {   // 사용자가 중단을 눌러 Claude 가 죽은 것 → 실패가 아니라 중단
            log.info("이슈 세션 {} {} 중 사용자 중단", s.ref(), stage);
            abort(s.getId(), "사용자 요청");
            return;
        }
        log.error("이슈 세션 {} {} 실패: {}", s.ref(), stage, e.toString(), e);
        s.setLastError(stage + ": " + abbreviate(e.getMessage(), 500));
        events.emit(s.getId(), IssueEvent.ERROR, stage + " 실패: " + abbreviate(e.getMessage(), 300), String.valueOf(e));
        transition(s, IssueSession.FAILED);
        git.cleanup(s.getProjectPath(), s.getIssueIid());
        notifier.sendSelf(PFX + "실패 " + s.ref() + " (" + stage + ")\n" + abbreviate(e.getMessage(), 600)
                + "\n다시 시작: 이슈 " + s.ref());
        alert.alert("이슈 에이전트 " + s.ref() + " " + stage, e);
        startNextIfIdle();
    }

    /** 대기 상태로 전환. 답변은 도착하는 순간 라우터가 {@link #deliverReply} 로 전달한다. */
    private void toWait(IssueSession s, String state) {
        transition(s, state);
        prefetchIfPossible();                         // 사용자 확인을 기다리는 동안 다음 이슈를 미리 분석
    }

    /** 상태 전이 + DB 저장 + STATE 이벤트(모니터 스테퍼/타임라인 원천). */
    void transition(IssueSession s, String state) {
        String from = s.getState();
        if (IssueSession.DESIGN_WAIT.equals(state) || IssueSession.REVIEW_WAIT.equals(state)) {
            waitGenerations.merge(s.getId(), 1L, Long::sum);   // 상태를 저장하기 전에 올린다 — 새 대기 상태를 본 답변은 새 세대를 본다
        }
        s.setState(state);
        sessions.update(s);
        events.emit(s.getId(), IssueEvent.STATE, from + " → " + state, state);
    }

    /** Claude 실행을 감싸 시작/종료/도구 활동을 이벤트로 남긴다. */
    private CodingAgent.Result runClaude(IssueSession s, String label, boolean resume, List<String> tools,
                                               String prompt) {
        return runClaude(s, label, resume, tools, prompt, false);
    }

    private CodingAgent.Result runClaude(IssueSession s, String label, boolean resume, List<String> tools,
                                               String prompt, boolean fixStage) {
        final long id = s.getId();
        SettingService.AgentModels am = settings.getAgentModels();
        String modelLabel = fixStage ? am.devModel() + "/" + am.devEffort() : am.designModel() + "/" + am.designEffort();
        events.emit(id, IssueEvent.CLAUDE, "▶ " + label + " 시작 (" + modelLabel
                + (resume ? ", 세션 이어서" : ", 새 세션") + ")", prompt);
        CodingAgent.Result r;
        try {
            r = agent.run(dirOf(s), s.getClaudeSessionId(), resume, tools,
                    prompts.systemPrompt(s), prompt, (kind, message, detail) -> events.emit(id, kind, message, detail),
                    fixStage);
        } finally {
            refs.guardReferences(id);                      // 타임아웃·실패로 끝나도 참고 저장소는 되돌린다
        }
        events.emit(id, IssueEvent.CLAUDE, String.format("■ %s 완료 · %d턴 · 도구 %d회 · %s · %ds%s", label, r.turns(),
                r.toolCalls(), r.tokens(), r.elapsedMs() / 1000, r.error() ? " · 오류" : ""), r.text());
        return r;
    }

    // ---------------------------------------------------------------- 모니터 조회

    public boolean isBusy() {
        return !busySessions.isEmpty();
    }

    /** 지금 Claude 가 돌고 있는 세션의 실행 정보(모델·노력·단계·시작 시각) — 대시보드 "실행 중인 모델" 표시용. 선분석 세션 포함. */
    public Map<Long, CodingAgent.RunInfo> runningInfo() {
        Map<Long, CodingAgent.RunInfo> out = new LinkedHashMap<>();
        if (!props.enabled()) {
            return out;
        }
        List<IssueSession> all = new ArrayList<>(sessions.findActiveAll());
        IssueSession pre = sessions.findPreparing();
        if (pre != null) {
            all.add(pre);
        }
        for (IssueSession s : all) {
            CodingAgent.RunInfo ri = agent.runInfo(s.getClaudeSessionId());
            if (ri == null) {
                ri = agent.runInfo(reviewerSessions.get(s.getId()));
            }
            if (ri != null) {
                out.put(s.getId(), ri);
            }
        }
        return out;
    }

    /** 워커가 작업 중인 세션 id 들(대시보드가 해당 세션의 답변 버튼을 잠근다). */
    public Set<Long> busySessionIds() {
        return Set.copyOf(busySessions);
    }

    public int concurrency() {
        return concurrency;
    }

    /** 활성 세션 전부(오래된 순). */
    public List<IssueSession> activeSessions() {
        return props.enabled() ? sessions.findActiveAll() : List.of();
    }

    /** 대표 활성 세션: 확인 대기 중인 것이 있으면 그중 첫 건, 없으면 첫 활성 건. */
    public IssueSession activeSession() {
        List<IssueSession> a = activeSessions();
        return a.stream().filter(IssueSession::isWaiting).findFirst().orElse(a.isEmpty() ? null : a.get(0));
    }

    public IssueSession nextSession() {
        if (!props.enabled()) {
            return null;
        }
        IssueSession r = sessions.findReady();
        if (r == null) {
            r = sessions.findPreparing();
        }
        return r != null ? r : sessions.findNextNew();
    }

    public IssueSession session(long id) {
        return sessions.find(id);
    }

    public List<IssueEvent> events(long sessionId, long afterId, int limit) {
        return events.events(sessionId, afterId, limit);
    }

    public IssueEvent event(long id) {
        return events.event(id);
    }

    /**
     * 세션의 변경 내용. 수정 중/리뷰 대기면 작업트리 diff, 푸시 후면 커밋 diff. 그 외엔 null.
     * 작업트리는 활성 세션 1건만 쓰므로 다른 세션이 진행 중이면 과거 세션의 작업트리 diff 는 보여주지 않는다.
     */
    public String diffFor(long id, int maxChars) {
        IssueSession s = sessions.find(id);
        if (s == null) {
            return null;
        }
        try {
            if (s.getCommitSha() != null && !s.getCommitSha().isBlank()) {
                Path base = git.repoDir(s.getProjectPath());  // 커밋 객체는 기준 clone 과 공유되므로 worktree 제거 후에도 조회 가능
                return Files.isDirectory(base.resolve(".git")) ? git.show(base, s.getCommitSha(), maxChars) : null;
            }
            Path dir = dirOf(s);
            if (!Files.exists(dir.resolve(".git"))) {
                return null;
            }
            if (IssueSession.FIXING.equals(s.getState()) || IssueSession.REVIEW_WAIT.equals(s.getState())
                    || IssueSession.PUSHING.equals(s.getState())) {
                return git.diff(dir, maxChars);
            }
        } catch (RuntimeException e) {
            return "(diff 조회 실패) " + e.getMessage();
        }
        return null;
    }

    // ---------------------------------------------------------------- 프롬프트

    // ---------------------------------------------------------------- 참고 저장소(다른 프로젝트 읽기 전용)

    /**
     * 수정 단계가 실행 상한(전체/무응답)에 걸렸을 때: 작업트리에 변경이 있으면 버리지 않고 리뷰 대기로 넘겨
     * 사용자가 diff 를 보고 「피드백: 이어서 진행」으로 같은 Claude 세션을 계속할 수 있게 한다. 변경이 없으면 false(→ 실패 처리).
     */
    private boolean timeoutToReview(IssueSession s, CodingAgent.AgentTimeoutException e) {
        return interruptedToReview(s, e.getMessage());
    }

    /** 수정 세션이 비정상 종료(상한·한도·오류)했을 때 작업트리 변경이 있으면 보존한 채 리뷰 대기로. 변경이 없으면 false(→ 실패 처리). */
    private boolean interruptedToReview(IssueSession s, String reason) {
        if (abortRequested.contains(s.getId())) {
            return false;
        }
        String stat;
        try {
            stat = git.diffStat(dirOf(s));
        } catch (RuntimeException ex) {
            return false;
        }
        if (stat.isBlank()) {
            return false;
        }
        events.emit(s.getId(), IssueEvent.ERROR, "수정 단계 중단: " + reason + " — 작업트리 변경을 보존하고 리뷰 대기로 넘깁니다", stat);
        // 이전 라운드의 진짜 요약(## 변경 요약/## 커밋 메시지)이 있으면 지우지 않는다 — 안내문이 커밋 제목·MR 본문으로 올라간 사고(11-13 MR !2)
        String note = "## 중단 안내\n⏱ " + reason + "\n세션이 중간에 끊겨 이번 라운드의 보고가 없습니다. 아래 diff --stat 과 대시보드 diff 로 확인하세요.\n"
                + "이어서 하려면 「피드백: 이어서 진행」(같은 세션을 재개해 남은 작업을 계속), 이대로 올리려면 「진행」(푸시 전에 변경 요약을 다시 생성합니다).";
        String prev = s.getChangeSummary();
        s.setChangeSummary(hasRealSummary(prev) ? prev.strip() + "\n\n" + note : note);
        sessions.update(s);
        toReviewWait(s, null);
        return true;
    }
    // ---------------------------------------------------------------- 대상 프로젝트(위임 — 구현은 IssueProjects)

    public List<Map<String, Object>> projectList() {
        return projectReg.projectList();
    }

    public String addProject(String path) {
        return projectReg.addProject(path);
    }

    public String removeProject(String path) {
        return projectReg.removeProject(path);
    }

    // ---------------------------------------------------------------- 유틸

    /**
     * 푸시 직전: 변경 요약에 에이전트의 진짜 보고가 없으면(중단·한도로 끊김) 읽기 전용 Claude 세션이 작업트리 diff 를 보고
     * "## 변경 요약 / ## 커밋 메시지" 를 새로 쓴다. 실패하면 기존 내용 그대로(커밋 메시지는 폴백 제목).
     */
    private void ensureChangeSummary(IssueSession s) {
        if (hasRealSummary(s.getChangeSummary())) {
            return;
        }
        final long id = s.getId();
        Path dir = dirOf(s);
        String stat = git.diffStat(dir);
        String diff = git.diff(dir, 60_000);
        String prompt = "작업트리의 변경을 보고 보고서를 작성하세요(코드를 수정하지 마세요). 이 변경은 이슈 '" + s.getIssueTitle() + "' 에 대한 것입니다.\n\n"
                + "--- git diff --stat ---\n" + stat + "\n\n--- git diff (앞부분, 생략될 수 있음) ---\n" + diff
                + "\n\n다음 형식으로만 출력하세요(전체 1500자 이내):\n## 변경 요약\n(모듈/파일 묶음별 한 줄씩, 무엇을 왜 바꿨는지)\n"
                + "## 커밋 메시지\n(첫 줄은 `feat|fix|docs|style|refactor|test|chore: 제목`(50자 이내, 이 작업은 " + commitTypeFor(s.getWorkType())
                + " 권장), 빈 줄, 본문 2~5줄. 마지막 줄에 'Resolves #" + s.getIssueIid() + "')";
        events.emit(id, IssueEvent.INFO, "변경 요약이 없어 diff 로 다시 생성합니다(읽기 전용 세션)");
        try {
            CodingAgent.Result r = agent.run(dir, UUID.randomUUID().toString(), false, props.agent().analyzeTools(), prompts.systemPrompt(s), prompt,
                    (kind, message, detail) -> events.emit(id, kind, message, detail), CodingAgent.Stage.ANALYZE);
            events.emit(id, IssueEvent.CLAUDE, String.format("■ 변경 요약 생성 · %d턴 · %s · %ds%s", r.turns(), r.tokens(), r.elapsedMs() / 1000,
                    r.error() ? " · 오류" : ""), r.text());
            if (!r.error() && hasRealSummary(r.text())) {
                String prev = s.getChangeSummary();
                String note = prev == null ? "" : section(prev, "중단 안내");
                s.setChangeSummary(r.text().strip() + (note.isBlank() || note.equals(prev.strip()) ? "" : "\n\n## 중단 안내\n" + note));
                sessions.update(s);
            }
        } catch (RuntimeException e) {
            events.emit(id, IssueEvent.ERROR, "변경 요약 생성 실패(기존 내용으로 진행): " + abbreviate(e.getMessage(), 200));
        }
    }

    private String commitMessage(IssueSession s) {
        String summary = s.getChangeSummary();
        // "## 커밋 메시지" 절이 없으면 section() 이 본문 전체를 돌려준다 → 그대로 쓰면 보고서 전체가 커밋 제목이 된다(11-13 실측) → 폴백
        boolean hasCommitSection = summary != null && Pattern.compile("(?m)^##\\s*커밋 메시지\\s*$").matcher(summary).find();
        String sec = hasCommitSection ? section(summary, "커밋 메시지") : "";
        String msg = sec.replaceAll("(?m)^```.*$", "").strip();
        if (msg.isBlank() || msg.length() < 8) {
            msg = commitTypeFor(s.getWorkType()) + ": " + abbreviate(s.getIssueTitle(), 60) + " (#" + s.getIssueIid() + ")\n\nResolves #" + s.getIssueIid();
        } else if (!closesIssue(msg, s.getIssueIid())) {
            // 이슈는 GitLab 정책으로 닫는다: 이 커밋이 기본 브랜치(main)에 도달하면 닫는 키워드가 이슈를 닫는다(develop MR 설명의 Closes 는 main 까지 따라가지 않음)
            msg = msg + "\n\nResolves #" + s.getIssueIid();
        }
        // 첫 줄을 컨벤션 타입 접두어로 정규화(모델이 빠뜨렸을 때)
        int nl = msg.indexOf('\n');
        String head = nl < 0 ? msg : msg.substring(0, nl);
        String rest = nl < 0 ? "" : msg.substring(nl);
        return conventionalTitle(head, s.getWorkType()) + rest;
    }

    /** MR 본문·이슈 코멘트 서명에 쓰는 에이전트 이름 — 커밋 author(`gitlab.git-user-name`)와 같게. */
    private String agentName() {
        String n = props.gitUserName();
        return n == null || n.isBlank() ? "dev-agent" : n.strip();
    }

    private IssueSession newSession(GitLabClient.Project p, GitLabClient.Issue is) {
        IssueSession s = new IssueSession();
        s.setProjectPath(p.path());
        s.setProjectId(p.id());
        s.setIssueIid(is.iid());
        s.setIssueTitle(is.title());
        s.setIssueUrl(is.webUrl());
        s.setDefaultBranch(p.defaultBranch());
        s.setState(IssueSession.NEW);
        return s;
    }

    private List<String> safeNotes(long projectId, long iid) {
        try {
            return gitlab.issueNotes(projectId, iid, 30);
        } catch (RuntimeException e) {
            log.debug("이슈 노트 조회 실패(무시): {}", e.toString());
            return new ArrayList<>();
        }
    }

    /** 세션에 속하지 않는 작업(스캔, 다음 건 시작). */
    private void submit(Runnable task) {
        worker.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("이슈 에이전트 작업 오류: {}", e.toString(), e);
            }
        });
    }

    /**
     * 세션 작업: 같은 세션의 작업은 체인으로 직렬화(답변 두 번 → 순서대로), 다른 세션끼리는 워커 풀에서 병렬.
     * 실행 중엔 busySessions 에 올려 답변 수신을 막고 대시보드에 "작업 중" 으로 보인다.
     */
    private void submit(long sessionId, Runnable task) {
        sessionChains.compute(sessionId, (id, prev) -> {
            java.util.concurrent.CompletableFuture<Void> base = prev == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : prev;
            return base.handle((v, e) -> null).thenRunAsync(() -> {
                busySessions.add(sessionId);
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("이슈 세션 {} 작업 오류: {}", sessionId, e.toString(), e);
                } finally {
                    busySessions.remove(sessionId);
                }
            }, worker);
        });
    }

    private boolean isBusy(long sessionId) {
        return busySessions.contains(sessionId);
    }

    private int activeCount() {
        return sessions.findActiveAll().size();
    }

}

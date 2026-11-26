package io.github.lib0823.devagent.research;

import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.config.ResearchProperties;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.issue.GitHubClient;
import io.github.lib0823.devagent.issue.GitRunner;
import io.github.lib0823.devagent.issue.IssueEvent;
import io.github.lib0823.devagent.issue.IssueEventBus;
import io.github.lib0823.devagent.issue.VerifyService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.notify.NotificationService;
import io.github.lib0823.devagent.llm.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질의(리서치) 워크플로우: "Batch job 목록 정리해줘" 같은 질문을 받아 대상 프로젝트의 읽기 전용 worktree 에서
 * Claude 세션이 소스를 훑고 마크다운 문서로 답한다. 코드 수정·빌드 없음.
 * <ul>
 *   <li>저장소: {@link GitRunner#prepareReference} 의 detached worktree(기본 브랜치)를 쓰므로 이슈 세션과 간섭 없음. 여러 프로젝트 동시 참조 가능.</li>
 *   <li>세션: 루트 질문은 새 세션, 후속 질문은 루트의 세션을 {@code --resume}(이미 읽은 코드 컨텍스트 재사용).</li>
 *   <li>실행: 단일 워커(이슈 워커 풀과 분리). 사용량 한도 중엔 대기열에 두고 리셋 뒤 자동 시작.</li>
 *   <li>이벤트: 이슈 이벤트 버스를 그대로 쓰되 세션 id 공간을 {@link #EVENT_BASE} 만큼 띄워 충돌을 피한다(대시보드 SSE 공유).</li>
 * </ul>
 */
@Service
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);
    /** issue_event.session_id 공간 분리: 질의 id + 10억. */
    public static final long EVENT_BASE = 1_000_000_000L;
    public static final Set<String> MODELS = Set.of("fable", "opus", "sonnet", "haiku");
    public static final Set<String> EFFORTS = Set.of("low", "medium", "high");
    private static final String PFX = "[질의] ";
    private static final int MAX_QUESTION = 4000;

    private final ResearchProperties props;
    private final IssueProperties issueProps;
    private final ResearchJobMapper jobs;
    private final CodingAgent agent;
    private final GitRunner git;
    private final GitHubClient github;
    private final IssueEventBus events;
    private final SettingService settings;
    private final UsageService usage;
    private final MessengerService client;
    private final NotificationService notifications;
    private final ResearchAttachments attachments;
    private final int adminPort;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "research-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Long> runningId = new AtomicReference<>();
    private final Set<Long> abortRequested = ConcurrentHashMap.newKeySet();
    private final Map<String, GitHubClient.Project> projectCache = new ConcurrentHashMap<>();
    private volatile boolean limitLogged;

    public ResearchService(ResearchProperties props, IssueProperties issueProps, ResearchJobMapper jobs, CodingAgent agent,
                           GitRunner git, GitHubClient github, IssueEventBus events, SettingService settings, UsageService usage,
                           MessengerService client, NotificationService notifications, ResearchAttachments attachments,
                           @Value("${server.port:8777}") int adminPort) {
        this.notifications = notifications;
        this.attachments = attachments;
        this.props = props;
        this.issueProps = issueProps;
        this.jobs = jobs;
        this.agent = agent;
        this.git = git;
        this.github = github;
        this.events = events;
        this.settings = settings;
        this.usage = usage;
        this.client = client;
        this.adminPort = adminPort;
    }

    @PostConstruct
    void init() {
        if (!enabled()) {
            log.info("질의 워크플로우 비활성(research.enabled=false 또는 issue.enabled=false)");
            return;
        }
        jobs.ensureTable();
        jobs.ensureIndex();
        jobs.ensureColumns();
        // 재기동 시 RUNNING 이던 질의는 중간 상태를 알 수 없으므로 실패 처리(후속 질문으로 같은 세션을 이어갈 수는 있다)
        for (ResearchJob j : jobs.findByState(ResearchJob.RUNNING)) {
            j.setState(ResearchJob.FAILED);
            j.setLastError("봇 재기동으로 중단됨");
            j.setFinishedAt(OffsetDateTime.now());
            jobs.update(j);
            events.emit(eventId(j.getId()), IssueEvent.ERROR, "봇 재기동으로 중단됨");
        }
        kick();
    }

    public boolean enabled() {
        return props.enabled() && issueProps.enabled();
    }

    /** 질의를 실행 중인가(재시작 가능 여부 판단용). */
    public boolean busy() {
        return runningId.get() != null;
    }

    public static long eventId(long jobId) {
        return EVENT_BASE + jobId;
    }

    // ---------------------------------------------------------------- 접수

    /**
     * 질문 접수. {@code parentId} 가 있으면 그 스레드의 후속 질문(루트 세션 --resume). 프로젝트/모델/노력은 후속이면 루트 값을 물려받는다.
     * 검증 실패는 IllegalArgumentException(메시지를 그대로 사용자에게).
     */
    public ResearchJob submit(String question, List<String> projects, String model, String effort, Long parentId, boolean notify, String requester) {
        return submit(question, projects, model, effort, parentId, notify, requester, null);
    }

    /** @param verify 2차 검증 여부. null 이면 새 질의는 설정 기본값(research.verify), 후속 질문은 첫 질문의 값을 따른다. */
    public ResearchJob submit(String question, List<String> projects, String model, String effort, Long parentId, boolean notify, String requester,
                              Boolean verify) {
        return submit(question, projects, model, effort, parentId, notify, requester, verify, null);
    }

    /** @param kind 결과 형식({@link ResearchJob#KIND_ISSUE} 면 이슈 초안). 항목마다 따로이며 후속 질문이 물려받지 않는다. */
    public ResearchJob submit(String question, List<String> projects, String model, String effort, Long parentId, boolean notify, String requester,
                              Boolean verify, String kind) {
        return submit(question, projects, model, effort, parentId, notify, requester, verify, kind, null);
    }

    /** @param staged 화면이 먼저 올려 둔 첨부 이미지의 임시 이름({@link ResearchAttachments#stage}). 항목마다 따로이며 후속 질문이 물려받지 않는다. */
    public ResearchJob submit(String question, List<String> projects, String model, String effort, Long parentId, boolean notify, String requester,
                              Boolean verify, String kind, List<String> staged) {
        if (!enabled()) {
            throw new IllegalArgumentException("질의 워크플로우가 비활성 상태입니다(research.enabled / issue.enabled).");
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("질문을 입력하세요.");
        }
        if (q.length() > MAX_QUESTION) {
            throw new IllegalArgumentException("질문이 너무 깁니다(" + MAX_QUESTION + "자 이내).");
        }
        List<String> images = attachments.check(staged);
        ResearchJob j = new ResearchJob();
        j.setQuestion(q);
        j.setState(ResearchJob.NEW);
        j.setNotify(notify);
        j.setRequester(requester);
        j.setKind(kind);
        if (parentId != null) {
            ResearchJob parent = jobs.find(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("이어갈 질의를 찾을 수 없습니다: #" + parentId);
            }
            ResearchJob root = parent.getRootId() == parent.getId() ? parent : jobs.find(parent.getRootId());
            List<ResearchJob> thread = jobs.findThread(root.getId());
            ResearchJob last = thread.get(thread.size() - 1);
            if (last.isActive()) {
                throw new IllegalArgumentException("이 스레드의 이전 질문이 아직 진행 중입니다. 끝난 뒤 이어서 질문하세요.");
            }
            if (root.getClaudeSessionId() == null || root.getClaudeSessionId().isBlank()) {
                throw new IllegalArgumentException("이 스레드는 이어갈 세션이 없습니다(첫 질문이 실행되지 않았음). 새 질의로 올려 주세요.");
            }
            j.setParentId(last.getId());
            j.setRootId(root.getId());
            j.setProjects(root.getProjects());
            j.setModel(root.getModel());
            j.setEffort(root.getEffort());
            j.setClaudeSessionId(root.getClaudeSessionId());
            j.setVerify(verify != null ? verify : root.isVerify());
        } else {
            List<String> ps = normalizeProjects(projects);
            if (ps.isEmpty()) {
                throw new IllegalArgumentException("대상 프로젝트를 하나 이상 고르세요.");
            }
            j.setProjects(String.join(",", ps));
            j.setModel(normalizeModel(model));
            j.setEffort(normalizeEffort(effort));
            j.setRootId(0);
            j.setVerify(verify != null ? verify : props.verify());
        }
        jobs.insert(j);
        if (parentId == null) {
            jobs.fixRoot(j.getId());
            j.setRootId(j.getId());
        }
        if (!images.isEmpty()) {
            j.setAttachments(String.join(",", attachments.claim(j.getId(), images)));
            jobs.setAttachments(j.getId(), j.getAttachments());
        }
        events.emit(eventId(j.getId()), IssueEvent.STATE, (parentId == null ? "리서치 접수" : "후속 질문 접수") + " → NEW", ResearchJob.NEW);
        if (!images.isEmpty()) {
            events.emit(eventId(j.getId()), IssueEvent.INFO, "첨부 이미지 " + images.size() + "개");
        }
        events.emit(eventId(j.getId()), IssueEvent.INFO, "질문: " + abbreviate(q, 300), q);
        log.info("질의 접수 #{} root={} projects={} model={}/{} by={}", j.getId(), j.getRootId(), j.getProjects(), j.getModel(), j.getEffort(), requester);
        kick();
        return j;
    }

    /** "group/repo@branch" 항목을 [경로, 브랜치(없으면 null)] 로. 브랜치 이름에 '/' 가 있어도 된다(첫 '@' 기준). */
    static String[] splitEntry(String entry) {
        String e = entry == null ? "" : entry.strip();
        int at = e.indexOf('@');
        if (at < 0) {
            return new String[]{e, null};
        }
        String branch = e.substring(at + 1).strip();
        return new String[]{e.substring(0, at).strip(), branch.isEmpty() ? null : branch};
    }

    /** 브랜치 선택용: 프로젝트의 기본 브랜치와 브랜치 목록(최근 갱신 순). 대상 프로젝트만 조회한다. */
    public Map<String, Object> branches(String project, String search) {
        List<String> ps = normalizeProjects(List.of(splitEntry(project)[0]), false);
        if (ps.isEmpty()) {
            throw new IllegalArgumentException("프로젝트를 지정하세요.");
        }
        GitHubClient.Project p = projectCache.computeIfAbsent(ps.get(0), github::project);
        List<String> names = new ArrayList<>(github.branches(p.path(), search));
        if ((search == null || search.isBlank()) && names.remove(p.defaultBranch())) {
            names.add(0, p.defaultBranch());              // 기본 브랜치를 맨 앞에
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", p.path());
        out.put("defaultBranch", p.defaultBranch());
        out.put("branches", names);
        return out;
    }

    List<String> normalizeProjects(List<String> wanted) {
        return normalizeProjects(wanted, true);
    }

    /**
     * 대상 프로젝트 정규화: 관리 페이지의 대상 프로젝트 중에서만, 경로 전체 또는 마지막 세그먼트(batch)로 지정 가능.
     * 항목 뒤에 {@code @branch} 를 붙이면 그 브랜치를 조사한다(없으면 기본 브랜치). 반환 항목은 "경로" 또는 "경로@브랜치".
     *
     * @param checkBranch 브랜치가 원격에 있는지 GitHub 에 확인할지(접수 때 true)
     */
    List<String> normalizeProjects(List<String> wanted, boolean checkBranch) {
        List<String> allowed = settings.getIssueProjects();
        List<String> out = new ArrayList<>();
        if (wanted == null) {
            return out;
        }
        for (String w : wanted) {
            String[] parts = splitEntry(w);
            String key = parts[0];
            String branch = parts[1];
            if (key.isEmpty()) {
                continue;
            }
            String hit = null;
            for (String a : allowed) {
                if (a.equalsIgnoreCase(key)) {
                    hit = a;
                    break;
                }
            }
            if (hit == null) {
                List<String> tails = allowed.stream().filter(a -> a.substring(a.lastIndexOf('/') + 1).equalsIgnoreCase(key)).toList();
                if (tails.size() == 1) {
                    hit = tails.get(0);
                } else if (tails.size() > 1) {
                    throw new IllegalArgumentException("'" + key + "' 에 해당하는 프로젝트가 여러 개입니다: " + tails + " — 전체 경로로 지정하세요.");
                }
            }
            if (hit == null) {
                throw new IllegalArgumentException("'" + key + "' 는 대상 프로젝트가 아닙니다. 관리 페이지 '대상 프로젝트' 에 먼저 등록하세요: " + allowed);
            }
            if (branch != null && checkBranch) {
                GitHubClient.Project p = projectCache.computeIfAbsent(hit, github::project);
                if (branch.equals(p.defaultBranch())) {
                    branch = null;                        // 기본 브랜치는 표기를 생략해 하나로 맞춘다
                } else if (!github.branchExists(p.path(), branch)) {
                    throw new IllegalArgumentException("'" + hit + "' 에 '" + branch + "' 브랜치가 없습니다.");
                }
            }
            // 같은 프로젝트의 다른 브랜치를 함께 넣는 것은 허용한다(브랜치 간 비교 질의). 완전히 같은 항목만 한 번으로.
            String entry = branch == null ? hit : hit + "@" + branch;
            if (!out.contains(entry)) {
                out.add(entry);
            }
        }
        return out;
    }

    String normalizeModel(String model) {
        String m = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return nz(props.model(), settings.getAgentModels().designModel());
        }
        if (!MODELS.contains(m)) {
            throw new IllegalArgumentException("모델은 " + MODELS + " 중 하나여야 합니다: " + model);
        }
        return m;
    }

    String normalizeEffort(String effort) {
        String e = effort == null ? "" : effort.strip().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) {
            return nz(props.effort(), settings.getAgentModels().designEffort());
        }
        if (!EFFORTS.contains(e)) {
            throw new IllegalArgumentException("노력 단계는 " + EFFORTS + " 중 하나여야 합니다: " + effort);
        }
        return e;
    }

    // ---------------------------------------------------------------- 실행

    private void kick() {
        worker.submit(this::runNext);
    }

    /** 한도 대기 중인 큐를 주기적으로 다시 본다(리셋 뒤 자동 시작). */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    void tick() {
        if (enabled()) {
            kick();
        }
    }

    /**
     * 첨부 이미지 정리: 스레드가 끝나고(진행 중인 항목 없음, 마지막 항목 종료 기준) {@code research.attachment-keep-days} 일이 지난 질의의
     * 이미지 파일을 지운다. 후속 질문을 하면 기준 시각이 그만큼 밀린다. 질문·결과 문서는 그대로 두고 파일과 첨부 표시만 없앤다.
     */
    @Scheduled(fixedDelayString = "21600000", initialDelayString = "180000")
    void sweepAttachments() {
        int days = props.attachmentKeepDays();
        if (!enabled() || days <= 0) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        int removed = 0;
        for (long id : attachments.jobIds()) {
            try {
                ResearchJob j = jobs.find(id);
                boolean expired;
                if (j == null) {
                    expired = attachments.folderTime(id) < cutoff.toInstant().toEpochMilli();   // 기록이 없는 폴더: 폴더 나이로
                } else {
                    OffsetDateTime doneAt = jobs.threadDoneAt(j.getRootId());
                    expired = doneAt != null && doneAt.isBefore(cutoff);
                }
                if (expired && attachments.delete(id)) {
                    removed++;
                    if (j != null) {
                        jobs.setAttachments(id, null);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("질의 #{} 첨부 정리 실패(다음 주기에 다시): {}", id, e.toString());
            }
        }
        if (removed > 0) {
            log.info("리서치 첨부 이미지 정리: {}건(스레드 종료 후 {}일 경과)", removed, days);
        }
    }

    private void runNext() {
        if (runningId.get() != null) {
            return;
        }
        ResearchJob next = jobs.findNextNew();
        if (next == null) {
            return;
        }
        if (usage.isLimited()) {
            if (!limitLogged) {
                limitLogged = true;
                events.emit(eventId(next.getId()), IssueEvent.INFO, "Claude 사용량 한도 — 리셋(" + usage.limitUntilText() + ") 뒤 자동 시작");
            }
            return;
        }
        limitLogged = false;
        if (!runningId.compareAndSet(null, next.getId())) {
            return;
        }
        try {
            run(next);
        } finally {
            runningId.set(null);
            abortRequested.remove(next.getId());
            worker.submit(this::runNext);          // 큐에 남은 질문 이어서
        }
    }

    private void run(ResearchJob j) {
        final long eid = eventId(j.getId());
        if (abortRequested.contains(j.getId())) {
            finish(j, ResearchJob.ABORTED, null, "사용자 중단(시작 전)");
            return;
        }
        j.setState(ResearchJob.RUNNING);
        j.setStartedAt(OffsetDateTime.now());
        jobs.update(j);
        events.emit(eid, IssueEvent.STATE, "NEW → RUNNING", ResearchJob.RUNNING);
        boolean resume = j.getParentId() != null;
        String sid = resume ? j.getClaudeSessionId() : UUID.randomUUID().toString();
        j.setClaudeSessionId(sid);
        jobs.update(j);
        List<Ref> refs = new ArrayList<>();
        try {
            for (String entry : j.projectList()) {
                String[] pe = splitEntry(entry);          // 경로 + (선택) 브랜치. 브랜치가 없으면 기본 브랜치
                GitHubClient.Project p = projectCache.computeIfAbsent(pe[0], github::project);
                String branch = pe[1] != null ? pe[1] : p.defaultBranch();
                Path dir = git.prepareReference(p.path(), p.httpUrl(), branch);
                refs.add(new Ref(p.path(), branch, dir));
                events.emit(eid, IssueEvent.GIT, "저장소 준비(읽기 전용): " + p.path() + " @" + branch + " → " + dir);
            }
            if (abortRequested.contains(j.getId())) {
                finish(j, ResearchJob.ABORTED, null, "사용자 중단");
                return;
            }
            Path cwd = refs.get(0).dir();
            String sys = systemPrompt(refs);
            String asked = j.getQuestion() + attachments.promptNote(j);   // 첨부 이미지가 있으면 경로 안내를 덧붙인다
            String prompt = j.issueDraft() ? ResearchPrompts.issueDraftPrompt(asked, resume)
                    : resume ? followupPrompt(asked) : firstPrompt(asked);
            CodingAgent.Overrides ov = new CodingAgent.Overrides(j.getModel(), j.getEffort(), props.timeoutOr(), props.maxTurnsOr(), "research");
            events.emit(eid, IssueEvent.CLAUDE, "▶ 질의 " + (resume ? "이어서 " : "") + "시작 (" + j.getModel() + "/" + j.getEffort() + ", 상한 "
                    + props.timeoutOr() / 60_000 + "분/" + props.maxTurnsOr() + "턴)", prompt);
            CodingAgent.Result r = agent.run(cwd, sid, resume, issueProps.agent().analyzeTools(), sys, prompt,
                    (kind, message, detail) -> events.emit(eid, kind, message, detail), CodingAgent.Stage.ANALYZE, ov);
            guard(refs, eid);
            if (abortRequested.contains(j.getId())) {
                finish(j, ResearchJob.ABORTED, null, "사용자 중단");
                return;
            }
            j.setTurns(r.turns());
            j.setInputTokens(r.inputTotal());
            j.setOutputTokens(r.outputTokens());
            j.setElapsedMs(r.elapsedMs());
            events.emit(eid, IssueEvent.CLAUDE, String.format("■ 질의 완료 · %d턴 · %s · %ds%s", r.turns(), r.tokens(), r.elapsedMs() / 1000,
                    r.error() ? " · 오류" : ""), r.text());
            if (r.error()) {
                finish(j, ResearchJob.FAILED, null, "Claude 오류: " + abbreviate(r.text(), 400));
                return;
            }
            String text = r.text().strip();
            if (j.isVerify()) {
                text = verifyAndRevise(j, refs, cwd, sid, sys, ov, text);
                if (abortRequested.contains(j.getId())) {
                    finish(j, ResearchJob.ABORTED, null, "사용자 중단");
                    return;
                }
            }
            if (text.length() > props.maxResultCharsOr()) {
                text = text.substring(0, props.maxResultCharsOr()) + "\n\n…(결과가 길어 " + props.maxResultCharsOr() + "자에서 잘랐습니다)";
            }
            finish(j, ResearchJob.DONE, text, null);
        } catch (CodingAgent.UsageLimitException e) {
            guard(refs, eid);
            // 한도: 질문을 큐에 되돌려 리셋 뒤 자동 재시작(세션은 새로 시작해도 무방)
            j.setState(ResearchJob.NEW);
            j.setLastError(e.getMessage());
            j.setStartedAt(null);
            jobs.update(j);
            events.emit(eid, IssueEvent.STATE, "RUNNING → NEW (사용량 한도, 리셋 " + usage.limitUntilText() + " 뒤 자동 재시작)", ResearchJob.NEW);
            log.warn("질의 #{} 사용량 한도로 보류: {}", j.getId(), e.getMessage());
        } catch (RuntimeException e) {
            guard(refs, eid);
            if (abortRequested.contains(j.getId())) {
                finish(j, ResearchJob.ABORTED, null, "사용자 중단");
                return;
            }
            log.warn("질의 #{} 실패: {}", j.getId(), e.toString());
            finish(j, ResearchJob.FAILED, null, abbreviate(e.getMessage(), 500));
        }
    }

    /**
     * 2차 검증(피드백 루프): 독립 검증자(새 세션, '테스트' 모델, 읽기 전용)가 문서를 소스와 대조해 PASS/FAIL 을 낸다.
     * FAIL 이면 지적을 작성 세션에 돌려보내 1회 수정한다(작성자는 지적을 다시 확인해 맞는 것만 반영). 검증 자체가 실패해도 원 문서는 살린다.
     * 반환: 최종 문서. 검증 결과는 {@code verifyState}(PASS/FIXED/FAIL/SKIPPED)·{@code verifyMd} 에 남는다.
     */
    private String verifyAndRevise(ResearchJob j, List<Ref> refs, Path cwd, String sid, String sys, CodingAgent.Overrides ov, String document) {
        final long eid = eventId(j.getId());
        CodingAgent.Listener listener = (kind, message, detail) -> events.emit(eid, kind, message, detail);
        SettingService.AgentModels m = settings.getAgentModels();
        CodingAgent.Overrides vov = new CodingAgent.Overrides(m.verifyModel(), m.verifyEffort(), props.timeoutOr(), props.maxTurnsOr(), "research");
        CodingAgent.Result v;
        try {
            String vprompt = ResearchPrompts.verifierPrompt(j.getQuestion() + attachments.promptNote(j), document);
            events.emit(eid, IssueEvent.CLAUDE, "▶ 2차 검증 시작 (" + m.verifyModel() + "/" + m.verifyEffort() + ", 독립 세션)", vprompt);
            v = agent.run(cwd, UUID.randomUUID().toString(), false, issueProps.agent().analyzeTools(),
                    ResearchPrompts.verifierSystem() + refsNote(refs), vprompt, listener, CodingAgent.Stage.ANALYZE, vov);
            guard(refs, eid);
            addCost(j, v);
        } catch (RuntimeException e) {
            guard(refs, eid);
            j.setVerifyState("SKIPPED");
            j.setVerifyMd("검증을 실행하지 못했습니다: " + abbreviate(e.getMessage(), 300));
            events.emit(eid, IssueEvent.ERROR, "2차 검증 실행 실패(문서는 그대로 제공): " + abbreviate(e.getMessage(), 200));
            return document;
        }
        VerifyService.Verdict verdict = v.error() ? VerifyService.Verdict.UNKNOWN : VerifyService.parseVerdict(v.text());
        events.emit(eid, IssueEvent.CLAUDE, String.format("■ 2차 검증 완료 · %s · %d턴 · %s · %ds", verdict, v.turns(), v.tokens(), v.elapsedMs() / 1000), v.text());
        j.setVerifyMd(v.text());
        if (verdict == VerifyService.Verdict.PASS) {
            j.setVerifyState("PASS");
            return document;
        }
        if (verdict == VerifyService.Verdict.UNKNOWN) {
            j.setVerifyState("SKIPPED");
            return document;
        }
        String problems = section(v.text(), "문제");
        if (problems.isBlank()) {
            problems = v.text();
        }
        try {
            String rprompt = j.issueDraft() ? ResearchPrompts.reviseIssuePrompt(problems) : ResearchPrompts.revisePrompt(problems);
            events.emit(eid, IssueEvent.CLAUDE, "▶ 검증 지적 반영 시작 (" + j.getModel() + "/" + j.getEffort() + ", 작성 세션 이어서)", rprompt);
            CodingAgent.Result r2 = agent.run(cwd, sid, true, issueProps.agent().analyzeTools(), sys, rprompt, listener,
                    CodingAgent.Stage.ANALYZE, ov);
            guard(refs, eid);
            addCost(j, r2);
            events.emit(eid, IssueEvent.CLAUDE, String.format("■ 검증 지적 반영 완료 · %d턴 · %s · %ds%s", r2.turns(), r2.tokens(), r2.elapsedMs() / 1000,
                    r2.error() ? " · 오류" : ""), r2.text());
            if (!r2.error() && !r2.text().isBlank()) {
                j.setVerifyState("FIXED");
                return r2.text().strip();
            }
        } catch (RuntimeException e) {
            guard(refs, eid);
            events.emit(eid, IssueEvent.ERROR, "검증 지적 반영 실패(원 문서 제공): " + abbreviate(e.getMessage(), 200));
        }
        j.setVerifyState("FAIL");
        return document;
    }

    private static void addCost(ResearchJob j, CodingAgent.Result r) {
        j.setTurns(j.getTurns() + r.turns());
        j.setInputTokens(j.getInputTokens() + r.inputTotal());
        j.setOutputTokens(j.getOutputTokens() + r.outputTokens());
        j.setElapsedMs(j.getElapsedMs() + r.elapsedMs());
    }

    private static String refsNote(List<Ref> refs) {
        StringBuilder sb = new StringBuilder("\n대상 저장소(읽기 전용, 절대 경로로 Read/Grep/Glob):");
        for (Ref r : refs) {
            sb.append("\n- ").append(r.path()).append(" (").append(r.branch()).append("): ")
                    .append(r.dir().toAbsolutePath().toString().replace('\\', '/'));
        }
        return sb.toString();
    }

    private void finish(ResearchJob j, String state, String result, String error) {
        j.setState(state);
        j.setResultMd(result);
        j.setLastError(error);
        j.setFinishedAt(OffsetDateTime.now());
        jobs.update(j);
        long eid = eventId(j.getId());
        if (error != null) {
            events.emit(eid, IssueEvent.ERROR, error);
        }
        events.emit(eid, IssueEvent.STATE, "RUNNING → " + state, state);
        boolean done = ResearchJob.DONE.equals(state);
        notifications.add("research", done ? "ok" : "ABORTED".equals(state) ? "warn" : "err",
                "리서치 #" + j.getId() + " " + (done ? "완료" : "ABORTED".equals(state) ? "중단" : "실패") + ": " + abbreviate(j.getQuestion(), 80),
                error, "/research.html#r=" + j.getRootId());
        if (j.isNotify()) {
            notifySelf(j);
        }
    }

    private void notifySelf(ResearchJob j) {
        if (!client.notifyOn(MessengerService.NOTICE_RESEARCH)) {
            return;                               // 메신저 알림을 껐다(알림함에는 finish 가 이미 남겼다)
        }
        try {
            String link = "http://localhost:" + adminPort + "/research.html#r=" + j.getRootId();
            if (ResearchJob.DONE.equals(j.getState())) {
                String summary = section(j.getResultMd(), "요약");
                if (summary.isBlank()) {
                    summary = abbreviate(j.getResultMd(), 600);
                }
                client.sendToSelf(PFX + "#" + j.getId() + " 완료: " + abbreviate(j.getQuestion(), 80) + "\n" + plain(summary)
                        + "\n전체 문서: " + link);
            } else {
                client.sendToSelf(PFX + "#" + j.getId() + " " + ("ABORTED".equals(j.getState()) ? "중단" : "실패") + ": "
                        + abbreviate(j.getQuestion(), 80) + (j.getLastError() == null ? "" : "\n" + j.getLastError()) + "\n" + link);
            }
        } catch (RuntimeException e) {
            log.warn("질의 알림 전송 실패(무시): {}", e.toString());
        }
    }

    /** 실행 후 참고 worktree 에 변경이 생겼으면 되돌린다(읽기 전용 약속 위반). */
    private void guard(List<Ref> refs, long eid) {
        for (Ref r : refs) {
            try {
                String changes = git.referenceChanges(r.dir());
                if (!changes.isBlank()) {
                    git.resetReference(r.dir());
                    events.emit(eid, IssueEvent.ERROR, "⚠ 저장소 " + r.path() + " 에 변경이 생겨 되돌렸습니다(읽기 전용)", changes);
                }
            } catch (RuntimeException e) {
                log.warn("질의 참고 저장소 {} 검사 실패: {}", r.path(), e.toString());
            }
        }
    }

    public String abort(long id) {
        ResearchJob j = jobs.find(id);
        if (j == null) {
            return "질의를 찾을 수 없습니다: #" + id;
        }
        if (!j.isActive()) {
            return "#" + id + " 는 이미 종료된 질의입니다 (" + j.getState() + ").";
        }
        abortRequested.add(id);
        boolean killed = agent.kill(j.getClaudeSessionId());
        if (killed) {
            events.emit(eventId(id), IssueEvent.INFO, "중단 요청 — 실행 중인 Claude 프로세스 종료");
        } else if (ResearchJob.NEW.equals(j.getState())) {
            finish(j, ResearchJob.ABORTED, null, "사용자 중단(대기열)");
        }
        return "#" + id + " 중단 요청을 접수했습니다." + (killed ? " (실행 중이던 질의를 종료합니다)" : "");
    }

    // ---------------------------------------------------------------- 조회

    public Map<String, Object> overview(int limit) {
        List<ResearchJob> roots = jobs.findRoots(Math.max(1, Math.min(limit, 200)));
        Map<Long, ResearchJobMapper.ThreadSummary> sum = new LinkedHashMap<>();
        if (!roots.isEmpty()) {
            for (ResearchJobMapper.ThreadSummary s : jobs.summaries(roots.stream().map(ResearchJob::getId).toList())) {
                sum.put(s.getId(), s);
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ResearchJob r : roots) {
            Map<String, Object> m = new LinkedHashMap<>();
            ResearchJobMapper.ThreadSummary s = sum.get(r.getId());
            m.put("id", r.getId());
            m.put("question", r.getQuestion());
            m.put("projects", r.projectList());
            m.put("model", r.getModel());
            m.put("effort", r.getEffort());
            m.put("state", s != null ? s.getState() : r.getState());
            m.put("followups", s != null ? s.getFollowups() : 0);
            m.put("verify", r.isVerify());
            m.put("verifyState", r.getVerifyState());
            m.put("kind", r.getKind());
            m.put("requester", r.getRequester());
            m.put("createdAt", r.getCreatedAt());
            m.put("updatedAt", s != null && s.getUpdatedAt() != null ? s.getUpdatedAt() : r.getUpdatedAt());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("threads", list);
        Long rid = runningId.get();
        out.put("runningId", rid);
        if (rid != null) {
            ResearchJob rj = jobs.find(rid);
            CodingAgent.RunInfo ri = rj == null ? null : agent.runInfo(rj.getClaudeSessionId());
            if (ri != null) {
                out.put("running", Map.of("id", rid, "rootId", rj.getRootId(), "model", ri.model(), "effort", ri.effort(), "startedAt", ri.startedAt()));
            }
        }
        out.put("limited", usage.isLimited());
        out.put("limitUntil", usage.isLimited() ? usage.limitUntilText() : null);
        return out;
    }

    /** 스레드 상세: 루트/후속 항목 전부(결과 본문 포함) + 이벤트(모든 항목, id 순). */
    public Map<String, Object> thread(long anyId, long afterEventId, int limit) {
        ResearchJob j = jobs.find(anyId);
        if (j == null) {
            return null;
        }
        List<ResearchJob> items = jobs.findThread(j.getRootId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rootId", j.getRootId());
        out.put("items", items);
        out.put("eventIds", items.stream().map(x -> eventId(x.getId())).toList());
        out.put("events", events.events(items.stream().map(x -> eventId(x.getId())).toList(), afterEventId, limit));
        return out;
    }

    public ResearchJob find(long id) {
        return jobs.find(id);
    }

    /** 화면이 올린 이미지를 임시 보관하고 이름을 돌려준다(접수 때 그 이름을 함께 보낸다). */
    public String stageAttachment(byte[] data) {
        if (!enabled()) {
            throw new IllegalArgumentException("질의 워크플로우가 비활성 상태입니다.");
        }
        return attachments.stage(data);
    }

    public Path attachmentFile(long jobId, String name) {
        return attachments.file(jobId, name);
    }

    public Map<String, Object> options() {
        SettingService.AgentModels m = settings.getAgentModels();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("projects", settings.getIssueProjects());
        out.put("models", List.of("fable", "opus", "sonnet", "haiku"));
        out.put("efforts", List.of("low", "medium", "high"));
        out.put("defaultModel", nz(props.model(), m.designModel()));
        out.put("defaultEffort", nz(props.effort(), m.designEffort()));
        out.put("timeoutMin", props.timeoutOr() / 60_000);
        out.put("maxTurns", props.maxTurnsOr());
        out.put("defaultVerify", props.verify());
        return out;
    }

    /** 메신저용 한 줄 접수 안내. */
    public String submitFromMessenger(String text) {
        Parsed p = parseMessengerCommand(text, settings.getIssueProjects());
        if (p == null) {
            return "사용법: 질문 <프로젝트[@브랜치][,프로젝트…]>: <질문>  예) 질문 batch: 스케줄 job 목록을 표로 정리해줘 / 질문 batch@develop: …"
                    + (settings.getIssueProjects().size() == 1 ? "  (대상 프로젝트가 1개면 '프로젝트:' 생략 가능)" : "")
                    + "\n대상 프로젝트: " + settings.getIssueProjects();
        }
        ResearchJob j = submit(p.question(), p.projects(), null, null, null, true, "messenger", null);
        return "#" + j.getId() + " 접수 (" + String.join(", ", j.projectList()) + " · " + j.getModel() + "/" + j.getEffort() + "). "
                + "소스를 훑어 문서로 정리한 뒤 알려드립니다(보통 5~20분). 진행: http://localhost:" + adminPort + "/research.html#r=" + j.getId();
    }

    record Parsed(List<String> projects, String question) {
    }

    /**
     * "{@code batch: 질문}" / "{@code batch,scheduler: 질문}" / (대상 프로젝트가 1개면) "{@code 질문}". 프로젝트는 경로 전체나 마지막 세그먼트.
     * 콜론 앞이 프로젝트처럼 생기지 않았으면(공백 포함 등) 프로젝트 지정이 없는 것으로 본다.
     */
    static Parsed parseMessengerCommand(String text, List<String> allowed) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return null;
        }
        // 프로젝트 뒤에 @브랜치 를 붙일 수 있다(batch@develop). 브랜치 이름의 '/' 도 허용
        Matcher m = Pattern.compile("^([A-Za-z0-9_./@-]+(?:\\s*,\\s*[A-Za-z0-9_./@-]+)*)\\s*[:：]\\s*(.+)$", Pattern.DOTALL).matcher(t);
        if (m.find()) {
            List<String> ps = new ArrayList<>();
            for (String s : m.group(1).split(",")) {
                ps.add(s.strip());
            }
            String q = m.group(2).strip();
            return q.isEmpty() ? null : new Parsed(ps, q);
        }
        if (allowed != null && allowed.size() == 1) {
            return new Parsed(List.of(allowed.get(0)), t);
        }
        return null;
    }

    // ---------------------------------------------------------------- 프롬프트

    record Ref(String path, String branch, Path dir) {
    }

    private String systemPrompt(List<Ref> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 GitHub 저장소의 소스를 읽고 질문에 답하는 시니어 개발자·분석가입니다. ")
                .append("이 세션은 조사·정리 전용입니다: 파일을 수정·생성하지 말고(Edit/Write 금지), 빌드·테스트·설치 명령도 돌리지 마세요. ")
                .append("현재 작업 디렉터리는 '").append(refs.get(0).path()).append("' 의 ").append(refs.get(0).branch())
                .append(" 브랜치 읽기 전용 작업트리입니다.");
        if (refs.size() > 1) {
            sb.append("\n함께 볼 프로젝트(읽기 전용, 절대 경로로 Read/Grep/Glob):");
            for (Ref r : refs) {
                sb.append("\n- ").append(r.path()).append(" (").append(r.branch()).append("): ")
                        .append(r.dir().toAbsolutePath().toString().replace('\\', '/'));
            }
        }
        sb.append("\n\n답변 원칙:\n")
                .append("- 소스에서 확인한 사실만 쓰고, 추측은 '추정' 이라고 표시합니다. 못 찾은 것은 못 찾았다고 적습니다.\n")
                .append("- 목록·비교는 마크다운 표로 정리합니다(항목이 많아도 생략하지 말고 전부).\n")
                .append("- 각 항목에 근거 파일 경로(가능하면 :라인)를 붙입니다. 저장소 루트 기준 상대 경로.\n")
                .append("- 한국어로 쓰되 코드 식별자·파일명·설정 키는 원문 그대로.\n")
                .append("\n출력 형식(마크다운 문서, 이 틀을 지키세요):\n")
                .append("# <문서 제목>\n")
                .append("## 요약\n(3~5줄. 핵심 숫자·결론 먼저)\n")
                .append("## 내용\n(표/목록 중심. 필요하면 ### 소제목으로 묶음)\n")
                .append("## 근거\n(참조한 파일·디렉터리 목록)\n")
                .append("## 미확인·주의\n(확인하지 못한 것, 전제, 주의할 점. 없으면 '없음')\n");
        return sb.toString();
    }

    private String firstPrompt(String question) {
        return "다음 질문에 대해 프로젝트 소스를 조사해 문서로 정리해 주세요.\n\n--- 질문 ---\n" + question;
    }

    private String followupPrompt(String question) {
        return "앞선 조사에 이어지는 추가 질문입니다. 이미 읽은 내용을 활용하되 필요한 부분은 다시 확인하고, 같은 문서 형식으로 답하세요.\n\n--- 추가 질문 ---\n" + question;
    }

    // ---------------------------------------------------------------- 유틸

    static String section(String md, String heading) {
        if (md == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?ms)^##\\s*" + Pattern.quote(heading) + "\\s*$\\n?(.*?)(?=^#{1,2}\\s|\\z)").matcher(md);
        return m.find() ? m.group(1).strip() : "";
    }

    /** 메신저용: 마크다운 장식을 벗겨 평문으로. */
    static String plain(String md) {
        return md.replaceAll("(?m)^#{1,6}\\s*", "").replace("**", "").replaceAll("`([^`]*)`", "$1").replaceAll("(?m)^\\|?\\s*:?-{2,}.*$\\n?", "").strip();
    }

    private static String nz(String v, String def) {
        return v == null || v.isBlank() ? def : v.strip();
    }

    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}

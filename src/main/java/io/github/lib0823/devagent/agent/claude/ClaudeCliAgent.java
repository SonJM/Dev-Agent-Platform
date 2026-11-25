package io.github.lib0823.devagent.agent.claude;

import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.platform.CallStats;
import io.github.lib0823.devagent.platform.Os;
import io.github.lib0823.devagent.platform.PortHealth;
import io.github.lib0823.devagent.gitlab.IssueEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.ClaudeProperties;
import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.config.PreviewProperties;
import io.github.lib0823.devagent.mcp.McpConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 코딩 작업용 Claude Code CLI 실행기(에이전틱 모드).
 * <p>
 * 저장소 디렉터리를 cwd 로 하여 {@code claude -p} 를 실행하고 파일 읽기/수정·제한된 Bash 를 허용한다. 이슈 1건 = CLI 세션 1개:
 * 첫 호출은 {@code --session-id <uuid>}, 이후 단계(피드백/수정)는 {@code --resume <uuid>} 로 맥락을 이어간다.
 * <p>
 * 출력은 {@code --output-format stream-json --verbose} 로 받아 진행 중 도구 호출(Read/Edit/Bash…)과
 * 중간 텍스트를 {@link Listener} 로 실시간 전달하고(모니터 화면), 최종 {@code result} 이벤트를 반환한다.
 */
@Component
@ConditionalOnProperty(prefix = "platform", name = "coding-agent", havingValue = "claude-cli", matchIfMissing = true)
public class ClaudeCliAgent implements CodingAgent {

    @Override
    public String provider() {
        return "claude-cli";
    }

    private final CallStats stats = new CallStats();

    @Override
    public PortHealth health() {
        int n = running.size();
        return stats.health(provider(), PortHealth.OK,
                        n > 0 ? "세션 " + n + "개 실행 중" : stats.everCalled() ? "대기(최근 실행 정상)" : "대기(아직 실행 없음)")
                .with("cli", claude.command())
                .with("running", n);
    }

    @Override
    public PortHealth.TestResult selfTest() {
        return ClaudeCliProbe.version(claude.command());
    }

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliAgent.class);

    private final ClaudeProperties claude;
    private final GitLabProperties props;
    private final McpConfigService mcp;
    private final PreviewProperties preview;
    private final SettingService settings;
    private final int adminPort;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 세션 UUID → 실행 중 프로세스 (중단 요청 시 즉시 종료용). */
    private final Map<String, Process> running = new java.util.concurrent.ConcurrentHashMap<>();
    /** 실행 중인 세션의 모델·단계(대시보드 "지금 돌고 있는 모델" 표시용). 키는 Claude 세션 id. */
    private final Map<String, RunInfo> runs = new java.util.concurrent.ConcurrentHashMap<>();
    /** 선택한 모델을 쓸 수 없을 때(권한 없음·미지원) 대체할 모델. */
    static final String FALLBACK_MODEL = "opus";
    /** 플랫폼 관리 세션의 사용량 구분값(Overrides.source) 겸 스킬 단계 키. */
    static final String MAINTAIN_SOURCE = "maintain";

    /** 해당 Claude 세션이 지금 실행 중이면 그 실행 정보, 아니면 null. */
    @Override
    public RunInfo runInfo(String sessionId) {
        return sessionId == null ? null : runs.get(sessionId);
    }

    /** 서브 에이전트를 띄우는 도구(Claude Code 의 Agent, 예전 이름 Task). */
    static boolean isSubagentTool(String name) {
        return "Agent".equals(name) || "Task".equals(name);
    }

    /** CLI 가 모델을 거부했는지("There's an issue with the selected model …" / stderr unrecognized_model). */
    static boolean isModelUnavailable(String text) {
        return text != null && (text.contains("issue with the selected model") || text.contains("unrecognized_model"));
    }

    /** 해당 세션의 Claude 프로세스(와 gradle/node 등 자식)를 강제 종료한다. 실행 중이 아니면 false. */
    @Override
    public boolean kill(String sessionId) {
        Process p = sessionId == null ? null : running.get(sessionId);
        if (p == null) {
            return false;
        }
        killed.add(sessionId);                        // 사용자가 끊은 실행은 연동 상태의 "실패" 로 세지 않는다
        killTree(p);
        return true;
    }

    /** 중단 요청으로 종료시킨 세션 — 그 실행의 예외는 장애가 아니다. */
    private final java.util.Set<String> killed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void killTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);   // Windows: claude.exe 만 죽이면 gradle 데몬/node 가 남아 파이프를 잡는다
        } catch (RuntimeException ignore) {
            // 권한/경쟁 문제는 무시하고 본 프로세스만이라도 종료
        }
        p.destroyForcibly();
    }

    private final io.github.lib0823.devagent.llm.UsageService usage;
    /** 플랫폼 스킬(관리자 등록 지침) — 단계에 맞는 것을 시스템 프롬프트에 덧붙인다. */
    private final io.github.lib0823.devagent.skill.SkillService skills;

    public ClaudeCliAgent(ClaudeProperties claude, GitLabProperties props, McpConfigService mcp,
                             PreviewProperties preview, SettingService settings,
                             io.github.lib0823.devagent.llm.UsageService usage,
                             io.github.lib0823.devagent.skill.SkillService skills,
                             @Value("${server.port:8777}") int adminPort) {
        this.skills = skills;
        this.usage = usage;
        this.claude = claude;
        this.settings = settings;
        this.props = props;
        this.mcp = mcp;
        this.preview = preview;
        this.adminPort = adminPort;
    }

    /** 프리뷰/정적검사 스크립트(scripts/bin)의 Bash 허용 패턴 — 수정 단계에서만 쓰인다. */
    List<String> previewTools(boolean fixStage) {
        return previewTools(fixStage ? Stage.FIX : Stage.ANALYZE);
    }

    List<String> previewTools(Stage stage) {
        if (stage == Stage.ANALYZE || preview == null || !preview.enabled()) {
            return List.of();
        }
        if (stage == Stage.VERIFY) {
            return List.of("Bash(jscheck:*)", "Bash(jspcheck:*)");   // 배포/중지는 리뷰어에게 주지 않는다(preview status/log 는 verify.tools 에서)
        }
        return List.of("Bash(preview:*)", "Bash(jscheck:*)", "Bash(jspcheck:*)");
    }

    /** 리뷰어(검증 단계) 안내: 프리뷰가 떠 있으면 Playwright 로 직접 확인, 파일은 수정하지 않는다. */
    String verifyHint(Path shotsDir, boolean previewUp) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 수정 세션과 분리된 독립 리뷰어입니다. 파일을 수정하지 말고 읽기·실행·확인만 하세요.\n");
        if (preview != null && preview.enabled() && previewUp) {
            sb.append("프리뷰 Tomcat 이 ").append(preview.url()).append(" 에 이 작업트리로 떠 있습니다. 화면 이슈면 playwright 도구(mcp__playwright)로 직접 열어 ")
              .append("이슈의 재현 경로를 따라가 수정 결과를 눈으로 확인하세요. 스크린샷은 browser_take_screenshot 의 filename 에 절대경로 ")
              .append(shotsDir == null ? "(출력 폴더)" : shotsDir.toAbsolutePath().toString().replace('\\', '/'))
              .append("/verify-<설명>.png 로 저장하세요.\n");
            if (preview.hasLogin()) {
                sb.append("   로그인 계정: ").append(preview.loginId()).append(" / ").append(preview.loginPw()).append("\n");
            }
            sb.append("`preview log 80` 으로 Tomcat 로그의 예외를 확인할 수 있습니다.\n");
        } else if (preview != null && preview.enabled()) {
            sb.append("프리뷰 Tomcat 은 현재 떠 있지 않습니다(수정 세션이 배포하지 않았거나 내려감). 화면 확인은 코드 리뷰와 정적검사(jscheck/jspcheck)로 대신하고 그 사실을 근거에 적으세요.\n");
        }
        return sb.toString();
    }

    /** 프리뷰·화면 검증 사용법(수정 단계 시스템 프롬프트). */
    String previewHint(Path shotsDir) {
        if (preview == null || !preview.enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("명령 실행 규칙: `preview`, `jscheck`, `jspcheck`, `mvn`(JDK 21 포함) 은 PATH 에 있으니 **경로 없이 그 이름으로** 실행하세요. ")
          .append("`cd …&&` 를 앞에 붙이거나 절대경로로 부르면 권한 패턴과 어긋나 거부됩니다(현재 디렉터리가 이미 작업트리). PowerShell 도구는 쓰지 마세요.\n")
          .append("화면/JSP/JS 를 고쳤다면 반드시 실제 화면으로 검증하세요. 순서:\n")
          .append("1) 정적 검사: 수정한 파일에 `jscheck <파일…>`(JS 문법), `jspcheck <파일…>`(JSP 를 Jasper 로 컴파일; 빌드 산출물이 필요하므로 preview deploy 뒤에 실행 가능).\n")
          .append("2) `preview deploy` — 현재 worktree 를 Maven 으로 빌드해 로컬 Tomcat 에 배포하고 URL 을 돌려줍니다(빌드 10분 안팎 + 기동 최대 9분, 한 번만 기다리세요). ")
          .append("Bash 도구의 timeout 을 2400000(40분)으로 지정해 실행하세요. 프리뷰 Tomcat 은 1개를 여러 이슈가 순서대로 쓰므로 다른 세션이 쓰는 중이면 응답이 그만큼 늦어집니다(최대 25분 대기 후 FAIL 이면 다시 실행). ")
          .append("빌드 실패면 출력의 오류를 고친 뒤 다시 실행, 기동만 문제면 `preview redeploy`.\n")
          .append("   프리뷰가 이미 떠 있고 그 뒤 JSP/JS/CSS/이미지(src/main/webapp)만 고쳤다면 `preview sync`(수 초)로 반영하세요 — 다시 deploy 하지 마세요. Java/resources/pom 을 고쳤을 때만 `preview deploy`.\n")
          .append("   권장 순서: 화면 이슈면 수정 전에 먼저 `preview deploy` 로 띄워 현상을 재현·캡처(before-*.png) → 수정 → `preview sync` → 재확인·캡처(after-*.png).\n")
          .append("3) playwright 도구(mcp__playwright)로 ").append(preview.url()).append(" 을 열어 이슈의 화면을 재현하고 수정 결과를 확인하세요. ")
          .append("스크린샷은 browser_take_screenshot 의 filename 에 반드시 절대경로 ")
          .append(shotsDir == null ? "(출력 폴더)" : shotsDir.toAbsolutePath().toString().replace('\\', '/'))
          .append("/<설명>.png 로 저장하세요(예: after-metric-list.png). 상대 경로로 저장하면 저장소가 오염됩니다.\n");
        if (preview.hasLogin()) {
            sb.append("   로그인 계정: ").append(preview.loginId()).append(" / ").append(preview.loginPw()).append("\n");
        } else {
            sb.append("   로그인 계정이 아직 없으면 로그인 화면까지만 확인하고 그 사실을 적으세요.\n");
        }
        sb.append("4) '## 검증 결과' 에 실행한 검사·프리뷰 결과와 스크린샷 파일명을 적으세요. `preview log 80` 으로 Tomcat 로그를 볼 수 있습니다.\n")
          .append("프리뷰 Tomcat 은 개발 DB/OpenSearch 에 붙습니다(쓰기 허용됨). 작업이 끝나면 `preview stop` 은 하지 않아도 됩니다(봇이 정리).");
        return sb.toString();
    }

    @Override
    public Result run(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                      String systemAppend, String prompt, Listener listener, Path shotsDir, Stage stage, boolean previewUp,
                      Overrides ov) {
        // 상태 표시용 기록: 예외·오류 결과는 실패, 그 외는 성공(연동 카드의 "최근 실행")
        // 사용자 중단과 사용량 한도는 장애가 아니므로 실패로 세지 않는다
        long t0 = System.currentTimeMillis();
        try {
            Result r = doRun(cwd, sessionId, resume, allowedTools, systemAppend, prompt, listener, shotsDir, stage, previewUp, ov);
            if (killed.remove(sessionId)) {
                return r;
            }
            if (r.error()) {
                stats.error(new IllegalStateException(abbreviate(r.text(), 200)));
            } else {
                stats.ok(System.currentTimeMillis() - t0);
            }
            return r;
        } catch (RuntimeException e) {
            if (!killed.remove(sessionId) && !(e instanceof UsageLimitException)) {
                stats.error(e);
            }
            throw e;
        }
    }

    private Result doRun(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                         String systemAppend, String prompt, Listener listener, Path shotsDir, Stage stage, boolean previewUp,
                         Overrides ov) {
        // 모델/노력은 관리 페이지(DB) 값 우선 — 기획(분석·재설계) / 개발(수정·재수정) / 테스트(독립 리뷰어) 따로
        SettingService.AgentModels m = settings.getAgentModels();
        String model = stage == Stage.VERIFY ? m.verifyModel() : stage == Stage.ANALYZE ? m.designModel() : m.devModel();
        String effort = stage == Stage.VERIFY ? m.verifyEffort() : stage == Stage.ANALYZE ? m.designEffort() : m.devEffort();
        if (ov != null && ov.model() != null && !ov.model().isBlank()) {
            model = ov.model().strip();
        }
        if (ov != null && ov.effort() != null && !ov.effort().isBlank()) {
            effort = ov.effort().strip();
        }
        Result r = runOnce(cwd, sessionId, resume, allowedTools, systemAppend, prompt, listener, shotsDir, stage, previewUp, model, effort, ov);
        if (r.error() && isModelUnavailable(r.text()) && !FALLBACK_MODEL.equalsIgnoreCase(model)) {
            String msg = "모델 '" + model + "' 사용 불가(권한 없음 또는 미지원) → '" + FALLBACK_MODEL + "' 로 대체 실행";
            log.warn("claude(agent) {}: {}", msg, abbreviate(r.text(), 200));
            if (listener != null) {
                listener.onActivity(IssueEvent.ERROR, "⚠ " + msg, r.text());
            }
            // 같은 --session-id 재사용은 "already in use" 로 거부되지만 --resume 은 된다(실측). 거부된 호출도 세션 파일은 만든다.
            r = runOnce(cwd, sessionId, true, allowedTools, systemAppend, prompt, listener, shotsDir, stage, previewUp, FALLBACK_MODEL, effort, ov);
        }
        return r;
    }

    private Result runOnce(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                           String systemAppend, String prompt, Listener listener, Path shotsDir, Stage stage, boolean previewUp,
                           String model, String effort, Overrides ov) {
        boolean fixStage = stage == Stage.FIX;
        // 플랫폼 관리 세션(자기 저장소 유지보수): 대상 프로젝트용 프리뷰·사용자 정의 MCP 안내를 싣지 않고, 푸시는 화면 버튼의 몫이라 막는다
        boolean maintain = ov != null && MAINTAIN_SOURCE.equals(ov.source());
        List<String> cmd = new ArrayList<>();
        cmd.add(claude.command());              // claude.exe 직접 실행 (한글 인자 안전)
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--strict-mcp-config");
        cmd.add("--disable-slash-commands");
        cmd.add("--disallowedTools");                 // Bash 권한 패턴을 우회하는 PowerShell 도구는 막는다(실측: allowedTools 에 없어도 실행됨)
        cmd.add("PowerShell");
        if (maintain) {
            cmd.add("Bash(git push:*)");
            cmd.add("Read(./.env)");                  // 비밀값 파일은 읽기 도구로도 열지 못하게(셸 우회까지 막지는 못한다 — 프롬프트로도 금지)
        }
        boolean subagents = settings.getAgentSubagents();
        boolean teams = settings.getAgentTeams();
        if (!subagents) {
            // 서브 에이전트(Agent/Task)는 allowedTools 에 없어도 돌아가며 토큰을 병렬로 태운다(scheduler#1 에서 15개 실측) → 끄면 도구 금지
            cmd.add("Agent");
            cmd.add("Task");
        }
        if (!teams) {
            // 팀 에이전트(실험 기능)는 아래 env 플래그 0 으로 끄고, 혹시 남는 팀 메시지 도구도 금지
            cmd.add("SendMessage");
            cmd.add("TeamCreate");
            cmd.add("TeamDelete");
        }
        cmd.add("--permission-mode");
        cmd.add("acceptEdits");
        if (resume) {
            cmd.add("--resume");
            cmd.add(sessionId);
        } else {
            cmd.add("--session-id");
            cmd.add(sessionId);
        }
        GitLabProperties.Agent a = props.agent();
        if (!model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (!effort.isBlank()) {
            cmd.add("--effort");
            cmd.add(effort);
        }
        // 단계별 상한: 분석은 agent.timeout-ms, 개발(수정·재수정)은 agent.fix-timeout-ms(큰 이슈용으로 길게), 리뷰어는 verify.reviewer-timeout-ms
        long timeoutMs = fixStage ? a.fixTimeoutOr()
                : stage == Stage.VERIFY && props.verify() != null && props.verify().reviewerTimeoutMs() > 0 ? props.verify().reviewerTimeoutMs()
                : a.timeoutMs();
        int maxTurns = fixStage ? a.fixMaxTurnsOr() : a.maxTurns();
        if (ov != null && ov.timeoutMs() > 0) {
            timeoutMs = ov.timeoutMs();
        }
        if (ov != null && ov.maxTurns() > 0) {
            maxTurns = ov.maxTurns();
        }
        if (maxTurns > 0) {
            cmd.add("--max-turns");
            cmd.add(String.valueOf(maxTurns));
        }
        boolean withMcp = mcp.agentEnabled() && !maintain;
        boolean withPw = (fixStage || (stage == Stage.VERIFY && previewUp)) && shotsDir != null && mcp.playwrightEnabled();
        List<String> tools = new ArrayList<>(allowedTools == null ? List.of() : allowedTools);
        if (!maintain) {
            tools.addAll(previewTools(stage));        // preview / jscheck / jspcheck 스크립트
        }
        Path mcpConfig = withPw ? mcp.configFor(shotsDir) : (withMcp ? mcp.configPath() : null);
        if (mcpConfig != null) {
            cmd.add("--mcp-config");                  // 사용자 정의 MCP 서버 + (수정 단계) playwright
            cmd.add(mcpConfig.toString());
            if (withMcp) {
                tools.addAll(mcp.tools());
            }
            if (withPw) {
                tools.addAll(mcp.playwrightTools());
            }
        }
        if (!tools.isEmpty()) {
            cmd.add("--allowedTools");
            cmd.addAll(tools);
        }
        String sys = systemAppend == null ? "" : systemAppend.strip();
        // 플랫폼 스킬: 질의는 research, 플랫폼 관리는 maintain, 그 외는 단계(analyze/fix/verify)에 맞는 지침
        String skillNote = skills == null ? "" : skills.promptNote(
                maintain ? MAINTAIN_SOURCE : ov != null && "research".equals(ov.source()) ? "research" : stage.name().toLowerCase(java.util.Locale.ROOT));
        if (!skillNote.isEmpty()) {
            sys = (sys.isEmpty() ? "" : sys + "\n\n") + skillNote;
        }
        if (fixStage && !maintain) {
            String ph = previewHint(shotsDir);
            if (!ph.isEmpty()) {
                sys = (sys.isEmpty() ? "" : sys + "\n\n") + ph;
            }
        } else if (stage == Stage.VERIFY) {
            String vh = verifyHint(shotsDir, previewUp);
            if (!vh.isEmpty()) {
                sys = (sys.isEmpty() ? "" : sys + "\n\n") + vh;
            }
        }
        if (withMcp) {
            sys = (sys.isEmpty() ? "" : sys + "\n\n") + mcp.usageHint()
                    + "\nMCP 도구는 원인 확인·재현·검증에만 쓰고, 조회 실패가 작업을 막지 않게 하세요.";
        }
        // 시스템 프롬프트는 인자가 아니라 파일로 넘긴다: 인자로 넘기면 본문의 큰따옴표가 Windows 인자 파싱을 깨서
        // 뒤쪽(스킬·MCP·프리뷰 안내)이 시스템 프롬프트에서 떨어져 나간다(11-16 실측). 길이 제한(32K)도 없어진다.
        final Path sysFile = sys.isBlank() ? null : ClaudeCliProbe.writePromptFile(sys);
        if (sysFile != null) {
            cmd.add("--append-system-prompt-file");
            cmd.add(sysFile.toString());
        }

        long started = System.currentTimeMillis();
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
            if (withMcp) {
                pb.environment().putAll(mcp.env());   // 사용자 정의 서버의 비밀값은 env 로만(설정 파일엔 ${ENV} 자리표시자)
            }
            Map<String, String> env = pb.environment();
            Path scripts = Path.of("scripts", "bin").toAbsolutePath();   // preview / jscheck / jspcheck
            // 주의: Claude 의 Bash 도구는 이 PATH 를 쓰지 않는다 → 실제 해결은 installShims()(~/bin). 다른 도구/프로세스용으로 남겨 둔다.
            env.put("PATH", scripts + java.io.File.pathSeparator + env.getOrDefault("PATH", env.getOrDefault("Path", "")));
            env.put("DEV_AGENT_PORT", String.valueOf(adminPort));
            // 모델이 돌리는 gradlew 가 데몬을 남기면 그 데몬이 supervisor 의 bot.lock 핸들을 상속해 재시작을 막는다(11-12 두 번 실측) → 데몬 금지
            env.put("GRADLE_OPTS", (env.getOrDefault("GRADLE_OPTS", "") + " -Dorg.gradle.daemon=false").strip());
            // 팀 에이전트는 Claude Code 실험 플래그(사용자 settings/env 에 1)로 켜진다 → 관리 페이지에서 끄면 자식 env 를 0 으로 덮어 팀 기능 자체를 비활성
            if (!teams) {
                env.put("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "0");
            }
            // Bash 도구 기본 타임아웃(2분)·상한(10분)으로는 preview deploy(빌드 12분+기동 9분, 공유 대기 포함 최대 45분)가 끊긴다
            env.put("BASH_DEFAULT_TIMEOUT_MS", "600000");
            env.put("BASH_MAX_TIMEOUT_MS", String.valueOf(Math.max(600_000L, Math.min(3_000_000L, timeoutMs - 60_000L))));
            installShims();                           // 설정이 바뀌었을 수 있으니 매 실행 전 멱등 갱신
            if (maintain) {
                // 플랫폼 자신을 빌드한다: 프리뷰 JDK(21)가 아니라 지금 이 JVM 의 JDK(javac 가 있으면)를 쓴다. JRE 로 떴으면 물려받은 JAVA_HOME 그대로
                Path own = Path.of(System.getProperty("java.home"));
                if (java.nio.file.Files.exists(own.resolve("bin").resolve("javac.exe")) || java.nio.file.Files.exists(own.resolve("bin").resolve("javac"))) {
                    env.put("JAVA_HOME", own.toString());
                }
            } else if (preview != null && preview.enabled()) {
                env.put("JAVA_HOME", nz(preview.javaHome()));   // 대상 저장소(Java 21) 빌드용. 봇 자신은 JDK 17 로 돌지만 자식은 프리뷰 JDK
                env.put("PREVIEW_JAVA_HOME", nz(preview.javaHome()));
                env.put("PREVIEW_CATALINA_HOME", nz(preview.catalinaHome()));
                env.put("PREVIEW_URL", preview.url());
                if (shotsDir != null) {
                    env.put("PREVIEW_SHOTS_DIR", shotsDir.toAbsolutePath().toString());
                }
            }
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("claude(agent) 실행 실패: " + e.getMessage(), e);
        }
        running.put(sessionId, process);
        runs.put(sessionId, new RunInfo(model, effort, stage, started));
        StreamState st = new StreamState(cwd, listener);
        CompletableFuture<Void> stdout = CompletableFuture.runAsync(() -> readStream(process.getInputStream(), st));
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            try (OutputStream os = process.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                killTree(process);
                throw new IllegalStateException("claude(agent) stdin 쓰기 실패: " + e.getMessage(), e);
            }
            // 전체 상한 + 무응답 감시: 긴 상한(개발 4시간)을 주더라도 출력이 끊긴 채 멈춘 세션은 idle-timeout 으로 일찍 끊는다
            long idleMs = a.idleTimeoutMs();
            long deadline = started + timeoutMs;
            boolean finished = false;
            try {
                while (!finished) {
                    long now = System.currentTimeMillis();
                    if (now >= deadline) {
                        break;
                    }
                    long idle = now - st.lastActivityAt;
                    if (idleMs > 0 && idle >= idleMs) {
                        killTree(process);
                        throw new AgentTimeoutException(true, idle, st.toolCalls.get(), now - started);
                    }
                    long slice = Math.min(5_000L, deadline - now);
                    if (idleMs > 0) {
                        slice = Math.min(slice, Math.max(1_000L, idleMs - idle));
                    }
                    finished = process.waitFor(slice, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree(process);
                throw new IllegalStateException("claude(agent) 대기 인터럽트", e);
            }
            if (!finished) {
                killTree(process);
                throw new AgentTimeoutException(false, timeoutMs, st.toolCalls.get(), System.currentTimeMillis() - started);
            }
            try {
                stdout.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("claude(agent) stdout 리더 종료 대기 실패: {}", e.toString());
            }
            long elapsed = System.currentTimeMillis() - started;
            JsonNode result = st.result.get();
            if (process.exitValue() != 0 && result == null) {
                throw new IllegalStateException("claude(agent) 실패 (exit=" + process.exitValue() + "): "
                        + abbreviate(text(stderr), 800) + " / 마지막 출력: " + abbreviate(st.lastLine, 400));
            }
            if (result == null) {
                throw new IllegalStateException("claude(agent) result 이벤트 없음: " + abbreviate(text(stderr), 400)
                        + " / 마지막 출력: " + abbreviate(st.lastLine, 400));
            }
            Result r = parse(result, st.toolCalls.get(), elapsed);
            boolean limit = st.sawLimit || io.github.lib0823.devagent.llm.UsageService.isLimitText(r.text());
            usage.record(ov != null && ov.source() != null && !ov.source().isBlank() ? ov.source() : "agent", stage.name(), model, cwd.getFileName().toString(),
                    r.inputTokens(), r.outputTokens(), r.cacheReadTokens(), r.cacheCreateTokens(), limit);
            log.info("claude(agent) 완료 cwd={} resume={} turns={} tools={} cost=${} {}ms error={} limit={}",
                    cwd.getFileName(), resume, r.turns(), r.toolCalls(), r.tokens(), elapsed, r.error(), limit);
            if (limit && (r.error() || r.text().isBlank() || r.turns() == 0)) {
                // 한도에 걸리면 CLI 가 더 진행하지 못한 채 빈 결과(0턴)로 끝난다 → 호출자가 작업을 보존하고 리셋까지 보류하도록 전용 예외
                String lt = st.limitText != null ? st.limitText : r.text();
                throw new UsageLimitException(lt, usage.noteLimit(lt), st.toolCalls.get());
            }
            return r;
        } finally {
            running.remove(sessionId, process);
            runs.remove(sessionId);
            ClaudeCliProbe.deleteQuietly(sysFile);
            killTree(process);                        // 정상 종료 후에도 남은 자식(gradle 데몬 등)이 worktree 파일을 잠그지 않도록
        }
    }

    /** stderr 는 자식 프로세스가 파이프를 잡고 있으면 끝나지 않을 수 있어 짧게만 기다린다. */
    private static String text(CompletableFuture<String> f) {
        try {
            return f.get(5, TimeUnit.SECONDS).strip();
        } catch (Exception e) {
            return "(stderr 읽기 시간 초과)";
        }
    }

    private Result parse(JsonNode n, int toolCalls, long elapsed) {
        boolean error = n.path("is_error").asBoolean(false) || !"success".equals(n.path("subtype").asText("success"));
        String text = n.path("result").asText("");
        if (text.isBlank() && error) {
            text = "(오류) " + n.path("subtype").asText("") + " " + n.path("error").asText("");
        }
        JsonNode u = n.path("usage");
        return new Result(text.strip(), n.path("session_id").asText(null), error,
                n.path("total_cost_usd").asDouble(0), n.path("num_turns").asInt(0), toolCalls, elapsed,
                u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0), u.path("cache_creation_input_tokens").asLong(0));
    }

    // ---------------------------------------------------------------- stream-json 파싱

    private static final class StreamState {
        final Path cwd;
        final Listener listener;
        final AtomicReference<JsonNode> result = new AtomicReference<>();
        final AtomicInteger toolCalls = new AtomicInteger();
        final Map<String, String> toolNames = new HashMap<>();   // tool_use_id → 도구명
        volatile String lastLine = "";
        volatile long lastActivityAt = System.currentTimeMillis();   // 마지막 stream 출력 시각(무응답 감시용)
        volatile boolean sawLimit;                                  // "You've hit your session limit …" 텍스트를 봤는지
        volatile String limitText;
        final Map<String, Long> subagentStart = new HashMap<>();   // tool_use_id → 서브 에이전트 시작 시각
        final Map<String, String> subagentDesc = new HashMap<>();  // tool_use_id → 서브 에이전트 설명

        StreamState(Path cwd, Listener listener) {
            this.cwd = cwd;
            this.listener = listener;
        }
    }

    private void readStream(InputStream in, StreamState st) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                st.lastLine = line;
                st.lastActivityAt = System.currentTimeMillis();
                JsonNode ev;
                try {
                    ev = mapper.readTree(line);
                } catch (IOException parse) {
                    continue;
                }
                try {
                    handle(ev, st);
                } catch (RuntimeException e) {
                    log.debug("stream 이벤트 처리 오류(무시): {}", e.toString());
                }
            }
        } catch (IOException e) {
            log.debug("claude(agent) stdout 종료: {}", e.toString());
        }
    }

    private void handle(JsonNode ev, StreamState st) {
        String type = ev.path("type").asText("");
        switch (type) {
            case "result" -> st.result.set(ev);
            case "assistant" -> {
                for (JsonNode c : ev.path("message").path("content")) {
                    String ct = c.path("type").asText("");
                    if ("tool_use".equals(ct)) {
                        st.toolCalls.incrementAndGet();
                        String name = c.path("name").asText("tool");
                        String id = c.path("id").asText("");
                        st.toolNames.put(id, name);
                        String desc = describeTool(name, c.path("input"), st.cwd);
                        if (isSubagentTool(name)) {
                            st.subagentStart.put(id, System.currentTimeMillis());   // 완료 이벤트에 소요·설명을 붙이기 위해
                            st.subagentDesc.put(id, c.path("input").path("description").asText(""));
                        }
                        notify(st, IssueEvent.TOOL, desc, null);
                    } else if ("text".equals(ct)) {
                        String t = c.path("text").asText("").strip();
                        if (!t.isEmpty()) {
                            if (io.github.lib0823.devagent.llm.UsageService.isLimitText(t)) {
                                st.sawLimit = true;               // 한도 메시지는 오류가 아닌 텍스트로 와서 result 만 봐서는 모른다(실측)
                                st.limitText = t;
                            }
                            notify(st, IssueEvent.TEXT, abbreviate(t.replace('\n', ' '), 160), t);
                        }
                    }
                }
            }
            case "user" -> {
                for (JsonNode c : ev.path("message").path("content")) {
                    if (!"tool_result".equals(c.path("type").asText(""))) {
                        continue;
                    }
                    String tid = c.path("tool_use_id").asText("");
                    String name = st.toolNames.getOrDefault(tid, "tool");
                    boolean err = c.path("is_error").asBoolean(false);
                    String body = resultText(c.path("content"));
                    if (isSubagentTool(name)) {
                        // 서브 에이전트 완료: 대시보드 "서브 에이전트" 탭이 시작(TOOL "Agent …")과 짝지어 소요·상태를 그린다
                        Long t0 = st.subagentStart.remove(tid);
                        String desc = st.subagentDesc.remove(tid);
                        long sec = t0 == null ? 0 : (System.currentTimeMillis() - t0) / 1000;
                        notify(st, IssueEvent.TOOL_RESULT, (err ? "⚠ " : "↳ ") + name + " 완료 (" + (desc == null ? "" : desc) + ") · " + sec + "s"
                                + (err ? " · 오류" : ""), body);
                        continue;
                    }
                    if (err) {
                        notify(st, IssueEvent.TOOL_RESULT, "⚠ " + name + " 오류: " + abbreviate(firstLines(body, 2), 200), body);
                    } else if ("Bash".equals(name)) {
                        String head = firstLines(body, 3).strip();
                        notify(st, IssueEvent.TOOL_RESULT, "↳ " + (head.isEmpty() ? "(출력 없음)" : abbreviate(head, 200)), body);
                    }
                }
            }
            default -> { /* system/init 등 무시 */ }
        }
    }

    private static void notify(StreamState st, String kind, String message, String detail) {
        if (st.listener != null) {
            st.listener.onActivity(kind, message, detail);
        }
    }

    /** 도구 호출 한 줄 요약: "Read src/main/java/.../Foo.java", "Bash gradlew test" 등. */
    static String describeTool(String name, JsonNode input, Path cwd) {
        String arg;
        switch (name) {
            case "Read", "Edit", "Write", "MultiEdit", "NotebookEdit" -> arg = rel(input.path("file_path").asText(""), cwd);
            case "Glob" -> arg = input.path("pattern").asText("") + optPath(input, cwd);
            case "Grep" -> arg = "/" + input.path("pattern").asText("") + "/" + optPath(input, cwd);
            case "LS" -> arg = rel(input.path("path").asText(""), cwd);
            case "Bash" -> arg = input.path("command").asText("");
            case "WebFetch", "WebSearch" -> arg = input.path("url").asText(input.path("query").asText(""));
            case "Task", "Agent" -> arg = input.path("description").asText("");
            case "TodoWrite" -> arg = "(할 일 목록 갱신)";
            default -> {
                String s = input.toString();
                arg = s.length() > 2 ? s : "";
            }
        }
        arg = arg.replace('\n', ' ').strip();
        return (name + " " + abbreviate(arg, 220)).strip();
    }

    private static String optPath(JsonNode input, Path cwd) {
        String p = input.path("path").asText("");
        return p.isBlank() ? "" : " in " + rel(p, cwd);
    }

    private static String rel(String path, Path cwd) {
        if (path == null || path.isBlank() || cwd == null) {
            return path == null ? "" : path;
        }
        try {
            Path p = Path.of(path);
            if (p.isAbsolute() && p.startsWith(cwd.toAbsolutePath())) {
                return cwd.toAbsolutePath().relativize(p).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignore) {
            // 잘못된 경로 문자열은 그대로 반환
        }
        return path.replace('\\', '/');
    }

    private static String resultText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if ("text".equals(c.path("type").asText(""))) {
                sb.append(c.path("text").asText("")).append('\n');
            }
        }
        return sb.toString();
    }

    private static String firstLines(String s, int n) {
        if (s == null) {
            return "";
        }
        String[] lines = s.strip().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].strip());
        }
        return sb.toString();
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String abbreviate(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }

    // ---------------------------------------------------------------- ~/bin 심(shim)

    /**
     * Claude Code 의 Bash 도구는 봇이 자식 프로세스 환경에 넣어 준 PATH 를 보지 않는다(세션 시작 때 로그인 셸로 찍은 스냅샷의 PATH 를 쓴다).
     * 그래서 scripts/bin 을 PATH 에 붙여도 `jspcheck: command not found` 가 난다(2026-11-11 실측). Git Bash 의 로그인 PATH 맨 앞에는
     * 항상 `~/bin`(%USERPROFILE%\bin)이 있으므로 거기에 실행 심을 써 둔다: preview / jscheck / jspcheck → 저장소 스크립트,
     * mvn → 프리뷰용 Maven(JDK 21). 필요한 환경변수는 심 안에 절대값으로 박아 Bash 도구 환경과 무관하게 만든다. 멱등(내용 같으면 안 씀).
     * Linux 서버에서는 프리뷰가 꺼져 있으면 아무것도 쓰지 않고, 사용자의 기존 파일은 덮어쓰지 않는다({@link AgentShims}).
     */
    @jakarta.annotation.PostConstruct
    void installShims() {
        try {
            Path bin = Path.of(System.getProperty("user.home")).resolve("bin");
            List<String> written = AgentShims.install(bin, Path.of("scripts", "bin").toAbsolutePath(), adminPort, preview, Os.isWindows());
            if (!written.isEmpty()) {
                log.info("에이전트 실행 심 준비: {} {}", bin, written);
            }
        } catch (IOException e) {
            log.warn("~/bin 심 생성 실패(에이전트가 jspcheck/preview 를 못 찾을 수 있음): {}", e.toString());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

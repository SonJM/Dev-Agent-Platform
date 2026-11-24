package io.github.lib0823.devagent.ops;

import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.boot.Bootstrap;
import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.config.PreviewProperties;
import io.github.lib0823.devagent.docs.KnowledgeSource;
import io.github.lib0823.devagent.gitlab.GitLabClient;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.platform.Os;
import io.github.lib0823.devagent.platform.PortHealth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 설치 점검: 이 호스트에서 플랫폼이 제대로 돌 준비가 됐는지 항목별로 확인한다(플랫폼 탭 "설치 점검" 카드, 새 PC 에 설치했을 때의 길잡이).
 * 각 항목은 ok / warn(동작하지만 일부 기능 제한) / fail(핵심 기능 불가) / off(끔) 와 고치는 방법 한 줄을 낸다.
 * 외부 프로세스·네트워크를 부르므로 결과를 60초 캐시한다. LLM 토큰은 쓰지 않는다. 비밀값은 싣지 않는다.
 */
@Service
public class Doctor {

    private static final long TTL_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final CodingAgent agent;
    private final MessengerService messenger;
    private final GitLabProperties gitlab;
    private final GitLabClient gitlabClient;
    private final SettingService settings;
    private final PreviewProperties preview;
    private final KnowledgeSource knowledge;

    private volatile List<Map<String, Object>> cache;
    private volatile long cachedAt;

    public Doctor(JdbcTemplate jdbc, CodingAgent agent, MessengerService messenger, GitLabProperties gitlab, GitLabClient gitlabClient,
                  SettingService settings, PreviewProperties preview, KnowledgeSource knowledge) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.messenger = messenger;
        this.gitlab = gitlab;
        this.gitlabClient = gitlabClient;
        this.settings = settings;
        this.preview = preview;
        this.knowledge = knowledge;
    }

    public List<Map<String, Object>> checks(boolean refresh) {
        List<Map<String, Object>> c = cache;
        if (!refresh && c != null && System.currentTimeMillis() - cachedAt < TTL_MS) {
            return c;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(runtime());
        out.add(database());
        out.add(codingAgent());
        out.add(claudeLogin());
        out.add(tool("git", "Git", List.of("git", "--version"), "fail", (Os.isWindows() ? "저장소 clone·worktree·푸시에 필요합니다. Git 을 설치하세요." : "저장소 clone·worktree·푸시에 필요합니다. 패키지 관리자로 git 을 설치하세요(예: apt install git).")));
        out.add(messengerCheck());
        out.add(gitlabCheck());
        out.add(workDir());
        out.add(tool("node", "Node.js", List.of("node", "--version"), "warn", "JS 문법 검사(node --check)와 npx 기반 MCP 서버에 필요합니다. 없으면 그 검사만 생략됩니다."));
        if (Os.isWindows()) {
            out.add(gitBash());
        }
        out.add(previewCheck());
        out.add(knowledgeCheck());
        cache = out;
        cachedAt = System.currentTimeMillis();
        return out;
    }

    /** 요약: 가장 나쁜 상태(fail &gt; warn &gt; ok)와 개수. */
    public Map<String, Object> summary(List<Map<String, Object>> checks) {
        long fail = checks.stream().filter(c -> "fail".equals(c.get("status"))).count();
        long warn = checks.stream().filter(c -> "warn".equals(c.get("status"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", fail > 0 ? "fail" : warn > 0 ? "warn" : "ok");
        m.put("fail", fail);
        m.put("warn", warn);
        m.put("total", checks.size());
        return m;
    }

    // ---------------------------------------------------------------- 항목

    private Map<String, Object> runtime() {
        return check("runtime", "실행 환경", "ok",
                Os.name() + " · Java " + Runtime.version().feature() + " · 홈 " + Path.of("").toAbsolutePath(), null);
    }

    private Map<String, Object> database() {
        String mode = "embedded".equals(Bootstrap.dbMode()) ? "내장 PostgreSQL" : "외부 PostgreSQL(DB_URL)";
        try {
            String v = jdbc.queryForObject("SHOW server_version", String.class);
            return check("db", "데이터베이스", "ok", mode + " " + v, null);
        } catch (RuntimeException e) {
            return check("db", "데이터베이스", "fail", mode + " 접속 실패: " + rootMessage(e), "DB_URL/DB_USERNAME/DB_PASSWORD 를 확인하거나, 비워서 내장 DB 를 쓰세요.");
        }
    }

    private Map<String, Object> codingAgent() {
        PortHealth.TestResult r = agent.selfTest();
        return check("agent", "코딩 에이전트(" + agent.provider() + ")", r.ok() ? "ok" : "fail", r.message(),
                r.ok() ? null : "Claude Code 를 설치하세요(npm i -g @anthropic-ai/claude-code 또는 공식 설치 프로그램). 위치가 특이하면 .env 의 CLAUDE_EXE_COMMAND 에 실행 파일 경로를 적습니다.");
    }

    private Map<String, Object> claudeLogin() {
        Path cred = Path.of(System.getProperty("user.home", ""), ".claude", ".credentials.json");
        if (Files.isRegularFile(cred)) {
            return check("login", "Claude 로그인", "ok", "로그인 정보 있음(~/.claude)", null);
        }
        if (!Os.isWindows()) {
            boolean mac = "macOS".equals(Os.name());
            return check("login", "Claude 로그인", "warn", mac ? "로그인 파일이 없습니다(macOS 는 키체인에 저장되므로 정상일 수 있음)"
                            : "로그인 파일이 없습니다(" + cred + ")",
                    mac ? "터미널에서 claude 를 한 번 실행해 로그인돼 있는지 확인하세요. 구독 사용률 게이지는 추정치로 표시됩니다."
                            : "이 앱을 실행하는 계정으로 터미널에서 claude 를 실행해 로그인하세요(서비스로 띄우면 그 계정의 홈을 봅니다). 구독 사용률 게이지는 추정치로 표시됩니다.");
        }
        return check("login", "Claude 로그인", "fail", "로그인 정보가 없습니다", "터미널에서 claude 를 실행해 로그인하세요(구독 계정).");
    }

    private Map<String, Object> messengerCheck() {
        PortHealth h = messenger.health();
        if ("none".equals(h.provider())) {
            return check("messenger", "메신저", "warn", "연결 안 함 — 화면에서만 확인·알림",
                    "메신저 어댑터(MessengerPort 구현)를 추가하고 .env 의 PLATFORM_MESSENGER 에 그 이름을 넣으면 @AI 명령·자기방 알림을 쓸 수 있습니다.");
        }
        String st = PortHealth.OK.equals(h.status()) ? "ok" : PortHealth.DOWN.equals(h.status()) ? "fail" : "warn";
        return check("messenger", "메신저(" + h.provider() + ")", st, h.summary(), "ok".equals(st) ? null : "메신저 계정·주소 설정과 네트워크를 확인하세요.");
    }

    private Map<String, Object> gitlabCheck() {
        if (!gitlab.enabled()) {
            return check("gitlab", "GitLab", "warn", "꺼짐 — 이슈 에이전트·질의를 쓸 수 없습니다",
                    ".env 에 GITLAB_ENABLED=true, GITLAB_URL, GITLAB_TOKEN(개인 액세스 토큰: api·write_repository)을 넣고 재시작하세요.");
        }
        if (blank(gitlab.url()) || blank(gitlab.token())) {
            return check("gitlab", "GitLab", "fail", "주소 또는 토큰이 비어 있습니다", ".env 의 GITLAB_URL, GITLAB_TOKEN 을 채우세요.");
        }
        List<String> projects = settings.getGitLabProjects();
        if (projects.isEmpty()) {
            return check("gitlab", "GitLab", "warn", gitlab.url() + " · 대상 프로젝트 없음", "플랫폼 탭 \"대상 프로젝트\"에서 group/repo 를 추가하세요.");
        }
        try {
            gitlabClient.project(projects.get(0));
            return check("gitlab", "GitLab", "ok", gitlab.url() + " · 대상 프로젝트 " + projects.size() + "개 · 토큰 확인됨", null);
        } catch (RuntimeException e) {
            return check("gitlab", "GitLab", "fail", projects.get(0) + " 조회 실패: " + rootMessage(e), "토큰 권한·만료와 GITLAB_URL 을 확인하세요.");
        }
    }

    /** 작업 디렉터리가 CLAUDE.md 를 가진 디렉터리 아래면 에이전트 세션이 그 지침을 읽어 들인다(토큰 낭비·규칙 혼입). */
    private Map<String, Object> workDir() {
        Path dir = Path.of(blank(gitlab.workDir()) ? "work" : gitlab.workDir()).toAbsolutePath().normalize();
        for (Path p = dir.getParent(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return check("workdir", "작업 디렉터리", "warn", dir + " — 상위(" + p + ")에 CLAUDE.md 가 있습니다",
                        "GITLAB_WORK_DIR 을 다른 프로젝트 밖의 경로로 옮기세요(에이전트 세션이 상위 CLAUDE.md 를 읽습니다).");
            }
        }
        return check("workdir", "작업 디렉터리", "ok", dir.toString(), null);
    }

    private Map<String, Object> gitBash() {
        for (String c : List.of("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe",
                System.getProperty("user.home", "") + "/AppData/Local/Programs/Git/bin/bash.exe")) {
            if (Files.isRegularFile(Path.of(c))) {
                return check("bash", "Git Bash", "ok", c, null);
            }
        }
        return check("bash", "Git Bash", "warn", "찾지 못했습니다", "Claude Code 의 Bash 도구와 검사 스크립트(jscheck·jspcheck)가 Git Bash 를 씁니다. Git for Windows 를 설치하세요.");
    }

    private Map<String, Object> previewCheck() {
        if (preview == null || !preview.enabled()) {
            return check("preview", "로컬 프리뷰(화면 검증)", "off", "꺼짐(PREVIEW_ENABLED=false)", null);
        }
        boolean tools = !blank(preview.catalinaHome()) && Files.isDirectory(Path.of(preview.catalinaHome()))
                && !blank(preview.javaHome()) && Files.isDirectory(Path.of(preview.javaHome()));
        return tools ? check("preview", "로컬 프리뷰(화면 검증)", "ok", "도구 설치됨(" + preview.catalinaHome() + ")", null)
                : check("preview", "로컬 프리뷰(화면 검증)", "warn", "도구(JDK 21·Maven·Tomcat)가 설치돼 있지 않습니다",
                "화면 검증은 Windows 에서만 지원합니다(scripts/setup-preview.ps1). 쓰지 않으면 PREVIEW_ENABLED=false 로 끕니다.");
    }

    private Map<String, Object> knowledgeCheck() {
        PortHealth h = knowledge.health();
        String st = PortHealth.OK.equals(h.status()) ? "ok" : "warn";
        return check("knowledge", "지식(RAG · " + h.provider() + ")", st, h.summary(),
                "ok".equals(st) ? null : "사내 문서 사이트(DOCS_SEARCH_INDEX_URL)에 닿는지 확인하세요. 없어도 다른 기능은 동작합니다(DOCS_ENABLED=false 로 끌 수 있음).");
    }

    private Map<String, Object> tool(String key, String name, List<String> cmd, String missing, String hint) {
        String out = run(cmd);
        return out == null ? check(key, name, missing, "찾지 못했습니다", hint) : check(key, name, "ok", out, null);
    }

    // ---------------------------------------------------------------- 도우미

    /** 명령을 돌려 출력 첫 줄을 돌려준다. 실행 실패·시간 초과·오류 종료면 null. */
    static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.exitValue() != 0 || out.isEmpty()) {
                return null;
            }
            return out.lines().findFirst().orElse("").strip();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static Map<String, Object> check(String key, String name, String status, String detail, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("status", status);
        m.put("detail", detail);
        if (hint != null) {
            m.put("hint", hint);
        }
        return m;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

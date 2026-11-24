package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitLabProperties;
import io.github.lib0823.devagent.config.PreviewProperties;
import io.github.lib0823.devagent.platform.Os;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 검증 단계의 결정적 검사: 코드를 쓴 모델의 보고와 무관하게 봇이 직접 돌린다.
 * <ul>
 *   <li>바뀐 .js → {@code node --check}</li>
 *   <li>바뀐 .jsp → {@code jspcheck}(Jasper 컴파일; 빌드 산출물 target/&lt;war&gt;/WEB-INF/lib 가 있을 때만)</li>
 *   <li>프로젝트 빌드 명령 — gitlab.verify.commands 의 프로젝트별 명령, 없으면 pom.xml → {@code mvn -o -q compile}, gradle → {@code gradlew compileJava}</li>
 * </ul>
 * 리뷰어 프롬프트 생성과 판정 파싱도 여기 둔다. 실행 중 abort 가 요청되면 다음 검사로 넘어가지 않는다.
 */
@Service
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    private final GitLabProperties props;
    private final PreviewProperties preview;

    public VerifyService(GitLabProperties props, PreviewProperties preview) {
        this.props = props;
        this.preview = preview;
    }

    /** 검사 1건의 결과. */
    public record Check(String name, boolean ok, boolean skipped, String output, long elapsedMs) {
        public String status() {
            return skipped ? "SKIP" : ok ? "OK" : "FAIL";
        }
    }

    /** 리뷰어 판정. */
    public enum Verdict { PASS, FAIL, UNKNOWN }

    public GitLabProperties.Verify cfg() {
        GitLabProperties.Verify v = props.verify();
        return v != null ? v : new GitLabProperties.Verify(false, 0, false, "", "", 600000, "", List.of(), 0);
    }

    public boolean enabled() {
        return cfg().enabled();
    }

    // ---------------------------------------------------------------- 결정적 검사

    /** 봇이 직접 돌리는 검사 전부. aborted 가 true 를 돌려주면 남은 검사는 건너뛴다. */
    public List<Check> runChecks(Path worktree, String projectPath, List<String> changedFiles, BooleanSupplier aborted) {
        List<Check> out = new ArrayList<>();
        List<String> js = new ArrayList<>(), jsp = new ArrayList<>();
        for (String f : changedFiles) {
            if (!Files.isRegularFile(worktree.resolve(f))) {
                continue;                                   // 삭제된 파일
            }
            String l = f.toLowerCase(Locale.ROOT);
            if (l.endsWith(".js") && !l.contains("/lib/") && !l.contains(".min.")) {
                js.add(f);
            } else if (l.endsWith(".jsp")) {
                jsp.add(f);
            }
        }
        if (!js.isEmpty() && !aborted.getAsBoolean()) {
            List<String> cmd = new ArrayList<>(List.of("node", "--check"));
            // node --check 는 파일 1개씩: 하나라도 실패하면 FAIL
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            long t0 = System.currentTimeMillis();
            for (String f : js) {
                Check c = exec("node --check " + f, worktree, List.of("node", "--check", f), null, 60000);
                sb.append(c.ok() ? "OK   " : "FAIL ").append(f).append('\n');
                if (!c.ok()) {
                    ok = false;
                    sb.append(indent(c.output())).append('\n');
                }
            }
            out.add(new Check("JS 문법(node --check) " + js.size() + "개", ok, false, sb.toString().strip(), System.currentTimeMillis() - t0));
        }
        if (!jsp.isEmpty() && !aborted.getAsBoolean()) {
            boolean hasLib = false;
            try (var st = Files.list(worktree.resolve("target"))) {
                hasLib = st.anyMatch(d -> Files.isDirectory(d.resolve("WEB-INF").resolve("lib")));
            } catch (IOException ignore) {
                // target 없음
            }
            if (!hasLib || preview == null || !preview.enabled()) {
                out.add(new Check("JSP 컴파일(jspcheck) " + jsp.size() + "개", true, true,
                        "빌드 산출물(target/<war>/WEB-INF/lib)이 없어 건너뜀 — 리뷰어가 코드로 확인", 0));
            } else {
                // Git Bash 는 "C:/..." 형태는 받지만 백슬래시는 이스케이프로 먹는다 → 슬래시로 넘긴다
                List<String> cmd = new ArrayList<>(List.of(bashExe(), Path.of("scripts", "bin", "jspcheck").toAbsolutePath().toString().replace('\\', '/')));
                cmd.addAll(jsp);
                Map<String, String> env = new LinkedHashMap<>();
                env.put("PREVIEW_JAVA_HOME", nz(preview.javaHome()));
                env.put("PREVIEW_CATALINA_HOME", nz(preview.catalinaHome()));
                out.add(exec("JSP 컴파일(jspcheck) " + jsp.size() + "개", worktree, cmd, env, cfg().commandTimeoutMs()));
            }
        }
        if (!aborted.getAsBoolean()) {
            List<String> build = buildCommand(worktree, projectPath);
            if (build == null) {
                out.add(new Check("빌드", true, true, "빌드 명령을 정하지 못해 건너뜀(gitlab.verify.commands 에 지정 가능)", 0));
            } else {
                Map<String, String> env = new LinkedHashMap<>();
                if (preview != null && preview.enabled() && Files.isDirectory(Path.of(nz(preview.javaHome())))) {
                    env.put("JAVA_HOME", preview.javaHome());
                    env.put("PATH", Path.of(preview.javaHome()).resolve("bin") + java.io.File.pathSeparator + System.getenv().getOrDefault("Path", System.getenv().getOrDefault("PATH", "")));
                }
                out.add(exec("빌드: " + String.join(" ", displayCmd(build)), worktree, build, env, cfg().commandTimeoutMs()));
            }
        }
        return out;
    }

    /** 프로젝트별 설정 명령 → 없으면 빌드 도구 자동 감지. null 이면 생략. */
    List<String> buildCommand(Path worktree, String projectPath) {
        String configured = configuredCommand(projectPath);
        if (configured != null) {
            return Os.shellLine(configured);
        }
        if (Files.isRegularFile(worktree.resolve("pom.xml"))) {
            String mvnName = Os.isWindows() ? "mvn.cmd" : "mvn";
            String mvn = preview != null && preview.mavenHome() != null && !preview.mavenHome().isBlank()
                    && Files.isRegularFile(Path.of(preview.mavenHome()).resolve("bin").resolve(mvnName))
                    ? Path.of(preview.mavenHome()).resolve("bin").resolve(mvnName).toString() : "mvn";
            return Os.viaShell(mvn, "-o", "-q", "-B", "-DskipTests", "-Dmaven.test.skip=true", "compile");
        }
        if (!Os.isWindows() && Files.isRegularFile(worktree.resolve("gradlew"))) {
            return List.of("sh", "gradlew", "--no-daemon", "-q", "compileJava");
        }
        if (Os.isWindows() && Files.isRegularFile(worktree.resolve("gradlew.bat"))) {
            return List.of("cmd", "/c", "gradlew.bat", "--no-daemon", "-q", "compileJava");   // 데몬이 남으면 bot.lock 핸들을 물려받아 재시작을 막는다
        }
        if (Files.isRegularFile(worktree.resolve("build.gradle")) || Files.isRegularFile(worktree.resolve("build.gradle.kts"))) {
            return Os.viaShell("gradle", "--no-daemon", "-q", "compileJava");
        }
        return null;
    }

    /** "group/repo=cmd;group/repo2=cmd" 에서 이 프로젝트의 명령. 대소문자 무시, 끝부분(repo) 일치도 허용. */
    String configuredCommand(String projectPath) {
        String all = cfg().commands();
        if (all == null || all.isBlank() || projectPath == null) {
            return null;
        }
        String p = projectPath.toLowerCase(Locale.ROOT);
        String suffixHit = null;
        for (String entry : all.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String cmd = entry.substring(eq + 1).strip();
            if (cmd.isEmpty()) {
                continue;
            }
            if (key.equals(p)) {
                return cmd;                                  // 정확 일치 우선
            }
            if (suffixHit == null && p.endsWith("/" + key)) {
                suffixHit = cmd;
            }
        }
        return suffixHit;
    }

    private Check exec(String name, Path cwd, List<String> cmd, Map<String, String> env, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
            pb.environment().put("GRADLE_OPTS", (pb.environment().getOrDefault("GRADLE_OPTS", "") + " -Dorg.gradle.daemon=false").strip());   // 데몬 금지(bot.lock 상속 방지)
            if (env != null) {
                pb.environment().putAll(env);
            }
            Process p = pb.start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(timeoutMs > 0 ? timeoutMs : 600000, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Check(name, false, false, "타임아웃(" + timeoutMs / 1000 + "s)", System.currentTimeMillis() - t0);
            }
            String o = tail(out.join(), 60);
            return new Check(name, p.exitValue() == 0, false, o.isBlank() ? "(출력 없음, exit " + p.exitValue() + ")" : o, System.currentTimeMillis() - t0);
        } catch (IOException e) {
            return new Check(name, true, true, "실행 불가(" + e.getMessage() + ") — 건너뜀", System.currentTimeMillis() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(name, false, false, "인터럽트", System.currentTimeMillis() - t0);
        }
    }

    public static boolean allOk(List<Check> checks) {
        return checks.stream().allMatch(c -> c.ok() || c.skipped());
    }

    /** 검사 결과를 마크다운(메신저/대시보드 공용)으로. */
    public static String report(List<Check> checks) {
        StringBuilder sb = new StringBuilder();
        for (Check c : checks) {
            sb.append("- [").append(c.status()).append("] ").append(c.name());
            if (!c.skipped()) {
                sb.append(" (").append(c.elapsedMs() / 1000).append("s)");
            }
            if (c.skipped() || !c.ok()) {
                sb.append('\n').append(indent(c.output()));
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    // ---------------------------------------------------------------- 리뷰어

    /** 독립 리뷰어 프롬프트. 이슈·설계·수정 보고·diff·봇 검사 결과를 주고 PASS/FAIL 을 받는다. */
    public String reviewerPrompt(IssueSession s, String issueDescription, String diff, List<Check> checks,
                                 List<String> screenshots, boolean previewUp, int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 이슈를 고친 변경이 이슈를 실제로 해결하는지, 회귀 위험은 없는지 독립적으로 검증하세요(검증 ").append(round).append("회차).\n\n");
        sb.append("## 이슈 ").append(s.ref()).append("\n").append(s.getIssueTitle()).append('\n');
        if (issueDescription != null && !issueDescription.isBlank()) {
            sb.append(abbreviate(issueDescription, 4000)).append('\n');
        }
        if (s.getDesignText() != null && !s.getDesignText().isBlank()) {
            sb.append("\n## 승인된 설계안\n").append(abbreviate(s.getDesignText(), 4000)).append('\n');
        }
        if (s.getChangeSummary() != null && !s.getChangeSummary().isBlank()) {
            sb.append("\n## 수정 세션의 보고(검증 대상 — 그대로 믿지 말 것)\n").append(abbreviate(s.getChangeSummary(), 3000)).append('\n');
        }
        sb.append("\n## 봇이 직접 돌린 검사\n").append(checks.isEmpty() ? "(없음)" : report(checks)).append('\n');
        if (!screenshots.isEmpty()) {
            sb.append("\n## 수정 세션이 남긴 스크린샷(Read 로 볼 수 있음)\n");
            for (String p : screenshots) {
                sb.append("- ").append(p).append('\n');
            }
        }
        sb.append("\n## git diff\n").append(diff == null || diff.isBlank() ? "(변경 없음)" : diff).append('\n');
        sb.append("\n검증 방법:\n")
          .append("1) diff 를 이슈·설계와 대조해 (a) 이슈의 원인이 실제로 제거됐는지 (b) 설계와 다른 변경이 섞였는지 (c) 널/예외/기존 동작 회귀 가능성을 확인.\n")
          .append("2) 필요한 파일은 Read/Grep 으로 직접 열어 보세요. 바뀐 JSP/JS 는 jspcheck/jscheck 를 돌려도 됩니다.\n")
          .append(previewUp ? "3) 프리뷰가 떠 있으니 화면 이슈면 playwright 로 재현 경로를 직접 확인하고 스크린샷을 남기세요.\n"
                            : "3) 프리뷰가 없으므로 화면 동작은 코드로 판단하고 한계를 근거에 적으세요.\n")
          .append("4) 봇 검사에 FAIL 이 있으면 반드시 FAIL 입니다.\n\n")
          .append("출력은 다음 형식만(1200자 이내):\n")
          .append("## 판정\nPASS 또는 FAIL (한 단어)\n")
          .append("## 근거\n(확인한 것과 결과 3~6줄. 스크린샷을 찍었으면 파일명)\n")
          .append("## 수정 요청\n(FAIL 일 때만: 수정 세션이 바로 고칠 수 있게 파일·위치·무엇을 어떻게. PASS 면 '없음')");
        return sb.toString();
    }

    /** '## 판정' 절의 첫 단어로 판정. 형식을 어겼으면 본문에서 PASS/FAIL 단독 줄을 찾고, 그래도 없으면 UNKNOWN. */
    public static Verdict parseVerdict(String text) {
        if (text == null || text.isBlank()) {
            return Verdict.UNKNOWN;
        }
        String sec = IssueTexts.section(text, "판정");
        String probe = sec.equals(text.strip()) ? "" : sec;      // section 은 못 찾으면 전체를 돌려준다
        for (String src : List.of(probe, text)) {
            for (String line : src.lines().map(String::strip).toList()) {
                String u = line.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", " ").strip();
                if (u.startsWith("PASS")) {
                    return Verdict.PASS;
                }
                if (u.startsWith("FAIL")) {
                    return Verdict.FAIL;
                }
            }
            if (!probe.isEmpty()) {
                break;                                       // 판정 절은 있는데 단어가 없음 → 전체에서 찾지 않는다
            }
        }
        return Verdict.UNKNOWN;
    }

    // ---------------------------------------------------------------- 유틸

    /** Git Bash 실행 파일(WSL bash 가 PATH 에 먼저 잡히는 것을 피한다). 없으면 PATH 의 bash. */
    static String bashExe() {
        for (String c : List.of("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe",
                System.getProperty("user.home", "") + "/AppData/Local/Programs/Git/bin/bash.exe")) {
            if (Files.isRegularFile(Path.of(c))) {
                return c;
            }
        }
        return "bash";
    }

    private static List<String> displayCmd(List<String> cmd) {
        List<String> out = new ArrayList<>();
        for (String c : cmd) {
            if (c.equals("cmd") || c.equals("/c")) {
                continue;
            }
            int slash = Math.max(c.lastIndexOf('\\'), c.lastIndexOf('/'));
            out.add(slash >= 0 && c.toLowerCase(Locale.ROOT).endsWith(".cmd") ? c.substring(slash + 1) : c);
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String indent(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.strip().lines().map(l -> "    " + l).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String tail(String s, int lines) {
        if (s == null) {
            return "";
        }
        List<String> all = s.strip().lines().toList();
        int from = Math.max(0, all.size() - lines);
        return (from > 0 ? "… (" + from + "줄 생략)\n" : "") + String.join("\n", all.subList(from, all.size()));
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n… (생략)";
    }
}

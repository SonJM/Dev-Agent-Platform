package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.boot.Bootstrap;
import io.github.lib0823.devagent.config.IssueProperties;
import io.github.lib0823.devagent.issue.IssueAgentService;
import io.github.lib0823.devagent.maintain.MaintainService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.ops.Doctor;
import io.github.lib0823.devagent.platform.Os;
import io.github.lib0823.devagent.platform.RunMode;
import io.github.lib0823.devagent.research.ResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 앱 자체의 상태와 수명: 버전·실행 방식·작업 중 여부({@code /api/system/status}), 설치 점검({@code /api/system/doctor}),
 * 재시작·종료. 재시작은 감시자(systemd)가 다시 띄워 주는 것에 기대므로 감시자 없이 뜬 경우엔 거부한다.
 */
@RestController
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);
    /** 감시자(systemd)가 즉시 다시 띄우는 종료 코드. unit 의 Restart=on-failure 와 짝이다(0 은 다시 뜨지 않는다). */
    private static final int EXIT_RESTART = 10;

    private final ConfigurableApplicationContext ctx;
    private final IssueAgentService issues;
    private final ResearchService research;
    private final MaintainService maintain;
    private final MessengerService messenger;
    private final IssueProperties issueProps;
    private final Doctor doctor;
    private final String version;

    public SystemController(ConfigurableApplicationContext ctx, IssueAgentService issues, ResearchService research, MaintainService maintain,
                            MessengerService messenger,
                            IssueProperties issueProps, Doctor doctor, ObjectProvider<BuildProperties> build) {
        this.ctx = ctx;
        this.issues = issues;
        this.research = research;
        this.maintain = maintain;
        this.messenger = messenger;
        this.issueProps = issueProps;
        this.doctor = doctor;
        BuildProperties b = build.getIfAvailable();
        this.version = b == null ? "dev" : b.getVersion();
    }

    /** 가벼운 상태(화면이 자주 부른다 — 외부 호출 없음). */
    @GetMapping("/api/system/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Dev Agent");
        m.put("version", version);
        m.put("os", Os.name());
        m.put("home", Path.of("").toAbsolutePath().toString());
        m.put("runMode", runMode());
        m.put("supervised", supervised());
        m.put("db", Bootstrap.dbMode());
        m.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("busy", busyCount());
        // 첫 실행 길잡이: 아직 채우지 않은 핵심 설정
        List<String> setup = new ArrayList<>();
        if (!issueProps.enabled()) {
            setup.add("GitHub 연결(이슈 에이전트·질의)");
        }
        if ("none".equals(messenger.provider())) {
            setup.add("메신저 연결(선택)");
        }
        m.put("setup", setup);
        return m;
    }

    @GetMapping("/api/system/doctor")
    public Map<String, Object> doctor(@RequestParam(defaultValue = "false") boolean refresh) {
        List<Map<String, Object>> checks = doctor.checks(refresh);
        Map<String, Object> m = new LinkedHashMap<>(doctor.summary(checks));
        m.put("checks", checks);
        m.put("envFile", Path.of(".env").toAbsolutePath().toString());
        return m;
    }

    /** 재시작. 작업 중인 세션이 있으면 거부(force=true 로 강행 — 그 세션은 실패 처리된다). */
    @PostMapping("/api/system/restart")
    public Map<String, Object> restart(@RequestParam(defaultValue = "false") boolean force) {
        if (!supervised()) {
            return result(false, "감시자 없이 실행 중이라 다시 띄울 수 없습니다. 직접 종료 후 시작하세요.");
        }
        int busy = busyCount();
        if (busy > 0 && !force) {
            return result(false, "작업 중인 세션이 " + busy + "건 있습니다. 끝난 뒤 다시 시도하세요.");
        }
        log.info("화면 요청으로 재시작합니다(작업 중 {}건)", busy);
        exitLater(EXIT_RESTART);
        return result(true, "재시작합니다. 10초쯤 뒤 화면이 다시 연결됩니다.");
    }

    /** 종료(감시자도 함께 끝난다). */
    @PostMapping("/api/system/shutdown")
    public Map<String, Object> shutdown() {
        log.info("요청으로 종료합니다");
        exitLater(0);
        return result(true, "종료합니다.");
    }

    // ---------------------------------------------------------------- 내부

    private int busyCount() {
        return issues.busySessionIds().size() + (research.busy() ? 1 : 0) + (maintain.busy() ? 1 : 0);
    }

    /** systemd | direct — 판별은 {@link RunMode}. */
    private static String runMode() {
        return RunMode.detect();
    }

    private static boolean supervised() {
        return RunMode.supervised(runMode());
    }

    private void exitLater(int code) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(400);          // 응답이 나간 뒤에
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(ctx, () -> code));
        }, "system-exit");
        t.start();
    }

    private static Map<String, Object> result(boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("result", message);
        return m;
    }
}

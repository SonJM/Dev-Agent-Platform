package io.github.lib0823.devagent.platform;

import java.util.Map;

/** 실행 방식 판별: launcher(실행 파일·dev-agent.sh) | systemd | run.cmd(작업 스케줄러 감시 루프) | direct(java -jar 직접). */
public final class RunMode {

    public static final String ENV_RUN_MODE = "DEV_AGENT_RUN_MODE";
    private static final String ENV_LAUNCHER = "DEV_AGENT_LAUNCHER";   // boot/Launcher.ENV_FLAG 와 같은 값(Launcher 는 AWT 를 끌고 와서 직접 참조하지 않는다)
    private static final String ENV_SUPERVISED = "DEV_AGENT_SUPERVISED";
    private static final String SYSTEMD = "systemd";

    private RunMode() {
    }

    public static String detect() {
        return detect(System.getenv());
    }

    static String detect(Map<String, String> env) {
        if ("1".equals(env.get(ENV_LAUNCHER))) {
            return "launcher";
        }
        String runMode = env.get(ENV_RUN_MODE);
        // EnvironmentFile 을 CRLF 로 만들었거나 대문자로 적어도 알아본다(못 알아보면 조용히 direct 가 되어 「재시작」이 거부된다)
        if (runMode != null && SYSTEMD.equalsIgnoreCase(runMode.strip())) {
            return SYSTEMD;
        }
        return "1".equals(env.get(ENV_SUPERVISED)) ? "run.cmd" : "direct";
    }

    /** 앱이 종료 코드 10(재시작)으로 끝났을 때 다시 띄워 주는 감시자가 있는가. */
    public static boolean supervised(String mode) {
        return !"direct".equals(mode);
    }

    /** 종료 때 stop 플래그 파일을 써야 하는가(run.cmd 감시 루프만). systemd 는 종료 코드 0 이면 다시 띄우지 않는다. */
    public static boolean writesStopFlag(String mode) {
        return "run.cmd".equals(mode);
    }
}

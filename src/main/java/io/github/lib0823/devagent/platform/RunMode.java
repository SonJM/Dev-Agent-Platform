package io.github.lib0823.devagent.platform;

import java.util.Map;

/** 실행 방식 판별: systemd(서버) | direct(java -jar 직접). */
public final class RunMode {

    public static final String ENV_RUN_MODE = "DEV_AGENT_RUN_MODE";
    private static final String SYSTEMD = "systemd";

    private RunMode() {
    }

    public static String detect() {
        return detect(System.getenv());
    }

    static String detect(Map<String, String> env) {
        String runMode = env.get(ENV_RUN_MODE);
        // EnvironmentFile 을 CRLF 로 만들었거나 대문자로 적어도 알아본다(못 알아보면 조용히 direct 가 되어 「재시작」이 거부된다)
        if (runMode != null && SYSTEMD.equalsIgnoreCase(runMode.strip())) {
            return SYSTEMD;
        }
        return "direct";
    }

    /** 앱이 종료 코드 10(재시작)으로 끝났을 때 다시 띄워 주는 감시자가 있는가. */
    public static boolean supervised(String mode) {
        return !"direct".equals(mode);
    }
}

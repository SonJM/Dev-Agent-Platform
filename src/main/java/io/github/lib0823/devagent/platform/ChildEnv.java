package io.github.lib0823.devagent.platform;

import java.util.List;
import java.util.Map;

/**
 * 자식 프로세스(Claude CLI·git·검증 빌드·도구 버전 확인)가 앱의 비밀 환경변수를 물려받지 않게 한다.
 * 모든 {@code new ProcessBuilder(..)} 는 {@code start()} 전에 {@link #clean(ProcessBuilder)} 를 거친다.
 * <p>
 * 앱이 자식에게 일부러 넣는 값(git 인증 헤더 {@code GIT_CONFIG_*}, 사용자 정의 MCP 의 {@code ${ENV}} 값 등)은
 * 반드시 이 정리 <b>뒤에</b> 넣는다 — 그래야 남는다. 단 MCP 는 {@link #isSecretKey(String)} 에 든 이름을 참조할 수 없다.
 * <p>
 * 목록은 이름 규칙({@code *_TOKEN} 등)이 아니라 설정 키 기준이다: {@code CLAUDE_CODE_OAUTH_TOKEN}·{@code NPM_TOKEN}
 * 처럼 자식이 정당하게 쓰는 변수까지 지우지 않으려고. 새 비밀 설정 키(메신저 어댑터 토큰 등)가 생기면 여기에 더한다
 * (application.yml 의 password·secret·token 속성은 테스트가 이 목록과 대조한다).
 */
public final class ChildEnv {

    /**
     * 앱 비밀값 환경변수 이름. application.yml 의 자리표시자 이름과, Spring relaxed binding 으로
     * 같은 설정에 직접 닿는 이름({@code SPRING_DATASOURCE_*})을 모두 담는다. 비교는 대소문자를 구분하지 않는다.
     */
    public static final List<String> SECRET_KEYS = List.of(
            "DB_URL", "DB_USERNAME", "DB_PASSWORD",
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "GITHUB_TOKEN", "GITLAB_TOKEN");   // GITLAB_TOKEN: 옛 .env 에 값이 남아 있을 수 있어 자식 프로세스로 새지 않게 일부러 남긴 옛 이름(T-011)

    private ChildEnv() {
    }

    /** 앱 비밀 변수 이름인지(대소문자 무시 — Windows 환경변수는 대소문자를 구분하지 않는다). */
    public static boolean isSecretKey(String name) {
        if (name == null) {
            return false;
        }
        return SECRET_KEYS.stream().anyMatch(k -> k.equalsIgnoreCase(name));
    }

    /** 환경 맵에서 앱 비밀 변수를 지운다(대소문자 변형 포함). */
    public static void scrub(Map<String, String> env) {
        env.keySet().removeIf(ChildEnv::isSecretKey);
    }

    /** ProcessBuilder 의 환경을 정리하고 같은 빌더를 돌려준다. */
    public static ProcessBuilder clean(ProcessBuilder pb) {
        scrub(pb.environment());
        return pb;
    }
}

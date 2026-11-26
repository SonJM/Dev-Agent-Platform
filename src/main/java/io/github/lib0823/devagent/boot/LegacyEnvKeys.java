package io.github.lib0823.devagent.boot;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 업그레이드 뒤 {@code .env} 에 남은 옛 {@code GITLAB_*} 키 감지. 이 키들은 더 읽히지 않아(ISSUE_* 로 개명) 에이전트가 조용히 꺼진다.
 * 키 이름만 다루고 값은 읽지도 출력하지도 않는다.
 */
public final class LegacyEnvKeys {

    static final String PREFIX = "GITLAB_";

    private LegacyEnvKeys() {
    }

    /** 여러 환경(맵)에서 옛 키 이름을 모아 정렬·중복 제거해 돌려준다. */
    @SafeVarargs
    public static List<String> find(Map<String, String>... envs) {
        TreeSet<String> out = new TreeSet<>();
        for (Map<String, String> env : envs) {
            collect(env.keySet(), out);
        }
        return List.copyOf(out);
    }

    private static void collect(Collection<String> names, TreeSet<String> out) {
        for (String n : names) {
            if (n != null && n.startsWith(PREFIX)) {
                out.add(n);
            }
        }
    }

    /** 고치는 방법 한 줄(키 이름만). */
    public static String hint(List<String> keys) {
        return "옛 키(" + String.join(", ", keys) + ")는 더 읽히지 않습니다. .env 에서 GITLAB_* 를 ISSUE_* 로 바꾸세요"
                + "(GITLAB_CREATE_MR 은 ISSUE_CREATE_PR). 대응표는 deploy/README.md 의 '기존 설치의 .env 개명'.";
    }

    /** 기동 로그용 한 줄. 옛 키가 없으면 null. */
    public static String warning(List<String> keys) {
        return keys.isEmpty() ? null : hint(keys);
    }
}

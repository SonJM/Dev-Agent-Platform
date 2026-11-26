package io.github.lib0823.devagent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ChildEnvTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path APP_YML = Path.of("src", "main", "resources", "application.yml");

    @Test
    void removesEverySecretKey() {
        Map<String, String> env = new HashMap<>();
        for (String k : ChildEnv.SECRET_KEYS) {
            env.put(k, "secret-value");
        }
        ChildEnv.scrub(env);
        for (String k : ChildEnv.SECRET_KEYS) {
            assertFalse(env.containsKey(k), k);
        }
    }

    @Test
    void secretKeysCoverAppConfigSecrets() {
        assertTrue(ChildEnv.SECRET_KEYS.containsAll(List.of(
                "DB_URL", "DB_USERNAME", "DB_PASSWORD", "GITHUB_TOKEN", "GITLAB_TOKEN",
                "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")));
    }

    @Test
    void removesSpringRelaxedDatasourceNames() {
        Map<String, String> env = new HashMap<>(Map.of(
                "SPRING_DATASOURCE_URL", "u", "SPRING_DATASOURCE_USERNAME", "n",
                "SPRING_DATASOURCE_PASSWORD", "p", "PATH", "/bin"));
        ChildEnv.scrub(env);
        assertEquals(Map.of("PATH", "/bin"), env);
    }

    @Test
    void removesCaseVariantsOfSecretKeys() {
        Map<String, String> env = new HashMap<>(Map.of("gitlab_token", "t", "Db_Password", "p", "KEEP", "1"));
        ChildEnv.scrub(env);
        assertEquals(Map.of("KEEP", "1"), env);
    }

    @Test
    void isSecretKeyIgnoresCase() {
        assertTrue(ChildEnv.isSecretKey("gitlab_token"));
        assertTrue(ChildEnv.isSecretKey("DB_PASSWORD"));
        assertFalse(ChildEnv.isSecretKey("MY_API_KEY"));
        assertFalse(ChildEnv.isSecretKey(null));
    }

    @Test
    void keepsOrdinaryVariables() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/bin", "HOME", "/h", "JAVA_HOME", "/jdk", "DB_URL", "x"));
        ChildEnv.scrub(env);
        assertEquals("/bin", env.get("PATH"));
        assertEquals("/h", env.get("HOME"));
        assertEquals("/jdk", env.get("JAVA_HOME"));
        assertFalse(env.containsKey("DB_URL"));
    }

    @Test
    void valuesAddedAfterScrubSurvive() {
        Map<String, String> env = new HashMap<>(Map.of("GITLAB_TOKEN", "t"));
        ChildEnv.scrub(env);
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("DB_PASSWORD", "intentional");   // 일부러 넣은 값은 지운 뒤에 넣으면 남는다
        assertEquals("1", env.get("GIT_CONFIG_COUNT"));
        assertEquals("intentional", env.get("DB_PASSWORD"));
        assertFalse(env.containsKey("GITLAB_TOKEN"));
    }

    @Test
    void cleanScrubsProcessBuilderEnvironmentAndReturnsSameBuilder() {
        ProcessBuilder pb = new ProcessBuilder("true");
        pb.environment().put("DB_PASSWORD", "p");
        pb.environment().put("KEEP_ME", "1");
        assertSame(pb, ChildEnv.clean(pb));
        assertFalse(pb.environment().containsKey("DB_PASSWORD"));
        assertEquals("1", pb.environment().get("KEEP_ME"));
    }

    /** application.yml 에서 password·secret·token 속성이 참조하는 환경변수는 전부 비밀 목록에 있어야 한다. */
    @Test
    void everySecretPlaceholderInApplicationYmlIsListed() throws IOException {
        Pattern line = Pattern.compile("^\\s*([\\w.-]+):\\s*\\$\\{([A-Za-z0-9_]+)[:}]");
        Pattern secretProp = Pattern.compile("(?i).*(password|secret|token)$");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String l : Files.readAllLines(APP_YML, StandardCharsets.UTF_8)) {
            Matcher m = line.matcher(l);
            if (m.find() && secretProp.matcher(m.group(1)).matches()) {
                checked++;
                if (!ChildEnv.isSecretKey(m.group(2))) {
                    missing.add(m.group(1) + " -> " + m.group(2));
                }
            }
        }
        assertTrue(checked >= 2, "비밀 속성을 하나도 못 찾았다(정규식 점검 필요)");
        assertTrue(missing.isEmpty(), "ChildEnv.SECRET_KEYS 에 없음: " + missing);
    }

    /** 자식 프로세스를 만드는 모든 소스 파일은 ChildEnv.clean 을 호출해야 한다. */
    @Test
    void everyProcessBuilderFileCallsChildEnvClean() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("ChildEnv.java")) {
                    continue;
                }
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (src.contains("new ProcessBuilder(") && !src.contains("ChildEnv.clean(")) {
                    offenders.add(p.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "ChildEnv.clean 호출 없음: " + offenders);
    }
}

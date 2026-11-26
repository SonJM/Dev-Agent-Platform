package io.github.lib0823.devagent.boot;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring 기동 전 준비(설치 직후 설정 없이도 뜨게 하는 부분). 작업 디렉터리(홈)는 현재 디렉터리다.
 * <ol>
 *   <li>{@code .env} 가 없으면 주석 달린 틀을 만들어 둔다(값은 비어 있음 — 사용자가 채운다).</li>
 *   <li>메신저 어댑터의 기본값은 {@code none}(application.yml) — 화면·에이전트·질의는 메신저 없이 동작한다.</li>
 *   <li>{@code DB_URL} 이 없으면 내장 PostgreSQL 을 띄우고 접속 정보를 시스템 속성으로 넘긴다.</li>
 *   <li>빈 DB 면 {@code db/schema.sql} 로 초기 스키마를 만든다(이후 변경은 각 매퍼의 ensureTable 이 맡는다).</li>
 * </ol>
 * 우선순위는 OS 환경변수 → {@code .env}. 여기서 정한 값은 시스템 속성이라 application.yml 보다 앞선다.
 */
public final class Bootstrap {

    /** 내장 DB 기본 포트(외부 PostgreSQL 기본 5432 와 겹치지 않게). */
    static final int EMBEDDED_PORT = 54329;

    private static volatile String dbMode = "external";

    private Bootstrap() {
    }

    /** DB 방식: embedded(내장) | external(DB_URL 지정). 설치 점검 화면 표시용. */
    public static String dbMode() {
        return dbMode;
    }

    public static void prepare() {
        Path home = Path.of("").toAbsolutePath();
        Path envFile = home.resolve(".env");
        if (!Files.exists(envFile)) {
            writeTemplate(envFile);
        }
        Map<String, String> env = readEnv(envFile);
        String legacyWarning = LegacyEnvKeys.warning(LegacyEnvKeys.find(env, System.getenv()));
        if (legacyWarning != null) {
            say("WARN " + legacyWarning);
        }

        String url = val(env, "DB_URL");
        String user = val(env, "DB_USERNAME");
        String pw = val(env, "DB_PASSWORD");
        if (blank(url)) {
            int port = parseInt(val(env, "DB_EMBEDDED_PORT"), EMBEDDED_PORT);
            EmbeddedDb.start(home.resolve("pgdata-embedded"), home.resolve("pg-bin"), port);
            url = "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
            user = "postgres";
            pw = "";
            dbMode = "embedded";
            System.setProperty("spring.datasource.url", url);
            System.setProperty("spring.datasource.username", user);
            System.setProperty("spring.datasource.password", pw);
        }
        try {
            initSchema(url, user, pw);
        } catch (Exception e) {
            // 외부 DB 가 아직 안 떴을 수 있다 — 스키마 확인은 건너뛰고 Spring 이 접속을 재시도하게 둔다
            say("스키마 확인을 건너뜁니다: " + e.getMessage());
        }
    }

    /** {@code bot_setting} 테이블이 없을 때만(빈 DB) 초기 스키마를 실행한다. DROP 문은 빼고 실행한다. */
    static void initSchema(String url, String user, String pw) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, pw == null ? "" : pw); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT to_regclass('public.bot_setting')")) {
                if (rs.next() && rs.getString(1) != null) {
                    return;
                }
            }
            String sql;
            try (InputStream in = new ClassPathResource("db/schema.sql").getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int n = 0;
            for (String stmt : splitStatements(sql)) {
                st.execute(stmt);
                n++;
            }
            say("빈 DB 에 초기 스키마를 만들었습니다(" + n + "개 문).");
        }
    }

    /** 주석·DROP 문을 걷어 내고 세미콜론으로 나눈다(schema.sql 은 문자열 안에 세미콜론을 쓰지 않는다). */
    static java.util.List<String> splitStatements(String sql) {
        StringBuilder body = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            int i = line.indexOf("--");
            body.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : body.toString().split(";")) {
            String t = s.strip();
            if (!t.isEmpty() && !t.regionMatches(true, 0, "DROP ", 0, 5)) {
                out.add(t);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- .env

    /** {@code KEY=VALUE} 줄만 읽는다(따옴표·뒤따르는 {@code  # 주석} 제거). 파일이 없으면 빈 맵. */
    public static Map<String, String> readEnv(Path file) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return m;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                    continue;
                }
                String v = line.substring(eq + 1).strip();
                int c = v.indexOf(" #");
                if (c >= 0) {
                    v = v.substring(0, c).strip();
                }
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                m.put(line.substring(0, eq).strip(), v);
            }
        } catch (IOException e) {
            say(".env 를 읽지 못했습니다: " + e.getMessage());
        }
        return m;
    }

    static String val(Map<String, String> env, String key) {
        String v = System.getenv(key);
        return blank(v) ? env.get(key) : v;
    }

    private static void writeTemplate(Path envFile) {
        try (InputStream in = new ClassPathResource("dist/env.template").getInputStream()) {
            Files.write(envFile, in.readAllBytes());
            say("설정 파일 틀을 만들었습니다: " + envFile);
        } catch (IOException e) {
            say("설정 파일 틀을 만들지 못했습니다: " + e.getMessage());
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int parseInt(String s, int def) {
        try {
            return blank(s) ? def : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static void say(String msg) {
        System.out.println("[boot] " + msg);
    }
}

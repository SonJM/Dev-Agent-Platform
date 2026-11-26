package io.github.lib0823.devagent.issue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-011: 옛 이름(issue_iid, mr_url, project_id, work_id, kind=GITLAB)의 기존 DB 가 기동 때 새 이름으로 맞춰지는지,
 * 빈 DB 에서도 같은 SQL 이 통하는지 실제 PostgreSQL(내장)로 확인한다. 매퍼 어노테이션의 SQL 을 그대로 실행한다.
 */
class IssueSchemaMigrationTest {

    private static EmbeddedPostgres pg;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    private static String sql(Class<?> mapper, String method) throws Exception {
        return String.join("", mapper.getMethod(method).getAnnotation(Update.class).value());
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void legacyTableIsRenamedAndTrimmed_T011() throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS issue_session");
            st.execute("""
                    CREATE TABLE issue_session (
                        id BIGSERIAL PRIMARY KEY, project_path TEXT NOT NULL, project_id BIGINT NOT NULL,
                        issue_iid BIGINT NOT NULL, issue_title TEXT, issue_url TEXT, default_branch TEXT,
                        state VARCHAR(16) NOT NULL, claude_session_id TEXT, branch_name TEXT, design_text TEXT,
                        change_summary TEXT, commit_sha TEXT, mr_url TEXT, last_error TEXT,
                        feedback_rounds INT NOT NULL DEFAULT 0, work_id VARCHAR(64),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        UNIQUE (project_path, issue_iid))
                    """);
            st.execute("INSERT INTO issue_session (project_path, project_id, issue_iid, state, mr_url) VALUES ('o/r', 1, 7, 'DONE', 'http://pr')");

            st.execute(sql(IssueSessionMapper.class, "ensureTable"));
            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));
            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));   // 멱등

            List<String> cols = columns(c, "issue_session");
            assertTrue(cols.contains("issue_number") && cols.contains("pr_url"), cols.toString());
            assertTrue(cols.stream().noneMatch(n -> List.of("issue_iid", "mr_url", "project_id", "work_id").contains(n)), cols.toString());
            try (ResultSet rs = st.executeQuery("SELECT issue_number, pr_url FROM issue_session")) {
                assertTrue(rs.next());
                assertEquals(7L, rs.getLong(1));
                assertEquals("http://pr", rs.getString(2));
            }
        }
    }

    @Test
    void bothOldAndNewColumnsMergeIntoNewAndDropOld_T011() throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS issue_session");
            st.execute("""
                    CREATE TABLE issue_session (
                        id BIGSERIAL PRIMARY KEY, project_path TEXT NOT NULL,
                        issue_iid BIGINT, issue_number BIGINT, mr_url TEXT, pr_url TEXT,
                        state VARCHAR(16) NOT NULL DEFAULT 'DONE')
                    """);
            st.execute("INSERT INTO issue_session (project_path, issue_iid, issue_number, mr_url, pr_url) VALUES "
                    + "('o/a', 7, NULL, 'http://old', NULL), ('o/b', 8, 80, 'http://old2', 'http://new2')");

            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));
            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));   // 멱등

            List<String> cols = columns(c, "issue_session");
            assertTrue(cols.stream().noneMatch(n -> List.of("issue_iid", "mr_url").contains(n)), cols.toString());
            try (ResultSet rs = st.executeQuery("SELECT string_agg(issue_number || '|' || pr_url, ',' ORDER BY id) FROM issue_session")) {
                assertTrue(rs.next());
                assertEquals("7|http://old,80|http://new2", rs.getString(1));
            }
        }
    }

    @Test
    void newColumnsOnlyIsLeftAloneAndIdempotent_T011() throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS issue_session");
            st.execute(sql(IssueSessionMapper.class, "ensureTable"));
            st.execute("INSERT INTO issue_session (project_path, issue_number, state, pr_url) VALUES ('o/r', 5, 'DONE', 'http://pr')");

            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));
            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));

            try (ResultSet rs = st.executeQuery("SELECT issue_number, pr_url FROM issue_session")) {
                assertTrue(rs.next());
                assertEquals(5L, rs.getLong(1));
                assertEquals("http://pr", rs.getString(2));
            }
        }
    }

    @Test
    void freshTableHasSameColumnsAsMigrated_T011() throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS issue_session");
            st.execute(sql(IssueSessionMapper.class, "ensureTable"));
            st.execute(sql(IssueSessionMapper.class, "ensureColumns"));
            List<String> cols = columns(c, "issue_session");
            assertTrue(cols.containsAll(List.of("issue_number", "pr_url", "verify_rounds", "verify_result", "work_type", "base_branch")), cols.toString());
            assertTrue(cols.stream().noneMatch(n -> List.of("issue_iid", "mr_url", "project_id", "work_id").contains(n)), cols.toString());
        }
    }

    @Test
    void legacyEventKindIsRenamed_T011() throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS issue_event");
            st.execute(sql(IssueEventMapper.class, "ensureTable"));
            st.execute("INSERT INTO issue_event (session_id, kind, message) VALUES (1, 'GITLAB', 'old'), (1, 'GIT', 'keep')");
            st.execute(sql(IssueEventMapper.class, "renameLegacyKind"));
            try (ResultSet rs = st.executeQuery("SELECT string_agg(kind, ',' ORDER BY id) FROM issue_event")) {
                assertTrue(rs.next());
                assertEquals("REMOTE,GIT", rs.getString(1));
            }
        }
    }
}

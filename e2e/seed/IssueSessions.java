// Synthetic issue_session / issue_event seed for the QA instance (T-012 screen checks).
// Run (single-file source mode, PostgreSQL JDBC driver on the class path):
//   java -cp <postgresql.jar> e2e/seed/IssueSessions.java [--dry-run]
// Injection path: SQL against the QA embedded DB (127.0.0.1:54329) - no API creates sessions.
// Scale: 07 section 3 assumes "hundreds" of sessions (300) -> seed 2x = 600. One heavy session
// carries 2000 events ("thousands" per session). Deterministic: fixed Random seed, fixed offsets.
// Only DONE/FAILED/ABORTED/DESIGN_WAIT/REVIEW_WAIT states are used so that no worker picks a row up.
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Random;

public class IssueSessions {
    static final long SEED = 20261126L;
    static final int BULK = 596;
    static final int HEAVY_EVENTS = 2000;
    static final String FAKE = "http://127.0.0.1:9/";
    static final String MAIN = "qa-group/qa-sample";
    static final String[] KINDS = {"STATE", "INFO", "REPLY", "CLAUDE", "TOOL", "TOOL_RESULT", "TEXT", "GIT", "REMOTE", "ERROR"};

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--dry-run")) {
            System.out.println("seed: " + (6 + 11 + HEAVY_EVENTS + BULK) + " rows, boundary 2");
            return;
        }
        int rows = 0;
        int boundary = 0;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:54329/postgres", "postgres", "")) {
            c.setAutoCommit(false);
            // Named sessions (issue numbers 9001-9006) used by the T-012 assertions.
            long wait = session(c, MAIN, 9001, "설계 확인 대기 이슈", "DESIGN_WAIT", null, null, 0);
            long review = session(c, MAIN, 9002, "변경 확인 대기 이슈 PR 있음", "REVIEW_WAIT", "pull/9002", "fix/9002", 1);
            long done = session(c, MAIN, 9003, "완료된 이슈 이벤트 많음", "DONE", "pull/9003", "fix/9003", 0);
            session(c, MAIN, 9004, "실패한 이슈 PR 없음", "FAILED", null, null, 0);
            // Boundary: emoji, very long title, markup characters, empty title.
            session(c, "qa-group/qa-boundary", 9005, "🚀 긴 제목 " + "가".repeat(300) + " <b>&\"'", "DONE", "pull/9005", "fix/9005", 0);
            session(c, "qa-group/qa-boundary", 9006, "", "ABORTED", null, null, 0);
            rows += 6; boundary += 2;
            for (String k : KINDS) event(c, review, k, (k.equals("REMOTE") ? "원격 이벤트 " : "이벤트 ") + k, "상세 " + k);
            event(c, wait, "STATE", "설계 확인 대기", "DESIGN_WAIT");
            rows += KINDS.length + 1;
            Random rng = new Random(SEED);
            for (int i = 0; i < HEAVY_EVENTS; i++) event(c, done, KINDS[rng.nextInt(KINDS.length)], "활동 " + i, null);
            rows += HEAVY_EVENTS;
            for (int i = 1; i <= BULK; i++) {
                boolean pr = rng.nextInt(2) == 0;
                session(c, "qa-group/qa-bulk", i, "대량 세션 " + i, "DONE", pr ? "pull/" + i : null, null, 0);
                rows++;
            }
            c.commit();
        }
        System.out.println("seed: " + rows + " rows, boundary " + boundary);
    }

    static long session(Connection c, String project, long number, String title, String state, String prPath, String branch, int rounds) throws Exception {
        String sql = "INSERT INTO issue_session (project_path, issue_number, issue_title, issue_url, state, branch_name, pr_url, feedback_rounds, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?, now() - interval '2 hour', now() - (? * interval '1 minute')) RETURNING id";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, project); p.setLong(2, number); p.setString(3, title);
            p.setString(4, FAKE + project + "/issues/" + number); p.setString(5, state); p.setString(6, branch);
            p.setString(7, prPath == null ? null : FAKE + project + "/" + prPath); p.setInt(8, rounds); p.setInt(9, (int) (number % 500));
            var rs = p.executeQuery(); rs.next(); return rs.getLong(1);
        }
    }

    static void event(Connection c, long sessionId, String kind, String message, String detail) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO issue_event (session_id, kind, message, detail) VALUES (?,?,?,?)")) {
            p.setLong(1, sessionId); p.setString(2, kind); p.setString(3, message); p.setString(4, detail); p.executeUpdate();
        }
    }
}

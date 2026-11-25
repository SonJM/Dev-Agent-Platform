package io.github.lib0823.devagent.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.agent.CodingAgent.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCliAgentTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path CWD = Path.of("C:/work/repo").toAbsolutePath();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s.replace('\'', '"'));
    }

    @Test
    void verifyStageGetsReadOnlyReviewerNote() {
        String note = ClaudeCliAgent.stageNote(Stage.VERIFY);
        assertTrue(note.contains("독립 리뷰어"));
        assertTrue(note.contains("파일을 수정하지 말고"));
    }

    @Test
    void nonVerifyStagesGetNoReviewerNote() {
        assertEquals("", ClaudeCliAgent.stageNote(Stage.FIX));
        assertEquals("", ClaudeCliAgent.stageNote(Stage.ANALYZE));
    }

    @Test
    void describeToolRelativizesPathsUnderCwd() throws Exception {
        String abs = CWD.resolve("src/main/A.java").toString().replace("\\", "\\\\");
        assertEquals("Read src/main/A.java",
                ClaudeCliAgent.describeTool("Read", json("{'file_path':'" + abs + "'}"), CWD));
        assertEquals("Edit other/B.java",
                ClaudeCliAgent.describeTool("Edit", json("{'file_path':'other/B.java'}"), CWD));
    }

    @Test
    void describeToolSummarizesBashAndGrep() throws Exception {
        assertEquals("Bash gradlew test --tests Foo",
                ClaudeCliAgent.describeTool("Bash", json("{'command':'gradlew test --tests Foo'}"), CWD));
        assertEquals("Grep /userMetric/ in src",
                ClaudeCliAgent.describeTool("Grep", json("{'pattern':'userMetric','path':'src'}"), CWD));
        assertEquals("Glob **/*.java",
                ClaudeCliAgent.describeTool("Glob", json("{'pattern':'**/*.java'}"), CWD));
    }

    @Test
    void describeToolTruncatesLongCommands() throws Exception {
        String cmd = "echo " + "x".repeat(500);
        String d = ClaudeCliAgent.describeTool("Bash", json("{'command':'" + cmd + "'}"), CWD);
        assertTrue(d.length() < 240, d);
        assertTrue(d.endsWith("…"), d);
    }

    // ── T-023: --disallowedTools 조립 ─────────────────────────────

    private static java.util.List<String> deny(Stage stage, boolean maintain) {
        return ClaudeCliAgent.disallowedTools(stage, maintain, true, true);
    }

    private static final java.util.List<String> EDIT_TOOL_NAMES =
            java.util.List.of("Edit", "Write", "MultiEdit", "NotebookEdit");

    @Test
    void t023VerifyStageDeniesEditTools() {
        java.util.List<String> d = deny(Stage.VERIFY, false);
        assertTrue(d.containsAll(EDIT_TOOL_NAMES), d.toString());
    }

    // ── T-024: 읽기 전용 세션 편집 금지, 관리 세션 금지 경로 ─────────

    @Test
    void t024AnalyzeStageDeniesEditTools() {
        assertTrue(deny(Stage.ANALYZE, false).containsAll(EDIT_TOOL_NAMES));
    }

    @Test
    void t024FixAndMaintainSessionsKeepEditTools() {
        for (String tool : EDIT_TOOL_NAMES) {
            assertTrue(!deny(Stage.FIX, false).contains(tool), tool);
            assertTrue(!deny(Stage.FIX, true).contains(tool), tool);
        }
    }

    @Test
    void t024MaintainDeniesCredentialWideningApisOnly() {
        java.util.List<String> d = deny(Stage.FIX, true);
        for (String api : java.util.List.of("api/mcp-servers", "api/agent-subagents", "api/agent-teams",
                "api/agent-models", "api/issues-projects")) {
            assertTrue(d.contains("Bash(curl *" + api + "*)"), api + " " + d);
        }
        assertTrue(d.stream().noneMatch(p -> p.contains("api/skills") || p.contains("api/system/status")), d.toString());
    }

    @Test
    void t023NonMaintainSessionsDenyCurlAndWget() {
        for (Stage s : Stage.values()) {
            java.util.List<String> d = deny(s, false);
            assertTrue(d.contains("Bash(curl:*)"), s + " " + d);
            assertTrue(d.contains("Bash(wget:*)"), s + " " + d);
        }
    }

    @Test
    void t023MaintainDoesNotDenyAllCurlButDeniesStateChangingApis() {
        java.util.List<String> d = deny(Stage.FIX, true);
        assertTrue(!d.contains("Bash(curl:*)"));
        assertTrue(!d.contains("Bash(wget:*)"));
        for (String api : java.util.List.of("api/maintain", "api/system/restart", "api/system/shutdown",
                "api/system/import", "api/issues-reply", "api/issues-create", "api/issues-command")) {
            assertTrue(d.contains("Bash(curl *" + api + "*)"), api + " " + d);
        }
        // 스킬 등록·삭제와 상태 조회는 막히지 않는다
        assertTrue(d.stream().noneMatch(p -> p.contains("api/skills") || p.contains("api/system/status")), d.toString());
    }

    @Test
    void t023AllSessionsDenyEnvFileReadsButNotExamples() {
        for (Stage s : Stage.values()) {
            for (boolean maintain : new boolean[]{false, true}) {
                java.util.List<String> d = deny(s, maintain);
                assertTrue(d.contains("Read(./.env)"), d.toString());
                assertTrue(d.contains("Read(**/.env)"), d.toString());
                assertTrue(d.contains("Read(//**/.env)"), d.toString());
                assertTrue(d.contains("Bash(cat *.env)"), d.toString());
                assertTrue(d.contains("Bash(grep *.env *)"), d.toString());
                assertTrue(d.stream().noneMatch(p -> p.contains("example") || p.contains("template")), d.toString());
            }
        }
    }

    @Test
    void t023ExistingDenialsArePreserved() {
        java.util.List<String> d = deny(Stage.FIX, true);
        assertTrue(d.contains("PowerShell"));
        assertTrue(d.contains("Bash(git push:*)"));
        assertTrue(!deny(Stage.FIX, false).contains("Bash(git push:*)"));
        assertTrue(!d.contains("Agent"));
        java.util.List<String> off = ClaudeCliAgent.disallowedTools(Stage.FIX, false, false, false);
        assertTrue(off.containsAll(java.util.List.of("Agent", "Task", "SendMessage", "TeamCreate", "TeamDelete")));
    }
}

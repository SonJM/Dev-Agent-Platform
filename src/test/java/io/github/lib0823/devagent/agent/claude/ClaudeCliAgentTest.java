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
}

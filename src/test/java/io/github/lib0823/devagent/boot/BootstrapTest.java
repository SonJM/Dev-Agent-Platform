package io.github.lib0823.devagent.boot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapTest {

    @Test
    void readEnvHandlesCommentsQuotesAndBlanks(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".env");
        Files.writeString(f, String.join("\n",
                "# 주석", "", "ISSUE_URL=http://git.example  # 뒤 주석", "QUOTED=\"a b\"", "EMPTY=", "  SPACED = v ", "# OFF=1", "no-equals"),
                StandardCharsets.UTF_8);
        Map<String, String> m = Bootstrap.readEnv(f);
        assertEquals("http://git.example", m.get("ISSUE_URL"));
        assertEquals("a b", m.get("QUOTED"));
        assertEquals("", m.get("EMPTY"));
        assertEquals("v", m.get("SPACED"));
        assertFalse(m.containsKey("OFF"));
        assertTrue(Bootstrap.readEnv(dir.resolve("missing")).isEmpty());
    }

    /** 실제 스키마 파일: DROP 은 빠지고 테이블 생성문은 모두 남아야 한다(빈 DB 초기화가 기존 데이터를 지우지 않게). */
    @Test
    void schemaStatementsSkipDropAndKeepCreates() throws Exception {
        String sql = Files.readString(Path.of("db", "schema.sql"), StandardCharsets.UTF_8);
        List<String> stmts = Bootstrap.splitStatements(sql);
        assertTrue(stmts.stream().noneMatch(s -> s.toUpperCase().startsWith("DROP")), "DROP 문이 남아 있다");
        for (String table : List.of("bot_setting", "issue_session", "research_job", "mcp_server", "agent_skill", "routine")) {
            assertTrue(stmts.stream().anyMatch(s -> s.matches("(?is)CREATE TABLE (IF NOT EXISTS )?" + table + "\\b.*")), table + " 생성문 없음");
        }
        for (String gone : List.of("chat_message", "chat_room_cursor", "chat_summary", "mention_reply", "doc_chunk")) {   // TC-014: 삭제한 테이블
            assertTrue(stmts.stream().noneMatch(s -> s.contains(gone)), gone + " 가 스키마에 남아 있다");
        }
        assertTrue(stmts.stream().noneMatch(s -> s.contains("--")), "주석이 남아 있다");
    }

    @Test
    void envTemplateHasNoValuesForSecrets() {
        Map<String, String> m = Bootstrap.readEnv(Path.of("src", "main", "resources", "dist", "env.template"));
        for (String k : List.of("GITHUB_TOKEN", "ISSUE_PROJECTS")) {
            assertEquals("", m.getOrDefault(k, ""), k + " 에 값이 들어 있다");
        }
        assertEquals("false", m.get("ISSUE_ENABLED"));
    }
}

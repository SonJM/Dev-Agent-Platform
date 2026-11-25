package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitLabProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 검증 단계의 판정 파싱·빌드 명령 선택·검사 실행(node --check 는 실제 실행). */
class VerifyServiceTest {

    @TempDir
    Path wt;

    private static VerifyService svc(String commands) {
        GitLabProperties props = new GitLabProperties(true, "https://gitlab.example", "t", List.of("grp/repo"), "ai-fix",
                1000, 1000, "work", "bot", "bot@x", true, true, true, 0L,
                new GitLabProperties.Agent("opus", "high", 1000, 10, List.of("Read"), List.of("Read"), "", 3000, 0, 0, 0),
                new GitLabProperties.Verify(true, 2, true, "", "medium", 60000, commands, List.of("Read"), 0),
                new GitLabProperties.Convention("develop", "main", "develop", List.of(), List.of(), "#?(\\d{5,7})"), 2);
        return new VerifyService(props);
    }

    @Test
    void verdictParsing() {
        assertEquals(VerifyService.Verdict.PASS, VerifyService.parseVerdict("## 판정\nPASS\n## 근거\n좋음\n## 수정 요청\n없음"));
        assertEquals(VerifyService.Verdict.FAIL, VerifyService.parseVerdict("## 판정\n**FAIL** — 널 체크 누락\n## 근거\n…"));
        assertEquals(VerifyService.Verdict.PASS, VerifyService.parseVerdict("## 판정\n판정: pass\n"));
        assertEquals(VerifyService.Verdict.FAIL, VerifyService.parseVerdict("결론만 말하면\nFAIL\n이유: …"));      // 절 없이 단독 줄
        assertEquals(VerifyService.Verdict.UNKNOWN, VerifyService.parseVerdict("## 판정\n보류\n## 근거\n확인 불가"));
        assertEquals(VerifyService.Verdict.UNKNOWN, VerifyService.parseVerdict(""));
        assertEquals(VerifyService.Verdict.UNKNOWN, VerifyService.parseVerdict("검토했는데 문제를 발견하지 못했습니다."));   // 단어 없음
    }

    @Test
    void buildCommandAutoDetectAndConfigured() throws Exception {
        VerifyService v = svc("");
        assertNull(v.buildCommand(wt, "grp/repo"));
        Files.writeString(wt.resolve("pom.xml"), "<project/>");
        List<String> mvn = v.buildCommand(wt, "grp/repo");
        assertTrue(mvn.get(2).endsWith("mvn") || mvn.get(2).endsWith("mvn.cmd"), mvn.toString());
        assertTrue(mvn.contains("-o") && mvn.contains("compile"), mvn.toString());

        VerifyService c = svc("grp/other=echo x; repo=gradlew.bat test ;grp/repo=npm run lint");
        assertEquals("npm run lint", c.configuredCommand("grp/repo"));          // 정확 일치가 끝부분 일치(repo=)보다 우선
        assertEquals(List.of("cmd", "/c", "npm run lint"), c.buildCommand(wt, "GRP/REPO"));
        assertEquals("gradlew.bat test", svc("repo=gradlew.bat test").configuredCommand("grp/repo"));   // 끝부분 일치
        assertNull(svc("x/y=cmd").configuredCommand("grp/repo"));
    }

    @Test
    void runChecksFlagsBrokenJsAndSkipsBuildWithoutTool() throws Exception {
        Path ok = wt.resolve("src/main/webapp/resources/js/ok.js");
        Files.createDirectories(ok.getParent());
        Files.writeString(ok, "const a = 1;\n");
        Path bad = wt.resolve("src/main/webapp/resources/js/bad.js");
        Files.writeString(bad, "const a = ;\n");
        Files.writeString(wt.resolve("src/main/webapp/resources/js/vendor.min.js"), "garbage((( ");   // min 은 제외
        List<VerifyService.Check> checks = svc("").runChecks(wt, "grp/repo",
                List.of("src/main/webapp/resources/js/ok.js", "src/main/webapp/resources/js/bad.js", "src/main/webapp/resources/js/vendor.min.js", "deleted.js"), () -> false);
        assertEquals(2, checks.size(), checks.toString());
        VerifyService.Check js = checks.get(0);
        if (js.skipped()) {
            return;                                   // node 가 없는 환경이면 건너뜀
        }
        assertFalse(js.ok(), js.output());
        assertTrue(js.output().contains("FAIL src/main/webapp/resources/js/bad.js"), js.output());
        assertTrue(js.output().contains("OK   src/main/webapp/resources/js/ok.js"), js.output());
        assertTrue(checks.get(1).skipped());          // 빌드 도구 없음 → SKIP
        assertFalse(VerifyService.allOk(checks));
        assertTrue(VerifyService.report(checks).contains("[FAIL]"));
    }

    @Test
    void abortStopsRemainingChecks() throws Exception {
        Files.writeString(wt.resolve("pom.xml"), "<project/>");
        List<VerifyService.Check> checks = svc("").runChecks(wt, "grp/repo", List.of(), () -> true);
        assertTrue(checks.isEmpty());
    }
}

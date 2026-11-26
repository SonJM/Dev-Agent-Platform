package io.github.lib0823.devagent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.mcp.McpServer;
import io.github.lib0823.devagent.research.ResearchService;
import io.github.lib0823.devagent.routine.Routine;
import io.github.lib0823.devagent.routine.RoutineService;
import io.github.lib0823.devagent.skill.AgentSkill;
import io.github.lib0823.devagent.skill.SkillService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구성 묶음(번들) 내보내기·가져오기: 스킬 · 사용자 정의 MCP 서버 · 루틴 · 에이전트 설정(모델·서브/팀 에이전트) · 대상 프로젝트를 JSON 한 파일로.
 * 다른 PC 에 같은 구성을 옮기거나 팀에 공유할 때 쓴다. <b>비밀값은 들어가지 않는다</b> — MCP 서버의 인증 헤더는 저장 단계에서 이미
 * {@code ${ENV}} 자리표시자만 허용되고, 계정·토큰(.env)은 번들 대상이 아니다. 가져오기는 같은 이름을 덮어쓰고 없는 것은 그대로 둔다(삭제 없음).
 */
@RestController
public class BundleController {

    static final String FORMAT = "dev-agent-bundle";

    private final SkillService skills;
    private final McpConfigService mcp;
    private final RoutineService routines;
    private final SettingService settings;

    public BundleController(SkillService skills, McpConfigService mcp, RoutineService routines, SettingService settings) {
        this.skills = skills;
        this.mcp = mcp;
        this.routines = routines;
        this.settings = settings;
    }

    @GetMapping("/api/bundle/export")
    public ResponseEntity<Map<String, Object>> export() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("version", 1);
        out.put("exportedAt", OffsetDateTime.now().toString());

        List<Map<String, Object>> sk = new ArrayList<>();
        for (AgentSkill s : skills.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("body", s.getBody());
            m.put("stages", String.join(",", s.stageList()));
            m.put("enabled", s.isEnabled());
            sk.add(m);
        }
        out.put("skills", sk);

        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpServer s : mcp.customServers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("type", s.getType());
            m.put("url", s.getUrl());
            m.put("command", s.getCommand());
            m.put("args", s.getArgs());
            m.put("headers", s.getHeaders());
            m.put("tools", s.getTools());
            m.put("description", s.getDescription());
            m.put("enabled", s.isEnabled());
            servers.add(m);
        }
        out.put("mcpServers", servers);

        List<Map<String, Object>> rs = new ArrayList<>();
        for (Routine r : routines.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.getName());
            m.put("question", r.getQuestion());
            m.put("projects", r.getProjects());
            m.put("model", r.getModel());
            m.put("effort", r.getEffort());
            m.put("verify", r.getVerify());
            m.put("days", r.getDays());
            m.put("runAt", r.getRunAt());
            m.put("notify", r.isNotify());
            m.put("enabled", r.isEnabled());
            rs.add(m);
        }
        out.put("routines", rs);

        SettingService.AgentModels am = settings.getAgentModels();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("designModel", am.designModel());
        st.put("designEffort", am.designEffort());
        st.put("devModel", am.devModel());
        st.put("devEffort", am.devEffort());
        st.put("verifyModel", am.verifyModel());
        st.put("verifyEffort", am.verifyEffort());
        st.put("subagents", settings.getAgentSubagents());
        st.put("teams", settings.getAgentTeams());
        st.put("projects", settings.getIssueProjects());
        out.put("settings", st);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dev-agent-bundle-" + LocalDate.now() + ".json\"")
                .body(out);
    }

    /** 번들 적용. 항목별로 검증해 들어간 것과 거부된 것을 알려 준다(하나가 실패해도 나머지는 적용). */
    @PostMapping("/api/bundle/import")
    public Map<String, Object> importBundle(@RequestBody JsonNode b) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (b == null || !FORMAT.equals(b.path("format").asText())) {
            out.put("ok", false);
            out.put("result", "Dev Agent 구성 묶음 파일이 아닙니다(format 이 " + FORMAT + " 여야 합니다).");
            return out;
        }
        List<String> errors = new ArrayList<>();
        int nSkill = 0;
        for (JsonNode n : b.path("skills")) {
            AgentSkill s = new AgentSkill();
            s.setName(text(n, "name"));
            s.setDescription(text(n, "description"));
            s.setBody(text(n, "body"));
            s.setStages(text(n, "stages"));
            s.setEnabled(n.path("enabled").asBoolean(true));
            try {
                skills.save(s);
                nSkill++;
            } catch (RuntimeException e) {
                errors.add("스킬 " + s.getName() + ": " + e.getMessage());
            }
        }
        int nMcp = 0;
        for (JsonNode n : b.path("mcpServers")) {
            McpServer s = new McpServer();
            s.setName(text(n, "name"));
            s.setType(text(n, "type"));
            s.setUrl(text(n, "url"));
            s.setCommand(text(n, "command"));
            s.setArgs(text(n, "args"));
            s.setHeaders(text(n, "headers"));
            s.setTools(text(n, "tools"));
            s.setDescription(text(n, "description"));
            s.setEnabled(n.path("enabled").asBoolean(true));
            try {
                mcp.saveCustom(s);        // 비밀값이 든 헤더는 여기 검증에서 거부된다
                nMcp++;
            } catch (RuntimeException e) {
                errors.add("MCP 서버 " + s.getName() + ": " + e.getMessage());
            }
        }
        int nRoutine = 0;
        List<Routine> existing = routines.list();
        for (JsonNode n : b.path("routines")) {
            Routine r = new Routine();
            r.setName(text(n, "name"));
            r.setQuestion(text(n, "question"));
            r.setProjects(text(n, "projects"));
            r.setModel(text(n, "model"));
            r.setEffort(text(n, "effort"));
            r.setVerify(n.hasNonNull("verify") ? n.get("verify").asBoolean() : null);
            r.setDays(text(n, "days"));
            r.setRunAt(text(n, "runAt"));
            r.setNotify(n.path("notify").asBoolean(false));
            r.setEnabled(n.path("enabled").asBoolean(true));
            existing.stream().filter(e -> e.getName().equals(r.getName())).findFirst().ifPresent(e -> r.setId(e.getId()));
            try {
                routines.save(r);
                nRoutine++;
            } catch (RuntimeException e) {
                errors.add("루틴 " + r.getName() + ": " + e.getMessage());
            }
        }
        boolean settingsApplied = false;
        JsonNode st = b.path("settings");
        if (st.isObject()) {
            try {
                applySettings(st);
                settingsApplied = true;
            } catch (RuntimeException e) {
                errors.add("설정: " + e.getMessage());
            }
        }
        out.put("ok", errors.isEmpty());
        out.put("result", "가져오기 완료 — 스킬 " + nSkill + " · MCP 서버 " + nMcp + " · 루틴 " + nRoutine
                + (settingsApplied ? " · 에이전트 설정" : "") + (errors.isEmpty() ? "" : " (거부 " + errors.size() + "건)"));
        out.put("errors", errors);
        return out;
    }

    private void applySettings(JsonNode st) {
        SettingService.AgentModels cur = settings.getAgentModels();
        SettingService.AgentModels next = new SettingService.AgentModels(
                model(st, "designModel", cur.designModel()), effort(st, "designEffort", cur.designEffort()),
                model(st, "devModel", cur.devModel()), effort(st, "devEffort", cur.devEffort()),
                model(st, "verifyModel", cur.verifyModel()), effort(st, "verifyEffort", cur.verifyEffort()));
        settings.setAgentModels(next);
        if (st.hasNonNull("subagents")) {
            settings.setAgentSubagents(st.get("subagents").asBoolean());
        }
        if (st.hasNonNull("teams")) {
            settings.setAgentTeams(st.get("teams").asBoolean());
        }
        // 대상 프로젝트는 합친다(이 PC 에만 있는 프로젝트를 지우지 않는다). 형식만 확인 — 존재 여부는 스캔 때 드러난다.
        if (st.path("projects").isArray()) {
            List<String> merged = new ArrayList<>(settings.getIssueProjects());
            for (JsonNode p : st.path("projects")) {
                String path = p.asText("").strip();
                if (path.matches("[\\w.-]+(/[\\w.-]+)+") && !merged.contains(path)) {
                    merged.add(path);
                }
            }
            settings.setIssueProjects(merged);
        }
    }

    private static String model(JsonNode st, String key, String cur) {
        String v = st.path(key).asText("").strip().toLowerCase();
        if (v.isEmpty()) {
            return cur;
        }
        if (!ResearchService.MODELS.contains(v)) {
            throw new IllegalArgumentException("모델 값이 올바르지 않습니다: " + v);
        }
        return v;
    }

    private static String effort(JsonNode st, String key, String cur) {
        String v = st.path(key).asText("").strip().toLowerCase();
        if (v.isEmpty()) {
            return cur;
        }
        if (!ResearchService.EFFORTS.contains(v)) {
            throw new IllegalArgumentException("노력 값이 올바르지 않습니다: " + v);
        }
        return v;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }
}

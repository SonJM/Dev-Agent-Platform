package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.skill.AgentSkill;
import io.github.lib0823.devagent.skill.SkillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 플랫폼 스킬 등록·수정·삭제 API (localhost 전용). 저장 즉시 다음 에이전트 실행부터 반영된다. */
@RestController
public class SkillController {

    private final SkillService skills;

    public SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping("/api/skills")
    public Map<String, Object> list() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentSkill s : skills.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("body", s.getBody());
            m.put("stages", s.stageList());
            m.put("enabled", s.isEnabled());
            m.put("chars", s.getBody().length());
            m.put("updatedAt", s.getUpdatedAt());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", AgentSkill.STAGES);
        out.put("skills", list);
        return out;
    }

    @PostMapping("/api/skills")
    public Map<String, Object> save(@RequestParam String name, @RequestParam String description, @RequestParam String body,
                                    @RequestParam(required = false) String stages,
                                    @RequestParam(defaultValue = "true") boolean enabled) {
        AgentSkill s = new AgentSkill();
        s.setName(name);
        s.setDescription(description);
        s.setBody(body);
        s.setStages(stages);
        s.setEnabled(enabled);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            skills.save(s);
            out.put("ok", true);
            out.put("result", "스킬 '" + s.getName() + "' 저장 — 다음 실행부터 반영");
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("result", e.getMessage());
        }
        return out;
    }

    /** 지워서 없는 기본 스킬만 다시 넣는다(있는 스킬은 고친 내용 그대로). */
    @PostMapping("/api/skills/defaults")
    public Map<String, Object> restoreDefaults() {
        int n = skills.restoreDefaults();
        return Map.of("ok", true, "result", n > 0 ? "기본 스킬 " + n + "개를 다시 넣었습니다" : "기본 스킬이 이미 모두 있습니다(고친 내용은 그대로 둡니다)");
    }

    @PostMapping("/api/skills/delete")
    public Map<String, Object> delete(@RequestParam String name) {
        boolean removed = skills.delete(name);
        return Map.of("ok", removed, "result", removed ? "스킬 '" + name + "' 삭제" : "'" + name + "' 스킬이 없습니다");
    }
}

package io.github.lib0823.devagent.skill;

import jakarta.annotation.PostConstruct;
import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.config.IssueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 플랫폼 스킬 관리(등록·수정·삭제)와 프롬프트 주입.
 * <p>
 * Claude Code 의 네이티브 스킬({@code .claude/skills})을 쓰지 않는 이유: 헤드리스 실행은 콜드스타트를 줄이려고 스킬 로딩을 끄고 있고,
 * 네이티브 스킬은 Claude Code 전용이라 다른 LLM 구현체로 바꾸면 사라진다. 그래서 스킬을 플랫폼이 갖고 시스템 프롬프트로 싣는다 —
 * 코딩 에이전트 구현체는 실행 때 {@link #promptNote(String)} 를 시스템 프롬프트에 덧붙이기만 하면 된다.
 * <p>
 * 저장은 DB({@code agent_skill})가 원본이고, 긴 본문을 에이전트가 읽을 수 있게 {@code work/skills/<name>/SKILL.md} 로도 내보낸다.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);
    /** 이 길이 이하의 본문은 프롬프트에 그대로 싣는다. 넘으면 설명만 싣고 파일을 읽게 한다. */
    static final int INLINE_MAX = 1500;
    static final int BODY_MAX = 40_000;
    /** 프롬프트에 그대로 싣는 스킬 본문의 합계 상한. 넘는 스킬은 파일 참조로 돌린다. */
    static final int NOTE_MAX = 6_000;

    /** 기본 스킬을 이미 심었다는 표시(bot_setting). 있으면 다시 심지 않는다 — 사용자가 지운 스킬이 재시작 때 되살아나지 않게. */
    static final String SEEDED_KEY = "skills_seeded";

    private final AgentSkillMapper mapper;
    private final SettingService settings;
    private final Path dir;
    private volatile List<AgentSkill> cache = List.of();

    public SkillService(AgentSkillMapper mapper, IssueProperties issueProps, SettingService settings) {
        this.mapper = mapper;
        this.settings = settings;
        this.dir = Path.of(issueProps.workDir() == null || issueProps.workDir().isBlank() ? "work" : issueProps.workDir()).toAbsolutePath().resolve("skills");
    }

    @PostConstruct
    void init() {
        try {
            mapper.ensureTable();
            seedDefaults();
            reload();
        } catch (RuntimeException e) {
            log.warn("스킬 테이블 준비 실패(스킬 없이 진행): {}", e.toString());
        }
    }

    /**
     * 기본 스킬({@link DefaultSkills})을 설치당 한 번만 심는다. 같은 이름이 이미 있으면 건드리지 않고,
     * 심은 뒤에는 표시를 남겨 사용자가 고치거나 지운 것을 되돌리지 않는다.
     */
    void seedDefaults() {
        if (settings.getRaw(SEEDED_KEY) != null) {
            return;
        }
        int n = insertDefaults();
        settings.setRaw(SEEDED_KEY, "1");
        log.info("기본 스킬 {}개를 넣었습니다(플랫폼 탭 스킬 카드에서 수정·끄기·삭제 가능)", n);
    }

    /** 화면의 「기본 스킬 다시 넣기」: 지워서 없는 기본 스킬만 다시 넣는다(있는 것은 그대로). 넣은 개수. */
    public int restoreDefaults() {
        int n = insertDefaults();
        settings.setRaw(SEEDED_KEY, "1");
        reload();
        return n;
    }

    private int insertDefaults() {
        int n = 0;
        for (AgentSkill s : DefaultSkills.all()) {
            if (validate(s) == null) {
                n += mapper.insertIfAbsent(s);
            }
        }
        return n;
    }

    public List<AgentSkill> list() {
        return cache;
    }

    /** 등록·수정(이름이 같으면 덮어씀). 검증 실패는 IllegalArgumentException. 다음 에이전트 실행부터 반영. */
    public void save(AgentSkill s) {
        String err = validate(s);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        mapper.upsert(s);
        reload();
    }

    public boolean delete(String name) {
        boolean removed = mapper.delete(name) > 0;
        reload();
        return removed;
    }

    static String validate(AgentSkill s) {
        String name = s.getName() == null ? "" : s.getName().strip();
        if (!name.matches("[a-z0-9][a-z0-9-]{1,46}")) {
            return "이름은 영소문자·숫자·하이픈 2~47자여야 합니다(예: commit-convention).";
        }
        s.setName(name);
        if (s.getDescription() == null || s.getDescription().isBlank()) {
            return "설명을 입력하세요(언제 쓰는 스킬인지 한 줄 — 에이전트가 이 문장을 보고 쓸지 정합니다).";
        }
        s.setDescription(s.getDescription().strip().replaceAll("\\s+", " "));
        if (s.getDescription().length() > 300) {
            return "설명은 300자 이내로 쓰세요.";
        }
        if (s.getBody() == null || s.getBody().isBlank()) {
            return "본문(지침)을 입력하세요.";
        }
        if (s.getBody().length() > BODY_MAX) {
            return "본문이 너무 깁니다(" + BODY_MAX + "자 이내).";
        }
        s.setBody(s.getBody().strip());
        s.setStages(String.join(",", s.stageList()));
        return null;
    }

    /**
     * 해당 단계에 적용되는 스킬을 시스템 프롬프트용 블록으로. 없으면 빈 문자열.
     *
     * @param stageKey analyze | fix | verify | research | maintain
     */
    public String promptNote(String stageKey) {
        List<AgentSkill> skills = cache.stream().filter(s -> s.appliesTo(stageKey)).toList();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("플랫폼 스킬(관리자가 등록한 작업 지침). 설명이 지금 작업에 해당하면 그 지침을 따르세요.");
        for (AgentSkill s : skills) {
            sb.append("\n\n### 스킬: ").append(s.getName()).append("\n언제: ").append(s.getDescription());
            // 시스템 프롬프트는 명령행 인자로 넘어가므로(Windows 32K 제한) 전체 분량도 묶는다
            if (s.getBody().length() <= INLINE_MAX && sb.length() + s.getBody().length() <= NOTE_MAX) {
                sb.append("\n").append(s.getBody());
            } else {
                sb.append("\n지침 전문은 길어서 파일에 있습니다. 이 스킬이 필요하면 먼저 Read 로 읽으세요: ")
                        .append(file(s.getName()).toString().replace('\\', '/'));
            }
        }
        return sb.toString();
    }

    public Path file(String name) {
        return dir.resolve(name).resolve("SKILL.md");
    }

    /** DB → 캐시 → 파일 동기화(없어진 스킬의 폴더는 지운다). */
    private synchronized void reload() {
        List<AgentSkill> all = mapper.findAll();
        cache = all;
        try {
            Files.createDirectories(dir);
            for (AgentSkill s : all) {
                Path f = file(s.getName());
                Files.createDirectories(f.getParent());
                String md = "---\nname: " + s.getName() + "\ndescription: " + s.getDescription() + "\n---\n\n" + s.getBody() + "\n";
                Files.writeString(f, md, StandardCharsets.UTF_8);
            }
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : children.toList()) {
                    String n = child.getFileName().toString();
                    if (Files.isDirectory(child) && all.stream().noneMatch(s -> s.getName().equals(n))) {
                        Files.deleteIfExists(child.resolve("SKILL.md"));
                        Files.deleteIfExists(child);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("스킬 파일 동기화 실패(프롬프트 주입은 계속): {}", e.toString());
        }
    }
}

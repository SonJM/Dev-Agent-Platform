package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.config.GitLabProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 런타임 설정 저장소. DB(bot_setting)가 우선, 없으면 application.yml/.env 값으로 폴백.
 * 관리 페이지에서 바꾸면 DB에 저장되어 재시작 없이 다음 폴링부터 적용된다.
 */
@Service
public class SettingService {

    private static final String KEY_GITLAB_PROJECTS = "gitlab_projects";
    private static final String KEY_DESIGN_MODEL = "agent_design_model";
    private static final String KEY_DESIGN_EFFORT = "agent_design_effort";
    private static final String KEY_DEV_MODEL = "agent_dev_model";
    private static final String KEY_DEV_EFFORT = "agent_dev_effort";
    private static final String KEY_VERIFY_MODEL = "agent_verify_model";
    private static final String KEY_VERIFY_EFFORT = "agent_verify_effort";

    private final SettingMapper mapper;
    private final GitLabProperties gitlab;
    /**
     * bot_setting 메모리 캐시. 폴링이 설정을 자주 읽어 DB 조회가 수백 번 나가던 것을 없앤다(11-16 실측: 로그의 25%).
     * 이 서비스가 유일한 쓰기 경로라 쓰기 때 갱신하면 된다. DB 를 직접 고쳤으면 재시작.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private String cached(String key) {
        return cache.computeIfAbsent(key, k -> java.util.Optional.ofNullable(mapper.get(k))).orElse(null);
    }

    private void store(String key, String value) {
        mapper.upsert(key, value);
        cache.put(key, java.util.Optional.ofNullable(value));
    }

    public SettingService(SettingMapper mapper, GitLabProperties gitlab) {
        this.mapper = mapper;
        this.gitlab = gitlab;
    }

    /** 이슈 에이전트 대상 프로젝트("group/repo") 목록. DB 값이 있으면 그것을, 없으면 GITLAB_PROJECTS 폴백. 관리 페이지에서 추가/삭제. */
    public List<String> getGitLabProjects() {
        String v = cached(KEY_GITLAB_PROJECTS);
        if (v != null) {
            return new ArrayList<>(parse(v));
        }
        List<String> fallback = gitlab.projects();
        return fallback == null ? new ArrayList<>() : new ArrayList<>(parse(String.join(",", fallback)));
    }

    private static final String KEY_SUBAGENTS = "agent_subagents";
    private static final String KEY_TEAMS = "agent_teams";

    /** 이슈 에이전트가 서브 에이전트(Agent/Task)·팀 메시지를 쓰게 할지. 기본 true(사용자 결정) — 끄면 다음 실행부터 도구 금지. 병렬로 토큰을 많이 쓴다. */
    public boolean getAgentSubagents() {
        return !"false".equalsIgnoreCase(nz(cached(KEY_SUBAGENTS), "true"));
    }

    public void setAgentSubagents(boolean on) {
        store(KEY_SUBAGENTS, String.valueOf(on));
    }

    /** 팀 에이전트(Claude Code 실험 기능 CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS) 허용 여부. 기본 허용. 끄면 자식 프로세스 env 를 0 으로 덮고 팀 메시지 도구를 금지. */
    public boolean getAgentTeams() {
        return !"false".equalsIgnoreCase(nz(cached(KEY_TEAMS), "true"));
    }

    public void setAgentTeams(boolean on) {
        store(KEY_TEAMS, String.valueOf(on));
    }

    /** 임의 키 읽기(없으면 null) — 다른 서비스가 작은 상태값을 보관할 때(예: 사용량 한도 학습값). */
    public String getRaw(String key) {
        return cached(key);
    }

    public void setRaw(String key, String value) {
        store(key, value);
    }

    public void setGitLabProjects(List<String> paths) {
        store(KEY_GITLAB_PROJECTS, join(paths));
    }

    /** 이슈 에이전트 모델: 개발(분석·수정)과 검증(독립 리뷰어)을 따로. DB 값이 있으면 그것을, 없으면 gitlab.agent / gitlab.verify 설정. */
    /** 단계별 모델: 기획(분석·재설계) / 개발(수정·재수정) / 테스트(독립 리뷰어). 모델이 없으면 실행기가 opus 로 대체한다. */
    public record AgentModels(String designModel, String designEffort, String devModel, String devEffort,
                              String verifyModel, String verifyEffort) {
    }

    public AgentModels getAgentModels() {
        String devModel = nz(gitlab.agent() == null ? null : gitlab.agent().model(), "opus");
        String devEffort = nz(gitlab.agent() == null ? null : gitlab.agent().effort(), "high");
        String vModel = gitlab.verify() == null ? devModel : gitlab.verify().modelOr(devModel);
        String vEffort = gitlab.verify() == null ? devEffort : gitlab.verify().effortOr(devEffort);
        String dModel = nz(cached(KEY_DEV_MODEL), devModel);   // 기획 기본값 = 개발 설정(예전 DB 에 기획 키가 없을 때)
        String dEffort = nz(cached(KEY_DEV_EFFORT), devEffort);
        return new AgentModels(
                nz(cached(KEY_DESIGN_MODEL), dModel), nz(cached(KEY_DESIGN_EFFORT), dEffort),
                dModel, dEffort,
                nz(cached(KEY_VERIFY_MODEL), vModel), nz(cached(KEY_VERIFY_EFFORT), vEffort));
    }

    /** 값 검증(모델명·노력 단계 화이트리스트)은 호출자(AdminController)가 한다. */
    public void setAgentModels(AgentModels m) {
        store(KEY_DESIGN_MODEL, m.designModel().strip());
        store(KEY_DESIGN_EFFORT, m.designEffort().strip());
        store(KEY_DEV_MODEL, m.devModel().strip());
        store(KEY_DEV_EFFORT, m.devEffort().strip());
        store(KEY_VERIFY_MODEL, m.verifyModel().strip());
        store(KEY_VERIFY_EFFORT, m.verifyEffort().strip());
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private static final String KEY_NOTIFY_OFF = "messenger_notify_off";

    /**
     * 메신저(자기방)로 보내지 않기로 한 플랫폼 알림 유형(저장된 그대로). 기본은 빈 집합 = 전부 보냄.
     * 예전 설치에 남은 없어진 유형(summary·mention)이 섞여 있을 수 있으나 읽는 쪽은 유형별 contains 만 보므로 무해하다.
     */
    public Set<String> getMessengerNotifyOff() {
        String v = cached(KEY_NOTIFY_OFF);
        return v == null ? new LinkedHashSet<>() : parse(v);
    }

    /** 알림 유형 하나를 켜거나 끈다. 저장할 때 {@code known}(현재 유형 키)에 없는 예전 유형은 버린다. */
    public void setMessengerNotify(String kind, boolean on, Set<String> known) {
        Set<String> off = getMessengerNotifyOff();
        off.retainAll(known);
        if (on) {
            off.remove(kind);
        } else {
            off.add(kind);
        }
        store(KEY_NOTIFY_OFF, String.join(",", off));
    }

    private String join(List<String> keys) {
        return keys == null ? "" : keys.stream()
                .map(String::trim).filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.joining(","));
    }

    private Set<String> parse(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}

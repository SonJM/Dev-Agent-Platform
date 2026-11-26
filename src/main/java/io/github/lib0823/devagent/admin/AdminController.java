package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.issue.IssueAgentService;
import io.github.lib0823.devagent.messenger.MessengerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 로컬 관리 API (localhost 전용). 이슈 에이전트 현황·설정, 메신저 알림 유형 켜기/끄기.
 */
@RestController
public class AdminController {

    private final MessengerService client;
    private final SettingService settings;
    private final IssueAgentService issueAgent;

    public AdminController(MessengerService client, SettingService settings, IssueAgentService issueAgent) {
        this.client = client;
        this.settings = settings;
        this.issueAgent = issueAgent;
    }

    /** GitHub 이슈 에이전트 현황: 활성/다음 대기 + 최근 세션 (localhost 전용). */
    @GetMapping("/api/issues")
    public Map<String, Object> issues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", issueAgent.enabled());
        m.put("status", issueAgent.enabled() ? issueAgent.statusText() : "disabled");
        m.put("recent", issueAgent.enabled() ? issueAgent.recent(20) : List.of());
        return m;
    }

    /** 이슈 에이전트 명령 (메신저 "이슈 ..." 명령과 동일). 예: cmd=상태 / cmd=group/repo#12 / cmd=중단 */
    @PostMapping("/api/issues-command")
    public Map<String, Object> issuesCommand(@RequestParam String cmd) {
        return Map.of("result", issueAgent.command(cmd));
    }

    /** 이슈 에이전트 대상 프로젝트 목록(DB 우선, 없으면 ISSUE_PROJECTS). 각 항목에 GitHub 조회 결과(id/webUrl 또는 error). */
    @GetMapping("/api/issues-projects")
    public Map<String, Object> issuesProjects() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", issueAgent.enabled());
        m.put("projects", issueAgent.enabled() ? issueAgent.projectList() : List.of());
        return m;
    }

    private static final Set<String> MODELS = Set.of("fable", "opus", "sonnet", "haiku");
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high");

    /** 이슈 에이전트 모델(개발/검증 따로). DB 우선, 없으면 .env/yml. */
    @GetMapping("/api/agent-models")
    public Map<String, Object> agentModels() {
        SettingService.AgentModels m = settings.getAgentModels();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("designModel", m.designModel());
        out.put("designEffort", m.designEffort());
        out.put("devModel", m.devModel());
        out.put("devEffort", m.devEffort());
        out.put("verifyModel", m.verifyModel());
        out.put("verifyEffort", m.verifyEffort());
        out.put("models", List.of("fable", "opus", "sonnet", "haiku"));
        out.put("efforts", List.of("low", "medium", "high"));
        out.put("fallback", "opus");                   // 선택한 모델을 쓸 수 없으면(권한 없음) 실행기가 이 모델로 대체
        out.put("subagents", settings.getAgentSubagents());
        out.put("teams", settings.getAgentTeams());
        return out;
    }

    /** 서브 에이전트 허용 토글(다음 Claude 실행부터). */
    @PostMapping("/api/agent-subagents")
    public Map<String, Object> agentSubagents(@RequestParam boolean on) {
        settings.setAgentSubagents(on);
        return Map.of("result", on ? "서브 에이전트 허용 — 병렬로 토큰을 많이 쓸 수 있습니다 (다음 실행부터)" : "서브 에이전트 금지 (다음 실행부터)");
    }

    /** 팀 에이전트(실험 기능) 허용/금지. 서브 에이전트와 별개 — 서브는 두고 팀만 끌 수 있다. */
    @PostMapping("/api/agent-teams")
    public Map<String, Object> agentTeams(@RequestParam boolean on) {
        settings.setAgentTeams(on);
        return Map.of("result", on ? "팀 에이전트 허용 — 팀원 세션이 병렬로 토큰을 씁니다 (다음 실행부터)" : "팀 에이전트 금지 (다음 실행부터)");
    }

    /** 모델 저장(재시작 불필요, 다음 Claude 실행부터 적용). 진행 중인 실행은 그대로 끝난다. 기획 값이 없으면 개발 값을 따른다. */
    @PostMapping("/api/agent-models")
    public Map<String, Object> agentModelsSave(@RequestParam String devModel, @RequestParam String devEffort,
                                               @RequestParam String verifyModel, @RequestParam String verifyEffort,
                                               @RequestParam(required = false) String designModel,
                                               @RequestParam(required = false) String designEffort) {
        if (designModel == null || designModel.isBlank()) {
            designModel = devModel;
        }
        if (designEffort == null || designEffort.isBlank()) {
            designEffort = devEffort;
        }
        for (String m : List.of(designModel, devModel, verifyModel)) {
            if (!MODELS.contains(m.strip().toLowerCase())) {
                return Map.of("result", "모델은 " + MODELS + " 중 하나여야 합니다: " + m);
            }
        }
        for (String e : List.of(designEffort, devEffort, verifyEffort)) {
            if (!EFFORTS.contains(e.strip().toLowerCase())) {
                return Map.of("result", "노력 단계는 " + EFFORTS + " 중 하나여야 합니다: " + e);
            }
        }
        settings.setAgentModels(new SettingService.AgentModels(
                designModel.strip().toLowerCase(), designEffort.strip().toLowerCase(),
                devModel.strip().toLowerCase(), devEffort.strip().toLowerCase(),
                verifyModel.strip().toLowerCase(), verifyEffort.strip().toLowerCase()));
        return Map.of("result", "저장했습니다: 기획 " + designModel + "/" + designEffort + " · 개발 " + devModel + "/" + devEffort
                + " · 테스트 " + verifyModel + "/" + verifyEffort + " (다음 실행부터 적용, 사용 불가 모델은 opus 로 대체)");
    }

    /** 대상 프로젝트 추가(group/repo). GitHub 에서 조회해 검증·정규화 후 저장, 즉시 스캔. 재시작 불필요. */
    @PostMapping("/api/issues-projects/add")
    public Map<String, Object> issuesProjectAdd(@RequestParam String path) {
        return Map.of("result", issueAgent.addProject(path));
    }

    /** 대상 프로젝트 삭제. 진행 중 세션은 유지된다. */
    @PostMapping("/api/issues-projects/remove")
    public Map<String, Object> issuesProjectRemove(@RequestParam String path) {
        return Map.of("result", issueAgent.removeProject(path));
    }

    /**
     * 확인 대기 중인 세션에 답변 주입 (자기방에 "진행"/"중단"/피드백을 쓰는 것과 동일).
     * 본문은 form 파라미터로도 받는다(긴 피드백이 URL 길이 제한에 걸리지 않도록). sessionId 가 있으면 그 세션이 대기 중일 때만 적용.
     */
    @PostMapping("/api/issues-reply")
    public Map<String, Object> issuesReply(@RequestParam String text, @RequestParam(required = false) Long sessionId,
                                           @RequestParam(required = false) String note) {
        return Map.of("result", issueAgent.injectReply(text, sessionId, note));
    }

    /** 대상 프로젝트의 열린 이슈(최근 갱신 순 50건) + 에이전트 세션 상태. 에이전트 화면 "이슈 목록" 창이 열 때만 부른다. */
    @GetMapping("/api/issues-list")
    public Map<String, Object> issuesList(@RequestParam String project) {
        return issueAgent.openIssueList(project);
    }

    /**
     * GitHub 이슈 등록(에이전트 화면 "새 이슈"). project 는 대상 프로젝트만. start=true 면 스캔 라벨을 붙이고 바로 큐에 넣는다.
     * GitHub 에 글을 쓰는 요청이라 다른 사이트 페이지에서 온 것은 막는다.
     */
    @PostMapping("/api/issues-create")
    public Map<String, Object> issuesCreate(@RequestParam String project, @RequestParam String title,
                                            @RequestParam(defaultValue = "") String description,
                                            @RequestParam(defaultValue = "true") boolean start) {
        return issueAgent.createIssue(project, title, description, start);
    }

    private static final Map<String, String> NOTICE_HINTS = Map.of(
            MessengerService.NOTICE_ISSUE, "설계·리뷰 확인 요청, 진행·완료·한도 안내. 끄면 확인은 에이전트 화면과 알림함에서 합니다.",
            MessengerService.NOTICE_RESEARCH, "메신저로 접수한 질문(질문 …)의 완료 알림. 결과는 리서치 탭에 있습니다.",
            MessengerService.NOTICE_SYSTEM, "플랫폼 시작됨 · 오류 알림.");

    /** 플랫폼이 자기방으로 보내는 알림의 유형별 켜짐 상태(메신저 탭 "메신저 알림" 카드). */
    @GetMapping("/api/messenger-notify")
    public Map<String, Object> messengerNotify() {
        List<Map<String, Object>> kinds = new java.util.ArrayList<>();
        MessengerService.NOTICES.forEach((key, label) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("hint", NOTICE_HINTS.getOrDefault(key, ""));
            m.put("on", client.notifyOn(key));
            kinds.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", !"none".equals(client.provider()));
        out.put("kinds", kinds);
        return out;
    }

    /** 알림 유형 하나를 켜거나 끈다(재시작 불필요, 다음 알림부터 적용). */
    @PostMapping("/api/messenger-notify")
    public Map<String, Object> setMessengerNotify(@RequestParam String key, @RequestParam boolean on) {
        String label = MessengerService.NOTICES.get(key);
        if (label == null) {
            return Map.of("ok", false, "result", "알 수 없는 알림 유형: " + key);
        }
        settings.setMessengerNotify(key, on, MessengerService.NOTICES.keySet());
        return Map.of("ok", true, "result", "메신저 알림 「" + label + "」 " + (on ? "켬" : "끔 — 화면(알림함·각 탭)에서만 확인합니다"));
    }
}

package io.github.lib0823.devagent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.issue.IssueProjects;
import io.github.lib0823.devagent.routine.RoutineService;
import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.skill.SkillService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-028 R1: 구성 묶음 가져오기의 대상 프로젝트는 IssueProjects.mergeProjects 를 거치고 SettingService 에 직접 쓰지 않는다. */
class BundleProjectsTest {

    @Test
    void tc026_r1_importMergesProjectsThroughIssueProjectsAndReportsResult() throws Exception {
        SettingService settings = mock(SettingService.class);
        when(settings.getAgentModels()).thenReturn(new SettingService.AgentModels("opus", "high", "opus", "high", "opus", "high"));
        IssueProjects issueProjects = mock(IssueProjects.class);
        when(issueProjects.mergeProjects(anyList())).thenReturn("대상 프로젝트 추가 1 · 거부 1 — 조직 저장소는 대상으로 할 수 없습니다.");
        BundleController c = new BundleController(mock(SkillService.class), mock(McpConfigService.class),
                mock(RoutineService.class), settings, issueProjects);
        JsonNode body = new ObjectMapper().readTree(
                "{\"format\":\"dev-agent-bundle\",\"settings\":{\"projects\":[\"me/a\",\"org/o\"]}}");

        Map<String, Object> out = c.importBundle(body);

        verify(issueProjects).mergeProjects(List.of("me/a", "org/o"));
        verify(settings, never()).setIssueProjects(any());
        assertTrue(out.get("result").toString().contains("대상 프로젝트 추가 1 · 거부 1"), out.toString());
    }
}

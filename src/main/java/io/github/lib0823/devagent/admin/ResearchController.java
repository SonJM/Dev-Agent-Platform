package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.research.ResearchJob;
import io.github.lib0823.devagent.research.ResearchService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질의(리서치) 탭 API. 이벤트 스트림은 대시보드와 같은 {@code /api/agent/stream}(세션 id = {@link ResearchService#EVENT_BASE} + 질의 id),
 * 이벤트 본문은 {@code /api/agent/events/{id}} 를 그대로 쓴다.
 */
@RestController
public class ResearchController {

    private final ResearchService research;

    public ResearchController(ResearchService research) {
        this.research = research;
    }

    @GetMapping("/api/research")
    public Map<String, Object> overview(@RequestParam(defaultValue = "80") int limit) {
        return research.overview(limit);
    }

    @GetMapping("/api/research/options")
    public Map<String, Object> options() {
        return research.options();
    }

    /** 브랜치 선택용: 프로젝트의 기본 브랜치 + 브랜치 목록(최근 갱신 순, 최대 100). q 로 이름 검색. */
    @GetMapping("/api/research/branches")
    public Map<String, Object> branches(@RequestParam String project, @RequestParam(required = false) String q) {
        try {
            return research.branches(project, q);
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.getMessage());
            out.put("branches", java.util.List.of());
            return out;
        }
    }

    /** 스레드 상세(어느 항목 id 로 불러도 스레드 전체). afterId 는 이벤트 증분 조회용. */
    @GetMapping("/api/research/{id}")
    public ResponseEntity<Map<String, Object>> thread(@PathVariable long id,
                                                      @RequestParam(defaultValue = "0") long afterId,
                                                      @RequestParam(defaultValue = "2000") int limit) {
        Map<String, Object> t = research.thread(id, afterId, limit);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    /** 질문 접수(폼). projects 는 쉼표 구분. parentId 가 있으면 후속 질문(프로젝트/모델은 루트 값). kind=issue 면 결과를 이슈 초안 형식으로. */
    @PostMapping("/api/research")
    public Map<String, Object> submit(@RequestParam String question,
                                      @RequestParam(required = false) String projects,
                                      @RequestParam(required = false) String model,
                                      @RequestParam(required = false) String effort,
                                      @RequestParam(required = false) Long parentId,
                                      @RequestParam(required = false) Boolean verify,
                                      @RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String attachments) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<String> ps = projects == null ? List.of() : Arrays.stream(projects.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            List<String> imgs = attachments == null ? List.of() : Arrays.stream(attachments.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            ResearchJob j = research.submit(question, ps, model, effort, parentId, false, "dashboard", verify, kind, imgs);
            out.put("ok", true);
            out.put("id", j.getId());
            out.put("rootId", j.getRootId());
            out.put("result", "#" + j.getId() + " 접수");
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("result", e.getMessage());
        }
        return out;
    }

    /** 질문에 붙일 이미지 한 장 올리기(본문 = 파일 바이트). 돌려준 name 을 접수 때 attachments 로 보낸다. */
    @PostMapping("/api/research/attach")
    public Map<String, Object> attach(@RequestBody(required = false) byte[] data) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("name", research.stageAttachment(data));
            out.put("ok", true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.put("ok", false);
            out.put("result", e.getMessage());
        }
        return out;
    }

    /** 질문에 첨부된 이미지 보기. */
    @GetMapping("/api/research/{id}/attach/{name}")
    public ResponseEntity<FileSystemResource> attachment(@PathVariable long id, @PathVariable String name) {
        java.nio.file.Path p = research.attachmentFile(id, name);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/" + ("jpg".equals(ext) ? "jpeg" : ext)))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(p));
    }

    @PostMapping("/api/research/{id}/abort")
    public Map<String, Object> abort(@PathVariable long id) {
        return Map.of("result", research.abort(id));
    }

    /** 결과 문서를 .md 파일로 내려받기(항목 1건). */
    @GetMapping("/api/research/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        ResearchJob j = research.find(id);
        if (j == null || j.getResultMd() == null) {
            return ResponseEntity.notFound().build();
        }
        String body = j.getResultMd() + "\n\n---\n질문: " + j.getQuestion() + "\n프로젝트: " + j.getProjects()
                + "\n모델: " + j.getModel() + "/" + j.getEffort() + " · " + j.getTurns() + "턴\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"research-" + id + ".md\"")
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}

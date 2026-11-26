package io.github.lib0823.devagent.admin;

import io.github.lib0823.devagent.maintain.MaintainService;
import io.github.lib0823.devagent.maintain.MaintainTurn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 플랫폼 관리(상단바 렌치 버튼의 채팅 창) API. 요청은 플랫폼 소스를 고치는 Claude 세션으로 이어지므로
 * 상태를 바꾸는 호출은 모두 Origin 을 확인한다(다른 사이트의 페이지가 브라우저를 통해 지시를 밀어 넣지 못하게).
 */
@RestController
public class MaintainController {

    private final MaintainService maintain;

    public MaintainController(MaintainService maintain) {
        this.maintain = maintain;
    }

    /** 활성 여부·실행 중인 요청·대화 목록(가볍다). */
    @GetMapping("/api/maintain")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int limit) {
        return maintain.overview(limit);
    }

    /** 저장소 상태(브랜치·변경·올리지 않은 커밋·새 빌드 여부). */
    @GetMapping("/api/maintain/status")
    public Map<String, Object> status() {
        return maintain.status();
    }

    /** 대화 상세. afterId 는 활동 로그 증분 조회용. */
    @GetMapping("/api/maintain/threads/{id}")
    public ResponseEntity<Map<String, Object>> thread(@PathVariable long id, @RequestParam(defaultValue = "0") long afterId) {
        Map<String, Object> t = maintain.thread(id, afterId);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    /** 요청 접수. threadId 가 있으면 그 대화에 이어 붙인다. */
    @PostMapping("/api/maintain")
    public Map<String, Object> submit(@RequestParam String question,
                                      @RequestParam(required = false) Long threadId,
                                      @RequestParam(required = false) String model) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            MaintainTurn t = maintain.submit(question, threadId, model);
            out.put("ok", true);
            out.put("id", t.getId());
            out.put("threadId", t.getThreadId());
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("result", e.getMessage());
        }
        return out;
    }

    @PostMapping("/api/maintain/{id}/abort")
    public Map<String, Object> abort(@PathVariable long id) {
        return Map.of("ok", true, "result", maintain.abort(id));
    }

    /** 현재 브랜치의 커밋을 origin 으로 올린다(강제 푸시 없음). */
    @PostMapping("/api/maintain/push")
    public Map<String, Object> push() {
        return maintain.push();
    }
}

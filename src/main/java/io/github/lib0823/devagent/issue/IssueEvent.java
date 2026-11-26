package io.github.lib0823.devagent.issue;

import java.time.OffsetDateTime;

/**
 * 이슈 세션 타임라인 이벤트 1건 (issue_event 행). 모니터 화면의 활동 로그/타임라인 원천.
 * <pre>
 * kind: STATE(상태 전이) | INFO | REPLY(사용자 답변) | CLAUDE(실행 시작/종료) | TOOL(도구 호출)
 *       | TOOL_RESULT(도구 결과: 오류·Bash 출력) | TEXT(중간 사고 텍스트) | GIT | REMOTE | ERROR
 * </pre>
 */
public class IssueEvent {

    public static final String STATE = "STATE";
    public static final String INFO = "INFO";
    public static final String REPLY = "REPLY";
    public static final String CLAUDE = "CLAUDE";
    public static final String TOOL = "TOOL";
    public static final String TOOL_RESULT = "TOOL_RESULT";
    public static final String TEXT = "TEXT";
    public static final String GIT = "GIT";
    public static final String REMOTE = "REMOTE";
    public static final String ERROR = "ERROR";

    private long id;
    private long sessionId;
    private String kind;
    private String message;   // 한 줄 요약
    private String detail;    // 긴 본문(선택)
    private Boolean hasDetail; // 경량 조회(detail 제외) 시 본문 존재 여부
    private OffsetDateTime createdAt;

    public IssueEvent() {
    }

    public IssueEvent(long sessionId, String kind, String message, String detail) {
        this.sessionId = sessionId;
        this.kind = kind;
        this.message = message;
        this.detail = detail;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getSessionId() { return sessionId; }
    public void setSessionId(long sessionId) { this.sessionId = sessionId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    /** 본문 존재 여부. 경량 조회면 DB 계산값, 아니면 detail 로 판단. (JSON: hasDetail) */
    public boolean isHasDetail() { return hasDetail != null ? hasDetail : (detail != null && !detail.isBlank()); }
    public void setHasDetail(Boolean hasDetail) { this.hasDetail = hasDetail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}

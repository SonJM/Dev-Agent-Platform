package io.github.lib0823.devagent.research;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 질의 1건(= 스레드의 한 항목). 첫 질문이 루트(root_id = 자기 id)이고, 후속 질문은 parent_id/root_id 로 묶여
 * 루트의 Claude 세션을 {@code --resume} 으로 이어간다. 상태: NEW → RUNNING → DONE / FAILED / ABORTED.
 */
public class ResearchJob {

    public static final String NEW = "NEW";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String ABORTED = "ABORTED";

    public static final String KIND_DOC = "doc";
    public static final String KIND_ISSUE = "issue";

    private long id;
    private Long parentId;
    private long rootId;
    private String question;
    private String projects;          // 쉼표 구분 group/repo 목록(첫 항목이 작업 디렉터리)
    private String model;
    private String effort;
    private String state;
    private String claudeSessionId;
    private String resultMd;
    private String lastError;
    private int turns;
    private long inputTokens;
    private long outputTokens;
    private long elapsedMs;
    private boolean notify;           // 완료 시 자기방 알림(메신저로 접수한 질문)
    private String requester;         // 접수 경로 표시(dashboard / messenger)
    private boolean verify;           // 2차 검증(독립 검증자 → 지적 반영) 수행 여부
    private String verifyState;       // null(안 함) / PASS / FIXED(지적 반영해 수정) / FAIL(지적은 있으나 수정 실패) / SKIPPED(검증 실행 실패)
    private String verifyMd;          // 검증자 보고서
    private String kind = KIND_DOC;   // 결과 형식: doc(조사 문서) / issue(GitHub 이슈 초안 — 화면에서 「이슈로 등록」)
    private String attachments;       // 첨부 이미지 이름(쉼표 구분: 1.png,2.jpg) — 파일은 작업 디렉터리 research-attach/<id>/
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime updatedAt;

    public boolean isActive() {
        return NEW.equals(state) || RUNNING.equals(state);
    }

    public List<String> projectList() {
        if (projects == null || projects.isBlank()) {
            return List.of();
        }
        return Arrays.stream(projects.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public List<String> attachmentList() {
        if (attachments == null || attachments.isBlank()) {
            return List.of();
        }
        return Arrays.stream(attachments.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public long getRootId() { return rootId; }
    public void setRootId(long rootId) { this.rootId = rootId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getProjects() { return projects; }
    public void setProjects(String projects) { this.projects = projects; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }
    public String getResultMd() { return resultMd; }
    public void setResultMd(String resultMd) { this.resultMd = resultMd; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public boolean isNotify() { return notify; }
    public void setNotify(boolean notify) { this.notify = notify; }
    public boolean isVerify() { return verify; }
    public void setVerify(boolean verify) { this.verify = verify; }
    public String getVerifyState() { return verifyState; }
    public void setVerifyState(String verifyState) { this.verifyState = verifyState; }
    public String getVerifyMd() { return verifyMd; }
    public void setVerifyMd(String verifyMd) { this.verifyMd = verifyMd; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = KIND_ISSUE.equals(kind) ? KIND_ISSUE : KIND_DOC; }
    public boolean issueDraft() { return KIND_ISSUE.equals(kind); }
    public String getRequester() { return requester; }
    public void setRequester(String requester) { this.requester = requester; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}

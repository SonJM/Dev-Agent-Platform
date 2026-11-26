package io.github.lib0823.devagent.issue;

import java.time.OffsetDateTime;

/**
 * 이슈 1건의 처리 세션 (issue_session 행). MyBatis 호환 JavaBean.
 * <pre>
 * NEW → ANALYZING → DESIGN_WAIT → FIXING → VERIFYING → REVIEW_WAIT → PUSHING → DONE
 *                                     ↑ (검증 실패 시 자동 재수정, 상한 있음) ┘
 *        (어느 단계든 ABORTED / FAILED 로 종료 가능)
 * 선분석(prefetch): NEW → PREPARING → READY → (앞 건 종료 후) DESIGN_WAIT …
 *   PREPARING/READY 는 '활성(active)' 세션으로 치지 않는다 — 사용자 확인은 항상 1건만 받는다.
 * </pre>
 */
public class IssueSession {

    public static final String NEW = "NEW";
    public static final String ANALYZING = "ANALYZING";
    public static final String PREPARING = "PREPARING";   // 선분석 진행 중(백그라운드)
    public static final String READY = "READY";           // 선분석 완료, 제시 대기
    public static final String DESIGN_WAIT = "DESIGN_WAIT";
    public static final String FIXING = "FIXING";
    public static final String VERIFYING = "VERIFYING";   // 봇 검사 + 독립 리뷰어(실패 시 FIXING 으로 자동 복귀)
    public static final String REVIEW_WAIT = "REVIEW_WAIT";
    public static final String PUSHING = "PUSHING";
    public static final String DONE = "DONE";
    public static final String ABORTED = "ABORTED";
    public static final String FAILED = "FAILED";

    private long id;
    private String projectPath;
    private long issueNumber;
    private String issueTitle;
    private String issueUrl;
    private String defaultBranch;
    private String state;
    private String claudeSessionId;
    private String branchName;
    private String designText;
    private String changeSummary;
    private String commitSha;
    private String prUrl;
    private String lastError;
    private int feedbackRounds;
    private String workType;           // 컨벤션 작업 유형 = 브랜치 접두어(feature/fix/refactor/doc/hotfix), 분석 결과·라벨로 결정
    private String baseBranch;         // 작업트리를 만든 기준 브랜치(develop / hotfix 는 main)
    private int verifyRounds;          // 검증 실행 횟수(자동 재수정 포함)
    private String verifyResult;       // 마지막 검증 보고(마크다운: 봇 검사 결과 + 리뷰어 판정/근거)
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public boolean isWaiting() {
        return DESIGN_WAIT.equals(state) || REVIEW_WAIT.equals(state);
    }

    public boolean isActive() {
        return !(DONE.equals(state) || ABORTED.equals(state) || FAILED.equals(state));
    }

    /** 선분석 중/완료(아직 사용자에게 제시 전). */
    public boolean isPending() {
        return PREPARING.equals(state) || READY.equals(state);
    }

    public String ref() {
        return projectPath + "#" + issueNumber;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public long getIssueNumber() { return issueNumber; }
    public void setIssueNumber(long issueNumber) { this.issueNumber = issueNumber; }
    public String getIssueTitle() { return issueTitle; }
    public void setIssueTitle(String issueTitle) { this.issueTitle = issueTitle; }
    public String getIssueUrl() { return issueUrl; }
    public void setIssueUrl(String issueUrl) { this.issueUrl = issueUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getDesignText() { return designText; }
    public void setDesignText(String designText) { this.designText = designText; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public int getFeedbackRounds() { return feedbackRounds; }
    public void setFeedbackRounds(int feedbackRounds) { this.feedbackRounds = feedbackRounds; }
    public String getWorkType() { return workType; }
    public void setWorkType(String workType) { this.workType = workType; }
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    public int getVerifyRounds() { return verifyRounds; }
    public void setVerifyRounds(int verifyRounds) { this.verifyRounds = verifyRounds; }
    public String getVerifyResult() { return verifyResult; }
    public void setVerifyResult(String verifyResult) { this.verifyResult = verifyResult; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}

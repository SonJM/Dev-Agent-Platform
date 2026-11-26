package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 이슈 수정 에이전트 설정 (prefix: gitlab — 키 개명은 T-011).
 * <p>
 * 라벨이 붙은 열린 이슈를 폴링해 1건씩: 소스 준비 → 분석/설계(자기방 확인) → 수정(자기방 확인)
 * → 컨벤션 브랜치 커밋·푸시 → 이슈 코멘트·Draft PR 생성.
 */
@ConfigurationProperties(prefix = "gitlab")
public record GitLabProperties(
        boolean enabled,
        List<String> projects,         // "owner/repo" 경로 목록(GitHub 접속 설정은 github.* — GitHubProperties)
        String label,                  // 이 라벨이 붙은 열린 이슈만 자동 처리 (예: ai-fix)
        long scanIntervalMs,           // 이슈 스캔 주기
        long replyPollIntervalMs,      // 자기방 답변 폴링 주기
        String workDir,                // 저장소 clone 루트 (예: work)
        String gitUserName,            // 커밋 author
        String gitUserEmail,
        boolean commentOnIssue,        // 푸시 후 이슈에 코멘트
        boolean createMr,              // 푸시 후 Draft PR 생성
        boolean prefetch,              // 현재 건이 사용자 확인 대기 중일 때 다음 이슈를 미리 분석(READY)
        long waitRemindMs,             // 확인 대기가 이 시간 넘게 이어지면 자기방 리마인더(0=끔)
        Agent agent,
        Verify verify,
        Convention convention,
        int concurrency                // 동시에 진행할 이슈 세션 수(기본 2). 프리뷰 Tomcat 은 1개를 순서대로 공유
) {

    public int concurrencyOr() {
        return concurrency <= 0 ? 1 : Math.min(concurrency, 8);
    }

    /**
     * 브랜치/커밋 컨벤션. 브랜치 {작업 유형}/{이슈번호}, 항상 저장소 기본 브랜치에서 분기해 기본 브랜치로 PR.
     * 커밋 첫 줄은 feat|fix|docs|style|refactor|test|chore: 제목.
     */
    public record Convention(
            List<String> prefixes          // 허용 브랜치 접두어 = 작업 유형(feature, fix, refactor, doc, hotfix)
    ) {
        public List<String> prefixesOr() {
            return prefixes == null || prefixes.isEmpty() ? List.of("feature", "fix", "refactor", "doc", "hotfix") : prefixes;
        }
    }

    /**
     * 검증 단계(FIXING → VERIFYING → REVIEW_WAIT). 봇이 직접 돌리는 결정적 검사(컴파일·정적검사) + 독립 리뷰어 Claude 세션.
     * 실패하면 실패 내역을 피드백으로 넣어 자동 재수정(maxRetries 회), 그래도 실패면 실패 내역과 함께 리뷰 대기로 올린다.
     */
    public record Verify(
            boolean enabled,
            int maxRetries,                // 검증 실패 시 자동 재수정 횟수(0=재수정 없이 바로 리뷰 대기)
            boolean reviewer,              // 독립 리뷰어 세션 사용
            String model,                  // 리뷰어 모델(비우면 agent.model)
            String effort,
            long commandTimeoutMs,         // 봇이 돌리는 검사 명령 1개 상한
            String commands,               // 프로젝트별 검사 명령 "group/repo=cmd;group/repo2=cmd" (비우면 pom/gradle 자동)
            List<String> tools,            // 리뷰어 --allowedTools (읽기 전용 + 정적검사 + 프리뷰 상태/로그)
            long reviewerTimeoutMs         // 리뷰어 세션 1회 실행 상한(0 이면 agent.timeout-ms)
    ) {
        public String modelOr(String fallback) {
            return model == null || model.isBlank() ? fallback : model.strip();
        }

        public String effortOr(String fallback) {
            return effort == null || effort.isBlank() ? fallback : effort.strip();
        }
    }

    /** 코딩용 Claude Code CLI 실행 옵션. */
    public record Agent(
            String model,                  // 예: opus
            String effort,                 // 예: high
            long timeoutMs,                // 분석(설계·재설계) 1회 실행 상한
            int maxTurns,                  // 분석 --max-turns
            List<String> analyzeTools,     // 분석 단계 --allowedTools
            List<String> fixTools,         // 수정 단계 --allowedTools (Bash(...) 패턴으로 빌드/테스트 명령 제한 허용)
            String testHint,               // 프롬프트에 안내할 검증 명령 설명
            int maxMessageChars,           // 메신저 1건 최대 길이(초과 시 분할 전송)
            long fixTimeoutMs,             // 개발(수정·재수정) 1회 실행 상한(0 이면 timeoutMs). 큰 이슈는 분석보다 훨씬 길게
            int fixMaxTurns,               // 개발 --max-turns(0 이면 maxTurns)
            long idleTimeoutMs             // 무응답 상한: 이 시간 동안 도구 호출·출력이 전혀 없으면 멈춘 것으로 보고 중단(0=끔)
    ) {
        public long fixTimeoutOr() {
            return fixTimeoutMs > 0 ? fixTimeoutMs : timeoutMs;
        }

        public int fixMaxTurnsOr() {
            return fixMaxTurns > 0 ? fixMaxTurns : maxTurns;
        }
    }

}

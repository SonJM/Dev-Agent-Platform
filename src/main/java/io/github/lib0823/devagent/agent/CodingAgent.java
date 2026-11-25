package io.github.lib0823.devagent.agent;

import io.github.lib0823.devagent.platform.PortHealth;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * 코딩 에이전트 포트(전략). 저장소 작업 디렉터리에서 도구(파일 읽기·편집·제한된 셸)를 쓰며 여러 턴을 도는 LLM 실행기를 추상화한다.
 * 코어(이슈 에이전트·검증·질의)는 이 인터페이스만 알고, 구현체는 {@code platform.coding-agent} 값으로 하나만 활성화된다
 * (현재 {@code claude-cli} = Claude Code CLI headless. Claude API·다른 LLM 은 이 인터페이스를 구현해 끼우면 된다).
 * <p>
 * 구현 규약
 * <ul>
 *   <li>세션: {@code sessionId} 는 호출측이 정한 UUID. {@code resume=false} 면 새 세션, true 면 같은 id 의 대화 맥락을 이어간다.</li>
 *   <li>도구: {@code allowedTools} 는 허용 도구 목록(Claude Code 표기: Read, Grep, Edit, Bash(접두:*) …). 다른 구현체는 자기 도구로 사상한다.</li>
 *   <li>진행 상황: 도구 호출·중간 텍스트를 {@link Listener} 로 실시간 전달(kind 는 IssueEvent 의 TOOL / TOOL_RESULT / TEXT / ERROR).</li>
 *   <li>상한: 단계별 시간·턴 상한을 넘으면 {@link AgentTimeoutException}, 구독/쿼터 한도면 {@link UsageLimitException}.</li>
 *   <li>사용량은 구현체가 {@code UsageService.record} 로 남긴다.</li>
 * </ul>
 */
public interface CodingAgent {

    /** 구현체 이름(로그·관리 화면 표시). 예: claude-cli */
    String provider();

    /** 연결 상태(즉시 응답, LLM 호출 없음): 실행 중 세션 수, 최근 실행 성공/실패. */
    PortHealth health();

    /** 연결 테스트(토큰을 쓰지 않는 가벼운 확인 — CLI 실행 가능 여부, API 키 유효성 등). */
    PortHealth.TestResult selfTest();

    /** 실행 단계. ANALYZE(읽기 전용 분석·설계) / FIX(편집+빌드·프리뷰) / VERIFY(독립 리뷰어, 읽기 전용). 모델·상한이 단계별로 다르다. */
    enum Stage { ANALYZE, FIX, VERIFY }

    /** 진행 중 활동 수신자. */
    @FunctionalInterface
    interface Listener {
        void onActivity(String kind, String message, String detail);
    }

    /** 실행 중인 세션의 모델·단계(대시보드 표시용). */
    record RunInfo(String model, String effort, Stage stage, long startedAt) {
    }

    /** 호출별 재정의: 모델/노력은 비우면 단계 설정값, 상한/턴은 0 이면 단계 기본값, source 는 사용량 기록 구분(null → "agent"). */
    record Overrides(String model, String effort, long timeoutMs, int maxTurns, String source) {
    }

    record Result(String text, String sessionId, boolean error, double costUsd, int turns, int toolCalls,
                  long elapsedMs, long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreateTokens) {

        /** 입력 = 신규 입력 + 캐시 생성 + 캐시 읽기(모델이 실제로 읽은 양). */
        public long inputTotal() {
            return inputTokens + cacheReadTokens + cacheCreateTokens;
        }

        /** 이벤트/로그용 고정 형식(대시보드가 파싱): "토큰 in=72086 out=308". */
        public String tokens() {
            return "토큰 in=" + inputTotal() + " out=" + outputTokens;
        }
    }

    /**
     * 에이전트 실행(블로킹).
     *
     * @param cwd          저장소 작업 디렉터리
     * @param sessionId    세션 UUID
     * @param resume       기존 세션 이어가기 여부
     * @param allowedTools 허용 도구 목록
     * @param systemAppend 시스템 프롬프트에 덧붙일 지시
     * @param prompt       사용자 메시지
     * @param listener     진행 활동 수신(선택)
     * @param stage        실행 단계
     * @param overrides    호출별 재정의(선택)
     */
    Result run(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
               String systemAppend, String prompt, Listener listener, Stage stage, Overrides overrides);

    default Result run(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                       String systemAppend, String prompt, Listener listener, Stage stage) {
        return run(cwd, sessionId, resume, allowedTools, systemAppend, prompt, listener, stage, null);
    }

    default Result run(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                       String systemAppend, String prompt, Listener listener, boolean fixStage) {
        return run(cwd, sessionId, resume, allowedTools, systemAppend, prompt, listener, fixStage ? Stage.FIX : Stage.ANALYZE, null);
    }

    default Result run(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                       String systemAppend, String prompt, Listener listener) {
        return run(cwd, sessionId, resume, allowedTools, systemAppend, prompt, listener, Stage.ANALYZE, null);
    }

    default Result run(Path cwd, String sessionId, boolean resume, List<String> allowedTools,
                       String systemAppend, String prompt) {
        return run(cwd, sessionId, resume, allowedTools, systemAppend, prompt, null, Stage.ANALYZE, null);
    }

    /** 해당 세션의 실행을 강제 종료한다. 실행 중이 아니면 false. */
    boolean kill(String sessionId);

    /** 해당 세션이 지금 실행 중이면 그 실행 정보, 아니면 null. */
    RunInfo runInfo(String sessionId);

    /** 사용량 한도에 걸려 실행이 진행되지 않음. {@code until} 은 리셋 시각(모르면 구현체가 추정). */
    final class UsageLimitException extends IllegalStateException {
        private final Instant until;

        public UsageLimitException(String text, Instant until, int toolCalls) {
            super("Claude 사용량 한도 도달 (리셋 " + until.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().withNano(0)
                    + ", 도구 호출 " + toolCalls + "회 후): " + cut(text, 120));
            this.until = until;
        }

        public Instant until() {
            return until;
        }

        private static String cut(String s, int n) {
            if (s == null) {
                return "";
            }
            String t = s.strip();
            return t.length() <= n ? t : t.substring(0, n) + "…";
        }
    }

    /**
     * 실행 상한 초과. {@code idle} 이면 무응답 상한(출력 없음), 아니면 전체 상한. 수정 단계에서는 호출자가 작업트리 변경을 보존한 채
     * 리뷰 대기로 넘겨 피드백으로 이어갈 수 있게 한다.
     */
    final class AgentTimeoutException extends IllegalStateException {
        private final boolean idle;
        private final long limitMs;
        private final int toolCalls;
        private final long elapsedMs;

        public AgentTimeoutException(boolean idle, long limitMs, int toolCalls, long elapsedMs) {
            super(idle
                    ? "claude(agent) 무응답 " + (limitMs / 60_000) + "분 — 멈춘 것으로 보고 중단 (도구 호출 " + toolCalls + "회, 경과 " + (elapsedMs / 60_000) + "분)"
                    : "claude(agent) 타임아웃 (" + (limitMs / 60_000) + "분 상한, 도구 호출 " + toolCalls + "회 후)");
            this.idle = idle;
            this.limitMs = limitMs;
            this.toolCalls = toolCalls;
            this.elapsedMs = elapsedMs;
        }

        public boolean idle() {
            return idle;
        }

        public long limitMs() {
            return limitMs;
        }

        public int toolCalls() {
            return toolCalls;
        }

        public long elapsedMs() {
            return elapsedMs;
        }
    }
}

package io.github.lib0823.devagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 질의(리서치) 워크플로우 설정 (prefix: research).
 * 대시보드 "질의" 탭 / 자기방 {@code 질문 …} 으로 들어온 질문을, 대상 프로젝트의 읽기 전용 worktree 에서
 * Claude 세션이 소스를 훑어 마크다운 문서로 답한다. 코드 수정·빌드 없음, 후속 질문은 같은 세션을 이어간다.
 */
@ConfigurationProperties(prefix = "research")
public record ResearchProperties(
        boolean enabled,
        long timeoutMs,          // 질문 1건 실행 상한(기본 30분)
        int maxTurns,            // 질문 1건 턴 상한(기본 60)
        String model,            // 기본 모델(비우면 관리 페이지 '기획' 모델)
        String effort,           // 기본 노력(비우면 '기획' 노력)
        int maxResultChars,      // 결과 문서 저장 상한(초과분은 잘라 저장)
        boolean verify,          // 2차 검증 기본값(질의마다 화면에서 끌 수 있음)
        int attachmentKeepDays   // 첨부 이미지 보관 일수: 스레드가 끝난 뒤 이만큼 지나면 파일을 지운다(0 이하 = 지우지 않음)
) {
    public long timeoutOr() {
        return timeoutMs > 0 ? timeoutMs : 30 * 60_000L;
    }

    public int maxTurnsOr() {
        return maxTurns > 0 ? maxTurns : 60;
    }

    public int maxResultCharsOr() {
        return maxResultChars > 0 ? maxResultChars : 60_000;
    }
}

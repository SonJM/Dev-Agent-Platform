package io.github.lib0823.devagent.gitlab;

import io.github.lib0823.devagent.config.GitLabProperties;
import java.util.List;
import org.springframework.stereotype.Component;

import static io.github.lib0823.devagent.gitlab.IssueTexts.*;

/**
 * 이슈 에이전트 프롬프트: 시스템 프롬프트(참고 저장소 안내 포함), 분석 요청, 수정 요청. {@link IssueAgentService} 에서 분리.
 */
@Component
public class IssuePrompts {

    private final GitLabProperties props;
    private final IssueReferences refs;

    public IssuePrompts(GitLabProperties props, IssueReferences refs) {
        this.props = props;
        this.refs = refs;
    }

    public String systemPrompt(IssueSession s) {
        return "당신은 사내 GitLab 프로젝트 '" + s.getProjectPath() + "' 의 이슈를 처리하는 시니어 개발자입니다. "
                + "현재 작업 디렉터리는 이 저장소의 origin/" + (s.getBaseBranch() == null ? s.getDefaultBranch() : s.getBaseBranch()) + " 기준 작업트리입니다. "
                + "한국어로 답하되 코드 식별자/파일명은 원문을 유지하세요. 출력은 사내 메신저로 그대로 전달되므로 "
                + "간결하게 쓰고 마크다운 표는 쓰지 마세요(헤더는 '## ' 만 사용). "
                + "저장소의 기존 코드 컨벤션을 따르고, 이슈 범위를 벗어난 리팩터링은 하지 마세요."
                + refs.referenceNote(s.getId());
    }

    public String analyzePrompt(GitLabClient.Issue is, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 GitLab 이슈를 이 저장소에서 확인하고 수정 설계안을 작성하세요. 이 단계에서는 코드를 수정하지 마세요(읽기 전용).\n");
        sb.append("저장소 루트의 CLAUDE.md / README / 컨벤션 문서가 있으면 먼저 읽고 그 규칙을 따르세요.\n\n");
        sb.append("--- 이슈 #").append(is.iid()).append(" ---\n");
        sb.append("제목: ").append(is.title()).append('\n');
        sb.append("작성자: ").append(is.author()).append("  라벨: ").append(is.labels()).append('\n');
        sb.append("URL: ").append(is.webUrl()).append('\n');
        sb.append("본문:\n").append(is.description() == null || is.description().isBlank() ? "(없음)" : is.description()).append('\n');
        if (!notes.isEmpty()) {
            sb.append("\n--- 이슈 코멘트 ---\n");
            for (String n : notes) {
                sb.append("- ").append(abbreviate(n, 800)).append('\n');
            }
        }
        sb.append("""

                --- 출력 형식 (이 형식 그대로, 전체 2000자 이내) ---
                ## 작업 유형
                (feature | fix | refactor | doc | hotfix 중 하나만 첫 단어로, 뒤에 한 줄 이유. 사내 컨벤션: 브랜치 {유형}/{일감번호},
                 일반 작업은 develop 에서 분기해 develop 으로 MR. hotfix 는 운영 장애로 즉시 배포가 필요한 경우에만 — main 에서 분기)
                ## 원인
                (재현 경로와 근본 원인. 관련 파일:라인 명시)
                ## 수정 설계
                (변경할 파일과 내용을 번호 목록으로. 대안이 있으면 추천안을 먼저)
                ## 영향/위험
                (영향 범위, 하위호환, 데이터/설정 변경 여부)
                ## 검증 방법
                (실행할 테스트/확인 절차)
                ## 확인 필요
                (불명확해 사용자에게 물어볼 점. 없으면 "없음")
                """);
        return sb.toString();
    }

    public String fixPrompt(IssueSession s) {
        String hint = props.agent().testHint();
        return "설계안이 승인되었습니다. 방금 정리한 '## 수정 설계' 대로 코드를 수정하세요.\n"
                + "- 설계에 없는 변경은 하지 마세요. 필요하면 최소한으로 하고 '## 변경 요약'에 사유를 적으세요.\n"
                + "- 검증: " + (hint == null || hint.isBlank() ? "가능한 범위에서 빌드/테스트를 실행하세요" : hint)
                + " (허용된 명령만 실행됩니다. 실행이 거부되거나 불가하면 그 사실과 이유를 적으세요)\n"
                + "- git 으로 커밋/브랜치/푸시를 하지 마세요. 커밋은 봇이 처리합니다.\n"
                + "- 임시 파일/디버그 출력은 남기지 마세요.\n\n"
                + "완료 후 다음 형식으로만 출력하세요(전체 1500자 이내):\n"
                + "## 변경 요약\n(파일별 한 줄)\n"
                + "## 검증 결과\n(실행한 검사·테스트·프리뷰 확인 내용과 결과. 화면을 고쳤으면 스크린샷 파일명을 함께)\n"
                + "## 커밋 메시지\n(사내 커밋 컨벤션: 첫 줄은 `feat|fix|docs|style|refactor|test|chore: 제목`(50자 이내, 이 작업은 "
                + commitTypeFor(s.getWorkType()) + " 권장), 빈 줄, 본문 2~5줄. 마지막 줄에 'Resolves #" + s.getIssueIid() + "')";
    }
}

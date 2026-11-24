# T-010. GitHub 연동 — GitLabClient 교체 (기능)

```
id: T-010
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-009]
병렬: 불가
충돌: [T-011]
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 구현
QA 확인: 예
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-24
```

Phase 3. 단계 수준 지시서 — 착수 전에 `OI-09`(토큰)·`OI-10`(브랜치 규칙)·`OI-15`(빌드 도구)가 정해져야 하고, 보안 리뷰(security-reviewer)를 1회 받는다(토큰 취급·푸시 권한).

## 목적
GitHub 이슈가 라벨 스캔부터 Draft PR까지 간다. GitLab 호출 코드는 남지 않는다.

## 근거
- 요구사항: `FR-301`~`FR-307` ([01 §4.3](../01-requirements.md)), `NFR-302`
- 설계: [02 §3.2](../02-architecture.md), [02 §4.2 D-5·D-6](../02-architecture.md)
- 데이터: [03 §2, §4.1](../03-data-model.md) (Phase 3 행 — 컬럼 삭제·개명은 T-011에서, 이 작업은 의미 변경과 `project_id`·`work_id` 사용 중단까지)
- 명세: [05 §2.3](../05-api-specification.md)
- 테스트: `TC-015`, `TC-016`, `TC-017`, 회귀 핵심 묶음 ([07](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), [04 §3 규칙 5](../04-project-structure.md)

## 관련 코드 경로
- 교체: `gitlab/GitLabClient.java`(199줄, 공개 메서드: `project`·`branchExists`·`branches`·`openIssues`·`recentOpenIssues`·`issue`·`createIssue`·`issueNotes`·`comment`·`createMergeRequest`) → `GitHubClient`
- 수정: 참조 6곳(`gitlab/IssueAgentService`·`IssueProjects`·`IssuePrompts`·`IssueReferences`, `ops/Doctor`, `research/ResearchService`), `gitlab/GitRunner.java`(인증 헤더 299행 부근), `gitlab/IssueTexts.java`(이슈 참조·URL 해석, 브랜치 이름), `config/GitLabProperties.java`, `application.yml`, `.env.example`, `env.template`

## 범위
- `GitHubClient`: 같은 역할의 메서드를 GitHub REST로 구현. 이슈 목록에서 PR 제외, PR은 `draft: true`·`base` = 기본 브랜치, 응답 한도(rate limit)·인증 실패를 구분해 원인을 드러낸다. 토큰은 로그·예외 메시지에 싣지 않는다.
- 저장소 식별을 `owner/repo`로, 이슈 참조를 `owner/repo#n`과 GitHub 이슈 URL로.
- 브랜치: 기본 브랜치에서 분기, `{유형}/{이슈번호}`, hotfix 전용 분기·`work-id-pattern` 제거, 기본 브랜치 푸시 금지·강제 푸시 없음·충돌 시 접미사 규칙 유지.
- git 인증을 GitHub 토큰 형식으로. 설정 키는 이 작업에서 `github.token`(환경변수 `GITHUB_TOKEN`)과 API 주소만 새로 만들고, 나머지 `gitlab.*` 키의 개명은 T-011.
- `Doctor`·연동 카드에 GitHub 토큰 상태·권한 점검.
- 프롬프트의 "GitLab"·"MR" 문구를 고친다(출력 틀의 절 제목은 화면 강조 규칙과 맞물리므로 바꾸면 보고).

## 범위 밖
- 패키지·클래스·컬럼 개명(T-011), 화면 문구(T-012)
- 포트(인터페이스) 도입, `gh` CLI 사용, GitHub App
- 실제 GitHub를 호출하는 자동 테스트(사용자가 수동 확인)

## 완료 조건
- [ ] `TC-015`, `TC-016`, `TC-017`이 단위 테스트로 존재하고 통과한다
- [ ] 저장소에 GitLab REST 호출 코드가 없다(`/api/v4`, `merge_requests` 검색 0)
- [ ] 토큰 없이 기동되고 Doctor가 원인과 고치는 방법을 보여 준다
- [ ] "사용자가 서버에서 확인할 것"(실제 이슈 1건: 스캔 → Draft PR → 코멘트) 절차가 변경 설명서에 있다
- [ ] 변경 설명서(`docs/tasks/results/T-010-github-연동.md`)가 그림·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-010-github-연동.md](./results/T-010-github-연동.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -rnE "api/v4|merge_requests|GitLabClient" src/main/java ; test $? -eq 1
```

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(단계 수준) |

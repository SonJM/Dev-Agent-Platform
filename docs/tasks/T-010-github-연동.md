# T-010. GitHub 연동 — GitLabClient 교체 (기능)

```
id: T-010
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-009, T-021, T-023, T-024]
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

Phase 3. 선결 결정은 모두 끝났다 — `OI-09`(토큰, 2026-11-25 결정 ①), `OI-10`(브랜치 규칙), `OI-15`(빌드 도구, 결정 ① — 저장소별 도구 목록만 미정). 구현 뒤 보안 리뷰(security-reviewer)를 1회 받는다(토큰 취급·푸시 권한·git 인증 헤더 범위).

## 목적
GitHub 이슈가 라벨 스캔부터 Draft PR까지 간다. GitLab 호출 코드는 남지 않는다. 토큰은 정해진 범위(개인 계정의 대상 저장소만)로만 쓰이고, 만료·접근 불가를 Doctor가 미리 알린다.

## 근거
- 요구사항: `FR-301`~`FR-307` ([01 §4.3](../01-requirements.md)), `NFR-302`
- 결정: `OI-09` ①, `OI-10`, `OI-15` ① ([00-INDEX 미결 사항](../00-INDEX.md))
- 설계: [02 §3.2](../02-architecture.md), [02 §4.2 D-5·D-6](../02-architecture.md) (D-6: fine-grained PAT, 대상 저장소만, Contents·Issues·Pull requests 읽기/쓰기 + Metadata 읽기, 만료 90일 안팎, 조직 저장소 제외, 값은 서버 `.env`의 `GITHUB_TOKEN`에만)
- 데이터: [03 §2, §4.1](../03-data-model.md) (Phase 3 행 — 컬럼 삭제·개명은 T-011에서, 이 작업은 의미 변경과 `project_id`·`work_id` 사용 중단까지)
- 명세: [05 §2.3](../05-api-specification.md)
- 테스트: `TC-015`, `TC-016`, `TC-017`, 회귀 핵심 묶음 ([07](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), [04 §3 규칙 5](../04-project-structure.md)
- 배포: [04 §9](../04-project-structure.md) "자식 프로세스 환경"·"대상 저장소 빌드 도구"·"필요한 비밀값" 행

## 관련 코드 경로
행 번호는 2026-11-25 HEAD(`7663f4d`) 기준.
- 교체: `gitlab/GitLabClient.java`(199줄, 공개 메서드: `project`·`branchExists`·`branches`·`openIssues`·`recentOpenIssues`·`issue`·`createIssue`·`issueNotes`·`comment`·`createMergeRequest`) → `GitHubClient`
- 참조 7개 파일: `gitlab/IssueAgentService`(19곳)·`IssueProjects`(6)·`IssuePrompts`(1)·`IssueReferences`(3), `ops/Doctor`(3), `research/ResearchService`(7)
- `gitlab/GitRunner.java`: 인증 헤더 300~305행(`oauth2:` + 토큰 Basic을 `GIT_CONFIG_*` 환경변수로 `http.extraheader`에 주입, 범위 없음 — 모든 원격에 붙는다), 클래스 주석 22행, 브랜치 이름 262행 부근(`Convention` 사용 279행)
- `gitlab/IssueTexts.java`(이슈 참조·URL 해석, 브랜치 이름), `config/GitLabProperties.java`(`Convention` 37~53행: `hotfixBase`·`hotfixLabels`·`workIdPattern`)
- `gitlab/IssueAgentService.java` 1636·1661·1664행: 커밋 메시지 `Resolves #n`(바꾸지 않고 보고 — 아래 범위)
- `platform/ChildEnv.java` 23~26행 `SECRET_KEYS`(현재 `GITLAB_TOKEN`), 테스트 `src/test/.../platform/ChildEnvTest.java`(application.yml 비밀 속성과 목록 대조)
- `mcp/McpConfigService.java` 204~206행 `isAllowedPlaceholder`(→ `ChildEnv.isSecretKey`), 213행 거부 문구("GitLab 토큰")
- `ops/Doctor.java`: 캐시 `TTL_MS` 32행, 생성자 44행, 점검 목록 66행 `gitlabCheck()`, 본문 137~155행
- `src/main/resources/application.yml` 64~67행(`gitlab:` 블록, `token: ${GITLAB_TOKEN:}`)
- `.env.example` 20~57행(GitLab 블록, PAT scope 주석 26행), `src/main/resources/dist/env.template` 4~12행(scope 주석 7행)
- `deploy/env.server.example` 21~28행(GitLab 블록, scope 주석 24행), `deploy/README.md` 87행("GitLab 항목")

## 범위
- `GitHubClient`: 같은 역할의 메서드를 GitHub REST로 구현. 이슈 목록에서 PR 제외, PR은 `draft: true`·`base` = 기본 브랜치, 응답 한도(rate limit)·인증 실패(401)·접근 불가(404/403)를 구분해 원인을 드러낸다. 토큰은 로그·예외 메시지·응답에 싣지 않는다.
- 저장소 식별을 `owner/repo`로, 이슈 참조를 `owner/repo#n`과 GitHub 이슈 URL로.
- 브랜치(OI-10 결정, [01 FR-304](../01-requirements.md)): 저장소 기본 브랜치에서 분기, 이름 `{유형}/{이슈번호}`(예 `fix/12`, `{유형}`은 작업 유형 그대로 — `hotfix/12`도 기본 브랜치 기준), PR 대상도 기본 브랜치. hotfix 전용 분기(라벨 `hotfix`/`긴급`·설계의 작업 유형으로 main 기준 전환)와 일감번호 추출(`work-id-pattern`)을 제거. 기본 브랜치 푸시 금지·강제 푸시 없음·충돌 시 접미사 규칙 유지. 커밋 메시지 마지막 줄의 `Resolves #n`은 GitHub에서 기본 브랜치 병합 때 이슈를 닫는다 — FR-305 가정(PR 본문에 닫기 참조 없음)과 함께 바꾸지 말고 설명서에 보고한다.
- 설정: 이 작업에서 `github.token`(환경변수 `GITHUB_TOKEN`)과 API 주소(`github.api-url`, 기본값은 공개 GitHub API 주소)만 새로 만든다. 나머지 `gitlab.*` 키의 개명은 T-011. 토큰이 비어도 기동된다(필수 자리표시자 금지 — CLAUDE.md).
- **git 인증**(GitRunner 300~305행): 헤더 주입 방식(`GIT_CONFIG_*` 환경변수, URL·설정 파일에 토큰을 쓰지 않음)은 유지하고 GitHub이 받는 Basic 형식으로 바꾼다(사용자 이름 자리에 무엇을 쓰는지와 근거를 설명서에 적는다). 키를 `http.extraheader`(모든 원격)에서 **GitHub 호스트로 한정한 `http.<GitHub 주소>/.extraheader`**로 바꿔, 참고 저장소·서브모듈 등 다른 호스트로 토큰이 나가지 않게 한다.
- **비밀 키 목록**(04 §9 "자식 프로세스 환경"): `ChildEnv.SECRET_KEYS`에 `GITHUB_TOKEN`을 더한다. `GITLAB_TOKEN`은 기존 `.env`에 값이 남아 있을 수 있으므로 T-011까지 목록에 둔다. `ChildEnvTest`가 새 `github.token` 속성을 잡는지 확인한다.
- **MCP 자리표시자**: `McpConfigService.isAllowedPlaceholder`가 `ChildEnv.isSecretKey`를 따르는지 확인하고, `${GITHUB_TOKEN}`이 든 사용자 정의 MCP 저장이 거부되는 단위 테스트를 더한다. 213행 거부 문구의 "GitLab 토큰"을 "GitHub 토큰"으로.
- **설정 예시의 권한 주석**: `.env.example`·`dist/env.template`·`deploy/env.server.example`의 GitLab PAT scope 주석(`api, write_repository`)을 fine-grained PAT 안내로 바꾼다 — 저장소 선택 "대상 저장소만", 권한 Contents·Issues·Pull requests 읽기/쓰기 + Metadata 읽기, 만료 90일 안팎, 개인 계정 저장소만. 키는 `GITHUB_TOKEN`·API 주소로. `deploy/README.md` 87행의 "GitLab 항목"도 GitHub 항목으로. 예시 값은 자리표시자만 쓴다(실제 토큰·계정·저장소 이름 금지).
- **Doctor**(`gitlabCheck` 대체, 항목 이름 GitHub): 60초 캐시(`TTL_MS`) 안에서만 GitHub를 부르고, `/api/system/status`에서는 외부 호출하지 않는다(CLAUDE.md). 점검 항목과 결과:
  - 토큰 없음 → `fail`(이슈 에이전트를 쓰는 설정일 때) + 고치는 방법(발급 경로와 권한 목록, `.env`의 `GITHUB_TOKEN`, 재시작).
  - 토큰 무효·만료(401) → `fail` + "토큰을 다시 발급해 `.env`를 고치고 재시작".
  - 대상 저장소별 접근: 저장소마다 조회해 200이 아닌 것을 이름과 함께 모은다(404/403 → `fail` + "토큰의 저장소 선택에 이 저장소를 더하세요". 조직 저장소는 대상이 아님을 문구에 포함). 응답 한도(rate limit)는 `warn`으로 구분.
  - 만료일: 응답 헤더 `github-authentication-token-expiration`을 읽어 표시한다. 남은 기간이 상수 `TOKEN_EXPIRY_WARN_DAYS = 14`(PM 결정, 2026-11-25 — 90일 토큰의 마지막 2주) 이하면 `warn` + 남은 일수 + 고치는 방법(재발급·`.env` 교체·재시작). 헤더가 없으면(만료 없는 토큰) "만료일 없음"으로 표시하고 `ok`. 헤더 형식을 못 읽으면 `warn`으로 두고 예외를 내지 않는다.
  - 쓰기 권한은 조회만으로 확정할 수 없으므로 Doctor는 "읽기 확인"까지로 표시하고, 쓰기(푸시·PR·코멘트)는 사용자 확인 절차에서 본다.
  - 토큰 값·Authorization 헤더는 로그·점검 결과·예외 메시지에 싣지 않는다(실패 메시지는 상태 코드와 GitHub 오류 문구만).
- 연동 카드: Doctor와 같은 점검 결과를 쓰고 새 외부 호출을 더하지 않는다.
- **`deploy/README.md` "대상 저장소 빌드 도구" 절의 틀**(OI-15 ①, 04 §9): 기존 절 번호를 바꾸지 않도록 마지막 번호 절 뒤에 새 번호로 붙인다. 내용은 틀만 —
  1. 대상 저장소가 쓰는 도구만 설치한다(JDK는 unit의 JDK 21, Maven·Gradle·Node 등은 저장소에 따라).
  2. 서비스 계정(`<SERVICE_USER>`)의 로그인 셸에서 저장소마다 한 번 빌드해 의존성 캐시를 채운다 — 검증 빌드(`VerifyService`)가 `mvn -o` 오프라인이라 캐시가 없으면 FAIL.
  3. 확인 명령(도구 버전 확인, 캐시를 채운 뒤 오프라인 빌드가 통과하는지).
  4. 저장소별 목록 표: 행은 "미정 — 첫 대상 저장소를 정할 때".
  실제 경로·주소·저장소 이름은 쓰지 않고 자리표시자(`<SERVICE_USER>`, `<owner/repo>` 등)만 쓴다.
- 프롬프트의 "GitLab"·"MR" 문구를 고친다(출력 틀의 절 제목은 화면 강조 규칙과 맞물리므로 바꾸면 보고).
- (T-006 검토 M-5 후속) `GitHubClient`의 모든 HTTP 호출에 연결·응답 제한 시간을 둔다(기존 `GitLabClient`는 없어 메신저 수신 루프가 무기한 막힐 수 있었다). 메신저 `이슈 스캔`은 라우터가 동기 실행하므로, 스캔을 비동기로 접수하고 결과는 알림으로 보내는 방식을 검토한다 — 대시보드 「가져오기」(`/api/issues-command?cmd=스캔`)가 같은 반환 문구("N건 큐 추가")를 쓰므로 화면 동작을 바꾸게 되면 05·06 변경으로 보고한다.

## 범위 밖
- 패키지·클래스·컬럼·설정 키 개명(T-011), 화면 문구(T-012)
- **조직(organization) 저장소** 지원 — 대상은 개인 계정 저장소만(OI-09 ①). 조직 저장소를 막는 별도 코드는 만들지 않는다(접근 불가로 Doctor에 드러난다)
- 포트(인터페이스) 도입, `gh` CLI 사용, GitHub App
- 실제 GitHub를 호출하는 자동 테스트(사용자가 수동 확인)
- `deploy/README.md` 빌드 도구 절의 저장소별 실제 도구 목록(첫 대상 저장소를 사용자가 정할 때), 서버에 도구 설치·캐시 채우기 실행(사용자)

## 완료 조건
- [ ] `TC-015`, `TC-016`, `TC-017`이 단위 테스트로 존재하고 통과한다
- [ ] 저장소에 GitLab REST 호출 코드가 없다(`/api/v4`, `merge_requests` 검색 0)
- [ ] `ChildEnv.SECRET_KEYS`에 `GITHUB_TOKEN`이 있고, `${GITHUB_TOKEN}`을 쓴 MCP 저장이 거부되는 단위 테스트가 통과한다
- [ ] git 인증 헤더가 GitHub 호스트로 한정된 키로 주입된다(단위 테스트로 환경변수 키·값 형식 확인, 토큰 값은 단언문 밖으로 출력하지 않음)
- [ ] 토큰 없이 기동되고 Doctor가 원인과 고치는 방법을 보여 준다. 만료 임박(14일 이하)·만료 헤더 없음·401·저장소 404·rate limit이 각각 다른 상태·문구로 나오는 단위 테스트가 있다(가짜 HTTP)
- [ ] `.env.example`·`dist/env.template`·`deploy/env.server.example`에 GitLab PAT scope 주석이 없고 fine-grained 권한 안내가 있다
- [ ] `deploy/README.md`에 "대상 저장소 빌드 도구" 절(틀)이 있고 기존 절 번호가 그대로다
- [ ] "사용자가 서버에서 확인할 것"(실제 이슈 1건: 스캔 → Draft PR → 코멘트, Doctor의 만료일 표시) 절차가 변경 설명서에 있다
- [ ] 변경 설명서(`docs/tasks/results/T-010-github-연동.md`)가 그림·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-010-github-연동.md](./results/T-010-github-연동.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -rnE "api/v4|merge_requests|GitLabClient" src/main/java ; test $? -eq 1
grep -n '"GITHUB_TOKEN"' src/main/java/io/github/lib0823/devagent/platform/ChildEnv.java
grep -rnE "write_repository" .env.example src/main/resources/dist/env.template deploy/env.server.example ; test $? -eq 1
grep -n "대상 저장소 빌드 도구" deploy/README.md
```

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(단계 수준) |
| 2026-11-24 | 대기 | PM 수정(T-006 검수): M-5 후속(HTTP 제한 시간, `이슈 스캔` 비동기 접수 검토)을 범위에 추가 |
| 2026-11-25 | 대기 | PM 수정: `depends`에 T-021(OI-27 결정 — 외부 저장소 빌드 전에 자식 프로세스 환경 정리) |
| 2026-11-25 | 대기 | PM 수정: `depends`에 T-023(OI-30·OI-22 결정 — 외부 이슈 본문이 입력이 되기 전에 리뷰어 편집 도구·에이전트 curl 금지). 대상 프로젝트 검증 빌드의 JDK는 서버 unit JDK 21 고정(OI-29 ①, T-022) 전제 |
| 2026-11-25 | 대기 | PM 수정: OI-10 결정으로 브랜치 범위 확정(01 FR-304), `depends`에 T-024(OI-33·OI-34 결정 — 분석·리서치 세션 편집 도구 금지, 관리 세션 권한 확대 경로 금지). 남은 선결: OI-09·OI-15 |
| 2026-11-25 | 대기 | PM 보완(OI-09 ①·OI-15 ① 반영, 코드 대조): 비밀 키 목록·MCP 자리표시자·설정 예시 권한 주석, git 인증 헤더 호스트 한정, Doctor 만료 점검(임계 14일, PM 결정), `deploy/README.md` 빌드 도구 절 틀, 조직 저장소 범위 밖. 관련 코드 경로 행 번호를 HEAD `7663f4d` 기준으로 갱신, 완료 조건·검증 명령 추가. 착수 가능 |

# T-028. GitHub 내 저장소 목록 API와 대상 프로젝트 일괄 저장

```
id: T-028
담당: backend-dev (backend-dev)
상태: 대기
depends: []
병렬: 불가
충돌: [T-029]   # T-029가 이 작업의 API를 쓴다 — T-028 완료 뒤 착수
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 구현
QA 확인: 아니오   # 화면 동작 확인은 T-029 qa(TC-027). 실제 GitHub는 QA 환경에 토큰이 없어 단위 테스트(TC-026, 가짜 HTTP)로 지킨다
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-27
```

## 목적
토큰이 접근할 수 있는 **내 소유 GitHub 저장소 목록**을 화면이 받아 갈 수 있게 하고, 화면에서 고른 저장소 묶음을 대상 프로젝트로 **한 번에** 저장한다. 처리 범위(대상 프로젝트 목록 밖은 처리하지 않음)는 그대로 지킨다.

## 근거
- 결정: `OI-09` 결정 변경(2026-11-27, 사용자 — 토큰 Repository access "All repositories"), `OI-42`(세부 기준 가정: 소유 저장소만·보관 저장소 고를 수 없음·목록 1,000개·대상 50개·캐시 60초·직접 입력 유지) ([00-INDEX](../00-INDEX.md))
- 요구사항: `FR-301`(대상 저장소 등록·삭제), `FR-307`(토큰 하나) ([01 §4.3](../01-requirements.md))
- 화면 규칙(이 API를 쓰는 쪽): [06 §3.6 S-05](../06-functional-specification.md) 「GitHub 저장소 고르기」 업무 규칙 ①~⑦
- 기존 API: [05 §2.3](../05-api-specification.md) `/api/issues-projects`, `/add`, `/remove`
- 테스트: `TC-026` ([07 §4](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), 루트 `CLAUDE.md` 「코드 작업 규칙」·「이 환경에서 확인된 함정」(빈 생성자 둘 → `@Autowired`, 기동 확인)

## 관련 코드 경로
(패키지 접두 `src/main/java/io/github/lib0823/devagent/` 생략)
- 수정: `issue/GitHubClient.java`(새 조회 메서드 — 기존 타임아웃·5MB 상한·https·`***` 마스킹을 그대로 탄다), `issue/IssueProjects.java`(목록 캐시·일괄 저장), `issue/IssueAgentService.java` 1572행 근처 「대상 프로젝트(위임)」(필요하면 위임 메서드), `admin/AdminController.java`(48·123·129행 근처)
- 안내 문구 수정: `README.md`(284·331행 근처), `.env.example`(21~22행), `deploy/env.server.example`(23행), `src/main/resources/dist/env.template`(6행), `config/GitHubProperties.java`(9행 주석), `src/main/resources/application.yml`(68행 주석), `ops/GitHubCheck.java`(32행 "고치는 방법" 문구), 루트 `CLAUDE.md` 「이슈 에이전트 사용법」 첫 줄(대상 프로젝트 바꾸는 방법)
- 버전: `build.gradle` `version`
- 참고(바꾸지 않음): `issue/IssueReferences.java` `isTarget`(참고 저장소 대상 제한), `research/ResearchService.java` `normalizeProjects`(리서치 대상 제한), `config/AccessControlFilter.java`(상태 변경 요청 Origin 확인), `agent/claude/ClaudeCliAgent.java` 202~207행(플랫폼 관리 세션의 `api/issues-projects` 금지 패턴)
- 테스트 참고: `src/test/java/.../issue/FakeGitHubServer.java`, `GitHubClientTest.java`, `GitHubSecurityTest.java`

## 범위
1. **`GitHubClient` 내 저장소 목록**: `GET /user/repos?affiliation=owner&sort=updated&per_page=100&page=n`을 쪽마다 부르고, 받은 쪽이 100개 미만이거나 `Link` 헤더에 `rel="next"`가 없으면 멈춘다. 최대 10쪽(1,000개) — 넘으면 `truncated=true`. 항목: `path`(`full_name`), `defaultBranch`, `private`, `archived`, `fork`, `updatedAt`(ISO 문자열 그대로), `webUrl`. 안전을 위해 `owner.type`이 `User`가 아닌 항목은 버린다(OI-42 ① — 조직 저장소 제외). 오류는 기존 `GitHubException.Kind`로 구분(토큰 없음 / 401 / 403 / 그 밖).
2. **`GET /api/issues-repos?refresh=`**: 응답 `{ ok, reason?, repos:[{path, defaultBranch, private, archived, fork, updatedAt, webUrl, selected}], truncated, max }`. `selected`는 현재 대상 프로젝트에 있으면 true(대소문자 무시). `max`는 대상 상한(50). 실패하면 HTTP 200에 `ok:false` + 사람이 읽는 `reason`(토큰 없음 / 토큰이 유효하지 않거나 만료 / 권한 부족·호출 한도(서버 문구) / 그 밖) — 토큰·헤더 값은 절대 싣지 않는다. 결과는 메모리에 60초 캐시, `refresh=true`면 캐시를 무시한다(`selected`는 캐시와 무관하게 매번 현재 목록으로 계산). 이 API는 화면이 창을 열 때만 부른다 — `/api/system/status`·Doctor·스케줄러에서 부르지 않는다.
3. **`POST /api/issues-projects/set`**(form, `path` 여러 개 — 0개 허용): 보낸 묶음을 새 대상 목록으로 저장한다. 표기 검사(`owner/repo` 정규식) → 새로 들어오는 저장소만 기존 `addProject`와 같은 방식으로 GitHub 확인·정규화(기본 브랜치 확인) → 실패한 것은 `rejected:[{path, reason}]`로 돌려주고 저장하지 않는다(나머지는 저장). 기존 대상 중 빠진 것은 `removed`. 상한(50) 초과면 아무것도 저장하지 않고 거부. 응답 `{ result, added, removed, rejected }`(`result`는 한 줄 요약). 추가가 하나라도 있으면 스캔은 **한 번만**(`setOnAdded`). 진행 중 세션은 `removeProject`와 같이 유지. 경로가 `api/issues-projects` 아래라 플랫폼 관리 세션 금지 패턴과 Origin 확인을 그대로 받아야 한다 — 둘 다 단위 테스트로 확인한다(필터는 다른 Origin의 POST를 403으로, `ClaudeCliAgent`의 금지 패턴이 `/api/issues-projects/set`을 덮음).
4. 기존 `/api/issues-projects`, `/add`, `/remove`와 메신저 `이슈 프로젝트 추가|삭제`는 그대로 둔다(OI-42 ⑥). `/add`에도 상한(50)을 적용한다.
5. **처리 범위 유지 확인**: 스캔·수동 시작·이슈 등록·리서치·참고 저장소가 여전히 대상 목록으로 제한되는지 코드로 확인하고, 참고 저장소(`IssueReferences.isTarget`)·리서치(`normalizeProjects`)에 대상 밖 저장소가 거부되는 단위 테스트가 없으면 추가한다(동작 변경 없음).
6. **안내 문구 갱신**(관련 코드 경로의 안내 문구 목록): 토큰 Repository access는 **"All repositories"**(개인 계정 저장소 전부, 조직 저장소 제외), 권한·만료는 그대로. 위험 한 줄 — "토큰이 새면 개인 저장소 전부가 노출된다. 서버 `.env`(권한 600)에만 두고, 앱은 대상 프로젝트로 고른 저장소만 처리한다". 대상 프로젝트는 플랫폼 탭 「GitHub에서 고르기」(T-029) 또는 직접 입력·메신저 명령으로 바꾼다. README 설정 표에 새 API 두 개를 05와 같은 표기로 적을 곳이 있으면 적는다.
7. **버전 2.3.0**(minor — 화면·API 추가). 커밋은 T-029와 한 묶음(메인 세션).
8. TC-026 단위 테스트(JUnit 5, Spring 없이, `FakeGitHubServer` 활용): 07 TC-026의 ①②③ 전부.

## 범위 밖
- 화면(`static/` 전부) — T-029
- 스캔 방식 변경("열린 이슈 전부 자동" 금지 — 사용자 결정), `ai-fix` 라벨 규칙, 이슈 처리 흐름
- 조직 저장소 지원, 저장소별 설정(라벨·브랜치), DB 스키마 변경(대상 목록 저장 위치는 기존 `bot_setting.projects` 그대로)
- Doctor 항목 추가(문구만 고침), 문서 `docs/` 01~07(02 D-6·04 §9 위험·05 §2.3은 PM이 검수 때)

## 완료 조건
- [ ] `TC-026`이 자동 테스트로 존재하고 통과한다. `./gradlew test bootJar` 실패 0
- [ ] `GET /api/issues-repos`·`POST /api/issues-projects/set`이 범위 2·3의 응답 형식대로 동작하고, 다른 Origin의 `set` POST가 403, 플랫폼 관리 세션 금지 패턴이 `set`을 덮는다(단위 테스트)
- [ ] 토큰 값이 응답·로그·예외 메시지에 없다(`GitHubSecurityTest` 계열에 목록 조회 경로 추가)
- [ ] 저장소(`docs/` 제외)에 "Only select repositories"·"대상 저장소만" 안내가 남아 있지 않고, 범위 6의 위험 문구가 README·`deploy/env.server.example`·`.env.example`에 있다
- [ ] `build.gradle` 버전 2.3.0, 새 빌드로 8778 격리 기동 뒤 `/api/system/status` 200(04 §8 띄우기 명령 — 빈 배선 확인)
- [ ] 명세(05 §2.3·06 §3.6)와 구현이 다르면 응답에 보고했다
- [ ] 변경 설명서(`docs/tasks/results/T-028-github-저장소-목록-api.md`)가 그림(화면 → API → `IssueProjects` → `GitHubClient`/`bot_setting`, 일괄 저장 흐름)·설명과 함께 있고, 남는 위험(토큰 범위 확대, 플랫폼 관리 세션은 GET으로 비공개 저장소 이름을 읽을 수 있음)을 적었다

## 변경 설명서
개발 에이전트가 작성: [results/T-028-github-저장소-목록-api.md](./results/T-028-github-저장소-목록-api.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -n "affiliation=owner" src/main/java/io/github/lib0823/devagent/issue/GitHubClient.java
grep -nE "issues-repos|issues-projects/set" src/main/java/io/github/lib0823/devagent/admin/AdminController.java
grep -rn "Only select repositories\|대상 저장소만" README.md .env.example deploy src/main CLAUDE.md   # 0줄이어야 한다
grep -n "version = '2.3.0'" build.gradle
```

## 검증 증거
개발 에이전트가 작성: 실행한 명령, 결과 요약(통과/실패 수), 일시.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-27 | 대기 | PM 작성(사용자 요청 — GitHub 저장소 고르기, OI-09 결정 변경, OI-42 가정). security-reviewer 1회 권장(외부 API·토큰·상태 변경 API) |

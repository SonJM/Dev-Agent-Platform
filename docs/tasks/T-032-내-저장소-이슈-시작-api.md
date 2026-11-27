# T-032. 대상 아닌 내 저장소의 이슈 목록 조회와 「시작 = 대상 추가 후 처리」 API

```
id: T-032
담당: backend-dev (backend-dev)
상태: 완료
depends: []
병렬: 불가
충돌: [T-033]
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 구현
QA 확인: 아니오
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-27
```

## 목적
에이전트 탭 「이슈 목록」에서 대상 프로젝트로 등록하지 않은 **내 GitHub 저장소**도 이슈를 보고 골라 시작할 수 있게 서버를 넓힌다. 시작하면 검사를 모두 통과한 뒤에만 그 저장소를 대상 프로젝트에 넣고 처리한다(대상 목록 밖 저장소는 처리하지 않는다는 보장 유지). 화면은 T-033.

## 근거
- API: [05 §2.3](../05-api-specification.md) `GET /api/issues-list`(2.4.0 변경) · `POST /api/issues-start`(신규) 행, 변경 이력 0.10의 보안 판단
- 화면 규칙(서버가 받쳐야 할 것): [06 §3.3](../06-functional-specification.md) S-02 「내 저장소에서 바로 고르기」 ①·③·⑤
- 요구사항: `FR-301`, `FR-306` ([01 §4.3](../01-requirements.md))
- 테스트: `TC-030` ([07 §4](../07-test-plan.md))
- 기존 보장: 대상 상한 50·조직 저장소 거부(`OI-42`, T-028), 신뢰 작성자 확인(`github.trusted-authors-only`), 상태 변경은 Origin 확인(05 §1.2 `AccessControlFilter` 일괄), 플랫폼 관리 세션 금지 API(`ClaudeCliAgent.MAINTAIN_DENIED_APIS`, T-024·T-028 R5)
- 코딩 규약: 루트 `CLAUDE.md` 「코드 작업 규칙」·「버전」(minor → **2.4.0**)

## 관련 코드 경로
- 수정: `admin/AdminController.java`(`/api/issues-list` 160행 근처, 새 `/api/issues-start`), `issue/IssueAgentService.java`(`openIssueList` 426행, `startManual` 575행, `untrustedReason` 233행), `issue/IssueProjects.java`(`repoList`·`addProject`·`resolveProject`, `onAdded` 스캔), `agent/claude/ClaudeCliAgent.java`(`MAINTAIN_DENIED_APIS` 205행), `build.gradle`(`version`), 루트 `CLAUDE.md`(「이슈 에이전트 사용법」의 「화면에서 이슈 등록」 단락 — 이슈 목록 창 설명 한두 줄)
- 참고: `src/test/java`의 `RepoPickerTest`·`ClaudeCliAgentTest`(가짜 HTTP·금지 패턴 시험 방식)
- 패키지 경로 앞부분: `src/main/java/io/github/lib0823/devagent/`

## 범위
1. `GET /api/issues-list?project=`: 대상이면 지금처럼. 대상이 아니면 `repoList(false)`(60초 캐시 재사용 — 새 GitHub 호출 경로를 만들지 않는다)의 `repos[].path`와 대소문자 무시로 맞춰 정규 경로로 조회. 둘 다 아니면 GitHub 이슈 조회 없이 `ok:false` + 문구, 저장소 목록 조회가 실패했으면 그 `reason`. 응답에 `target`·`archived`, 항목에 `blocked`(`untrustedReason` 결과, 처리 가능하면 넣지 않음). 대상 아닌 저장소를 `projectCache`에 영구로 남기지 않는다(넣는다면 대상 삭제 때처럼 정리되는지 설명서에 적는다).
2. `POST /api/issues-start`(form `project`, `number`): 05 §2.3 순서 ①~⑤ 그대로. **③까지 실패하면 대상 목록을 바꾸지 않는다.** ④ 추가는 기존 `addProject`의 검사(존재·`isUserOwned`·상한 50)를 그대로 거친다(검사를 복제하지 말고 재사용). 보관 저장소는 대상 아닌 경우 거부(목록의 `archived` 기준). 응답 `{ ok, result, added, ref }`. `ok`는 문구 정규식이 아니라 구조로 판정(필요하면 `startManual`이 결과 객체를 돌려주도록 내부만 정리 — 메신저 `이슈 owner/repo#n` 명령의 동작·문구는 바꾸지 않는다).
3. 대상 추가 뒤 스캔(`onAdded`)과 수동 시작이 같은 이슈를 집어도 세션이 둘 생기지 않고 `ok:true`가 나오게 순서를 정한다(예: 추가 → 수동 시작 → 스캔). 방법과 이유를 설명서에 적는다.
4. `MAINTAIN_DENIED_APIS`에 `api/issues-start`·`api/issues-list` 추가(목록을 고치면 클래스 주석의 이유 줄도).
5. `build.gradle` `version` → `2.4.0`.
6. 단위 테스트(TC-030 ①~⑥, JUnit 5, Spring 컨텍스트 없이). 토큰 값이 응답·로그·예외 문구에 없는지 포함.

## 범위 밖
- 화면(`static/` 전부) — T-033
- `/api/issues-command`·메신저 명령의 대상 판정(대상만 — 그대로), `/api/issues-create`(대상만), 스캔 라벨 규칙, `/api/issues-repos`·`/set`의 형식
- 다른 사람·조직 저장소의 이슈 조회 허용, 대상 자동 삭제
- 범위 밖 결함·개선은 설명서 `## 제안`으로

## 완료 조건
- [ ] 05 §2.3의 두 행과 구현이 같다(다르면 응답에 보고)
- [ ] 대상 아님 + 검사 실패(보관·이슈 없음·닫힘·PR·신뢰 작성자 아님·진행 중·조직 소유·상한) 때 대상 목록이 그대로임을 테스트로 보였다
- [ ] 다른 사람 저장소는 GitHub 이슈 조회 없이 거부, 저장소 목록 확인은 캐시를 쓴다(테스트)
- [ ] 같은 이슈 세션이 둘 생기지 않는다(TC-030 ⑤)
- [ ] 금지 패턴이 두 경로를 덮는다(TC-030 ⑥)
- [ ] `gradlew test bootJar` 전부 통과, 버전 2.4.0
- [ ] 빈 배선 확인: `java -jar build/libs/dev-agent.jar --server.port=8778`(내장 DB, `ISSUE_ENABLED`는 기본) 기동 뒤 `GET /api/system/status`가 200·`2.4.0`, `POST /api/issues-start`(Origin 없음, 토큰 없음)이 대상 목록을 바꾸지 않고 `ok:false` 문구를 준다 — 결과를 설명서 `## 검증`에
- [ ] 변경 설명서(`docs/tasks/results/T-032-내-저장소-이슈-시작-api.md`)에 그림(`issues-start` 판정 순서 흐름도 — 어디서 실패하면 대상이 그대로인지)과 설명, 파일 지도, 지시서 밖 추가

## 변경 설명서
개발 에이전트가 작성: [results/T-032-내-저장소-이슈-시작-api.md](./results/T-032-내-저장소-이슈-시작-api.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -n "version" build.gradle | head -3
grep -nE "api/issues-start|api/issues-list" src/main/java/io/github/lib0823/devagent/agent/claude/ClaudeCliAgent.java
grep -n "issues-start" src/main/java/io/github/lib0823/devagent/admin/AdminController.java
```

## 검증 증거
- 일시: 2026-11-27 (재작업 R1~R3)
- 명령: ./gradlew.bat test bootJar; grep version; 8778 기동 확인(e2e/scripts/start-app.sh, /api/system/shutdown)
- 결과: 전체 테스트 통과(exit 0), IssueStartTest R1~R3 4건 추가·RepoPickerTest 실패 캐시 시험 갱신, version 2.4.0, /api/system/status 2.4.0, issues-start 대상 목록 불변·ok:false 문구(ISSUE_ENABLED 꺼진 기동 — 한계는 설명서 `## 검증`)
- 변경 설명서: [results/T-032-내-저장소-이슈-시작-api.md](./results/T-032-내-저장소-이슈-시작-api.md)

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-27 | 대기 | PM 작성(사용자 요청 — 에이전트 탭에서 내 저장소를 바로 골라 시작, 시작하면 대상 자동 추가). 05 §2.3·06 §3.3·07 TC-030 반영. QA 확인: 아니오(단위 TC-030, 화면 확인은 T-033 qa). 보안 영향(비공개 저장소 이슈 열람 범위 확대·대상 자동 추가) → security-reviewer 1회 |
| 2026-11-27 | 반려 | PM 검수: 검증 명령 재실행 통과(`gradlew test bootJar` BUILD SUCCESSFUL, version 2.4.0, 금지 패턴 209행, 매핑 172행), 05 §2.3 두 행과 구현 일치, 지시서 변조 없음, 파일 지도와 `git status` 일치. security-reviewer(1회) CRITICAL·HIGH 0이나 **화면 버튼이 새 진입점이 되면서 커지는 동시성·호출 한도 문제 3건을 이번에 고친다**(재작업 R1~R3). 나머지 L2·L3는 OI-45, L5는 05 §2.3 명시 동작이라 그대로. 빈 배선은 `ISSUE_ENABLED` 꺼진 기동이라 비활성 분기만 확인됐다 — 재작업 뒤에는 이 한계를 설명서 `## 검증`에 그대로 적으면 된다(토큰 없음 분기는 단위 테스트로 인정).<br>**R1 (M1, 종료 세션 재시작 경쟁)** `IssueAgentService.enqueueManual`의 종료 세션 재시작을 조건부 갱신으로 바꾼다: `UPDATE … WHERE id=? AND state IN ('DONE','ABORTED','FAILED')`(종료 상태 목록은 `IssueSession`의 상수에서, 매퍼 145행 `WHERE id AND state = #{from}` 방식 참고)로 상태·초기화 필드를 한 번에 쓰고, 0건이면 이벤트를 지우지 않고 `StartResult(false, "<ref> 는 이미 진행 중입니다 …")`(메신저 문구와 같은 형식). `sessions.update(행 전체)`로 덮어쓰지 않는다. 상태 기록은 가능하면 `transition()`과 같은 이벤트 기록을 남긴다(어렵다면 이유를 설명서 `## 주요 결정`에). DB 스키마 변경은 없어야 한다(있으면 `ensureTable()`·`schema.sql` 둘 다). 테스트: 조건부 갱신이 0건일 때 거부·이벤트 미삭제·`claudeSessionId` 미변경, 1건일 때 NEW 재시작.<br>**R2 (L1, 목록 조회 실패 반복 호출)** `IssueProjects.cachedRepos`: 조회 실패 결과(`reason`)도 30초 캐시하고 `refresh=false` 호출은 그 동안 GitHub를 다시 부르지 않는다. `refresh=true`의 최소 10초 간격(OI-42 ⑧)은 그대로. 테스트: 실패 뒤 30초 안 `issues-list`(비대상) 두 번 → `ownRepos` 호출 1회, 같은 `reason`.<br>**R3 (L4, ④~⑤ 사이 주기 스캔)** `startIssue`의 ⑤에서 같은 이슈의 세션이 이미 `NEW`(아직 분석 전, 큐 대기)면 `ok:true` + "<ref> 는 이미 큐에 있습니다."로 돌려준다(`added`는 그대로). 이 처리는 `startIssue` 경로에만 두고 메신저 `이슈 owner/repo#n`의 동작·문구는 바꾸지 않는다. ③의 진행 중 거부는 그대로(05 §2.3 갱신본 참고). 테스트: ④ 뒤 NEW 세션이 끼어든 상황 → `ok:true`, 세션 1개.<br>공통: `gradlew test bootJar` 전부 통과, 버전 2.4.0 유지(같은 묶음), 설명서 그림 1의 ⑤ 분기와 `## 주요 결정`·`## 파일 지도`·`## 검증`을 갱신. 검토 에이전트는 다시 부르지 않고 PM 재검수 |
| 2026-11-27 | 완료 | PM 재검수(R1~R3) 통과: 검증 명령 재실행 `gradlew test bootJar` BUILD SUCCESSFUL(288건 실패 0), version 2.4.0, 금지 패턴 209행, 매핑 172행. 지시서 변조 없음, 파일 지도와 `git status` 일치. R1 `restartIfFinished` 조건부 UPDATE·0건이면 이벤트 미삭제 거부, R2 실패 30초 캐시, R3 ⑤ `NEW` → `ok:true` 확인(테스트 4건 + `RepoPickerTest` 실패 캐시 시험 갱신은 R2와 충돌한 옛 시험이라 인정).<br>**판단 ① SQL 리터럴 허용**: 지시는 `IssueSession` 상수였으나 MyBatis 어노테이션 SQL 안 리터럴 `'DONE','ABORTED','FAILED'`가 상수 값·`isActive()` 판정과 같고, 컬럼 이름은 기존 `update()`와 PM이 대조해 일치. 종료 상태가 늘면 두 곳을 함께 고쳐야 하는 점은 매퍼 주석에 상태 이름이 적혀 있어 감수.<br>**판단 ② 실제 PostgreSQL 실행 미확인**: 단위 테스트는 가짜 매퍼, 8778 기동은 `ISSUE_ENABLED` 꺼짐이라 MyBatis 파싱만 통과. T-033 qa(TC-031)는 요청 가로채기라 이 SQL을 타지 않으므로 **서버 2.4.0 반영 확인 항목**으로 넘긴다(07 추적표 FR-301·FR-306 행): 종료된 세션 1건을 「이슈 목록」에서 「다시 시작」 → `NEW`로 큐 등록, `logs/dev-agent.log`에 SQL 오류 없음 |

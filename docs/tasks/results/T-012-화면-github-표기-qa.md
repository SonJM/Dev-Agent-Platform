# T-012 QA 보고서 — 화면의 GitHub 표기 반영 (T-011 응답 필드·설정 개명 회귀 포함)
> 담당: qa (qa) · 2026-11-26 · 지시서: [T-012](../T-012-화면-github-표기.md) · 설명서: [T-012-화면-github-표기.md](./T-012-화면-github-표기.md)

## 판정
**QA PASS (재확인, 2026-11-26)** — 아래 `## 재확인` 참조. 직전 판정은 QA FAIL(변경 오류 1, LOW)이었고 그 1건이 고쳐져 해소됐다. 이하 원판정:
**QA FAIL** — 변경 오류 1(LOW, 화면 문구), 회귀 0, 기존 결함 0. 핵심 묶음 @core 10개 테스트(8 TC 묶음) 전부 통과. 기준 기록: qa-baseline.md 2026-11-26 09:05 (이번 실행으로 한 행 추가).

## 요약
- 작업 트리로 다시 빌드한 jar(`./gradlew bootJar`, 이슈 연동 켬·GitHub 주소는 닿지 않는 로컬 주소)에 세션 600여 건 + 이벤트 2,000건을 심어 에이전트·홈·메신저·플랫폼·리서치 화면을 확인했다. 목록 `owner/repo#n`(issueNumber), 목록 PR 배지, 상세 Pull Request 링크, kind REMOTE 이벤트 렌더(아이콘·색·타임라인)는 모두 기대와 같다.
- T-011 응답 필드 개명(`issueNumber`·`prUrl`, `workId`·`projectId` 제거, `REMOTE`)을 쓰는 화면에 `undefined`·콘솔 오류가 없다. 핵심 묶음 8개 TC(10개 테스트)는 세션이 없는 DB로 통과했다.
- 실패 1건: 리뷰 확인 대기 세션의 안내 문구에 「Draft MR을 만듭니다」가 남아 있다(`agent.html` 694행). 범위의 "MR → PR"에 해당하지만 지시서 검증 명령(`gitlab|issueIid|mrUrl|projectId|workId` 검색)이 `MR`을 보지 않아 놓쳤다.

## 화면 흐름
### TC-T012-1 에이전트 화면: 세션 목록 issueNumber·PR 배지, 상세 이슈·PR 링크 — 실패(소프트 단언 1건, 나머지 단계 통과)
1. [세션 목록](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/01-agent-list.png) — `qa-sample #9002`·`#9001`, PR 있는 세션에만 「PR」 배지(#9001에는 없음)
2. [상세: 이슈·PR 링크](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/02-agent-detail-pr-link.png) — 이슈 #9002, 「이슈」·`fix/9002`·「Pull Request」 링크, href가 `/pull/9002`로 끝남. **안내 문구에 「Draft MR」**(실패 지점)
3. [REMOTE 이벤트 렌더](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/03-agent-remote-event.png) · [로그 요소 확대](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/03b-agent-log-element.png) — kind REMOTE 줄이 보임(아이콘 ⬡, 색 rgb(253,186,116)), 10종 이벤트 전부 렌더
4. [타임라인 탭](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/04-agent-timeline-remote.png) — `.ev.k-REMOTE` 보임. PR 없는 세션(#9001)에는 Pull Request 링크 없음(단언 통과)
- 기대 결과: 화면 문구에 MR이 남지 않고, 이슈·PR 링크·REMOTE 이벤트가 GitHub 표기로 보인다 / 실제: 링크·배지·이벤트는 기대대로, 리뷰 확인 대기 안내문만 「MR」

### TC-T012-2 홈·메신저·플랫폼·리서치: 이슈 표기와 GitHub 용어 — 통과
1. [홈](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-2-홈·메신저·플랫폼-이슈-표기와-GitHub-용어-chromium/01-home-sessions.png) — 확인 대기 `qa-group/qa-sample#9001`, 최근 세션 `qa-bulk#n`, 개수 표시, `undefined` 없음
2. [메신저](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-2-홈·메신저·플랫폼-이슈-표기와-GitHub-용어-chromium/02-messenger-examples.png) — 「이슈 owner/repo#12」·「질문 owner/repo: …」 예시, GitLab·MR·issueIid·mrUrl 없음
3. [플랫폼](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-2-홈·메신저·플랫폼-이슈-표기와-GitHub-용어-chromium/03-platform-project-input.png) — 프로젝트 입력 자리 표시 「추가: owner/repo」
4. [리서치](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-2-홈·메신저·플랫폼-이슈-표기와-GitHub-용어-chromium/04-research.png) — 본문에 GitLab·MR 없음(최종 화면), 콘솔 오류 0
- 기대 결과: GitHub 표기, `owner/repo#n` / 실제: 같음

## 실패
| TC | 단계 | 기대 | 실제 | 분류·근거 | 재현 단계 | 증거 |
|---|---|---|---|---|---|---|
| TC-T012-1 | 상세 화면(리뷰 확인 대기) | 화면 텍스트에 「MR」 없음(범위 "MR → PR") | 안내 문구 「…커밋·푸시하고 Draft MR을 만듭니다」 | 변경 오류 — `static/agent.html:694`는 지시서 `관련 코드 경로` 안. 검증 명령은 `MR`을 검색하지 않아 통과했다. 같은 세션 상태의 서버 문구 `PUSHING` 단계 이름은 「푸시 · PR」로 바뀌어 있어 이 한 곳만 남았다 | REVIEW_WAIT 상태 세션을 에이전트 화면에서 선택 → 경고 박스 | [02 스크린샷](./T-012-qa/task/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/02-agent-detail-pr-link.png) |

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-006 | 통과 | 홈 → 다섯 탭 이동 |
| TC-007 | 통과 | 스킬 등록·삭제 |
| TC-008 | 통과 | 루틴 등록·삭제 |
| TC-009 | 통과 | 알림함 |
| TC-010 | 통과 | 에이전트·리서치 빈 상태 |
| TC-023 | 통과 | 다른 출처 POST 403 |
| TC-013 | 통과 | 제거 요소·API (+TC-014) |
| TC-025 | 통과 | 묶음 가져오기 3개 테스트 |

핵심 묶음 10개 테스트 통과(skipped 0), 실행 58초. 이번 변경 TC(`@task:T-012`) 2개 실행, skipped 0. 07 §8 목록과 `e2e/core/` @core 일치(8 TC, 파일 8개).

## 데이터
- 규모 가정: 07 §3 — 이슈 세션 누적 수백 건(300) → 2배 = 600건, 세션당 이벤트 수천 행 → 한 세션에 2,000건.
- 생성: 이슈 세션 602건(이름 있는 6건 + 대량 596건) + 이벤트 2,011건, 경계값 2건(이모지·300자 제목·마크업 문자, 빈 제목). seed 마지막 줄 `seed: 2613 rows, boundary 2`. 스킬·루틴 90행은 핵심 묶음 실행에서 `seed.ts`로 주입(경계값 4).
- 주입 경로: 세션·이벤트는 API가 없어 **로컬 내장 DB(54329)에 SQL**(`e2e/seed/IssueSessions.java`, 고정 난수 seed, DONE·FAILED·ABORTED·DESIGN_WAIT·REVIEW_WAIT 상태만 — 작업자가 집지 않게). 스킬·루틴은 앱 API.
- 두 번으로 나눠 실행했다: 핵심 묶음 TC-006·010·013은 "세션 없음"(빈 상태 안내, 「세션을 선택하세요」)을 단언해 세션을 심으면 앱 회귀가 아닌데도 실패한다(첫 실행에서 3개 실패로 확인 — 시드 때문이며 앱 결함 아님). 그래서 핵심은 세션 없이, T-012는 세션 시드로 실행했다.
- 이슈 연동은 T-012 실행에서만 켰다(`ISSUE_ENABLED=true`, `GITHUB_API_URL`은 닿지 않는 로컬 주소, 토큰 없음). 핵심 묶음 실행은 04 기본값.
- 정리한 테스트: 없음(`e2e/tasks/`는 이번 파일만).
- 보조 파일: `e2e/seed/IssueSessions.java`(세션 시드, JDBC 드라이버는 jar에서 꺼낸 것을 `-cp`로), `e2e/tasks/T-012.spec.ts`(@task:T-012, 테스트 2개).

## 묶음 제안
- 추가 후보: `TC-T012-1` 중 REMOTE·PR 링크 렌더는 이슈 세션 시드가 필요해 핵심 묶음(세션 없음 전제)에 넣지 못한다. 세션을 쓰는 핵심 TC를 두려면 빈 상태 TC-006·010·013과 같은 DB로 못 돌리니 순서·정리 규칙이 필요하다. 지금은 넣지 않기를 권한다(07 §8 변경 없음).
- 제거 후보: 없음.

## 제안
- 개발 쪽 수정: `agent.html:694`의 「Draft MR」 → 「Draft PR」. 지시서 검증 명령에 `MR` 검색(한글 조사가 붙으므로 `\bMR\b` 말고 `MR`)을 더하면 같은 누락을 막는다. 서버 문구에 남은 MR(`IssueAgentService`·`GitRunner` 주석 등)은 화면 노출이 아니라 판단하지 않았다.
- 04 §8에 「세션 시드 확인은 `ISSUE_ENABLED=true` + 로컬 GitHub 주소로, 핵심 묶음과 따로 실행한다」를 적는 것을 권한다.
- `@task:T-012` 시험을 다시 돌리려면 `e2e/seed/IssueSessions.java` 주입 한 줄이 필요하다(위 데이터 절).

## 검증
- 로그(이번 변경 TC, `@task:T-012`): `<tmp>/qa-run.log`
```
    attachment #1: screenshot (image/png) ──────────────────────────────────────────────────────────
    ..\docs\tasks\results\T-012-qa\task\artifacts\tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium\test-failed-1.png
    ────────────────────────────────────────────────────────────────────────────────────────────────

    Error Context: ..\docs\tasks\results\T-012-qa\task\artifacts\tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium\error-context.md

    attachment #3: trace (application/zip) ─────────────────────────────────────────────────────────
    ..\docs\tasks\results\T-012-qa\task\artifacts\tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium\trace.zip
    Usage:

        npx playwright show-trace ..\docs\tasks\results\T-012-qa\task\artifacts\tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium\trace.zip

    ────────────────────────────────────────────────────────────────────────────────────────────────

  1 failed
    [chromium] › tasks\T-012.spec.ts:20:1 › TC-T012-1 에이전트 화면: 세션 목록 issueNumber·PR 배지, 상세 이슈·PR 링크 @task:T-012 
  1 passed (25.7s)
playwright exited with code 1
== down
QA FAIL: test
```
- 로그(핵심 묶음, `@core`): `<tmp>/qa-run.log`
```
== seed
seed: 90 rows, boundary 4
== test (@core)

Running 10 tests using 1 worker

  ✓   1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (5.9s)
  ✓   2 [chromium] › core\bundle-import.spec.ts:23:1 › TC-025 stdio MCP 묶음: 확인 창·체크 전 비활성·취소 시 저장 없음·체크 후 저장 @core (5.8s)
  ✓   3 [chromium] › core\bundle-import.spec.ts:72:1 › TC-025 stdio 없는 묶음(스킬 1개): 경고 없이 항목 수만, 가져오면 스킬 반영 @core (4.0s)
  ✓   4 [chromium] › core\bundle-import.spec.ts:107:1 › TC-025 390px: 확인 창 가로 넘침 0, 버튼이 보인다 @core (3.3s)
  ✓   5 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (2.1s)
  ✓   6 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (4.0s)
  ✓   7 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (6.9s)
  ✓   8 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (3.8s)
  ✓   9 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (6.8s)
  ✓  10 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (8.9s)

  10 passed (58.1s)
== down
QA PASS
```
- 첫 실행(세션 시드 + @core 동시)은 TC-006·010·013이 시드 때문에 실패해 폐기했다(위 데이터 절). 증거 디렉터리: `docs/tasks/results/T-012-qa/task`, `docs/tasks/results/T-012-qa/core`.
- 정리 확인: 두 실행 모두 `qa-run.sh`가 내림(down 실패 없음). 8778 응답 없음(curl 연결 거부), 8778·54329 리슨 0. 8777은 건드리지 않음. 프로세스 종료 거부 없음.

## 재확인
- 변경: 메인 세션이 `static/agent.html` 694행 안내 문구를 「이슈 브랜치(fix/이슈번호 등)로 커밋·푸시하고 Draft PR을 만듭니다」로 고치고 `./gradlew bootJar`로 jar를 다시 만들었다(작업 트리, 커밋 없음).
- 실행: 직전과 같은 방식(세션 시드, `ISSUE_ENABLED=true`, 닿지 않는 로컬 GitHub 주소, 토큰 없음)으로 `@task:T-012`만 다시 돌렸다. 앱 코드가 static 한 줄만 바뀌었으므로 @core는 다시 돌리지 않았다(직전 QA PASS, 기준 기록 그대로).
- 결과: TC-T012-1 통과(상세 `#detail`에 「MR」 없음 단언 포함, 이전 실패 지점), TC-T012-2 통과. `report.json` expected 2, unexpected 0, skipped 0. 실행 18.5초.
- static 전수 검색: `src/main/resources/static`에서 `MR`·`Merge Request`·`GitLab` 일치 0건.
- 증거: [에이전트 상세 PR 링크·안내 문구](./T-012-qa/task-recheck/artifacts/tasks-T-012-TC-T012-1-에이전트-6ec7f-sueNumber·PR-배지-상세-이슈·PR-링크-chromium/02-agent-detail-pr-link.png) · [리서치(TC-T012-2 최종 화면)](./T-012-qa/task-recheck/artifacts/tasks-T-012-TC-T012-2-홈·메신저·플랫폼-이슈-표기와-GitHub-용어-chromium/04-research.png)
- 정리 확인: `qa-run.sh`가 내림(down 실패 없음). 8778 응답 없음(curl 연결 거부), 8778·54329 리슨 0. 8777은 건드리지 않음.
- 판정: **QA PASS** (변경 오류 0, 회귀 0, 기존 결함 0).

```
== seed
seed: 2613 rows, boundary 2
== test (@task:T-012)

Running 2 tests using 1 worker

  ✓  1 [chromium] › tasks\T-012.spec.ts:20:1 › TC-T012-1 에이전트 화면: 세션 목록 issueNumber·PR 배지, 상세 이슈·PR 링크 @task:T-012 (9.0s)
  ✓  2 [chromium] › tasks\T-012.spec.ts:71:1 › TC-T012-2 홈·메신저·플랫폼: 이슈 표기와 GitHub 용어 @task:T-012 (4.0s)

  2 passed (18.5s)
== down
QA PASS
```

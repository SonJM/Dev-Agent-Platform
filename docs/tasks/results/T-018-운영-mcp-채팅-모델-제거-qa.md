# T-018 QA 보고서 — 사내 전용 기능 제거 ② 내장 운영 MCP·`운영` 질의·채팅 모델 포트
> 담당: qa (qa) · 2026-11-25 · 지시서: [T-018](../T-018-운영-mcp-채팅-모델-제거.md) · 설명서: [T-018-운영-mcp-채팅-모델-제거.md](./T-018-운영-mcp-채팅-모델-제거.md)

## 판정
**QA PASS.** 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 9개 실행 9개 통과, skipped 0(`report.json`). 기준 기록: [qa-baseline.md](./qa-baseline.md) 2026-11-25 10:55(T-007) 대비 회귀 없음, 이번 실행 행 추가.

## 요약
- 앱(2.0.0, 작업 트리 빌드 11:22 jar, 8778)을 띄워 제거한 API 3개(`/api/llm-test`·`/api/mcp-ask`·`/api/mcp-status`)가 GET·POST 모두 404이고 `/api/mcp-servers`·`/api/system/status`는 200인 것을 확인했다.
- 연동 카드(플랫폼)와 연동 상태 목록(홈)이 메신저·코딩 에이전트 2행만 남았고, `/api/platform` 응답에 `chatModel`·`toolChatModel` 필드가 없다.
- TC-023의 `/api/llm-test` 기대를 405에서 404로 고쳐 핵심 묶음 6개와 T-007·T-008 TC까지 함께 통과했다.

## 화면 흐름
### TC-014 (T-018분) 제거 API 3개 404 + 연동 카드 2행 — 통과
1. [홈](./T-018-qa/artifacts/tasks-T-018-TC-014-T-018-제-7172c-4이고-연동-카드가-메신저·코딩-에이전트-2행이다-chromium/01-home.png) — 연동 상태 목록 2행(`#homePorts .prow`)
2. [플랫폼 연동 카드](./T-018-qa/artifacts/tasks-T-018-TC-014-T-018-제-7172c-4이고-연동-카드가-메신저·코딩-에이전트-2행이다-chromium/02-platform-ports.png) — 최종 결과 화면. 연동 카드 제목 옆 `1/2 정상`, 행은 메신저(none, 주의)와 코딩 에이전트(claude-cli, 정상)
- 기대 결과: 연동 카드가 메신저·코딩 에이전트 2행, 채팅 모델·도구 모델 행 없음, 제거 API 404 / 실제: 같음. 카드 본문에 `채팅 모델`·`도구 모델`·`운영 MCP` 문구 없음, 플랫폼 MCP 서버 카드는 사용자 정의 서버 영역만 보임(등록 0건)
- 참고: `1/2 정상`은 QA 환경에서 메신저가 `none`(기본, 주의 표시)이라서다. 분모 2가 이번 변경의 확인 대상이며 단언은 분모만 고정했다.

### TC-023 접근 제어(핵심) — 통과
1. [변경 전](./T-018-qa/artifacts/core-access-control-TC-023-다른-출처의-상태-변경-요청은-경로-표기와-무관하게-403-chromium/01-before.png) — 플랫폼 화면
2. [새로고침 뒤](./T-018-qa/artifacts/core-access-control-TC-023-다른-출처의-상태-변경-요청은-경로-표기와-무관하게-403-chromium/02-after-reload.png) — 최종 결과 화면, 「+ 스킬 추가」 표시
- 기대 결과: 다른 출처 POST 403, `GET /api/llm-test` 404(T-018로 405에서 변경) / 실제: 같음

## 실패
없음

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-006 다섯 탭 이동 | 통과 | |
| TC-007 스킬 등록·삭제 | 통과 | |
| TC-008 루틴 등록·삭제 | 통과 | |
| TC-009 알림함 | 통과 | |
| TC-010 빈 상태 | 통과 | |
| TC-023 접근 제어 | 통과 | `llm-test` 기대 405→404로 qa가 수정 |
| (task) TC-014 T-018분 | 통과 | `e2e/tasks/T-018.spec.ts` |
| (task) TC-014 T-007분 | 통과 | 제거 API 8개 404 재확인 |
| (task) TC-013 T-008 시점 | 통과 | 정적 요소 제거 재확인 |

전체 실행 시간: 22초(상한 10분, 앱 기동 제외) · 07 §8 목록(TC-006~010, TC-023)과 `@core` 태그 일치(`e2e/core/` 6개 파일).

## 데이터
- 규모 가정: 이번 작업은 데이터 형태가 바뀌지 않는다(API·화면 제거). 직전 qa 실행들과 같게 데이터 주입 없음(0행). 경계값 0.
- `e2e/` 정리: `e2e/tasks/`에서 지운 파일 없음(T-007·T-008 spec은 TC-013 승격 지시서까지 유지).
- 수정: `e2e/core/access-control.spec.ts`(TC-023 기대 404, 주석), 신규 `e2e/tasks/T-018.spec.ts`.
- 증거 정리: `T-018-qa/report.json`의 로컬 홈 경로를 `~`로 치환했고 스크린샷은 `shot()`이 DOM의 계정 경로를 가렸다. `trace.zip`·`app.log`는 gitignore(성공 실행이라 trace 없음).

## 묶음 제안
- 07 §8 안내대로 T-018 PASS 뒤 TC-013 승격(qa 유지 지시서)에 `e2e/tasks/T-018.spec.ts`를 `T-008.spec.ts`에 합칠 것(연동 카드 2행 단언, 제거 API 3개 404). 연동 카드 단언은 상태 문구에 의존하지 않는 형태로 이미 써 두었다(메신저 none이면 `1/2 정상`).

## 제안
- 07 §8 "Phase 2 영향" 표의 TC-023 행은 반영 완료(404)로 갱신 가능. 05 §1.2 문장은 PM 처리분.
- FR-208·TC-014 추적: T-018분 확인 완료 기록 가능.

## 검증
- 로그: `/c/Users/Public/Documents/ESTsoft/CreatorTemp/qa-run-20261125-112900-267.log` (임시 폴더)
```
log: /c/Users/Public/Documents/ESTsoft/CreatorTemp/qa-run-20261125-112900-267.log
== up
== health (http://127.0.0.1:8778/api/system/status, timeout 150s)
== test (@task:T-018|@task:T-007|@task:T-008|@core)

Running 9 tests using 1 worker

  ✓  1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (2.4s)
  ✓  2 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (1.4s)
  ✓  3 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (1.9s)
  ✓  4 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (1.6s)
  ✓  5 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (3.2s)
  ✓  6 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (2.4s)
  ✓  7 [chromium] › tasks\T-007.spec.ts:22:1 › TC-014 T-007 제거한 API 8개가 404이고 서버 응답 기반 화면 행이 사라졌다 @task:T-007 (1.9s)
  ✓  8 [chromium] › tasks\T-008.spec.ts:29:1 › TC-013 T-008 화면 정리 — 제거 대상 요소가 없고 남은 화면이 정상 동작한다 @task:T-008 (2.3s)
  ✓  9 [chromium] › tasks\T-018.spec.ts:9:1 › TC-014 T-018 제거한 API 3개가 404이고 연동 카드가 메신저·코딩 에이전트 2행이다 @task:T-018 (1.6s)

  9 passed (21.9s)
== down
QA PASS
```
- 첫 실행은 내 단언(`2개 모두 정상`)이 환경(메신저 none → `1/2 정상`)에 맞지 않아 실패해 테스트 쪽을 고쳤다(분모만 확인). 앱 결함 아님.
- 정리 확인: 종료 뒤 `/api/system/status` 응답 없음(curl 연결 실패), 8778·54329 리슨 0. 사용자 인스턴스(8777)는 건드리지 않음.

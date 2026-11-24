# T-015 QA 보고서 — TC-023 핵심 묶음 승격
> 담당: qa (qa) · 2026-11-24 · 지시서: [T-015](../T-015-tc023-core-승격.md) · 설명서: 본 보고서로 갈음

## 판정
QA PASS. 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 기준 기록: qa-baseline.md 2026-11-24 16:28 (유지 모드, 이전 행 16:19).

## 요약
- `e2e/tasks/T-002.spec.ts`를 `e2e/core/access-control.spec.ts`로 옮기고 태그를 `@core`로 바꿨다(테스트 내용 변경 없음). `e2e/tasks/`는 비었다.
- `@core` 6개(TC-006~010, TC-023)를 `qa-run.sh`로 실행해 모두 통과, skipped 0(report.json expected 6, unexpected 0, skipped 0).
- 07 §8 목록(TC-006·007·008·009·010·023)과 `@core` 태그 테스트가 일치한다.

## 화면 흐름
### TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 — 통과
1. [시작: 플랫폼 화면](./T-015-qa/artifacts/core-access-control-TC-023-다른-출처의-상태-변경-요청은-경로-표기와-무관하게-403-chromium/01-before.png) — 스킬 목록 로드 완료
2. 다른 출처(evil, null) POST를 경로 표기 5종으로 보내 403과 안내문을 확인, 같은 출처·Origin 없음은 403 아님
3. [최종: 새로고침 뒤 화면](./T-015-qa/artifacts/core-access-control-TC-023-다른-출처의-상태-변경-요청은-경로-표기와-무관하게-403-chromium/02-after-reload.png) — 새로고침 뒤 정상 표시
- 기대 결과: 5종 경로 모두 403 + 안내문, 새로고침 뒤 정상 / 실제: 단언 통과(3.1s). 이 실행에서 스크린샷은 열어 보지 않았고 단언과 report.json으로 판정함.

## 실패
없음

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-023 | 통과 | 3.1s, 새로 승격 |
| TC-010 | 통과 | 1.6s |
| TC-009 | 통과 | 2.5s |
| TC-008 | 통과 | 1.6s |
| TC-006 | 통과 | 4.0s |
| TC-007 | 통과 | 3.0s |

전체 실행 시간: 약 20초(테스트 19.7s, 상한 10분) · 07 §8 목록과 @core 일치.

## 데이터
- 규모 가정: 해당 없음 — 이번 실행은 파일 이동 확인이라 데이터 주입 없음(seed 생성기 변경 없음). 앱은 빈 내장 DB로 기동(기동 전 pgdata-embedded 삭제).
- 정리한 테스트: `e2e/tasks/T-002.spec.ts`(`e2e/core/access-control.spec.ts`로 이동). `e2e/tasks/` 디렉터리 삭제.
- 앱: 8778, 기존 `build/libs/dev-agent.jar`(재빌드 없음). `report.json`의 절대경로는 `<repo>`로 치환함.

## 묶음 제안
없음

## 제안
없음

## 검증
- 로그: `qa-run-20261124-162737-49852.log`(qa-run 임시 로그)
```
log: /c/Users/Public/Documents/ESTsoft/CreatorTemp/qa-run-20261124-162737-49852.log
== up
== health (http://127.0.0.1:8778/api/system/status, timeout 180s)
== test (@core)

Running 6 tests using 1 worker

  ✓  1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (3.1s)
  ✓  2 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (1.6s)
  ✓  3 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (2.5s)
  ✓  4 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (1.6s)
  ✓  5 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (4.0s)
  ✓  6 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (3.0s)

  6 passed (19.7s)
== down
QA PASS
```
- 정리 확인: 8778 health URL 닫힘(curl 응답 없음). 컨테이너 기반 아님.

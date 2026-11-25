# T-009 QA 보고서 — Windows 전용 요소와 로컬 프리뷰 제거
> 담당: qa (qa) · 2026-11-25 · 지시서: [T-009](../T-009-windows-요소-제거.md) · 설명서: [T-009-windows-요소-제거.md](./T-009-windows-요소-제거.md)

## 판정
**QA PASS.** `@core` 7개 실행 7개 통과, skipped 0, unexpected 0(`T-009-qa/report.json`). 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 기준 기록: [qa-baseline.md](./qa-baseline.md) 2026-11-25 11:39(T-019) 대비 회귀 없음, 이번 실행 행 추가.

## 요약
- 앱을 04 §8 방식(8778, `-Duser.home` 분리, 내장 DB)으로 띄워 플랫폼 「시스템」 카드 설치 점검이 행을 그리되 「로컬 프리뷰(화면 검증)」 행이 없음을 화면에서 확인했다(시스템 카드 실행 방식 표시는 「직접 실행」).
- 07 §8 기록대로 `e2e/core/removed-features.spec.ts`(TC-013)에 설치 점검 단언을 더했다(범위 밖 e2e 변경 없음). 핵심 묶음 7개는 모두 통과했다.
- 격리 홈 아래 `bin/`이 생기지 않았고, 종료 뒤 8778·54329 리슨 0이다.

## 화면 흐름
### TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) — 통과
1. [홈](./T-009-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/01-home.png) — 연동 2행, 메신저 통계 카드 없음
2. [메신저](./T-009-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/02-messenger.png) — 알림 유형 3개, 방 설정 없음
3. [플랫폼 설치 점검](./T-009-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/03-platform-doctor.png) — 점검 행이 그려졌고 「프리뷰」 문구·「로컬 프리뷰」 행 없음(**T-009 시점 신규 단언**)
4. [플랫폼 연동](./T-009-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/04-platform-ports.png) — 메신저·코딩 에이전트 2행
5. [MCP 등록 폼](./T-009-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/05-platform-mcp-form.png) — 「+ 서버 추가」로 폼 열림
6. [에이전트(최종)](./T-009-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/06-agent.png) — 세션 선택 안내, 스크린샷 갤러리·프리뷰 문구 없음
- 기대 결과: 설치 점검에 「로컬 프리뷰(화면 검증)」 행 없음, 제거 요소·API 요청 0, 실패 요청 0, 콘솔 오류 0 / 실제: 모두 단언 통과. 3번 화면에서 설치 점검 행은 실행 환경·데이터베이스·코딩 에이전트·Claude 로그인·Git·메신저·GitLab·작업 디렉터리·Node.js·Git Bash 10행이고 프리뷰 행이 없다. 시스템 카드의 실행 방식은 「직접 실행」이다.

## 실패
없음

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-006 | 통과 | 다섯 탭 이동 |
| TC-007 | 통과 | 스킬 등록·삭제 |
| TC-008 | 통과 | 루틴 등록·삭제 |
| TC-009 | 통과 | 알림함 |
| TC-010 | 통과 | 에이전트·리서치 빈 상태 |
| TC-023 | 통과 | 다른 출처 POST 403 |
| TC-013 | 통과 | 설치 점검 「로컬 프리뷰」 행 없음 단언 추가 (+ TC-014 제거 API 404) |

테스트 실행 시간 21.6초(상한 10분) · 07 §8 목록(TC-006~010, TC-023, TC-013)과 `@core` 일치.

TC-001(Linux 기동)·`server-smoke.sh`는 서버 격리 실행 환경이 필요해 이 확인 범위에서 실행하지 않았다(지시서도 미실행으로 기록).

## 데이터
- 규모 가정: 07 §3(이슈 세션·스킬·루틴 등). 이 작업은 제거 확인이라 데이터를 주입하지 않았다(seed 0행, 기존 확인 T-019와 같음).
- 주입 경로: 없음(내장 DB 새로 초기화).
- 정리한 테스트: 없음(`e2e/tasks/` 비어 있음).
- e2e 변경: `e2e/core/removed-features.spec.ts`에 설치 점검 단언 6줄(+스크린샷 한 장)만 추가.
- 추가 확인: 격리 홈(`-Duser.home`) 아래 `bin/` 없음. `app.log`의 ERROR 0줄.

## 묶음 제안
없음

## 제안
- 증거 스크린샷 `03-platform-doctor.png`에 사용자 홈 밑 프로젝트 경로(`~\…\e2e\test-results\qa-home`)가 보인다(홈 부분은 `~`로 표시, 계정명 없음). 공개 저장소에 올릴지는 PM이 판단한다.
- 07 §3·§4의 FR-206·TC-013 T-009 시점 상태를 통과로 갱신하는 것은 PM 몫이다.

## 검증
- 로그: `<tmp>/qa-run-20261125-123224-2281.log`
```
log: <tmp>/qa-run-20261125-123224-2281.log
== up
== health (http://127.0.0.1:8778/api/system/status, timeout 180s)
== test (@core)

Running 7 tests using 1 worker

  ✓  1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (5.5s)
  ✓  2 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (1.3s)
  ✓  3 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (1.8s)
  ✓  4 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (3.1s)
  ✓  5 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (1.6s)
  ✓  6 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (2.9s)
  ✓  7 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (2.3s)

  7 passed (21.6s)
== down
QA PASS
```
- 정리 확인: `qa-run.sh`의 down 단계 후 `/api/system/status` 접속 불가, 8778·54329 리슨 0. 8777은 건드리지 않았다.

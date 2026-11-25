# T-019 QA 보고서 — TC-013 핵심 묶음 승격(T-007·T-018 단언 합침)
> 담당: qa (qa) · 2026-11-25 · 지시서: [T-019](../T-019-tc013-core-승격.md) · 설명서: 이 보고서로 갈음(지시서 §변경 설명서)

## 판정
**QA PASS.** `@core` 7개 실행 7개 통과, skipped 0, unexpected 0, flaky 0(`T-019-qa/report.json`). 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 기준 기록: [qa-baseline.md](./qa-baseline.md) 2026-11-25 11:29(T-018) 대비 회귀 없음, 이번 실행 행 추가. 모드: 유지(`QA 확인: 아니오`라 TC 확인이 아니라 묶음 정리).

## 요약
- `e2e/tasks/T-008.spec.ts`를 바탕으로 `T-007.spec.ts`·`T-018.spec.ts` 단언을 뜻을 바꾸지 않고 합쳐 `e2e/core/removed-features.spec.ts`(`@core`)로 옮겼고 세 파일을 지웠다(`e2e/tasks/` 비어 있음).
- 테스트 이름은 `TC-013 …(+ TC-014 제거 API 404)`, 제거 API 404 부분은 `expectRemovedApis`(주석 "TC-014 part")로 같은 파일에 뒀다.
- `@core` 7개(TC-006~010, TC-023, TC-013)를 표준 실행(`qa-run.sh`, 8778)으로 23.9초(테스트 시간)에 통과시켰다. 07 §8 목록과 `@core`가 일치한다.

## 합친 단언 대조표
| 원본 파일 | 단언 | 새 파일 위치(`e2e/core/removed-features.spec.ts`) |
|---|---|---|
| T-008 | REMOVED_API 정규식·`watchRequests`, 4개 화면 로딩 중 제거 경로 요청 0·실패 요청 0·콘솔 오류 0 | 상단 상수·`watchRequests`, 테스트 마지막 `req.removed`/`req.failed`/`errors` |
| T-008 | 홈: 연동 상태 제목, `#homeMessenger` 없음, 연동 N / M 정상 링크, 본문에 수집 메시지·구 문구 없음 | 홈 구간 |
| T-008 | 메신저: 제목 3개, 로딩 해제, `#stats` 없음, 방 설정·최근 활동 제목 없음, 본문 금지어, 명령 안내 문구 5개, 명령 카드에 운영·방 요약·일반 대화 없음 | 메신저 구간 |
| T-008 | 플랫폼: MCP 서버 제목, 제거 id 6개 없음, RAG·운영 MCP 제목 없음, MCP 카드 금지어, 프리뷰 스크린샷 없음, 「+ 서버 추가」→ `#mfName` 표시 | 플랫폼 구간(폼 열기는 구간 끝) |
| T-008 | 에이전트: 세션 선택 안내, `#shots` 없음, 프리뷰 문구 없음 | 에이전트 구간(최종 결과 화면) |
| T-007 | 제거 API 8개 GET 404 | `expectRemovedApis` 첫 반복 |
| T-007 | 제거 알림 유형 `summary`·`mention` POST 거부(`ok:false`) | `expectRemovedApis` 둘째 반복 |
| T-007 | `/api/system/status` 200 | `expectRemovedApis` |
| T-007 | `/api/messenger-notify` kinds = issue·research·system, `/api/platform` ports에 knowledge 없음 | `expectRemovedApis` 끝 |
| T-007 | 홈 본문에 지식·RAG 없음 | 홈 구간 |
| T-007 | 플랫폼 「방 목록 · 메시지 수집」 없음, 본문에 지식·RAG 없음 | 플랫폼 구간 |
| T-007 | 메신저 알림 행 3개·`data-notify` 키 순서, 알림 카드에 방 요약·멘션 초안·최근 활동·멘션 처리 없음, 본문에 방 설정·수집 메시지·지식 없음 | 메신저 구간 |
| T-018 | 제거 API 3개 GET·POST 404 | `expectRemovedApis` 셋째 반복 |
| T-018 | `/api/mcp-servers`·`/api/system/status` 200 | `expectRemovedApis` |
| T-018 | `/api/platform` ports = messenger·agent, `chatModel`·`toolChatModel` 필드 없음 | `expectRemovedApis` 끝 |
| T-018 | 홈 `#homePorts .prow` 2행 | 홈 구간 |
| T-018 | 플랫폼 `#platPorts .plat` 2행, 메신저·코딩 에이전트 문구, 채팅 모델·도구 모델·운영 MCP 없음, `#platSummary` 분모 2 | 플랫폼 구간 |

합치며 달라진 점(단언 뜻 불변): ① T-007의 별도 `failedRequests` 수집은 T-008의 `req.failed`로 일원화(API 탐침은 `page.request`라 페이지 이벤트가 없어 초기화가 필요 없다) ② 낡은 주석 두 곳(메신저 「최근 활동」이 T-007 전 서버 문구라는 설명, 플랫폼 "T-007/T-009가 백엔드를 바꿀 때까지")을 지금 상태로 고침. 플랫폼 구간의 지식·RAG 본문 단언이 T-009 전의 프리뷰 행 문구를 건드리지 않음을 실행으로 확인했다.

## 화면 흐름
### TC-013 제거 요소·API 회귀 감지 — 통과
1. [홈](./T-019-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/01-home.png) — 연동 상태 2행
2. [메신저](./T-019-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/02-messenger.png) — 알림 유형 3개
3. [플랫폼 연동 카드](./T-019-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/03-platform-ports.png)
4. [MCP 등록 폼](./T-019-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/04-platform-mcp-form.png)
5. [에이전트](./T-019-qa/artifacts/core-removed-features-TC-0-7d600--정상-동작한다-TC-014-제거-API-404--chromium/05-agent.png) — 최종 결과 화면, 「세션을 선택하세요」, 갤러리 없음(열어 확인)
- 기대 결과: 07 §8 TC-013 행 / 실제: 일치. 제거 요소·요청·콘솔 오류 없음.

## 실패
없음

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-006 | 통과 | |
| TC-007 | 통과 | |
| TC-008 | 통과 | |
| TC-009 | 통과 | |
| TC-010 | 통과 | |
| TC-023 | 통과 | |
| TC-013 | 통과 | 이번에 `e2e/core/removed-features.spec.ts`로 승격(+TC-014 제거 API 404) |

테스트 시간 23.9초(상한 10분) · 07 §8 목록(TC-006~010, TC-023, TC-013)과 `@core` 7개 일치.

## 데이터
규모 가정 07 §3 대상 아님, 데이터 주입 없음(0행). 정리한 테스트: `e2e/tasks/T-007.spec.ts`, `e2e/tasks/T-008.spec.ts`, `e2e/tasks/T-018.spec.ts`(합친 뒤 삭제). 증거 `report.json`의 로컬 홈 경로는 `~`로, `app.log`의 계정명은 `user`로 치환했다(`app.log`·`trace.zip`은 gitignore).

## 묶음 제안
없음. T-009 qa 확인 때 이 파일에 로컬 프리뷰 행 없음 단언을 더한다(07 §8 기록대로).

## 제안
없음

## 검증
- 로그: `<tmp>/qa-run-20261125-113837-583.log`
```
log: <tmp>/qa-run-20261125-113837-583.log
== up
== health (http://127.0.0.1:8778/api/system/status, timeout 150s)
== test (@core)

Running 7 tests using 1 worker

  ✓  1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (5.7s)
  ✓  2 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (1.4s)
  ✓  3 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (2.0s)
  ✓  4 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (3.1s)
  ✓  5 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (1.8s)
  ✓  6 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (3.4s)
  ✓  7 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (2.8s)

  7 passed (23.9s)
== down
QA PASS
```
- 정리 확인: 8778·54329 리슨 0, 준비 확인 URL 무응답. 8777은 건드리지 않았다(리슨 0, 사용자 인스턴스 없음). 프로세스 종료 거부 없음.

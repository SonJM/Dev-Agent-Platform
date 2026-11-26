# T-026 QA 보고서 — TC-025 핵심 묶음 승격
> 담당: qa (qa) · 2026-11-26 · 지시서: [T-026](../T-026-tc025-core-승격.md) · 설명서: 이 보고서로 갈음

## 판정
**QA PASS** — 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 10개 실행(8 TC, TC-025는 테스트 3개), 통과 10, skipped 0, `report.json` expected 10·unexpected 0. 기준 기록: qa-baseline.md 2026-11-26 09:05 행 추가(직전 2026-11-25 18:23).

## 요약
- `e2e/tasks/T-025.spec.ts`의 TC-025 테스트 3개를 `e2e/core/bundle-import.spec.ts`로 옮기고 태그만 `@task:T-025`에서 `@core`로 바꿨다. 본문(단언·시험 데이터 정리)은 한 글자도 바꾸지 않았다.
- `e2e/tasks/`의 `T-025.spec.ts`·`T-010.spec.ts`를 지웠다(남은 파일 없음).
- `@core` 전체를 표준 실행으로 돌려 전부 통과, 실행 56.2초(상한 10분). TC-025가 다른 core(TC-007 스킬 등)와 간섭하지 않았다(순서: TC-025는 TC-023 뒤, TC-007 앞).

## 화면 흐름
이번 작업은 테스트 이동이라 새 흐름이 없다. TC-025의 화면은 이번 실행에서도 그대로 찍혔다.

### TC-025 stdio MCP 묶음 — 통과
1. [체크 전 확인 창](./T-026-qa/artifacts/core-bundle-import-TC-025--253c9-체크-전-비활성·취소-시-저장-없음·체크-후-저장-chromium/01-dialog-unchecked.png) — 서버 이름·명령·인자·경고, 가져오기 비활성
2. [취소 뒤](./T-026-qa/artifacts/core-bundle-import-TC-025--253c9-체크-전-비활성·취소-시-저장-없음·체크-후-저장-chromium/02-cancelled.png)
3. [체크 후](./T-026-qa/artifacts/core-bundle-import-TC-025--253c9-체크-전-비활성·취소-시-저장-없음·체크-후-저장-chromium/03-dialog-checked.png)
4. [가져온 뒤](./T-026-qa/artifacts/core-bundle-import-TC-025--253c9-체크-전-비활성·취소-시-저장-없음·체크-후-저장-chromium/04-imported.png) — 최종: `/api/mcp-servers`에 서버 있음(단언), 끝에 삭제 정리
- 기대 결과: 07 TC-025 ① / 실제: 단언 전부 통과.

## 실패
없음

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-023 | 통과 | 9.1s |
| TC-025 (3개) | 통과 | 7.8s·3.5s·3.1s, 신규 승격 |
| TC-010 | 통과 | 2.2s |
| TC-009 | 통과 | 3.0s |
| TC-013 (+TC-014) | 통과 | 5.4s |
| TC-008 | 통과 | 3.2s |
| TC-006 | 통과 | 5.4s |
| TC-007 | 통과 | 4.2s |

전체 실행 시간: 56.2초 (상한 10분) · 07 §8 표(TC-006~010, 023, 013)에는 아직 TC-025가 없고 `@core`에는 있다 — 07 §8 갱신은 PM(지시서 범위 밖).

### 옮긴 단언 대조표 (원본 `e2e/tasks/T-025.spec.ts` → 새 `e2e/core/bundle-import.spec.ts`)
| 테스트 | 원본 줄 | 새 줄 | 단언 |
|---|---|---|---|
| ① stdio MCP 묶음 | 23-70 | 23-70 | 항목 수 `MCP 서버 1`, 이름·`node`·`--qa-flag` 표시, HTML 문자열이 텍스트로 표시(`b` 요소 0), 경고 표시, 체크 전 가져오기 비활성, 취소 뒤 `/api/mcp-servers`에 없음, 체크 후 활성, 가져온 뒤 있음, `finally` 삭제 후 없음, 콘솔 오류 0 |
| ② stdio 없는 묶음 | 72-105 | 72-105 | `스킬 1`·`MCP 서버 0`, 경고·stdio 영역·확인 체크 행 숨김, 가져오기 활성, 가져온 뒤 스킬 목록에 이름·설명, `finally` 스킬 삭제, 콘솔 오류 0 |
| ③ 390px | 107-141 | 107-141 | 취소·가져오기 버튼 뷰포트 안, 창 가로 넘침 ≤1·내부 요소 넘침 없음·창이 뷰포트 안, 취소 뒤 서버 없음, 콘솔 오류 0 |

원본과 새 파일은 태그 문자열 3곳 외 동일(`sed` 치환으로 이동).

## 데이터
- 규모 가정: 07 §3 기준, 이번 변경은 데이터 주입 없음(0행). 시험 데이터(가져온 MCP 서버·스킬)는 테스트가 만들고 `finally`에서 지운다.
- 지운 테스트 파일: `e2e/tasks/T-025.spec.ts`(핵심으로 이동), `e2e/tasks/T-010.spec.ts`(T-010 완료). 남은 `e2e/tasks/`는 비어 있다.
- 앱: `build/libs/dev-agent.jar`(2.0.4 시점 빌드)로 실행. HEAD는 2.1.0이므로 이 jar는 최신 소스가 아니다 — 이 작업은 앱 코드 변경이 없고 묶음 통과 확인이 목적이라 그대로 썼다. 최신 jar 재확인은 다음 `QA 확인: 예` 실행에서 이뤄진다.
- 증거 `report.json`의 로컬 경로는 `<repo>`로 치환했다.

## 묶음 제안
없음 (TC 수 8개, 한 번 실행 56초 — 상한 안).

## 제안
- 07 §8 표에 TC-025 행 추가, "승격 후보" 단락 정리는 PM이 한다(지시서 범위 밖).
- `qa-run.sh` 종료 중 `cat: /proc/<pid>/winpid: No such file` 메시지가 나왔다. 판정에는 영향 없고 종료 정리는 확인됨(아래).

## 검증
- 로그: `<tmp>/qa-run-20261126-090407-11637.log`
```
== health (http://127.0.0.1:8778/api/system/status, timeout 120s)
== test (@core)

Running 10 tests using 1 worker

  ✓   1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (9.1s)
  ✓   2 [chromium] › core\bundle-import.spec.ts:23:1 › TC-025 stdio MCP 묶음: 확인 창·체크 전 비활성·취소 시 저장 없음·체크 후 저장 @core (7.8s)
  ✓   3 [chromium] › core\bundle-import.spec.ts:72:1 › TC-025 stdio 없는 묶음(스킬 1개): 경고 없이 항목 수만, 가져오면 스킬 반영 @core (3.5s)
  ✓   4 [chromium] › core\bundle-import.spec.ts:107:1 › TC-025 390px: 확인 창 가로 넘침 0, 버튼이 보인다 @core (3.1s)
  ✓   5 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (2.2s)
  ✓   6 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (3.0s)
  ✓   7 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (5.4s)
  ✓   8 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (3.2s)
  ✓   9 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (5.4s)
  ✓  10 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (4.2s)

  10 passed (56.2s)
== down
cat: /proc/11648/winpid: No such file or directory
QA PASS
```
- 정리 확인: 준비 확인 URL 응답 없음(닫힘), 8778·54329 리슨 0, 8777은 건드리지 않음.

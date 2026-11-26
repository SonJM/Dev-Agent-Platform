# T-027 QA 보고서 — TC-024 핵심 묶음 승격
> 담당: qa (qa) · 2026-11-26 · 지시서: [T-027](../T-027-tc024-core-승격.md) · 설명서: 이 보고서로 갈음

## 판정
**QA PASS** — 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 13개 실행(9 TC, TC-025는 3개·TC-024는 3개), 통과 13, skipped 0, `report.json` expected 13·unexpected 0·skipped 0·flaky 0. 기준 기록: qa-baseline.md 2026-11-26 14:29 행 추가(직전 2026-11-26 14:19).

## 요약
- `e2e/tasks/T-020.spec.ts`의 TC-024 테스트 3개를 `e2e/core/narrow-topbar.spec.ts`로 옮기고 태그만 `@task:T-020`에서 `@core`로 바꿨다. 본문(단언)은 바꾸지 않았다(`diff` 결과 태그 2줄뿐).
- `e2e/tasks/`에 `T-020.spec.ts`가 남지 않았다(디렉터리 비어 있음).
- `@core` 전체를 표준 실행(`qa-run.sh`)으로 돌려 13개 모두 통과, 실행 38.0초(상한 10분). TC-024가 다른 core와 간섭하지 않았다.

## 화면 흐름
테스트 이동이라 새 흐름은 없다. TC-024의 화면은 이번 실행에서도 그대로 찍혔다.

### TC-024 390px 다섯 화면·탭 이동·알림함 — 통과
1. [홈 직접 열기](./T-027-qa/artifacts/core-narrow-topbar-TC-024--cbd4c--가로-넘침-없음-탭-아이콘-이동-종-버튼-알림함-chromium/01-direct-homehtml.png) — 탭 5개(아이콘)·종·테마, 가로 넘침 없음(에이전트~플랫폼 02~05도 같은 단언)
2. [탭 이동: 플랫폼](./T-027-qa/artifacts/core-narrow-topbar-TC-024--cbd4c--가로-넘침-없음-탭-아이콘-이동-종-버튼-알림함-chromium/09-tab-platformhtml.png) — 아이콘 탭만으로 이동
3. [알림함 열림](./T-027-qa/artifacts/core-narrow-topbar-TC-024--cbd4c--가로-넘침-없음-탭-아이콘-이동-종-버튼-알림함-chromium/10-notice-panel-open.png) — 최종: 알림 패널이 뷰포트 안, 셸 `scrollWidth <= 390`(단언), 시작 알림 1건 보임(최종 화면을 직접 열어 확인)
- 기대 결과: 07 TC-024 390px / 실제: 단언 전부 통과, 콘솔 오류 0.

### TC-024 768px · 1440px — 통과
- [768px 홈](./T-027-qa/artifacts/core-narrow-topbar-TC-024-768px-상단바-기존-모양-유지-탭-이름-보임-한-줄--chromium/01-home-768.png) · [1440px 홈](./T-027-qa/artifacts/core-narrow-topbar-TC-024-1440px-상단바-기존-모양-유지-탭-이름-보임-한-줄--chromium/01-home-1440.png)
- 기대 결과: 탭 이름 5개 보임, 로고·탭 한 줄, 1440은 단축키 표시 보임, 종·테마 보임 / 실제: 단언 통과.

## 실패
없음

## 핵심 TC 묶음 결과
| TC | 결과 | 비고 |
|---|---|---|
| TC-023 | 통과 | 5.4s |
| TC-025 (3개) | 통과 | 2.8s·1.8s·1.4s |
| TC-010 | 통과 | 1.3s |
| TC-024 (3개) | 통과 | 6.5s·1.5s·1.1s, 신규 승격 |
| TC-009 | 통과 | 1.7s |
| TC-013 (+TC-014) | 통과 | 3.2s |
| TC-008 | 통과 | 1.4s |
| TC-006 | 통과 | 3.3s |
| TC-007 | 통과 | 2.7s |

전체 실행 시간: 38.0초 (상한 10분) · 07 §8 표(TC-006~010, 023, 013, 025)에는 아직 TC-024가 없고 `@core`에는 있다 — 07 §8 갱신은 PM(지시서 범위 밖).

### 옮긴 단언 대조표 (원본 `e2e/tasks/T-020.spec.ts` → 새 `e2e/core/narrow-topbar.spec.ts`)
| 테스트 | 원본 줄 | 새 줄 | 단언 |
|---|---|---|---|
| ① 390px 다섯 화면·탭 이동·알림함 | 22-78 | 22-78 | 화면마다 표지 문구 보임, `scrollWidth <= 390`, 탭 5개 보임·`aria-label`·`.nl` 숨김·44px 이상·뷰포트 안, 종·테마 보임·44px 이상·뷰포트 안, 아이콘 탭으로 네 화면 이동 후 표지 문구·셸 넘침 없음, 종 클릭 시 알림 패널 보임·`알림` 문구·패널이 뷰포트 안·셸 넘침 없음, 콘솔 오류 0 |
| ② 768px | 81-98 | 81-98 | 홈 제목, 탭 5개와 `.nl` 보임, 종·테마 보임, 로고·탭 한 줄(세로 중심 차 < 로고 높이), 콘솔 오류 0 |
| ③ 1440px | 81-98 | 81-98 | ②와 같고 추가로 첫 탭 `.k`(단축키 표시) 보임 |

원본과 새 파일은 태그 문자열 2곳(테스트 정의 두 줄, ②③은 반복문 하나) 외 동일(`diff`로 확인).

## 데이터
- 규모 가정: 07 §3 기준, 이번 변경은 데이터 주입 없음(0행).
- 지운 테스트 파일: `e2e/tasks/T-020.spec.ts`(핵심으로 이동). 남은 `e2e/tasks/`는 비어 있다.
- 앱: `build/libs/dev-agent.jar`(14:17 빌드, `build.gradle` 버전 2.2.1)로 실행, 포트 8778. 8777(사용자 인스턴스)은 건드리지 않았다.
- 증거 `report.json`의 로컬 경로는 `<repo>`로 치환했다(`<tmp>` 경로는 파일에 없음). `app.log`·`trace.zip`은 `T-027-qa/.gitignore`로 추적에서 뺐다.

## 묶음 제안
없음 (TC 9개·테스트 13개, 한 번 실행 38초 — 상한 안).

## 제안
- 07 §8 표에 TC-024 행 추가, "승격 후보" 단락 정리는 PM이 한다(지시서 범위 밖).

## 검증
- 로그: `<tmp>/qa-run-20261126-142739-22502.log`
```
Running 13 tests using 1 worker

  ✓   1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (5.4s)
  ✓   2 [chromium] › core\bundle-import.spec.ts:23:1 › TC-025 stdio MCP 묶음: 확인 창·체크 전 비활성·취소 시 저장 없음·체크 후 저장 @core (2.8s)
  ✓   3 [chromium] › core\bundle-import.spec.ts:72:1 › TC-025 stdio 없는 묶음(스킬 1개): 경고 없이 항목 수만, 가져오면 스킬 반영 @core (1.8s)
  ✓   4 [chromium] › core\bundle-import.spec.ts:107:1 › TC-025 390px: 확인 창 가로 넘침 0, 버튼이 보인다 @core (1.4s)
  ✓   5 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (1.3s)
  ✓   6 [chromium] › core\narrow-topbar.spec.ts:22:1 › TC-024 390px: 다섯 화면 가로 넘침 없음, 탭 아이콘 이동, 종 버튼 알림함 @core (6.5s)
  ✓   7 [chromium] › core\narrow-topbar.spec.ts:81:3 › TC-024 768px: 상단바 기존 모양 유지(탭 이름 보임, 한 줄) @core (1.5s)
  ✓   8 [chromium] › core\narrow-topbar.spec.ts:81:3 › TC-024 1440px: 상단바 기존 모양 유지(탭 이름 보임, 한 줄) @core (1.1s)
  ✓   9 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (1.7s)
  ✓  10 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (3.2s)
  ✓  11 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (1.4s)
  ✓  12 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (3.3s)
  ✓  13 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (2.7s)

  13 passed (38.0s)
== down
QA PASS
```
- 정리 확인: 준비 확인 URL 응답 없음(닫힘), 8778·54329 리슨 0, 8777은 건드리지 않음.

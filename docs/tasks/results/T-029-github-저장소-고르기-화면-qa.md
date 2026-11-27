# T-029 QA 보고서 — GitHub 저장소 고르기 화면
> 담당: qa (qa) · 2026-11-27 · 지시서: [T-029](../T-029-github-저장소-고르기-화면.md) · 설명서: [T-029-github-저장소-고르기-화면.md](./T-029-github-저장소-고르기-화면.md)

## 판정
QA PASS. 실패 0(변경 오류 0, 회귀 0, 기존 결함 0). 18개 테스트 통과, skipped 0, unexpected 0. 기준 기록: qa-baseline.md 2026-11-27 13:11 행 추가(직전 기록은 2026-11-26 14:29).

## 요약
- 미커밋 작업 트리(T-028·T-029·T-030)의 jar(2.3.0, 12:59)를 띄워 TC-027 ①~⑤와 핵심 묶음(@core 13개)을 한 번에 실행했고 모두 통과했다. jar가 소스보다 새로워 다시 빌드하지 않았다.
- T-030의 저장 응답 `ok:true`는 ②에서 확인했다(창 닫힘, 성공 토스트, 칩 목록 재조회). `ok:false`는 ⑤에서 확인했다(성공 토스트 없음, 창 안 오류, 창 유지).
- 대상 목록을 바꾸는 저장은 하지 않았다(`/api/issues-projects/set`은 전부 가로채 서버에 닿지 않음). 가짜 저장소는 `demo-user/*`.

## 화면 흐름
스크린샷 폴더: `./T-029-qa/artifacts/` (아래 `<폴더>`는 `tasks-T-029-TC-027-<번호>…-chromium`).

### TC-027 ① 실제 서버 응답(토큰 없음) — 통과
1. [토큰 없음 창](./T-029-qa/artifacts/) `①…/01-no-token.png` — 창이 열리고 상태 영역에 `GITHUB_TOKEN` 비어 있음 안내.
- 기대 결과: 안내 문구, 직접 입력 칸 보임, 콘솔 오류 0, `ISSUE_ENABLED=true`면 「목록에 없는 대상」에 `qa-group/qa-sample` 체크 / 실제: 모두 일치(이 실행은 `ISSUE_ENABLED=true`, 닿지 않는 로컬 GitHub 주소, 토큰 없음). 꺼짐(`false`) 분기는 이 실행에서 실제 값으로는 돌지 않았고 ④의 `enabled:false` 가로채기로 같은 화면 문구를 확인했다.

### TC-027 ② 가로챈 목록(비공개 1·보관 1·일반 1) — 통과
1. 목록 `②…/01-list.png` — 비공개 배지, 기본 브랜치(main·trunk), "3일 전", 보관 저장소 체크 비활성, 「목록에 없는 대상」에 `qa-group/qa-sample` 체크됨, "1개 선택 (최대 50개)".
2. 체크 `②…/02-checked.png` — 일반 저장소를 체크하면 "2개 선택".
3. 저장 후 `②…/03-saved.png` — 창이 닫히고 칩 목록 재조회.
- 필터 `beta`는 나머지 두 행을 숨기고, 취소하면 요청 0건, 다시 열면 체크가 현재 대상으로 초기화. 저장 요청 본문의 `path` = 체크한 저장소 + `qa-group/qa-sample`(체크를 풀지 않은 목록 밖 대상) 정확히 2개.
- `ok:true` 응답(T-030): 창 닫힘, 성공 토스트, 콘솔 오류 0.

### TC-027 ③ 390px — 통과
1. [모바일 창](./T-029-qa/artifacts/) `③…/01-mobile-dialog.png` — 창 전체 폭, 필터 위 고정, 긴 이름(문자 단위 줄바꿈), 저장·취소가 아래 고정.
- 문서·창 가로 넘침 0, 창 높이 ≤ 화면 높이, 저장·취소가 뷰포트 안, 행 높이 44px 이상(측정값 43.99 — CSS `min-height:44px`의 소수점 오차라 반올림 값으로 비교).

### TC-027 ④ 현재 대상 조회 500 / `enabled:false` — 통과
1. `④…/01-current-fail.png` — 「현재 대상 프로젝트를 불러오지 못해…」 문구, 저장 비활성, 목록은 표시.
2. `④…/02-agent-off.png` — `ISSUE_ENABLED` 꺼짐 안내, 저장 비활성.

### TC-027 ⑤ 저장 응답 `ok:false` — 통과
1. `⑤…/01-save-error.png` — 서버 `result` 문구가 창 안에 보이고 창 유지, 성공 토스트(`저장했습니다`·`추가했습니다`) 없음, 저장 버튼 다시 활성.

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
| TC-013 (+TC-014) | 통과 | |
| TC-025 (3건) | 통과 | |
| TC-024 (3건) | 통과 | |

전체 실행 시간: 18개 55초(상한 10분). 07 §8 묶음 9개 TC와 `e2e/core/`의 `@core` 일치(TC-025 3건·TC-024 3건 포함 13개 테스트).

## 데이터
- 규모 가정: 이번 변경은 데이터를 주입하지 않는다(0행). 목록 데이터는 가로챈 응답의 합성 저장소 3~4개(`demo-user/*`, 긴 이름 1개). 주입 경로: 브라우저 요청 가로채기(`page.route`).
- 앱 기동: `ISSUE_ENABLED=true GITHUB_API_URL=http://127.0.0.1:9`(닿지 않는 로컬 주소) + `start-app.sh`(8778). 8777은 건드리지 않았다.
- 새 테스트: `e2e/tasks/T-029.spec.ts`(`@task:T-029`, TC-027 ①~⑤). 07의 「핵심 묶음 여부: 아니오」대로 `@core` 아님.
- 정리한 테스트: 없음.
- 공개 저장소 처리: 결과 폴더 `.gitignore`가 `app.log`·`trace.zip`을 제외한다. `report.json`의 로컬 경로는 `<repo>`로 치환했다. 스크린샷은 `/api/system/**`·`/api/usage` 응답의 홈 경로를 `<repo>`로 바꾼 뒤 촬영했고, 테스트가 매 화면에서 페이지 HTML에 계정 이름이 없음을 단언한다. 스크린샷 2장(②, ③)을 열어 계정 이름이 없음을 확인했다.

## 묶음 제안
없음

## 제안
- 앱 결함 후보(범위 밖, 낮음): `/api/usage`(상단바 배터리 툴팁)의 "구독 API 조회 실패" 문구에 `.credentials.json` 절대 경로(사용자 홈)가 그대로 들어간다. 화면과 응답에 로컬 홈 경로·계정 이름이 노출되므로 문구에서 경로를 빼는 것이 좋다. 이번 실행의 첫 시도가 이 때문에 실패했다(테스트의 계정 이름 단언). 가려서 촬영했으므로 이번 판정에는 영향이 없다.
- 04 §8 보완 후보: 지시서가 `/api/system/**`만 가리라고 했으나 `/api/usage`도 경로를 싣는다. 스크린샷 마스킹 대상에 추가할 것.

## 검증
- 로그: `/c/Users/Public/Documents/ESTsoft/CreatorTemp/qa-run-20261127-131058-27439.log` (공개 저장소라 임시 폴더는 `<tmp>` 로 가려도 되는 경로이나 로컬 로그라 그대로 적음)
```
  ✓   3 [chromium] › core\bundle-import.spec.ts:72:1 › TC-025 stdio 없는 묶음(스킬 1개): 경고 없이 항목 수만, 가져오면 스킬 반영 @core (2.0s)
  ✓   4 [chromium] › core\bundle-import.spec.ts:107:1 › TC-025 390px: 확인 창 가로 넘침 0, 버튼이 보인다 @core (1.4s)
  ✓   5 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (1.2s)
  ✓   6 [chromium] › core\narrow-topbar.spec.ts:22:1 › TC-024 390px: 다섯 화면 가로 넘침 없음, 탭 아이콘 이동, 종 버튼 알림함 @core (7.7s)
  ✓   7 [chromium] › core\narrow-topbar.spec.ts:81:3 › TC-024 768px: 상단바 기존 모양 유지(탭 이름 보임, 한 줄) @core (1.3s)
  ✓   8 [chromium] › core\narrow-topbar.spec.ts:81:3 › TC-024 1440px: 상단바 기존 모양 유지(탭 이름 보임, 한 줄) @core (1.5s)
  ✓   9 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (2.0s)
  ✓  10 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (4.1s)
  ✓  11 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (2.2s)
  ✓  12 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (5.1s)
  ✓  13 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (3.4s)
  ✓  14 [chromium] › tasks\T-029.spec.ts:77:1 › TC-027 ① 실제 서버 응답(토큰 없음): 안내 문구·직접 입력 칸·현재 대상 @task:T-029 (1.5s)
  ✓  15 [chromium] › tasks\T-029.spec.ts:99:1 › TC-027 ② 가로챈 목록: 표시·필터·체크·저장 본문·취소·ok:true 처리 @task:T-029 (2.6s)
  ✓  16 [chromium] › tasks\T-029.spec.ts:149:1 › TC-027 ③ 390px: 가로 넘침 0, 저장·취소 보임, 행 44px 이상 @task:T-029 (1.4s)
  ✓  17 [chromium] › tasks\T-029.spec.ts:181:1 › TC-027 ④ 현재 대상 조회 500·enabled:false: 원인 문구와 저장 비활성 @task:T-029 (2.0s)
  ✓  18 [chromium] › tasks\T-029.spec.ts:207:1 › TC-027 ⑤ 저장 응답 ok:false: 성공 토스트 없음, 오류가 창 안에, 창 유지 @task:T-029 (1.9s)

  18 passed (54.9s)
== down
QA PASS
```
- 첫 시도는 `QA FAIL: test`(3건)였다. 원인은 테스트 쪽 두 가지다 — ① 상단바 배터리 툴팁(`/api/usage`)에 홈 경로가 실려 계정 이름 단언이 걸렸고(마스킹 대상에 `/api/usage` 추가), ② 행 높이 측정값 43.99px를 44와 비교(반올림 비교로 수정). 앱 결함이 아니고 단언 내용(저장 본문, 오류 문구, 넘침 0 등)은 약화하지 않았다. 두 번째 실행 결과가 위 원문이다.
- 정리 확인: 8778 health URL 닫힘(curl 응답 없음), 이 프로젝트는 컨테이너를 쓰지 않는다(podman 미기동), 남은 프로세스 없음.

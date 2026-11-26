# T-010 QA 보고서 — GitHub 연동 (GitLabClient 교체)
> 담당: qa (qa) · 2026-11-25 · 지시서: [T-010](../T-010-github-연동.md) · 설명서: [T-010-github-연동.md](./T-010-github-연동.md)

## 판정
**QA PASS** — 8/8 통과, skipped 0 (변경 오류 0, 회귀 0, 기존 결함 0). 기준 기록: qa-baseline.md 2026-11-25 17:01(T-025) 대비 핵심 TC 7개 모두 계속 통과, 이번 실행을 18:23 행으로 추가.

## 요약
- 앱(jar 18:14, 2.0.4)을 8778에 띄워 `GITLAB_ENABLED=true`, 토큰 없음, GitHub API 주소는 닿지 않는 로컬 주소로 두고 실행했다(실제 GitHub 호출 없음).
- 플랫폼 탭 설치 점검의 GitHub 항목이 `필요` 배지와 원인(`GITHUB_TOKEN` 비어 있음), 고치는 방법(Fine-grained 발급 경로·Contents/Issues/Pull requests 권한·`.env`·재시작)을 보였고, 점검 목록에 GitLab 항목·`write_repository`가 없다.
- 핵심 묶음 7개(TC-006·007·008·009·010·023·013)는 회귀 없이 통과했다.
- TC-015·016·017의 본체는 단위 테스트(가짜 HTTP)라 qa가 다시 돌리지 않았다. 화면에서 보이는 부분(Doctor)만 확인했다.

## 화면 흐름
### TC-015(화면 확인분) 설치 점검의 GitHub 항목 — 통과
1. [설치 점검 목록](./T-010-qa/artifacts/tasks-T-010-TC-015-설치-점검의--a62b2-인과-고치는-방법을-보이고-GitLab-표기-없음-chromium/01-설치%20점검%20목록.png) — 플랫폼 탭 로드
2. [GitHub 항목 토큰 없음 안내](./T-010-qa/artifacts/tasks-T-010-TC-015-설치-점검의--a62b2-인과-고치는-방법을-보이고-GitLab-표기-없음-chromium/02-GitHub%20항목%20토큰%20없음%20안내.png) — 최종 결과 화면
- 기대 결과: 토큰 없음 → `fail` + 고치는 방법, 토큰 값 없음 / 실제: 배지 「필요」, 「토큰이 비어 있습니다 (GITHUB_TOKEN)」, 힌트에 Fine-grained tokens·Contents·Issues·Pull requests 읽기/쓰기·Metadata 읽기·만료 90일·개인 계정 저장소만·`.env`·재시작. `/api/system/doctor` 원문에 토큰 형태 값·Authorization 없음, `github` 키 `fail`, `gitlab` 키 없음. 콘솔 오류 0.

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
| TC-013 | 통과 | TC-014 포함 |

전체 실행 시간: 약 0:55 (상한 10분, 앱 기동 별도) · 07 §8 목록 7개와 `@core` 7개 일치.

## 데이터
- 규모 가정(07 §3)은 이 변경과 무관(이슈 세션·이벤트 규모). 데이터 주입 없음 — 0행. 화면 확인만으로 충분해 seed 미사용.
- 지운 테스트 파일: 없음. `e2e/tasks/T-025.spec.ts`는 BOARD 완료지만 07 §8의 `@core` 승격 후보로 결정 대기라 남겼다.
- 새 테스트: `e2e/tasks/T-010.spec.ts`(`@task:T-010`). 실행 환경값: `GITLAB_ENABLED=true`(기본 false면 Doctor가 「꺼짐」 warn이라 토큰 점검까지 가지 않음), `GITHUB_TOKEN` 미설정.
- 증거 `report.json`의 로컬 계정 경로는 `~`/`<user>`로 치환했다. `app.log`·`trace.zip`은 gitignore.

## 묶음 제안
없음

## 제안
- 04 §8: GitHub 점검 확인용 환경(`GITLAB_ENABLED=true`)을 `start-app.sh`에 넣을지 PM 결정. 지금은 `--up` 앞에 환경값을 붙여 실행했다.
- 화면에 GitLab 문구가 남아 있다(대상 프로젝트 입력 「추가: group/repo (GitLab 경로)」, 설치 점검의 `GITLAB_WORK_DIR` 힌트). T-012 범위로 보인다.
- 확인하지 못한 것: 401·404·rate limit·만료 임박 표시와 Draft PR·코멘트 실동작(실제 GitHub 필요). 설명서의 「사용자가 서버에서 확인할 것」 절차로 사용자가 본다. 이 실행은 토큰 없음 경로만 화면으로 확인했다.

## 검증
- 로그: `<tmp>/qa-run-20261125-182141-11105.log`
```
log: <tmp>/qa-run-20261125-182141-11105.log
== up
== health (http://127.0.0.1:8778/api/system/status, timeout 180s)
== test (@task:T-010|@core)

Running 8 tests using 1 worker

  ✓  1 [chromium] › core\access-control.spec.ts:17:1 › TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403 @core (7.7s)
  ✓  2 [chromium] › core\empty-states.spec.ts:5:1 › TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다 @core (2.2s)
  ✓  3 [chromium] › core\notifications.spec.ts:5:1 › TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다 @core (2.5s)
  ✓  4 [chromium] › core\removed-features.spec.ts:72:1 › TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404) @core (4.9s)
  ✓  5 [chromium] › core\routine-crud.spec.ts:5:1 › TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음) @core (2.2s)
  ✓  6 [chromium] › core\shell-tabs.spec.ts:17:1 › TC-006 홈에서 다섯 탭을 차례로 이동한다 @core (9.7s)
  ✓  7 [chromium] › core\skill-crud.spec.ts:5:1 › TC-007 플랫폼에서 스킬을 등록하고 삭제한다 @core (3.8s)
  ✓  8 [chromium] › tasks\T-010.spec.ts:8:1 › TC-015 설치 점검의 GitHub 항목: 토큰 없음이면 원인과 고치는 방법을 보이고 GitLab 표기 없음 @task:T-010 (6.4s)

  8 passed (55.0s)
== down
QA PASS
```
- 정리 확인: `qa-run.sh`가 내림(down 실패 없음). 8778 응답 없음(curl), 8778·54329·8777 리슨 0(8777은 건드리지 않음). 프로세스 종료 거부 없음.

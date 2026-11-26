# T-012. 화면의 GitHub 표기 반영

```
id: T-012
담당: frontend-dev (frontend-dev)
상태: 완료
depends: [T-011, T-025, T-026]
병렬: 불가
충돌: [T-011]
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 구현
QA 확인: 예
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-24
```

Phase 3. 단계 수준 지시서. 기존 정적 HTML을 고친다(SPA 아님).

## 목적
화면이 GitHub 용어와 바뀐 응답 필드로 동작한다.

## 근거
- 요구사항: `FR-306`, `FR-305` ([01 §4.3](../01-requirements.md))
- 화면: `S-01`·`S-02`·`S-03`·`S-05`의 "변경 — Phase 3" ([06 §3](../06-functional-specification.md))
- 명세: [05 §2.3](../05-api-specification.md), T-011 변경 설명서의 이름 대응표
- 테스트: 회귀 핵심 묶음 ([07 §8](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), `CLAUDE.md`(`shortEntry` — 프로젝트 항목을 `/`로 자르지 않는다)

## 관련 코드 경로
- 수정: `src/main/resources/static/agent.html`, `research.html`, `home.html`, `platform.html`, `admin.js`, `ui.js`

## 범위
- 문구 "GitLab" → "GitHub", "MR" → "PR", 표기 `owner/repo#n`, 이슈·PR 링크.
- 응답 필드 `issueIid` → `issueNumber`, `mrUrl` → `prUrl`, `projectId` 사용 제거.
- (T-011 완료 뒤 PM 보완, 2026-11-26) `workId` 사용 제거(필드가 사라짐 — `agent.html`의 work 링크), 이벤트 종류 `GITLAB` → `REMOTE`(`agent.html`의 CSS `.k-GITLAB`, `ICON` 키), 비활성 안내의 `GITLAB_ENABLED=false` → `ISSUE_ENABLED=false`(`admin.js`), `research.html`의 `gitlab.enabled` → `issue.enabled`. `/api/issues-list` 응답의 `iid` 키는 그대로 읽는다(T-011에서 유지 결정). 위치 예시는 [T-011 설명서 「제안」](./results/T-011-이름-정리.md).
- 빈 상태 안내 문구(대상 저장소 없음, 토큰 없음)를 GitHub에 맞춘다.
- 대상 프로젝트 입력의 자리 표시 「추가: group/repo (GitLab 경로)」 → `owner/repo`(T-010 qa 제안 — 검증 명령 검색에 걸린다). 설치 점검 힌트의 환경변수 이름은 서버 문구라 T-011 범위다.
- qa 확인은 T-011의 응답 필드 개명까지 함께 본다(T-011은 `QA 확인: 아니오`). 커밋은 T-011과 한 묶음(메인 세션).

## 범위 밖
- 백엔드, 새 화면·디자인 변경, SPA

## 완료 조건
- [ ] 검증 명령의 검색 결과 0, `node --check` 통과
- [ ] 390 / 768 / 1440px 스크린샷 3장(에이전트 화면), 가로 넘침이 작업 전보다 늘지 않았다
- [ ] 변경 설명서(`docs/tasks/results/T-012-화면-github-표기.md`)가 그림·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-012-화면-github-표기.md](./results/T-012-화면-github-표기.md)

## 검증 명령
```bash
for f in src/main/resources/static/*.js; do node --check "$f" || exit 1; done
grep -rniE "gitlab|issueIid|mrUrl|projectId|workId" src/main/resources/static ; test $? -eq 1
```

## 검증 증거
- 일시: 2026-11-26 10:35
- 명령: 검증 명령(node --check 전체, grep 검색) + jar 재빌드 후 `bash e2e/scripts/start-app.sh`(8778)로 다섯 화면 390/768/1440 확인, `stop-app.sh`
- 결과: node --check 통과, 검색 일치 0, 콘솔 오류 0, 스크린샷 3장, 종료 후 8778·54329 리슨 0
- 변경 설명서: [results/T-012-화면-github-표기.md](./results/T-012-화면-github-표기.md)

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(단계 수준) |
| 2026-11-25 | 대기 | PM 수정: `depends`에 T-020(같은 `ui.js` — 좁은 폭 상단바 보정을 먼저, OI-26) |
| 2026-11-25 | 대기 | PM 수정: `depends`에 T-025(같은 `platform.html`·`admin.js` — 묶음 가져오기 확인을 먼저, OI-32) |
| 2026-11-26 | 대기 | PM 수정: 순서 교체 — `depends`에서 T-020을 빼고(T-020이 T-012 뒤로, 서버 반영을 기다리지 않게) T-026(TC-025 `@core` 승격)을 넣음. 범위에 「GitLab 경로」 자리 표시, T-011과 qa·커밋 묶음 |
| 2026-11-26 | 대기 | PM 보완(T-011 검수 뒤): 범위에 `workId`·이벤트 종류 `REMOTE`·`ISSUE_ENABLED`·`issue.enabled` 추가, 검증 명령 검색을 대소문자 무시(`-i`)로 하고 `workId` 추가(대문자 `GITLAB`이 빠지던 것). depends 모두 완료 — 착수 가능. 작업 트리에 T-011 미커밋 변경이 있다(같은 묶음 커밋) |
| 2026-11-26 | 검수 요청 | PM 검수 1차 통과 → qa 확인 대기. 검증 명령 재실행: `node --check` 전체 통과, 검색 일치 0(rc=1). 지시서 변조 없음. 파일 지도 = 작업 트리의 `static/` 6개 + 스크린샷(T-011 변경 제외). 서버 대조: `IssueSession` 게터(`getIssueNumber`·`getPrUrl`, `workId`·`projectId` 없음)와 `recent`·`recentLight` SQL 열(`issue_number`·`pr_url`, 카멜 변환 켜짐), `IssueEvent.REMOTE`, `/api/issues-list`의 `iid` 키가 화면 사용처와 일치. 390 스크린샷: 본문 넘침 없음, 상단바 넘침은 기존 문제(OI-26, T-020). 지시서 밖 `messenger.html` 예시 3곳은 같은 표기 교체라 인정. taste inspect 기록은 없으나 문자열·키 교체뿐이고 완료 조건에 없어 문제 삼지 않음. 세션이 있는 화면(PR 링크·`REMOTE` 색·`issueNumber` 목록)은 qa에서 세션 시드로 확인 |
| 2026-11-26 | 완료 | qa 확인 판정. 1차 QA FAIL(변경 오류 1: `agent.html` 694행 「Draft MR」 — 범위 "MR → PR" 안, 검증 명령 검색이 `MR`을 보지 않아 통과했던 것) → 메인 세션이 그 줄을 「Draft PR」 문구로 고침(변경 설명서 밖 수정, 같은 범위라 인정) → 재확인 **QA PASS**(TC-T012-1·2, `report.json` 2/0/0과 일치). `@core` 10개는 1차에서 PASS(`report.json` 10/0/0). PM 재확인: `static/`에서 `MR`·`Merge Request`·`GitLab`·검증 명령 검색 모두 0. 지시서 변조 없음. qa 제안 처리: 04 §8에 세션 시드 확인 방법 추가(모드 C), 세션 시드 TC는 `@core`에 넣지 않음(07 §8 변경 없음). `e2e/seed/IssueSessions.java`는 시드 도구로 유지, `e2e/tasks/T-012.spec.ts`는 다음 qa 유지 작업 때 정리. 이후 지시서에서 화면 문구 교체를 검증할 때는 검색어에 `MR`처럼 약어도 넣는다(PM 교훈). 커밋은 T-011과 한 묶음(2.2.0, 메인 세션) |

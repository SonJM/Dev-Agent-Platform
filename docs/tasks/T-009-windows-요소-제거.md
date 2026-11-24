# T-009. Windows 전용 요소와 로컬 프리뷰 제거

```
id: T-009
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-007]
병렬: 불가
충돌: [T-007]         # agent/claude/ClaudeCliAgent.java, admin/*, application.yml
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

단계 수준 지시서 — 착수 전에 `OI-04`(프리뷰 삭제)가 정해져야 한다.

## 목적
실행 방식이 "jar + systemd(서버) / `java -jar`(개발 PC)" 둘만 남는다.

## 근거
- 요구사항: `FR-206` ([01 §4.2](../01-requirements.md)), `NFR-401`
- 설계: [02 §4.2 D-8](../02-architecture.md) (대상 표와 유지 항목)
- 명세: [05 §2.2](../05-api-specification.md) (프리뷰·스크린샷 API)
- 구조: [04 §2](../04-project-structure.md)
- 테스트: `TC-014`, `TC-001`(Linux 기동 재확인), 회귀 `TC-006`~`TC-010`·`TC-013`

## 관련 코드 경로
- 삭제: `boot/Launcher.java`, `boot/Autostart.java`, `preview/`, `admin/PreviewController.java`, `config/PreviewProperties.java`, `gradle/dist.gradle`, `scripts/`, 루트의 `run.cmd`·`stop.cmd`·`restart.cmd`·`admin.cmd`·`list-rooms.cmd`·`install-autostart.ps1`, 관련 테스트(`preview/*Test`)
- 수정: `DevAgentApplication.java`(`--launch` 분기), `admin/SystemController.java`(실행 방식 `launcher`·`run.cmd` 제거 → `systemd`·`direct`), `agent/claude/ClaudeCliAgent.java`(프리뷰 심·안내·JDK 전환), `gitlab/IssueAgentService.java`·`VerifyService.java`(프리뷰 공유 대기, `jspcheck`·`preview` 도구), `admin/AgentMonitorController.java`(스크린샷), `ops/Doctor.java`, `build.gradle`, `application.yml`(`preview.*`, 도구 목록의 `preview`·`jspcheck`), `.env.example`, `env.template`, `README.md`, `CLAUDE.md`

## 범위
- D-8 표의 "삭제" 항목 전부. `platform/Os`의 분기와 `boot/Bootstrap`·`EmbeddedDb`는 유지.
- `jscheck` 심은 프리뷰와 무관하게 쓰이는지 확인해 유지/삭제를 정하고 근거를 변경 설명서에 적는다.
- `README.md`·`CLAUDE.md`에서 해당 설명을 지우고 실행 방법을 `deploy/README.md` 참조로 바꾼다.

## 범위 밖
- `deploy/`(T-003 산출물)의 내용 변경 — unit이 쓰는 환경변수 이름이 바뀌면 보고만 한다
- 플랫폼 관리(`maintain/`)의 동작 변경

## 완료 조건
- [ ] 검증 명령의 검색 결과 0, 삭제 대상 파일이 없다
- [ ] `gradlew test` 실패 0, Windows에서 `java -jar` 기동 후 `/api/system/status` 200
- [ ] `deploy/smoke/server-smoke.sh`가 `SMOKE PASS`(서버 격리 실행 — `DEV_AGENT_SSH`가 설정된 세션에서)
- [ ] 변경 설명서(`docs/tasks/results/T-009-windows-요소-제거.md`)가 그림·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-009-windows-요소-제거.md](./results/T-009-windows-요소-제거.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -rnE "PreviewService|Autostart|boot\.Launcher|jpackage|preview deploy|preview sync" src/main build.gradle gradle ; test $? -eq 1
ls run.cmd stop.cmd restart.cmd install-autostart.ps1 scripts 2>/dev/null ; test $? -ne 0
bash deploy/smoke/server-smoke.sh
```

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(단계 수준) |

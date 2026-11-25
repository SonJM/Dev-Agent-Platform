# T-009. Windows 전용 요소와 로컬 프리뷰 제거

```
id: T-009
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-018]
병렬: 불가
충돌: [T-007, T-018]  # agent/claude/ClaudeCliAgent.java, mcp/McpConfigService.java, ops/Doctor.java, admin/*, application.yml
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

단계 수준 지시서 — `OI-04`는 **삭제로 결정됐다**(2026-11-25, 사용자). 착수 전에 PM이 T-007·T-018 결과를 반영해 다시 확인하고, `OI-20`(남는 심의 Windows 덮어쓰기)을 정한다.

## 목적
실행 방식이 "jar + systemd(서버) / `java -jar`(개발 PC)" 둘만 남고, 로컬 프리뷰(Tomcat·WAR 전제)가 저장소에 남아 있지 않다.

## 근거
- 요구사항: `FR-206` ([01 §4.2](../01-requirements.md)), `NFR-401`
- 결정: `OI-04`(프리뷰 삭제 확정) ([00-INDEX](../00-INDEX.md))
- 설계: [02 §4.2 D-8](../02-architecture.md) (대상 표와 유지 항목)
- 명세: [05 §2.2](../05-api-specification.md) (프리뷰·스크린샷 API)
- 구조: [04 §2](../04-project-structure.md), 격리 실행 [04 §8](../04-project-structure.md)
- 테스트: `TC-014`, `TC-001`(Linux 기동 재확인), 회귀 `TC-006`~`TC-010`·`TC-013`·`TC-023`

## 관련 코드 경로
- 삭제: `boot/Launcher.java`, `boot/Autostart.java`, `preview/`, `admin/PreviewController.java`, `config/PreviewProperties.java`, `gradle/dist.gradle`, `scripts/`, 루트의 `run.cmd`·`stop.cmd`·`restart.cmd`·`admin.cmd`·`list-rooms.cmd`·`install-autostart.ps1`, 관련 테스트(`preview/*Test`)
- 수정: `DevAgentApplication.java`(`--launch` 분기), `admin/SystemController.java`(실행 방식 `launcher`·`run.cmd` 제거 → `systemd`·`direct`), `agent/claude/ClaudeCliAgent.java`(프리뷰 심·안내·JDK 전환), `gitlab/IssueAgentService.java`·`VerifyService.java`(프리뷰 공유 대기, `jspcheck`·`preview` 도구), `admin/AgentMonitorController.java`(스크린샷 API), `ops/Doctor.java`, `build.gradle`, `application.yml`(`preview.*`, 도구 목록의 `preview`·`jspcheck`), `.env.example`, `env.template`, `README.md`, `CLAUDE.md`
- 수정(이 작업에 한해 허용): `deploy/env.server.example`의 `PREVIEW_ENABLED` 줄과 그 주석, `deploy/README.md`에서 `PREVIEW_ENABLED`를 말하는 문장, `e2e/scripts/start-app.sh`가 주는 `PREVIEW_ENABLED`와 `CLAUDE_WARM`·`CLAUDE_WARM_PREWARM`(T-018에서 읽는 곳이 없어짐), 21행 주석의 warm 설명(없어진 설정이므로 정리 — 04 §8 표는 PM이 검수 때 고친다), `src/main/resources/static/admin.js` 119행 `PLAT_KEYS`의 `warm`·`warmAlive`·`warmFailures` 표시 키 3개(T-018에서 서버가 더는 주지 않음 — 이 3개 키 삭제만)

## 범위
- D-8 표의 "삭제" 항목 전부. `platform/Os`의 분기와 `boot/Bootstrap`·`EmbeddedDb`는 유지.
- 화면은 T-008에서 프리뷰 항목·스크린샷 갤러리를 이미 지웠다. 이 작업은 그 API(`/api/preview/**`, `/api/agent/sessions/{id}/shots/**`)를 지운다. 착수 때 `src/main/resources/static`에 호출처가 없는지 다시 검색한다.
- 서버 응답으로 화면에 남은 프리뷰 흔적(T-008 qa 제안): `ops/Doctor`의 「로컬 프리뷰(화면 검증)」 점검 항목을 지운다(플랫폼 「시스템」 카드 설치 점검에서 사라져야 한다 — qa 확인 항목, 07 TC-013 T-009 시점). `mcp/McpConfigService`의 playwright 연동(`playwrightEnabled`·`playwrightTools`·`configFor`의 playwright 분기)과 `RESERVED`의 `playwright`도 프리뷰와 함께 지운다. `application.yml`의 `preview.overlay-dirs`(기본값에 `opensearch` 폴더 이름)가 사라지면 T-018 검증 명령에서 제외한 두 패턴도 없어진다.
- `jscheck` 심은 프리뷰와 무관하게 쓰이는지 확인해 유지/삭제를 정하고 근거를 변경 설명서에 적는다. 심이 하나라도 남으면 Windows에서의 "항상 덮어쓰기"를 어떻게 할지는 `OI-20` 결정에 따른다.
- `README.md`·`CLAUDE.md`에서 해당 설명을 지우고 실행 방법을 `deploy/README.md` 참조로 바꾼다.

## 범위 밖
- `deploy/`의 그 밖의 내용(T-003·T-017 산출물) — unit이 쓰는 환경변수 이름이 바뀌면 보고만 한다
- 화면 파일(위 `PLAT_KEYS` 3개 키 삭제 제외). 단, 「시스템」 카드의 실행 방식 라벨(`admin.js`의 `RUN_MODE`)이 백엔드 값과 어긋나게 되면 보고한다(PM이 frontend-dev 소규모 지시서로 낸다)
- 플랫폼 관리(`maintain/`)의 동작 변경
- 프리뷰를 대신할 일반 화면 검증 기능(계획 없음 — 06 §5)

## 완료 조건
- [ ] 검증 명령의 검색 결과 0, 삭제 대상 파일이 없다
- [ ] `gradlew test` 실패 0, Windows에서 `java -jar` 기동 후 `/api/system/status` 200(04 §8 형태로 띄우고 반드시 내린다)
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
| 2026-11-25 | 대기 | PM 수정: OI-04 결정(프리뷰 삭제 확정) 반영, 화면은 T-008이 먼저 지움을 명시, `PREVIEW_ENABLED` 흔적(`deploy/`·`start-app.sh`) 정리를 범위에 추가, 실행 방식 라벨은 보고 사항으로, OI-20은 착수 전 결정 |
| 2026-11-25 | 대기 | PM 수정(T-007 분할): `depends`를 T-018로(T-007 → T-018 → T-009). T-008 qa 제안 반영 — Doctor의 「로컬 프리뷰」 항목 제거를 명시(qa 확인 항목), playwright MCP 연동·예약 이름 제거를 범위에 추가. 단계 수준 그대로 — 착수 전 PM 재확인 |
| 2026-11-25 | 대기 | PM 수정(T-018 검수): T-018 설명서 제안·리뷰 LOW4를 배정 — `start-app.sh`의 `CLAUDE_WARM`·`CLAUDE_WARM_PREWARM` 정리(같은 줄의 `PREVIEW_ENABLED`와 함께), `admin.js` `PLAT_KEYS`의 warm 표시 키 3개 삭제를 "이 작업에 한해 허용"에 추가. 04 §8 표는 T-009 검수 때 PM |

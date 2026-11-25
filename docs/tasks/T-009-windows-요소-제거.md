# T-009. Windows 전용 요소와 로컬 프리뷰 제거

```
id: T-009
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-018, T-019]
병렬: 불가
충돌: [T-021]  # agent/claude/ClaudeCliAgent.java, gitlab/VerifyService.java — T-021은 이 작업 뒤
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

착수 가능 — 2026-11-25 PM 재확인(T-007·T-018 반영 뒤 코드 대조, `OI-20` 결정 반영). 아래 "관련 코드 경로"는 그 시점의 검색 결과다. 다르면 임의로 넓히지 말고 보고한다.

## 목적
실행 방식이 "jar + systemd(서버) / `java -jar`(개발 PC)" 둘만 남고, 로컬 프리뷰(Tomcat·WAR 전제)와 그에 딸린 `~/bin` 심이 저장소에 남아 있지 않다.

## 근거
- 요구사항: `FR-206` ([01 §4.2](../01-requirements.md)), `NFR-401`
- 결정: `OI-04`(프리뷰 삭제), `OI-20`(①: 남는 심이 없게 — 아래 범위 4) ([00-INDEX](../00-INDEX.md))
- 설계: [02 §4.2 D-8](../02-architecture.md) (대상 표와 유지 항목)
- 명세: [05 §2.2](../05-api-specification.md) (프리뷰·스크린샷 API)
- 구조: [04 §2](../04-project-structure.md), 격리 실행 [04 §8](../04-project-structure.md)
- 테스트: `TC-014`, `TC-013`(T-009 시점 — 설치 점검에 「로컬 프리뷰(화면 검증)」 행 없음), `TC-001`(Linux 기동 재확인), 회귀 `TC-006`~`TC-010`·`TC-023` ([07 §4·§8](../07-test-plan.md))

## 관련 코드 경로
(패키지 접두 `src/main/java/io/github/lib0823/devagent/` 생략)
- **삭제**: `boot/Launcher.java`, `boot/Autostart.java`, `preview/`(`PreviewService`), `admin/PreviewController.java`, `config/PreviewProperties.java`, `agent/claude/AgentShims.java`, `gradle/dist.gradle`과 `gradle/dist/`(README.txt·dev-agent.cmd·dev-agent.sh·dev-agent.ico), `scripts/`(`bin/preview`·`bin/jscheck`·`bin/jspcheck`, `preview-tomcat.cmd`, `setup-preview.ps1`), 루트의 `run.cmd`·`stop.cmd`·`restart.cmd`·`admin.cmd`·`install-autostart.ps1`(`list-rooms.cmd`는 이미 없음). 테스트: `preview/PreviewConfigTest`·`PreviewSyncTest`, `agent/claude/AgentShimsTest`, `mcp/PlaywrightNodeOsTest`
- **수정**
  - `DevAgentApplication.java` — `--launch` 분기
  - `platform/RunMode.java`(+`RunModeTest`) — `launcher`·`run.cmd`(`DEV_AGENT_LAUNCHER`·`DEV_AGENT_SUPERVISED`)를 지워 `systemd`·`direct`만. `writesStopFlag`가 항상 거짓이 되면 메서드째 지운다
  - `admin/SystemController.java` — 실행 방식 주석, `bot-stop.flag` 쓰기(run.cmd 전용) 제거. 재시작 = 종료 코드 10·종료 = 0(systemd) 규칙은 유지
  - `agent/CodingAgent.java` — `run(...)`의 `shotsDir`·`previewUp` 인자와 그것만 위해 있는 오버로드를 지운다(포트 정리). 호출처 `gitlab/IssueAgentService`·`research/ResearchService`·`maintain/MaintainService`는 인자만 맞춘다
  - `agent/claude/ClaudeCliAgent.java` — `installShims()`(`@PostConstruct`)와 매 실행 전 호출, `scripts/bin`을 PATH 앞에 붙이는 줄, `previewTools`·`previewHint`·`verifyHint`의 프리뷰·스크린샷·playwright 부분, 프리뷰 JDK로 `JAVA_HOME`을 바꾸는 분기(플랫폼 관리용 "이 JVM의 JDK" 분기는 유지), `preview deploy` 시간을 근거로 한 주석
  - `gitlab/IssueAgentService.java` — `PreviewService` 의존, 스크린샷 폴더·`shots()`, `stopPreviewIfMine`, `previewUrlFor`, 공유 대기, 안내문의 스크린샷 수·프리뷰 줄. `gitlab/IssuePrompts`(또는 같은 문구가 있는 곳)의 "프리뷰가 떠 …" 문구
  - `gitlab/VerifyService.java`(+`VerifyServiceTest`) — `PreviewProperties` 의존, `jspcheck` 단계(바뀐 `.jsp`는 검사 생략으로 보고하거나 단계를 지운다 — 판단 근거를 설명서에), 프리뷰 Maven·JDK 선택(→ PATH의 `mvn`), 리뷰어 프롬프트의 `jspcheck/jscheck`·playwright·스크린샷 문구. 봇이 직접 돌리는 `node --check`는 유지
  - `admin/AgentMonitorController.java` — `/api/agent/sessions/{id}/shots`·`/shots/{name}`, 응답의 `previewUrl`
  - `mcp/McpConfigService.java`(+`McpCustomOnlyTest`) — playwright 연동(`playwrightEnabled`·`playwrightTools`·`configFor`의 playwright 분기·`playwrightNode`), `RESERVED`의 `playwright`. `RESERVED`가 비면 상수와 검사를 지우고 `McpCustomOnlyTest`의 `RESERVED` 단언을 그에 맞춘다
  - `ops/Doctor.java` — 「로컬 프리뷰(화면 검증)」 항목(`previewCheck`), Git Bash 항목 안내문의 "검사 스크립트(jscheck·jspcheck)" 문구
  - `maintain/MaintainPrompts.java` 25행 — `restart.cmd·stop.cmd·run.cmd` 언급을 남는 실행 방식(systemd·직접 실행)에 맞춘 **문구만**(플랫폼 관리 동작은 바꾸지 않는다)
  - `build.gradle`(`apply from: 'gradle/dist.gradle'`), `application.yml`(`preview:` 블록 전체, `gitlab.verify.tools` 기본값의 `Bash(preview status:*)`·`Bash(preview log:*)`·`Bash(jscheck:*)`·`Bash(jspcheck:*)`, 주석의 `jscheck·jspcheck`·`preview deploy`), `.env.example`(`PREVIEW_ENABLED`·`PREVIEW_LOGIN_ID`·`PREVIEW_LOGIN_PW`), `src/main/resources/dist/env.template`(`PREVIEW_ENABLED`), `README.md`, `CLAUDE.md`
- **수정(이 작업에 한해 허용)**: `deploy/env.server.example`의 `PREVIEW_ENABLED` 줄과 그 주석, `deploy/README.md` 87행에서 `PREVIEW_ENABLED`를 말하는 부분, `e2e/scripts/start-app.sh` 20행의 `PREVIEW_ENABLED=false`와 23행의 `CLAUDE_WARM`·`CLAUDE_WARM_PREWARM`(T-018에서 읽는 곳이 없어짐)·21행 근처 warm 주석, `src/main/resources/static/admin.js` 119행 `PLAT_KEYS`의 `warm`·`warmAlive`·`warmFailures` 키 3개 삭제와 157행 `RUN_MODE`를 백엔드 값에 맞추기(`systemd: '서비스(systemd)'`, `direct: '직접 실행'` — `launcher`·`run.cmd` 삭제). 화면 파일은 이 두 줄 외에 건드리지 않는다

## 범위
1. D-8 표의 "삭제" 항목 전부와 위 "관련 코드 경로". `platform/Os`의 분기와 `boot/Bootstrap`·`EmbeddedDb`는 유지한다.
2. 화면은 T-008에서 프리뷰 항목·스크린샷 갤러리를 이미 지웠다(2026-11-25 재확인: `static/`에 `preview`·`shots` 호출처 없음). 이 작업은 그 API(`/api/preview/**`, `/api/agent/sessions/{id}/shots/**`)를 지운다. 착수 때 `static/`을 한 번 더 검색한다.
3. 서버 응답으로 화면에 남은 프리뷰 흔적: Doctor의 「로컬 프리뷰(화면 검증)」 항목이 플랫폼 「시스템」 카드 설치 점검에서 사라진다(qa 확인 항목). playwright MCP 연동은 프리뷰와 함께 지운다.
4. **심(`OI-20` 결정 ①, 2026-11-25 사용자)**: 심 네 개의 대상이 모두 사라진다 — `preview`·`jscheck`·`jspcheck`는 지우는 `scripts/bin/`의 스크립트이고 `mvn`은 프리뷰 Maven이다. 그리고 `jscheck`는 프리뷰가 켜졌을 때만 허용 도구에 들어가며 JS 문법 검사는 봇이 `node --check`로 직접 한다. 따라서 **심을 전부 없앤다**: `AgentShims`·`installShims()` 삭제, 앱이 `<user.home>/bin`에 아무것도 쓰지 않는다(Windows 포함). 이미 써 둔 심 파일을 앱이 지우지는 않는다(사용자 PC의 파일 — 설명서 `## 적용 방법`에 "`~/bin`의 표식(`dev-agent 이 생성한 심`)이 있는 파일은 지워도 된다" 한 줄).
5. `README.md`·`CLAUDE.md`: 프리뷰·실행 파일 배포·`run.cmd`·트레이·심(「인스턴스 2개」 함정 포함) 설명을 지우고 실행 방법을 `deploy/README.md`(서버)와 `java -jar`(개발 PC) 참조로 바꾼다. 버전은 2.0.0 그대로(같은 major 안의 제거 마무리 — T-018과 같은 판단).

## 범위 밖
- `deploy/`의 그 밖의 내용(T-003·T-017 산출물) — unit이 쓰는 환경변수 이름이 바뀌면 보고만 한다
- 화면 파일(위 `admin.js` 두 줄 제외), 04·07 문서(04 §8의 심·warm 문구는 PM이 검수 때 고친다)
- 플랫폼 관리(`maintain/`)의 동작 변경(위 문구 한 줄 제외)
- 자식 프로세스 환경의 비밀 변수 제거(`OI-27` — T-021). `pb.environment()` 처리 구조는 바꾸지 않는다
- 프리뷰를 대신할 일반 화면 검증 기능(계획 없음 — 06 §5)
- `e2e/`(위 `start-app.sh` 줄 제외) — TC-013 T-009 시점 단언은 qa 확인 때 qa가 `e2e/core/removed-features.spec.ts`에 더한다

## 완료 조건
- [ ] 검증 명령의 검색 결과 0, 삭제 대상 파일이 없다
- [ ] `gradlew test` 실패 0, Windows에서 `java -jar` 기동 후 `/api/system/status` 200(04 §8 형태로 띄우고 반드시 내린다). 그때 `-Duser.home`으로 준 홈 아래에 `bin/`이 생기지 않는다(설명서에 확인 방법과 결과)
- [ ] `/api/system/doctor` 응답에 `preview` 항목이 없다, `/api/system/status`의 실행 방식이 `direct`(직접 기동 시)
- [ ] `deploy/smoke/server-smoke.sh`가 `SMOKE PASS`(서버 격리 실행 — `DEV_AGENT_SSH`가 설정된 세션에서. 없으면 설명서에 "미실행 — 환경변수 없음"으로 적고 검수 요청)
- [ ] 변경 설명서(`docs/tasks/results/T-009-windows-요소-제거.md`)가 그림·설명, `jspcheck` 단계 처리 근거, `## 적용 방법`(심 파일 정리 한 줄, `.env`에서 지울 키)과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-009-windows-요소-제거.md](./results/T-009-windows-요소-제거.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -rnE "PreviewService|PreviewProperties|AgentShims|installShims|Autostart|boot\.Launcher|jpackage|preview deploy|preview sync|jspcheck|jscheck|playwright|shotsDir|previewUp|/shots|bot-stop\.flag|DEV_AGENT_LAUNCHER|DEV_AGENT_SUPERVISED|PREVIEW_" src/main build.gradle gradle .env.example ; test $? -eq 1
ls run.cmd stop.cmd restart.cmd admin.cmd install-autostart.ps1 scripts gradle/dist gradle/dist.gradle 2>/dev/null ; test $? -ne 0
grep -nE "PREVIEW_ENABLED|CLAUDE_WARM" e2e/scripts/start-app.sh deploy/env.server.example ; test $? -eq 1
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
| 2026-11-25 | 대기 | PM 재확인(착수 가능): T-007·T-018 뒤 코드 대조로 관련 코드 경로를 파일·메서드 단위로 확정(`AgentShims`, `RunMode`, `CodingAgent` 인자, `VerifyService` jspcheck·프리뷰 Maven, `AgentMonitorController` 스크린샷 API, `MaintainPrompts` 문구, `gradle/dist/`, 테스트 4개). OI-20 결정 ① → 심 전부 제거(범위 4). `RUN_MODE` 라벨 맞추기를 허용 범위로 옮김(보고 사항이던 것). `depends`에 T-019(같은 core 파일), 충돌 T-021. 검증 명령 검색 패턴 확대 |

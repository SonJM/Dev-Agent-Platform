# Dev Agent — Claude 작업 가이드

**Dev Agent** 는 로컬에서 도는 에이전트 운용 플랫폼이다: GitLab 이슈 수정 에이전트 + 메신저 봇(선택, `MessengerPort` 어댑터) + 질의·루틴을 한 Spring Boot 앱에 담았다. 제품 이름은 Dev Agent 하나만 쓴다(패키지 `io.github.lib0823.devagent`, jar 이름 `dev-agent`).
이 문서는 이 저장소에서 작업하는 Claude(및 사람)가 먼저 알아야 할 것만 적는다. 상세는 `README.md`.

## 한눈에

| 구성 | 패키지 | 역할 |
|---|---|---|
| 메신저 명령 | `messenger/ command/ llm/` | 자기 대화 새 글 수신(`MessengerReceiver`)·알림/답 전송, 첫 단어로 `이슈 …`·`질문 …` 명령 분기(`MessengerCommandRouter`). 방 목록·다른 방 읽기·그룹 전송·요약·멘션·문서 RAG 는 없다(2.0.0) |
| 이슈 에이전트 | `gitlab/` | 라벨(`ai-fix`) 이슈를 분석→설계 확인→수정→검증(봇 검사+독립 리뷰, 자동 재수정)→리뷰 확인→컨벤션 브랜치(`fix/123456`) 푸시→Draft MR(→develop). 자기방 대화형 확인 |
| 질의(리서치) | `research/`, `static/research.html`, `admin/ResearchController` | 「리서치」 탭/`질문 batch: …`: 대상 프로젝트 읽기 전용 worktree 에서 소스를 훑어 마크다운 문서로 답(수정·빌드 없음). 후속 질문은 같은 세션 `--resume`. 이슈 워커와 분리된 단일 워커 |
| 에이전트 대시보드 | `static/agent.html`, `admin/AgentMonitorController` | 스테퍼·실시간 활동 로그(SSE)·설계안/변경요약·diff·스크린샷·진행/중단/피드백 |
| 루틴(예약 질의) | `routine/`, `admin/RoutineController` | 요일·시각마다 같은 질문을 질의 워크플로우에 접수(1분 틱). 리서치 탭 "예약" · 루틴 목록 |
| 기동·배포 | `boot/`, `admin/SystemController`, `ops/Doctor` | `.env` 틀·메신저 없음 모드·내장 DB(`Bootstrap`/`EmbeddedDb`), 설치 점검·재시작·구성 묶음 |
| 알림함 | `notify/`, `admin/NotificationController`, `ui.js` | 상단 종 버튼(Alt+N): 화면 토스트 기록(localStorage) + 플랫폼 알림(이슈·질의·시스템, `notification` 테이블)을 합쳐 보여 준다. 메신저와 독립 |
| 플랫폼 관리 | `maintain/`, `admin/MaintainController`, `ui.js`(mt*) | 상단 렌치 버튼(Alt+M) 채팅 창: 이 저장소를 작업 디렉터리로 한 Claude 세션이 플랫폼 자체의 버그 확인·수정·빌드·커밋·스킬 등록. 푸시·재시작은 창의 버튼 |

LLM 호출은 전부 **Claude Code CLI(headless)** 다. 코딩은 저장소 worktree 에서 `claude -p --session-id/--resume`(`agent/claude/ClaudeCliAgent`). API 키 없음(구독 로그인).

**포트-어댑터 구조(11-16)**: 코어는 포트만 의존한다 — `messenger/MessengerPort`(자기 대화 받기·보내기만 — 방 목록·다른 방 읽기·그룹 전송 없음. 알림 유형 켜고 끄기는 코어 `MessengerService`), `agent/CodingAgent`. 구현체는 `messenger/none/`(메신저 없음 — 기본), `agent/claude/` 에만 있고 `platform.messenger|coding-agent` 값(`@ConditionalOnProperty`, 기본 none/claude-cli)으로 하나씩 뜬다. 활성 구현체 `GET /api/platform`. **코어 패키지(gitlab/research/command/admin)에서 어댑터 클래스(`ClaudeCli*`, 메신저 어댑터)를 직접 참조하지 말 것.** **메신저의 사용자·메시지 id 는 어댑터가 정하는 문자열**이다(Slack `ts`, Discord snowflake 등): 코어는 같은지만 비교한다. `receiveSelf` 는 자기 대화의 새 글을 시간순으로 돌려주고 글마다 `cursorAfter` 를 준다(코어는 커서를 해석하지 않고 `bot_setting.messenger_cursor.<provider>` 에 저장만 한다). 새 메신저/코딩 에이전트는 포트 구현 빈 추가 + 설정값 변경. 모든 포트는 `health()`(즉시 응답, 호출 없음, `platform/PortHealth`·`CallStats`)를 구현해야 하고 코딩 에이전트 포트는 토큰 없는 `selfTest()` 도 — 플랫폼 탭 "연동" 카드(`PlatformController`: `GET /api/platform`, `POST /api/platform/test?port=`)가 이를 보여 준다. 구현체 전환·자격증명은 화면에 두지 않는다(.env + 재시작).

## 실행 · 빌드 · 배포 (소스 실행)

- 빌드: JDK 17 로 `.\gradlew.bat test bootJar`. 산출물 `build/libs/dev-agent.jar`. 실행 방식은 둘뿐이다 — 서버는 systemd unit(`deploy/README.md`), 개발 PC 는 `java -jar build/libs/dev-agent.jar`(감시자 없음, 화면의 「재시작」은 거부됨). 새 빌드는 재시작해야 반영된다.
- **재시작은 이슈 세션이 대기 상태(DESIGN_WAIT/REVIEW_WAIT)일 때만.** 작업 중(ANALYZING/FIXING/VERIFYING/PUSHING) 재시작하면 그 세션은 FAILED 처리된다. 확인: `GET http://localhost:8777/api/issues`.
- 화면(**Dev Agent**): `http://localhost:8777/` → 홈 `/home.html`(Alt+1, 전체 현황) · 에이전트 `/agent.html`(Alt+2) · 질의 `/research.html`(Alt+3, 탭 이름 「리서치」) · 메신저 `/messenger.html`(Alt+4, 알림 유형 켜기/끄기) · 플랫폼 `/platform.html`(Alt+5, 연동·에이전트 설정·스킬·MCP·RAG). 예전 `/index.html`·`/admin.html` 은 메신저로 리다이렉트(`admin/WebConfig`). 홈·메신저·플랫폼은 `static/admin.js`·`admin.css` 를 함께 쓰고 각 HTML 이 필요한 `load*()` 만 부른다 — **admin.js 최상위에서 요소에 이벤트를 걸 때는 `on(id, ev, fn)`**(요소가 없는 화면에서 예외가 나면 그 화면 전체가 멈춘다). 전부 localhost 전용.
- 설정은 `application.yml`(기본값) + `.env`(비밀값, git 제외). 예시는 `.env.example`. **토큰/비밀번호는 절대 커밋하지 않는다.**
- 로그: `logs/dev-agent.log`. DEBUG 로그는 시끄러우니 grep 시 필요한 것만 본다.

## 기동 준비 · 실행 방식 (11-16)

- **두 가지 실행 방식**: ① 서버 — jar + systemd(`deploy/`, `DEV_AGENT_RUN_MODE=systemd`), ② 개발 PC — `java -jar`(`DB_URL` 지정 또는 내장 DB). `main` 은 `Bootstrap.prepare()` 뒤 Spring 을 띄운다. 예전의 실행 파일 패키지(`Launcher`·트레이·jpackage)와 `run.cmd` 감시 루프는 없다(2.0.0).
- `Bootstrap.prepare()`(Spring 전, `.env` 를 직접 읽음): `.env` 없으면 틀 생성(`resources/dist/env.template`) → (메신저 기본값은 `platform.messenger=none` — `messenger/none/NoMessengerAdapter`: 방 없음, 전송 버림) → `DB_URL` 없으면 내장 PostgreSQL(`EmbeddedDb`, zonky, 홈의 `pgdata-embedded/`·`pg-bin/`, 포트 54329, 127.0.0.1 trust) → `bot_setting` 테이블이 없으면 `db/schema.sql`(jar 에 포함, **DROP 문 제외**) 실행.
- **필수 설정에 기본값 없는 자리표시자(`${X}`)를 새로 만들지 말 것** — 설정 없이 첫 기동이 실패한다. 숫자형은 `${X:0}`. `.env` 틀에 빈 값(`KEY=`)을 두면 yml 기본값을 빈 문자열로 덮으니 숫자·기본값 있는 키는 주석으로 둔다.
- 내장 DB 는 앱이 강제 종료되면 서버만 남는다 → 다음 기동이 포트가 열려 있으면 그대로 붙는다. 정상 종료(`/api/system/shutdown`)는 종료 훅이 DB 까지 내린다. 첫 기동은 바이너리 풀기+initdb 로 1분, 이후 8초 안팎.
- 종료 코드 규약: 앱이 종료 코드 10(`SystemController.EXIT_RESTART`)으로 끝나면 감시자(systemd `Restart=on-failure`)가 다시 띄우고, 0 이면 다시 뜨지 않는다. 감시자 없이 뜬 앱(`direct`)은 「재시작」을 거부한다(`RunMode.supervised`).
- Claude CLI 경로는 박아 두지 않는다: `config/ClaudeCliLocator`(PATH → npm 전역 → `~/.local/bin` → Homebrew). OS 차이는 `platform/Os`(`viaShell`·`shellLine`)로 — `cmd /c` 를 직접 쓰지 말 것.
- 설치 점검 `ops/Doctor`(`/api/system/doctor`, 60초 캐시, 토큰 없음) — 새 외부 의존(도구·서버)을 추가하면 항목과 "고치는 방법" 한 줄을 여기에 넣는다. `/api/system/status` 는 화면이 자주 부르므로 외부 호출 금지.
- 구성 묶음 `admin/BundleController`: 스킬·사용자 MCP·루틴·에이전트 설정·대상 프로젝트만. **계정·토큰·`.env` 값은 절대 넣지 않는다**(MCP 는 `saveCustom` 검증을 그대로 타게 해 비밀 헤더가 든 묶음은 거부된다). 새 "공유할 만한 구성"이 생기면 export/import 양쪽에 추가.
- 상태를 바꾸는 시스템 API(restart/shutdown/import)는 `SystemController.foreign(req)` 로 Origin 을 확인한다(다른 사이트 페이지의 요청 차단).
- 루틴: `RoutineService.isDue` — 오늘이 대상 요일 && 예약 시각 지남 && 그 시각 이후 실행·수정된 적 없음(수정 시각을 보는 이유: 오후에 만든 09:00 루틴이 바로 돌지 않게). `research_job.requester` 는 VARCHAR(32) 라 `"routine"` 고정(이름을 붙이면 넘친다).

## 버전 (11-17)

- `build.gradle` 의 `version` 한 곳(major.minor.patch) → `buildInfo()` → `/api/system/status` → 상단바 배터리 왼쪽 `v1.1.0`·플랫폼 시스템 카드·배포 파일 이름. 시작은 1.0.0(첫 실행 파일 배포), 알림함부터 1.1.0.
- 플랫폼 관리(렌치 버튼 채팅 창)부터 1.8.0, 메신저 알림 유형별 끄기부터 1.9.0, 리서치 이미지 첨부부터 1.10.0, 기본 스킬부터 1.11.0, 에이전트 화면 문서·Diff 보기 정리부터 1.12.0, 메신저 포트 축소·수집/요약/멘션/문서 RAG 제거부터 2.0.0.
- 올리는 기준: **major** = 기존 설치가 그대로는 안 도는 변경(설정 키·DB 스키마 비호환, 실행 방식 변경) / **minor** = 기능 추가(화면·API·설정 추가, 하위 호환) / **patch** = 버그 수정·문구·내부 정리. 사용자에게 보이는 변경을 커밋할 때 같이 올린다(문서만 고친 커밋은 그대로).

## 플랫폼 관리 (11-23, 1.8.0)

- 목적: 플랫폼 자체를 고치려고 터미널에서 Claude Code 를 켜는 일을 화면 안으로. `MaintainService` 가 **이 소스 저장소를 cwd 로** `CodingAgent.run(…, Stage.FIX, Overrides(source="maintain"))` 을 돌린다(그래서 이 CLAUDE.md 가 그 세션에도 읽힌다). worktree 없음 → 한 번에 한 요청(`submit` 이 진행 중이면 거부), 대화(`maintain_turn.thread_id`)마다 세션 하나를 `--resume`. 이벤트는 `IssueEventBus` 공유(세션 id = `EVENT_BASE(20억)` + 차례 id).
- **세션은 push·재시작을 하지 않는다.** 세션은 봇의 자식 프로세스라 재시작하면 같이 죽고(재기동 때 RUNNING 은 FAILED 로 닫고 「이어서 진행해 줘」로 같은 세션 재개), 푸시는 사람이 본 뒤 올린다. `ClaudeCliAgent` 가 source 가 `maintain` 이면 `--disallowedTools Bash(git push:*)` 를 붙이고, 사용자 정의 MCP 를 빼고, JAVA_HOME 을 이 JVM 의 JDK 로 둔다. 창의 「푸시」= `MaintainService.push()`(`git push -u origin <현재 브랜치>`, 강제 없음), 「재시작 적용」= 기존 `/api/system/restart`(`busyCount` 에 `maintain.busy()` 포함). 「재시작 적용」은 `build/libs/dev-agent.jar` 수정 시각이 JVM 시작보다 나중일 때만 보인다.
- 켜지는 조건: `maintain.repo-dir` 가 비면 실행 디렉터리가 소스 저장소(`.git`·`build.gradle`·`src/main/java`, `isSourceRepo`)일 때만. 배포판은 꺼져 있고 버튼도 숨는다(`GET /api/maintain` 의 `enabled`). 허용 도구는 `maintain.tools`.
- 상태를 바꾸는 API(`POST /api/maintain`, `…/abort`, `/push`)는 전부 `SystemController.foreign(req)` 확인 — 요청이 코드 수정 세션으로 이어지므로 다른 사이트 페이지가 지시를 밀어 넣지 못하게. 새 엔드포인트를 추가할 때도 지킬 것.
- 화면은 `ui.js` 의 "플랫폼 관리" 블록 하나(모든 탭 공통, `dialog.maint`). **SSE 를 쓰지 않고** 창이 열린 동안만 폴링한다(진행 중 2초, 저장소 상태 10초) — 탭 문서마다 연결을 열면 호스트당 6개 제한에 닿는다. 창(dialog)이 떠 있으면 토스트가 가려지므로 안내는 `mtNote`(창 안 한 줄). 알림함 항목의 link `#maintain` 은 창을 여는 신호.
- 스킬 단계 키에 `maintain` 추가(`AgentSkill.STAGES`). 예전에 "전 단계"로 저장된 스킬은 네 단계가 명시돼 있어 플랫폼 관리 세션에는 실리지 않는다(대상 프로젝트용 지침이 섞이지 않게 — 의도된 동작).
- 이 세션(Claude Code)의 Bash 도구에서 **작은따옴표가 든 따옴표 친 heredoc(`<<'EOF'`)은 "unexpected EOF" 로 깨진다**(11-23 두 번 실측). 여러 파일을 스크립트로 고칠 때는 스크립트를 Write 도구로 파일에 쓴 뒤 실행.

## 알림함 (11-17)

- 방향: **메신저는 활용하되 의존하지 않는다.** 사용자가 알아야 할 일은 메신저 전송과 별개로 `NotificationService.add(kind, level, title, body, link)` 로 남긴다(실패해도 예외 없음). 현재 기록 지점: `IssueNotifier.sendSelf`(이슈 안내문 → `addIssue`, 첫 줄이 제목, 수준은 `issueLevel` 문구 규칙), `ResearchService.finish`(자기방 알림 여부와 무관), `AlertService`(시작·오류). 새 기능이 자기방으로만 알리게 만들지 말 것.
- **메신저 알림 유형별 끄기(1.9.0)**: 플랫폼이 스스로 자기방에 보내는 알림은 `MessengerService.NOTICES`(issue·research·system)의 유형을 달아 `notifySelf(kind, text)` 로 보내거나, 전송 전에 `notifyOn(kind)` 를 확인한다(꺼짐 목록은 `bot_setting.messenger_notify_off`, 기본 전부 켜짐, 메신저 탭 "메신저 알림" 카드 · `/api/messenger-notify`). **새 자기방 알림을 `sendToSelf` 로 직접 보내지 말 것** — 사용자가 끌 수 없게 된다. 사용자가 메신저에서 친 명령(`이슈 …`·`질문 …`)의 답은 알림이 아니므로 `sendToSelf` 그대로(항상 전송). 꺼도 내용은 남아야 한다: 이슈·리서치·시스템은 알림함. 예전 설치에 저장된 `summary`·`mention` 꺼짐 값은 읽을 때 무시한다.
- 서버는 읽음 상태를 모른다: 최근 60건을 메모리 캐시로 돌려주고(추가 때만 다시 읽음, 화면이 1분마다 호출) 읽음·비우기 시각은 브라우저 localStorage `da-notices`(`seen`/`cleared`/토스트 `items`)가 기억한다. `UI.toast()` 는 자동으로 기록되므로 화면 코드는 할 일이 없다.

## 이슈 에이전트 사용법

- 자동: 대상 프로젝트의 열린 이슈 중 라벨 `ai-fix`(스캔 2분 주기, `이슈 스캔`/대시보드 `스캔` 은 즉시). 대상 프로젝트는 관리 페이지 "대상 프로젝트" 칩이나 `이슈 프로젝트 추가|삭제 group/repo` 로 바꾼다(`SettingService.getGitLabProjects`, DB `bot_setting.gitlab_projects` 가 `GITLAB_PROJECTS` 보다 우선, 재시작 불필요). 수동: 자기방에 `이슈 group/repo#12` 또는 이슈 URL.
- 동시 처리 `gitlab.concurrency`(기본 2): 워커 풀 + 세션별 직렬 체인(`submit(sessionId, …)`), `findActiveAll/findWaiting`. 대기 세션 2건 이상이면 자기방 답변 앞에 `shop#1`/`#7` 대상 필수(`splitSessionRef`). 에이전트 실행 상한은 단계별(`CodingAgent.run` 의 stage): 분석 60분/80턴, **개발 4시간/600턴**(`gitlab.agent.fix-timeout-ms`), 리뷰어 60분(`gitlab.verify.reviewer-timeout-ms`), 무응답 45분(`idle-timeout-ms`, stream 출력이 없으면 중단 — 긴 gradlew build 가 20분을 넘겨 끊긴 실측). 초과 시 `AgentTimeoutException` — 개발 단계는 `timeoutToReview()` 가 변경을 보존한 채 REVIEW_WAIT 로 넘기고 「피드백: 이어서 진행」으로 같은 세션 재개. Bash 도구 상한은 단계 상한에 맞춰 최대 50분.
- 토큰 사용량: 모든 Claude 호출이 `llm/UsageService.record` → `claude_usage`(agent — 예전 행의 ops·chat 은 남아 있고 새로 생기지 않는다). `GET /api/usage`, 상단바 배터리(`ui.js renderBattery`). **실제 구독 사용률**은 `UsageService.planUsage()` 가 `~/.claude/.credentials.json` 의 OAuth accessToken 으로 `https://api.anthropic.com/api/oauth/usage`(헤더 `anthropic-beta: oauth-2025-04-20`)를 호출해 `five_hour/seven_day.utilization`(%)·`resets_at` 을 받는다(Claude Code `/usage` 와 같은 소스, 실측 OK, 60초 캐시). `isLimited()` 는 이 값(100%)을 우선 본다. 토큰은 절대 로그·응답에 싣지 않는다. **사용량 한도**는 CLI 가 텍스트 "You've hit your session limit · resets 2:20pm (Asia/Seoul)" 로 알리고 result 는 오류가 아닌 0턴 빈 결과(실측 11-12) → `ClaudeCliAgent` 가 stream TEXT 에서 감지(`UsageService.isLimitText`)해 `UsageLimitException(until)`. `IssueAgentService`: 분석→NEW 로 되돌림, 수정→`interruptedToReview`(변경 없으면 DESIGN_WAIT), 리뷰어→생략, `startNextIfIdle/prefetch` 는 `usage.isLimited()` 동안 보류, 자기방 알림 1회(`notifyLimit`).
- 참고 저장소: 이슈 본문 `## 참고 저장소` 절의 `group/repo`(+`@branch`/`**branch**`/`branch 브랜치`)를 `parseReferenceRepos` 가 뽑아 `GitRunner.prepareReference` 로 `work/<grp__repo>__ref/<branch>` detached worktree 를 만들고(`ensureReferences`, 분석·수정 시작 때), 시스템 프롬프트에 경로+읽기 전용을 안내한다(`referenceNote`). 매 실행 뒤 `guardReferences` 가 변경을 되돌리고 ERROR 이벤트. 다른 서비스 코드를 참고하는 마이그레이션 이슈용. git 은 모든 호출에 `-c core.longpaths=true`(scheduler 같은 깊은 경로 저장소가 Windows 260자 제한으로 checkout 실패하던 문제).
- 질의 워크플로우(`research/ResearchService`): `research_job`(루트+후속, `root_id`), 저장소는 `GitRunner.prepareReference` 의 detached worktree(기본 브랜치) 재사용, 도구는 `agent.analyze-tools`(+문서 curl·MCP), 실행은 `CodingAgent.run(…, Overrides(model, effort, timeout, maxTurns, source="research"))`. 이벤트는 `IssueEventBus` 공유(세션 id = `EVENT_BASE(10억)` + 질의 id, 대시보드 SSE 동일). 한도(`UsageLimitException`) 면 NEW 로 되돌려 60초 틱에서 재시작. 재기동 시 RUNNING 은 FAILED. 메신저 접수(`notify`)는 완료 시 자기방 `[질의]` 알림(이 접두어도 수집 제외). 설정 `research.*`(`ResearchProperties`). MyBatis `<script>` 안에서는 `<>` 대신 `!=`(XML 파싱 오류로 기동 실패 실측).
- 질의 브랜치 선택: `research_job.projects` 항목은 `group/repo` 또는 `group/repo@branch`(첫 `@` 기준, `ResearchService.splitEntry`). 접수 때 `normalizeProjects` 가 브랜치 존재를 확인하고 기본 브랜치는 표기를 생략한다. 같은 프로젝트의 다른 브랜치를 함께 넣을 수 있다(worktree 가 브랜치별 `work/<grp__repo>__ref/<branch>`). 화면은 `GET /api/research/branches?project=`(기본 브랜치 + 최근 갱신 순 100개)로 선택지를 채운다. 화면에서 프로젝트 항목을 표시할 때 `/` 로 자르면 브랜치의 `/` 때문에 깨지니 `shortEntry` 를 쓴다.
- 질의 2차 검증(`ResearchService.verifyAndRevise`, 기본 켜짐 `research.verify`, 질의마다 `verify` 파라미터): 독립 검증자(새 세션, 테스트 모델, `ResearchPrompts.verifierPrompt`)가 수치 재계산·누락/과잉·근거 표본 확인 후 `## 판정 PASS|FAIL`(`VerifyService.parseVerdict` 재사용). FAIL 이면 `## 문제` 를 작성 세션(`--resume`)에 돌려 1회 수정. 결과 `verify_state`(PASS/FIXED/FAIL/SKIPPED)·`verify_md`. 검증 실행이 실패해도 원 문서는 살린다.
- 질의 이슈 초안(1.4.0): `research_job.kind`(`doc` 기본 / `issue`, 항목마다 따로 — 후속 질문이 물려받지 않음). `issue` 면 `ResearchPrompts.issueDraftPrompt` 가 출력 틀만 이슈 본문(`# 제목` + 배경/현재 동작/요구 사항/수정 방향/확인 방법/참고)으로 바꾸고, 검증 지적 반영은 `reviseIssuePrompt`(본문에 검증 메모가 섞이지 않게). 화면 「이슈로 등록」은 첫 `# ` 줄을 제목으로 잘라 `/api/issues-create` 로 보낸다 — **자동 등록은 하지 않는다**(사람이 확인). 등록 여부는 서버가 모른다(localStorage `research.issued`). 루틴·메신저 접수는 `doc` 고정.
- 대시보드 「진행」+ 입력칸 글(1.2.0): `/api/issues-reply` 의 `note` → `handleReply(…, note)`. DESIGN_WAIT 는 `fix(s, null, note)`(수정 프롬프트에 추가 지시로 덧붙임), REVIEW_WAIT 는 **푸시하지 않고** `fix(s, note)`(반영 안 된 요청을 둔 채 올리지 않기 위해). 자기방 「진행」은 코멘트를 받지 않는다.
- 화면에서 이슈 등록(1.2.0): `POST /api/issues-create`(Origin 확인) → `IssueAgentService.createIssue` → `GitLabClient.createIssue`. 대상 프로젝트만 받고, `start=true` 면 스캔 라벨을 붙여 만든 뒤 `startManual` 로 바로 큐에 넣는다. 에이전트 화면 「가져오기」는 `/api/issues-command?cmd=스캔`. 왼쪽 위 명령 입력창은 없앴다(1.3.0) — 대신 「이슈 목록」(`GET /api/issues-list?project=` → `openIssueList`, 열린 이슈 50건 + 세션 상태, 창을 열 때만 조회)에서 골라 `issues-command?cmd=group/repo#n` 으로 시작·다시 시작한다. 닫힌 이슈 재실행은 자기방 `@AI 이슈 repo#n` 으로만.
- 질의 이미지 첨부(1.10.0): `research/ResearchAttachments` — 화면이 `POST /api/research/attach`(본문 = 파일 바이트, Origin 확인)로 올리면 작업 디렉터리 `research-attach/tmp/<uuid>.<확장자>` 에 받아 두고, 접수 때 `attachments`(임시 이름 쉼표 구분)를 받아 `research-attach/<질의 id>/<순번>.<확장자>` 로 옮긴다(`research_job.attachments`). 모델에는 **절대 경로를 프롬프트에 적어 Read 도구로 열게** 한다(`promptNote`, 실측 OK — 코딩 에이전트 포트에 이미지 입력을 따로 두지 않았다). 형식은 내용 앞머리로 판별(png/jpg/gif/webp, 10MB·6장), 이름은 서버가 정한다(올린 쪽 이름·경로 불신). 항목마다 따로라 후속 질문이 물려받지 않고, 루틴·메신저 접수에는 없다. 화면은 붙여넣기·끌어놓기·버튼(`attAdd`), 리서치 프로젝트 선택은 「프로젝트 추가」 드롭다운 + `번호 이름 | 브랜치 ▾ | ×` 칩(1.9.2).
- 사용자 정의 MCP 서버: `mcp_server` 테이블 + `McpConfigService.saveCustom/deleteCustom/rebuild()`(설정 파일·허용 도구 재생성, 재시작 불필요). 내장 운영 서버(opensearch·postgres·k3s-ops)는 없다(2.0.0 에서 제거 — 같은 이름으로 등록해도 된다). **비밀값 저장 금지** — 인증·토큰·키류 헤더는 `${ENV}` 자리표시자만 통과(`validate`), 값은 `Environment`(.env)에서 읽어 자식 env 로만 전달(`env()`). API `/api/mcp-servers`(GET/POST), `/api/mcp-servers/delete`.
- 플랫폼 스킬: `skill/SkillService`(`agent_skill` 테이블, `work/skills/<name>/SKILL.md` 동기화). `promptNote(stageKey)`(analyze|fix|verify|research)를 코딩 에이전트 구현체가 시스템 프롬프트에 덧붙인다(`ClaudeCliAgent.runOnce`; 새 `CodingAgent` 구현체도 같은 호출 필요). 본문 1,500자 이하는 인라인, 초과는 파일 참조, 인라인 합계 6,000자 상한(시스템 프롬프트가 명령행 인자라 Windows 32K 제한). API `/api/skills`(GET/POST), `/api/skills/delete`. Claude Code 네이티브 스킬은 쓰지 않는다(`--disable-slash-commands` + 구현체 종속). **기본 스킬(1.11.0)**: `skill/DefaultSkills`(regression-test · new-dependency)를 `SkillService.seedDefaults()` 가 설치당 한 번 심는다(`bot_setting.skills_seeded`, `insertIfAbsent` — upsert 인 `save()` 로 심으면 사용자가 고친 것을 덮어쓴다). 심은 뒤로는 사용자 것이라 지운 스킬을 되살리지 않고, 본문을 고쳐 배포해도 기존 설치에는 반영되지 않는다. 화면 「기본 스킬 다시 넣기」= `POST /api/skills/defaults`(없는 것만). 기본 스킬을 추가할 때의 기준(기본 프롬프트와 겹치지 않기 · 단계 명시 · 특정 도구 전제 금지 · 짧게)은 `DefaultSkills` 주석 — 화면 캡처(화면 검증 도구 없음)와 DB 변경 안전(분석 출력 틀과 중복)은 검토 뒤 뺐다.
- 확인 대기 중 자기방 답변은 **명령(진행/승인/중단/diff/상태)과 `피드백: …` 만** 인정하고 그 외(스티커·잡담)는 무시한다(`IssueAgentService.parseSelfReply`). 대시보드 입력은 접두어 없이 전부 피드백.
- `gitlab/` 구성(11-16 분해): `IssueAgentService`(상태 기계·큐·대화 처리, 1,700줄) + 협력 객체 `IssueTexts`(순수 함수: 답변 해석·작업 유형·커밋 제목·절 추출, static import), `IssueReferences`(참고 저장소 worktree·안내·변경 되돌림), `IssueProjects`(대상 프로젝트 목록·캐시), `IssueNotifier`(자기방 전송·한도 알림), `IssuePrompts`(시스템/분석/수정 프롬프트). 새 순수 함수는 `IssueTexts` 에, 상태 전이는 서비스에 둔다. 남은 덩어리는 검증(verify)·푸시(push) 흐름 — 상태 전이와 얽혀 있어 통합 테스트 없이 떼지 않았다.
- 흐름: NEW → ANALYZING → DESIGN_WAIT → FIXING → **VERIFYING** → REVIEW_WAIT → PUSHING → DONE. 선분석: NEW → PREPARING → READY(앞 건 종료 후 제시).
- 모델은 플랫폼 탭 "에이전트 설정"의 모델(기획=ANALYZE/개발=FIX/테스트=VERIFY 따로, `SettingService.getAgentModels`, DB 우선, `/api/agent-models`, 허용 fable/opus/sonnet/haiku)에서 바꾼다. `.env` 값은 초기값. 모델을 쓸 수 없으면(`is_error` + "issue with the selected model") `ClaudeCliAgent.run()` 이 `--resume` 으로 opus 재실행(같은 `--session-id` 재사용은 "already in use" 로 거부됨, 실측). 실행 중 모델은 `runInfo()` → overview `running` → 대시보드 아바타.
- 서브/팀 에이전트 토글(플랫폼 탭 "에이전트 설정" 카드, 둘 다 기본 허용): `agent_subagents` 끄면 `--disallowedTools Agent,Task`; `agent_teams` 끄면 자식 env `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=0` + `--disallowedTools SendMessage,TeamCreate,TeamDelete`. **env 0 만으로는 팀 도구가 안 사라진다**(사용자 settings.json 의 env 가 1 이라 우선, 11-13 실측) → 도구 금지가 실제 효과. scheduler#1 에서 토큰이 급증한 건 팀 기능이 켜진 채 Agent 15회·SendMessage 4회.
- VERIFYING(`gitlab.verify`): 봇이 직접 `node --check`/빌드(바뀐 JSP 는 검사 도구가 없어 SKIP)(pom → `mvn -o -q compile`, 3.5분)를 돌리고(`VerifyService`), 독립 리뷰어 Claude 세션(새 세션, 읽기 전용)이 PASS/FAIL. FAIL 이면 같은 수정 세션에 자동 재수정 최대 2회, 그래도 FAIL 이면 실패 내역과 함께 REVIEW_WAIT. 결과는 `verify_result` → 대시보드 검증 탭.
- 저장소는 프로젝트당 기준 clone(`work/<grp__repo>`) + 세션별 worktree(`work/<grp__repo>__wt/issue-<n>`). 종료 시 worktree 제거.
- 브랜치/커밋 컨벤션(`gitlab.convention`): `{feature|fix|refactor|doc|hotfix}/{일감번호}`, 일반은 develop 기준→develop MR, hotfix 는 main 기준(라벨 `hotfix`/`긴급` 또는 설계의 `## 작업 유형`). 커밋 첫 줄 `feat|fix|docs|…: 제목`. 푸시는 컨벤션 접두어 브랜치만, 기준 브랜치 금지, 강제 푸시 없음(있으면 `-ai`, 또 있으면 `-ai-r<MMddHHmm>`). 그룹방에는 절대 자동 발송하지 않는다.
- 수정 단계에서 Claude 가 쓸 수 있는 것: 파일 편집, `gradlew/mvn/npm test` 등 지정 명령, 사용자 정의 MCP 도구.

## 코드 작업 규칙

- 주석·메시지·문서는 한국어. 코드 식별자는 영어. 기존 파일의 스타일(2단 구분선 주석, record 기반 Properties, MyBatis 어노테이션 매퍼)을 따른다.
- 새 설정은 `config/*Properties`(record) + `application.yml` + `.env.example` + README 표를 함께 갱신한다. 상태 전이는 반드시 `IssueAgentService.transition()`(이벤트 기록) 을 통한다.
- DB 스키마 변경은 매퍼의 `ensureTable()`(멱등) 과 `db/schema.sql` 둘 다.
- 단위 테스트: `src/test/java` (JUnit 5, Spring 컨텍스트 없이 순수 단위). `gradlew test` 로 전부 통과시킨 뒤 jar 빌드.
- 프런트는 `static/ui.css`·`ui.js`(공통 디자인 시스템)를 쓰고, 수정 후 `node --check` 로 스크립트 문법을 확인한다.
- 탭 전환 셸(1.7.0, `ui.js` "탭 전환(셸)"): 다섯 화면은 별도 문서지만 탭 이동 때 새로 읽지 않는다 — 처음 연 화면이 바탕 문서, 나머지는 화면 전체를 덮는 `iframe.tabframe` 으로 한 번 띄운 뒤 보이기/숨기기만. 그래서 ① 화면 코드에서 탭 사이 이동은 `<a href="/x.html#…">`(자동 가로챔) 또는 `UI.nav(url)` 로 — `location.href` 로 옮기면 iframe 안에서만 이동한다. ② 주기 작업은 `UI.every` 또는 `UI.asleep()` 확인(가려진 탭은 쉰다, `document.hidden` 만 보면 안 됨). ③ 전체 새로고침은 `window.top.location.reload()`. ④ 한 창에 같은 `ui.js` 가 문서마다 따로 돈다(상단바도 문서마다 하나 — 테마는 `storage` 이벤트로 맞춘다). 화면 확인은 `~/AppData/Local/npm-cache/_npx/*/node_modules/playwright` + `ms-playwright/chromium-*/chrome.exe`(executablePath 지정) 헤드리스 스크립트로 가능(실측).
- 에이전트 화면의 문서·Diff(1.12.0, `agent.html` 의 `md()`·`loadDiff()`): 서버가 주는 글(설계안·변경 요약·검증 보고·git diff)은 그대로 두고 **화면에서만** 구조를 입힌다 — 절(`#`·`##`)마다 카드(`.msec`, 「확인 필요」·「위험」·「수정 요청」은 강조), 들여쓴 목록은 중첩, `[OK]/[SKIP]/[FAIL]`·`PASS/FAIL`·첫 줄 `✅/❌` 는 배지, 「커밋 메시지」 절은 줄바꿈 그대로, 검증 보고의 리뷰어 절은 「독립 리뷰」 카드 안 소제목(`vmdc`). Diff 는 파일별 `<details>` 이고 **줄은 펼칠 때 그린다**(`dfFill`, 전체 400줄 이하면 처음부터 펼침) — 71개 파일·2,485줄 diff 가 처음엔 파일 목록만 DOM 에 있다. 프롬프트의 출력 틀(절 제목)을 바꾸면 이 강조 규칙도 본다. 새 클래스 이름은 `ui.css` 와 겹치지 않게(`.sec` 는 이미 사이드바 제목용 — 겹쳐서 카드가 가로로 깨진 적 있음). 리서치(`research.html`)·플랫폼 관리(`ui.js mtMd`)의 마크다운 변환기는 별개다.
- 다시 그리기: 주기 갱신으로 그리는 목록·문서는 `UI.set(el, html)`(내용이 같으면 건너뜀), 마크다운은 `UI.memo(md)`. 세션·스레드 전환은 `UI.swapOut/swapIn`.
- 사이드바 폭 조절(1.5.0): `UI.splitter(key, 기본폭)` 이 `.body` 에 손잡이를 넣고 `--side-w` 를 바꾼다(localStorage `da-side-<key>`, 더블클릭 = 기본). 손잡이 위치(`ui.css .splitter`)는 `.body` 의 padding 22px·gap 18px 을 전제로 하니 그 값을 바꾸면 같이 고친다. 리서치 탭의 접기 상태는 `research.fold.*`.

## 이 환경에서 확인된 함정 (다시 겪지 말 것)

- 봇 메시지 접두어(`[이슈]`, `[질의]`, `[봇]` …)가 붙은 글은 어댑터가 "자기가 보낸 글"로 걸러야 한다(`MessengerPort.receiveSelf` 규약 — 코어는 접두어로 거르지 않는다). 새 접두어를 추가해도 코어는 바꿀 것이 없다.
- Claude Code `--allowedTools` Bash 패턴에 **URL(`http://…:8777`)을 넣으면 ':' 때문에 매칭이 깨져 거부**된다 → `Bash(curl -s -G:*)` 처럼 접두만.
- Windows 에서 Claude 의 Bash→curl **인자에 한글을 넣으면 서버가 깨진 바이트를 받는다** → 검색어는 heredoc 으로 stdin(`--data-urlencode "q@-"`). 응답의 한글은 정상.
- Claude Code WebFetch 는 localhost 를 거부하고 세션에 따라 도구 자체가 없을 수 있다 → 로컬 API 는 curl.
- `--mcp-config` 파일의 `${ENV}` 는 치환된다(토큰은 파일에 쓰지 말고 env 로). Windows 에서 npx 기반 MCP 는 `cmd /c npx …` 로 감싼다.
- 디버그 출력에서 `python -I` 로 한글을 print 하면 깨져 보인다 → 바이트로 확인하거나 파일로 저장해 UTF-8 로 읽는다.
- Git Bash `curl` 로 한글 폼을 보내면 깨진다(테스트 클라이언트 문제). API 검증은 Python urllib 로.
- Claude Code 의 Bash 도구에서 heredoc 으로 Python/sed 를 돌려 **Java 문자열 리터럴에 `\n`·`\\` 를 써 넣으면 이스케이프가 한 단계 벗겨져** 실제 개행/단일 백슬래시가 소스에 들어간다(컴파일 오류 "unclosed string literal"). Java/JS 소스의 문자열은 Edit/Write 도구로 넣을 것.
- 이 호스트에서 파일 stat 이 매우 느리다(`Files.walk`+`toFile().lastModified()` 가 4,700개에 17초). 트리 스냅샷은 `Files.walkFileTree` 의 `visitFile(path, attrs)` 속성을 써라(0.4초).
- **`@Component`/`@Service` 에 생성자를 둘 두면(테스트용 포함) Spring 이 쓸 것에 `@Autowired` 를 붙여야 한다.** 없으면 기본 생성자를 찾다가 `No default constructor found` 로 **기동이 실패**하고 감시 루프가 계속 재시작만 한다(11-23 실측, 6분 중단). 단위 테스트는 Spring 컨텍스트를 띄우지 않아 이런 배선 오류를 못 잡는다 — 빈을 추가·변경했으면 재시작 직후 `/api/system/status` 가 뜨는지 반드시 확인하고, 안 뜨면 `logs/dev-agent.log` 끝의 `Caused by` 를 본다.
- Bash heredoc 안의 Python 으로 Java 소스에 `\n` 을 써 넣는 것도 같은 함정이다(따옴표 친 heredoc·이중 백슬래시여도 실제 개행이 들어감, 11-16 두 번 실측). 문자열 리터럴이 든 줄은 반드시 Edit/Write.
- 경량 운용 설정(11-16): JVM 옵션(SerialGC·Xmx512m·C1 — 실행 방식이 준다), Hikari 5, Tomcat 스레드 16, `spring.task.scheduling.pool.size=3`(단일 스케줄 스레드가 LLM 호출 동안 답변 폴링을 막던 문제). 실측 기동 27초→8초, 메모리(private) 311MB→149MB, 스레드 63→42.
- **시스템 프롬프트를 명령행 인자(`--append-system-prompt`)로 넘기지 말 것.** 본문에 큰따옴표가 있으면 Java ProcessBuilder 가 이스케이프하지 않아 Windows 인자 파싱이 깨지고, 그 뒤에 붙인 내용(스킬·MCP 안내)이 시스템 프롬프트에서 떨어져 나간다(11-16 실측: 모델이 "스킬 블록 없음" 이라고 답함). `ClaudeCliProbe.writePromptFile` + `--append-system-prompt-file` 로 넘긴다(길이 제한도 없음). 프로세스 명령행을 검사할 때 줄 단위로 자르면 개행 뒤가 안 보여 오판한다.
- **에이전트 작업 디렉터리는 이 저장소 밖에 둔다**(`.env` 의 `GITLAB_WORK_DIR`). 저장소 안(`work/`)에 두면 Claude Code 가 상위 디렉터리의 이 CLAUDE.md 를 대상 저장소 세션에도 읽어 들여 토큰이 들고 무관한 규칙이 섞인다(11-16 실측 후 이동). 옮길 때는 봇을 내리고 → 디렉터리 이동 → 기준 clone 마다 `git worktree repair <worktree 경로…>` → `~/.claude/projects/` 의 세션 폴더 이름(경로를 `-` 로 바꾼 것)도 새 경로에 맞춰 바꾸면 기존 세션 `--resume` 이 유지된다. 아래 문서의 `work/…` 표기는 이 작업 디렉터리 기준 상대 경로다.
- 경량화 2차(11-16 점검): ① 앱 로그 기본 INFO(`LOG_LEVEL_APP`) — DEBUG 는 MyBatis SQL·파라미터(메시지 본문)가 로그의 88% 였다. ② `SettingService` 가 `bot_setting` 을 메모리에 캐시(쓰기 때 갱신, DB 를 직접 고치면 재시작) — 폴링마다 수백 번 조회하던 것. ③ `UsageService.summary()` 10초 캐시, 구독 사용률 3분 캐시 + 마지막 값을 `bot_setting.usage_plan_snapshot` 에 저장해 재시작 직후 429 여도 이전 값(`stale`)을 쓴다. 5시간 창이 리셋된 옛 값은 버린다. **구독 API 를 못 읽을 때의 추정치(토큰 합계÷가정 예산)는 실제와 크게 다르다** — 화면은 `~N% 추정` 으로 표시하고 경고색을 쓰지 않는다(추정 27% 일 때 실제는 99% 남아 있었다). ④ 화면 폴링은 `UI.every(fn, ms)`(탭이 가려지면 중지, 다시 보이면 즉시 1회). ⑤ 브라우저가 SSE 를 끊을 때의 IOException 은 `ClientAbortAdvice` 가 삼킨다(응답이 나가기 시작한 뒤의 것만). ⑥ 사용자 중단·사용량 한도는 연동 카드의 "실패" 로 세지 않는다(`ClaudeCliAgent.killed`).

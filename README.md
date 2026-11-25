# Dev Agent — 로컬 에이전트 운용 플랫폼

로컬 PC 에서 도는 에이전트 운용 플랫폼입니다. 라벨이 붙은 GitLab 이슈를 **분석 → 설계 확인 → 수정 → 검증 → 리뷰 확인 → 브랜치 푸시·MR** 까지 대화형으로 처리하고, 소스를 훑어 문서로 답하는 **질의**, 예약 실행(**루틴**)을 한 화면에서 다룹니다. 메신저를 연결하면(`MessengerPort` 어댑터) 자기 대화로 `이슈 …`·`질문 …` 명령을 보내고 알림·확인 답을 주고받을 수 있습니다. LLM 호출은 전부 **Claude Code CLI(headless)** 입니다(API 키 없이 구독 로그인).

> 모든 화면은 localhost 전용입니다. 메신저는 **사용자 자신의 대화 하나**(받기·보내기)만 다루며, 이슈 에이전트의 확인은 자기 대화/로컬 대시보드에서만 받습니다.

- **Stack**: Java 17 · Spring Boot 3.3.5 · MyBatis · PostgreSQL 16(내장 또는 외부) · Claude Code CLI
- **배포**: 서버는 jar + systemd([deploy/README.md](deploy/README.md)), 개발 PC 는 `java -jar` — [설치 & 실행](#설치--실행)
- **작업 규칙**: [CLAUDE.md](CLAUDE.md)

---

## 한눈에 보기

| 트리거 | 누가 | 동작 |
|---|---|---|
| **메신저 명령** | 내가 자기 대화로 보냄 | `이슈 …`(에이전트 명령) · `질문 …`(리서치 접수) · 확인 대기 중이면 진행·중단·피드백 답 |
| **GitLab 이슈 에이전트** | 라벨 `ai-fix` 이슈(2분 스캔) / `이슈 group/repo#12` | 분석 → **설계 확인** → 수정 → **검증(봇 검사 + 독립 리뷰, 실패 시 자동 재수정)** → **리뷰 확인** → 컨벤션 브랜치 푸시 → 이슈 코멘트·Draft MR |
| **리서치** | 대시보드 「리서치」 탭 / `질문 batch: …` | 대상 프로젝트 소스를 **읽기 전용**으로 훑어 마크다운 문서(요약·표·근거·미확인)로 답. 후속 질문은 같은 세션 재사용. 코드 수정·빌드 없음 |

**안전 설계**: 메신저 포트에는 방 목록 조회·다른 방 읽기·그룹 전송이 없습니다(자기 대화 받기·보내기만). 봇 생성물 접두어(`[이슈]`/`[질의]`/`[봇]`)가 붙은 글은 어댑터가 자기가 보낸 글로 걸러야 합니다.

---

## 화면

`http://localhost:8777/` 로 들어갑니다. 다섯 탭이 같은 디자인 시스템(`ui.css`/`ui.js`)을 쓰며 Alt+1~5 와 테마(Alt+T)로 오갑니다. 상단바 오른쪽 렌치 버튼(Alt+M)은 플랫폼 자체를 고치는 **플랫폼 관리** 채팅 창입니다(아래 「플랫폼 관리」). 아래 그림은 가상의 프로젝트(`acme/shop/…`) 데이터로 찍은 것이고, 메신저 칸의 `slack` 은 어댑터를 붙였을 때의 예시입니다(이 저장소에는 메신저 없음 어댑터만 들어 있습니다).

| 탭 | 주소 | 내용 |
|---|---|---|
| 홈 (Alt+1) | `/home.html` | 전체 현황: 진행 중·확인 대기 이슈, 리서치, 연동 상태, Claude 사용량, 주의 알림 |
| 에이전트 (Alt+2) | `/agent.html` | 이슈 세션 대시보드(스테퍼·활동 로그·설계안·검증·diff) |
| 리서치 (Alt+3) | `/research.html` | 소스 조사 리서치(프로젝트·브랜치 선택, 2차 검증, 이슈 초안), **루틴(예약 리서치)** |
| 메신저 (Alt+4) | `/messenger.html` | 메신저 알림 유형별 켜기/끄기 |
| 플랫폼 (Alt+5) | `/platform.html` | **시스템(설치 점검·재시작·구성 묶음)**, 연동 상태·테스트, 에이전트 모델·대상 프로젝트, 스킬, MCP 서버 |

탭을 옮길 때 화면을 새로 읽지 않습니다 — 한 번 연 탭은 뒤에 그대로 두고 보이기만 바꾸므로 선택한 세션·스레드와 실시간 로그가 유지되고, 가려진 탭은 주기 조회를 쉽니다. 에이전트·리서치 탭은 왼쪽 목록과 본문 사이를 끌어 폭을 조절할 수 있고(더블클릭 = 기본 폭), 선택 상자는 항목이 많으면 검색칸이 붙습니다.

상단바 오른쪽 배터리 옆의 `v1.12.0` 이 실행 중인 버전입니다(major.minor.patch — 호환이 깨지는 변경 / 기능 추가 / 수정). 플랫폼 탭은 시스템 · 에이전트 설정/스킬 · MCP 서버/연동 세 열로 배치됩니다.

**알림함** — 모든 화면 상단의 종 버튼(Alt+N)을 누르면 작은 패널에 알림이 시간순으로 쌓여 있습니다. 메신저와 무관한 플랫폼 자체 알림입니다(메신저가 없어도 동작).

- **화면 알림**: 저장·재적재·실패처럼 화면 구석에 잠깐 떴다 사라지는 안내를 그대로 모아 둡니다(브라우저에 최근 100건, 화면 간 공유).
- **플랫폼 알림**: 이슈 에이전트의 진행·확인 대기·완료·실패, 리서치 완료·실패, 시작·오류. 누르면 해당 화면으로 갑니다(서버에 최근 200건, `GET /api/notifications`, 테이블 `notification`).
- 새 알림 수가 종 버튼에 배지로 뜨고(실패가 있으면 빨강, 확인 대기·경고는 주황) 패널을 열면 읽음 처리됩니다. "비우기"는 이 브라우저에서만 지웁니다.

![알림함](docs/images/notice.png)

**홈** — 지금 무엇이 돌고 있고 무엇이 내 확인을 기다리는지, 연동과 사용량이 정상인지를 한 화면에서 봅니다. 확인 대기·연동 이상·사용량 한도는 맨 위 알림으로 뜨고, 진행 중인 이슈는 여기서 바로 진행·중단·피드백을 보낼 수 있습니다.

![홈](docs/images/home.png)

**플랫폼** — 메신저·LLM 연동의 상태와 연결 테스트, 에이전트 모델(기획/개발/테스트)·서브/팀 에이전트·대상 프로젝트, 스킬, MCP 서버를 관리합니다.

![플랫폼](docs/images/platform.png)

**메신저** — 플랫폼 알림(이슈 · 리서치 · 시스템)을 메신저로 보낼지 유형별로 켜고 끕니다.

**에이전트 대시보드 · 활동 로그** — 7단계 스테퍼(분석 → 설계 확인 → 코드 수정 → 검증 → 리뷰 확인 → 푸시·MR → 완료)와 Claude 가 지금 읽고·고치고·실행하는 것을 줄 단위로 흘려보내는 실시간 로그(SSE). 줄을 누르면 프롬프트·전체 출력이 펼쳐집니다.

![대시보드 활동 로그](docs/images/agent-activity.png)

**단계별 모델 · 아바타** — 스테퍼 아래에 그 단계를 실제로 돌린 모델(`fable·high` 등)이 표시되고, 실행 중이면 모델 꼬리표를 단 아바타가 현재 단계 위에서 뛰고 확인 대기면 그 단계 위에 서 있습니다. 아래는 코드 수정 단계가 도는 화면입니다(상단바에도 작업 중 표시).

![단계별 모델과 아바타](docs/images/agent-running.png)

**리서치 탭** — 이슈 없이 "소스를 훑어 정리해 달라"는 요청용. 왼쪽에서 질문·대상 프로젝트(복수, 브랜치 선택)·모델을 고르면 읽기 전용 worktree 에서 조사해 마크다운 문서(요약·표·근거·미확인)로 답하고, 결과 아래에서 같은 세션을 이어 후속 질문을 할 수 있습니다. 아래는 두 저장소를 함께 조사하는 질의가 도는 화면(활동 로그 탭)입니다.

![리서치 탭](docs/images/research-running.png)

**설계안 / 검증 / Diff 탭** — 분석 결과(작업 유형·원인·수정 설계·브랜치 계획), 봇 검사 결과와 독립 리뷰어의 PASS/FAIL 근거, 작업트리 또는 푸시된 커밋의 diff. 문서는 절마다 카드로 나뉘고(확인 필요·위험은 강조, 검사 결과와 판정은 배지), Diff 는 파일별로 접혀 있어 파일 목록(+추가 −삭제)을 먼저 보고 필요한 파일만 펼쳐 줄 번호와 함께 봅니다(이름으로 거르기 · 모두 펼치기/접기).

| 설계안 | 검증 | Diff |
|---|---|---|
| ![설계안](docs/images/agent-design.png) | ![검증](docs/images/agent-verify.png) | ![Diff](docs/images/agent-diff.png) |

---

## 아키텍처

```
┌──────────────── 로컬 PC(호스트) ─────────────────────────────┐
│ Dev Agent (Java, 상시 실행 — systemd 또는 java -jar) │
│   ├ MessengerPort ────▶ 메신저(선택)        자기 대화 수신·전송 │
│   ├ claude.exe -p (세션별 worktree) ─▶ Claude  이슈 분석/수정/리뷰│
│   │     └ --mcp-config: 사용자 정의 MCP                │
│   └ 관리 페이지 · 대시보드 :8777 (localhost)                      │
│ PostgreSQL (내장 또는 외부)                                      │
└───────────────────────────────────────────────────────────────┘
```

- **LLM 접근**: SDK 대신 **Claude Code CLI 를 headless 로** 호출합니다(구독 로그인 사용). 코딩은 저장소 worktree 에서 `claude -p --session-id/--resume`(stream-json 으로 도구 이벤트 수신). API 키를 쓰려면 포트(`CodingAgent`) 구현체만 추가해 갈아 끼웁니다(아래 "포트와 어댑터").
- **안정성**: 서버는 systemd 가 감시합니다(`Restart=on-failure` — 종료 코드 10 은 다시 뜨고 0 은 다시 뜨지 않음). 개발 PC 의 `java -jar` 는 감시자가 없어 화면의 「재시작」이 거부됩니다. 운영 알림(시작/오류)은 자기방으로.

### 포트와 어댑터 (메신저 · 코딩 에이전트 교체)

코어(이슈 에이전트·리서치·명령 라우터)는 **포트(인터페이스)** 만 알고, 실제 메신저와 코딩 에이전트(LLM)는 **어댑터(구현체)** 가 맡습니다. 어댑터는 `platform.*` 설정값에 해당하는 빈만 뜨므로, 새 구현체를 추가하고 값만 바꾸면 교체됩니다. 지금 활성화된 구현체와 연결 상태는 플랫폼 탭 "연동" 카드(요약은 홈)에서 봅니다.

| 포트 | 역할 | 현재 어댑터 | 설정 |
|---|---|---|---|
| `messenger/MessengerPort` | 자기 대화의 새 메시지 받기 · 자기 대화로 보내기 | `messenger/none/NoMessengerAdapter`(메신저 없음 — 기본) | `PLATFORM_MESSENGER=none` |
| `agent/CodingAgent` | 작업 디렉터리에서 도구를 쓰며 여러 턴 (이슈 분석·수정·검증, 질의) | `agent/claude/ClaudeCliAgent` | `PLATFORM_CODING_AGENT=claude-cli` |

- **전송은 자기 대화로만 갑니다**(`MessengerPort.sendSelf` — 방을 고르는 인자가 없습니다). 알림 유형별 켜고 끄기는 코어(`messenger/MessengerService`)가 적용합니다.
- **연동 카드(플랫폼 탭)**: 포트별 구현체 이름·상태(정상/주의/실패)·최근 호출 성공/실패를 보여 주고, 테스트 버튼으로 실제 연결을 확인합니다(메신저: 자기방 전송, 코딩 에이전트: CLI 실행). API 는 `GET /api/platform`, `POST /api/platform/test?port=messenger|agent`. 구현체 전환과 자격증명은 화면에서 다루지 않고 `.env` 로만 합니다.

![연동 카드](docs/images/admin-platform.png)

- **상태 보고 규약**: 모든 포트는 `health()`(즉시 응답, 네트워크·LLM 호출 없음)를 구현하고, 코딩 에이전트 포트는 토큰을 쓰지 않는 `selfTest()` 도 구현합니다. 최근 호출 기록은 `platform/CallStats` 로 남깁니다.
- **새 메신저(예: Slack · Discord · Telegram)**: `MessengerPort` 를 구현한 어댑터(예: `messenger/slack/SlackMessengerAdapter`)를 만들고 `@ConditionalOnProperty(prefix = "platform", name = "messenger", havingValue = "slack")` 를 붙인 뒤 `PLATFORM_MESSENGER=slack`. 구현할 메서드는 `provider` · `health` · `selfId` · `connect` · `reset` · `receiveSelf(cursor, timeout)`(자기 대화의 새 글을 시간순으로, 글마다 `cursorAfter` 포함, 차단형 허용) · `sendSelf(text)`(자기 대화로 전송) 입니다. 사용자·메시지 id 는 **어댑터가 정하는 문자열**을 그대로 씁니다(64자 이하, `:` 제외). 자기가 보낸 글과 허용하지 않은 대화의 글을 걸러 내는 것은 어댑터 책임입니다. 최소 구현 표면은 테스트의 `FakePort` 참고.
- **새 코딩 에이전트(예: Claude API)**: `CodingAgent` 를 구현하고 같은 방식으로 조건을 붙입니다. `CodingAgent` 구현체는 세션 이어가기(`resume`), 허용 도구 사상, 진행 이벤트(`Listener`), 상한 예외(`AgentTimeoutException`/`UsageLimitException`), 사용량 기록(`UsageService.record`)을 지켜야 합니다.

> ⚠️ Claude 구독을 자동화에 쓰는 것이 약관상 허용되는지는 사용하는 요금제 기준으로 직접 확인하세요.

---

## 메신저

- **명령 라우터**(`command/MessengerCommandRouter`): 자기 대화로 온 글을 첫 단어로 나눕니다 — `이슈 …`(이슈 에이전트), `질문 …`·`질의 …`(리서치 접수), 그 외는 확인 대기 답변 후보(대기 세션이 없으면 명령 안내 한 번). 글은 저장하지 않고 어댑터가 넘긴 것을 바로 처리합니다.
- **메신저 알림 끄기**: 플랫폼이 스스로 자기방에 보내는 알림은 메신저 탭 "메신저 알림" 카드에서 유형별(이슈 · 리서치 · 시스템)로 끌 수 있습니다. 꺼도 내용은 알림함에 남습니다. 메신저에서 직접 친 명령의 답은 알림이 아니므로 항상 갑니다.

---

## GitLab 이슈 수정 에이전트

대상 프로젝트의 **라벨(`ai-fix`) 붙은 열린 이슈**를 자동으로, 또는 자기방 `이슈 group/repo#12` 로 수동으로 집어 아래 흐름을 돕니다. 기본 비활성(`GITLAB_ENABLED=false`).

```
이슈 스캔(2분) ─▶ NEW ─▶ ANALYZING   fetch → origin/develop 기준 worktree → claude(읽기 전용) 원인·설계·작업 유형
                            │  [이슈] 설계안 + 브랜치 계획  👉 진행 / 중단 / 피드백: …
                            ▼
                      DESIGN_WAIT ──피드백──▶ 같은 세션(--resume) 재설계 → DESIGN_WAIT
                            │ 진행
                            ▼
                        FIXING      claude(편집 + 허용 명령 + 사용자 정의 MCP)
                            ▼
                       VERIFYING    봇 검사(node --check · mvn/gradle compile) + 독립 리뷰어 세션 PASS/FAIL
                            │        FAIL → 같은 세션에 자동 재수정(최대 2회) → 다시 검증
                            ▼
                      REVIEW_WAIT ──피드백──▶ 추가 수정 → 검증 → REVIEW_WAIT
                            │ 진행                 (diff 로 전체 diff)
                            ▼
                        PUSHING     {유형}/{일감번호} 브랜치 커밋·푸시 → 이슈 코멘트 + Draft MR(→ develop)
                            ▼
                         DONE       어느 단계든 「중단」→ ABORTED, 오류 → FAILED
```

확인 대기 중 **다음 이슈 1건을 미리 분석**(`NEW → PREPARING → READY`)해 두었다가 자리가 나면 바로 설계안을 제시합니다. 세션마다 `git worktree`(`work/<grp__repo>__wt/issue-<iid>`)를 쓰고 종료 시 제거합니다.

### 대화 방식

봇이 `[이슈] …` 로 자기방에 설계안/변경 요약을 보내고 기다립니다. 대기 중 자기방 답변은 **명령 또는 `피드백: …` 만** 인정하고 그 외(스티커·잡담)는 무시합니다. 대시보드 입력은 접두어 없이 전부 피드백입니다. 확인 대기 세션이 2건 이상이면 `shop#1 진행`, `#7 피드백: …`, `중단 board#1` 처럼 대상을 앞에 붙입니다.

| 답변 | 동작 |
|---|---|
| `진행` / `승인` / `ok` | 다음 단계로(설계→수정, 리뷰→푸시) |
| `중단` / `취소` | 실행 중인 Claude 프로세스 종료, 작업트리 정리, ABORTED |
| `diff` / `상태` | 전체 diff / 세션·큐 현황 |
| `검증` / `재검증` / `검증만 한번 더` | (리뷰 대기) 코드 수정 없이 봇 검사 + 독립 리뷰만 다시. 수정을 뜻하는 말이 섞이면 피드백으로 처리 |
| `피드백: …` (`fb:`) | 같은 Claude 세션에 이어서 설계/코드를 수정하고 다시 보고. `작업 유형: hotfix` 로 유형 변경 가능 |

**명령**(`이슈 …`): `상태` · `목록` · `스캔`(즉시) · `프로젝트 [추가|삭제 group/repo]` · `중단 [대상]` · `group/repo#12` 또는 이슈 URL · `도움말`. 관리 페이지와 대시보드에서도 같은 일을 합니다(`POST /api/issues-reply`, `/api/issues-command`).

**에이전트 화면에서 바로**: 「새 이슈」로 GitLab 이슈를 등록합니다(`POST /api/issues-create` — 대상 프로젝트만, "등록 후 바로 시작"이면 스캔 라벨을 붙이고 곧바로 큐에 넣음). 「이슈 목록」은 고른 프로젝트의 열린 이슈(최근 갱신 순 50건, `GET /api/issues-list?project=`)와 에이전트 상태를 보여 주고, 라벨이 없는 이슈를 「시작」하거나 실패·중단된 이슈를 「다시 시작」합니다(완료된 이슈는 「보기」만 — 미흡하면 새 이슈로. 창을 열 때만 조회). 「가져오기」는 2분 주기를 기다리지 않고 라벨 스캔을 즉시 돌립니다. 확인 대기 중 입력칸에 글을 쓴 채 「진행」을 누르면 코멘트가 함께 전달됩니다(`note` 파라미터) — 설계 확인에서는 설계대로 수정하되 추가 지시로 반영, 리뷰 확인에서는 푸시하지 않고 그 내용부터 수정·검증한 뒤 다시 확인을 받습니다.

![이슈 목록](docs/images/agent-issues.png)

### 검증 단계(VERIFYING)

- **봇 검사**(`VerifyService`): 바뀐 `.js` → `node --check`(바뀐 `.jsp` 는 검사 도구가 없어 SKIP 으로 보고하고 리뷰어가 코드로 확인), 빌드(`GITLAB_VERIFY_COMMANDS` 의 `group/repo=명령`, 없으면 pom → `mvn -o -q compile`, gradle → `gradlew compileJava`). 코드를 쓴 모델의 보고와 무관하게 돕니다.
- **독립 리뷰어**: 수정 세션과 분리된 새 Claude 세션(읽기 전용)이 이슈·설계·수정 보고·검사 결과·diff 를 보고 `## 판정 PASS|FAIL / ## 근거 / ## 수정 요청` 으로 답합니다. 모델/노력은 관리 페이지 "모델"에서 **기획(분석·설계) / 개발(수정) / 테스트(리뷰어)** 를 따로 고릅니다(fable·opus·sonnet·haiku × low·medium·high, DB 저장, 다음 실행부터 적용). 고른 모델을 쓸 수 없으면(권한 없음) 실행기가 **opus 로 대체**해 같은 세션을 `--resume` 으로 이어가고 활동 로그에 경고를 남깁니다. 대시보드 스테퍼에는 단계별로 실제 쓴 모델이 표시되고, 실행 중이면 모델명 꼬리표를 단 아바타가 현재 단계 위에서 통통 튀다가 단계가 바뀌면 그 자리로 뛰어갑니다(확인 대기 중엔 그 단계 위에 조용히 서 있음).
- FAIL 이면 보고 전체를 피드백으로 **같은 수정 세션에 자동 재수정**(최대 `GITLAB_VERIFY_MAX_RETRIES`=2회). 그래도 FAIL 이면 실패 내역과 함께 리뷰 대기로 올려 사람이 판단합니다. 결과는 대시보드 "검증" 탭과 리뷰 확인 메시지에 붙습니다.

### 브랜치 · 커밋 컨벤션

아래 브랜치 규칙을 따릅니다(`gitlab.convention`).

- 브랜치 `{feature|fix|refactor|doc|hotfix}/{일감번호}` — 유형은 분석 결과의 `## 작업 유형` 과 라벨(`hotfix`/`긴급` → hotfix 확정), 일감번호는 이슈 제목의 `#123456`(`GITLAB_WORK_ID_PATTERN`, 없으면 `issue-<iid>`).
- 일반 작업은 `develop` 기준·MR → develop, hotfix 는 `main` 기준(MR 은 컨벤션대로 develop, 설명에 main 분기 표기). 설계 중 유형이 바뀌면 수정 시작 때 작업트리를 다시 받습니다.
- 커밋 첫 줄 `feat|fix|docs|style|refactor|test|chore: 제목`, MR 제목 `Draft: <커밋 제목>`. 같은 이름의 원격 브랜치가 있으면 `-ai`, 또 있으면 `-ai-r<MMddHHmm>`. 강제 푸시 없음. 커밋 작성자는 `GITLAB_GIT_USER_NAME/EMAIL`(기본 dev-agent).

### 동시 처리 · 실행 상한

- **동시 2건**(`GITLAB_CONCURRENCY`): 워커 풀 + 세션별 직렬 체인. 자리가 나면 READY → NEW 순으로 시작하고, 차 있으면 다음 1건을 선분석합니다.
- **단계별 상한**: 분석 60분/80턴(`GITLAB_AGENT_TIMEOUT_MS`), **개발 4시간/600턴**(`GITLAB_AGENT_FIX_TIMEOUT_MS`), 리뷰어 60분(`GITLAB_VERIFY_REVIEWER_TIMEOUT_MS`), 무응답 20분(`GITLAB_AGENT_IDLE_TIMEOUT_MS`: 도구 호출·출력이 전혀 없으면 중단). Bash 도구 상한은 단계 상한에 맞춰 최대 50분.
- **개발 상한 초과 시** 작업트리 변경을 보존한 채 리뷰 대기로 넘기고(검증 생략), 「피드백: 이어서 진행」으로 같은 세션을 재개합니다. 변경이 없으면 FAILED.
- **서브 에이전트 · 팀 에이전트**: 관리 페이지 "모델" 카드의 토글 두 개(둘 다 기본 허용). 서브 에이전트를 끄면 Claude 가 Agent/Task 도구를 못 쓰고, 팀 에이전트(Claude Code 실험 기능)를 끄면 팀 메시지 도구를 금지하고 자식 프로세스의 실험 플래그를 0 으로 덮습니다. 병렬로 토큰을 크게 쓰므로 사용량이 빠듯하면 팀부터 끄는 것을 권합니다.

### 토큰 사용량 · 사용량 한도

구독제라 비용 대신 **토큰**을 봅니다. 두 페이지 상단바 오른쪽의 **배터리 게이지**가 5시간 창의 남은 비율을 보여 주고(40% 이하 노랑, 15% 이하·한도 빨강), 마우스를 올리면 5시간·7일 사용률과 리셋 시각, 봇 호출 토큰(5시간/24시간)이 나옵니다. 값은 **Claude Code 가 로그인 때 저장한 OAuth 토큰(`~/.claude/.credentials.json`)으로 `/usage` 명령과 같은 구독 사용량 API 를 호출한 실제 사용률**이고(60초 캐시, 토큰은 메모리에서만 사용), API 를 못 부르면 봇 호출 토큰 집계 / 용량(한도에 걸린 시점의 사용량을 학습, 없으면 `USAGE_WINDOW_BUDGET`) 추정치로 폴백합니다. 모든 Claude 호출(이슈 에이전트 각 단계·질의·플랫폼 관리)은 `claude_usage` 테이블에 기록되고 `GET /api/usage` 로 집계(5h/24h/7d, 모델별·출처별·시간대별)를 봅니다.

**한도 도달 시 안전 처리**: CLI 는 한도에 걸리면 오류가 아니라 "You've hit your session limit · resets 2:20pm (Asia/Seoul)" 텍스트를 내보내고 빈 결과(0턴)로 끝납니다(실측). 봇은 그 문장을 감지해 리셋 시각을 읽고,
- 분석 중이던 세션은 큐(NEW)로 되돌려 리셋 뒤 자동으로 다시 분석하고(선분석도 동일), 리셋 전에는 새 세션을 시작하지 않습니다.
- 수정 중이던 세션은 작업트리 변경을 보존한 채 리뷰 대기로 넘기고, **리셋이 지나면 같은 세션을 자동으로 「이어서 진행」** 합니다(사람이 먼저 답하면 그쪽이 우선). 아직 변경이 없으면 설계 확인 대기로 되돌립니다.
- 검증 리뷰어가 걸리면 리뷰를 생략하고 봇 검사 결과만으로 리뷰 대기로 올린 뒤, 리셋 뒤 검증을 자동으로 다시 돌립니다.
- 자동 재개 목록은 메모리에만 있어서 그 사이 봇을 재시작하면 수동(「피드백: 이어서 진행」)으로 재개합니다.
- 자기방에 한 번 알리고, 대시보드·관리 페이지에 "한도 도달 — 리셋 hh:mm" 배너를 띄웁니다. 리셋 시각을 못 읽으면 30분 뒤 재시도합니다.

### 참고 저장소(다른 프로젝트 읽기 전용)

다른 서비스의 코드를 보고 옮기는 마이그레이션 이슈(예: batch → scheduler)용. 이슈 본문에 `## 참고 저장소` 절을 두고 `` `group/repo` **main** `` / `group/repo@develop` / `group/repo develop 브랜치` 처럼 적으면 봇이 그 저장소를 `work/<grp__repo>__ref/<branch>` 에 detached worktree 로 받아 두고(세션 간 공유) 시스템 프롬프트에 경로와 "읽기 전용" 을 안내합니다. 매 Claude 실행 뒤 그 worktree 에 변경이 생겼으면 되돌리고 대시보드에 경고를 남깁니다. 참고 저장소는 등록 프로젝트가 아니어도 되지만 봇 토큰으로 읽을 수 있어야 합니다. git 은 모든 호출에 `core.longpaths=true` 를 씁니다(깊은 경로 저장소의 Windows 260자 제한).

### 리서치 워크플로우 — 「리서치」 탭

이슈→개발이 아니라 **"Batch job 목록 정리해줘"** 처럼 소스를 훑어 확인·정리해 달라는 요청용입니다. 상단 탭 「리서치」(`research.html`, Alt+3) 또는 자기방 `질문 <프로젝트[@브랜치][,프로젝트…]>: <질문>`(대상 프로젝트가 1개면 접두어 생략 가능)으로 올립니다.

- **저장소 · 브랜치**: 대상 프로젝트(관리 페이지 "대상 프로젝트" 중 선택, 여러 개 가능)마다 **브랜치를 고를 수 있습니다**(기본은 기본 브랜치). 같은 프로젝트를 브랜치만 달리해 두 번 넣으면 브랜치 간 비교 질의가 됩니다. 메신저에서는 `질문 batch@develop: …`. 고른 브랜치를 `work/<grp__repo>__ref/<branch>` detached worktree(참고 저장소와 같은 방식)로 받아 **읽기 전용**으로 읽습니다. 이슈 세션의 worktree 와 겹치지 않고, 실행 뒤 변경이 생겼으면 되돌립니다.
- **세션**: 첫 질문은 새 Claude 세션(분석 도구 Read/Grep/Glob/git log + 사용자 정의 MCP 읽기), **후속 질문은 같은 세션을 `--resume`** 해 이미 읽은 코드 맥락을 재사용합니다(빠르고 토큰이 적음). 질의마다 모델/노력을 고를 수 있고(기본 '기획' 모델), 상한 30분/60턴(`RESEARCH_TIMEOUT_MS`/`RESEARCH_MAX_TURNS`).
- **이미지 첨부**: 질문(새 질문·후속 질문)에 화면 캡처 같은 이미지를 붙여넣기·끌어놓기·버튼으로 최대 6장(png/jpg/gif/webp, 장당 10MB) 붙일 수 있고, 모델이 파일을 직접 열어 봅니다. 스레드에 썸네일로 남고, 스레드가 끝난 뒤 7일(`RESEARCH_ATTACHMENT_KEEP_DAYS`, 0 = 지우지 않음)이 지나면 파일을 지웁니다. 루틴·메신저 접수에는 없습니다.
- **결과**: 고정 틀의 마크다운 문서(`# 제목` / `## 요약` / `## 내용`(표) / `## 근거`(파일 경로) / `## 미확인·주의`). 리서치 탭에서 표로 렌더링, 복사·`.md` 내려받기. 메신저로 접수한 질문은 완료 시 자기방에 요약 + 링크를 보냅니다.
- **실행**: 이슈 워커와 분리된 단일 워커가 순서대로 처리하고, Claude 사용량 한도 중에는 대기열에 두었다가 리셋 뒤 자동 시작합니다. 활동 로그는 이슈 대시보드와 같은 SSE/이벤트 저장소를 공유합니다(`issue_event.session_id = 10억 + 질의 id`). 데이터는 `research_job`.

### 루틴(예약 리서치)

같은 질문을 **정해 둔 요일·시각마다** 자동으로 돌립니다. 예) 매주 월요일 09:00 "지난주 바뀐 배치 잡 정리". 리서치 탭의 질문 칸 옆 **예약** 을 누르면 지금 폼의 질문·프로젝트(브랜치)·모델·2차 검증 설정 그대로 이름·시각·요일만 더해 저장합니다.

![루틴 예약](docs/images/research-routine.png)

- 1분마다 "실행할 때가 된" 루틴을 리서치 대기열에 접수할 뿐이고(`routine/RoutineService`), 실행·검증·알림은 리서치 워크플로우가 그대로 맡습니다. 결과는 질의 스레드에 `루틴` 표시로 쌓입니다.
- 그 시각에 PC 가 꺼져 있었다면 **같은 날 켜질 때 한 번** 따라잡고, 날이 바뀌면 건너뜁니다. 오후에 "09:00" 루틴을 새로 만들거나 고쳐도 그 자리에서 실행되지 않습니다.
- 목록에서 ▶ 지금 실행 · ⏸ 끄기 · ✕ 삭제 · ↗ 마지막 결과. 이름을 누르면 폼에 불러와 고칩니다. "완료 알림"을 켜면 자기방으로 `[질의]` 알림이 갑니다.
- API: `GET/POST /api/routines`, `POST /api/routines/run?id=`, `POST /api/routines/delete?id=`. 테이블 `routine`.

### 이슈 초안 (리서치 → GitLab 이슈)

이슈 본문을 직접 쓰는 대신 리서치에 맡깁니다. 리서치 폼에서 **이슈 초안** 을 켜고 보내면, 평소처럼 소스를 조사한 뒤 결과를 이슈 형식(`# 제목` / 배경 / 현재 동작 / 요구 사항 / 수정 방향 / 확인 방법 / 참고)으로 씁니다. 완료되면 결과에 **이슈로 등록** 버튼이 생기고, 누르면 프로젝트·제목·본문이 채워진 창에서 고친 뒤 등록합니다(`POST /api/issues-create`, "등록 후 바로 시작"이면 이슈 에이전트가 곧바로 분석).

- 이미 받은 일반 답변에는 **이슈 초안으로** 버튼이 있습니다 — 같은 세션에 이어서 초안만 다시 쓰므로 조사를 반복하지 않습니다(입력칸에 쓴 글은 초안에 반영할 요청으로 함께 전달).
- 등록은 항상 사람이 확인하고 누릅니다(자동 등록 없음). 등록한 이슈 주소는 브라우저에만 기억해 `등록됨 ↗` 으로 보여 줍니다.
- 결과 형식은 `research_job.kind`(`doc`/`issue`, 항목마다 따로). API 는 `POST /api/research` 의 `kind=issue`.

![이슈로 등록](docs/images/research-issue.png)

### 2차 검증(피드백 루프)

에이전트의 결과를 그대로 믿지 않도록 **작성자와 다른 독립 세션이 한 번 더 확인**합니다.

- **개발(이슈)**: 수정 뒤 봇 검사(빌드·정적검사)와 독립 리뷰어(새 세션, 테스트 모델)가 PASS/FAIL 을 내고, FAIL 이면 같은 수정 세션이 자동 재수정(최대 2회)합니다. 위 "검증 단계" 참고.
- **리서치**: 문서를 쓴 뒤 독립 검증자(새 세션, 테스트 모델, 읽기 전용)가 **수치를 다시 세고, 목록의 누락·과잉을 찾고, 근거 경로를 표본 확인**해 PASS/FAIL 을 냅니다. FAIL 이면 지적을 작성 세션에 돌려보내 1회 수정합니다(작성자는 지적을 다시 확인해 맞는 것만 반영). 결과 문서에 `검증 통과` / `검증 후 수정됨` / `검증 실패(미수정)` / `검증 생략` 배지가 붙고, 눌러서 검증자 보고서를 볼 수 있습니다. 질의마다 끌 수 있고 기본값은 `RESEARCH_VERIFY=true`(토큰이 더 듭니다).

![리서치 2차 검증 내역](docs/images/research-verify.png)

### 플랫폼 관리 — 상단바 렌치 버튼 (Alt+M)

플랫폼 자체에 문제가 있거나 고치고 싶은 것이 있을 때 터미널에서 Claude Code 를 켜지 않고 **화면 안에서 채팅으로** 맡깁니다. 상단바 오른쪽 렌치 버튼(Alt+M)을 누르면 채팅 창이 뜨고, 요청은 **이 플랫폼의 소스 저장소를 작업 디렉터리로 한 Claude 세션**이 처리합니다.

- **할 수 있는 일**: 버그 확인(소스 · `logs/dev-agent.log` · 로컬 API)과 수정, 기능 추가, `gradlew test bootJar`, git 커밋, 스킬 등록·수정, 문서 갱신. 저장소의 `CLAUDE.md` 를 그대로 읽으므로 터미널 세션과 같은 규칙으로 일합니다.
- **대화**: 대화마다 Claude 세션 하나이고 이어지는 요청은 `--resume` 으로 맥락을 유지합니다(위 선택 상자로 지난 대화 전환, 「새 대화」는 새 세션). 창을 닫아도 작업은 계속되고 끝나면 알림함에 남습니다. 진행 중에는 도구 호출이 실시간으로 보이고 「중단」할 수 있습니다.
- **푸시와 재시작은 세션이 하지 않습니다.** 세션은 플랫폼의 자식 프로세스라 재시작하면 함께 끊기고, 푸시는 사람이 커밋을 보고 올리도록 `git push` 를 막아 두었습니다. 창 아래 상태 줄(브랜치 · 변경 파일 · 올리지 않은 커밋)에 **「푸시」**(올릴 커밋이 있을 때)와 **「재시작 적용」**(지금 떠 있는 것보다 새 jar 가 빌드됐을 때) 버튼이 나타납니다. 재시작하면 화면이 다시 열리고 같은 대화로 돌아옵니다.
- **한 번에 한 요청**: worktree 를 따로 두지 않고 저장소에서 직접 고치기 때문입니다(실행 중인 jar 는 `run/` 의 사본이라 빌드해도 안전). 요청 처리 중에는 재시작이 거부됩니다.
- **켜지는 조건**: 실행 디렉터리가 소스 저장소일 때(소스에서 띄운 경우) 설정 없이 켜집니다. 실행 파일 패키지에는 소스가 없으므로 버튼이 보이지 않으며, 소스를 받아 둔 경로를 `MAINTAIN_REPO_DIR` 에 지정하면 켜집니다.
- **한계**: 플랫폼이 아예 뜨지 않는 장애는 이 창으로 고칠 수 없습니다(그때는 터미널). 대화는 `maintain_turn` 테이블, 활동 로그는 `issue_event`(세션 id = 20억 + 차례 id), 사용량은 `maintain` 으로 따로 집계됩니다. 스킬 카드의 적용 단계 「플랫폼 관리」를 고르면 이 세션에만 실리는 지침을 둘 수 있습니다.

### MCP 서버 · 스킬 관리 (플랫폼 탭)

- **MCP 서버**: 내장 서버는 없고(예전의 opensearch · postgres · k3s-ops 내장 연동은 2.0.0 에서 제거), 플랫폼 탭 MCP 카드에서 서버를 등록·수정·삭제합니다(http / sse / stdio). 저장하면 `work/mcp-servers.json` 을 다시 만들어 다음 실행부터 에이전트에 반영됩니다(재시작 불필요). **비밀값은 저장하지 않습니다** — 인증·토큰·키류 헤더는 `${ENV_NAME}` 자리표시자만 받고 실제 값은 `.env` 에 둡니다(없으면 화면에 "env 없음" 표시). 허용 도구를 비우면 서버의 모든 도구가 허용되므로, 변경 도구가 있는 서버는 조회 도구 이름만 적습니다.
- **스킬**: 팀 코딩 규칙·커밋 규칙·조사 문서 양식 같은 재사용 지침을 "스킬" 카드에서 등록·수정·삭제합니다(이름 · 언제 쓰는지 한 줄 · 적용 단계 · 본문). 해당 단계(분석·설계 / 코드 수정 / 검증 / 질의 / 플랫폼 관리)의 시스템 프롬프트에 실리며, 1,500자 이하는 본문 그대로, 넘으면 설명만 싣고 필요할 때 `work/skills/<이름>/SKILL.md` 를 읽게 합니다. Claude Code 의 네이티브 스킬 대신 플랫폼이 관리하므로 다른 LLM 구현체로 바꿔도 유지됩니다. 처음 기동할 때 **기본 스킬** 두 개가 들어 있습니다 — `regression-test`(버그 수정이면 설계에 재현 테스트를 넣고 추가·확인), `new-dependency`(라이브러리 추가·버전 변경은 설계의 「확인 필요」로 승인받기). 한 번 넣은 뒤로는 일반 스킬과 같아서 고치거나 끄거나 지워도 플랫폼이 되돌리지 않으며, 지운 것은 「기본 스킬 다시 넣기」로 다시 받을 수 있습니다(있는 스킬은 덮어쓰지 않음).

| 스킬 | MCP 서버 |
|---|---|
| ![스킬](docs/images/platform-skill.png) | ![MCP 서버](docs/images/platform-mcp.png) |

### 에이전트가 쓰는 도구

| 단계 | 허용 |
|---|---|
| 분석 / 리뷰어 | Read·Grep·Glob + `git log/show/blame`, 사용자 정의 MCP 읽기 도구 |
| 수정 | 위 + Edit/Write, 허용 명령 접두어만(`gradlew`, `mvn`, `npm test` …, `gitlab.agent.fix-tools`) |

git commit/push 는 Claude 가 아니라 봇이 합니다. PowerShell 도구는 권한 패턴을 우회하므로 금지(`--disallowedTools`).

### 안전장치

- 확인은 **자기방과 localhost 대시보드에서만**.
- 푸시는 컨벤션 접두어 브랜치에만(코드에서 강제), main/develop 직접 푸시·강제 푸시 불가. 토큰은 `.git/config` 에 남기지 않고 명령별 env(`http.extraheader`)로만 주입.
- 답변은 접수 시점의 대기 단계를 기억해 그 단계일 때만 적용(「진행」 두 번이 다음 단계 승인으로 번지지 않음). 대시보드 답변은 세션 id 동반.
- 「중단」은 Claude 와 자식 프로세스(gradle/node, 리뷰어 세션)까지 즉시 종료. 푸시 실패는 리뷰 대기로 되돌려 재시도. 확인 대기 4시간마다 리마인더.
- 봇 재기동 시 작업 중(ANALYZING/FIXING/VERIFYING/PUSHING) 세션은 FAILED(재시작 `이슈 group/repo#iid`), 확인 대기 세션은 유지되고 `--resume` 으로 이어집니다. 그래서 **재시작은 세션이 대기 상태일 때만**.

### 준비

GitLab PAT(scope `api`, `write_repository`) → `.env` 에 `GITLAB_ENABLED=true`, `GITLAB_URL`, `GITLAB_TOKEN`, `GITLAB_PROJECTS`(초기값) → 재시작 → 이슈에 `ai-fix` 라벨. 대상 프로젝트는 이후 관리 페이지 칩이나 `이슈 프로젝트 추가|삭제 group/repo` 로 바꾸며 DB 에 저장됩니다(재시작 불필요). 메신저 어댑터가 연결돼 있으면 자기 대화로 보낸 명령과 답변이 바로 전달됩니다. 저장소는 `work/`(gitignore)에 clone 됩니다.

---

## 설치 & 실행
## 설치 & 실행

실행 방식은 둘뿐입니다. 서버는 jar + systemd, 개발 PC 는 `java -jar` 입니다. 둘 다 같은 jar·같은 화면이고 내장 PostgreSQL 또는 외부 DB(`DB_URL`)를 씁니다.

### A. 서버 (jar + systemd)

설치 절차는 [deploy/README.md](deploy/README.md) 를 따릅니다(unit·환경 파일 예시·smoke 시험 포함). 화면은 `127.0.0.1:8777` 에만 바인딩합니다.

### B. 개발 PC (`java -jar`)

사전 요구: JDK 17, Node.js + Claude Code CLI(로그인 완료), Git. DB 는 Docker PostgreSQL 을 쓰거나 `DB_URL` 을 비워 내장 DB 를 씁니다.

```bash
cp .env.example .env            # 1) 값 채우기 (GitLab 토큰, DB …)
docker compose up -d postgres   # 2) DB (DB_URL 을 비우면 생략 — 내장 DB)
gradlew.bat test bootJar        # 3) 빌드 → build/libs/dev-agent.jar  (JAVA_HOME=JDK 17)
java -jar build/libs/dev-agent.jar   # 4) 실행 (작업 디렉터리 = 홈: .env · logs/ · work/ · 내장 DB 가 여기에 생김)
```

- `.env` 가 없으면 첫 기동이 틀을 만듭니다(메신저 없음 모드). 채운 뒤 다시 실행합니다.
- 감시자가 없으므로 화면의 「재시작」은 거부됩니다(종료 후 다시 실행). 종료는 `POST /api/system/shutdown` 또는 프로세스 종료.
- 같은 PC 에서 인스턴스를 둘 띄울 때는 `ADMIN_PORT` 와 작업 디렉터리를 따로 줍니다.
- 메신저는 선택 사항입니다. 기본은 메신저 없이 화면(에이전트·리서치·플랫폼)만 씁니다 — 확인·알림은 대시보드로. 연결하려면 `MessengerPort` 어댑터를 추가합니다.
- 접근 범위는 `127.0.0.1` 전용입니다. 계정·토큰은 `.env` 밖으로 나가지 않고 화면에서 다루지 않습니다.

![시스템 · 설치 점검](docs/images/platform-system.png)

화면 **플랫폼 > 시스템 > 설치 점검** 에서 빠진 것을 확인합니다. 구성(스킬·MCP·루틴)은 **구성 묶음 가져오기**로 한 번에 받습니다.

### 설치 점검 · 구성 묶음 · 재시작 (플랫폼 > 시스템)

- **설치 점검**(`GET /api/system/doctor`, `ops/Doctor`): 실행 환경 · DB · 코딩 에이전트(CLI) · Claude 로그인 · Git · 메신저 · GitLab(토큰 확인) · 작업 디렉터리 위치 · Node.js · Git Bash. 항목마다 정상/주의/필요와 **고치는 방법 한 줄**. 토큰을 쓰지 않고 60초 캐시합니다.
- **구성 묶음**(`GET /api/bundle/export`, `POST /api/bundle/import`): 스킬 · 사용자 정의 MCP 서버 · 루틴 · 에이전트 설정(모델·서브/팀) · 대상 프로젝트를 JSON 한 파일로 옮깁니다. **비밀값은 들어가지 않습니다**(MCP 인증 헤더는 저장 단계에서 `${ENV}` 자리표시자만 허용, 계정·토큰은 대상이 아님). 가져오기는 같은 이름을 덮어쓰고 지우지 않으며, 대상 프로젝트는 합칩니다.
- **재시작 · 종료**(`POST /api/system/restart|shutdown`): 감시자(systemd)가 다시 띄웁니다(감시자 없이 `java -jar` 로 뜬 경우 재시작은 거부). 작업 중인 세션이 있으면 거부하고(`force=true` 로 강행 — 그 세션은 실패 처리), 다른 사이트의 페이지가 브라우저를 통해 보낸 요청(Origin 불일치)은 거부합니다.
- **상태**(`GET /api/system/status`): 버전 · 실행 방식(systemd / direct) · DB 방식(embedded / external) · 작업 중 건수 · 아직 안 채운 설정. 홈 화면 맨 위 안내가 이것을 씁니다.

## 환경변수 (`.env`)

| 키 | 설명 |
|---|---|
| `MCP_ENABLED` / `MCP_AGENT_ENABLED` | 사용자 정의 MCP 기능 · 에이전트 실행에 MCP 도구 붙이기 |
| `GITLAB_ENABLED` / `GITLAB_URL` / `GITLAB_TOKEN` / `GITLAB_PROJECTS` / `GITLAB_LABEL` | 이슈 에이전트 · GitLab · PAT · 대상 프로젝트 초기값(DB 우선) · 라벨(ai-fix) |
| `GITLAB_SCAN_INTERVAL_MS` / `GITLAB_CONCURRENCY` / `GITLAB_PREFETCH` / `GITLAB_WAIT_REMIND_MS` | 스캔 2분 · 동시 2건 · 선분석 · 대기 리마인더 4h |
| `GITLAB_GIT_USER_NAME` / `GITLAB_GIT_USER_EMAIL` / `GITLAB_COMMENT_ON_ISSUE` / `GITLAB_CREATE_MR` | 커밋 작성자(MR·코멘트 서명 동일) · 이슈 코멘트 · Draft MR |
| `GITLAB_BASE_BRANCH` / `GITLAB_HOTFIX_BASE` / `GITLAB_MR_TARGET` / `GITLAB_BRANCH_PREFIXES` / `GITLAB_HOTFIX_LABELS` / `GITLAB_WORK_ID_PATTERN` | 브랜치 컨벤션(develop / main / develop / 접두어 / hotfix 라벨 / 일감번호 정규식) |
| `GITLAB_AGENT_MODEL` / `GITLAB_AGENT_EFFORT` / `GITLAB_AGENT_FIX_TOOLS` / `GITLAB_AGENT_TEST_HINT` | 코딩용 모델·노력 초기값(관리 페이지 값이 우선) · 수정 단계 허용 명령 · 검증 명령 안내 |
| `GITLAB_AGENT_TIMEOUT_MS` / `GITLAB_AGENT_MAX_TURNS` / `GITLAB_AGENT_FIX_TIMEOUT_MS` / `GITLAB_AGENT_FIX_MAX_TURNS` / `GITLAB_AGENT_IDLE_TIMEOUT_MS` | 분석 60분/80턴 · 개발 4시간/600턴 · 무응답 20분 |
| `GITLAB_VERIFY_ENABLED` / `GITLAB_VERIFY_MAX_RETRIES` / `GITLAB_VERIFY_REVIEWER` / `GITLAB_VERIFY_MODEL` / `GITLAB_VERIFY_EFFORT` / `GITLAB_VERIFY_COMMANDS` / `GITLAB_VERIFY_REVIEWER_TIMEOUT_MS` | 검증 단계 · 자동 재수정 2회 · 독립 리뷰어 · 리뷰어 모델/노력 · 프로젝트별 검사 명령 · 리뷰어 상한 60분 |
| `RESEARCH_ENABLED` / `RESEARCH_TIMEOUT_MS` / `RESEARCH_MAX_TURNS` / `RESEARCH_MODEL` / `RESEARCH_EFFORT` / `RESEARCH_VERIFY` / `RESEARCH_ATTACHMENT_KEEP_DAYS` | 질의 워크플로우 · 질문 1건 상한 30분/60턴 · 기본 모델/노력(비우면 기획 모델) · 2차 검증 기본값 · 첨부 이미지 보관 일수(기본 7) |
| `MAINTAIN_ENABLED` / `MAINTAIN_REPO_DIR` / `MAINTAIN_MODEL` / `MAINTAIN_EFFORT` / `MAINTAIN_TIMEOUT_MS` / `MAINTAIN_MAX_TURNS` / `MAINTAIN_TOOLS` | 플랫폼 관리(렌치 버튼 채팅 창) · 소스 저장소 경로(비우면 실행 디렉터리가 소스 저장소일 때만 켜짐) · 기본 모델/노력(비우면 '개발') · 요청 1건 상한 2시간/300턴 · 허용 도구(`git push` 는 넣어도 금지) |
| `PLATFORM_MESSENGER` / `PLATFORM_CODING_AGENT` | 포트 구현체 선택(기본 none / claude-cli) |
| `BOT_JAVA_OPTS`(환경변수) | 봇 JVM 옵션. 기본 `-XX:+UseSerialGC -Xms32m -Xmx512m -XX:TieredStopAtLevel=1`(로컬 경량 운용) |
| `GITLAB_WORK_DIR` | 에이전트 작업 디렉터리(clone·worktree·MCP 설정·스킬 파일). **이 저장소 밖 경로로 지정** — 안에 두면 에이전트 세션이 이 저장소의 CLAUDE.md 를 읽는다 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `ADMIN_PORT` | PostgreSQL(`DB_URL` 을 **비우면 내장 DB**, 포트는 `DB_EMBEDDED_PORT` 기본 54329) · 관리 페이지 포트(8777) |
| `ACCESS_ALLOWED_ORIGINS` | 상태를 바꾸는 요청(경로와 무관하게 `GET`·`HEAD`·`OPTIONS` 외 전부)에서 루프백 외에 허용할 `Origin`(쉼표 구분, 정확히 일치, 기본 빈 값 = 루프백만). `tailscale serve` 뒤에서 쓸 때 `https://<MagicDNS 이름>:8443`. `Origin` 이 없는 요청(curl)은 통과 |
| `CLAUDE_EXE_COMMAND` | Claude Code 실행 파일 경로. 비우면 자동 탐색(PATH → npm 전역 → `~/.local/bin` → Homebrew) |
| `DEV_AGENT_HOME` / `DEV_AGENT_JAVA_OPTS`(환경변수) | 실행 파일 모드의 홈 폴더(기본 `~/.dev-agent`) · 앱 JVM 옵션 |

전체 목록과 설명은 [`.env.example`](.env.example). 런타임 토글(알림 유형/대상 프로젝트/모델)은 DB `bot_setting` 이 우선합니다.

## 프로젝트 구조

```
src/main/java/io/github/lib0823/devagent/
  boot/       Bootstrap(.env 틀·메신저 없음 모드·내장 DB·초기 스키마) · EmbeddedDb
  routine/    Routine · RoutineMapper · RoutineService(예약 질의: 1분 틱 → 질의 접수)
  config/     Gw/Bot/Claude/GitLab/Mcp Properties(record)
  messenger/  MessengerPort(포트: 자기 대화 받기·보내기) · MessengerService(알림 유형 켜고 끄기) · IncomingMessage/SelfInbox/SelfMessage · none/(메신저 없음 어댑터)
  command/    MessengerCommandRouter(`이슈 …`·`질문 …` 분기) · MessengerReceiver(자기 대화 수신 루프)
  llm/        UsageService · ClaudeUsageMapper(토큰 사용량 기록)
  agent/      CodingAgent(포트) · claude/ClaudeCliAgent(claude -p, stream-json, 단계별 상한)
  mcp/        McpConfigService(mcp-servers.json·허용 도구·사용자 정의 서버) · McpServer(Mapper)
  gitlab/     IssueAgentService(상태기계·대화·동시 처리) · IssueTexts(순수 함수) · IssueReferences(참고 저장소) · IssueProjects(대상 프로젝트)
              · IssueNotifier(자기방 알림) · IssuePrompts(프롬프트)
              · VerifyService(봇 검사·리뷰어 프롬프트) · GitRunner(worktree·참고 저장소·컨벤션 브랜치·푸시 가드) · GitLabClient
              · IssueSession/IssueEvent(Mapper) · IssueEventBus(DB 기록 + SSE)
  research/   ResearchService(질의 워크플로우: 읽기 전용 worktree · 세션 --resume · 단일 워커) · ResearchJob(Mapper)
  maintain/   MaintainService(플랫폼 관리: 소스 저장소에서 도는 세션 · 대화 --resume · 저장소 상태 · 푸시) · MaintainTurn(Mapper) · MaintainPrompts
  skill/      SkillService(플랫폼 스킬 등록·수정·삭제, 단계별 프롬프트 주입) · AgentSkill(Mapper)
  platform/   PortHealth(포트 상태) · CallStats(최근 호출 성공/실패 기록)
  admin/      AdminController · AgentMonitorController(대시보드 API/SSE) · ResearchController(질의 API) · MaintainController(플랫폼 관리 API) · PlatformController(활성 어댑터) · McpController · SettingService
src/main/resources/   application.yml · static/home.html(홈) · agent.html(에이전트) · research.html(리서치) · messenger.html(메신저) · platform.html(플랫폼) · admin.js·admin.css(홈·메신저·플랫폼 공통) · ui.css·ui.js
db/schema.sql · docker-compose.yml · deploy/(systemd unit · 서버 설치 절차 · smoke)
docs/                 images/
```

## 유지보수 · 보안

- **중지/재시작**: 서버는 `systemctl`, 개발 PC 는 프로세스 종료 후 다시 실행. 플랫폼 > 시스템 > 재시작은 systemd 로 뜬 경우만 동작합니다. 재시작은 이슈 세션이 대기 상태일 때만(`GET /api/issues`).
- **로그**: `logs/dev-agent.log`(10MB 롤링, 7일). 점검 API: `/api/agent/overview`.
- `.env`(토큰·비밀번호), `work/`, `tools/`, `build/`, `logs/`, `pgdata/` 는 커밋 제외. 토큰은 코드·설정 파일·메모리에 적지 않습니다.

## 라이선스

[MIT](LICENSE)

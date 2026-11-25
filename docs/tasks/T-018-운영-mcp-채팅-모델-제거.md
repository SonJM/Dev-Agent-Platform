# T-018. 사내 전용 기능 제거 ② — 내장 운영 MCP·`운영` 질의·채팅 모델 포트

```
id: T-018
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-007]
병렬: 불가
충돌: [T-007, T-009]   # admin/AdminController·PlatformController·McpController, agent/claude/ClaudeCliAgent, mcp/McpConfigService, application.yml, README.md, CLAUDE.md
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
작성: 2026-11-25
```

T-007을 2026-11-25 착수 전 재확인에서 나눈 뒷부분이다. T-007이 요약·멘션·RAG를 지운 뒤라 채팅 모델 포트의 사용처를 다시 확인할 수 있다.

## 목적
내장 운영 MCP 3종(opensearch·postgres·k3s-ops)과 `운영` 질의, 사용처가 없어진 채팅 모델 포트가 저장소에 남아 있지 않고, 사용자 정의 MCP 서버는 그대로 에이전트 실행에 붙는다.

## 근거
- 요구사항: `FR-208` ([01 §4.2](../01-requirements.md)), `NFR-401`, 가정 `AS-07`(사용자 정의 MCP 유지)
- 설계: [02 §4.2 D-11](../02-architecture.md)(제거 대상과 남기는 것 목록), [02 §1](../02-architecture.md)(사용처가 없어진 포트는 지운다)
- 결정: `OI-03` ([00-INDEX](../00-INDEX.md))
- 명세: [05 §2.2](../05-api-specification.md) — 이 작업의 행: `/api/llm-test`·`/api/mcp-ask`·`/api/mcp-status`·`/api/mcp-servers`(유지, 예약 이름)·`/api/platform`·`/api/usage`
- 테스트: `TC-014`(이 작업분), `TC-013`(T-018 시점), 회귀 `TC-006`~`TC-010`·`TC-023` ([07](../07-test-plan.md))

## 관련 코드 경로
(경로는 `src/main/java/io/github/lib0823/devagent/` 기준)
- 삭제: `mcp/OpsQueryService.java`, `llm/ChatModel.java`·`llm/ToolChatModel.java`, `llm/claude/ClaudeCliChatModel.java`·`ClaudeCliToolChat.java`·`ClaudeWarmSession.java`, 지우는 대상의 테스트(`mcp/McpRoutingTest` 등 — 착수 때 확인)
- 수정
  - `mcp/McpConfigService.java`: 내장 3종의 설정 생성·안내문·허용 도구·상태 점검·토큰 환경변수(`K3S_MCP_TOKEN`)·경고 로그 제거. `RESERVED`에서 `opensearch`·`postgres`·`k3s-ops` 제거(`playwright`는 T-009까지 남긴다). 안내문(`"운영 데이터 조회용 MCP 도구…"`)은 사용자 정의 서버에 맞는 일반 문구로. **사용자 정의 서버만 있을 때도 `available()`·`agentEnabled()`·`configFor()`가 그 서버를 붙여야 한다**(지금은 내장 서버 설정 파일 기준이라 확인 필요)
  - `config/McpProperties.java`: `opensearchUrl`·`postgresUrl`·`k3sUrl`·`k3sToken`·`k3sReadTools`·`chat`(`mcp.chat.*`) 제거. `enabled`·`agentEnabled`는 사용자 정의 MCP를 켜고 끄는 데 쓰이면 남기고 근거를 적는다
  - `admin/McpController.java`: `/api/mcp-ask`·`/api/mcp-status` 제거(아래 범위 3). `/api/mcp-servers` 계열은 유지
  - `admin/AdminController.java`: `/api/llm-test`와 `ChatModel` 주입 제거
  - `admin/PlatformController.java`: 채팅 모델·도구 모델 항목, 응답의 `chatModel`·`toolChatModel` 필드, `POST /api/platform/test`의 `chat`·`tool` 분기 제거 → `ports`가 메신저·코딩 에이전트 2개(화면은 `ports`만 그린다 — `admin.js`·`home.html` 확인됨)
  - `agent/claude/ClaudeCliAgent.java`: 운영 MCP 안내·주석(`--mcp-config` 줄, k3s 토큰 env 주석), 클래스 주석의 `ChatModel` 비교 문장. 사용자 정의 MCP 적용은 유지
  - `config/ClaudeProperties.java`·`application.yml` `claude.*`: 채팅 전용 항목(`warm`·`warm-*`·`system-prompt` 등) 제거. 코딩 에이전트·`ClaudeCliProbe`·`Doctor`가 읽는 키(`cli-command`·`exe-command`, `warmCommand()`가 쓰는 값 등)는 **사용처를 검색해 남기고**, 남긴 키와 이유를 설명서에 표로. 이름에 `warm`이 남는 메서드는 뜻에 맞게 바꿔도 된다
  - `llm/claude/ClaudeCliProbe.java`: 코딩 에이전트가 쓰므로 유지. 패키지를 옮길지 정하고 근거를 적는다(PM 권고: `llm/claude/`에 이 파일만 남으면 `agent/claude/`로 — 호출처 확인 뒤)
  - 주석만: `platform/PortHealth.java`(포트 목록), `mcp/McpServer.java`(내장 서버 언급), `db/schema.sql`의 `mcp_server` 주석(내장 3종 언급)
  - T-007 리뷰가 넘긴 LOW(2026-11-25 PM 확인 — 일부는 위 삭제로 함께 사라진다): `PlatformController` 52·55행 채팅·도구 모델 행 문구(행째 삭제), `McpProperties`의 `chat.allowAutoRooms`(방 화이트리스트 — `chat`째 삭제)와 클래스 주석의 `@AI` 운영 질의 문장, `OpsQueryService.allowAutoRooms()`(클래스째 삭제), `application.yml`의 `platform.chat-model` 설명 주석(키째 삭제), `llm/ChatModel` javadoc "요약·멘션"(파일째 삭제), **`config/ClaudeProperties.java` 클래스 주석 "LLM(요약/응답) 호출 설정"**(남는 키에 맞는 문구로), **`agent/claude/ClaudeCliAgent.java` 시스템 프롬프트 파일 전달 주석(현재 375~376행)의 "문서 검색 안내의 curl 예시" 실측 사례**(T-007에서 문서 검색 안내가 사라졌으므로 "본문의 큰따옴표가 인자 파싱을 깬다"는 일반 이유로 — 파일 전달 동작은 그대로)
  - `application.yml`의 `platform.chat-model`·`mcp.opensearch-url`·`mcp.postgres-url`·`mcp.k3s-*`·`mcp.chat.*`, `.env.example`, `src/main/resources/dist/env.template`, `README.md`, `CLAUDE.md`

## 범위
1. **호출처 재검색 먼저**: `ChatModel`·`ToolChatModel` 호출처가 `OpsQueryService`·`AdminController`(`/api/llm-test`)·`PlatformController`(연동 카드)뿐인지 확인한다(T-007 뒤 기준). 다른 기능상 사용처가 나오면 **지우지 말고 멈춰 보고**한다.
2. 위 삭제·수정. 설정 키 `platform.chat-model`·`mcp.opensearch-url`·`mcp.postgres-url`·`mcp.k3s-*`·`mcp.chat.*`·`claude.*` 채팅 항목 제거.
3. **`/api/mcp-status` 삭제**: 2026-11-25 PM 확인 — 화면·`e2e/`·`deploy/`에 호출처 없음(T-008 뒤). 착수 때 다시 검색해 0이면 지우고 설명서에 적는다(PM이 05 §2.2 반영). 호출처가 나오면 지우지 말고 보고.
4. **사용자 정의 MCP 회귀를 단위 테스트로 지킨다**: ① 예약 이름에서 세 이름이 빠져 `opensearch` 이름으로 등록할 수 있다 ② 내장 서버 없이 사용자 정의 서버 하나만 있을 때 에이전트 실행용 설정 파일에 그 서버가 들어가고 허용 도구에 그 서버 도구가 들어간다 ③ 비밀 헤더 검증(`validate`)은 그대로(기존 `McpServerValidationTest` 통과).
5. `llm/UsageService`·`ClaudeUsageMapper`·`claude_usage`는 유지(`/api/usage`의 출처 `ops`·`chat`은 기존 행만 남고 새로 생기지 않는다).
6. **문서**: `README.md`·`CLAUDE.md`에서 운영 MCP·`운영` 질의·채팅 모델(warm 세션)·`/api/llm-test` 설명을 지운다(예: CLAUDE.md "한눈에"의 운영 MCP 행, 포트-어댑터 구조 문단의 `llm/ChatModel`·`ToolChatModel`, "LLM 호출은 전부…"의 warm 채팅 문장, `platform.chat-model`). 프리뷰 설명은 남긴다(T-009). 다른 절은 건드리지 않는다.
7. **버전**: T-007의 2.0.0 그대로 둔다(Phase 2를 한 번에 서버에 반영한다 — 사이에 배포 없음).
8. **@AI 표기 마무리**: 이 작업이 끝나면 `src/main/java`에 `@AI`가 남지 않는다(PlatformController 행 문구, 지우는 클래스).

## 범위 밖
- 화면 파일(`src/main/resources/static`). `platform.html`의 "메신저와 LLM 은 포트 …" 안내 문장은 그대로 둔다(코딩 에이전트도 LLM)
- 사용자 정의 MCP 기능의 동작 변경, `playwright` 예약 이름과 프리뷰 연동(T-009)
- `e2e/` 파일 — `e2e/core/access-control.spec.ts`(TC-023)가 `GET /api/llm-test`의 응답 코드(405)를 확인한다. 이 API가 없어지면 기대값이 404로 바뀐다. **고치지 않고** 설명서에 "TC-023의 `llm-test` 확인 갱신 필요"로 적는다(qa 확인 때 qa가 고친다)
- Windows 요소·프리뷰(T-009), GitLab(T-010)

## 완료 조건
- [ ] 범위 1의 호출처 재검색 결과를 설명서에 적었다
- [ ] 검증 명령 2~5행 출력 없음, 제거한 API 3개(`/api/llm-test`·`/api/mcp-ask`·`/api/mcp-status`)가 404
- [ ] `gradlew test` 실패 0(범위 4의 새 단위 테스트 포함), `bootJar` 성공
- [ ] 04 §8 형태로 8778에 띄워 `/api/system/status` 200, `GET /api/platform`의 `ports`가 정확히 `messenger`·`agent` 2개, `GET /api/mcp-servers` 200. 확인 뒤 반드시 내리고 8778·54329 리슨 0
- [ ] 변경 설명서(`docs/tasks/results/T-018-운영-mcp-채팅-모델-제거.md`)가 그림·설명과 함께 있고 삭제 목록(파일·API·설정 키), 남긴 `claude.*` 키 표, `ClaudeCliProbe` 위치 결정, TC-023 보고가 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-018-운영-mcp-채팅-모델-제거.md](./results/T-018-운영-mcp-채팅-모델-제거.md)

## 검증 명령
```bash
./gradlew test bootJar
grep -rnE 'OpsQueryService|ToolChatModel|ChatModel|ClaudeWarmSession|ClaudeCliChatModel|ClaudeCliToolChat|opensearch|k3s|K3S_|mcp-ask|llm-test|mcp-status|chat-model|CHAT_MODEL|CLAUDE_WARM|CLAUDE_SYSTEM_PROMPT|McpRouting' src/main db | grep -vE 'PreviewProperties\.java|PREVIEW_OVERLAY_DIRS' ; test $? -eq 1
grep -rn '@AI' src/main/java ; test $? -eq 1
grep -rnE '요약[·/ ]*(멘션|응답)|allowAutoRooms|문서 검색 안내' src/main/java src/main/resources/application.yml ; test $? -eq 1
grep -rnE '/api/(mcp-status|mcp-ask|llm-test)' src/main/resources/static --include=*.js --include=*.html ; test $? -eq 1
# 이어서 04 §8 형태로 8778 기동 → /api/system/status 200, /api/platform ports 2개, /api/mcp-servers 200, 제거 API 3개 404 → stop-app.sh → 8778·54329 리슨 0
```
기대: 1행 성공, 2~5행 출력 없음(4행은 T-007 리뷰 LOW 문구 — 2026-11-25 추가). 2행에서 제외하는 두 패턴은 프리뷰 설정(`preview.overlay-dirs`의 `opensearch` 폴더 이름)으로 T-009가 지운다. `e2e/core/access-control.spec.ts`의 `llm-test`는 검색 대상이 아니다(범위 밖 — qa).

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-25 | 대기 | PM 작성 — T-007 착수 전 재확인에서 나눔(운영 MCP·`운영` 질의·채팅 모델 포트, 연동 카드 2행, `/api/llm-test`·`/api/mcp-ask`·`/api/mcp-status`). 코드 확인: 화면이 `/api/platform`의 `ports`만 그림, `McpConfigService.available()`이 내장 설정 파일 기준이라 사용자 정의 서버만 남을 때의 회귀 테스트를 범위에 넣음, `RESERVED`의 `playwright`는 T-009까지 유지 |
| 2026-11-25 | 대기 | PM 수정(T-007 검수): T-007 java-reviewer가 범위 밖으로 남긴 LOW를 대조 — `PlatformController` 행 문구·`OpsQueryService`·`ChatModel` javadoc·`platform.chat-model` 주석은 기존 삭제 범위에 포함, `McpProperties.allowAutoRooms`를 명시, 빠져 있던 `ClaudeProperties` 클래스 주석·`ClaudeCliAgent` 시스템 프롬프트 주석을 수정 목록에 추가. 검증 명령 4행(문구 grep) 추가, 완료 조건을 "2~5행"으로 |

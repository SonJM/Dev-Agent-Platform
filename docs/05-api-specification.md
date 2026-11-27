# 05. API 명세서

> Dev Agent — 개인 개발 서버용 에이전트 운용 플랫폼으로의 전환(2.0)
> 버전 0.10 · 2026-11-27 · 상태: Draft
> 선행 문서: [02. 아키텍처 설계서](./02-architecture.md), [03. 데이터 모델 설계서](./03-data-model.md)
> 관련 문서: [06. 기능 정의서](./06-functional-specification.md)
> 기계 판독 명세는 없다. **현행 API의 기준은 `admin/*Controller.java`** 이고, 이 문서는 공통 규칙과 전환으로 바뀌는 목록만 적는다(전수 명세는 범위 밖).

---

## 1. 공통 규칙

### 1.1 기본 경로와 형식
- Base path: `/api` (버전 접두어 없음 — 현행)
- 형식: JSON(UTF-8). 일부는 폼 파라미터·파일 바이트(`/api/research/attach`)·SSE(`/api/agent/sessions/{id}/events`).

### 1.2 인증과 접근 제어
- 로그인 없음.
- **신뢰 Origin 확인** (Phase 1, FR-104, T-002 구현 `config/AccessControlFilter`): `GET`·`HEAD`·`OPTIONS`가 아닌 **모든 요청(경로와 무관)** 에 `Origin` 헤더가 있으면 신뢰 목록과 정확히 일치해야 한다. 아니면 `403` + `{"ok":false,"result":"이 화면에서만 요청할 수 있습니다."}`. `Origin`이 없으면 통과(로컬 도구).
  - 경로로 거르지 않는 이유: 필터가 보는 원문 URI와 Spring MVC가 매칭하는 경로가 달라 `/api;x/…`·`/%61pi/…` 같은 표기가 `/api/**` 조건을 우회해 컨트롤러에 닿는다(T-002 보안 검토에서 실측). 그래서 `/api` 밖 경로(`/home.html` 등)의 `POST`도 같은 판정을 받는다.
  - 신뢰 목록: 루프백 `http://127.0.0.1:<server.port>`·`http://localhost:<server.port>`(포트는 요청이 아니라 설정값) + `ACCESS_ALLOWED_ORIGINS`(쉼표 구분, 앞뒤 공백 제거, 대소문자·끝 슬래시 정규화 없음 — OI-21).
  - 예전에는 `SystemController.foreign(req)`를 10곳에서만 불렀고 거부가 HTTP 200 + `ok:false`였다. T-002에서 `foreign()`을 지우고 필터로 대체했다(거부는 403).
  - `GET`으로 상태를 바꾸던 엔드포인트는 T-002 전수 조사(79개)에서 `/api/llm-test` 하나였고 `POST`로 바꿨다. 그 뒤 T-018에서 API째 삭제했다(§2.2, 2026-11-25 — 지금은 `GET`·`POST` 모두 404). 상태를 바꾸는 `GET`은 남아 있지 않다.
  - Host 헤더는 검사하지 않는다(02 §6). 재검토 중 — OI-21.
- Tailscale 신원 확인은 하지 않는다(FR-105 폐기, OI-05).
- 거부 응답 본문: `{"ok": false, "result": "<사유 한 줄>"}` (현행 형식).

### 1.3 권한
| 역할 | 할 수 있는 것 |
|---|---|
| 사용자(1명) | 전부 |

### 1.4 응답 envelope
현행 유지: 통일된 envelope가 없다(목록·객체를 그대로 돌려주거나 `{ok, result}`). 4단계까지 바꾸지 않는다. SPA 단계에서 통일할지는 OI-13.

### 1.5 오류 코드
| HTTP | 언제 |
|---|---|
| 400 | 입력 오류(현행) |
| 403 | 신뢰 Origin이 아님 |
| 404 | 없는 리소스, 제거된 API |
| 409 | 진행 중이라 받을 수 없음(플랫폼 관리 등, 현행) |
| 500 | 예상하지 못한 오류 |

### 1.6 페이지 처리
현행 유지(대부분 최근 N건 고정).

### 1.7 멱등성·동시성
현행 유지.

## 2. API 목록 — 전환으로 바뀌는 것

### 2.1 Phase 1
| 메서드 | 경로 | 변경 | 요구사항 |
|---|---|---|---|
| 전체 | `GET`·`HEAD`·`OPTIONS` 외 모든 요청(경로 무관) | 신뢰 Origin 확인 일괄 적용(§1.2) | FR-104 |
| POST | `/api/llm-test` | `GET`→`POST`(GET이 LLM 호출을 일으킴, T-002) | FR-104 |
| GET | `/api/system/status` | 실행 방식 값에 `systemd` 추가 | FR-102 |
| GET | `/api/system/doctor` | Linux 항목 | FR-106 |
| POST | `/api/system/restart`, `/api/system/shutdown` | systemd 아래에서 동작(종료 코드 10 / 0) | FR-102 |

### 2.2 Phase 2 (제거)
| 메서드 | 경로 | 처리 | 요구사항 |
|---|---|---|---|
| GET / POST | `/api/rooms`, `/api/rooms-config` | 삭제 — **완료**(T-007, 2026-11-25 404 확인) | FR-201 |
| GET | `/api/recent` | 삭제(요약·멘션 최근 활동) — **완료**(T-007) | FR-201 |
| GET / POST | `/api/docs-status`, `/api/docs-refresh`, `/api/docs-search`, `/api/docs-read` | 삭제(OI-02) — **완료**(T-007). `docs-read`는 에이전트의 문서 읽기용(화면 미사용) — 2026-11-25 코드 대조로 추가 | FR-202 |
| GET | `/api/preview/**`, `/api/agent/sessions/{id}/shots/**` | 삭제(OI-04) | FR-206 |
| GET / POST | `/api/messenger-notify` | 유지. 유형에서 `summary`·`mention` 제거 — **완료**(T-007: 유형 `issue`·`research`·`system`, 제거 유형 `POST`는 `ok=false`) | FR-205 |
| GET | `/api/status` | 삭제(T-007) — **완료**. 응답이 수집·요약·멘션 통계뿐이고 T-008 뒤 화면·`e2e/`·`deploy/` 호출처 0(2026-11-25 PM 확인, 착수 때 재확인) | FR-207 |
| POST | `/api/llm-test` | 삭제(채팅 모델 포트 제거, T-018) — **완료**(T-018, 2026-11-25 GET·POST 404 확인). T-002에서 GET→POST로 바뀌었다 | FR-208 |
| POST | `/api/mcp-ask` | 삭제(`운영` 질의 제거, T-018) — **완료**(T-018) | FR-208 |
| GET | `/api/mcp-status` | 삭제(T-018) — **완료**. 착수 때 재검색으로 호출처 0 확인 — 사용자 정의 MCP 목록은 `/api/mcp-servers`가 준다 | FR-208 |
| GET / POST | `/api/mcp-servers`, `/api/mcp-servers/delete` | 유지(가정 AS-07). 예약 이름에서 내장 3종 제외 — **완료**(T-018, `opensearch` 이름 등록 가능은 단위 테스트 `McpCustomOnlyTest`) | FR-208 |
| GET | `/api/platform`, POST `/api/platform/test` | 채팅 모델·도구 채팅 모델·지식 검색 항목 제거. 메신저·코딩 에이전트만 — **완료**(지식 검색 T-007, 채팅·도구 모델 T-018: `ports`가 `messenger`·`agent` 2개, 응답에 `chatModel`·`toolChatModel` 없음) | FR-208 |
| GET | `/api/usage` | 유지. 출처 구분에서 `ops`·`chat`은 더 생기지 않는다 | FR-605 |
| GET | `/api/bundle/export`, POST `/api/bundle/import` | 유지. 묶음에서 방 관련 항목이 있으면 제거 | FR-604 |

제거된 경로는 `404`가 된다(대체 경로·리다이렉트 없음).

### 2.3 Phase 3 (GitHub)
경로는 바꾸지 않는다(`/api/issues*`는 호스팅과 무관한 이름). 값의 의미만 바뀐다.

| 메서드 | 경로 | 변경 | 요구사항 |
|---|---|---|---|
| GET / POST | `/api/issues-projects`, `/add`, `/remove` | 항목 표기 `owner/repo`. 등록 때 저장소 존재·권한 확인. `/add`는 대상 상한 50, 조직 소유(`owner.type`≠`User`) 저장소 거부(OI-42 ①, T-028) | FR-301 |
| GET | `/api/issues-repos?refresh=` (T-028, 2.3.0) | 토큰 사용자 **소유** 저장소 목록(`GET /user/repos?affiliation=owner&sort=updated&per_page=100`, 최대 10쪽, 조직 소유 제외). 응답 `{ ok, reason?, repos:[{path, defaultBranch, private, archived, fork, updatedAt, webUrl, selected}], truncated, max }` — `selected`는 현재 대상이면 true(대소문자 무시, 매번 계산), `max`=50. 실패도 HTTP 200 + `ok:false` + 사람이 읽는 `reason`(토큰 없음 / 토큰 무효·만료 / 권한 부족·호출 한도 / 그 밖), 토큰·헤더 값은 싣지 않는다. 성공 결과 60초 캐시, `refresh=true`는 캐시 무시 — 단 마지막 GitHub 조회 시도 뒤 10초 안이면 다시 부르지 않는다(캐시가 없으면 `ok:false` "잠시 뒤 다시"). 화면이 창을 열 때만 부른다(status·Doctor·스케줄러에서 부르지 않음). 플랫폼 관리 세션 curl 금지 경로 | FR-301, FR-307 |
| POST | `/api/issues-projects/set` (T-028, 2.3.0) | form `path` 여러 개(0개 = 전부 해제, 100개 초과 = 처리 없이 거부). 보낸 묶음을 새 대상 목록으로 저장: 표기 검사(`owner/repo`) → **새로 들어오는 것만** GitHub 확인·정규화(존재·기본 브랜치·소유자 유형 `User`) → 실패는 `rejected:[{path, reason}]`(나머지는 저장), 빠진 기존 대상은 `removed`(진행 중 세션은 끝까지). 유효 항목이 상한 50을 넘으면 아무것도 저장하지 않는다. 응답 `{ ok, result, added, removed, rejected }` — `ok`는 요청을 처리했으면 `true`(일부 `rejected`가 있어도), 100개 초과·상한 50 초과로 아무것도 저장하지 않았으면 `false`(T-030, 2.3.0 묶음). 추가가 있으면 스캔 한 번. Origin 확인(다른 출처 403)·플랫폼 관리 세션 curl 금지(`api/issues-projects`) 적용. `rejected[].path`는 입력이 그대로 돌아오므로 화면은 이스케이프한다 | FR-301 |
| GET | `/api/issues-list?project=` | GitHub 열린 이슈(PR 제외) 50건 + 세션 상태. **2.4.0(T-032)부터 대상이 아닌 내 저장소도 허용**: `project`가 대상 프로젝트이거나 `/api/issues-repos`의 목록(토큰 사용자 소유, 60초 캐시를 그대로 씀 — 캐시가 없으면 이때 한 번 조회)에 있으면 조회, 둘 다 아니면 `ok:false` + 사람이 읽는 `result`(저장소 목록 조회 실패면 그 `reason`). 다른 사람·조직 저장소는 여전히 조회하지 않는다. 응답에 `target`(대상 여부)·`archived`(목록 기준, 모르면 false) 추가, 항목에 `blocked`(신뢰 작성자 확인에 걸려 처리하지 않을 이유 문자열, 처리 가능하면 없음) 추가. 화면이 창을 열거나 저장소를 고를 때만 부른다. 플랫폼 관리 세션 curl 금지 경로(2.4.0 추가 — 비공개 저장소 이슈를 읽게 되므로) | FR-306 |
| POST | `/api/issues-start` (T-032, 2.4.0) | form `project`(`owner/repo`), `number`(양의 정수). 에이전트 화면 「이슈 목록」의 시작·다시 시작. 순서: ① `project`가 대상이거나 내 저장소 목록에 있는지(`issues-list`와 같은 판정) ② 대상이 아니면 보관 저장소 거부 ③ 이슈 조회(없음·닫힘·PR 거부), 신뢰 작성자 확인, 진행 중 세션이면 거부 — **여기까지 실패하면 대상 목록을 바꾸지 않는다** ④ 대상이 아니면 대상에 추가(`/add`와 같은 검사: 존재·소유자 유형 `User`·상한 50) ⑤ 수동 시작(`issues-command`의 `owner/repo#n`과 같은 경로, 종료된 세션은 처음부터 다시). 스캔 라벨 이슈가 함께 큐에 들어가도 고른 이슈의 세션이 둘 생기지 않는다(④~⑤ 사이 주기 스캔이 먼저 만든 `NEW` 세션을 ⑤에서 만나면 「이미 큐에 있음」 `ok:true`). 종료 세션 재시작은 종료 상태일 때만 바꾸는 조건부 갱신이라 겹친 요청은 「이미 진행 중」으로 거부된다. 응답 `{ ok, result, added, ref }` — `ok`는 큐에 넣었으면 true, `added`는 이번 요청으로 대상에 추가됐으면 true(⑤가 실패해도 추가는 남고 `result`가 그렇게 알린다). Origin 확인(§1.2 일괄)·플랫폼 관리 세션 curl 금지 경로. `result`에 이슈 제목·작성자 등 외부 문자열이 들어갈 수 있으므로 화면은 이스케이프한다 | FR-301, FR-306 |
| POST | `/api/issues-create` | GitHub 이슈 생성. `start=true`면 스캔 라벨 부여 후 큐 등록 | FR-306 |
| POST | `/api/issues-command` | `cmd` 표기 `owner/repo#n` 또는 이슈 URL(`https://github.com/<owner>/<repo>/issues/<n>`) | FR-303 |
| GET | `/api/issues`, `/api/agent/**` | 응답 필드 `issueIid` → `issueNumber`, `mrUrl` → `prUrl`, `projectId`·`workId` 제거, 이벤트 `kind` 값 `GITLAB` → `REMOTE`(T-011 완료, 화면 수정 T-012와 함께 커밋). `/api/issues-list` 항목의 `iid` 키는 유지 | FR-305 |
| GET | `/api/research/branches?project=` | GitHub 브랜치(기본 브랜치 + 최근 갱신 순) | FR-306 |

### 2.4 Phase 4·5
- Phase 4: 새 API 없음. `/api/platform`의 메신저 항목이 `telegram`의 상태를 보여 주고 `POST /api/platform/test?port=messenger`가 시험 전송을 한다(현행 구조).
- Phase 5: SPA가 쓰는 API 정리는 5단계 기획에서(OI-13).

## 3. 상세 명세

바뀌는 API의 요청·응답 상세는 해당 작업의 변경 설명서를 PM이 검수한 뒤 이 절에 옮긴다. 지금은 비워 둔다.

---

## 변경 이력

| 일자 | 버전 | 내용 |
|---|---|---|
| 2026-11-24 | 0.1 | 최초 작성 |
| 2026-11-24 | 0.2 | 신원 확인 삭제(§1.2), §2.2에 운영 MCP·채팅 모델 관련 API 처리 확정 |
| 2026-11-24 | 0.3 | T-002 반영: §1.2 신뢰 Origin 확인이 경로와 무관하게 비안전 메서드 전부에 적용(보안 검토 C-1), 루프백 포트는 설정값, 거부 본문, `/api/llm-test` POST 전환(§2.1) |
| 2026-11-25 | 0.4 | T-007 착수 전 재확인: §2.2에 `/api/docs-read` 추가(코드 대조), `/api/status`·`/api/mcp-status`를 "유지·T-007에서 확인" → 삭제로 확정(T-008 뒤 호출처 0), T-007 분할에 맞춰 채팅 모델·운영 MCP 행 담당을 T-018로 |
| 2026-11-25 | 0.5 | T-007 완료(QA PASS): §2.2 이 작업 행(`/api/rooms`·`/api/rooms-config`·`/api/recent`·`/api/docs-*`·`/api/status` 삭제, `/api/messenger-notify` 유형 축소, `/api/platform` 지식 검색 항목 제거)에 완료 표시 |
| 2026-11-25 | 0.6 | T-018 완료(QA PASS): §1.2 `/api/llm-test` 문장을 삭제 완료로, §2.2 `/api/llm-test`·`/api/mcp-ask`·`/api/mcp-status` 삭제, `/api/mcp-servers` 예약 이름, `/api/platform` 채팅·도구 모델 제거에 완료 표시 |
| 2026-11-26 | 0.7 | T-011 검수 통과(커밋은 T-012와 묶음): §2.3 `/api/issues`·`/api/agent/**` 행에 `workId` 제거, 이벤트 `kind` `REMOTE`, `/api/issues-list`의 `iid` 유지를 더함 |
| 2026-11-27 | 0.8 | T-028 검수(재작업 반려와 함께 확정 형식 기록): §2.3에 `GET /api/issues-repos`·`POST /api/issues-projects/set` 추가, `/add`에 상한 50·조직 저장소 거부(R2). 새로고침 10초 간격(R4)·요청 100개 상한(R3)은 재작업 반영분 |
| 2026-11-27 | 0.9 | T-029 검수: `/api/issues-projects/set` 응답에 `ok` 추가(전체 거부를 화면이 성공으로 보이던 문제, typescript-reviewer MEDIUM) — 구현은 T-030 |
| 2026-11-27 | 0.10 | 에이전트 탭에서 내 저장소를 바로 고르기(사용자 요청, T-032·T-033, 2.4.0): `/api/issues-list`를 대상 + 내 소유 저장소로 넓힘(`target`·`archived`·`blocked` 추가), `POST /api/issues-start`(검사 뒤 대상 자동 추가 → 시작) 추가. 보안 판단: 같은 사용자 화면에서 토큰이 이미 목록으로 보여 주는 **내 소유 저장소**의 이슈를 읽는 것이라 허용(다른 출처 페이지는 CORS로 응답을 못 읽고, 상태 변경은 Origin 확인, 플랫폼 관리 세션은 두 경로 금지). 남는 위험은 `/api/issues-repos`와 같음(OI-21 Host 값) |
| 2026-11-27 | 0.11 | T-032 검수(반려, security-reviewer M1·L4 반영): `POST /api/issues-start`에 ⑤의 `NEW` 세션 처리(「이미 큐에 있음」 `ok:true`)와 종료 세션 재시작의 조건부 갱신을 명시 |

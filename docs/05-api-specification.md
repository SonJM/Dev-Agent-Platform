# 05. API 명세서

> Dev Agent — 개인 개발 서버용 에이전트 운용 플랫폼으로의 전환(2.0)
> 버전 0.1 · 2026-11-24 · 상태: Draft
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
- **신뢰 Origin 확인** (Phase 1, FR-104): `GET`·`HEAD`·`OPTIONS`가 아닌 `/api/**` 요청에 `Origin` 헤더가 있으면 신뢰 목록(루프백 + `ACCESS_ALLOWED_ORIGINS`)과 정확히 일치해야 한다. 아니면 `403`. `Origin`이 없으면 통과(로컬 도구).
  - 지금은 `SystemController.foreign(req)`를 10곳(이슈 등록, 구성 묶음 가져오기, 플랫폼 관리 3곳, 리서치 첨부, 스킬 기본값, 재시작, 종료)에서만 부르고 루프백 Origin만 허용한다. Phase 1에서 필터 하나로 모아 **전체 상태 변경 API에 적용**한다.
  - `GET`으로 상태를 바꾸는 엔드포인트가 있는지는 T-002에서 전수 조사해 `POST`로 바꾸거나 같은 확인을 적용한다.
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
| 전체 | 상태 변경 `/api/**` | 신뢰 Origin 확인 일괄 적용(§1.2) | FR-104 |
| GET | `/api/system/status` | 실행 방식 값에 `systemd` 추가 | FR-102 |
| GET | `/api/system/doctor` | Linux 항목 | FR-106 |
| POST | `/api/system/restart`, `/api/system/shutdown` | systemd 아래에서 동작(종료 코드 10 / 0) | FR-102 |

### 2.2 Phase 2 (제거)
| 메서드 | 경로 | 처리 | 요구사항 |
|---|---|---|---|
| GET / POST | `/api/rooms`, `/api/rooms-config` | 삭제 | FR-201 |
| GET | `/api/recent` | 삭제(요약·멘션 최근 활동) | FR-201 |
| GET / POST | `/api/docs-status`, `/api/docs-refresh`, `/api/docs-search` | 삭제(OI-02) | FR-202 |
| GET | `/api/preview/**`, `/api/agent/sessions/{id}/shots/**` | 삭제(OI-04) | FR-206 |
| GET / POST | `/api/messenger-notify` | 유지. 유형에서 `summary`·`mention` 제거 | FR-205 |
| GET | `/api/status` | 유지하되 수집·요약 항목 제거(화면이 쓰는 항목만 — T-007에서 확인) | FR-207 |
| GET | `/api/llm-test` | 삭제(채팅 모델 포트 제거) | FR-208 |
| POST | `/api/mcp-ask` | 삭제(`운영` 질의 제거) | FR-208 |
| GET | `/api/mcp-status` | 유지하되 내장 3종 항목 제거 — 사용자 정의 MCP 상태만(화면이 쓰는지 T-007에서 확인) | FR-208 |
| GET / POST | `/api/mcp-servers`, `/api/mcp-servers/delete` | 유지(가정 AS-07). 예약 이름에서 내장 3종 제외 | FR-208 |
| GET | `/api/platform`, POST `/api/platform/test` | 채팅 모델·도구 채팅 모델·지식 검색 항목 제거. 메신저·코딩 에이전트만 | FR-208 |
| GET | `/api/usage` | 유지. 출처 구분에서 `ops`·`chat`은 더 생기지 않는다 | FR-605 |
| GET | `/api/bundle/export`, POST `/api/bundle/import` | 유지. 묶음에서 방 관련 항목이 있으면 제거 | FR-604 |

제거된 경로는 `404`가 된다(대체 경로·리다이렉트 없음).

### 2.3 Phase 3 (GitHub)
경로는 바꾸지 않는다(`/api/issues*`는 호스팅과 무관한 이름). 값의 의미만 바뀐다.

| 메서드 | 경로 | 변경 | 요구사항 |
|---|---|---|---|
| GET / POST | `/api/issues-projects`, `/add`, `/remove` | 항목 표기 `owner/repo`. 등록 때 저장소 존재·권한 확인 | FR-301 |
| GET | `/api/issues-list?project=` | GitHub 열린 이슈(PR 제외) 50건 + 세션 상태 | FR-306 |
| POST | `/api/issues-create` | GitHub 이슈 생성. `start=true`면 스캔 라벨 부여 후 큐 등록 | FR-306 |
| POST | `/api/issues-command` | `cmd` 표기 `owner/repo#n` 또는 이슈 URL(`https://github.com/<owner>/<repo>/issues/<n>`) | FR-303 |
| GET | `/api/issues`, `/api/agent/**` | 응답 필드 `issueIid` → `issueNumber`, `mrUrl` → `prUrl`, `projectId` 제거(T-011, 화면 수정 T-012와 함께) | FR-305 |
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

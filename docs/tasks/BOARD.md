# 진행 보드

> Dev Agent (2.0 전환) · 최종 갱신 2026-11-25 (T-001~T-009·T-015~T-019·T-021~T-024 완료, 다음 T-010(PM 보완 뒤)·T-025, 서버 반영 뒤 T-020) · 상태는 PM만 바꾼다

상태값: **대기** (착수 전) · **진행** (담당 에이전트 작업 중) · **검수** (검수 요청됨, PM 확인 대기) · **반려** (재작업 필요) · **완료** · **보류** (외부 결정·환경 문제로 멈춤 — 사유 필수)

## 작업

| ID | 제목 | 담당 | 상태 | depends | 갱신일 | 비고 |
|---|---|---|---|---|---|---|
| [T-001](./T-001-linux-기동.md) | Linux 기동 지원 | backend-dev (backend-dev) | 완료 | - | 2026-11-24 | Phase 1. QA 확인: 아니오. 서버 smoke `SMOKE PASS`(메인 세션 실행) |
| [T-002](./T-002-접근-제어.md) | 접근 제어(신뢰 Origin) | backend-dev (backend-dev) | 완료 | T-001, T-004 | 2026-11-24 | Phase 1. QA PASS(6개). 1.13.0. 남은 지적은 OI-21·OI-22 |
| [T-003](./T-003-서버-실행-구성.md) | 서버 실행 구성(systemd·공유 DB SQL·절차서) | backend-dev (backend-dev) | 완료 | T-001, T-002 | 2026-11-24 | Phase 1. unit `systemd-analyze verify` 통과(메인 세션, 서버). DB 접속 경로는 T-017이 바꾼다. 서버 적용은 사용자(OI-24) |
| [T-004](./T-004-qa-구축.md) | QA 기반 구축(e2e·핵심 TC) | qa (qa) | 완료 | - | 2026-11-24 | Phase 1. 유형: 구축. @core 5개 QA PASS |
| [T-005](./T-005-전체-구성도.md) | 전체 구성도 | devops (devops) | 완료 | T-003 | 2026-11-24 | Phase 1. 유형: 그림. EVIDENCE PASS, 02 반영, HTML 스크린샷 확인 통과. "예정" 상자 표시와 DB 접속 경로 문구는 Phase 3 종료 때 갱신(OI-25) |
| [T-006](./T-006-메신저-수신-경로.md) | 메신저 수신 경로 신설 | backend-dev (backend-dev) | 완료 | T-002 | 2026-11-24 | Phase 2. java-reviewer 1회. 2차 재작업 뒤 완료(140건 통과). 격리 기동은 메인 세션 8779 대체 확인 인정, 뒤에 8778 실기동도 확인(지시서 증거 18:25). 코드 커밋 c27b82f |
| [T-007](./T-007-사내-기능-제거.md) | 사내 전용 기능 제거 ① 수집·요약·멘션·RAG·메신저 포트 축소 | backend-dev (backend-dev) | 완료 | T-006, T-008 | 2026-11-25 | Phase 2. QA 확인: 예. 2.0.0. **QA PASS 7/7**(TC-014 T-007분·TC-013 T-007 시점 + 핵심 6개). qa 묶음 제안은 TC-013 승격 지시서에(아래 메모). `MessengerPort` 최종 메서드·`command/` 이동을 02 §2.3·04 §2·T-013에 반영. 리뷰 LOW는 T-018에 대조·추가 |
| [T-008](./T-008-화면-정리.md) | 화면 정리 | frontend-dev (frontend-dev) | 완료 | T-002 | 2026-11-25 | Phase 2. QA PASS(TC-013 T-008 시점 + 핵심 6개, 7/7). 서버 응답으로 남은 행은 T-007·T-018·T-009 완료 조건으로. 390 상단바 넘침은 기존 문제(OI-26) |
| [T-009](./T-009-windows-요소-제거.md) | Windows 전용 요소·프리뷰 제거 | backend-dev (backend-dev) | 완료 | T-018, T-019 | 2026-11-25 | Phase 2. **QA PASS 7/7**(TC-013 T-009 시점 단언 `@core`에 추가). java-reviewer 1회 Block → 재작업, 테스트 127건. `server-smoke.sh` 미실행(`DEV_AGENT_SSH` 없음 — T-022에서 다시). 보고 사항 OI-29·30·31 → T-022·T-023 |
| [T-010](./T-010-github-연동.md) | GitHub 연동(기능 교체) | backend-dev (backend-dev) | 대기 | T-009, T-021, T-023, T-024 | 2026-11-25 | Phase 3. QA 확인: 예. 보안 리뷰. 선결 모두 충족(T-024 완료, OI-09·10·15 결정). **착수 전 PM 보완 1회**(OI-09·15 결정 반영 — 다음 호출) |
| [T-011](./T-011-이름-정리.md) | 이름 정리(`gitlab` 개명) | backend-dev (backend-dev) | 대기 | T-010 | 2026-11-24 | Phase 3. T-012와 한 묶음으로 반영 |
| [T-012](./T-012-화면-github-표기.md) | 화면의 GitHub 표기 | frontend-dev (frontend-dev) | 대기 | T-011, T-020, T-025 | 2026-11-25 | Phase 3. QA 확인: 예 |
| [T-013](./T-013-telegram-어댑터.md) | Telegram 어댑터 | backend-dev (backend-dev) | 대기 | T-007 | 2026-11-25 | Phase 4. 보안 리뷰. 왕복 확인은 사용자. T-007 확정 포트 메서드·자기 글 거름 반영 |
| [T-014](./T-014-spa-전환.md) | SPA 전환(묶음) | frontend-dev (frontend-dev) | 보류 | T-012, T-013 | 2026-11-24 | Phase 5. 기획 뒤 분할 |
| [T-015](./T-015-tc023-core-승격.md) | TC-023 핵심 묶음 승격 | qa (qa) | 완료 | T-002 | 2026-11-24 | 유형: 유지. @core 6개 QA PASS |
| [T-016](./T-016-start-app-중복-기동-거부.md) | start-app.sh 중복 기동 거부 | qa (qa) | 완료 | - | 2026-11-24 | 유형: 유지. 재실행 통과(재호출 rc=3, DB 보존). 04 §8 반영 |
| [T-017](./T-017-공유-db-접속-경로.md) | 공유 DB 접속 경로(포트 공개 없이 기동 때 조회) | backend-dev (backend-dev) | 완료 | T-003 | 2026-11-25 | Phase 1 보완(OI-18 결정 변경). QA 확인: 아니오. code-reviewer 1회(HIGH 반영: `ExecStartPre=+` root 조회로 구조 변경, 스크립트 `resolve-db.sh`). PM 검수 통과(`CHECK PASS` 재실행). systemd 동작 5항목은 메인 세션이 서버 시험 unit으로 확인(`RestartPreventExitStatus=2` 제거). 남은 서버 확인은 설치 때 README §8. 범위 밖 제안(자식 프로세스 환경의 DB 변수)은 OI-27 — 지시서는 사용자 결정 뒤 |
| [T-018](./T-018-운영-mcp-채팅-모델-제거.md) | 사내 전용 기능 제거 ② 운영 MCP·채팅 모델 포트 | backend-dev (backend-dev) | 완료 | T-007 | 2026-11-25 | Phase 2. QA 확인: 예. **QA PASS 9/9**(TC-014 T-018분 + T-007·T-008 task + 핵심 6개). T-007에서 분할. 연동 카드 2행, `/api/llm-test`·`/api/mcp-ask`·`/api/mcp-status` 삭제, 사용자 정의 MCP 회귀 단위 테스트(`McpCustomOnlyTest`). 버전 2.0.0 유지. TC-023 `llm-test` 기대 404는 qa가 갱신. 05·07 반영 |
| [T-019](./T-019-tc013-core-승격.md) | TC-013 핵심 묶음 승격(T-007·T-018 단언 합침) | qa (qa) | 완료 | T-018 | 2026-11-25 | 유형: 유지. **QA PASS 7/7**(`@core` 7개 — TC-006~010·TC-023·TC-013). `e2e/core/removed-features.spec.ts`, `e2e/tasks/` 비움. 07 TC-013 자동화 열 갱신 |
| [T-020](./T-020-좁은-폭-상단바.md) | 좁은 폭(390px) 상단바 보정 | frontend-dev (frontend-dev) | 대기 | T-008 | 2026-11-25 | OI-26 ①. QA 확인: 예(TC-024). **서버 반영(1단계, 사용자) 뒤** 착수. `ui.css`·`ui.js`만. T-012보다 먼저 |
| [T-021](./T-021-자식-프로세스-환경-정리.md) | 자식 프로세스 환경에서 앱 비밀 환경변수 제거 | backend-dev (backend-dev) | 완료 | T-009 | 2026-11-25 | OI-27 ①. QA 확인: 아니오. `platform/ChildEnv`(생성처 6곳) + MCP 자리표시자 이름 제한(security-reviewer HIGH-1, 범위 편입). 테스트 142건, 2.0.1. 02 §6·04 §4·§9 반영. 남는 위험(`.env` 파일 읽기) → T-023 범위 6, 묶음 import 경고 → OI-32 |
| [T-022](./T-022-서버-jdk21-고정.md) | 서버 unit JDK 21 고정 + smoke 프리뷰 흔적 정리 | backend-dev (backend-dev) | 완료 | T-009 | 2026-11-25 | OI-29 ①·OI-31 ①. QA 확인: 아니오. PM 검수 통과(JDK 21 `test bootJar` 재실행 rc=0). 서버 smoke `SMOKE PASS`(메인 세션, jar 2.0.3) — 07 TC-001 |
| [T-024](./T-024-읽기-전용-세션-편집-금지.md) | 읽기 전용 세션 편집 도구 금지 + 관리 세션 권한 확대 경로 금지 | backend-dev (backend-dev) | 완료 | T-023 | 2026-11-25 | OI-34 ①·OI-33 ①. QA 확인: 아니오. 2.0.3. PM 재실행 통과, diff 대조로 리뷰어 생략. 02 §6 반영, OI-33·34 해소 |
| [T-025](./T-025-묶음-가져오기-확인.md) | 구성 묶음 가져오기 확인 창(stdio MCP 명령 표시) | frontend-dev (frontend-dev) | 대기 | T-004 | 2026-11-25 | OI-32 ①. QA 확인: 예(TC-025). 필요하면 읽기 전용 미리보기 API 포함. T-012보다 먼저 |
| [T-023](./T-023-에이전트-도구-제한.md) | 검증 리뷰어 편집 도구 금지 + 에이전트 curl 제한 + `.env` 읽기 금지 | backend-dev (backend-dev) | 완료 | T-021 | 2026-11-25 | OI-30 ①·OI-22 ①, T-021 보안 리뷰 MEDIUM-1(범위 6). QA 확인: 아니오. `ClaudeCliAgent.disallowedTools` + 단위 테스트 5개(전체 147), 2.0.2. java-reviewer 1회 경고. 02 §6·04 §4 반영. 남는 위험 → OI-33(관리 세션 우회·나머지 POST), OI-34(분석·리서치 편집 도구) |

## 다음에 착수 가능한 작업
검수 요청: 없음. 보류: 없음. depends가 모두 완료된 `대기` 작업: **T-010**(PM 보완 뒤), **T-025**, T-020(서버 반영 뒤 — 사용자 확인 필요), T-013(Phase 4 — Phase 2·3 뒤에 착수). **다음 작업 착수 전에 T-024 코드·결과와 이번 문서 변경(00·02·04·07, BOARD, T-022·T-024 지시서, `results/T-024-*`)을 커밋한다.**

순서: PM(T-010 보완) → T-010(backend-dev). T-025(frontend-dev)는 T-010과 파일이 겹치지 않아 병렬 가능 — 다만 `gradlew`를 동시에 돌리지 않도록 메인 세션이 순서를 잡고, qa 확인(TC-025)은 8778 한 번에 한 실행. T-025는 `platform.html`·`admin.js`를 같이 고치는 T-012보다 먼저. T-020은 서버 반영(사용자) 뒤 언제든 — 8778은 한 번에 한 실행이므로 qa 확인은 순차. T-012는 T-020 뒤. T-013은 Phase 2·3 뒤.

## 막힌 작업
| ID | 막힌 이유 | 해소 조건 |
|---|---|---|
| T-014 | 화면별 상세와 API 형식이 정해지지 않음(OI-13) | 4단계 종료 후 5단계 기획(PM) |

## 단계 종료 때 사용자가 할 일
| Phase | 할 일 |
|---|---|
| 1 | 전용 DB·role 생성, `deploy/README.md` 절차대로 서버에 올리고 확인 항목 점검(serve 뒤 `Host`·`Origin` 값 기록 → OI-21 결정 자료). 공유 DB의 compose는 고치지 않는다(OI-18 — T-017 완료 뒤의 절차서 기준). 서버 설치를 누가 할지, OI-14(플랫폼 관리)·OI-21·OI-24 결정 |
| 2 | 서버 갱신(전용 DB를 비우고 다시 시작), `.env`에서 제거된 키 정리 |
| 3 | GitHub 토큰 발급(OI-09), 실제 이슈 1건으로 Draft PR까지 확인. (PM) 구성도 갱신 devops 지시서 발행 — OI-25 |
| 4 | Telegram 봇 생성, 알림·명령 왕복 확인 |
| 5 | PC·모바일에서 화면 확인 |

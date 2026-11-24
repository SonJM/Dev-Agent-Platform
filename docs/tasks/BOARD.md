# 진행 보드

> Dev Agent (2.0 전환) · 최종 갱신 2026-11-24 (T-001·T-004 완료) · 상태는 PM만 바꾼다

상태값: **대기** (착수 전) · **진행** (담당 에이전트 작업 중) · **검수** (검수 요청됨, PM 확인 대기) · **반려** (재작업 필요) · **완료** · **보류** (외부 결정·환경 문제로 멈춤 — 사유 필수)

## 작업

| ID | 제목 | 담당 | 상태 | depends | 갱신일 | 비고 |
|---|---|---|---|---|---|---|
| [T-001](./T-001-linux-기동.md) | Linux 기동 지원 | backend-dev (backend-dev) | 완료 | - | 2026-11-24 | Phase 1. QA 확인: 아니오. 서버 smoke `SMOKE PASS`(메인 세션 실행) |
| [T-002](./T-002-접근-제어.md) | 접근 제어(신뢰 Origin) | backend-dev (backend-dev) | 대기 | T-001, T-004 | 2026-11-24 | Phase 1. QA 확인: 예. 1.13.0. 신원 확인은 폐기 |
| [T-003](./T-003-서버-실행-구성.md) | 서버 실행 구성(systemd·공유 DB SQL·절차서) | backend-dev (backend-dev) | 대기 | T-001, T-002 | 2026-11-24 | Phase 1. 서버 적용은 사용자(OI-18 포함) |
| [T-004](./T-004-qa-구축.md) | QA 기반 구축(e2e·핵심 TC) | qa (qa) | 완료 | - | 2026-11-24 | Phase 1. 유형: 구축. @core 5개 QA PASS |
| [T-005](./T-005-전체-구성도.md) | 전체 구성도 | devops (devops) | 대기 | T-003 | 2026-11-24 | Phase 1. 유형: 그림 |
| [T-006](./T-006-메신저-수신-경로.md) | 메신저 수신 경로 신설 | backend-dev (backend-dev) | 대기 | T-002 | 2026-11-24 | Phase 2. 단계 수준 |
| [T-007](./T-007-사내-기능-제거.md) | 사내 전용 기능 제거 | backend-dev (backend-dev) | 대기 | T-006, T-008 | 2026-11-24 | Phase 2. QA 확인: 예. 2.0.0. 운영 MCP·채팅 모델 포트 제거 포함 |
| [T-008](./T-008-화면-정리.md) | 화면 정리 | frontend-dev (frontend-dev) | 대기 | T-002 | 2026-11-24 | Phase 2. QA 확인: 예. OI-04 선결 |
| [T-009](./T-009-windows-요소-제거.md) | Windows 전용 요소·프리뷰 제거 | backend-dev (backend-dev) | 대기 | T-007 | 2026-11-24 | Phase 2. QA 확인: 예. OI-04 선결 |
| [T-010](./T-010-github-연동.md) | GitHub 연동(기능 교체) | backend-dev (backend-dev) | 대기 | T-009 | 2026-11-24 | Phase 3. QA 확인: 예. 보안 리뷰. OI-09·10·15 선결 |
| [T-011](./T-011-이름-정리.md) | 이름 정리(`gitlab` 개명) | backend-dev (backend-dev) | 대기 | T-010 | 2026-11-24 | Phase 3. T-012와 한 묶음으로 반영 |
| [T-012](./T-012-화면-github-표기.md) | 화면의 GitHub 표기 | frontend-dev (frontend-dev) | 대기 | T-011 | 2026-11-24 | Phase 3. QA 확인: 예 |
| [T-013](./T-013-telegram-어댑터.md) | Telegram 어댑터 | backend-dev (backend-dev) | 대기 | T-007 | 2026-11-24 | Phase 4. 보안 리뷰. 왕복 확인은 사용자 |
| [T-014](./T-014-spa-전환.md) | SPA 전환(묶음) | frontend-dev (frontend-dev) | 보류 | T-012, T-013 | 2026-11-24 | Phase 5. 기획 뒤 분할 |

## 다음에 착수 가능한 작업
depends가 모두 완료된 `대기`·`반려` 작업: T-002 (T-001·T-004 변경을 커밋한 뒤 착수 — `admin/SystemController.java` 충돌)

## 막힌 작업
| ID | 막힌 이유 | 해소 조건 |
|---|---|---|
| T-014 | 화면별 상세와 API 형식이 정해지지 않음(OI-13) | 4단계 종료 후 5단계 기획(PM) |

## 단계 종료 때 사용자가 할 일
| Phase | 할 일 |
|---|---|
| 1 | 공유 PostgreSQL에 `127.0.0.1:5432` 공개 추가(OI-18, 서비스 정리 때), 전용 DB·role 생성, `deploy/README.md` 절차대로 서버에 올리고 확인 항목 점검. OI-14(플랫폼 관리) 결정 |
| 2 | 서버 갱신(전용 DB를 비우고 다시 시작), `.env`에서 제거된 키 정리 |
| 3 | GitHub 토큰 발급(OI-09), 실제 이슈 1건으로 Draft PR까지 확인 |
| 4 | Telegram 봇 생성, 알림·명령 왕복 확인 |
| 5 | PC·모바일에서 화면 확인 |

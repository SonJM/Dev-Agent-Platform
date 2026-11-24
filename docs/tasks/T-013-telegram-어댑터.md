# T-013. Telegram 어댑터

```
id: T-013
담당: backend-dev (backend-dev)
상태: 대기
depends: [T-007]
병렬: 가능            # Phase 3(T-010~T-012)과 파일이 겹치지 않는다. 순서는 Phase 3 뒤를 권한다
충돌: []
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 구현
QA 확인: 아니오       # 실제 Telegram과 봇 토큰이 있어야 관찰된다. 단위 테스트(TC-018~020)로 확인하고 왕복 동작은 사용자가 확인한다
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-24
```

Phase 4. 단계 수준 지시서 — 보안 리뷰(security-reviewer)를 1회 받는다(외부에서 들어오는 글이 에이전트 명령이 된다).

## 목적
봇과의 1:1 대화로 알림을 받고 명령·확인 답변을 보낸다.

## 근거
- 요구사항: `FR-401`, `FR-402`, `FR-403`, `FR-205` ([01 §4.4](../01-requirements.md)), `NFR-302`
- 설계: [02 §3.1](../02-architecture.md), [02 §4.1](../02-architecture.md)(long polling), T-006 변경 설명서의 포트 시그니처
- 화면: `S-04` Phase 4 ([06 §3.5](../06-functional-specification.md)) — 연결 상태 카드가 쓰는 값은 기존 `/api/platform` 구조로 제공
- 테스트: `TC-018`, `TC-019`, `TC-020` ([07 §4](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), `CLAUDE.md`(포트 규약: `health()`는 호출 없이 즉시)

## 관련 코드 경로
- 신규: `messenger/telegram/`(구현체, `@ConditionalOnProperty` `platform.messenger=telegram`), 설정 record, 테스트
- 수정: `application.yml`, `.env.example`, `env.template`, `deploy/env.server.example`, `ops/Doctor.java`, `README.md`

## 범위
- Bot API long polling으로 수신(수신 포트 없음), 텍스트 전송, 한 건 길이 한도에 맞춘 분할.
- 허용 대화는 설정한 chat id 하나. 다른 대화의 글은 버리고 답하지 않는다(커서는 전진). chat id를 알아내는 방법을 절차로 제공한다(예: 미설정 상태에서 받은 대화 id를 로그가 아닌 Doctor 안내로 보여 주는 방식 — 방식은 구현 시 정하고 근거를 적는다).
- 토큰·chat id는 `.env`로만(`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`). 토큰이 들어간 URL을 로그·예외에 남기지 않는다.
- 연결 끊김·오류 응답에서 재시도 간격을 두고 복구한다. 연속 실패는 연동 카드와 알림함(시스템)에 드러난다.
- 텍스트가 아닌 메시지(사진·스티커)는 무시한다.

## 범위 밖
- webhook, 그룹 대화, 버튼(인라인 키보드)·파일 전송, Slack
- 명령 체계 변경(라우터는 T-006)

## 완료 조건
- [ ] `TC-018`, `TC-019`, `TC-020`이 단위 테스트로 존재하고 통과한다
- [ ] `platform.messenger=none`(기본)에서 동작이 바뀌지 않는다, 토큰 없이 `telegram`을 켜도 기동은 된다
- [ ] "사용자가 확인할 것"(봇 만들기, chat id 확인, 알림 수신, `이슈 스캔` 왕복, 확인 대기 `진행`) 절차가 변경 설명서에 있다
- [ ] 변경 설명서(`docs/tasks/results/T-013-telegram-어댑터.md`)가 그림·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-013-telegram-어댑터.md](./results/T-013-telegram-어댑터.md)

## 검증 명령
```bash
./gradlew test bootJar
```

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(단계 수준) |

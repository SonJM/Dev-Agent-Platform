# T-012. 화면의 GitHub 표기 반영

```
id: T-012
담당: frontend-dev (frontend-dev)
상태: 대기
depends: [T-011, T-020]
병렬: 불가
충돌: [T-011]
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

Phase 3. 단계 수준 지시서. 기존 정적 HTML을 고친다(SPA 아님).

## 목적
화면이 GitHub 용어와 바뀐 응답 필드로 동작한다.

## 근거
- 요구사항: `FR-306`, `FR-305` ([01 §4.3](../01-requirements.md))
- 화면: `S-01`·`S-02`·`S-03`·`S-05`의 "변경 — Phase 3" ([06 §3](../06-functional-specification.md))
- 명세: [05 §2.3](../05-api-specification.md), T-011 변경 설명서의 이름 대응표
- 테스트: 회귀 핵심 묶음 ([07 §8](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), `CLAUDE.md`(`shortEntry` — 프로젝트 항목을 `/`로 자르지 않는다)

## 관련 코드 경로
- 수정: `src/main/resources/static/agent.html`, `research.html`, `home.html`, `platform.html`, `admin.js`, `ui.js`

## 범위
- 문구 "GitLab" → "GitHub", "MR" → "PR", 표기 `owner/repo#n`, 이슈·PR 링크.
- 응답 필드 `issueIid` → `issueNumber`, `mrUrl` → `prUrl`, `projectId` 사용 제거.
- 빈 상태 안내 문구(대상 저장소 없음, 토큰 없음)를 GitHub에 맞춘다.

## 범위 밖
- 백엔드, 새 화면·디자인 변경, SPA

## 완료 조건
- [ ] 검증 명령의 검색 결과 0, `node --check` 통과
- [ ] 390 / 768 / 1440px 스크린샷 3장(에이전트 화면), 가로 넘침이 작업 전보다 늘지 않았다
- [ ] 변경 설명서(`docs/tasks/results/T-012-화면-github-표기.md`)가 그림·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-012-화면-github-표기.md](./results/T-012-화면-github-표기.md)

## 검증 명령
```bash
for f in src/main/resources/static/*.js; do node --check "$f" || exit 1; done
grep -rnE "GitLab|gitlab|issueIid|mrUrl|projectId" src/main/resources/static ; test $? -eq 1
```

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(단계 수준) |
| 2026-11-25 | 대기 | PM 수정: `depends`에 T-020(같은 `ui.js` — 좁은 폭 상단바 보정을 먼저, OI-26) |

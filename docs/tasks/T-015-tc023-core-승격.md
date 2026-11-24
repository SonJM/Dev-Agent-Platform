# T-015. TC-023 핵심 묶음 승격

```
id: T-015
담당: qa (qa)
상태: 완료
depends: [T-002]
병렬: 가능
충돌: []
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 유지
QA 확인: 아니오
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-24
```

## 목적
TC-023(실제 컨테이너에서 다른 출처 상태 변경 차단)이 이후 모든 qa 확인에서 함께 돈다.

## 근거
- 결정: [07 §8](../07-test-plan.md) 핵심 TC 묶음에 TC-023 추가(2026-11-24, T-002 qa 보고서 `## 묶음 제안` 채택)
- TC: `TC-023` ([07 §4](../07-test-plan.md)), `FR-104`
- 원본: [T-002 qa 보고서](./results/T-002-접근-제어-qa.md)

## 관련 코드 경로
- `e2e/tasks/T-002.spec.ts` → `e2e/core/`(새 이름은 다른 core 파일 관례대로)

## 범위
- `e2e/tasks/T-002.spec.ts`를 `e2e/core/`로 옮기고 태그를 `@task:T-002`에서 `@core`로 바꾼다. 테스트 내용은 바꾸지 않는다.
- `e2e/tasks/`에 T-002 파일이 남지 않는다.
- `@core` 묶음 전체(6개)를 표준 실행으로 한 번 돌린다.

## 범위 밖
- 앱 코드, 04·07 수정(07 §8은 PM이 이미 갱신)
- 다른 TC 추가·수정

## 완료 조건
- [ ] `e2e/core/`에 TC-023 테스트가 있고 `@core` 태그, `e2e/tasks/T-002.spec.ts` 없음
- [ ] `@core` 실행 6개 통과·skipped 0, 07 §8 목록과 `@core`가 일치
- [ ] 보고서(`docs/tasks/results/T-015-tc023-core-승격-qa.md`)와 `qa-baseline.md` 한 행

## 변경 설명서
qa 보고서로 갈음: [results/T-015-tc023-core-승격-qa.md](./results/T-015-tc023-core-승격-qa.md)

## 검증 명령
```bash
# qa 표준 실행(qa-run.sh)으로 --grep @core. PM은 다시 실행하지 않고 보고서의 판정과 report.json을 대조한다
```

## 검증 증거
- 일시: 2026-11-24 16:28
- 명령: qa-run.sh --grep @core (e2e/scripts/start-app.sh, 8778, 기존 jar)
- 결과: 6 passed, skipped 0, QA PASS. e2e/tasks/T-002.spec.ts -> e2e/core/access-control.spec.ts(@core)
- 변경 설명서: [results/T-015-tc023-core-승격-qa.md](./results/T-015-tc023-core-승격-qa.md)

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(T-002 묶음 제안 채택) |
| 2026-11-24 | 검수 요청 | qa 실행 6/6 통과 |
| 2026-11-24 | 완료 | PM 검수: HEAD 대조 이상 없음, `e2e/core/access-control.spec.ts`는 원본과 태그 한 줄만 다름(`@task:T-002`→`@core`), `e2e/tasks/` 비어 있음, `@core` 6개 = 07 §8. 보고서 `## 검증` 마지막 줄 QA PASS = 판정, report.json expected 6·unexpected 0·skipped 0·flaky 0 = 표 |

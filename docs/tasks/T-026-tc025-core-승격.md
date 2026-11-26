# T-026. TC-025 핵심 묶음 승격(묶음 가져오기 stdio MCP 확인 창)

```
id: T-026
담당: qa (qa)
상태: 완료
depends: [T-025, T-010]
병렬: 불가
충돌: [T-012]   # 같은 platform.html·admin.js — T-012 전에 묶음에 넣어 회귀를 잡는다
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
작성: 2026-11-26
```

## 목적
구성 묶음 가져오기의 stdio MCP 확인 창(OI-32 보안 확인)이 이후 모든 qa 확인에서 함께 확인되어, 같은 화면을 고치는 T-012 등에서 회귀가 생기면 바로 드러난다.

## 근거
- 결정: [07 §8](../07-test-plan.md) 승격 후보 TC-025 — "승격한다, T-012 착수 전, qa `유형: 유지`"(2026-11-25 PM, T-025 qa 묶음 제안)
- TC: `TC-025` ([07 §4](../07-test-plan.md)), `FR-604`
- 원본: [T-025 qa 보고서](./results/T-025-묶음-가져오기-확인-qa.md), `e2e/tasks/T-025.spec.ts`

## 관련 코드 경로
- `e2e/tasks/T-025.spec.ts` → `e2e/core/`(새 이름은 다른 core 파일 관례대로)
- `e2e/tasks/T-010.spec.ts` — T-010 완료(2026-11-26)로 지울 대상

## 범위
1. `T-025.spec.ts`의 TC-025 테스트(①②와 390px 확인 창)를 `e2e/core/`로 옮기고 태그를 `@core`로 바꾼다. 단언의 뜻은 바꾸지 않는다. 시험 데이터(가져온 MCP 서버·스킬)를 끝에 지우는 정리는 유지한다 — 다른 core 테스트(TC-007 스킬 목록 등)와 실행 순서에 따라 간섭하지 않아야 한다.
2. `e2e/tasks/`에 `T-025.spec.ts`·`T-010.spec.ts`가 남지 않는다(T-010은 완료 작업의 task 테스트 정리).
3. `@core` 묶음 전체(8개)를 표준 실행으로 한 번 돌린다. 실행 시간 상한(10분) 안이어야 한다.

## 범위 밖
- 앱 코드, 04·07 수정(07 §8은 PM이 검수 때 갱신)
- 다른 TC 추가·수정, `start-app.sh` 환경값(04 §8 — 이슈 연동은 기본값에 넣지 않는다)

## 완료 조건
- [ ] `e2e/core/`에 TC-025 테스트가 있고 `@core` 태그, 범위 1의 단언이 모두 들어 있다
- [ ] `e2e/tasks/`에 `T-025.spec.ts`·`T-010.spec.ts` 없음
- [ ] `@core` 실행 8개 통과·skipped 0(TC-006~010, TC-023, TC-013, TC-025), 전체 실행 시간 상한(10분) 안
- [ ] 보고서(`docs/tasks/results/T-026-tc025-core-승격-qa.md`)에 옮긴 단언 대조표(원본 위치 → 새 위치)와 `qa-baseline.md` 한 행

## 변경 설명서
qa 보고서로 갈음: [results/T-026-tc025-core-승격-qa.md](./results/T-026-tc025-core-승격-qa.md)

## 검증 명령
```bash
# qa 표준 실행(qa-run.sh)으로 --grep @core. PM은 다시 실행하지 않고 보고서의 판정과 report.json을 대조한다
test -z "$(ls e2e/tasks/T-025.spec.ts e2e/tasks/T-010.spec.ts 2>/dev/null)"
grep -rl "TC-025" e2e/core | grep -q .
```

## 검증 증거
- 일시: 2026-11-26 09:05
- 명령: `qa-run.sh --grep @core --output docs/tasks/results/T-026-qa`(QA_BASE_URL=http://127.0.0.1:8778), `ls e2e/tasks`, `grep -rl TC-025 e2e/core`
- 결과: QA PASS, 10개 통과(8 TC)·skipped 0, 56.2초. `e2e/core/bundle-import.spec.ts`에 TC-025 3개(`@core`), `e2e/tasks/` 비어 있음. 8778·54329 리슨 0
- 변경 설명서: [results/T-026-tc025-core-승격-qa.md](./results/T-026-tc025-core-승격-qa.md)

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-26 | 대기 | PM 작성(07 §8 TC-025 승격 결정, T-012 착수 전. T-010 완료로 `T-010.spec.ts` 정리 포함) |
| 2026-11-26 | 완료 | PM 검수 통과: 검증 명령 2개 재실행 통과, `report.json` expected 10·unexpected 0·skipped 0이 보고서와 일치, 원본 `T-025.spec.ts`(HEAD)와 새 파일 diff는 태그 3줄뿐, 지시서 본문 변경 없음. 07 §8에 TC-025 행 추가. 참고: 실행 jar는 2.0.4 시점 빌드(앱 코드 변경 없는 작업이라 인정, 최신 jar 확인은 다음 `QA 확인: 예` 실행) |

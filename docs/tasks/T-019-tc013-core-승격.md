# T-019. TC-013 핵심 묶음 승격(T-007·T-018 단언 합침)

```
id: T-019
담당: qa (qa)
상태: 완료
depends: [T-018]
병렬: 불가
충돌: [T-009]   # e2e/tasks/T-008.spec.ts·e2e/core/ — T-009 qa 확인이 이 파일에 로컬 프리뷰 단언을 더한다
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
작성: 2026-11-25
```

## 목적
Phase 2에서 지운 화면 요소·API(TC-013 T-008·T-007·T-018 시점, TC-014 제거 API 404)가 되살아나지 않는지를 이후 모든 qa 확인에서 함께 확인한다.

## 근거
- 결정: [07 §8](../07-test-plan.md) 핵심 TC 묶음에 TC-013 추가(2026-11-25 — T-008·T-007·T-018 qa 보고서 `## 묶음 제안` 채택). "승격 지시서에 넣을 것" 목록을 그대로 따른다
- TC: `TC-013`(T-008·T-007·T-018 시점), `TC-014`(제거 API 404) ([07 §4](../07-test-plan.md)), `FR-207`·`FR-208`·`FR-201`·`FR-202`·`FR-205`
- 원본: [T-008 qa 보고서](./results/T-008-화면-정리-qa.md), [T-007 qa 보고서](./results/T-007-사내-기능-제거-qa.md), [T-018 qa 보고서](./results/T-018-운영-mcp-채팅-모델-제거-qa.md)

## 관련 코드 경로
- `e2e/tasks/T-008.spec.ts`(바탕) ← `e2e/tasks/T-007.spec.ts`·`e2e/tasks/T-018.spec.ts`의 단언 합침 → `e2e/core/`(새 이름은 다른 core 파일 관례대로)

## 범위
1. `T-007.spec.ts`의 단언(메신저 알림 유형 3개, 지식(RAG) 문구 없음, 제거 API 8개 404)과 `T-018.spec.ts`의 단언(연동 카드 2행 — 상태 문구가 아니라 분모만, 제거 API 3개 404)을 `T-008.spec.ts`에 합친다. 단언의 뜻은 바꾸지 않는다.
2. 합친 파일을 `e2e/core/`로 옮기고 태그를 `@core`로 바꾼다. `e2e/tasks/`에 T-007·T-008·T-018 파일이 남지 않는다.
3. `T-008.spec.ts`의 낡은 주석(T-007이 아직 백엔드를 바꾸기 전이라는 취지의 문장)이 있으면 지금 상태에 맞게 고친다.
4. 테스트 이름은 TC-013을 앞에 두고, 제거 API 404 부분이 TC-014 항목임을 주석이나 이름으로 남긴다(07 §8 — 같은 파일에 둔다).
5. `@core` 묶음 전체(7개)를 표준 실행으로 한 번 돌린다.

## 범위 밖
- 앱 코드, 04·07 수정(07 §8은 PM이 이미 갱신)
- T-009 시점 단언(로컬 프리뷰 행 없음) — T-009 qa 확인 때 qa가 이 core 파일에 더한다
- 다른 TC 추가·수정

## 완료 조건
- [ ] `e2e/core/`에 TC-013 테스트가 있고 `@core` 태그, 범위 1의 단언이 모두 들어 있다
- [ ] `e2e/tasks/`에 `T-007.spec.ts`·`T-008.spec.ts`·`T-018.spec.ts` 없음
- [ ] `@core` 실행 7개 통과·skipped 0, 07 §8 목록(TC-006~010, TC-023, TC-013)과 `@core`가 일치, 전체 실행 시간 상한(10분) 안
- [ ] 보고서(`docs/tasks/results/T-019-tc013-core-승격-qa.md`)에 합친 단언 대조표(원본 파일·단언 → 새 파일 위치)와 `qa-baseline.md` 한 행

## 변경 설명서
qa 보고서로 갈음: [results/T-019-tc013-core-승격-qa.md](./results/T-019-tc013-core-승격-qa.md)

## 검증 명령
```bash
# qa 표준 실행(qa-run.sh)으로 --grep @core. PM은 다시 실행하지 않고 보고서의 판정과 report.json을 대조한다
test -z "$(ls e2e/tasks/T-007.spec.ts e2e/tasks/T-008.spec.ts e2e/tasks/T-018.spec.ts 2>/dev/null)"
```

## 검증 증거
- 일시: 2026-11-25 11:39
- 명령: qa-run.sh --grep @core (QA_BASE_URL=http://127.0.0.1:8778); test -z "$(ls e2e/tasks/T-007.spec.ts e2e/tasks/T-008.spec.ts e2e/tasks/T-018.spec.ts)"
- 결과: QA PASS, 7 passed, skipped 0, 23.9초, 8778·54329 리슨 0
- 변경 설명서: [results/T-019-tc013-core-승격-qa.md](./results/T-019-tc013-core-승격-qa.md)

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-25 | 대기 | PM 작성(T-018 QA PASS 뒤 — T-008·T-007·T-018 묶음 제안 채택, 07 §8) |
| 2026-11-25 | 완료 | PM 검수 통과: 지시서 메타·범위·완료 조건·검증 명령은 HEAD와 같음(검증 증거·상태만 변경). 검증 명령(`e2e/tasks/` 세 파일 없음) 재실행 통과. `e2e/` 파일 지도 = 세 파일 삭제 + `e2e/core/removed-features.spec.ts` 추가(`@core`)뿐. `report.json` stats expected 7·skipped 0·unexpected 0·flaky 0, 7개 모두 `core` 태그·passed — 보고서 표·`## 검증` 마지막 줄 `QA PASS`와 일치. 대조표 17행, `qa-baseline.md` 한 행, `report.json`에 로컬 홈 경로 없음, `app.log`는 gitignore. 07 TC-013 자동화 열·§8 갱신 |

# T-029. 플랫폼 탭 「GitHub에서 고르기」 창

```
id: T-029
담당: frontend-dev (frontend-dev)
상태: 반려
depends: [T-028]
병렬: 불가
충돌: [T-028]
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
작성: 2026-11-27
```

## 목적
플랫폼 탭 「대상 프로젝트」 카드에서 내 GitHub 저장소 목록을 열어 체크로 골라 대상 프로젝트로 저장할 수 있게 한다(직접 입력은 그대로 남긴다).

## 근거
- 화면: `S-05` [06 §3.6](../06-functional-specification.md) 「GitHub 저장소 고르기」 — 업무 규칙 ①~⑧, **모바일 동작 (390px)**
- 결정: `OI-09` 결정 변경, `OI-42`(보관 저장소 고를 수 없음·상한 50·직접 입력 유지) ([00-INDEX](../00-INDEX.md))
- API(T-028이 만든다): `GET /api/issues-repos?refresh=` → `{ ok, reason?, repos:[{path, defaultBranch, private, archived, fork, updatedAt, webUrl, selected}], truncated, max }`, `POST /api/issues-projects/set`(form `path` 여러 개) → `{ result, added, removed, rejected:[{path, reason}] }`. 실제 형식은 [T-028 변경 설명서](./results/T-028-github-저장소-목록-api.md)를 따른다(다르면 보고)
- 요구사항: `FR-301`, `FR-306` ([01 §4.3](../01-requirements.md))
- 테스트: `TC-027` ([07 §4](../07-test-plan.md))
- 코딩 규약: 루트 `CLAUDE.md` 「코드 작업 규칙」(`ui.css`·`ui.js` 공통 디자인 시스템, admin.js 최상위 이벤트는 `on(id, ev, fn)`, 탭 셸 iframe 주의, 새 클래스 이름은 `ui.css`와 겹치지 않게, 수정 뒤 `node --check`)
- 디자인 시스템: 이 프로젝트는 `static/ui.css`·`ui.js`가 디자인 시스템이다(`docs/design/DESIGN-SYSTEM.md` 없음 — 만들지 않는다, 새 색·글꼴 없이 기존 토큰만)

## 관련 코드 경로
- 수정: `src/main/resources/static/platform.html`(51행 근처 「대상 프로젝트」 카드), `src/main/resources/static/admin.js`(376~402행 「대상 프로젝트 추가/삭제」 — `loadProjects`·`projectAdd`·`projectRemove`), 필요하면 `static/admin.css`
- 참고: `static/admin.js`·`platform.html`의 구성 묶음 가져오기 확인 창(T-025 — 같은 `dialog` 모양·모바일 처리), `static/ui.js`(`UI.toast`, `esc`)

## 범위
1. 「대상 프로젝트」 카드에 「GitHub에서 고르기」 버튼. 누르면 `dialog`를 열고 그때 한 번 `GET /api/issues-repos`(폴링·주기 갱신 없음). 창 안 「새로고침」은 `refresh=true`.
2. 06 §3.6 업무 규칙대로: 행(체크 · `owner/repo` 링크 · 비공개/보관됨/포크 배지 · 기본 브랜치 · 최근 갱신 상대 시각), 이름 필터(화면 안에서만), 현재 대상 미리 체크, 「목록에 없는 대상」 구역(체크된 채, 풀어야 빠짐), 보관 저장소 비활성, 상한(`max`) 초과 시 「저장」 비활성 + 안내, 전부 해제 저장은 `confirm`, 조회 실패 문구(`reason`을 그대로 보이되 `esc`), `truncated` 안내, 고정 안내 한 줄("토큰의 Repository access가 'All repositories'가 아니면 고른 저장소만 보입니다").
3. 「저장」 → `POST /api/issues-projects/set`(체크한 저장소 + 체크를 풀지 않은 「목록에 없는 대상」) → 결과를 창 안 한 줄과 `toast`로(추가·삭제 수, 거부된 저장소와 사유) → `loadProjects()` 다시 읽기. 거부가 없으면 창을 닫고, 있으면 창을 열어 둔다. 「취소」는 요청 없이 닫는다. **서버가 돌려준 문자열(`rejected[].path`·`reason`, `result`, 저장소 `path`·`defaultBranch`, `reason`)은 사용자 입력이 그대로 되돌아올 수 있으므로 전부 `esc`하거나 `textContent`로 넣는다**(innerHTML에 그대로 넣지 않는다 — T-028 security-reviewer LOW3). `webUrl`은 `https://github.com/`으로 시작할 때만 링크로 만든다.
4. 직접 입력 칸·칩의 × 삭제는 그대로 둔다.
5. 모바일 동작(390px)은 06 §3.6 그대로 — 창 전체 폭·최대 높이 90%, 필터 위 고정, 목록 세로 스크롤, 버튼 아래 고정, 긴 이름 문자 단위 줄바꿈, 행 전체 누르기(44px 이상). 768px 이상은 창 폭 최대 640px.
6. 스크린샷용 목록 상태는 브라우저 요청 가로채기(Playwright `page.route`)로 `/api/issues-repos` 응답을 흉내 내도 된다(QA 환경에는 토큰이 없다). 실제 대상 목록을 바꾸는 저장은 하지 않는다.

## 범위 밖
- 서버 코드(`src/main/java`), API 형식 변경 — 필요하면 설명서 `## 제안`으로 보고
- 에이전트 화면(`agent.html`)의 「이슈 목록」·프로젝트 필터, 리서치 화면, 메신저 화면
- 상단바·탭 셸(OI-40·OI-41), 버전(`build.gradle` — T-028이 2.3.0으로 올림)

## 완료 조건
- [ ] 06 §3.6 업무 규칙 ①~⑦과 모바일 동작이 화면에 반영됐다
- [ ] `node --check` 통과(`admin.js`, 고친 스크립트 전부)
- [ ] 390 / 768 / 1440px 스크린샷 3장(창이 열린 목록 상태, 모바일 viewport 에뮬레이션), 페이지·창 가로 넘침 없음
- [ ] 터치 영역 44px 이상, taste inspect FAIL 0(이 변경으로 생긴 FAIL 기준 — 기존 FAIL은 OI-35·OI-40으로 구분해 적는다, T-025·T-020 선례)
- [ ] 토큰 없음 상태(가로채기 없이 실제 응답)에서 창 안 안내가 보이고 콘솔 오류 0
- [ ] 명세(06 §3.6, T-028 응답 형식)와 구현이 다르면 응답에 보고했다
- [ ] 변경 설명서(`docs/tasks/results/T-029-github-저장소-고르기-화면.md`)가 그림(창 상태: 불러오는 중 → 목록/실패 → 저장 결과)·설명과 함께 있다

## 변경 설명서
개발 에이전트가 작성: [results/T-029-github-저장소-고르기-화면.md](./results/T-029-github-저장소-고르기-화면.md)

## 검증 명령
```bash
node --check src/main/resources/static/admin.js
node --check src/main/resources/static/ui.js
grep -nE "issues-repos|issues-projects/set" src/main/resources/static/admin.js
./gradlew bootJar
```

## 검증 증거
- 일시: 2026-11-27 12:50
- 명령: `node --check admin.js`·`ui.js`; `grep -nE "issues-repos|issues-projects/set" admin.js`; `./gradlew.bat bootJar`; Playwright 확인 스크립트(8778, 응답 가로채기); taste inspect
- 결과: node --check 통과, grep 3곳, bootJar 성공. 390/768/1440 넘침 없음·터치 44px 미만 0·콘솔 오류 0·XSS 미실행, 토큰 없음 안내 표시. inspect 6 FAIL 모두 기존(OI-35·OI-40), 이번 변경분 0
- 변경 설명서: [results/T-029-github-저장소-고르기-화면.md](./results/T-029-github-저장소-고르기-화면.md)

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-27 | 대기 | PM 작성(사용자 요청 — GitHub 저장소 고르기, 06 §3.6). QA 확인: 예(TC-027) |
| 2026-11-27 | 대기 | PM 보완(T-028 검수): 범위 3에 서버 문자열 출력 이스케이프·`webUrl` 링크 조건(security-reviewer LOW3). 참고 — T-028 재작업으로 「새로고침」은 10초 안에 다시 누르면 서버가 캐시를 준다(목록이 안 바뀌어도 정상), 조직 저장소·100개 넘는 요청은 서버가 거부한다 |
| 2026-11-27 | 대기 | depends 해소(T-028 완료) — 착수 가능 |
| 2026-11-27 | 검수 요청 | frontend-dev 구현 완료 |
| 2026-11-27 | 반려 | PM 검수: 검증 명령 재실행 통과(node --check 2개, grep 3곳, bootJar 성공), 지시서 변조 없음, 설명서 그림 3·파일 지도 일치. typescript-reviewer(1회) Block으로 반려. **재작업**(06 §3.6 ②·⑨·⑩ 갱신분 기준): **R1(HIGH)** `/api/issues-projects` 조회가 실패(네트워크·`!res.ok`·JSON 아님)하거나 `enabled:false`면 창 안 원인 문구 + 「저장」 비활성(`catch(()=>({}))`로 삼키지 말 것, 06 ⑨ 문구). **R2(MEDIUM)** 두 조회를 `Promise.allSettled`로 나눠, `/api/issues-repos` 예외는 ⑤ 실패 문구로만 처리하고 저장 가능 여부는 R1(현재 대상을 아는가)로만 정한다 — 현재 대상을 모르면 어떤 경로로도 「저장」이 켜지지 않는다. **R3(MEDIUM)** 저장 응답은 HTTP 상태(`res.ok`)와 `ok`(T-030이 추가, 05 §2.3)를 확인해 실패면 성공 토스트 없이 서버 `result`를 창 안 오류로 보이고 창 유지(06 ⑩). `ok`가 없는 응답(T-030 이전)은 `rejected` 기준 기존 동작. **R4(LOW④)** 결과 문구는 서버 `result`만 쓰고 추가·삭제 수를 덧붙이지 않는다(`result`가 없을 때만 대체 문구). **R5(LOW⑤)** 토스트 종류는 `ui.css`에 있는 `ok`·`err`만(거부가 있으면 `err`) — `ui.css`에 새 종류를 만들지 않는다. **R6(LOW⑥)** `platform.html` 51행 카드 머리의 `#projCount` 배지 위치를 맞춘다(`.sec`는 사이드바 제목용 클래스라 `ui.css`를 고치지 말고 `admin.css`에 범위를 좁힌 규칙). **R7(LOW⑧)** 저장 중에는 「새로고침」도 비활성, 저장이 끝나기 전 `repoRender`/`repoCount`가 「저장」을 다시 켜지 않게(저장 중 표시). **R8(LOW⑦, 한 줄)** `path`가 문자열이 아닌 항목은 렌더에서 건너뛴다. 테스트: Playwright 확인 스크립트에 R1(500·`enabled:false`)·R2(`issues-repos` 네트워크 실패)·R3(`ok:false`·HTTP 500) 가로채기 확인을 더하고 결과를 설명서 `## 검증`에 적는다(TC-027 ④⑤와 같은 상황). 스크린샷 재촬영·`.gitignore`는 메인 세션이 따로 맡긴 작업으로 처리. **제안 처리**: ① 토큰/꺼짐 때 현재 대상 미표시 → 서버 변경은 **기각**(현재 대상 표시는 `enabled`일 때만 의미 — 꺼진 상태의 대상 편집은 데이터 손실 위험이 더 크다). 대신 R1로 저장을 막고 안내. 참고: `enabled`는 토큰이 아니라 `ISSUE_ENABLED` 값이다 — 토큰만 없고 `ISSUE_ENABLED=true`면 현재 대상이 보인다. ② 보관 저장소 해석(현재 대상이면 해제 가능) **채택** → 06 ② 반영 ③ truncated "1,000개" 문구 채택. 재검수는 T-030 완료 뒤 함께 |

# T-016. start-app.sh 중복 기동 거부

```
id: T-016
담당: qa (qa)
상태: 대기
depends: []
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
QA 인스턴스(8778)가 이미 떠 있을 때 `start-app.sh`를 다시 불러도 살아 있는 인스턴스의 내장 DB를 지우지 않는다.

## 근거
- 사고: T-006 2차 재작업(2026-11-24) — 떠 있던 QA 인스턴스가 있는 채 `start-app.sh`를 다시 불러 `pgdata-embedded`가 지워졌고, 기존 인스턴스는 DB를 잃고 응답 없음, 새 기동은 `epg-lock busy`로 실패했다([T-006 이력](./T-006-메신저-수신-경로.md)).
- 격리 규칙: [04 §8](../04-project-structure.md)
- 관련: OI-19(내장 DB 포트 54329 겹침) — 이 작업 범위 밖

## 관련 코드 경로
- 수정: `e2e/scripts/start-app.sh`
- 참고: `e2e/scripts/stop-app.sh`

## 범위
- `rm -rf "$HOME_DIR/pgdata-embedded"` **앞에서** 127.0.0.1:8778에 TCP 연결을 시도해(예: bash `/dev/tcp`, 짧은 시간 제한) 연결되면 한 줄 안내(`port 8778 in use — run stop-app.sh first`)를 출력하고 **0이 아닌 코드로 종료**한다. HTTP 응답이 아니라 리슨 여부로 판정한다(응답 없는 인스턴스도 거부해야 한다).
- 포트 값은 스크립트 안 한 곳(변수)에서 쓴다.

## 범위 밖
- 앱 코드, 다른 e2e 스크립트·테스트, 04·07(필요하면 PM이 검수 때 04 §8에 한 줄 추가)
- 떠 있는 인스턴스를 스크립트가 대신 종료하는 동작(넣지 않는다)
- OI-19(`DB_EMBEDDED_PORT`)

## 착수 전 확인
- 8778을 쥔 프로세스가 없어야 한다(2026-11-24 기준 PID 18188이 응답 없이 남아 있음 — 사용자가 정리). 남아 있으면 착수하지 말고 `보류` 사유로 보고한다. 사용자 인스턴스(8777)는 건드리지 않는다.

## 완료 조건
- [ ] 8778이 비어 있을 때 `start-app.sh`가 이전과 같이 기동한다(status 200)
- [ ] 그 인스턴스가 떠 있는 채로 `start-app.sh`를 한 번 더 부르면 0이 아닌 코드로 끝나고, `pgdata-embedded`가 지워지지 않으며 기존 인스턴스의 status가 여전히 200이다
- [ ] 끝나면 `stop-app.sh`로 정리하고 8778 리슨이 없다
- [ ] 변경 설명서(`docs/tasks/results/T-016-start-app-중복-기동-거부.md`)가 있다(작은 작업 — "그림 없음 — 사유" 허용)

## 변경 설명서
개발 에이전트가 작성: [results/T-016-start-app-중복-기동-거부.md](./results/T-016-start-app-중복-기동-거부.md)

## 검증 명령
```bash
bash -n e2e/scripts/start-app.sh
(bash e2e/scripts/start-app.sh > /dev/null 2>&1 &) ; for i in $(seq 1 60); do c=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8778/api/system/status); [ "$c" = 200 ] && break; sleep 2; done; echo "first=$c"
bash e2e/scripts/start-app.sh ; echo "second_rc=$?"
curl -s -o /dev/null -w "after=%{http_code}\n" http://127.0.0.1:8778/api/system/status
bash e2e/scripts/stop-app.sh
```

## 검증 증거
개발 에이전트가 작성.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성(T-006 검수 중 발견한 재발 방지) |

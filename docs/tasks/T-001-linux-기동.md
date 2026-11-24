# T-001. Linux 기동 지원

```
id: T-001
담당: backend-dev (backend-dev)
상태: 대기
depends: []
병렬: 가능            # T-004와 동시 가능
충돌: [T-002]         # admin/SystemController.java
관련 제안: -
Redmine 일감: 없음
Figma 반영: 아니오
유형: 구현
QA 확인: 아니오       # Windows(QA 환경)에서의 동작은 바뀌지 않는다. Linux 동작은 TC-001 스크립트로 서버에서 확인
DB 접속: 없음
대상: -
대상 저장소: -
클러스터 접속: 없음
수집 자료: -
작성: 2026-11-24
```

## 목적
같은 jar가 Ubuntu 24.04(그래픽 없음, 비루트 계정, systemd 아래)에서 오류 없이 뜨고 에이전트 실행 준비가 된다.

## 근거
- 요구사항: `FR-101`, `FR-102`, `FR-106` ([01 §4.1](../01-requirements.md)), 제약 `CON-01`·`CON-05`·`CON-07`
- 설계: [02 §4.2 D-2](../02-architecture.md) (실행 형태·PATH), [02 §5](../02-architecture.md) (서버에서의 기동 확인 원칙), [02 §4.2 D-8](../02-architecture.md) (Windows 전용 요소는 이번에 지우지 않는다)
- 서버 접속·환경: [04 §9](../04-project-structure.md) ("서버 접속" 행), 환경변수 [04 §7](../04-project-structure.md)
- 테스트: `TC-001`, `TC-005` ([07 §3, §4](../07-test-plan.md))
- 코딩 규약: [04 §4](../04-project-structure.md), 저장소 루트 `CLAUDE.md`("실행 파일 배포 · 기동 준비", "이 환경에서 확인된 함정")

## 관련 코드 경로
- 수정 후보(OS 의존 코드가 있는 파일 — 전부 읽고 Linux에서 깨지는 것만 고친다):
  `admin/SystemController.java`, `agent/claude/ClaudeCliAgent.java`, `boot/Autostart.java`, `boot/Launcher.java`, `config/ClaudeCliLocator.java`, `config/ClaudeProperties.java`, `gitlab/VerifyService.java`, `maintain/MaintainPrompts.java`, `mcp/McpConfigService.java`, `platform/Os.java`, `preview/PreviewService.java`, `ops/Doctor.java`
- 신규: `deploy/smoke/server-smoke.sh`
- 참고: `boot/Bootstrap.java`(`.env`·홈 디렉터리 결정 방식 — `DEV_AGENT_HOME`이 `java -jar` 직접 실행에서도 적용되는지 확인), `boot/EmbeddedDb.java`(내장 DB 포트), `build.gradle`(내장 DB 바이너리 의존성)

## 범위
- 위 파일의 OS 분기를 조사해 **파일별로 "Linux에서의 동작 / 고친 것 / 고치지 않은 이유"** 표를 변경 설명서에 쓴다.
- Linux에서 기동과 에이전트 실행 준비가 실패하지 않게 고친다. 최소한 다음을 확인한다.
  - 그래픽 없는 환경에서 트레이·자동 실행 코드가 실행되지 않는다(`--launch` 없이 뜨는 경로).
  - 에이전트 실행 전의 심 설치(`ClaudeCliAgent.installShims`)가 Linux에서 실패하지 않는다: 프리뷰가 꺼져 있으면 프리뷰용 심을 쓰지 않고, 쓰는 심은 실행 권한이 있는 셸 스크립트다. **사용자 홈의 `~/bin`에 기존 파일이 있으면 덮어쓰지 않는지** 확인한다(서버는 사용자의 실제 계정이다).
  - 검증 단계의 빌드 명령 선택(`VerifyService`)이 Linux에서 `./gradlew`·`mvn`을 고른다.
  - MCP 설정의 `cmd /c npx` 감싸기가 Windows에서만 적용된다.
  - 프롬프트·힌트의 Windows 전용 안내(`gradlew.bat`, PowerShell 금지 등)가 Linux에서 틀린 지시를 하지 않는다.
- 실행 방식 `systemd` 추가(FR-102): 환경변수 `DEV_AGENT_RUN_MODE=systemd`이면 실행 방식이 `systemd`, 감시 중으로 보고 재시작을 허용한다. 재시작은 종료 코드 10, 종료는 0. `run.cmd` 방식의 stop 플래그 파일은 쓰지 않는다. 기존 방식(`launcher`·`run.cmd`·`direct`) 동작은 그대로.
- `ops/Doctor`: Linux에서 Claude CLI·git·DB 항목이 맞게 나오고 Windows 전용 항목은 실패로 표시하지 않는다. 고치는 방법 문구를 OS에 맞춘다.
- `deploy/smoke/server-smoke.sh` — **서버에서 격리 실행하는 기동 확인**(개발 PC에 Docker가 없다).
  - 접속 대상은 환경변수 `DEV_AGENT_SSH`로만 받는다. 없으면 `SMOKE SKIP: DEV_AGENT_SSH 없음`을 찍고 0이 아닌 코드로 끝낸다. 주소·계정을 스크립트·문서·로그 출력에 적지 않는다.
  - 원격 명령은 **로그인 셸**(`bash -lc`)로 실행한다. 서버의 java·claude는 로그인 셸 PATH에서만 보인다.
  - 순서: ① 서버에 새 임시 격리 디렉터리(`mktemp -d`)를 만든다 ② jar를 올린다 ③ 포트 18777과 내장 DB 포트가 비어 있는지 확인(쓰이고 있으면 아무것도 띄우지 않고 `SMOKE FAIL`) ④ 그 디렉터리를 작업 디렉터리 겸 `DEV_AGENT_HOME`으로, `ADMIN_PORT=18777`·`PREVIEW_ENABLED=false`·`MAINTAIN_ENABLED=false`·`GITLAB_ENABLED=false`·`DB_URL` 없음(내장 DB)·`GITLAB_WORK_DIR`도 그 디렉터리 안으로 해서 백그라운드 실행 ⑤ 제한 시간(첫 기동은 내장 DB 준비로 1분 이상) 안에 `127.0.0.1:18777`의 `/api/system/status`·`/api/system/doctor`가 200인지, Doctor의 Claude CLI·git 항목, 로그의 기동 실패 예외 유무 확인 ⑥ `POST /api/system/shutdown`으로 내리고, 앱과 내장 DB 프로세스가 사라졌는지 확인(남았으면 **자기가 띄운 PID만** 종료) ⑦ 격리 디렉터리 삭제.
  - 성공·실패와 무관하게 ⑥⑦ 정리가 실행되게 한다(trap). 성공 시 마지막 줄 `SMOKE PASS`, 실패 시 `SMOKE FAIL: <사유>`와 0이 아닌 종료 코드.
  - **건드리지 않는 것**: 기존 서비스·컨테이너·공유 PostgreSQL, 8777 포트, 사용자의 `~/.claude` 설정·자격증명(읽히기만 한다), systemd, 방화벽, `tailscale`. `sudo`·`docker` 명령을 쓰지 않는다. Claude CLI를 호출하는 동작(에이전트 실행, 연결 시험)을 일으키지 않는다.
- 단위 테스트: 실행 방식 판별(TC-005), 이번에 고친 OS 분기.

## 범위 밖
- Windows 전용 요소의 삭제(T-009), 로컬 프리뷰를 Linux에서 돌게 만드는 일
- 접근 제어(`SystemController.foreign`)의 변경 — T-002
- systemd unit·절차서·정식 설치 — T-003
- 사내 기능·GitLab 관련 코드
- 버전 올리기(T-002에서 1.13.0)

## 완료 조건
- [ ] `TC-005`가 단위 테스트로 존재하고 통과한다
- [ ] `deploy/smoke/server-smoke.sh`가 `SMOKE PASS`로 끝난다(`TC-001`)
- [ ] 스크립트에 주소·계정이 없고 `sudo`·`docker`를 쓰지 않으며, 실패 경로에서도 정리가 실행된다
- [ ] 스크립트가 실행 전후의 `~/bin` 파일 목록을 비교해 **기동만으로 사용자 홈에 심이 생기거나 바뀌지 않음**을 확인한다(지금 코드는 기동 때 `~/bin`에 `preview`·`jscheck`·`jspcheck`·`mvn` 심을 쓴다 — 프리뷰가 꺼진 Linux에서는 쓰지 않게 고친 뒤에 서버에서 실행한다)
- [ ] 기존 테스트 전부 통과(`gradlew test` 실패 0)
- [ ] 변경 설명서에 OS 의존 파일 12개의 조사 표가 있다
- [ ] Windows에서의 기존 실행 방식 동작을 바꾸지 않았다(바꿨으면 응답에 보고)
- [ ] 기동 확인으로 보지 못한 항목(실제 에이전트 실행, systemd 아래의 PATH)을 "서버에서 확인할 것" 목록으로 변경 설명서에 적었다
- [ ] 명세와 구현이 다르면 응답에 보고했다
- [ ] 변경 설명서(`docs/tasks/results/T-001-linux-기동.md`)가 그림·설명과 함께 있다

`DEV_AGENT_SSH`가 설정되지 않은 세션이면 스크립트는 작성하되 실행하지 못한 사실을 검증 증거에 적는다. 그 경우 PM은 `보류`로 두고, 환경변수가 설정된 세션(메인 세션 또는 사용자)이 실행한 결과를 받아 판정한다.

## 변경 설명서
개발 에이전트가 작성: [results/T-001-linux-기동.md](./results/T-001-linux-기동.md)

## 검증 명령
```bash
./gradlew test bootJar
bash deploy/smoke/server-smoke.sh
```

## 검증 증거
개발 에이전트가 작성: 실행한 명령, 결과 요약(통과/실패 수), 일시.

## 이력
| 일자 | 상태 | 내용 |
|---|---|---|
| 2026-11-24 | 대기 | PM 작성 |
| 2026-11-24 | 대기 | PM 수정(착수 전): Docker 기반 smoke를 서버 격리 실행(`server-smoke.sh`, SSH·로그인 셸)으로 변경 — OI-12 결정 |

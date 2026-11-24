# 서버 설치 절차서 (Dev Agent, 개인 서버)

서버에 Dev Agent 를 올려 Tailscale 기기(tailnet 의 PC·모바일)에서 여는 절차다. **이 절차는 전부 사람이 서버에서 실행한다.** 이 저장소의 어떤 도구도 서버에 접속하지 않는다.

- 이 문서의 `<…>` 는 자리표시자다. 실제 계정 이름·주소·경로·컨테이너 이름·비밀번호는 저장소에 적지 말고 서버에서만 쓴다.
- 표기: **확인 필요** = 이 저장소에서 확인하지 못한 것. 옆의 방법으로 서버에서 확인한다. 추측을 사실로 쓰지 않았다.
- 구성: 앱은 `127.0.0.1:8777` 에만 바인딩, 공유 PostgreSQL 은 `127.0.0.1:5432`, tailnet 에는 `tailscale serve` 의 HTTPS **8443** 하나만 연다.

| 자리표시자 | 뜻 |
|---|---|
| `<SERVICE_USER>` | Claude CLI 로그인이 돼 있는 기존 일반 계정(root 아님) |
| `<APP_HOME>` | 앱 홈 절대 경로(`.env`·jar·`logs/`) |
| `<JAVA_HOME>`, `<TOOLS_PATH>` | JDK 경로, claude·git·node·gradle 디렉터리를 `:` 로 이은 값 |
| `<DB_CONTAINER>` | 공유 PostgreSQL 컨테이너 이름 |
| `<ADMIN_ROLE>` | 공유 인스턴스의 관리 role(role·DB 를 만들 수 있는 것) |
| `<DB_NAME>`, `<DB_USER>`, `<DB_PASS>` | 전용 DB·role 이름과 비밀번호 |
| `<MagicDNS 이름>` | 이 서버의 tailnet MagicDNS 이름 |

## 1. 사전 확인 (로그인 셸에서)

서버의 도구(java·claude·git·node·gradle)는 사용자 홈 아래에 있고 **로그인 셸 PATH 에서만 보인다.** systemd 는 그 PATH 를 쓰지 않으므로 경로를 알아 두었다가 unit 에 옮겨 적는다.

```bash
# <SERVICE_USER> 로 로그인한 셸에서
command -v java claude git node gradle        # 각 실행 파일의 경로
java -version                                  # 17 이상
claude --version
git --version
claude                                         # 로그인 상태 확인(대화창이 뜨면 로그인된 것. 종료는 /exit)
readlink -f "$(command -v java)"               # → <JAVA_HOME>/bin/java 의 <JAVA_HOME> 을 알아낸다
```

- `<TOOLS_PATH>` = 위 `command -v` 결과에서 파일 이름을 뺀 디렉터리들(중복 제거)을 `:` 로 이은 값. 예: `claude` 가 `<홈>/.local/bin/claude` 면 `<홈>/.local/bin`.
- 앱의 `ClaudeCliLocator` 는 PATH → npm 전역 → `~/.local/bin` → Homebrew 순으로 찾는다. nvm 등 다른 위치면 못 찾을 수 있다(확인 필요: 서버의 claude 위치). 그런 경우 `.env` 의 `CLAUDE_EXE_COMMAND` 에 절대 경로를 적는다.
- `claude --version` 이 되는 것과 에이전트가 실제로 도는 것은 다르다. 실제 실행은 §8 의 마지막 항목에서 확인한다.

## 2. 사용자가 할 일 — 공유 PostgreSQL 접속 경로 (OI-18)

공유 PostgreSQL 컨테이너는 호스트 포트를 공개하지 않는다. 앱(호스트의 JVM)이 붙으려면 그 DB 의 compose 에 `127.0.0.1` 한정 공개를 더해야 한다. **이 절차서와 저장소는 그 compose 파일을 고치지 않는다 — 사용자가 직접 한다.**

```yaml
# 그 DB 서비스의 compose 항목에 추가
    ports:
      - "127.0.0.1:5432:5432"
```

- **컨테이너를 다시 만들어야 적용된다**(`docker compose up -d <서비스>`). 이때 **그 DB 를 쓰는 다른 서비스들의 연결이 순간 끊긴다.** 서비스 정리 때 함께 하고, 재연결 시도가 없는 서비스는 다시 띄워야 할 수 있다.
- 컨테이너의 브리지 IP(`172.x.x.x`)로 붙는 방법은 권하지 않는다: 컨테이너를 다시 만들면 IP 가 바뀔 수 있고, 같은 브리지의 다른 컨테이너에도 열려 있는 길이 된다.
- `0.0.0.0` 이나 `5432:5432`(주소 생략)로 공개하지 않는다. 반드시 `127.0.0.1:` 을 붙인다.
- 적용 뒤 확인:

```bash
ss -ltn | grep ':5432'     # 127.0.0.1:5432 만 보여야 한다. 0.0.0.0:5432 / [::]:5432 가 보이면 잘못 공개된 것
```

## 3. 전용 DB·role 만들기

서버에 psql 이 없으므로 DB 컨테이너 안의 psql 로 `deploy/sql/create-db.sql` 을 실행한다. 이 SQL 은 **전용 role(로그인만 가능, 슈퍼유저·DB 생성·role 생성 권한 없음, 연결 10개 제한)과 그 role 이 소유한 전용 DB** 만 만들고, 전용 DB 의 PUBLIC 접속 권한을 거둔다. 다른 DB·role 은 건드리지 않고 테이블도 만들지 않는다.

1. 비밀번호를 정한다(`<DB_PASS>`). 충분히 길고 무작위로, 비밀번호 관리 도구에 보관한다. 따옴표(`'`)·역슬래시는 쓰지 않는다.
2. 실행:

```bash
docker exec -i <DB_CONTAINER> psql -U <ADMIN_ROLE> -d postgres \
  -v dbname=<DB_NAME> -v dbuser=<DB_USER> -v dbpass='<DB_PASS>' < deploy/sql/create-db.sql
```

- `-v dbpass=` 값은 실행 중 프로세스 목록과 셸 기록에 남는다. 혼자 쓰는 서버면 실행 뒤 `history -d` 로 지우거나 `HISTCONTROL=ignorespace` 로 앞에 공백을 두고 실행한다.
- 관리 role 이 비밀번호를 요구하면 `docker exec -e PGPASSWORD=… ` 를 쓴다(확인 필요: 그 인스턴스의 `pg_hba` 설정).
- 같은 이름이 이미 있으면 `ERROR … already exists` 로 멈춘다(`ON_ERROR_STOP`). 이름을 바꿔 다시 실행하거나, 전용 DB 가 이전 시도의 잔여물이면 사용자가 직접 정리한다 — 이 SQL 은 삭제 문을 포함하지 않는다.
- 확인: `docker exec <DB_CONTAINER> psql -U <ADMIN_ROLE> -d postgres -c "\du <DB_USER>"` → 속성에 `Superuser`·`Create DB`·`Create role` 이 없고 `10 connections` 가 보인다.

## 4. 앱 홈과 `.env`

```bash
mkdir -p <APP_HOME> && chmod 700 <APP_HOME>
cp deploy/env.server.example <APP_HOME>/.env      # 또는 scp 로 올린다
chmod 600 <APP_HOME>/.env
$EDITOR <APP_HOME>/.env                            # 자리표시자를 모두 실제 값으로
```

- **줄바꿈은 LF 로 저장한다.** Windows 편집기에서 CRLF 로 저장해 올리면 값 끝에 CR 이 붙는 키가 생긴다(`DEV_AGENT_RUN_MODE` 는 견디지만 다른 값은 아닐 수 있다). 확인: `file <APP_HOME>/.env` 에 `CRLF` 가 나오면 `sed -i 's/\r$//' <APP_HOME>/.env`.
- 키 이름·설명은 `deploy/env.server.example` 에 있다. 꼭 채울 것: `ACCESS_ALLOWED_ORIGINS`(`https://<MagicDNS 이름>:8443`), `DB_URL`(`jdbc:postgresql://127.0.0.1:5432/<DB_NAME>`)·`DB_USERNAME`·`DB_PASSWORD`, `GITLAB_WORK_DIR`(앱 홈 **밖**), GitLab 항목.
- `DB_URL` 을 비우면 앱이 내장 PostgreSQL 로 떠 버려 공유 DB 에 붙지 않는다. 반드시 채운다.
- `PREVIEW_ENABLED=false`, `MAINTAIN_ENABLED=false` 를 유지한다(플랫폼 관리 사용 여부는 OI-14 미정).
- `.env` 는 비밀값이 들어 있으니 git·메신저·로그에 올리지 않는다.

## 5. jar 올리기

개발 PC 에서 jar 를 만들어 올린다. 서버에서 빌드하려면 Java 17 toolchain 이 필요하다.

```bash
# 개발 PC (JDK 17)
./gradlew test bootJar                            # → build/libs/dev-agent.jar
scp build/libs/dev-agent.jar <SERVICE_USER>@<서버>:<APP_HOME>/dev-agent.jar
```

## 6. unit 설치·시작·로그 보기

```bash
# 서버에서 (sudo 가능한 계정)
sudo cp deploy/systemd/dev-agent.service /etc/systemd/system/dev-agent.service
sudo sed -i -e 's|<SERVICE_USER>|…|g' -e 's|<APP_HOME>|…|g' -e 's|<JAVA_HOME>|…|g' -e 's|<TOOLS_PATH>|…|g' /etc/systemd/system/dev-agent.service
#   (또는 편집기로 네 자리표시자를 직접 바꾼다. …은 실제 값)
grep -n '<' /etc/systemd/system/dev-agent.service     # 주석 줄 외에 남은 자리표시자가 없어야 한다

systemd-analyze verify /etc/systemd/system/dev-agent.service    # 문법 확인. 경고가 없어야 한다
sudo systemctl daemon-reload
sudo systemctl enable --now dev-agent
systemctl status dev-agent --no-pager
journalctl -u dev-agent -f                            # 실시간 로그(Ctrl+C 로 빠져나옴)
tail -f <APP_HOME>/logs/dev-agent.log                 # 앱 로그
```

- unit 의 핵심: `User`(비루트)·`WorkingDirectory`·`PATH`·`JAVA_HOME`·`DEV_AGENT_RUN_MODE=systemd`·`ExecStart`(java 절대 경로)·`Restart=on-failure`(종료 코드 0 은 다시 안 띄우고 10 은 다시 띄움)·`After=docker.service`.
- `java -jar` 직접 실행은 `DEV_AGENT_HOME` 을 보지 않고 작업 디렉터리를 홈으로 쓴다. 그래서 `WorkingDirectory` 가 `<APP_HOME>` 이어야 `.env` 를 읽는다.
- 첫 기동 때 앱이 전용 DB 에 테이블을 만든다(수십 초). `curl -s http://127.0.0.1:8777/api/system/status` 에 JSON 이 나오면 떴다.
- 재기동이 반복돼 멈췄다면(3분에 5회): 원인을 로그에서 고친 뒤 `sudo systemctl reset-failed dev-agent && sudo systemctl start dev-agent`.

## 7. tailscale serve (HTTPS 8443)

```bash
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8777
tailscale serve status                                # 8443 → 127.0.0.1:8777 이 보여야 한다
# 끄기
sudo tailscale serve --https=8443 off                 # 또는: sudo tailscale serve reset  (serve 설정 전부 지움 — 다른 serve 가 있으면 쓰지 않는다)
```

- 명령 형태는 Tailscale 버전에 따라 다르다(확인 필요: `tailscale version`, `tailscale serve --help`). 위는 최근 버전 기준이다.
- 접속 주소: `https://<MagicDNS 이름>:8443`. 이 값이 `.env` 의 `ACCESS_ALLOWED_ORIGINS` 와 같아야 한다.
- **tailnet 의 HTTPS 인증서가 꺼져 있으면**: `tailscale serve` 가 "HTTPS 를 켜라" 는 안내 문구와 관리 콘솔 주소를 출력하고 serve 가 만들어지지 않거나, 브라우저에서 인증서 오류가 난다. 켜는 곳: Tailscale 관리 콘솔 → DNS → "HTTPS Certificates" 활성화(MagicDNS 가 켜져 있어야 한다). 켠 뒤 위 명령을 다시 실행한다.
- 공개 인터넷에 여는 `tailscale funnel` 은 쓰지 않는다. 이 앱은 로그인이 없다 — tailnet 이 곧 접근 제어다.
- serve 의 대상은 `127.0.0.1:8777` 뿐이다. 이 명령이 서버 방화벽·Tailscale ACL 을 바꾸지 않는다(ACL 은 사용자가 따로 관리).

## 8. 확인 항목

실행 결과를 `[ ]` 에 적어 둔다. 실패하면 §11.

**네트워크 노출**
- [ ] 8777 이 `127.0.0.1` 에만 열려 있다: `ss -ltn | grep ':8777'` → `127.0.0.1:8777` 만(`0.0.0.0` 이면 실패).
- [ ] DB 포트가 `127.0.0.1` 에만 열려 있다: `ss -ltn | grep ':5432'`.
- [ ] tailnet 밖(예: 이동통신망의 휴대폰, Tailscale 끔)에서 `https://<MagicDNS 이름>:8443` 이 열리지 않는다.

**공유 인스턴스 보호**

근거(코드 조사, 이 저장소의 해당 시점 코드): `Bootstrap.initSchema` 는 **접속한 DB 의 `public.bot_setting`** 존재만 보고(`to_regclass('public.bot_setting')`), 없을 때만 `db/schema.sql` 을 실행하며 DROP 문은 걸러 낸다(`splitStatements`). `schema.sql` 은 `CREATE TABLE` 문뿐이다. 매퍼의 `ensureTable()` 21개는 `CREATE TABLE/INDEX IF NOT EXISTS`, 컬럼 추가는 `ALTER TABLE … ADD COLUMN IF NOT EXISTS`(`IssueSessionMapper`, `ResearchJobMapper`) 뿐이고, `CREATE DATABASE/ROLE/SCHEMA/EXTENSION`·`GRANT` 는 코드에 없다. 즉 앱은 `DB_URL` 이 가리키는 전용 DB 안의 테이블만 만들고 바꾼다. 이 내용은 T-003 작업에서 코드를 다시 읽어 확인했다.

- [ ] 첫 기동 뒤 전용 DB 에만 테이블이 생겼다: `docker exec <DB_CONTAINER> psql -U <ADMIN_ROLE> -d <DB_NAME> -c '\dt'` 에 `bot_setting` 등이 있다.
- [ ] 다른 DB 의 테이블 수와 role 목록이 첫 기동 전과 같다: 기동 **전에** 기록해 둔다 — `docker exec <DB_CONTAINER> psql -U <ADMIN_ROLE> -d postgres -Atc "select datname from pg_database"` 로 DB 목록을 얻어, 각 DB 마다 `docker exec <DB_CONTAINER> psql -U <ADMIN_ROLE> -d <그 DB> -Atc "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')"` 와 `docker exec <DB_CONTAINER> psql -U <ADMIN_ROLE> -d postgres -Atc "select rolname from pg_roles order by 1"`. 기동 뒤 같은 명령 결과와 비교.
- [ ] 전용 role 로 다른 DB 의 데이터에 접근되지 않는다: `docker exec -e PGPASSWORD=<DB_PASS> <DB_CONTAINER> psql -h 127.0.0.1 -U <DB_USER> -d <다른 DB> -c '\dt'`. **확인 필요**: PostgreSQL 은 기본적으로 DB 의 PUBLIC 에 `CONNECT` 를 주므로 *접속 자체*는 될 수 있다. 접속되더라도 그 DB 의 테이블에 대한 권한이 없어 `select` 가 `permission denied` 여야 한다(그 DB 의 소유자가 PUBLIC 에 권한을 주지 않았다면). 접속 자체를 막으려면 그 DB 마다 `REVOKE CONNECT ON DATABASE <다른 DB> FROM PUBLIC;` 가 필요한데 그 DB 를 쓰는 서비스에 영향이 있고 이 저장소의 SQL 범위 밖이라 **사용자가 판단해서** 한다.
- [ ] Dev Agent 의 연결 수가 5 이하다(앱 풀 `maximum-pool-size: 5`): `docker exec <DB_CONTAINER> psql -U <ADMIN_ROLE> -d postgres -Atc "select count(*) from pg_stat_activity where usename='<DB_USER>'"` → 5 이하(기동 직후 점검 연결이 겹치면 순간 6 이 될 수 있다. 계속 5 를 넘으면 보고).

**화면과 기능**
- [ ] tailnet PC 와 모바일에서 `https://<MagicDNS 이름>:8443` 의 화면이 열리고, 에이전트 화면의 **실시간 로그(SSE)** 가 갱신된다.
- [ ] 리서치 탭에서 **이미지 첨부**(붙여넣기·끌어놓기)가 올라간다.

**접근 제어(T-002)**
- [ ] `ACCESS_ALLOWED_ORIGINS` 를 채우고 재기동한 상태에서, serve 주소로 연 화면의 상태 변경(스킬 등록·삭제 등)·SSE·파일 올리기가 403 없이 동작한다.
- [ ] 다른 Origin 의 POST 는 403: `curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Origin: https://other.invalid' http://127.0.0.1:8777/api/skills/defaults` → `403`. 접근 제어 필터는 `POST /api/**` 전부를 확인한다(T-002 설명서 엔드포인트 조사 표). 이 요청은 403 이 정상이라 상태가 바뀌지 않는다. 같은 명령에서 `-H 'Origin: …'` 줄을 `-H 'Origin: <ACCESS_ALLOWED_ORIGINS 의 값>'` 으로 바꾸면 허용 Origin 은 403 이 아니어야 한다(이때는 기본 스킬 중 없는 것이 다시 들어간다).
- [ ] **`tailscale serve` 뒤에서 앱이 실제로 받는 `Host`·`Origin` 헤더 값** 확인(OI-21 결정 자료 — 앱 코드 변경 없음, Host 검사 자체는 이 작업 범위 밖). 방법: 실제 serve(8443)는 두고 **다른 serve 포트(8444)에 임시 수신 서버**를 건다.

```bash
# 서버: 헤더를 찍는 임시 수신 서버(127.0.0.1:8999)
python3 - <<'PY' &
import http.server
class H(http.server.BaseHTTPRequestHandler):
    def _r(self):
        print("METHOD", self.command, "PATH", self.path)
        for k in ("Host", "Origin", "Referer", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto"):
            print(" ", k, "=", self.headers.get(k))
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
    do_GET = do_POST = _r
http.server.HTTPServer(("127.0.0.1", 8999), H).serve_forever()
PY
sudo tailscale serve --bg --https=8444 http://127.0.0.1:8999
# tailnet 기기의 브라우저 콘솔: fetch('https://<MagicDNS 이름>:8444/x', {method:'POST'})  (GET 은 주소창으로도 가능)
# 서버의 위 python 출력을 읽는다. 끝나면 정리:
sudo tailscale serve --https=8444 off ; kill %1
```

  결과 기록 칸(서버에서 채운다): `Host` = ____ , `Origin` = ____ (포트 포함 여부: ____ ), `X-Forwarded-*` = ____ . 이 값이 §4 의 `ACCESS_ALLOWED_ORIGINS` 형태와 같은지 비교한다 — 다르면 `.env` 를 그 값으로 맞춘다. (확인 필요: python3 가 서버에 있는지. 없으면 `nc -l 127.0.0.1 8999` 로 원문 요청을 읽는다.)

**운영 동작**
- [ ] 화면의 **「재시작」**: 눌렀을 때 앱이 종료 코드 10 으로 끝나고 systemd 가 다시 띄운다(`journalctl -u dev-agent` 에 재기동 기록, `curl http://127.0.0.1:8777/api/system/status` 가 다시 응답). 이슈 세션이 작업 중이면 거부되는 것이 정상이다.
- [ ] 화면의 **「종료」**: 종료 코드 0 으로 끝나고 **다시 뜨지 않는다**(`systemctl status dev-agent` 가 `inactive`). 다시 시작은 `sudo systemctl start dev-agent`.
- [ ] 서버 **재부팅** 뒤 자동 기동: `sudo reboot` 후 `systemctl status dev-agent` 가 active. DB 컨테이너보다 먼저 떠서 접속에 실패하면 앱이 non-zero 로 종료하고 `Restart=on-failure`(5초 간격, 3분에 5회까지)로 다시 붙는지 `journalctl -u dev-agent -b` 로 본다(확인 필요: DB 컨테이너가 5회 안에 안 뜨면 unit 이 멈추므로 `reset-failed` 가 필요할 수 있다).
- [ ] **systemd 아래에서 에이전트 1회 실행**: 대상 프로젝트에서 이슈 하나(또는 리서치 질의 하나)를 돌려, Claude CLI 가 git·빌드 도구(gradle/mvn/node)를 찾는지, 로그인 자격증명이 갱신되는지(`~/.claude/.credentials.json` 수정 시각, 세션 중 인증 오류 없음) 본다. 도구를 못 찾으면 unit 의 `PATH` 에 그 디렉터리를 더한다.

## 9. 갱신(새 jar)과 되돌리기

```bash
# 갱신: 이슈 세션이 대기 상태(DESIGN_WAIT/REVIEW_WAIT)일 때 한다 — 작업 중 재시작하면 그 세션은 FAILED
cp <APP_HOME>/dev-agent.jar <APP_HOME>/dev-agent.jar.prev       # 현재 jar 보관
scp build/libs/dev-agent.jar <SERVICE_USER>@<서버>:<APP_HOME>/dev-agent.jar.new
mv <APP_HOME>/dev-agent.jar.new <APP_HOME>/dev-agent.jar
sudo systemctl restart dev-agent

# 되돌리기
mv <APP_HOME>/dev-agent.jar.prev <APP_HOME>/dev-agent.jar
sudo systemctl restart dev-agent
```

- 새 버전이 테이블이나 컬럼을 추가했다면(앱이 `IF NOT EXISTS`/`ADD COLUMN IF NOT EXISTS` 로 더함) 옛 jar 는 그 열을 무시하고 대개 그대로 돈다(확인 필요: 버전별로). 안전하게 하려면 갱신 전에 §10 의 백업을 먼저 한다.
- 버전은 상단바 또는 `curl -s http://127.0.0.1:8777/api/system/status` 에서 본다.

## 10. 수동 백업과 복원 (전용 DB 만)

공유 인스턴스 전체(`pg_dumpall`)는 받지 않는다. 전용 DB 하나만 대상으로 한다.

```bash
# 백업(커스텀 형식)
docker exec -e PGPASSWORD=<DB_PASS> <DB_CONTAINER> pg_dump -h 127.0.0.1 -U <DB_USER> -d <DB_NAME> -Fc > dev-agent-$(date +%Y%m%d).dump
chmod 600 dev-agent-*.dump                       # 덤프에도 설정·이슈 내용이 들어 있다

# 복원(앱을 먼저 멈춘다)
sudo systemctl stop dev-agent
docker exec -i -e PGPASSWORD=<DB_PASS> <DB_CONTAINER> pg_restore -h 127.0.0.1 -U <DB_USER> -d <DB_NAME> --clean --if-exists --no-owner < dev-agent-YYYYMMDD.dump
sudo systemctl start dev-agent
```

- `--clean` 은 **`-d` 로 지정한 전용 DB 안의** 객체만 지운다. `-d` 를 다른 DB 로 쓰지 않도록 이름을 두 번 확인한다.
- 처음부터 다시 만들려면(데이터 삭제) 사용자가 판단해서 전용 DB 를 직접 비운다. 이 저장소의 SQL 은 삭제 문을 넣지 않았다.
- 덤프 파일은 서버 밖 안전한 곳으로 옮긴다(확인 필요: 보관 위치·주기는 사용자가 정한다. 자동화는 이 작업 범위 밖).

## 11. 문제 해결

| 증상 | 확인 | 조치 |
|---|---|---|
| 기동 직후 반복 재시작 후 멈춤 | `journalctl -u dev-agent -n 100 --no-pager`, `<APP_HOME>/logs/dev-agent.log` 끝의 `Caused by` | 원인 수정 → `sudo systemctl reset-failed dev-agent && sudo systemctl start dev-agent` |
| `Connection refused` (DB) | `ss -ltn \| grep 5432`, `docker ps` | §2 의 포트 공개 적용 여부, DB 컨테이너 기동 여부 |
| `password authentication failed` | `.env` 의 `DB_USERNAME`·`DB_PASSWORD`, 값 끝 공백·CR | §4 LF 저장 확인 |
| 앱이 내장 DB(54329)로 뜸 | `.env` 의 `DB_URL` 이 비었거나 키 이름 오타 | `DB_URL` 채우고 재시작 |
| 화면은 열리는데 저장·등록이 403 | `.env` 의 `ACCESS_ALLOWED_ORIGINS` 값, 재기동 여부 | §8 의 Host·Origin 확인으로 실제 Origin 에 맞춘다 |
| `claude: command not found` / 에이전트가 도구를 못 찾음 | unit 의 `PATH`, `CLAUDE_EXE_COMMAND` | §1 의 경로를 `PATH` 에 더하고 `daemon-reload` + `restart` |
| 「재시작」을 눌렀더니 거부/안 뜸 | `curl -s http://127.0.0.1:8777/api/system/status` 의 `runMode` 가 `systemd` 인가 | `direct` 면 unit 의 `DEV_AGENT_RUN_MODE=systemd` 누락 |
| 「종료」 뒤에 다시 뜸 | unit 의 `Restart=` 값 | `Restart=always` 면 `on-failure` 로 |
| `tailscale serve` 가 HTTPS 를 켜라고 함 / 인증서 오류 | `tailscale serve status` | §7 의 HTTPS Certificates 활성화 |
| serve 주소로 열리지 않음 | `tailscale serve status`, 기기가 같은 tailnet 인지, ACL | 앱이 `127.0.0.1:8777` 에서 응답하는지 먼저 확인 |

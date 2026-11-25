# 서버 설치 절차서 (Dev Agent, 개인 서버)

서버에 Dev Agent 를 올려 Tailscale 기기(tailnet 의 PC·모바일)에서 여는 절차다. **이 절차는 전부 사람이 서버에서 실행한다.** 이 저장소의 어떤 도구도 서버에 접속하지 않는다.

- 이 문서의 `<…>` 는 자리표시자다. 실제 계정 이름·주소·경로·컨테이너 이름·비밀번호는 저장소에 적지 말고 서버에서만 쓴다.
- 표기: **확인 필요** = 이 저장소에서 확인하지 못한 것. 옆의 방법으로 서버에서 확인한다. 추측을 사실로 쓰지 않았다.
- 구성: 앱은 `127.0.0.1:8777` 에만 바인딩, 공유 PostgreSQL 은 호스트 포트를 공개하지 않고(앱이 기동 때 컨테이너 주소를 조회해 붙는다), tailnet 에는 `tailscale serve` 의 HTTPS **8443** 하나만 연다.

| 자리표시자 | 뜻 |
|---|---|
| `<SERVICE_USER>` | Claude CLI 로그인이 돼 있는 기존 일반 계정(root 아님) |
| `<APP_HOME>` | 앱 홈 절대 경로(`.env`·jar·`logs/`) |
| `<JAVA_HOME>` | JDK **21** 설치 경로. 이유: 검증 빌드·수정 세션이 봇 JVM 의 JDK 와 PATH 도구를 쓰므로 대상 프로젝트가 요구하는 가장 높은 JDK(21)에 맞춘다 |
| `<TOOLS_PATH>` | claude·git·node·gradle 디렉터리를 `:` 로 이은 값 |
| `<DB_CONTAINER>` | 공유 PostgreSQL 컨테이너 이름 |
| `<DB_NETWORK>` | DB 컨테이너가 네트워크 여러 개에 붙어 있을 때 고를 네트워크 이름. 하나뿐이면 `-` |
| `<ADMIN_ROLE>` | 공유 인스턴스의 관리 role(role·DB 를 만들 수 있는 것) |
| `<DB_NAME>`, `<DB_USER>`, `<DB_PASS>` | 전용 DB·role 이름과 비밀번호 |
| `<MagicDNS 이름>` | 이 서버의 tailnet MagicDNS 이름 |

## 1. 사전 확인 (로그인 셸에서)

서버의 도구(java·claude·git·node·gradle)는 사용자 홈 아래에 있고 **로그인 셸 PATH 에서만 보인다.** systemd 는 그 PATH 를 쓰지 않으므로 경로를 알아 두었다가 unit 에 옮겨 적는다.

```bash
# <SERVICE_USER> 로 로그인한 셸에서
command -v java claude git node gradle        # 각 실행 파일의 경로
java -version                                  # 21 이어야 한다(JDK 21 고정)
claude --version
git --version
claude                                         # 로그인 상태 확인(대화창이 뜨면 로그인된 것. 종료는 /exit)
readlink -f "$(command -v java)"               # → <JAVA_HOME>/bin/java 의 <JAVA_HOME> 을 알아낸다
<JAVA_HOME>/bin/java -version                  # 알아낸 경로의 java 가 21 인지 확인(21 이 아니면 JDK 21 을 설치한 경로로 바꾼다)
```

- `<TOOLS_PATH>` = 위 `command -v` 결과에서 파일 이름을 뺀 디렉터리들(중복 제거)을 `:` 로 이은 값. 예: `claude` 가 `<홈>/.local/bin/claude` 면 `<홈>/.local/bin`.
- 앱의 `ClaudeCliLocator` 는 PATH → npm 전역 → `~/.local/bin` → Homebrew 순으로 찾는다. nvm 등 다른 위치면 못 찾을 수 있다(확인 필요: 서버의 claude 위치). 그런 경우 `.env` 의 `CLAUDE_EXE_COMMAND` 에 절대 경로를 적는다.
- `claude --version` 이 되는 것과 에이전트가 실제로 도는 것은 다르다. 실제 실행은 §8 의 마지막 항목에서 확인한다.

## 2. 사용자가 할 일 — 공유 PostgreSQL 접속 경로 (OI-18)

**포트를 공개하지 않는다. 기동 때 주소를 조회한다.** 공유 PostgreSQL 컨테이너의 compose·컨테이너·다른 서비스는 건드리지 않는다. unit 의 `ExecStartPre=+`(앞의 `+` = **root 로 실행**)가 `resolve-db.sh` 를 돌려 `docker inspect` 로 컨테이너의 네트워크 주소를 조회하고, `/run/dev-agent/db.env` 에 `DB_URL=jdbc:postgresql://<조회한 주소>:5432/<DB_NAME>` 한 줄을 쓴다. unit 의 `EnvironmentFile` 이 그것을 java 에 준다. 조회가 실패하면 `ExecStart`(java)가 실행되지 않는다.

**위험과 선택 — docker 그룹.** docker 소켓을 쓸 수 있는 계정(docker 그룹)은 사실상 root 와 같다. 이 앱은 `<SERVICE_USER>` 로 돌고 그 계정으로 Claude CLI 에이전트가 코드를 실행하므로, 그 계정이 docker 그룹에 있으면 에이전트가 root 권한을 얻을 수 있다. 이 구성에서는 조회를 root 가 하므로 **`<SERVICE_USER>` 가 docker 그룹에 있을 필요가 없다.** 계정을 그룹에서 뺄지는 사용자가 정한다(그 계정이 다른 용도로 docker 를 쓸 수 있다). `id <SERVICE_USER>` 로 그룹을 확인한다.

설치 전에 **root(sudo)로** 두 가지를 확인한다.

```bash
# 1) docker inspect 가 되고, 주소가 IPv4 하나로 나온다
sudo docker inspect --type container -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' <DB_CONTAINER>
#    값이 둘 이상(네트워크 여러 개)이면 쓸 네트워크 이름을 <DB_NETWORK> 로 정해 unit 의 ExecStartPre 인자에 적는다(하나뿐이면 -)
# 2) 호스트에서 그 주소의 5432 로 TCP 연결이 된다
timeout 3 bash -c 'exec 3<>/dev/tcp/<위에서 나온 주소>/5432' && echo OK
```

- 컨테이너를 **다시 만들어 주소가 바뀌어도** 앱을 다시 시작하면(`sudo systemctl restart dev-agent`) 새 주소를 조회해 붙는다. **앱이 도는 중에 주소가 바뀌면 기존 연결이 끊긴다** — 앱은 새 주소를 스스로 찾지 않으므로 재시작이 필요하다.
- 호스트 포트를 공개하지 않으므로 `ss -ltn` 에 5432 리슨이 없어야 한다(§8).
- 접속 인증은 DB 의 `pg_hba` 가 정한다(루프백 밖은 비밀번호 필요). `.env` 의 `DB_USERNAME`·`DB_PASSWORD` 가 그대로 쓰인다.

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
- 키 이름·설명은 `deploy/env.server.example` 에 있다. 꼭 채울 것: `ACCESS_ALLOWED_ORIGINS`(`https://<MagicDNS 이름>:8443`), `DB_USERNAME`·`DB_PASSWORD`, `GITLAB_WORK_DIR`(앱 홈 **밖**), GitLab 항목.
- **`.env` 에 `DB_URL` 을 적지 않는다.** `resolve-db.sh` 가 조회한 주소로 `DB_URL` 을 만든다. `.env` 에 `DB_URL=` 줄이 있으면 `resolve-db.sh` 가 거부한다(주석 처리한 줄은 무방).
- `MAINTAIN_ENABLED=false` 를 유지한다(플랫폼 관리 사용 여부는 OI-14 미정).
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
# 조회 스크립트: root 가 실행하므로 root 소유 경로에 root 소유로 둔다(서비스 계정이 쓸 수 없게)
sudo install -d -o root -g root -m 755 /usr/local/libexec/dev-agent
sudo install -o root -g root -m 755 deploy/bin/resolve-db.sh /usr/local/libexec/dev-agent/resolve-db.sh
sudo cp deploy/systemd/dev-agent.service /etc/systemd/system/dev-agent.service
sudo sed -i -e 's|<SERVICE_USER>|…|g' -e 's|<APP_HOME>|…|g' -e 's|<JAVA_HOME>|…|g' -e 's|<TOOLS_PATH>|…|g' -e 's|<DB_CONTAINER>|…|g' -e 's|<DB_NAME>|…|g' -e 's|<DB_NETWORK>|-|g' /etc/systemd/system/dev-agent.service
#   (또는 편집기로 일곱 자리표시자를 직접 바꾼다. …은 실제 값. <DB_NETWORK> 는 네트워크가 하나면 - , 여러 개면 고를 네트워크 이름)
grep -n '<' /etc/systemd/system/dev-agent.service     # 주석 줄 외에 남은 자리표시자가 없어야 한다

systemd-analyze verify /etc/systemd/system/dev-agent.service    # 문법 확인. 경고가 없어야 한다
sudo systemctl daemon-reload
sudo systemctl enable --now dev-agent
systemctl status dev-agent --no-pager
journalctl -u dev-agent -f                            # 실시간 로그(Ctrl+C 로 빠져나옴)
tail -f <APP_HOME>/logs/dev-agent.log                 # 앱 로그
```

- unit 의 핵심: `User`(비루트)·`WorkingDirectory`·`PATH`·`JAVA_HOME`·`DEV_AGENT_RUN_MODE=systemd`·`ExecStartPre=+`(root 로 DB 주소 조회)·`EnvironmentFile`(조회 결과 `DB_URL`)·`ExecStart`(java 직접 실행)·`Restart=on-failure`(종료 코드 0 은 다시 안 띄우고 10 은 다시 띄움)·`StartLimitBurst=5`(3분 안에 5회 실패하면 멈춤)·`After=docker.service`. `RestartPreventExitStatus` 는 쓰지 않는다 — `ExecStartPre` 의 종료 코드에는 적용되지 않는다(서버 systemd 255 에서 확인).
- **보안: `resolve-db.sh` 는 root 로 실행된다.** 서비스 계정이 이 파일(또는 그 디렉터리)을 고칠 수 있으면 서비스 계정이 root 권한을 얻는 통로가 된다. 그래서 위처럼 root 소유로 설치하고, 앱 홈(서비스 계정 소유)에 두지 않는다. 갱신할 때도 `sudo install` 로 덮는다. 같은 이유로 DB 컨테이너·DB 이름은 `Environment=` 가 아니라 `ExecStartPre` 인자로 주어 java 와 그 자식 프로세스(에이전트·대상 프로젝트 빌드)의 환경에 남기지 않는다. 단 `DB_URL` 은 `EnvironmentFile` 로 java 환경에 들어간다.
- `java -jar` 직접 실행은 `DEV_AGENT_HOME` 을 보지 않고 작업 디렉터리를 홈으로 쓴다. 그래서 `WorkingDirectory` 가 `<APP_HOME>` 이어야 `.env` 를 읽는다.
- `resolve-db.sh` 는 `docker` 를 PATH 에서 찾는다(unit 의 PATH 에 `/usr/bin` 이 있다. docker 가 다른 위치면 `<TOOLS_PATH>` 에 더한다). 컨테이너가 있는데 실행 중이 아니거나 주소가 비어 있으면 `DB_WAIT_SEC`(기본 60초) 동안 다시 시도하고, 못 얻으면 java 를 띄우지 않고 종료 코드 3 으로 실패해 `Restart=on-failure` 가 다시 시도한다. 컨테이너가 없거나(`No such object`) docker 권한이 없으면 종료 코드 2 로 바로 실패한다. 이 경우도 `Restart=on-failure` 가 5초 간격으로 다시 시도하지만 매번 즉시 실패하므로 약 25초(5회) 뒤 `StartLimitBurst` 로 멈춘다(`systemctl status` 가 `start-limit-hit`). 사유(docker 의 마지막 오류 포함)는 `journalctl -u dev-agent` 에 `resolve-db:` 한 줄로 남는다.
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
- [ ] 호스트에 DB 포트 리슨이 **없다**: `ss -ltn | grep ':5432'` → 출력 없음.
- [ ] 앱이 외부(공유) DB 로 떴다: 플랫폼 탭 「시스템」의 DB 가 "외부"(내장 DB 가 아님). `journalctl -u dev-agent` 에 `resolve-db: DB 주소 …` 한 줄이 있다.
- [ ] 조회 구성 확인(systemd 동작은 이 저장소에서 확인하지 못했다 — 서버에서 본다):
  - `systemd-analyze verify /etc/systemd/system/dev-agent.service` 경고 없음.
  - `ls -l /run/dev-agent/db.env` 가 `-rw-------` root 소유이고 내용이 `DB_URL=` 한 줄이다. `<SERVICE_USER>` 는 읽을 수 없다(읽을 필요 없다 — systemd 가 읽는다).
  - java 가 `DB_URL` 을 받았다(앱이 외부 DB 로 뜬 것으로 확인). `sudo cat /proc/<java PID>/environ | tr '\0' '\n' | grep -c '^DB_CONTAINER\|^DB_NAME'` → `0`(컨테이너·DB 이름이 java 환경에 없다).
  - **네트워크 지정 경로 실측**(가짜로는 템플릿 평가를 못 본다): 네트워크가 여럿인 컨테이너면 `sudo docker inspect --type container -f '{{if .State.Running}}{{with index .NetworkSettings.Networks "<DB_NETWORK>"}}{{.IPAddress}}{{end}}{{end}}' <DB_CONTAINER>` 가 IPv4 하나를 낸다. 하나뿐이어도 한 번 실행해 본다.
  - `<SERVICE_USER>` 가 docker 그룹에 없어도 앱이 뜬다(`id <SERVICE_USER>`, 그룹에서 빼기로 했다면).
  - 실패 경로: DB 컨테이너를 멈추고 `sudo systemctl restart dev-agent` → `resolve-db:` 메시지와 함께 java 가 뜨지 않고(`systemctl status`), 컨테이너를 다시 띄운 뒤 `Restart=on-failure` 로 다시 시도해 붙는다. 컨테이너 이름을 틀리게 하면 종료 코드 2 로 즉시 실패를 5회 반복한 뒤 `start-limit-hit` 으로 멈추는지 확인(약 25초).
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
- [ ] 서버 **재부팅** 뒤 자동 기동: `sudo reboot` 후 `systemctl status dev-agent` 가 active. DB 컨테이너보다 먼저 떠도 `resolve-db.sh` 가 60초 동안 주소 조회를 다시 시도하고, 그래도 못 얻으면 java 없이 non-zero 로 끝나 `Restart=on-failure` 로 다시 시도하는지 `journalctl -u dev-agent -b` 로 본다(확인 필요: 실패 한 번이 약 65초라 5회에 약 5분이 걸려 3분 안 5회 제한에 닿지 않는 계산이다. 컨테이너가 그보다 늦게 뜨면 `reset-failed` 가 필요할 수 있다).
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
| `Connection refused` / 연결 시간 초과 (DB) | `docker ps`, `journalctl -u dev-agent` 의 `resolve-db: DB 주소` | DB 컨테이너 기동 여부. 컨테이너를 다시 만들어 주소가 바뀌었으면 `sudo systemctl restart dev-agent` |
| `resolve-db: DB 컨테이너 주소를 …초 안에 얻지 못했다`(종료 코드 3) | 메시지 끝의 "마지막 상태", `sudo docker inspect <DB_CONTAINER>`, 컨테이너 실행 여부, unit 의 컨테이너 이름·네트워크 인자 | 컨테이너 기동·인자 수정 후 `reset-failed` + `start` |
| `resolve-db: docker 조회 실패: …No such object` / `permission denied`(종료 코드 2, 약 25초 뒤 `start-limit-hit`) | 컨테이너 이름 오타, root 로 docker 실행 가능 여부 | 인자 수정 → `daemon-reload` + `reset-failed` + `start` |
| `resolve-db: 조회한 값이 IPv4 주소 하나가 아니다`(종료 코드 2) | 컨테이너가 붙은 네트워크 수 | unit 의 네트워크 인자(`<DB_NETWORK>` 자리)를 이름으로 바꿈 → `daemon-reload` + `restart` |
| `resolve-db: 서버에서는 .env 에 DB_URL 을 두지 않는다`(종료 코드 2) | `<APP_HOME>/.env` 의 `DB_URL` 줄 | 줄을 지우거나 `#` 로 주석 처리 |
| `resolve-db: 인자 5개 필요` / `…형식 오류` / `docker 실행 파일을 PATH 에서 찾지 못했다`(종료 코드 2) | unit 의 `ExecStartPre` 줄과 `PATH` | §6 의 자리표시자 치환 확인 |
| `password authentication failed` | `.env` 의 `DB_USERNAME`·`DB_PASSWORD`, 값 끝 공백·CR | §4 LF 저장 확인 |
| 앱이 내장 DB(54329)로 뜸 | unit 에 `ExecStartPre=+…resolve-db.sh` 와 `EnvironmentFile=/run/dev-agent/db.env` 가 있는지 | 둘 중 하나가 빠지면 `DB_URL` 이 없어 내장 DB 로 뜬다. unit 을 §6 대로 고친다 |
| 화면은 열리는데 저장·등록이 403 | `.env` 의 `ACCESS_ALLOWED_ORIGINS` 값, 재기동 여부 | §8 의 Host·Origin 확인으로 실제 Origin 에 맞춘다 |
| `claude: command not found` / 에이전트가 도구를 못 찾음 | unit 의 `PATH`, `CLAUDE_EXE_COMMAND` | §1 의 경로를 `PATH` 에 더하고 `daemon-reload` + `restart` |
| 「재시작」을 눌렀더니 거부/안 뜸 | `curl -s http://127.0.0.1:8777/api/system/status` 의 `runMode` 가 `systemd` 인가 | `direct` 면 unit 의 `DEV_AGENT_RUN_MODE=systemd` 누락 |
| 「종료」 뒤에 다시 뜸 | unit 의 `Restart=` 값 | `Restart=always` 면 `on-failure` 로 |
| `tailscale serve` 가 HTTPS 를 켜라고 함 / 인증서 오류 | `tailscale serve status` | §7 의 HTTPS Certificates 활성화 |
| serve 주소로 열리지 않음 | `tailscale serve status`, 기기가 같은 tailnet 인지, ACL | 앱이 `127.0.0.1:8777` 에서 응답하는지 먼저 확인 |

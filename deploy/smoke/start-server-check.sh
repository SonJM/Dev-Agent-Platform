#!/usr/bin/env bash
# resolve-db.sh 동작 확인 — 서버·docker 없이 가짜 docker 로 시험한다(T-017).
# 사용: bash deploy/smoke/start-server-check.sh   → 마지막 줄 CHECK PASS / CHECK FAIL
# 시험용 주소는 문서용 대역(192.0.2.x)만 쓴다.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../bin/resolve-db.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILS=0
fail() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
ok() { echo "  ok: $1"; }

mkdir -p "$TMP/bin" "$TMP/run"
ARGS_LOG="$TMP/docker.args"
COUNTER="$TMP/docker.count"
export ARGS_LOG COUNTER

# 가짜 docker: FAKE_DOCKER_MODE 로 동작을 고르고, 받은 인자를 한 줄씩 ARGS_LOG 에 남긴다.
cat > "$TMP/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$ARGS_LOG"
n=$(( $(cat "$COUNTER" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$COUNTER"
case "${FAKE_DOCKER_MODE:-ok}" in
  ok) echo "192.0.2.10" ;;
  retry) if [ "$n" -ge 2 ]; then echo "192.0.2.11"; else echo ""; fi ;;
  notfound) echo "Error: No such object: x" >&2; exit 1 ;;
  perm) echo "permission denied while trying to connect to the Docker daemon socket" >&2; exit 1 ;;
  daemon) echo "Cannot connect to the Docker daemon" >&2; exit 1 ;;
  empty) echo "" ;;
  multi) printf '192.0.2.10192.0.2.11\n' ;;
  junk) echo "not-an-address" ;;
esac
EOF
chmod +x "$TMP/bin/docker"

DOTENV="$TMP/app.env"
OUT="$TMP/run/db.env"
export DB_ENV_OUT="$OUT"
export DB_WAIT_SEC=2

# run_case <docker 모드> [인자 5개 덮어쓰기: 컨테이너 DB 포트 네트워크 dotenv] — 종료 코드는 RC, 메시지는 $TMP/err
run_case() {
  local mode="$1"; shift
  local -a a=("${@:-}")
  [ "$#" -eq 0 ] && a=(dbc appdb 5432 - "$DOTENV")
  rm -f "$ARGS_LOG" "$COUNTER"
  RC=0
  FAKE_DOCKER_MODE="$mode" PATH="$TMP/bin:$PATH" bash "$SCRIPT" "${a[@]}" >"$TMP/out" 2>"$TMP/err" || RC=$?
}

expect_blocked() { # <설명> <기대 코드> — 해당 코드이고 db.env 가 없다
  if [ "$RC" -eq "$2" ] && [ ! -e "$OUT" ]; then ok "$1 (code $RC, db.env 없음)"; else fail "$1 (code $RC 기대 $2, db.env=$([ -e "$OUT" ] && echo 있음 || echo 없음))"; fi
}

reset_state() { printf 'DB_USERNAME=u\n' > "$DOTENV"; printf 'DB_URL=stale\n' > "$OUT"; }

echo "[정상]"
reset_state; run_case ok
if [ "$RC" -eq 0 ] && [ "$(cat "$OUT" 2>/dev/null)" = "DB_URL=jdbc:postgresql://192.0.2.10:5432/appdb" ] && [ "$(wc -l < "$OUT")" -eq 1 ]; then
  ok "주소 하나 → db.env 한 줄 DB_URL (지난 값을 덮어씀)"
else fail "정상 경로 (code $RC, 내용=$(cat "$OUT" 2>/dev/null))"; fi

if grep -qx -- "inspect --type container -f {{if .State.Running}}{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}{{end}} dbc" "$ARGS_LOG"; then
  ok "docker 인자: --type container, -f 템플릿(네트워크 전체), 컨테이너 이름"
else fail "docker 인자 (기록: $(cat "$ARGS_LOG" 2>/dev/null))"; fi

# 권한: 소유자만 읽기·쓰기(0600). 이 파일 시스템이 모드를 못 담으면(대조 파일로 확인) 건너뛴다.
touch "$TMP/modeprobe"; chmod 600 "$TMP/modeprobe"
if [ "$(stat -c %a "$TMP/modeprobe")" = "600" ]; then
  [ "$(stat -c %a "$OUT")" = "600" ] && ok "db.env 권한 0600" || fail "db.env 권한 ($(stat -c %a "$OUT"))"
else ok "권한 확인 건너뜀(이 파일 시스템은 모드를 저장하지 않음)"; fi

reset_state; run_case ok dbc appdb 6543 - "$DOTENV"
[ "$(cat "$OUT" 2>/dev/null)" = "DB_URL=jdbc:postgresql://192.0.2.10:6543/appdb" ] && ok "포트 인자 반영" || fail "포트 인자 반영"

reset_state; run_case ok dbc appdb 5432 net_1 "$DOTENV"
if [ "$RC" -eq 0 ] && grep -qF 'index .NetworkSettings.Networks "net_1"' "$ARGS_LOG"; then ok "네트워크 지정 → 해당 네트워크 템플릿 경로"; else fail "네트워크 지정 템플릿 ($(cat "$ARGS_LOG" 2>/dev/null))"; fi

echo "[재시도]"
DB_WAIT_SEC=8; reset_state; run_case retry; DB_WAIT_SEC=2   # 프로세스 생성이 느린 환경에서도 2회차 시도가 대기 안에 들어가게
if [ "$RC" -eq 0 ] && [ "$(cat "$COUNTER")" -ge 2 ] && [ "$(cat "$OUT")" = "DB_URL=jdbc:postgresql://192.0.2.11:5432/appdb" ]; then
  ok "1회차 빈 값 → 2회차 주소로 성공(docker $(cat "$COUNTER")회)"
else fail "재시도 성공 (code $RC, 호출 $(cat "$COUNTER" 2>/dev/null)회)"; fi

echo "[조회 실패 — 코드 3, db.env 없음]"
DB_WAIT_SEC=6; reset_state; run_case empty; DB_WAIT_SEC=2   # 느린 환경에서도 재시도가 최소 1회 들어가게
expect_blocked "중지 상태(빈 주소)를 기다려도 없음" 3
[ "$(cat "$COUNTER")" -ge 2 ] && ok "빈 값은 재시도했다" || fail "빈 값 재시도 안 함"
reset_state; run_case daemon; expect_blocked "데몬 연결 안 됨(재시도 후 실패)" 3
grep -q "Cannot connect" "$TMP/err" && ok "메시지에 docker 마지막 오류 포함" || fail "docker 오류가 메시지에 없음"

echo "[즉시 실패 — 코드 2, db.env 없음]"
reset_state; run_case notfound; expect_blocked "컨테이너 없음(No such object)" 2
[ "$(cat "$COUNTER")" -eq 1 ] && ok "재시도 없이 1회 조회" || fail "No such object 재시도함"
grep -q "No such object" "$TMP/err" && ok "메시지에 docker 오류 포함" || fail "docker 오류가 메시지에 없음"
reset_state; run_case perm;  expect_blocked "docker 권한 없음" 2
[ "$(cat "$COUNTER")" -eq 1 ] && ok "재시도 없이 1회 조회" || fail "permission denied 재시도함"
reset_state; run_case multi; expect_blocked "값이 이어 붙음(IPv4 하나 아님)" 2
reset_state; run_case junk;  expect_blocked "IPv4 형식 아님" 2
reset_state; printf 'DB_URL=jdbc:postgresql://x/y\n' > "$DOTENV"; run_case ok; expect_blocked ".env 에 DB_URL= 있음" 2
printf '  export DB_URL = z\n' > "$DOTENV"; printf 'DB_URL=stale\n' > "$OUT"; run_case ok; expect_blocked ".env 에 export DB_URL = 있음" 2
reset_state; run_case ok dbc appdb 5432 - "$TMP/missing.env"; expect_blocked ".env 경로가 없음" 2
printf '# DB_URL=주석\n' > "$DOTENV"; run_case ok
[ "$RC" -eq 0 ] && ok "주석 처리된 DB_URL 은 허용" || fail "주석 처리된 DB_URL 은 허용 (code $RC)"

echo "[입력 검사 — 코드 2]"
reset_state; run_case ok;                                       # 인자 개수 부족은 아래에서 직접
reset_state; run_case ok dbc appdb 5432 -;                      expect_blocked "인자 부족" 2
reset_state; run_case ok "" appdb 5432 - "$DOTENV";             expect_blocked "컨테이너 이름 비어 있음" 2
reset_state; run_case ok "-bad" appdb 5432 - "$DOTENV";         expect_blocked "컨테이너 이름이 -로 시작" 2
reset_state; run_case ok dbc "app;db" 5432 - "$DOTENV";         expect_blocked "DB 이름에 허용 밖 문자" 2
reset_state; run_case ok dbc appdb 54x2 - "$DOTENV";            expect_blocked "포트가 숫자 아님" 2
reset_state; run_case ok dbc appdb 5432 "bad name;x" "$DOTENV"; expect_blocked "네트워크 이름 형식 오류" 2

echo "[docker 없음 — 코드 2]"
SAFE_PATH=""
IFS=: read -ra DIRS <<< "$PATH"
for d in "${DIRS[@]}"; do
  [ -x "$d/docker" ] || [ -x "$d/docker.exe" ] || SAFE_PATH="${SAFE_PATH:+$SAFE_PATH:}$d"
done
if PATH="$SAFE_PATH" command -v grep >/dev/null 2>&1 && PATH="$SAFE_PATH" command -v timeout >/dev/null 2>&1; then
  reset_state; RC=0
  PATH="$SAFE_PATH" bash "$SCRIPT" dbc appdb 5432 - "$DOTENV" >"$TMP/out" 2>"$TMP/err" || RC=$?
  expect_blocked "docker 실행 파일 없음" 2
else ok "docker 없음 시험 건너뜀(docker 와 같은 디렉터리에 기본 도구가 있음)"; fi

if [ "$FAILS" -eq 0 ]; then echo "CHECK PASS"; else echo "CHECK FAIL ($FAILS)"; exit 1; fi

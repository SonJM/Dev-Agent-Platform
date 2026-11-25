#!/usr/bin/env bash
# T-001 / TC-001: 서버에서 격리 실행하는 기동 확인.
#
#   DEV_AGENT_SSH="<ssh 접속 대상>" bash deploy/smoke/server-smoke.sh [jar 경로]
#
# - 접속 대상은 환경변수 DEV_AGENT_SSH 로만 받는다(주소·계정은 이 파일·출력에 쓰지 않는다). 없으면 SMOKE SKIP.
# - 서버에는 mktemp -d 로 만든 새 디렉터리와 포트 18777(+내장 DB 포트) 밖의 어떤 것도 만들거나 바꾸지 않는다.
#   권한 상승·컨테이너 명령을 쓰지 않고, 기존 서비스·8777 포트·공유 DB·~/.claude·systemd·방화벽은 건드리지 않는다.
# - Claude CLI 를 호출하는 동작은 일으키지 않는다(Doctor 의 `claude --version` 만).
# - 마지막 줄: 성공 `SMOKE PASS`, 실패 `SMOKE FAIL: <사유>`(종료 코드 1), 접속 대상 없음 `SMOKE SKIP: ...`(종료 코드 2).

set -u

SMOKE_PORT=18777
EMBEDDED_DB_PORT=54329            # Bootstrap.EMBEDDED_PORT 기본값(DB_EMBEDDED_PORT 를 주지 않으므로 이 값)
START_TIMEOUT_SEC=240             # 첫 기동은 내장 DB 바이너리 풀기 + initdb 로 1분 이상
STOP_TIMEOUT_SEC=60

if [ -z "${DEV_AGENT_SSH:-}" ]; then
  echo "SMOKE SKIP: DEV_AGENT_SSH 없음"
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${1:-$ROOT/build/libs/dev-agent.jar}"
if [ ! -f "$JAR" ]; then
  echo "SMOKE FAIL: jar 가 없습니다(먼저 ./gradlew bootJar)"
  exit 1
fi

# DEV_AGENT_SSH 는 단어 분리해 ssh 인자로 쓴다(옵션을 함께 줄 수 있게). 값은 출력하지 않는다.
read -r -a SSH_TARGET <<< "$DEV_AGENT_SSH"
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o LogLevel=ERROR "${SSH_TARGET[@]}")

REMOTE_DIR=""
RESULT=""

# 원격 명령은 로그인 셸(bash -lc)로 — 서버의 java·claude 는 로그인 셸 PATH 에서만 보인다. stderr 는 접속 정보가 섞일 수 있어 버린다.
rsh() {
  "${SSH[@]}" "bash -lc $(printf '%q' "$1")" 2>/dev/null </dev/null
}

# 모든 원격 스크립트가 공유하는 함수. D(격리 디렉터리)는 호출마다 앞에 붙인다.
read -r -d '' COMMON <<'EOF'
# D 아래에서 도는 내 프로세스의 PID(작업 디렉터리가 D 이하). 앱(java)과 내장 DB(postgres)가 여기에 잡힌다.
find_pids() {
  for p in /proc/[0-9]*; do
    c=$(readlink "$p/cwd" 2>/dev/null) || continue
    case "$c" in "$D"|"$D"/*) echo "${p#/proc/}";; esac
  done
}
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
snap_bin() {
  if [ -d "$HOME/bin" ]; then
    echo "DIR"; (cd "$HOME/bin" 2>/dev/null && stat -c '%n %s %Y %a' ./* ./.[!.]* 2>/dev/null | sort)
  else
    echo "NO_DIR"
  fi
}
# 홈 경로·계정명은 출력에 남기지 않는다.
scrub() { sed -e "s#$HOME#~#g" -e "s#$(id -un)#<user>#g"; }
EOF

cleanup_remote() {
  [ -n "$REMOTE_DIR" ] || return 0
  rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
case \"\$D\" in */dev-agent-smoke.*) ;; *) echo 'refuse: unexpected dir'; exit 1;; esac
# 내가 띄운 프로세스만(작업 디렉터리가 D 이하): 먼저 TERM, 남으면 KILL
pids=\$(find_pids)
if [ -n \"\$pids\" ]; then
  kill \$pids 2>/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do [ -z \"\$(find_pids)\" ] && break; sleep 1; done
  pids=\$(find_pids); [ -n \"\$pids\" ] && kill -9 \$pids 2>/dev/null
fi
rm -rf \"\$D\"
[ ! -e \"\$D\" ] && echo 'cleanup: ok'
" || echo "cleanup: 원격 정리 실패(격리 디렉터리를 수동 확인)"
}

on_exit() {
  code=$?
  trap - EXIT
  cleanup_remote
  if [ -z "$RESULT" ]; then
    RESULT="SMOKE FAIL: 예기치 않은 종료(코드 $code)"
    code=1
  fi
  echo "$RESULT"
  exit "$code"
}
trap on_exit EXIT
trap 'RESULT="SMOKE FAIL: 중단됨"; exit 1' INT TERM

fail() {
  RESULT="SMOKE FAIL: $1"
  exit 1
}

# ---- 0. 접속·도구 확인 -------------------------------------------------------------------------
out=$(rsh 'command -v java >/dev/null && command -v curl >/dev/null && java -version 2>&1 | head -1') \
  || fail "서버에 접속하지 못했거나 java/curl 이 로그인 셸 PATH 에 없습니다"
echo "server java: $out"

# ---- 1. 격리 디렉터리 + 실행 전 ~/bin 스냅샷 ------------------------------------------------
# 로그인 셸(profile)이 stdout 에 무언가를 찍어도 마지막 줄(mktemp 결과)만 받는다
REMOTE_DIR=$(rsh 'mktemp -d "${TMPDIR:-/tmp}/dev-agent-smoke.XXXXXX"' | tail -n 1) || fail "격리 디렉터리를 만들지 못했습니다"
case "$REMOTE_DIR" in
  *..*|*[[:space:]]*) REMOTE_DIR=""; fail "격리 디렉터리 경로가 예상과 다릅니다";;
  /*/dev-agent-smoke.*) ;;
  *) REMOTE_DIR=""; fail "격리 디렉터리 경로가 예상과 다릅니다";;
esac
echo "isolated dir: created"
rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
snap_bin > \"\$D/bin.before\"
" || fail "실행 전 ~/bin 스냅샷 실패"

# ---- 2. jar 업로드 ----------------------------------------------------------------------------
"${SSH[@]}" "bash -lc $(printf '%q' "cat > $(printf '%q' "$REMOTE_DIR/dev-agent.jar")")" < "$JAR" 2>/dev/null \
  || fail "jar 를 올리지 못했습니다"
echo "jar uploaded"

# ---- 3. 포트 확인(쓰이고 있으면 아무것도 띄우지 않는다) -----------------------------------------
rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
for p in $SMOKE_PORT $EMBEDDED_DB_PORT; do
  if port_open \$p; then echo \"port \$p in use\"; exit 3; fi
done
echo 'ports free'
" || fail "포트 $SMOKE_PORT 또는 내장 DB 포트 $EMBEDDED_DB_PORT 가 이미 사용 중입니다(아무것도 띄우지 않음)"

# ---- 4. 기동(격리 디렉터리 = 작업 디렉터리 겸 DEV_AGENT_HOME) ------------------------------------
rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
# 서버 로그인 셸의 환경(SPRING_DATASOURCE_URL 같은 값 포함)이 앱에 전달되지 않게 env -i 로 비우고 필요한 값만 준다.
# 그래야 DB_URL 이 없는 앱이 내장 DB 만 쓴다(공유 DB 에 붙지 않는다).
(
  cd \"\$D\" || exit 1
  if command -v setsid >/dev/null; then S=setsid; else S=; fi
  \$S nohup env -i HOME=\"\$HOME\" PATH=\"\$PATH\" LANG=\"\${LANG:-C.UTF-8}\" USER=\"\$(id -un)\" \
      DEV_AGENT_HOME=\"\$D\" ADMIN_PORT=$SMOKE_PORT MAINTAIN_ENABLED=false GITLAB_ENABLED=false \
      GITLAB_WORK_DIR=\"\$D/work\" DEV_AGENT_RUN_MODE=systemd \
      java -Xmx512m -XX:+UseSerialGC -jar \"\$D/dev-agent.jar\" < /dev/null > \"\$D/out.log\" 2>&1 &
)
sleep 2
[ -n \"\$(find_pids)\" ] && echo started || { echo 'not started'; exit 1; }
" || fail "앱을 시작하지 못했습니다"
echo "app started (waiting up to ${START_TIMEOUT_SEC}s)"

# ---- 5. 확인 ------------------------------------------------------------------------------------
verify_out=$(rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
base=http://127.0.0.1:$SMOKE_PORT
code=000
for i in \$(seq 1 $((START_TIMEOUT_SEC / 3))); do
  code=\$(curl -s -o \"\$D/status.json\" -w '%{http_code}' \"\$base/api/system/status\" 2>/dev/null)
  [ \"\$code\" = 200 ] && break
  [ -z \"\$(find_pids)\" ] && { echo 'app exited early'; break; }
  sleep 3
done
echo \"status http=\$code\"
[ \"\$code\" = 200 ] || { echo '--- out.log (tail)'; tail -25 \"\$D/out.log\" 2>/dev/null | scrub; exit 10; }
dcode=\$(curl -s -o \"\$D/doctor.json\" -w '%{http_code}' \"\$base/api/system/doctor\" 2>/dev/null)
echo \"doctor http=\$dcode\"
[ \"\$dcode\" = 200 ] || exit 11
grep -q '\"runMode\":\"systemd\"' \"\$D/status.json\" && echo 'runMode=systemd' || { echo 'runMode mismatch'; exit 12; }
grep -q '\"supervised\":true' \"\$D/status.json\" && echo 'supervised=true' || { echo 'supervised mismatch'; exit 13; }
for k in agent git; do
  st=\$(grep -o \"\\\"key\\\":\\\"\$k\\\",\\\"name\\\":\\\"[^\\\"]*\\\",\\\"status\\\":\\\"[a-z]*\\\"\" \"\$D/doctor.json\" | grep -o '\"status\":\"[a-z]*\"')
  echo \"doctor \$k: \$st\"
  case \"\$st\" in *'\"ok\"'*) ;; *) echo \"doctor \$k not ok\"; exit 14;; esac
done
# 기동 실패 예외 흔적
if grep -qE 'APPLICATION FAILED TO START|BeanCreationException|UnsatisfiedDependencyException|Exception in thread \"main\"' \"\$D/out.log\" \"\$D/logs/dev-agent.log\" 2>/dev/null; then
  echo 'startup exception found:'; grep -hE 'APPLICATION FAILED TO START|BeanCreationException|UnsatisfiedDependencyException|Exception in thread' \"\$D/out.log\" \"\$D/logs/dev-agent.log\" 2>/dev/null | head -5 | scrub
  exit 15
fi
echo 'no startup exception'
") || verify_rc=$?
echo "$verify_out"
if [ -n "${verify_rc:-}" ]; then
  case "$verify_rc" in
    10) fail "제한 시간 안에 /api/system/status 가 200 이 되지 않았습니다";;
    11) fail "/api/system/doctor 가 200 이 아닙니다";;
    12|13) fail "실행 방식(runMode/supervised)이 systemd 로 보이지 않습니다";;
    14) fail "Doctor 의 Claude CLI 또는 git 항목이 ok 가 아닙니다";;
    15) fail "로그에 기동 실패 예외가 있습니다";;
    *) fail "기동 확인 중 오류(코드 $verify_rc)";;
  esac
fi

# ---- 6. 종료(POST /api/system/shutdown) 후 프로세스·포트가 사라졌는지 -----------------------------
rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
curl -s -X POST \"http://127.0.0.1:$SMOKE_PORT/api/system/shutdown\" >/dev/null 2>&1
for i in \$(seq 1 $STOP_TIMEOUT_SEC); do
  [ -z \"\$(find_pids)\" ] && break
  sleep 1
done
left=\$(find_pids)
if [ -n \"\$left\" ]; then echo 'processes left after shutdown'; exit 20; fi
for p in $SMOKE_PORT $EMBEDDED_DB_PORT; do
  if port_open \$p; then echo \"port \$p still open\"; exit 21; fi
done
echo 'shutdown clean: app and embedded db gone, ports closed'
" || fail "shutdown 뒤에도 앱/내장 DB 프로세스 또는 포트가 남았습니다(자기가 띄운 것만 정리 후 종료)"

# ---- 6b. 실행 전후 ~/bin 비교: 기동만으로 사용자 홈에 심이 생기거나 바뀌면 안 된다 ----------------
rsh "$COMMON
D=$(printf '%q' "$REMOTE_DIR")
snap_bin > \"\$D/bin.after\"
if cmp -s \"\$D/bin.before\" \"\$D/bin.after\"; then echo '~/bin unchanged'; rc=0
else echo '~/bin changed:'; diff \"\$D/bin.before\" \"\$D/bin.after\" | head -20 | scrub; rc=30; fi
rm -f \"\$D/bin.before\" \"\$D/bin.after\"
exit \$rc
" || fail "기동만으로 ~/bin 이 생성되거나 바뀌었습니다"

RESULT="SMOKE PASS"
exit 0

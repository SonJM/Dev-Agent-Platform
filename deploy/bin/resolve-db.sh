#!/usr/bin/env bash
# 공유 PostgreSQL 컨테이너 주소 조회 (T-017, OI-18 방법 c). unit 의 ExecStartPre=+ 가 root 로 실행한다.
# 조회한 주소로 DB_URL 한 줄짜리 환경 파일을 만들고, unit 의 EnvironmentFile 이 그것을 java 에 준다.
# 앱(서비스 계정)이 docker 권한을 가질 필요가 없고, 입력을 인자로만 받아 java 환경에 DB_* 가 남지 않는다.
#
# 사용: resolve-db.sh <컨테이너> <DB 이름> <포트> <네트워크 또는 -> <.env 절대 경로>
#   네트워크: 컨테이너가 네트워크 여러 개에 붙어 있을 때 고를 이름. 하나뿐이면 -
#   .env: 서버에서는 .env 에 DB_URL 을 두지 않으므로, 있으면 거부한다(Bootstrap 과 Spring 이 다른 DB 를 보는 일 방지)
# 환경변수(unit 이 주는 선택값): DB_WAIT_SEC(기본 60), DB_ENV_OUT(기본 /run/dev-agent/db.env)
#
# 종료 코드: 0 성공 / 2 설정 오류·재시도해도 소용없는 실패 / 3 대기 시간 안에 주소를 못 얻음
# 실패하면 이 스크립트가 쓴 파일이 남지 않는다(앞에서 지운다). ExecStartPre 가 실패하면 ExecStart(java)는 실행되지 않는다.
# 보안: 이 스크립트는 root 로 실행되므로 서비스 계정이 고칠 수 없는 root 소유 경로에 둔다(deploy/README.md §6).
set -eu

readonly EXIT_CONFIG=2
readonly EXIT_LOOKUP=3
readonly RETRY_INTERVAL_SEC=2
readonly CALL_TIMEOUT_SEC=5
readonly MIN_CALL_TIMEOUT_SEC=3
readonly IPV4_RE='^([0-9]{1,3}\.){3}[0-9]{1,3}$'
readonly OUT_FILE="${DB_ENV_OUT:-/run/dev-agent/db.env}"
readonly WAIT_SEC="${DB_WAIT_SEC:-60}"

die() { echo "resolve-db: $1" >&2; exit "${2:-$EXIT_LOOKUP}"; }

# 지난번 결과가 남지 않게 가장 먼저 지운다(어떤 이유로 실패해도 옛 주소로 뜨는 일 방지)
rm -f "$OUT_FILE"

[ "$#" -eq 5 ] || die "인자 5개 필요: <컨테이너> <DB 이름> <포트> <네트워크|-> <.env 경로>" "$EXIT_CONFIG"
CONTAINER="$1"; DB_NAME="$2"; DB_PORT="$3"; NETWORK="$4"; DOTENV="$5"

[[ "$CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "컨테이너 이름 형식 오류" "$EXIT_CONFIG"
[[ "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]] || die "DB 이름에는 영문·숫자·밑줄만 쓴다" "$EXIT_CONFIG"
[[ "$DB_PORT" =~ ^[0-9]+$ ]] || die "포트는 숫자여야 한다" "$EXIT_CONFIG"
[[ "$WAIT_SEC" =~ ^[0-9]+$ ]] || die "DB_WAIT_SEC 는 숫자여야 한다" "$EXIT_CONFIG"
if [ "$NETWORK" != "-" ] && ! [[ "$NETWORK" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  die "네트워크 이름에 쓸 수 없는 문자가 있다" "$EXIT_CONFIG"
fi

command -v docker >/dev/null 2>&1 || die "docker 실행 파일을 PATH 에서 찾지 못했다" "$EXIT_CONFIG"
[ -r "$DOTENV" ] || die ".env 를 읽을 수 없다: $DOTENV" "$EXIT_CONFIG"
if grep -Eq '^[[:space:]]*(export[[:space:]]+)?DB_URL[[:space:]]*=' "$DOTENV"; then
  die "서버에서는 .env 에 DB_URL 을 두지 않는다. .env 의 DB_URL 줄을 지우거나 주석 처리한다" "$EXIT_CONFIG"
fi

if [ "$NETWORK" != "-" ]; then
  ADDR_TMPL="{{if .State.Running}}{{with index .NetworkSettings.Networks \"$NETWORK\"}}{{.IPAddress}}{{end}}{{end}}"
else
  ADDR_TMPL="{{if .State.Running}}{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}{{end}}"
fi

ERR_FILE="$(mktemp)"
trap 'rm -f "$ERR_FILE"' EXIT
LAST_ERR=""
ADDR=""
START="$SECONDS"
while :; do
  REMAIN=$((WAIT_SEC - (SECONDS - START)))
  CALL_TIMEOUT=$CALL_TIMEOUT_SEC
  # 남은 대기 시간이 호출 상한보다 짧으면 그만큼만(너무 짧아 정상 응답을 끊지 않게 하한을 둔다)
  [ "$REMAIN" -lt "$CALL_TIMEOUT" ] && CALL_TIMEOUT=$(( REMAIN > MIN_CALL_TIMEOUT_SEC ? REMAIN : MIN_CALL_TIMEOUT_SEC ))
  RC=0
  ADDR="$(timeout "$CALL_TIMEOUT" docker inspect --type container -f "$ADDR_TMPL" "$CONTAINER" 2>"$ERR_FILE")" || RC=$?
  if [ "$RC" -ne 0 ]; then
    LAST_ERR="$(tail -n 1 "$ERR_FILE")"
    [ "$RC" -eq 124 ] && LAST_ERR="docker 응답 없음(${CALL_TIMEOUT}초 초과)"
    # 존재하지 않는 컨테이너·권한 없음은 기다려도 나아지지 않는다
    case "$LAST_ERR" in
      *"No such object"*|*"No such container"*|*"ermission denied"*)
        die "docker 조회 실패: $LAST_ERR" "$EXIT_CONFIG" ;;
    esac
    ADDR=""
  elif [ -n "$ADDR" ]; then
    break
  else
    LAST_ERR="컨테이너가 실행 중이 아니거나 (지정한 네트워크의) 주소가 비어 있다"
  fi
  if [ $((SECONDS - START)) -ge "$WAIT_SEC" ]; then
    die "DB 컨테이너 주소를 ${WAIT_SEC}초 안에 얻지 못했다. 마지막 상태: ${LAST_ERR:-없음}"
  fi
  sleep "$RETRY_INTERVAL_SEC"
done

# 값이 IPv4 하나인지 확인한다(네트워크가 여러 개인데 지정하지 않으면 값이 이어 붙는다). 기다려도 나아지지 않으므로 바로 실패한다.
[[ "$ADDR" =~ $IPV4_RE ]] || die "조회한 값이 IPv4 주소 하나가 아니다. 컨테이너가 네트워크 여러 개에 붙어 있으면 네트워크 이름을 지정한다" "$EXIT_CONFIG"

umask 077
TMP_OUT="$(mktemp "${OUT_FILE}.XXXXXX")"
printf 'DB_URL=jdbc:postgresql://%s:%s/%s\n' "$ADDR" "$DB_PORT" "$DB_NAME" > "$TMP_OUT"
chmod 600 "$TMP_OUT"
mv -f "$TMP_OUT" "$OUT_FILE"
echo "resolve-db: DB 주소 ${ADDR}:${DB_PORT} (컨테이너 조회)" >&2

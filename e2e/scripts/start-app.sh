#!/usr/bin/env bash
# Starts the app for QA on port 8778 in an isolated home directory (04 section 8).
# QA_JAR: jar to run (default: <repo>/build/libs/dev-agent.jar). QA_HOME: run directory.
# The embedded DB data is wiped on every start (DB reset); the unpacked pg-bin is kept.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
JAR=${QA_JAR:-$REPO/build/libs/dev-agent.jar}
HOME_DIR=${QA_HOME:-$REPO/e2e/test-results/qa-home}
[ -f "$JAR" ] || { echo "jar not found: $JAR"; exit 2; }
PORT=8778
# Refuse when something already listens: wiping the data dir under a live instance breaks it.
# Decided by a TCP connect, not an HTTP response, so an unresponsive instance is refused too.
if timeout 2 bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT" 2>/dev/null; then
  echo "port $PORT in use — run stop-app.sh first"
  exit 3
fi
mkdir -p "$HOME_DIR"
rm -rf "$HOME_DIR/pgdata-embedded"
cd "$HOME_DIR"
export ADMIN_PORT=$PORT MAINTAIN_ENABLED=false
# One fake target project so the routine screens work without GitLab
# (adding a project through the UI needs a real GitLab).
export GITLAB_PROJECTS=qa-group/qa-sample
unset DB_URL
# user.home is redirected so the test app (and any Claude CLI state it writes under the
# home directory) stays isolated from the real user's home.
mkdir -p "$HOME_DIR/home"
exec java -Duser.home="$HOME_DIR/home" -jar "$JAR"

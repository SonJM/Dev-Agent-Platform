#!/usr/bin/env bash
# Starts the app for QA on port 8778 in an isolated home directory (04 section 8).
# QA_JAR: jar to run (default: <repo>/build/libs/dev-agent.jar). QA_HOME: run directory.
# The embedded DB data is wiped on every start (DB reset); the unpacked pg-bin is kept.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
JAR=${QA_JAR:-$REPO/build/libs/dev-agent.jar}
HOME_DIR=${QA_HOME:-$REPO/e2e/test-results/qa-home}
[ -f "$JAR" ] || { echo "jar not found: $JAR"; exit 2; }
mkdir -p "$HOME_DIR"
rm -rf "$HOME_DIR/pgdata-embedded"
cd "$HOME_DIR"
export ADMIN_PORT=8778 PREVIEW_ENABLED=false MAINTAIN_ENABLED=false
# No Claude CLI call at startup (warm session prime). One fake target project so the routine
# screens work without GitLab (adding a project through the UI needs a real GitLab).
export CLAUDE_WARM=false CLAUDE_WARM_PREWARM=false GITLAB_PROJECTS=qa-group/qa-sample
unset DB_URL DEV_AGENT_SUPERVISED
# user.home is redirected: the app writes agent shims (preview, jscheck, jspcheck) to
# <user.home>/bin, which would otherwise overwrite the real user's shims.
mkdir -p "$HOME_DIR/home"
exec java -Duser.home="$HOME_DIR/home" -jar "$JAR"

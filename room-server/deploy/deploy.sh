#!/usr/bin/env bash
set -euo pipefail

HOST="${GRMEMBY_DEPLOY_HOST:-}"
DIR="${GRMEMBY_DEPLOY_DIR:-/opt/grmemby-room-server}"
JAR="${GRMEMBY_ROOM_SERVER_JAR:-room-server/build/libs/grmemby-room-server-all.jar}"
SERVICE="room-server/deploy/grmemby-room-server.service"
if [[ -z "$HOST" ]]; then
  echo "GRMEMBY_DEPLOY_HOST is required (example: user@example.test)" >&2
  exit 1
fi

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
fi

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '+ %q ' "$@"
    printf '\n'
  else
    "$@"
  fi
}

if [[ ! -f "$JAR" ]]; then
  echo "Missing jar: $JAR" >&2
  echo "Build it first: ./gradlew :room-server:fatJar" >&2
  exit 1
fi

run ssh "$HOST" "mkdir -p '$DIR'"
run scp "$JAR" "$HOST:$DIR/app.jar"
run scp "$SERVICE" "$HOST:/etc/systemd/system/grmemby-room-server.service"
run ssh "$HOST" "systemctl daemon-reload && systemctl enable grmemby-room-server && systemctl restart grmemby-room-server && systemctl --no-pager --full status grmemby-room-server"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_PATH="${1:-config.yaml}"
STATE_DB_PATH="${2:-syncstate.db}"

cd "$SCRIPT_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME must be set before running the syncer." >&2
  exit 1
fi

exec ./gradlew runSyncer --args="--config ${CONFIG_PATH} --sync-state-db-path ${STATE_DB_PATH} --max-cycles 1"

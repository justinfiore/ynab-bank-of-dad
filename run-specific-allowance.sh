#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: ./run-specific-allowance.sh YYYY-MM-DD [optional-config-path]" >&2
  exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -z "${YNAB_ACCESS_TOKEN:-}" ]]; then
  echo "ERROR: YNAB_ACCESS_TOKEN is not set." >&2
  exit 1
fi

RUN_DATE="$1"
CONFIG_ARGS=()
if [[ $# -eq 2 ]]; then
  CONFIG_ARGS=(--config "$2")
fi

./gradlew --no-daemon installDist
./gradlew --no-daemon run --args="--dry-run --date ${RUN_DATE} ${CONFIG_ARGS[*]:-}"

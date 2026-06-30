#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -z "${YNAB_ACCESS_TOKEN:-}" ]]; then
  echo "ERROR: YNAB_ACCESS_TOKEN is not set." >&2
  exit 1
fi

./gradlew --no-daemon installDist
./gradlew --no-daemon run --args='--dry-run'

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -z "${YNAB_ACCESS_TOKEN:-}" ]]; then
  echo "ERROR: YNAB_ACCESS_TOKEN is not set." >&2
  exit 1
fi

JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_VERSION_LINE="$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | head -n 1)"
JAVA_MAJOR_VERSION="$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ -z "$JAVA_MAJOR_VERSION" || "$JAVA_MAJOR_VERSION" -lt 25 ]]; then
  echo "ERROR: Java 25 or later is required." >&2
  echo "Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25." >&2
  echo "Detected java version: ${JAVA_VERSION_OUTPUT:-<unable to determine>}" >&2
  exit 1
fi

./gradlew --no-daemon installDist
./gradlew --no-daemon run

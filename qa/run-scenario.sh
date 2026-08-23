#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "Usage: qa/run-scenario.sh <artifact-dir> <safety.json> <dry|live> <command...>" >&2
  exit 64
fi

artifact_dir=$1
safety_file=$2
mode=$3
shift 3

python3 - "$safety_file" "$mode" <<'PY'
import json
import os
import sys

evidence = json.load(open(sys.argv[1], encoding="utf-8"))
mode = sys.argv[2]
if not evidence.get("all_targets_allowlisted"):
    raise SystemExit("BLOCKED: exact full-ID/name allowlist did not pass")
if mode == "live":
    if not evidence.get("provisioning_complete"):
        raise SystemExit("BLOCKED: required provisioning is incomplete")
    if not evidence.get("dry_run_passed"):
        raise SystemExit("BLOCKED: scenario dry run did not pass")
    if not evidence.get("expected_mutation_manifest"):
        raise SystemExit("BLOCKED: expected mutation manifest is missing")
    if os.environ.get("QA_CONFIRM_LIVE_MUTATIONS") != "YES":
        raise SystemExit("BLOCKED: QA_CONFIRM_LIVE_MUTATIONS=YES is required")
elif mode != "dry":
    raise SystemExit("Mode must be dry or live")
PY

qa_root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PYTHONPATH="$qa_root" python3 - "$artifact_dir" "$mode" "$@" <<'PY'
import json
import os
import sys
from pathlib import Path

from lib.run_capture import capture_command

artifact_dir = Path(sys.argv[1])
mode = sys.argv[2]
command = sys.argv[3:]
secrets = [
    value for key, value in os.environ.items()
    if "TOKEN" in key.upper() or "AUTHORIZATION" in key.upper()
]
result = capture_command(
    command,
    artifact_dir / f"{mode}-run.log",
    cwd=Path.cwd(),
    env=dict(os.environ),
    explicit_secrets=secrets,
)
(artifact_dir / f"{mode}-capture.json").write_text(
    json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
raise SystemExit(result["exitCode"])
PY

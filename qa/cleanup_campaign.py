#!/usr/bin/env python3
"""Delete transactions for one exact disposable QA campaign."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_cleanup import cleanup_exact_campaign
from lib.qa_config import load_budget_identities, load_tokens
from lib.ynab_qa_client import YnabQaClient, merge_request_telemetry


TOKEN_ENV = {
    "Jorsten's Plan": "PARENT_ACCESS_TOKEN",
    "Jorsten Jr's Plan": "JORSTEN_JR_ACCESS_TOKEN",
    "Borsten's Plan": "BORSTEN_ACCESS_TOKEN",
    "Thorsten's Plan": "THORSTEN_ACCESS_TOKEN",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Clean one exact disposable QA campaign")
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    confirmation = os.environ.get("QA_CONFIRM_LIVE_MUTATIONS")
    if confirmation != "YES":
        raise SystemExit("BLOCKED: QA_CONFIRM_LIVE_MUTATIONS=YES is required")

    load_tokens(os.environ, None)
    identities = load_budget_identities(QA_ROOT / "config/qa-sync.yaml")
    allowlist = {name: identity.plan_id for name, identity in identities.items()}
    clients = {
        name: YnabQaClient(os.environ[TOKEN_ENV[name]], allowlist) for name in TOKEN_ENV
    }
    result = cleanup_exact_campaign(
        args.campaign_id, identities, clients, confirmation=confirmation,
    )
    result["request_telemetry"] = merge_request_telemetry(clients)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Cleanup complete for {args.campaign_id}: {result['deleted_count']} deleted")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

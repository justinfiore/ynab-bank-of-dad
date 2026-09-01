#!/usr/bin/env python3
"""Run fresh QA discovery without persisting tokens, headers, or resource IDs."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(QA_ROOT))

from lib.qa_config import load_budget_identities
from lib.read_only_discovery import CHILD_NAMES, PARENT_NAME, DiscoveryBlocked, run_discovery
from lib.ynab_qa_client import PlanIdentity, YnabQaClient


TOKEN_ENV_BY_NAME = {
    PARENT_NAME: "PARENT_ACCESS_TOKEN",
    "Jorsten Jr's Plan": "JORSTEN_JR_ACCESS_TOKEN",
    "Borsten's Plan": "BORSTEN_ACCESS_TOKEN",
    "Thorsten's Plan": "THORSTEN_ACCESS_TOKEN",
}


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _identities(config_path: Path) -> dict[str, PlanIdentity]:
    return load_budget_identities(config_path)


def main() -> int:
    parser = argparse.ArgumentParser(description="Perform fail-closed, GET-only YNAB QA discovery")
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--campaign-root", required=True, type=Path)
    args = parser.parse_args()
    campaign_root = args.campaign_root
    identities = _identities(args.config)
    allowlist = {name: identity.plan_id for name, identity in identities.items()}
    clients = {
        name: YnabQaClient(os.environ.get(env_name, ""), allowlist)
        for name, env_name in TOKEN_ENV_BY_NAME.items()
    }
    try:
        evidence = run_discovery(
            identities,
            clients[PARENT_NAME],
            {name: clients[name] for name in CHILD_NAMES},
        )
    except DiscoveryBlocked as error:
        _write_json(campaign_root / "safety.json", {
            "all_targets_allowlisted": False,
            "api_write_count": 0,
            "status": "BLOCKED",
            "reason": str(error),
        })
        print("BLOCKED: exact immutable QA plan allowlist validation failed; API writes remain 0")
        return 2

    _write_json(campaign_root / "api-observations" / "current-discovery.json", evidence)
    _write_json(campaign_root / "missing-provisioning.json", {
        "complete": evidence["provisioning_complete"],
        "missingParentCategories": evidence["missing_parent_categories"],
        "missingChildAccounts": evidence["missing_child_accounts"],
        "apiWriteCount": 0,
    })
    _write_json(campaign_root / "safety.json", {
        "all_targets_allowlisted": True,
        "provisioning_complete": evidence["provisioning_complete"],
        "api_write_count": 0,
        "status": "PASS" if evidence["provisioning_complete"] else "BLOCKED",
    })
    print(
        "Read-only discovery complete: allowlist=PASS, "
        f"provisioning={'PASS' if evidence['provisioning_complete'] else 'BLOCKED'}, API writes=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

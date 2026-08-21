#!/usr/bin/env python3
"""Dry-run or explicitly execute fail-closed QA parent category provisioning."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(QA_ROOT))

from lib.category_provisioning import (
    LOCAL_QA_CONFIG,
    ProvisioningBlocked,
    build_dry_run_manifest,
    execute_provisioning,
    load_local_identities,
    write_redacted_json,
)
from lib.ynab_qa_client import QaSafetyError, YnabQaClient


ARTIFACT_ROOT = QA_ROOT / "artifacts"


def _artifact_path(value: str) -> Path:
    path = Path(value).resolve()
    try:
        path.relative_to(ARTIFACT_ROOT.resolve())
    except ValueError:
        raise argparse.ArgumentTypeError("evidence paths must be inside ignored qa/artifacts") from None
    return path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Provision missing categories only in the exact disposable QA parent plan"
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--provision", action="store_true")
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--provisioning-tag", required=True)
    parser.add_argument("--artifact", required=True, type=_artifact_path)
    parser.add_argument("--receipt", type=_artifact_path)
    args = parser.parse_args()

    try:
        identities = load_local_identities(LOCAL_QA_CONFIG)
        allowlist = {name: identity.plan_id for name, identity in identities.items()}
        client = YnabQaClient(os.environ.get("PARENT_ACCESS_TOKEN", ""), allowlist)
        if args.dry_run:
            if args.receipt is not None:
                raise ProvisioningBlocked("Dry run does not accept a receipt path")
            manifest = build_dry_run_manifest(
                identities, client, campaign_id=args.campaign_id,
                provisioning_tag=args.provisioning_tag,
            )
            write_redacted_json(args.artifact, manifest)
            print(
                "QA category dry run complete: exact plan pairs=4, "
                f"planned writes={manifest['plannedWriteCount']}, API writes=0"
            )
            return 0
        if args.receipt is None:
            raise ProvisioningBlocked("Live provisioning requires a receipt path")
        receipt = execute_provisioning(
            identities,
            client,
            args.artifact,
            args.receipt,
            campaign_id=args.campaign_id,
            provisioning_tag=args.provisioning_tag,
            confirmation=os.environ.get("QA_CONFIRM_PROVISIONING_MUTATIONS"),
        )
        print(
            "QA parent category provisioning complete: "
            f"response objects={receipt['successfulResponseObjectCount']}"
        )
        return 0
    except (ProvisioningBlocked, QaSafetyError, OSError):
        print("BLOCKED: QA category provisioning safety contract was not satisfied", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

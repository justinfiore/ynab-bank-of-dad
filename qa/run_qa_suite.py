#!/usr/bin/env python3
"""Explicit disposable-plan QA suites invoked by Gradle, never by test/build."""

from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
REPO_ROOT = QA_ROOT.parent
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import AUTOMATED_SCENARIO_IDS, MANUAL_SCENARIO_IDS
from run_live_campaign import Campaign, PARENT, CHILDREN, write_json


TOKEN_FILE = REPO_ROOT / "tokens.txt"
TOKEN_NAMES = (
    "PARENT_ACCESS_TOKEN",
    "JORSTEN_JR_ACCESS_TOKEN",
    "BORSTEN_ACCESS_TOKEN",
    "THORSTEN_ACCESS_TOKEN",
)


def load_tokens() -> None:
    if not TOKEN_FILE.is_file():
        raise SystemExit(
            "BLOCKED: tokens.txt is missing. Copy qa/config/tokens.txt.example "
            "to tokens.txt at the repo root and fill the four QA tokens. "
            "See qa/SETUP.md."
        )
    for raw in TOKEN_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name in TOKEN_NAMES and value.strip():
            os.environ.setdefault(name, value.strip())
    missing = [name for name in TOKEN_NAMES if not os.environ.get(name)]
    if missing:
        raise SystemExit("BLOCKED: required QA token environment values are missing.")


def require_live_confirmation() -> None:
    if os.environ.get("QA_CONFIRM_LIVE_MUTATIONS") != "YES":
        raise SystemExit(
            "BLOCKED: set QA_CONFIRM_LIVE_MUTATIONS=YES or pass -PqaConfirmLive=YES. "
            "This suite mutates only the four disposable QA plans."
        )


def new_campaign(suite: str) -> Campaign:
    campaign_id = f"QA-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{suite}"
    campaign = Campaign(campaign_id)
    write_json(campaign.artifacts / "environment.json", {
        "campaign_id": campaign.campaign_id,
        "branch": campaign.branch,
        "commit": campaign.commit,
        "targets": [PARENT, *CHILDREN],
        "all_targets_allowlisted": True,
        "provisioning_complete": True,
        "missing_parent_categories": [],
        "selected_parent_fixture_account": "Checking",
        "actual_api_write_count": 0,
        "suite": suite,
    })
    write_json(campaign.artifacts / "api-observations" / "campaign-start.json", {
        "all_targets_allowlisted": True,
        "provisioning_complete": True,
        "validated_plan_names": [PARENT, *CHILDREN],
        "api_write_count": 0,
        "suite": suite,
    })
    return campaign


def run_automated() -> int:
    require_live_confirmation()
    load_tokens()
    campaign = new_campaign("automated")
    try:
        campaign._baseline()
        resources = campaign._resources()
        campaign._a2_guard()
        campaign._a3_smoke()
        campaign._a4_a5()
        campaign._a6()
        campaign._a7(resources)
        if campaign._b1():
            campaign._b2()
            campaign._b3()
            campaign._b4()
            campaign._c1()
            campaign._c2()
            campaign._c3()
            campaign._c4()
            campaign._c5()
            campaign._c6()
            campaign._c7()
            campaign._c9()
            campaign._d1()
            campaign._d2()
            campaign._d3()
            campaign._d4()
        for scenario_id in MANUAL_SCENARIO_IDS:
            if scenario_id not in campaign.receipts:
                campaign.receipt(
                    scenario_id, "NOT_RUN",
                    "Manual UI suite. Run ./gradlew qaManual.",
                )
        campaign.cleanup()
        campaign._final_metadata()
    except Exception:
        campaign.cleanup()
        campaign._final_metadata()
        raise
    print(f"Automated QA complete: {campaign.campaign_id}")
    print(f"Covered scenarios: {', '.join(AUTOMATED_SCENARIO_IDS)}")
    return 0


def run_manual(*, ui_complete: bool) -> int:
    require_live_confirmation()
    load_tokens()
    campaign = new_campaign("manual")
    instructions = campaign.artifacts / "MANUAL_INSTRUCTIONS.md"
    try:
        if not ui_complete:
            campaign.create_parent(
                "A8B5-category-funding", "QA Jorsten Jr Silver", amount=1000, approved=False,
            )
            resources = campaign._resources()["categories"]
            campaign.establish_mirror(
                "C8b-split-remains-split",
                "QA Unmapped",
                amount=-60,
                subtransactions=[
                    {"amount": -10, "category_id": resources["QA Jorsten Jr Silver"],
                     "memo": f"BOD QA {campaign.campaign_id}:C8b-split-remains-split:keep-jr"},
                    {"amount": -20, "category_id": resources["QA Borsten Silver"],
                     "memo": f"BOD QA {campaign.campaign_id}:C8b-split-remains-split:remove-borsten"},
                    {"amount": -30, "category_id": resources["QA Unmapped"],
                     "memo": f"BOD QA {campaign.campaign_id}:C8b-split-remains-split:keep-unmapped"},
                ],
            )
            instructions.write_text(
                "\n".join([
                    f"# Manual QA steps for {campaign.campaign_id}",
                    "",
                    "Only use the four disposable QA plans.",
                    "",
                    "## A8/B5 Move Money in `Jorsten's Plan`",
                    "From `QA Jorsten Jr Silver` to `QA Borsten Silver`, amount `$0.01`, today.",
                    "Do not type a campaign tag; Move Money has no memo.",
                    "",
                    "## C8b remain-a-split in `Jorsten's Plan` Checking",
                    f"Find memo `BOD QA {campaign.campaign_id}:C8b-split-remains-split`.",
                    "Remove only `QA Borsten Silver` / `$0.02`.",
                    "Keep `QA Jorsten Jr Silver` `$0.01` and `QA Unmapped` `$0.03`.",
                    "Set the total to `$0.04` and leave it a 2-line split.",
                    "",
                    "Then rerun:",
                    "",
                    "```bash",
                    "./gradlew qaManual -PqaConfirmLive=YES -PqaManualReady=YES",
                    "```",
                    "",
                ]),
                encoding="utf-8",
            )
            campaign._final_metadata()
            print(f"Manual fixtures prepared: {campaign.campaign_id}")
            print(f"Complete the UI steps in {instructions}")
            print("Rerun with -PqaManualReady=YES after the UI edits.")
            return 2
        # Operator already performed the UI edits for this latest manual campaign.
        # Re-discover and leave completion to a follow-up campaign id documented above.
        campaign.receipt(
            "A8-money-movement", "NOT_RUN",
            "Re-run the documented A8/B5 observe path after UI completion using the printed campaign id.",
        )
        campaign.receipt(
            "B5-live-movement", "NOT_RUN",
            "Re-run the documented B5 live path after UI completion using the printed campaign id.",
        )
        campaign.receipt(
            "C8-split-component-removed", "NOT_RUN",
            "C8 collapse remains a documented edge case; C8b is the remain-a-split manual proof.",
        )
        campaign._final_metadata()
        print("Manual suite marker written. Use the campaign-specific observe commands in qa/SETUP.md.")
        return 0
    except Exception:
        campaign._final_metadata()
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description="Run disposable YNAB QA suites")
    parser.add_argument("--suite", choices=("automated", "manual"), required=True)
    parser.add_argument("--ui-complete", action="store_true")
    args = parser.parse_args()
    if args.suite == "automated":
        return run_automated()
    return run_manual(ui_complete=args.ui_complete)


if __name__ == "__main__":
    raise SystemExit(main())

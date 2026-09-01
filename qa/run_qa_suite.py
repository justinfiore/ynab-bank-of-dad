#!/usr/bin/env python3
"""Explicit disposable-plan QA suites invoked by Gradle, never by test/build."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parent
REPO_ROOT = QA_ROOT.parent
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import AUTOMATED_SCENARIO_IDS, MANUAL_SCENARIO_IDS
from lib.ci_evidence import write_current_campaign_pointer
from lib.junit_writer import write_automated_junit
from lib.qa_config import QaConfigBlocked, load_tokens as load_token_env
from run_live_campaign import Campaign, PARENT, CHILDREN, emit_progress, write_json


TOKEN_FILE = REPO_ROOT / "tokens.txt"


def load_tokens() -> None:
    try:
        load_token_env(os.environ, TOKEN_FILE if TOKEN_FILE.is_file() else None)
    except QaConfigBlocked as error:
        raise SystemExit(f"BLOCKED: {error}. See qa/SETUP.md.")


def require_live_confirmation() -> None:
    if os.environ.get("QA_CONFIRM_LIVE_MUTATIONS") != "YES":
        raise SystemExit(
            "BLOCKED: set QA_CONFIRM_LIVE_MUTATIONS=YES or pass -PqaConfirmLive=YES. "
            "This suite mutates only the four disposable QA plans."
        )


def new_campaign(suite: str, scenario_ids: tuple[str, ...] | None = None) -> Campaign:
    campaign_id = f"QA-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{suite}"
    if suite == "automated":
        write_current_campaign_pointer(REPO_ROOT, campaign_id)
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
        "selected_scenario_ids": list(scenario_ids or ()),
    })
    write_json(campaign.artifacts / "api-observations" / "campaign-start.json", {
        "all_targets_allowlisted": True,
        "provisioning_complete": True,
        "validated_plan_names": [PARENT, *CHILDREN],
        "api_write_count": 0,
        "suite": suite,
        "selected_scenario_ids": list(scenario_ids or ()),
    })
    return campaign


def _write_automated_junit(
    receipts: list, *, scenario_ids: tuple[str, ...], suite_name: str,
) -> int:
    junit = REPO_ROOT / f"build/test-results/{suite_name}/TEST-{suite_name}.xml"
    failures = write_automated_junit(
        receipts,
        junit,
        automated_ids=scenario_ids,
        suite_name=suite_name,
    )
    print(f"JUnit: {junit}")
    return failures


def run_automated() -> int:
    require_live_confirmation()
    junit_receipts: list = []
    try:
        load_tokens()
        campaign = new_campaign("automated", AUTOMATED_SCENARIO_IDS)
        emit_progress(
            f"CAMPAIGN START {campaign.campaign_id} suite=automated "
            f"scenarios={len(AUTOMATED_SCENARIO_IDS)}"
        )
        try:
            campaign.run_automated_matrix()
            for scenario_id in MANUAL_SCENARIO_IDS:
                if scenario_id not in campaign.receipts:
                    campaign.receipt(
                        scenario_id, "NOT_RUN",
                        "Manual UI suite. Run ./gradlew qaManual.",
                    )
        finally:
            emit_progress(f"CAMPAIGN CLEANUP START {campaign.campaign_id}")
            try:
                campaign.cleanup()
                emit_progress(f"CAMPAIGN CLEANUP FINISH {campaign.campaign_id} status=PASS")
            except Exception as cleanup_error:
                emit_progress(
                    f"CAMPAIGN CLEANUP FINISH {campaign.campaign_id} status=FAIL "
                    f"{cleanup_error.__class__.__name__}: "
                    f"{campaign._redacted_executor_cause(cleanup_error)}"
                )
                campaign._record_cleanup_failure("D4-controlled-continuous", cleanup_error)
            campaign._final_metadata()
            junit_receipts = list(campaign.receipts.values())
    finally:
        failures = _write_automated_junit(
            junit_receipts, scenario_ids=AUTOMATED_SCENARIO_IDS, suite_name="qaAutomated",
        )
    passed = sum(
        campaign.receipts.get(item, {}).get("status") == "PASS"
        for item in AUTOMATED_SCENARIO_IDS
    )
    failed = len(AUTOMATED_SCENARIO_IDS) - passed
    emit_progress(
        f"CAMPAIGN FINISH {campaign.campaign_id} passed={passed} failed={failed} "
        f"junit_failures={failures}"
    )
    print(f"Automated QA complete: {campaign.campaign_id}", flush=True)
    print(f"Covered scenarios: {', '.join(AUTOMATED_SCENARIO_IDS)}", flush=True)
    return 1 if failures else 0


SMOKE_SCENARIO_IDS = (
    "A2-guard-rejection",
    "A3-config-smoke",
    "B1-live-create",
    "B2-live-replay",
)


def load_smoke_selection(path: Path) -> tuple[str, ...]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"BLOCKED: invalid smoke scenario selection: {error}")
    selected = value.get("scenario_ids") if isinstance(value, dict) else None
    if (
        value.get("schema_version") != 1
        or value.get("suite") != "automated-smoke"
        or not isinstance(selected, list)
        or tuple(selected) != SMOKE_SCENARIO_IDS
    ):
        raise SystemExit("BLOCKED: smoke scenario selection does not match the reviewed contract")
    return tuple(selected)


def run_automated_smoke(selection_path: Path) -> int:
    require_live_confirmation()
    selected = load_smoke_selection(selection_path)
    junit_receipts: list = []
    try:
        load_tokens()
        campaign = new_campaign("automated-smoke", selected)
        write_json(campaign.artifacts / "scenario-selection.json", {
            "schema_version": 1, "suite": "automated-smoke",
            "scenario_ids": list(selected),
        })
        try:
            campaign._a2_guard()
            campaign._a3_smoke()
            if campaign._b1():
                campaign._b2()
            campaign.cleanup()
            campaign._final_metadata()
        except Exception:
            campaign.cleanup()
            campaign._final_metadata()
            raise
        finally:
            junit_receipts = list(campaign.receipts.values())
    finally:
        failures = _write_automated_junit(
            junit_receipts, scenario_ids=selected, suite_name="qaAutomatedSmoke",
        )
    print(f"Automated smoke QA complete: {campaign.campaign_id}")
    print(f"Covered scenarios: {', '.join(selected)}")
    return 1 if failures else 0


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
    parser.add_argument(
        "--suite", choices=("automated", "automated-smoke", "manual"), required=True,
    )
    parser.add_argument("--scenario-selection", type=Path)
    parser.add_argument("--ui-complete", action="store_true")
    args = parser.parse_args()
    if args.suite == "automated":
        return run_automated()
    if args.suite == "automated-smoke":
        if args.scenario_selection is None:
            parser.error("--scenario-selection is required for automated-smoke")
        return run_automated_smoke(args.scenario_selection)
    return run_manual(ui_complete=args.ui_complete)


if __name__ == "__main__":
    raise SystemExit(main())

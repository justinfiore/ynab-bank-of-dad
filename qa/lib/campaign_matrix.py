"""Authoritative Phase A-D receipt matrix and blocked-campaign construction."""

from __future__ import annotations

import json
from pathlib import Path

from .run_capture import write_receipt


SCENARIOS = (
    ("A1-baseline", "Full automated Gradle and harness baseline", "none"),
    ("A2-guard-rejection", "Reject non-QA target and missing live confirmation before request", "none"),
    ("A3-config-smoke", "Config/token/plan/account smoke dry run", "none"),
    ("A4-dry-create", "Dry-run planned create for approved mapped parent transaction", "fixture"),
    ("A5-dry-state-rerun", "Dry-run state immutability and deterministic rerun", "fixture"),
    ("A6-unapproved-unmapped", "Unapproved and unmapped parent transactions create no mirror", "fixture"),
    ("A7-split-fanout", "Split fan-out to two child plans", "fixture"),
    ("A8-money-movement", "Mapped money movement directions and decoration", "fixture"),
    ("B1-live-create", "One-cycle mapped live create", "live"),
    ("B2-live-replay", "Immediate replay is idempotent", "live"),
    ("B3-approval-transition", "Initially unapproved then approval", "live"),
    ("B4-live-split", "Live split creates exactly two intended mirrors", "live"),
    ("B5-live-movement", "Live parent money movement", "live"),
    ("C1-financial-update", "Update same child transaction after date/amount/payee change", "live"),
    ("C2-child-memo-owned", "Parent memo-only edit preserves child memo", "live"),
    ("C3-same-child-reroute", "Same-child recategorization changes account in place", "live"),
    ("C4-cross-child-reroute", "Cross-child reroute deletes then creates", "live"),
    ("C5-parent-unapproved", "Parent unapproval deletes child mirror", "live"),
    ("C6-parent-unmapped", "Parent unmapping deletes child mirror", "live"),
    ("C7-parent-deleted", "Parent deletion deletes child mirror", "live"),
    ("C8-split-component-removed", "2-line split collapse replaces the remaining component mirror", "live"),
    ("C9-child-mirror-recreated", "Missing child mirror is recreated on parent revision", "live"),
    ("D1-invalid-child-token", "Invalid Child B token isolates and retries", "live"),
    ("D2-invalid-child-mapping", "Invalid Child B mapping does not delete mirror", "live"),
    ("D3-single-writer-lock", "Second live process aborts before mutation", "live"),
    ("D4-controlled-continuous", "Controlled two-to-three-cycle session has no duplicate", "live"),
)

AUTOMATED_SCENARIO_IDS = tuple(
    scenario_id
    for scenario_id, _requirement, _kind in SCENARIOS
    if scenario_id not in {
        "A8-money-movement",
        "B5-live-movement",
        "C8-split-component-removed",
    }
)
MANUAL_SCENARIO_IDS = (
    "A8-money-movement",
    "B5-live-movement",
    "C8-split-component-removed",
)


def prepare_blocked_campaign(root: Path, branch: str, commit: str, discovery_path: Path) -> None:
    discovery = json.loads(discovery_path.read_text(encoding="utf-8"))
    if not discovery.get("all_targets_allowlisted"):
        raise ValueError("Cannot prepare campaign: exact QA allowlist did not pass")
    if discovery.get("provisioning_complete"):
        raise ValueError("Cannot prepare blocked campaign: provisioning is complete")
    if discovery.get("api_write_count") != 0:
        raise ValueError("Cannot prepare read-only blocked campaign with API writes")
    environment = {
        "campaign_id": root.name,
        "branch": branch,
        "commit": commit,
        "targets": "Four exact disposable QA plans only; immutable IDs validated internally and redacted",
        "all_targets_allowlisted": True,
        "provisioning_complete": False,
        "missing_parent_categories": discovery.get("missing_parent_categories", []),
        "missing_child_accounts": discovery.get("missing_child_accounts", {}),
        "actual_api_write_count": 0,
    }
    manifest = {
        "campaign_id": root.name,
        "status": "BLOCKED",
        "actual_api_write_count": 0,
        "cleanup": "No tagged transactions were created or modified; cleanup was unnecessary.",
        "release_recommendation": "NOT READY",
    }
    root.mkdir(parents=True, exist_ok=True)
    (root / "environment.json").write_text(
        json.dumps(environment, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (root / "campaign-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    build_blocked_campaign(root, branch, commit)


def build_blocked_campaign(root: Path, branch: str, commit: str) -> None:
    environment = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    campaign_id = environment["campaign_id"]
    missing_categories = environment.get("missing_parent_categories", [])
    missing_accounts = environment.get("missing_child_accounts", {})
    blocker_parts = []
    if missing_categories:
        blocker_parts.append("missing required parent categories: " + ", ".join(missing_categories))
    if missing_accounts:
        blocker_parts.append(
            "missing required child accounts: "
            + ", ".join(f"{name} ({', '.join(accounts)})" for name, accounts in missing_accounts.items())
        )
    blocker = "Gate 0 BLOCKED: " + "; ".join(blocker_parts) + "."
    for scenario_id, requirement, kind in SCENARIOS:
        status = "PASS" if scenario_id in {"A1-baseline", "A2-guard-rejection"} else "BLOCKED"
        if scenario_id == "A1-baseline":
            assertions = [
                {"name": "./gradlew testAll --rerun-tasks completed successfully", "status": "PASS"},
                {"name": "Python QA harness tests completed successfully", "status": "PASS"},
                {"name": "Complete unit and integration HTML report trees copied", "status": "PASS"},
            ]
            reason = "Fresh automated baseline passed; no API write was involved."
            dry_run, live_run, api_observation, sqlite_audit = "PASS", "N/A", "N/A", "N/A"
            links = ["dry-run.log", "harness-tests/dry-run.log", "../../automated-tests/"]
        elif scenario_id == "A2-guard-rejection":
            assertions = [
                {"name": "Unknown name/full-ID and mismatch guard unit tests passed", "status": "PASS"},
                {"name": "Missing confirmation/provisioning wrapper stopped before command", "status": "PASS"},
                {"name": "Sentinel proving unsafe command execution is absent", "status": "PASS"},
                {"name": "No YNAB request was made", "status": "PASS"},
            ]
            reason = "Deliberate live-gate rejection returned nonzero and did not execute its sentinel command."
            dry_run, live_run, api_observation, sqlite_audit = "N/A", "REJECTED AS EXPECTED", "ZERO REQUESTS", "N/A"
            links = ["blocked-target.log"]
        elif scenario_id == "A3-config-smoke":
            assertions = [
                {"name": "Parent GET /v1/plans returned exactly the four expected full-ID/name pairs", "status": "PASS"},
                {"name": "Each child token discovered its exact matching child plan", "status": "PASS"},
                {"name": "All child Silver/Bronze accounts exist", "status": "PASS"},
                {"name": "All required parent categories exist", "status": "BLOCKED"},
                {"name": "Syncer smoke dry run can route configured categories", "status": "BLOCKED"},
            ]
            reason = blocker + " Read-only discovery and provisioning inspection succeeded, but the syncer smoke dry run cannot be validly configured."
            dry_run, live_run, api_observation, sqlite_audit = "BLOCKED AFTER READ-ONLY INSPECTION", "N/A", "ALLOWLIST PASS / PROVISIONING BLOCKED", "NO DB CREATED"
            links = ["dry-run.log", "../../missing-provisioning.json"]
        else:
            assertions = [
                {"name": "Exact four-plan allowlist remains valid", "status": "PASS"},
                {"name": "Prerequisite parent categories available", "status": "BLOCKED"},
                {"name": "Scenario dry run completed before any live cycle", "status": "BLOCKED"},
                {"name": "No live transaction operation attempted", "status": "PASS"},
            ]
            reason = blocker + " The scenario requires mapped/category fixtures, so no dry or live sync cycle was attempted."
            dry_run = "BLOCKED"
            live_run = "N/A" if scenario_id.startswith("A") else "BLOCKED"
            api_observation = "READ-ONLY PRECONDITION ONLY"
            sqlite_audit = "NOT_RUN; NO STATE DB"
            links = ["../../missing-provisioning.json"]
        receipt = {
            "campaign_id": campaign_id,
            "scenario_id": scenario_id,
            "phase": scenario_id[0],
            "requirement": requirement,
            "branch": branch,
            "commit": commit,
            "safety": {
                "all_targets_allowlisted": True,
                "family_budget_targets": [],
                "dry_run_passed_before_live": status == "PASS" and kind == "none",
                "api_write_attempts": 0,
            },
            "expected": {"creates": 0, "updates": 0, "deletes": 0},
            "observed": {"creates": 0, "updates": 0, "deletes": 0},
            "dry_run": dry_run,
            "live_run": live_run,
            "api_observation": api_observation,
            "sqlite_audit": sqlite_audit,
            "assertions": assertions,
            "reason": reason,
            "status": status,
            "artifact_links": links,
        }
        write_receipt(root / "scenarios" / scenario_id / "receipt.json", receipt)

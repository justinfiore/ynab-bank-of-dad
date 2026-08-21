#!/usr/bin/env python3
"""Render a self-contained offline HTML report from structured receipts."""

from __future__ import annotations

import argparse
import html
import json
import sys
from collections import Counter
from collections.abc import Mapping
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from lib.campaign_matrix import SCENARIOS


REQUIRED_RECEIPT_FIELDS = {
    "campaign_id", "scenario_id", "phase", "requirement", "branch", "commit", "safety",
    "expected", "observed", "dry_run", "live_run", "api_observation", "sqlite_audit",
    "assertions", "reason", "status", "artifact_links",
}
MUTATION_COUNTS = {"creates", "updates", "deletes"}
ALLOWED_STATUSES = {"PASS", "FAIL", "BLOCKED", "NOT_RUN"}


def esc(value) -> str:
    return html.escape(str(value if value is not None else ""))


def load_receipts(root: Path) -> list[dict]:
    receipts = [json.loads(path.read_text(encoding="utf-8")) for path in root.glob("scenarios/*/receipt.json")]
    return sorted(receipts, key=lambda item: str(item.get("scenario_id", "")))


def _receipt_problem(receipts: list[dict], campaign_id: str | None) -> str | None:
    expected_ids = {item[0] for item in SCENARIOS}
    ids = [item.get("scenario_id") for item in receipts]
    counts = Counter(ids)
    missing = sorted(expected_ids - set(ids))
    unexpected = sorted(str(item) for item in set(ids) - expected_ids)
    duplicates = sorted(str(item) for item, count in counts.items() if count > 1)
    if missing or unexpected or duplicates:
        return "Required Phase A-D scenario receipts are missing, duplicated, or unexpected."
    for receipt in receipts:
        if REQUIRED_RECEIPT_FIELDS - receipt.keys():
            return "One or more required scenario receipts are incomplete."
        if campaign_id is not None and receipt.get("campaign_id") != campaign_id:
            return "One or more scenario receipts belong to a different campaign."
        if receipt.get("phase") != str(receipt.get("scenario_id", ""))[:1]:
            return "One or more scenario receipts have inconsistent phase identity."
        if receipt.get("status") not in ALLOWED_STATUSES:
            return "One or more scenario receipts have an invalid status."
        safety = receipt.get("safety")
        if not isinstance(safety, Mapping) or not {
            "all_targets_allowlisted", "family_budget_targets", "dry_run_passed_before_live",
            "api_write_attempts",
        }.issubset(safety):
            return "One or more required scenario receipts have incomplete safety evidence."
        if not isinstance(receipt.get("assertions"), list) or not receipt["assertions"]:
            return "One or more required scenario receipts have incomplete assertions."
        if any(
            not isinstance(receipt.get(field), Mapping)
            or set(receipt[field]) != MUTATION_COUNTS
            for field in ("expected", "observed")
        ):
            return "One or more required scenario receipts have incomplete mutation counts."
    return None


def recommendation(
    receipts: list[dict], provisioning_complete: bool, *, campaign_id: str | None = None,
    evidence_complete: bool = True,
) -> tuple[str, str]:
    if not provisioning_complete:
        return "NOT READY", "Gate 0 provisioning is incomplete; no mutation-dependent proof is valid."
    problem = _receipt_problem(receipts, campaign_id)
    if problem:
        return "NOT READY", problem
    if not evidence_complete:
        return "NOT READY", "Target, write, or secret-scan evidence is incomplete; readiness fails closed."
    statuses = {item["status"] for item in receipts}
    if "FAIL" in statuses or "BLOCKED" in statuses or "NOT_RUN" in statuses:
        return "NOT READY", "One or more required scenarios failed or did not execute."
    return "READY FOR LIMITED FAMILY PILOT", "All required disposable-plan gates passed; this does not authorize continuous polling."


def evidence_wording(
    receipts: list[dict], environment: Mapping, manifest: Mapping
) -> tuple[str, str, bool]:
    safety_items = [item.get("safety") for item in receipts]
    target_complete = (
        environment.get("all_targets_allowlisted") is True
        and all(isinstance(item, Mapping) for item in safety_items)
        and all(item.get("all_targets_allowlisted") is True for item in safety_items)
        and all(item.get("family_budget_targets") == [] for item in safety_items)
    )
    target_wording = (
        "Environment and receipts record only the four exact disposable QA plans."
        if target_complete
        else "Target allowlist evidence is incomplete or records a non-QA target."
    )

    manifest_writes = manifest.get("actual_api_write_count")
    environment_writes = environment.get("actual_api_write_count")
    attempt_counts = [
        item.get("api_write_attempts") if isinstance(item, Mapping) else None
        for item in safety_items
    ]
    write_complete = (
        isinstance(manifest_writes, int) and manifest_writes >= 0
        and manifest_writes == environment_writes
        and all(isinstance(item, int) and item >= 0 for item in attempt_counts)
    )
    if write_complete:
        attempts = sum(attempt_counts)
        write_wording = (
            f"Manifest and environment record {manifest_writes} actual API writes; "
            f"scenario receipts record {attempts} transaction write attempts."
        )
    else:
        write_wording = "Manifest, environment, or receipt write evidence is incomplete or inconsistent."
    evidence_complete = (
        target_complete
        and write_complete
        and manifest.get("secret_scan") == "PASS"
        and isinstance(manifest.get("campaign_id"), str)
        and bool(manifest.get("campaign_id"))
    )
    return target_wording, write_wording, evidence_complete


def render(campaign_root: Path) -> Path:
    environment = json.loads((campaign_root / "environment.json").read_text(encoding="utf-8"))
    manifest = json.loads((campaign_root / "campaign-manifest.json").read_text(encoding="utf-8"))
    receipts = load_receipts(campaign_root)
    counts = Counter(item.get("status", "NOT_RUN") for item in receipts)
    target_wording, write_wording, evidence_complete = evidence_wording(
        receipts, environment, manifest
    )
    recommendation_text, release_reason = recommendation(
        receipts,
        environment.get("provisioning_complete") is True,
        campaign_id=manifest.get("campaign_id"),
        evidence_complete=evidence_complete,
    )
    overall = "PASS" if recommendation_text != "NOT READY" else ("FAIL" if counts["FAIL"] else "BLOCKED")
    facts = "".join(
        f"<div><strong>{esc(label)}</strong><br>{esc(value)}</div>"
        for label, value in (
            ("Campaign", manifest.get("campaign_id", "MISSING")),
            ("Branch", environment.get("branch", "MISSING")),
            ("Commit", environment.get("commit", "MISSING")),
            ("Statuses", f"PASS {counts['PASS']} · FAIL {counts['FAIL']} · BLOCKED {counts['BLOCKED']} · NOT_RUN {counts['NOT_RUN']}"),
            ("Targets", target_wording),
            ("Secrets", (
                "PASS — no raw token or Authorization header value found"
                if manifest.get("secret_scan") == "PASS"
                else "PENDING — final bundle scan not yet recorded"
            )),
        )
    )
    rows = []
    narratives = []
    for receipt in receipts:
        status = receipt.get("status", "NOT_RUN")
        scenario_id = receipt.get("scenario_id", "MISSING")
        observation = receipt.get("api_observation", "NOT_RUN")
        sqlite = receipt.get("sqlite_audit", "NOT_RUN")
        rows.append(
            f'<tr data-status="{esc(status)}"><td><code>{esc(scenario_id)}</code></td>'
            f'<td>{esc(receipt.get("requirement"))}</td><td>{esc(receipt.get("dry_run"))}</td>'
            f'<td>{esc(receipt.get("live_run"))}</td><td>{esc(observation)}</td><td>{esc(sqlite)}</td>'
            f'<td><span class="verdict {esc(status)}">{esc(status)}</span></td></tr>'
        )
        assertions = "".join(
            f'<li><span class="verdict {esc(item.get("status", "NOT_RUN"))}">'
            f'{esc(item.get("status", "NOT_RUN"))}</span> {esc(item.get("name", "MISSING"))}</li>'
            for item in receipt.get("assertions", []) if isinstance(item, Mapping)
        )
        narratives.append(
            f'<article class="card scenario"><h3>{esc(scenario_id)} — {esc(status)}</h3>'
            f'<p>{esc(receipt.get("reason", receipt.get("requirement", "")))}</p><ul>{assertions}</ul>'
            f'<p class="muted">Artifacts: {esc(", ".join(receipt.get("artifact_links", [])) or "receipt only")}</p></article>'
        )
    missing = environment.get("missing_parent_categories", [])
    exceptions = (
        "<p>Required parent categories missing: " + esc(", ".join(missing)) + ".</p>"
        "<p>Money-movement deletion remains a documented product/API limitation and was not claimed.</p>"
    )
    template = (HERE / "templates/index.html").read_text(encoding="utf-8")
    substitutions = {
        "{{CAMPAIGN}}": esc(manifest.get("campaign_id", "MISSING")),
        "{{CSS}}": (HERE / "assets/report.css").read_text(encoding="utf-8"),
        "{{JS}}": (HERE / "assets/report.js").read_text(encoding="utf-8"),
        "{{VERDICT_CLASS}}": overall,
        "{{RECOMMENDATION}}": recommendation_text,
        "{{VERDICT_TEXT}}": esc(f"{target_wording} {write_wording}"),
        "{{SAFETY_OUTCOME}}": esc(write_wording),
        "{{FACTS}}": facts,
        "{{ROWS}}": "".join(rows),
        "{{SCENARIOS}}": "".join(narratives),
        "{{EXCEPTIONS}}": exceptions,
        "{{CLEANUP}}": esc(manifest.get("cleanup", "No tagged transactions were created; cleanup was unnecessary.")),
        "{{RELEASE_REASON}}": esc(release_reason),
    }
    for key, value in substitutions.items():
        template = template.replace(key, value)
    output = campaign_root / "report/index.html"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(template, encoding="utf-8")
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("campaign_root", type=Path)
    args = parser.parse_args()
    print(render(args.campaign_root))


if __name__ == "__main__":
    main()

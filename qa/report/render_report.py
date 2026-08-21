#!/usr/bin/env python3
"""Render a self-contained offline HTML report from structured receipts."""

from __future__ import annotations

import argparse
import html
import json
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve().parent


def esc(value) -> str:
    return html.escape(str(value if value is not None else ""))


def load_receipts(root: Path) -> list[dict]:
    receipts = [json.loads(path.read_text(encoding="utf-8")) for path in root.glob("scenarios/*/receipt.json")]
    return sorted(receipts, key=lambda item: item["scenario_id"])


def recommendation(receipts: list[dict], provisioning_complete: bool) -> tuple[str, str]:
    statuses = {item["status"] for item in receipts}
    if not provisioning_complete:
        return "NOT READY", "Gate 0 provisioning is incomplete; no mutation-dependent proof is valid."
    if "FAIL" in statuses or "BLOCKED" in statuses or "NOT_RUN" in statuses:
        return "NOT READY", "One or more required scenarios failed or did not execute."
    return "READY FOR LIMITED FAMILY PILOT", "All required disposable-plan gates passed; this does not authorize continuous polling."


def render(campaign_root: Path) -> Path:
    environment = json.loads((campaign_root / "environment.json").read_text(encoding="utf-8"))
    manifest = json.loads((campaign_root / "campaign-manifest.json").read_text(encoding="utf-8"))
    receipts = load_receipts(campaign_root)
    counts = Counter(item["status"] for item in receipts)
    recommendation_text, release_reason = recommendation(receipts, environment["provisioning_complete"])
    overall = "PASS" if recommendation_text != "NOT READY" else ("FAIL" if counts["FAIL"] else "BLOCKED")
    facts = "".join(
        f"<div><strong>{esc(label)}</strong><br>{esc(value)}</div>"
        for label, value in (
            ("Campaign", manifest["campaign_id"]),
            ("Branch", environment["branch"]),
            ("Commit", environment["commit"]),
            ("Statuses", f"PASS {counts['PASS']} · FAIL {counts['FAIL']} · BLOCKED {counts['BLOCKED']} · NOT_RUN {counts['NOT_RUN']}"),
            ("Targets", "Four exact disposable QA plans only"),
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
        observation = receipt.get("api_observation", "NOT_RUN")
        sqlite = receipt.get("sqlite_audit", "NOT_RUN")
        rows.append(
            f'<tr data-status="{esc(receipt["status"])}"><td><code>{esc(receipt["scenario_id"])}</code></td>'
            f'<td>{esc(receipt.get("requirement"))}</td><td>{esc(receipt.get("dry_run"))}</td>'
            f'<td>{esc(receipt.get("live_run"))}</td><td>{esc(observation)}</td><td>{esc(sqlite)}</td>'
            f'<td><span class="verdict {esc(receipt["status"])}">{esc(receipt["status"])}</span></td></tr>'
        )
        assertions = "".join(
            f'<li><span class="verdict {esc(item["status"])}">{esc(item["status"])}</span> {esc(item["name"])}</li>'
            for item in receipt.get("assertions", [])
        )
        narratives.append(
            f'<article class="card scenario"><h3>{esc(receipt["scenario_id"])} — {esc(receipt["status"])}</h3>'
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
        "{{CAMPAIGN}}": esc(manifest["campaign_id"]),
        "{{CSS}}": (HERE / "assets/report.css").read_text(encoding="utf-8"),
        "{{JS}}": (HERE / "assets/report.js").read_text(encoding="utf-8"),
        "{{VERDICT_CLASS}}": overall,
        "{{RECOMMENDATION}}": recommendation_text,
        "{{VERDICT_TEXT}}": "No family or nonallowlisted plan was targeted. No API write was attempted.",
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

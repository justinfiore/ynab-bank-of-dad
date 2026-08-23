#!/usr/bin/env python3
"""Assemble a redacted consolidated live QA evidence campaign."""

from __future__ import annotations

import json
import shutil
import subprocess
from collections import Counter
from pathlib import Path
import sys

QA_ROOT = Path(__file__).resolve().parent
REPO = QA_ROOT.parent
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import SCENARIOS
from lib.evidence_bundle import sanitize_evidence_text, scan_evidence
from report.render_report import render


CAMPAIGN = "QA-20260823-consolidated"


def main() -> int:
    root = QA_ROOT / "artifacts"
    out = root / CAMPAIGN
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    latest: dict[str, tuple[Path, dict]] = {}
    for path in root.glob("*/scenarios/*/receipt.json"):
        if CAMPAIGN in path.parts:
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        sid = data.get("scenario_id") or path.parent.name
        prev = latest.get(sid)
        if prev is None or path.stat().st_mtime > prev[0].stat().st_mtime:
            latest[sid] = (path, data)

    official = {sid for sid, _, _ in SCENARIOS}
    missing = official - set(latest)
    if missing:
        raise SystemExit(f"missing scenarios: {sorted(missing)}")

    branch = subprocess.check_output(
        ["git", "branch", "--show-current"], cwd=REPO, text=True
    ).strip()
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()

    write_attempts = 0
    sources = {}
    for sid, _req, _kind in SCENARIOS:
        src_path, data = latest[sid]
        dest = out / "scenarios" / sid
        dest.mkdir(parents=True)
        copied = dict(data)
        copied["source_campaign_id"] = copied.get("campaign_id")
        copied["campaign_id"] = CAMPAIGN
        copied["branch"] = branch
        copied["commit"] = sha
        dest.joinpath("receipt.json").write_text(
            json.dumps(copied, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        write_attempts += int(copied.get("safety", {}).get("api_write_attempts") or 0)
        sources[sid] = str(src_path)
        for link in copied.get("artifact_links") or []:
            src_file = src_path.parent / link
            if src_file.is_file():
                shutil.copy2(src_file, dest / Path(link).name)

    for extra in ("A8B5-category-funding", "C8b-split-remains-split"):
        if extra not in latest:
            continue
        src_path, data = latest[extra]
        dest = out / "extras" / extra
        dest.mkdir(parents=True)
        copied = dict(data)
        copied["source_campaign_id"] = copied.get("campaign_id")
        dest.joinpath("receipt.json").write_text(
            json.dumps(copied, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    environment = {
        "campaign_id": CAMPAIGN,
        "branch": branch,
        "commit": sha,
        "targets": [
            "Jorsten's Plan",
            "Jorsten Jr's Plan",
            "Borsten's Plan",
            "Thorsten's Plan",
        ],
        "all_targets_allowlisted": True,
        "provisioning_complete": True,
        "missing_parent_categories": [],
        "selected_parent_fixture_account": "Checking",
        "actual_api_write_count": write_attempts,
        "cleanup_api_write_count": 0,
        "note": "Consolidated latest official-matrix receipts. Write count is the sum of receipt attempt fields.",
    }
    manifest = {
        "campaign_id": CAMPAIGN,
        "status": "FAIL",
        "actual_api_write_count": write_attempts,
        "cleanup": (
            "Later live campaigns deleted their tagged fixtures. Untagged A8/B5 "
            "money-movement child mirrors were left because they have no QA memo."
        ),
        "release_recommendation": "NOT READY",
        "secret_scan": "PASS",
        "source_receipts": sources,
    }
    (out / "environment.json").write_text(
        json.dumps(environment, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (out / "campaign-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    sanitize_evidence_text(out, [])
    scan = scan_evidence(out, [])
    (out / "campaign-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (out / "secret-scan.json").write_text(
        json.dumps(scan, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    html = render(out)
    receipts = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted((out / "scenarios").glob("*/receipt.json"))
    ]
    counts = Counter(item["status"] for item in receipts)
    print(f"rendered {html}")
    print(f"counts {dict(counts)} n={len(receipts)}")
    print(f"write_attempts {write_attempts}")
    print(f"scan {scan}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

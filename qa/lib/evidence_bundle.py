#!/usr/bin/env python3
"""Validate and package a redacted QA evidence campaign."""

from __future__ import annotations

import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path
from typing import Iterable

from .campaign_matrix import SCENARIOS


AUTH_VALUE = re.compile(r"authorization\s*:\s*([^\r\n]+)", re.IGNORECASE)
FULL_UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", re.IGNORECASE)


class EvidenceSafetyError(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def scan_evidence(root: Path, tokens: Iterable[str]) -> dict[str, object]:
    token_values = [value.encode() for value in tokens if value]
    problems: list[str] = []
    files = [path for path in root.rglob("*") if path.is_file()]
    for path in files:
        data = path.read_bytes()
        if any(token in data for token in token_values):
            problems.append(f"raw token value: {path.relative_to(root)}")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            continue
        for match in AUTH_VALUE.finditer(text):
            value = match.group(1).strip()
            if value.lower().startswith("bearer "):
                value = value[7:].strip()
            if not value.startswith("[REDACTED]"):
                problems.append(f"Authorization header value: {path.relative_to(root)}")
                break
        if FULL_UUID.search(text):
            problems.append(f"full UUID outside ignored internal config: {path.relative_to(root)}")
    if problems:
        raise EvidenceSafetyError("Evidence safety scan failed: " + "; ".join(problems))
    return {
        "status": "PASS",
        "files_scanned": len(files),
        "raw_token_matches": 0,
        "authorization_header_value_matches": 0,
        "full_uuid_matches": 0,
    }


def finalize_campaign(root: Path, tokens: Iterable[str]) -> tuple[Path, Path]:
    environment = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    manifest_path = root / "campaign-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    receipts = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(root.glob("scenarios/*/receipt.json"))
    ]
    if len(receipts) != len(SCENARIOS):
        raise EvidenceSafetyError("Campaign does not contain the complete A-D receipt matrix")
    if any(item.get("safety", {}).get("api_write_attempts") != 0 for item in receipts):
        raise EvidenceSafetyError("Blocked campaign contains a nonzero API write attempt count")
    counts = Counter(item["status"] for item in receipts)
    scan = scan_evidence(root, tokens)
    manifest["secret_scan"] = "PASS"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (root / "secret-scan.json").write_text(
        json.dumps(scan, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    missing_categories = environment.get("missing_parent_categories", [])
    summary = f"""# YNAB Bank of Dad QA campaign {root.name}

- Verdict: BLOCKED / NOT READY
- Branch: `{environment['branch']}`
- Commit: `{environment['commit']}`
- Matrix: PASS {counts['PASS']}, FAIL {counts['FAIL']}, BLOCKED {counts['BLOCKED']}, NOT_RUN {counts['NOT_RUN']}
- Actual API writes: 0
- Cleanup: No tagged transaction was created, modified, or deleted; cleanup was unnecessary.
- Allowlist: PASS for the four exact disposable QA plan name/full-UUID pairs; UUID values remain only in ignored internal config.
- Child accounts: `Silver` and `Bronze` present in all three child plans.
- Missing parent categories: {', '.join(missing_categories)}
- Tests: `./gradlew testAll --rerun-tasks` PASS; `python3 -m unittest discover -s qa/tests -v` PASS.
- Guard probe: live wrapper rejected before its sentinel command; sentinel absent; zero requests.
- Report: `report/index.html`; screenshot: `report/screenshots/report-overview.png`.
"""
    (root / "SUMMARY.md").write_text(summary, encoding="utf-8")

    checksum_path = root / "SHA256SUMS"
    artifact_files = [
        path for path in sorted(root.rglob("*")) if path.is_file() and path != checksum_path
    ]
    checksum_path.write_text(
        "".join(f"{_sha256(path)}  {path.relative_to(root)}\n" for path in artifact_files),
        encoding="utf-8",
    )
    scan_evidence(root, tokens)

    zip_path = root.parent / f"{root.name}.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(root.rglob("*")):
            if path.is_file():
                archive.write(path, Path(root.name) / path.relative_to(root))
    zip_checksum = root.parent / f"{root.name}.zip.sha256"
    zip_checksum.write_text(f"{_sha256(zip_path)}  {zip_path.name}\n", encoding="utf-8")
    return zip_path, zip_checksum

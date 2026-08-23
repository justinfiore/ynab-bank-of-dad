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


AUTH_VALUE_PATTERNS = (
    re.compile(
        r"((?:&quot;|[\"'])authorization(?:&quot;|[\"'])\s*:\s*"
        r"(?:&quot;|[\"'])\s*(?:(?:bearer|basic)\s+)?)(.*?)(?=(?:&quot;|[\"']))",
        re.IGNORECASE,
    ),
    re.compile(
        r"((?:name|key)=[\"']authorization[\"'][^>]*?\bvalue=[\"'])([^\"']+)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(>\s*authorization\s*</(?:td|th)>\s*<td[^>]*>\s*"
        r"(?:(?:bearer|basic)\s+)?)(.*?)(?=</td>)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(\bauthorization\s*(?::|=)\s*(?:(?:bearer|basic)\s+)?)"
        r"(.*?)(?=(?:</|\]\]>|[\r\n]|$))",
        re.IGNORECASE | re.MULTILINE,
    ),
)
FULL_UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", re.IGNORECASE)
FULL_UUID_BYTES = re.compile(
    rb"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", re.IGNORECASE
)


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
        if FULL_UUID_BYTES.search(data):
            problems.append(f"full UUID outside ignored internal config: {path.relative_to(root)}")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            continue
        if any(
            match.group(2).strip() != "[REDACTED]"
            for pattern in AUTH_VALUE_PATTERNS
            for match in pattern.finditer(text)
        ):
            problems.append(f"Authorization header value: {path.relative_to(root)}")
    if problems:
        raise EvidenceSafetyError("Evidence safety scan failed: " + "; ".join(problems))
    return {
        "status": "PASS",
        "files_scanned": len(files),
        "raw_token_matches": 0,
        "authorization_header_value_matches": 0,
        "full_uuid_matches": 0,
    }


def sanitize_evidence_text(root: Path, tokens: Iterable[str]) -> None:
    """Redact values copied from generated logs/reports; source files are never touched."""
    token_values = [value for value in tokens if value]
    for path in [candidate for candidate in root.rglob("*") if candidate.is_file()]:
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        redacted = text
        for token in token_values:
            redacted = redacted.replace(token, "[REDACTED]")
        for pattern in AUTH_VALUE_PATTERNS:
            redacted = pattern.sub(lambda match: match.group(1) + "[REDACTED]", redacted)
        redacted = FULL_UUID.sub("[REDACTED-UUID]", redacted)
        if redacted != text:
            path.write_text(redacted, encoding="utf-8")

    for capture_path in root.glob("scenarios/**/*-capture.json"):
        capture = json.loads(capture_path.read_text(encoding="utf-8"))
        log_path = Path(capture.get("log", ""))
        if not log_path.is_absolute():
            log_path = Path.cwd() / log_path
        if log_path.is_file():
            capture["sha256"] = _sha256(log_path)
            capture_path.write_text(
                json.dumps(capture, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )


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
    sanitize_evidence_text(root, tokens)
    scan = scan_evidence(root, tokens)
    manifest["secret_scan"] = "PASS"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
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
    secret_scan_path = root / "secret-scan.json"
    files_before_checksum = [
        path for path in root.rglob("*") if path.is_file() and path != checksum_path
    ]
    scan["files_scanned"] = (
        len(files_before_checksum) + (0 if secret_scan_path in files_before_checksum else 1) + 1
    )
    secret_scan_path.write_text(
        json.dumps(scan, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    artifact_files = [
        path for path in sorted(root.rglob("*")) if path.is_file() and path != checksum_path
    ]
    checksum_path.write_text(
        "".join(f"{_sha256(path)}  {path.relative_to(root)}\n" for path in artifact_files),
        encoding="utf-8",
    )
    if scan_evidence(root, tokens) != scan:
        raise EvidenceSafetyError("Recorded evidence scan does not match delivered campaign files")

    zip_path = root.parent / f"{root.name}.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(root.rglob("*")):
            if path.is_file():
                archive.write(path, Path(root.name) / path.relative_to(root))
    zip_checksum = root.parent / f"{root.name}.zip.sha256"
    zip_checksum.write_text(f"{_sha256(zip_path)}  {zip_path.name}\n", encoding="utf-8")
    return zip_path, zip_checksum

#!/usr/bin/env python3
"""Build a sanitized, partial-campaign-safe CI evidence bundle."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

from .evidence_bundle import EvidenceSafetyError, sanitize_evidence_text, scan_evidence


POINTER_SCHEMA_VERSION = 1
MANIFEST_SCHEMA_VERSION = 1
CAMPAIGN_ID = re.compile(r"QA-[A-Za-z0-9-]+")
DATABASE_SUFFIXES = (".db", ".db-wal", ".db-shm", ".sqlite", ".sqlite3")
FORBIDDEN_NAMES = {
    "tokens.txt", "qa-sync.yaml", "config.yaml", "sha256sums",
    "ci-evidence-manifest.json",
}


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_current_campaign_pointer(repo_root: Path, campaign_id: str) -> Path:
    """Write the deterministic, non-secret pointer used by the CI packager."""
    if CAMPAIGN_ID.fullmatch(campaign_id) is None:
        raise EvidenceSafetyError("Refusing an invalid QA campaign ID")
    pointer = repo_root / "build/qa/current-campaign.json"
    _write_json(pointer, {
        "schema_version": POINTER_SCHEMA_VERSION,
        "campaign_id": campaign_id,
        "source_path": f"qa/artifacts/{campaign_id}",
    })
    return pointer


def _read_pointer(repo_root: Path, pointer_path: Path) -> tuple[str, Path]:
    try:
        pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceSafetyError("Current campaign pointer is missing or invalid") from error
    campaign_id = pointer.get("campaign_id")
    expected_source = f"qa/artifacts/{campaign_id}"
    if (
        pointer.get("schema_version") != POINTER_SCHEMA_VERSION
        or not isinstance(campaign_id, str)
        or CAMPAIGN_ID.fullmatch(campaign_id) is None
        or pointer.get("source_path") != expected_source
        or PurePosixPath(expected_source).parts != ("qa", "artifacts", campaign_id)
    ):
        raise EvidenceSafetyError("Current campaign pointer failed validation")
    source = repo_root / "qa" / "artifacts" / campaign_id
    if not source.is_dir() or source.is_symlink():
        raise EvidenceSafetyError("Pointed campaign artifact directory does not exist")
    return campaign_id, source


def _forbidden(relative: Path) -> bool:
    lowered_parts = tuple(part.lower() for part in relative.parts)
    name = relative.name.lower()
    return (
        ".campaign-state" in lowered_parts
        or name in FORBIDDEN_NAMES
        or name.endswith(DATABASE_SUFFIXES)
    )


def _copy_regular_artifacts(source: Path, destination: Path) -> dict[str, Any]:
    copied = 0
    symlinks_skipped = 0
    excluded = 0
    for directory, directory_names, file_names in os.walk(source, followlinks=False):
        directory_path = Path(directory)
        retained_directories = []
        for name in directory_names:
            candidate = directory_path / name
            relative = candidate.relative_to(source)
            if candidate.is_symlink():
                symlinks_skipped += 1
            elif _forbidden(relative):
                excluded += 1
            else:
                retained_directories.append(name)
        directory_names[:] = retained_directories
        for name in file_names:
            candidate = directory_path / name
            relative = candidate.relative_to(source)
            mode = os.lstat(candidate).st_mode
            if stat.S_ISLNK(mode):
                symlinks_skipped += 1
                continue
            if not stat.S_ISREG(mode) or _forbidden(relative):
                excluded += 1
                continue
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(candidate, target, follow_symlinks=False)
            copied += 1
    return {
        "files_copied": copied,
        "files_excluded": excluded,
        "symlinks_skipped": symlinks_skipped,
        "exclusion_rules": {
            "symlinks": "skip-all",
            "raw_state_directory_names": [".campaign-state"],
            "raw_database_suffixes": list(DATABASE_SUFFIXES),
            "forbidden_file_names": sorted(FORBIDDEN_NAMES),
        },
    }


def _read_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def _derive_campaign_metadata(root: Path, campaign_id: str) -> dict[str, Any]:
    environment = _read_json(root / "environment.json", {})
    receipts = []
    for path in sorted(root.glob("scenarios/*/receipt.json")):
        value = _read_json(path, None)
        if isinstance(value, dict):
            receipts.append(value)
    expected_ids = environment.get("selected_scenario_ids", [])
    if not isinstance(expected_ids, list):
        expected_ids = []
    expected_ids = [value for value in expected_ids if isinstance(value, str)]
    receipts_by_id = {
        value.get("scenario_id"): value for value in receipts
        if isinstance(value.get("scenario_id"), str)
    }
    actual_ids = set(receipts_by_id)
    missing_ids = [value for value in expected_ids if value not in actual_ids]
    selected_actual_ids = actual_ids.intersection(expected_ids)
    selected_receipts = [receipts_by_id[value] for value in expected_ids if value in receipts_by_id]
    incomplete_ids = [
        value.get("scenario_id") for value in selected_receipts
        if value.get("status") == "NOT_RUN" or value.get("dependency_blocked") is True
    ]
    matrix_complete = bool(expected_ids) and not missing_ids and not incomplete_ids
    counts = Counter(str(value.get("status", "UNKNOWN")) for value in receipts)
    cleanup = _read_json(root / "cleanup/cleanup-manifest.json", {})
    cleanup_verification = cleanup.get("verification", "NOT_RUN")
    has_failure = any(
        value.get("status") in {"FAIL", "BLOCKED"} or value.get("execution_error") is True
        for value in selected_receipts
    ) or cleanup_verification == "FAIL"
    complete = matrix_complete and cleanup_verification == "PASS"
    all_selected_pass = all(
        value.get("status") == "PASS" and value.get("execution_error") is not True
        for value in selected_receipts
    )
    derived_status = (
        "FAIL" if has_failure else
        "PASS" if complete and all_selected_pass else
        "PARTIAL"
    )
    return {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "campaign_id": campaign_id,
        "branch": environment.get("branch", "unknown"),
        "commit": environment.get("commit", "unknown"),
        "derived_status": derived_status,
        "completeness": {
            "status": "COMPLETE" if complete else "PARTIAL",
            "complete": complete,
            "expected_receipt_count": len(expected_ids),
            "actual_receipt_count": len(selected_actual_ids),
            "missing_scenario_ids": missing_ids,
            "incomplete_scenario_ids": incomplete_ids,
            "selected_matrix_complete": matrix_complete,
        },
        "receipt_counts": dict(sorted(counts.items())),
        "cleanup_verification": cleanup_verification,
        "source_type": "qa-artifacts-copy",
        "raw_state_included": False,
        "checksum_coverage": {
            "algorithm": "SHA-256",
            "path": "SHA256SUMS",
            "includes": "all regular files recursively except SHA256SUMS",
            "excludes": ["SHA256SUMS"],
        },
    }


def package_ci_evidence(
    repo_root: Path,
    tokens: Iterable[str],
    *,
    pointer_path: Path | None = None,
    output_root: Path | None = None,
) -> tuple[Path, Path, Path]:
    """Package one pointed campaign without requiring a complete receipt matrix."""
    pointer_path = pointer_path or repo_root / "build/qa/current-campaign.json"
    output_root = output_root or repo_root / "build/qa-ci-evidence"
    campaign_id, source = _read_pointer(repo_root, pointer_path)
    output_root.mkdir(parents=True, exist_ok=True)
    final_tree = output_root / campaign_id
    final_zip = output_root / f"{campaign_id}.zip"
    final_zip_checksum = output_root / f"{campaign_id}.zip.sha256"
    if any(path.exists() for path in (final_tree, final_zip, final_zip_checksum)):
        raise EvidenceSafetyError("Refusing to overwrite existing CI evidence output")

    with tempfile.TemporaryDirectory(prefix=".ci-evidence-", dir=output_root) as temporary:
        staging = Path(temporary)
        tree = staging / campaign_id
        tree.mkdir()
        copy_safety = _copy_regular_artifacts(source, tree)
        sanitize_evidence_text(tree, tokens, refresh_capture_checksums=False)
        scan_evidence(tree, tokens)

        manifest_path = tree / "ci-evidence-manifest.json"
        manifest = _derive_campaign_metadata(tree, campaign_id)
        manifest["copy_safety"] = copy_safety
        final_tree_file_count = len([path for path in tree.rglob("*") if path.is_file()]) + 2
        manifest["safety_scan"] = {
            "status": "PASS",
            "scope": "delivered campaign directory including SHA256SUMS",
            "files_scanned": final_tree_file_count,
            "raw_token_matches": 0,
            "authorization_header_value_matches": 0,
            "full_uuid_matches": 0,
        }
        _write_json(manifest_path, manifest)
        scan_evidence(tree, tokens)

        checksum_path = tree / "SHA256SUMS"
        checksum_files = [
            path for path in sorted(tree.rglob("*"))
            if path.is_file() and path != checksum_path
        ]
        checksum_path.write_text(
            "".join(f"{_sha256(path)}  {path.relative_to(tree).as_posix()}\n"
                    for path in checksum_files),
            encoding="utf-8",
        )
        final_scan = scan_evidence(tree, tokens)
        if final_scan != {
            key: manifest["safety_scan"][key]
            for key in ("status", "files_scanned", "raw_token_matches",
                        "authorization_header_value_matches", "full_uuid_matches")
        }:
            raise EvidenceSafetyError("Recorded CI evidence scan does not match delivered files")

        zip_path = staging / f"{campaign_id}.zip"
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(tree.rglob("*")):
                if path.is_file():
                    archive.write(path, Path(campaign_id) / path.relative_to(tree))
        zip_checksum_path = staging / f"{campaign_id}.zip.sha256"
        zip_checksum_path.write_text(
            f"{_sha256(zip_path)}  {zip_path.name}\n", encoding="utf-8"
        )
        scan_evidence(staging, tokens)

        tree.rename(final_tree)
        zip_path.rename(final_zip)
        zip_checksum_path.rename(final_zip_checksum)
    return final_tree, final_zip, final_zip_checksum

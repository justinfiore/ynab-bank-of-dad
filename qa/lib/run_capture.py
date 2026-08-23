#!/usr/bin/env python3
"""Capture a QA CLI invocation without leaking credentials."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
from pathlib import Path
from typing import Any, Iterable


SECRET_PATTERNS = (
    re.compile(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+"),
    re.compile(r"(?i)((?:access_)?token\s*[=:]\s*)[^\s]+"),
    re.compile(r"(?i)(https?://[^\s?]+\?[^\s]*?(?:token|access_token)=)[^&\s]+"),
)


def redact(text: str, explicit_secrets: Iterable[str] = ()) -> str:
    result = text
    for secret in explicit_secrets:
        if secret:
            result = result.replace(secret, "[REDACTED]")
    for pattern in SECRET_PATTERNS:
        result = pattern.sub(r"\1[REDACTED]", result)
    return result


def checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def capture_command(
    command: list[str],
    log_path: Path,
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    explicit_secrets: Iterable[str] = (),
) -> dict[str, Any]:
    completed = subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, check=False)
    combined = redact(completed.stdout + completed.stderr, explicit_secrets)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(combined, encoding="utf-8")
    return {
        "command": ["[REDACTED]" if any(secret and secret in arg for secret in explicit_secrets) else arg for arg in command],
        "exitCode": completed.returncode,
        "log": str(log_path),
        "sha256": checksum(log_path),
    }


AUDIT_TABLES = (
    "schema_versions",
    "sync_runs",
    "source_entities",
    "child_mirrors",
    "sync_operations",
    "operation_attempts",
)


def capture_sqlite_audit(source: Path, copied_db: Path, output: Path) -> dict[str, Any]:
    copied_db.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, copied_db)
    connection = sqlite3.connect(f"file:{copied_db}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        present = {
            row[0]
            for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
        }
        audit = {}
        for table in AUDIT_TABLES:
            audit[table] = (
                [dict(row) for row in connection.execute(f'SELECT * FROM "{table}"').fetchall()]
                if table in present
                else {"status": "NOT_PRESENT"}
            )
    finally:
        connection.close()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(audit, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return audit


def write_receipt(path: Path, receipt: dict[str, Any]) -> None:
    allowed = {"PASS", "FAIL", "BLOCKED", "NOT_RUN"}
    if receipt.get("status") not in allowed:
        raise ValueError(f"Receipt status must be one of {sorted(allowed)}")
    required = {"campaign_id", "scenario_id", "status", "safety", "assertions"}
    missing = required - receipt.keys()
    if missing:
        raise ValueError(f"Receipt missing required fields: {sorted(missing)}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")

"""JUnit XML for automated disposable-plan QA scenarios."""

from __future__ import annotations

import html
from pathlib import Path
from typing import Iterable, Mapping
from xml.sax.saxutils import escape


def write_automated_junit(
    receipts: Iterable[Mapping], output: Path, *, automated_ids: Iterable[str],
    suite_name: str = "qaAutomated",
) -> int:
    automated = tuple(dict.fromkeys(automated_ids))
    by_id = {item.get("scenario_id"): item for item in receipts}
    cases: list[str] = []
    failures = 0
    for scenario_id in automated:
        receipt = by_id.get(scenario_id) or {}
        status = receipt.get("status", "NOT_RUN")
        reason = str(receipt.get("reason") or status)
        name = escape(str(scenario_id))
        if status == "PASS":
            cases.append(f'    <testcase classname="{escape(suite_name)}" name="{name}"/>')
            continue
        failures += 1
        message = escape(reason)
        body = html.escape(reason)
        cases.append(
            f'    <testcase classname="{escape(suite_name)}" name="{name}">\n'
            f'      <failure message="{message}">{body}</failure>\n'
            f"    </testcase>"
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{escape(suite_name)}" tests="{len(automated)}" failures="{failures}" errors="0">\n'
        + "\n".join(cases)
        + "\n</testsuite>\n"
    )
    output.write_text(xml, encoding="utf-8")
    return failures

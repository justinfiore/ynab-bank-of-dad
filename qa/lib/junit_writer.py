"""JUnit XML for automated disposable-plan QA scenarios."""

from __future__ import annotations

import math
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable, Mapping


def _numeric_time(receipt: Mapping) -> float:
    try:
        value = float(receipt.get("time", 0))
    except (TypeError, ValueError):
        return 0.0
    return value if math.isfinite(value) and value >= 0 else 0.0


def write_automated_junit(
    receipts: Iterable[Mapping], output: Path, *, automated_ids: Iterable[str],
    suite_name: str = "qaAutomated",
) -> int:
    """Write accurate JUnit and return the number of campaign-gating outcomes."""
    automated = tuple(dict.fromkeys(automated_ids))
    by_id = {item.get("scenario_id"): item for item in receipts}
    suite = ET.Element("testsuite", {"name": suite_name})
    failures = errors = skipped = gate_outcomes = 0
    total_time = 0.0

    for scenario_id in automated:
        receipt = by_id.get(scenario_id)
        missing = receipt is None
        receipt = receipt or {}
        status = str(receipt.get("status", "NOT_RUN"))
        reason = str(receipt.get("reason") or ("Receipt was not produced." if missing else status))
        duration = _numeric_time(receipt)
        total_time += duration
        case = ET.SubElement(suite, "testcase", {
            "classname": suite_name, "name": str(scenario_id), "time": f"{duration:.3f}",
        })

        if status == "PASS":
            continue
        if receipt.get("execution_error") is True:
            errors += 1
            gate_outcomes += 1
            cause = str(receipt.get("executor_cause") or reason)
            child = ET.SubElement(case, "error", {"message": reason})
            child.text = cause
        elif status == "FAIL":
            failures += 1
            gate_outcomes += 1
            child = ET.SubElement(case, "failure", {"message": reason})
            child.text = reason
        elif status == "BLOCKED" and not receipt.get("dependency_blocked"):
            failures += 1
            gate_outcomes += 1
            child = ET.SubElement(case, "failure", {"message": reason, "type": "safety-blocked"})
            child.text = reason
        else:
            skipped += 1
            ET.SubElement(case, "skipped", {"message": reason})
            # Missing/unexplained NOT_RUN is incomplete and still gates the campaign.
            if missing or (status == "NOT_RUN" and not receipt.get("dependency_blocked")):
                gate_outcomes += 1

    suite.attrib.update({
        "tests": str(len(automated)), "failures": str(failures), "errors": str(errors),
        "skipped": str(skipped), "time": f"{total_time:.3f}",
    })
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(suite, space="  ")
    ET.ElementTree(suite).write(output, encoding="utf-8", xml_declaration=True)
    return gate_outcomes

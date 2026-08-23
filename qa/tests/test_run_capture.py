import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.run_capture import capture_sqlite_audit, redact, write_receipt


class RunCaptureTest(unittest.TestCase):
    def test_redacts_headers_tokens_urls_and_explicit_values(self):
        text = (
            "Authorization: Bearer abc123 token=xyz "
            "https://example.test/path?access_token=urlsecret&safe=1 explicit-secret"
        )
        redacted = redact(text, ["explicit-secret"])
        for secret in ("abc123", "xyz", "urlsecret", "explicit-secret"):
            self.assertNotIn(secret, redacted)
        self.assertGreaterEqual(redacted.count("[REDACTED]"), 4)

    def test_receipt_contract_rejects_silent_skip(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            with self.assertRaises(ValueError):
                write_receipt(path, {"status": "SKIP"})

    def test_sqlite_capture_reads_copy_and_reports_missing_tables(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "state.db"
            with sqlite3.connect(source) as connection:
                connection.execute("CREATE TABLE schema_versions(version INTEGER)")
                connection.execute("INSERT INTO schema_versions VALUES (1)")
            audit = capture_sqlite_audit(source, root / "copy.db", root / "audit.json")
            self.assertEqual(audit["schema_versions"][0]["version"], 1)
            self.assertEqual(audit["sync_runs"]["status"], "NOT_PRESENT")

    def test_scenario_wrapper_redacts_token_output_before_disk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            safety = root / "safety.json"
            safety.write_text(json.dumps({"all_targets_allowlisted": True}))
            environment = dict(os.environ, QA_TEST_ACCESS_TOKEN="never-write-this")
            completed = subprocess.run(
                [
                    str(QA_ROOT / "run-scenario.sh"), str(root / "scenario"), str(safety), "dry",
                    "bash", "-c", 'printf "%s" "$QA_TEST_ACCESS_TOKEN"',
                ],
                cwd=QA_ROOT.parent,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            log = (root / "scenario/dry-run.log").read_text()
            self.assertEqual(log, "[REDACTED]")

    def test_live_wrapper_blocks_before_command_when_provisioning_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            safety = root / "safety.json"
            safety.write_text(json.dumps({
                "all_targets_allowlisted": True,
                "provisioning_complete": False,
                "dry_run_passed": True,
                "expected_mutation_manifest": [{"operation": "create"}],
            }))
            sentinel = root / "must-not-exist"
            completed = subprocess.run(
                [
                    str(QA_ROOT / "run-scenario.sh"), str(root / "scenario"), str(safety), "live",
                    "touch", str(sentinel),
                ],
                cwd=QA_ROOT.parent,
                env=dict(os.environ, QA_CONFIRM_LIVE_MUTATIONS="YES"),
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(completed.returncode, 0)
            self.assertFalse(sentinel.exists())


if __name__ == "__main__":
    unittest.main()

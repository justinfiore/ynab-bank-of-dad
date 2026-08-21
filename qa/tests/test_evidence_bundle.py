import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import SCENARIOS
from lib.evidence_bundle import (
    EvidenceSafetyError, finalize_campaign, sanitize_evidence_text, scan_evidence,
)


class EvidenceBundleTest(unittest.TestCase):
    def test_scan_rejects_raw_token_header_and_uuid(self):
        cases = (
            ("raw-token-value", ["raw-token-value"]),
            ("Authorization: Bearer header-secret", []),
            ("00000000-0000-0000-0000-000000000001", []),
        )
        for content, tokens in cases:
            with self.subTest(content=content), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "evidence.txt").write_text(content)
                with self.assertRaises(EvidenceSafetyError):
                    scan_evidence(root, tokens)

    def test_scan_accepts_redacted_and_generic_source_reference(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "evidence.txt").write_text(
                "Authorization header values prohibited; Authorization: Bearer [REDACTED]"
            )
            result = scan_evidence(root, ["not-present"])
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["raw_token_matches"], 0)

    def test_sanitizer_removes_generated_test_values_before_scan(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "generated-test-report.html"
            artifact.write_text(
                "Authorization: Bearer synthetic-header "
                "00000000-0000-0000-0000-000000000001 raw-token-value"
            )
            sanitize_evidence_text(root, ["raw-token-value"])
            result = scan_evidence(root, ["raw-token-value"])
            self.assertEqual(result["status"], "PASS")
            text = artifact.read_text()
            self.assertIn("Authorization: Bearer [REDACTED]", text)
            self.assertIn("[REDACTED-UUID]", text)

    def test_finalizer_records_the_delivered_file_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "QA-test"
            root.mkdir()
            (root / "environment.json").write_text(
                '{"branch":"test","commit":"abc","missing_parent_categories":[]}'
            )
            (root / "campaign-manifest.json").write_text('{"campaign_id":"QA-test"}')
            for scenario_id, _requirement, _kind in SCENARIOS:
                path = root / "scenarios" / scenario_id / "receipt.json"
                path.parent.mkdir(parents=True)
                path.write_text(
                    '{"status":"BLOCKED","safety":{"api_write_attempts":0},'
                    '"observed":{"creates":0,"updates":0,"deletes":0}}'
                )

            finalize_campaign(root, [])

            recorded = __import__("json").loads((root / "secret-scan.json").read_text())
            delivered = len([path for path in root.rglob("*") if path.is_file()])
            self.assertEqual(recorded["files_scanned"], delivered)

import json
import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT / "report"))

from render_report import load_receipts, render
sys.path.insert(0, str(QA_ROOT))
from lib.campaign_matrix import SCENARIOS, prepare_blocked_campaign


class ReportTest(unittest.TestCase):
    def test_matrix_contains_every_planned_a_through_d_scenario(self):
        self.assertEqual(len(SCENARIOS), 26)
        self.assertEqual({item[0][0] for item in SCENARIOS}, {"A", "B", "C", "D"})

    def test_all_receipts_appear_and_report_is_self_contained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "environment.json").write_text(json.dumps({
                "provisioning_complete": False,
                "branch": "test",
                "commit": "abc",
                "missing_parent_categories": ["QA Missing"],
            }))
            (root / "campaign-manifest.json").write_text(json.dumps({
                "campaign_id": "QA-test", "secret_scan": "PASS",
            }))
            for scenario, status in (("A1", "PASS"), ("B1", "BLOCKED")):
                directory = root / "scenarios" / scenario
                directory.mkdir(parents=True)
                (directory / "receipt.json").write_text(json.dumps({
                    "campaign_id": "QA-test", "scenario_id": scenario, "status": status,
                    "safety": {}, "assertions": [], "requirement": scenario,
                }))
            output = render(root)
            report = output.read_text()
            self.assertIn("A1", report)
            self.assertIn("B1", report)
            self.assertIn("NOT READY", report)
            self.assertNotIn('src="assets/', report)
            self.assertNotIn('href="assets/', report)
            self.assertIn("PASS — no raw token or Authorization header value found", report)

    def test_blocked_campaign_is_built_from_fresh_redacted_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "QA-test"
            discovery = root / "api-observations" / "current-discovery.json"
            discovery.parent.mkdir(parents=True)
            discovery.write_text(json.dumps({
                "all_targets_allowlisted": True,
                "provisioning_complete": False,
                "missing_parent_categories": ["QA Missing One"],
                "missing_child_accounts": {},
                "api_write_count": 0,
            }))

            prepare_blocked_campaign(root, "test/branch", "abc123", discovery)

            environment = json.loads((root / "environment.json").read_text())
            receipts = load_receipts(root)
            self.assertEqual(environment["missing_parent_categories"], ["QA Missing One"])
            self.assertEqual(len(receipts), len(SCENARIOS))
            self.assertTrue(all(item["safety"]["api_write_attempts"] == 0 for item in receipts))
            self.assertIn("QA Missing One", receipts[-1]["reason"])


if __name__ == "__main__":
    unittest.main()

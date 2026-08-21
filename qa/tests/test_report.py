import json
import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT / "report"))

from render_report import load_receipts, render


class ReportTest(unittest.TestCase):
    def test_all_receipts_appear_and_report_is_self_contained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "environment.json").write_text(json.dumps({
                "provisioning_complete": False,
                "branch": "test",
                "commit": "abc",
                "missing_parent_categories": ["QA Missing"],
            }))
            (root / "campaign-manifest.json").write_text(json.dumps({"campaign_id": "QA-test"}))
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


if __name__ == "__main__":
    unittest.main()

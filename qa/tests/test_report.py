import json
import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT / "report"))

from render_report import evidence_wording, load_receipts, recommendation, render
sys.path.insert(0, str(QA_ROOT))
from lib.campaign_matrix import SCENARIOS, prepare_blocked_campaign


class ReportTest(unittest.TestCase):
    def complete_receipt(self, scenario_id):
        return {
            "campaign_id": "QA-test",
            "scenario_id": scenario_id,
            "phase": scenario_id[0],
            "requirement": "verified requirement",
            "branch": "test",
            "commit": "abc",
            "safety": {
                "all_targets_allowlisted": True,
                "family_budget_targets": [],
                "dry_run_passed_before_live": True,
                "api_write_attempts": 0,
            },
            "expected": {"creates": 0, "updates": 0, "deletes": 0},
            "observed": {"creates": 0, "updates": 0, "deletes": 0},
            "dry_run": "PASS",
            "live_run": "PASS",
            "api_observation": "PASS",
            "sqlite_audit": "PASS",
            "assertions": [{"name": "proof", "status": "PASS"}],
            "reason": "complete evidence",
            "status": "PASS",
            "artifact_links": ["proof.json"],
        }

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

    def test_recommendation_requires_complete_exact_a_through_d_matrix(self):
        receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
        self.assertEqual(
            recommendation(
                receipts, True, campaign_id="QA-test", evidence_complete=True
            )[0],
            "READY FOR LIMITED FAMILY PILOT",
        )

        for incomplete in (
            receipts[:-1],
            [*receipts[:-1], {**receipts[-1], "safety": {}}],
            [*receipts, self.complete_receipt(SCENARIOS[-1][0])],
        ):
            with self.subTest(receipt_count=len(incomplete)):
                self.assertEqual(
                    recommendation(incomplete, True, campaign_id="QA-test")[0],
                    "NOT READY",
                )

    def test_pass_receipt_rejects_any_non_pass_assertion(self):
        receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
        receipts[0]["assertions"].append({"name": "contradiction", "status": "FAIL"})

        self.assertEqual(
            recommendation(
                receipts, True, campaign_id="QA-test", evidence_complete=True
            )[0],
            "NOT READY",
        )

    def test_pass_live_receipt_requires_dry_run_before_live(self):
        receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
        live_receipt = next(item for item in receipts if item["scenario_id"] == "B1-live-create")
        live_receipt["safety"]["dry_run_passed_before_live"] = False

        self.assertEqual(
            recommendation(
                receipts, True, campaign_id="QA-test", evidence_complete=True
            )[0],
            "NOT READY",
        )

    def test_pass_receipt_requires_matching_expected_and_observed_counts(self):
        receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
        receipts[0]["observed"] = {"creates": 1, "updates": 0, "deletes": 0}

        self.assertEqual(
            recommendation(
                receipts, True, campaign_id="QA-test", evidence_complete=True
            )[0],
            "NOT READY",
        )

    def test_write_assertions_must_match_manifest_and_environment(self):
        receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
        cases = (
            ({"actual_api_write_count": 1}, {"actual_api_write_count": 1}),
            ({"actual_api_write_count": 0}, {"actual_api_write_count": 1}),
        )
        for environment_counts, manifest_counts in cases:
            with self.subTest(
                environment=environment_counts, manifest=manifest_counts
            ):
                environment = {"all_targets_allowlisted": True, **environment_counts}
                manifest = {
                    "campaign_id": "QA-test", "secret_scan": "PASS", **manifest_counts
                }
                _target, _writes, complete = evidence_wording(
                    receipts, environment, manifest
                )
                self.assertEqual(
                    recommendation(
                        receipts, True, campaign_id="QA-test",
                        evidence_complete=complete,
                    )[0],
                    "NOT READY",
                )

    def test_pass_receipt_rejects_absent_required_evidence(self):
        for field, value in (
            ("api_observation", ""),
            ("artifact_links", []),
            ("reason", ""),
        ):
            with self.subTest(field=field):
                receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
                live_receipt = next(
                    item for item in receipts if item["scenario_id"] == "B1-live-create"
                )
                live_receipt[field] = value
                self.assertEqual(
                    recommendation(
                        receipts, True, campaign_id="QA-test", evidence_complete=True
                    )[0],
                    "NOT READY",
                )

        receipts = [self.complete_receipt(item[0]) for item in SCENARIOS]
        self.assertEqual(
            recommendation(receipts, True, campaign_id="QA-test")[0],
            "NOT READY",
        )

    def test_report_derives_write_wording_and_fails_closed_on_missing_write_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "environment.json").write_text(json.dumps({
                "provisioning_complete": True,
                "all_targets_allowlisted": True,
                "actual_api_write_count": 2,
                "branch": "test",
                "commit": "abc",
                "missing_parent_categories": [],
            }))
            (root / "campaign-manifest.json").write_text(json.dumps({
                "campaign_id": "QA-test",
                "actual_api_write_count": 2,
                "secret_scan": "PASS",
            }))
            for receipt in (self.complete_receipt(item[0]) for item in SCENARIOS):
                directory = root / "scenarios" / receipt["scenario_id"]
                directory.mkdir(parents=True)
                receipt["safety"]["api_write_attempts"] = 1 if receipt["scenario_id"] in {
                    "B1-live-create", "C1-financial-update"
                } else 0
                if receipt["scenario_id"] == "B1-live-create":
                    receipt["expected"]["creates"] = 1
                    receipt["observed"]["creates"] = 1
                elif receipt["scenario_id"] == "C1-financial-update":
                    receipt["expected"]["updates"] = 1
                    receipt["observed"]["updates"] = 1
                (directory / "receipt.json").write_text(json.dumps(receipt))
                (directory / "proof.json").write_text("{}")

            report = render(root).read_text()
            self.assertIn("READY FOR LIMITED FAMILY PILOT", report)
            self.assertIn("2 actual API writes", report)
            self.assertIn("2 transaction write attempts", report)
            self.assertNotIn("No API write was attempted", report)

            (root / "scenarios" / "B1-live-create" / "proof.json").unlink()
            report = render(root).read_text()
            self.assertIn("NOT READY", report)
            self.assertIn("artifact evidence is incomplete", report)

            (root / "campaign-manifest.json").write_text(json.dumps({
                "campaign_id": "QA-test", "secret_scan": "PASS",
            }))
            report = render(root).read_text()
            self.assertIn("NOT READY", report)
            self.assertIn("write evidence is incomplete", report)

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

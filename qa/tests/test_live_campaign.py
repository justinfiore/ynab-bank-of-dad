import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.live_campaign import (
    FreshMutationGate,
    evidence_transaction,
    fixture_import_id,
    fixture_amount,
    memo_has_exact_campaign,
    one_transaction_matches,
    scenario_transactions,
    successful_operation_attempts,
)
from lib.ynab_qa_client import PlanIdentity, QaSafetyError
from run_live_campaign import Campaign


IDS = {
    "Jorsten's Plan": "10000000-0000-0000-0000-000000000001",
    "Jorsten Jr's Plan": "10000000-0000-0000-0000-000000000002",
    "Borsten's Plan": "10000000-0000-0000-0000-000000000003",
    "Thorsten's Plan": "10000000-0000-0000-0000-000000000004",
}


class FreshMutationGateTest(unittest.TestCase):
    def setUp(self):
        self.identities = {name: PlanIdentity(name, value) for name, value in IDS.items()}
        self.gate = FreshMutationGate(self.identities, "QA-test")
        self.parent_discovery = [{"name": name, "id": value} for name, value in IDS.items()]
        self.target_discovery = [{"name": "Jorsten's Plan", "id": IDS["Jorsten's Plan"]}]
        self.payload = {"transaction": {
            "account_id": "account-id", "date": "2026-08-21", "amount": -10,
            "payee_name": "Synthetic", "memo": "BOD QA QA-test:A4-dry-create",
            "cleared": "cleared", "approved": True, "import_id": "QA:test:A4",
        }}
        self.manifest = [{
            "operation": "create", "targetPlanId": IDS["Jorsten's Plan"],
            "campaignId": "QA-test", "payload": self.payload,
        }]

    def test_exact_fresh_discovery_manifest_confirmation_and_tag_authorize_once(self):
        self.gate.authorize(
            "create", self.identities["Jorsten's Plan"], self.payload, None,
            self.manifest, self.parent_discovery, self.target_discovery, "YES",
        )
        with self.assertRaises(QaSafetyError):
            self.gate.authorize(
                "create", self.identities["Jorsten's Plan"], self.payload, None,
                self.manifest, self.parent_discovery, self.target_discovery, "YES",
            )

    def test_pair_mismatch_missing_confirmation_and_untagged_target_block(self):
        cases = (
            ({**self.parent_discovery[0], "id": IDS["Borsten's Plan"]}, "YES", None),
            (self.parent_discovery[0], "yes", None),
        )
        for replacement, confirmation, existing in cases:
            with self.subTest(confirmation=confirmation):
                discovered = [replacement, *self.parent_discovery[1:]]
                with self.assertRaises(QaSafetyError):
                    self.gate.authorize(
                        "create", self.identities["Jorsten's Plan"], self.payload, None,
                        self.manifest, discovered, self.target_discovery, confirmation,
                        existing_transaction=existing,
                    )

        update_manifest = [{
            "operation": "update", "targetPlanId": IDS["Jorsten's Plan"],
            "targetTransactionId": "t1", "campaignId": "QA-test", "payload": self.payload,
        }]
        with self.assertRaises(QaSafetyError):
            self.gate.authorize(
                "update", self.identities["Jorsten's Plan"], self.payload, "t1",
                update_manifest, self.parent_discovery, self.target_discovery, "YES",
                existing_transaction={"id": "t1", "memo": "ordinary"},
            )

    def test_evidence_snapshot_replaces_all_resource_ids_with_stable_refs(self):
        raw = {
            "id": IDS["Jorsten's Plan"], "account_id": IDS["Jorsten Jr's Plan"],
            "category_id": IDS["Borsten's Plan"], "memo": "BOD QA QA-test:A4",
            "subtransactions": [{"id": IDS["Thorsten's Plan"], "amount": -10}],
        }
        serialized = json.dumps(evidence_transaction(raw))
        for value in IDS.values():
            self.assertNotIn(value, serialized)
        self.assertIn("ref:", serialized)
        self.assertIn("BOD QA QA-test:A4", serialized)

    def test_repeated_scenario_fixtures_have_distinct_stable_import_ids(self):
        first = fixture_import_id("BOD QA QA-test:A6", 1)
        second = fixture_import_id("BOD QA QA-test:A6", 2)
        self.assertNotEqual(first, second)
        self.assertEqual(first, fixture_import_id("BOD QA QA-test:A6", 1))
        self.assertLessEqual(len(first), 36)

    def test_split_fixture_amount_must_equal_its_components(self):
        components = [{"amount": -10}, {"amount": -20}, {"amount": -30}]
        self.assertEqual(fixture_amount(-30, components), -60)
        self.assertEqual(fixture_amount(-10, None), -10)

    def test_successful_operation_attempts_uses_durable_outcome_column(self):
        attempts = [
            {"outcome": "applied"},
            {"outcome": "already_complete"},
            {"outcome": "failed"},
            {"status": "applied"},
        ]
        self.assertEqual(successful_operation_attempts(attempts), 2)

    def test_campaign_tag_matching_rejects_campaign_id_prefixes(self):
        self.assertTrue(memo_has_exact_campaign("BOD QA QA-123:B1", "QA-123"))
        self.assertFalse(memo_has_exact_campaign("BOD QA QA-1234:B1", "QA-123"))
        self.assertFalse(memo_has_exact_campaign("ordinary QA-123", "QA-123"))

    def test_scenario_selection_excludes_prior_fixtures_and_deleted_by_default(self):
        transactions = [
            {"id": "prior", "memo": "BOD QA QA-123:B4-live-split"},
            {"id": "current", "memo": "YBOD: BOD QA QA-123:C1-financial-update"},
            {"id": "deleted", "memo": "BOD QA QA-123:C1-financial-update", "deleted": True},
            {"id": "other", "memo": "BOD QA QA-123:C2-child-memo-owned"},
        ]
        selected = scenario_transactions(transactions, "QA-123", "C1-financial-update")
        self.assertEqual([item["id"] for item in selected], ["current"])
        with_deleted = scenario_transactions(
            transactions, "QA-123", "C1-financial-update", include_deleted=True,
        )
        self.assertEqual([item["id"] for item in with_deleted], ["current", "deleted"])

    def test_c1_field_verification_ignores_prior_scenarios_but_requires_stable_identity(self):
        transactions = [
            {"id": "b-fixture", "memo": "BOD QA QA-123:B1-live-create", "amount": -10},
            {"id": "child-c1", "memo": "BOD QA QA-123:C1-financial-update", "date": "2026-08-20",
             "amount": -15, "payee_name": "Synthetic C1 changed", "cleared": "cleared",
             "approved": False},
            {"id": "child-c2", "memo": "BOD QA QA-123:C2-child-memo-owned"},
        ]
        selected = scenario_transactions(transactions, "QA-123", "C1-financial-update")
        fields = {"date": "2026-08-20", "amount": -15,
                  "payee_name": "Synthetic C1 changed", "cleared": "cleared",
                  "approved": False}
        self.assertTrue(one_transaction_matches(selected, "child-c1", fields))
        self.assertFalse(one_transaction_matches(selected, "different-child", fields))

    def test_automated_matrix_isolates_executor_and_cleanup_failures(self):
        campaign = Campaign.__new__(Campaign)
        campaign.campaign_id = "QA-test"
        campaign.secrets = ["private-token"]
        campaign.receipts = {}
        campaign.cleanup_failures = []
        events = []

        def receipt(scenario, status, reason, **outcome):
            campaign.receipts[scenario] = {
                "scenario_id": scenario, "status": status, "reason": reason, **outcome,
            }

        campaign.receipt = receipt

        scenario_methods = {
            "_baseline": "A1-baseline",
            "_a2_guard": "A2-guard-rejection",
            "_a3_smoke": "A3-config-smoke",
            "_a4_a5": "A4-dry-create",
            "_a6": "A6-unapproved-unmapped",
            "_b1": "B1-live-create",
            "_b3": "B3-approval-transition",
            "_b4": "B4-live-split",
            "_c1": "C1-financial-update",
            "_c2": "C2-child-memo-owned",
            "_c3": "C3-same-child-reroute",
            "_c4": "C4-cross-child-reroute",
            "_c5": "C5-parent-unapproved",
            "_c6": "C6-parent-unmapped",
            "_c7": "C7-parent-deleted",
            "_c9": "C9-child-mirror-recreated",
            "_d1": "D1-invalid-child-token",
            "_d2": "D2-invalid-child-mapping",
            "_d3": "D3-single-writer-lock",
            "_d4": "D4-controlled-continuous",
        }

        def action(scenario):
            def run():
                events.append(("action", scenario))
                if scenario == "C2-child-memo-owned":
                    raise RuntimeError(
                        "private-token failed at https://api.ynab.com/v1/budgets/private"
                    )
                return False if scenario == "B1-live-create" else None
            return run

        for method, scenario in scenario_methods.items():
            setattr(campaign, method, action(scenario))
        campaign._resources = lambda: {}
        campaign._a7 = lambda _resources: events.append(("action", "A7-split-fanout"))

        cleanup_failed = False

        def cleanup():
            nonlocal cleanup_failed
            prior_scenario = events[-1][1]
            events.append(("cleanup", prior_scenario))
            if prior_scenario == "C1-financial-update" and not cleanup_failed:
                cleanup_failed = True
                raise RuntimeError("private-token cleanup failed")

        campaign.cleanup = cleanup

        campaign.run_automated_matrix()

        c2 = campaign.receipts["C2-child-memo-owned"]
        self.assertEqual(c2["status"], "FAIL")
        self.assertTrue(c2["execution_error"])
        self.assertNotIn("private-token", c2["executor_cause"])
        self.assertNotIn("api.ynab.com", c2["executor_cause"])
        self.assertIn("[REDACTED", c2["executor_cause"])
        self.assertIn(("action", "C3-same-child-reroute"), events)
        self.assertIn(("action", "D4-controlled-continuous"), events)

        for first, second in zip(
            ("A1-baseline", "A2-guard-rejection", "A3-config-smoke",
             "A4-dry-create", "A6-unapproved-unmapped"),
            ("A2-guard-rejection", "A3-config-smoke", "A4-dry-create",
             "A6-unapproved-unmapped", "A7-split-fanout"),
        ):
            self.assertLess(events.index(("action", first)), events.index(("cleanup", first)))
            self.assertLess(events.index(("cleanup", first)), events.index(("action", second)))

        cleanup_receipt = campaign.receipts["C1-financial-update"]
        self.assertEqual(cleanup_receipt["status"], "FAIL")
        self.assertTrue(cleanup_receipt["cleanup_failure"])
        self.assertEqual(campaign.cleanup_failures[0]["scenario_id"], "C1-financial-update")
        self.assertNotIn("private-token", campaign.cleanup_failures[0]["cause"])
        self.assertLess(
            events.index(("cleanup", "C1-financial-update")),
            events.index(("action", "C2-child-memo-owned")),
        )

    def test_remaining_tagged_cleanup_verification_gates_and_later_scenario_can_continue(self):
        class Client:
            def set_request_pacing_ms(self, _value):
                pass

            def get(self, _identity, _resource):
                return {"data": {"transactions": []}}

        with tempfile.TemporaryDirectory() as temporary:
            campaign = Campaign.__new__(Campaign)
            campaign.campaign_id = "QA-test"
            campaign.artifacts = Path(temporary)
            campaign.secrets = []
            campaign.receipts = {}
            campaign.cleanup_failures = []
            campaign.identities = {
                name: PlanIdentity(name, plan_id) for name, plan_id in IDS.items()
            }
            campaign.clients = {name: Client() for name in IDS}
            campaign.mutate = lambda *_args, **_kwargs: None
            remaining = [{name: [{"id": "still-tagged"}] for name in IDS},
                         {name: [] for name in IDS}]
            campaign.snapshot = lambda *_args, **_kwargs: remaining.pop(0)

            def receipt(scenario, status, reason, **outcome):
                campaign.receipts[scenario] = {
                    "campaign_id": campaign.campaign_id,
                    "scenario_id": scenario,
                    "status": status,
                    "reason": reason,
                    "safety": {"api_write_attempts": 0},
                    "assertions": [{"name": reason, "status": status}],
                    **outcome,
                }

            campaign.receipt = receipt
            later_actions = []

            campaign.run_scenario("C1-financial-update", lambda: receipt(
                "C1-financial-update", "PASS", "scenario passed",
            ))

            cleanup_manifest = json.loads(
                (campaign.artifacts / "cleanup/cleanup-manifest.json").read_text()
            )
            self.assertEqual(cleanup_manifest["verification"], "FAIL")
            self.assertEqual(campaign.receipts["C1-financial-update"]["status"], "FAIL")
            self.assertTrue(campaign.receipts["C1-financial-update"]["cleanup_failure"])

            campaign.run_scenario(
                "C2-child-memo-owned", lambda: later_actions.append("C2-child-memo-owned")
            )

            self.assertEqual(later_actions, ["C2-child-memo-owned"])
            self.assertEqual(len(campaign.cleanup_failures), 1)


class CampaignD3Test(unittest.TestCase):
    def campaign(self, root: Path) -> Campaign:
        campaign = Campaign.__new__(Campaign)
        campaign.raw = root / "raw"
        campaign.artifacts = root / "artifacts"
        campaign.raw.mkdir()
        campaign.api_write_attempts = 7
        campaign.receipts = {}

        def receipt(scenario, status, reason, **outcome):
            campaign.receipts[scenario] = {
                "scenario_id": scenario, "status": status, "reason": reason, **outcome,
            }

        campaign.receipt = receipt
        return campaign

    def test_d3_blocked_contender_has_zero_deltas_and_post_release_attempts_may_be_nonzero(self):
        with tempfile.TemporaryDirectory() as temporary:
            campaign = self.campaign(Path(temporary))

            def blocked_contender(*_args, **kwargs):
                telemetry = Path(kwargs["env_override"]["YNAB_WRITE_ATTEMPT_TELEMETRY_FILE"])
                self.assertTrue(telemetry.is_file())
                self.assertEqual(telemetry.read_text(), "")
                return 1, "Another process already holds the live lock"

            campaign.run_sync = blocked_contender

            def run_live_counted(*_args, **kwargs):
                self.assertIsNone(
                    kwargs["env_override"]["YNAB_WRITE_ATTEMPT_TELEMETRY_FILE"]
                )
                campaign.api_write_attempts += 2
                return 0, "completed", 2

            campaign.run_live_counted = run_live_counted

            campaign._d3()

            receipt = campaign.receipts["D3-single-writer-lock"]
            self.assertEqual(receipt["status"], "PASS")
            self.assertEqual(receipt["blocked_api_write_attempt_delta"], 0)
            self.assertTrue(receipt["blocked_sqlite_database_family_unchanged"])
            self.assertEqual(receipt["blocked_sqlite_operation_attempt_row_delta"], 0)
            self.assertEqual(receipt["post_release_operation_attempts"], 2)
            self.assertEqual(receipt["attempts"], 2)
            observation = json.loads((
                campaign.artifacts / "scenarios/D3-single-writer-lock/lock-observation.json"
            ).read_text())
            self.assertEqual(observation["blocked_contender"]["api_write_attempt_delta"], 0)
            self.assertTrue(
                observation["blocked_contender"]["sqlite_database_family_unchanged"]
            )
            self.assertEqual(
                observation["blocked_contender"]["sqlite_operation_attempt_row_delta"], 0,
            )
            self.assertEqual(observation["post_release_process"]["operation_attempt_rows"], 2)

    def test_d3_fails_if_blocked_contender_records_mutation_attempts(self):
        with tempfile.TemporaryDirectory() as temporary:
            campaign = self.campaign(Path(temporary))

            def mutating_blocked_contender(*_args, **kwargs):
                telemetry = Path(kwargs["env_override"]["YNAB_WRITE_ATTEMPT_TELEMETRY_FILE"])
                with telemetry.open("a", encoding="utf-8") as records:
                    records.write(
                        json.dumps({"method": "POST", "resource_class": "transactions"})
                        + "\n"
                    )
                return 1, "Another process already holds the live lock"

            campaign.run_sync = mutating_blocked_contender
            campaign.run_live_counted = lambda *_args, **_kwargs: (0, "completed", 0)

            campaign._d3()

            receipt = campaign.receipts["D3-single-writer-lock"]
            self.assertEqual(receipt["status"], "FAIL")
            self.assertEqual(receipt["blocked_api_write_attempt_delta"], 1)
            self.assertTrue(receipt["blocked_sqlite_database_family_unchanged"])
            self.assertEqual(receipt["blocked_sqlite_operation_attempt_row_delta"], 0)
            self.assertEqual(receipt["assertions"][1]["status"], "FAIL")
            self.assertEqual(receipt["assertions"][2]["status"], "PASS")
            self.assertEqual(receipt["assertions"][3]["status"], "PASS")
            self.assertEqual(campaign.api_write_attempts, 8)

    def test_d3_fails_if_blocked_contender_creates_other_sqlite_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            campaign = self.campaign(Path(temporary))

            def schema_creating_blocked_contender(_scenario, _dry, state, **_kwargs):
                with sqlite3.connect(state) as connection:
                    connection.execute("CREATE TABLE sync_runs (id INTEGER PRIMARY KEY)")
                return 1, "Another process already holds the live lock"

            campaign.run_sync = schema_creating_blocked_contender
            campaign.run_live_counted = lambda *_args, **_kwargs: (0, "completed", 0)

            campaign._d3()

            receipt = campaign.receipts["D3-single-writer-lock"]
            self.assertEqual(receipt["status"], "FAIL")
            self.assertFalse(receipt["blocked_sqlite_database_family_unchanged"])
            self.assertEqual(receipt["blocked_sqlite_operation_attempt_row_delta"], 0)
            self.assertEqual(receipt["assertions"][2]["status"], "FAIL")
            self.assertEqual(receipt["assertions"][3]["status"], "PASS")

    def test_d3_fails_if_blocked_contender_mutates_other_sqlite_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            campaign = self.campaign(Path(temporary))
            state = campaign.raw / "D3-single-writer-lock.db"
            with sqlite3.connect(state) as connection:
                connection.execute("CREATE TABLE sync_cursors (value TEXT)")

            def mutating_blocked_contender(*_args, **_kwargs):
                with sqlite3.connect(state) as connection:
                    connection.execute("INSERT INTO sync_cursors VALUES ('changed')")
                return 1, "Another process already holds the live lock"

            campaign.run_sync = mutating_blocked_contender
            campaign.run_live_counted = lambda *_args, **_kwargs: (0, "completed", 0)

            campaign._d3()

            receipt = campaign.receipts["D3-single-writer-lock"]
            self.assertEqual(receipt["status"], "FAIL")
            self.assertFalse(receipt["blocked_sqlite_database_family_unchanged"])
            self.assertEqual(receipt["blocked_sqlite_operation_attempt_row_delta"], 0)

    def test_operation_attempt_count_is_zero_before_schema_exists(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state.db"
            self.assertEqual(Campaign.operation_attempt_count(state), 0)
            sqlite3.connect(state).close()
            self.assertEqual(Campaign.operation_attempt_count(state), 0)

    def test_write_attempt_telemetry_rejects_unsanitized_schema(self):
        with tempfile.TemporaryDirectory() as temporary:
            telemetry = Path(temporary) / "attempts.jsonl"
            telemetry.write_text(json.dumps({
                "method": "POST", "resource_class": "transactions",
                "url": "https://example.invalid/private-id",
            }) + "\n")

            with self.assertRaisesRegex(
                QaSafetyError, "Write-attempt telemetry contained an unsafe record",
            ):
                Campaign.write_attempt_telemetry_count(telemetry)


class CampaignFinalMetadataTest(unittest.TestCase):
    def final_manifest(self, selected, receipts, *, cleanup_failures=None):
        with tempfile.TemporaryDirectory() as temporary:
            artifacts = Path(temporary)
            (artifacts / "api-observations").mkdir()
            (artifacts / "environment.json").write_text(json.dumps({
                "selected_scenario_ids": selected,
            }))
            for receipt in receipts:
                receipt_dir = artifacts / "scenarios" / receipt["scenario_id"]
                receipt_dir.mkdir(parents=True)
                (receipt_dir / "receipt.json").write_text(json.dumps(receipt))

            campaign = Campaign.__new__(Campaign)
            campaign.campaign_id = "QA-test"
            campaign.artifacts = artifacts
            campaign.api_write_attempts = 0
            campaign.successful_writes = 0
            campaign.cleanup_failures = cleanup_failures or []
            campaign.clients = {}
            campaign._final_metadata()

            return json.loads((artifacts / "campaign-manifest.json").read_text())

    def test_all_selected_pass_with_extra_manual_not_run_is_pass(self):
        manifest = self.final_manifest(
            ["A1-baseline", "A2-guard-rejection"],
            [
                {"scenario_id": "A1-baseline", "status": "PASS"},
                {"scenario_id": "A2-guard-rejection", "status": "PASS"},
                {"scenario_id": "A8-money-movement", "status": "NOT_RUN"},
            ],
        )
        self.assertEqual(manifest["status"], "PASS")
        self.assertEqual(manifest["release_recommendation"], "NOT READY")

    def test_selected_fail_is_fail(self):
        manifest = self.final_manifest(
            ["A1-baseline"],
            [{"scenario_id": "A1-baseline", "status": "FAIL"}],
        )
        self.assertEqual(manifest["status"], "FAIL")

    def test_missing_or_selected_not_run_is_blocked(self):
        cases = (
            [],
            [{"scenario_id": "A1-baseline", "status": "NOT_RUN"}],
        )
        for receipts in cases:
            with self.subTest(receipts=receipts):
                manifest = self.final_manifest(["A1-baseline"], receipts)
                self.assertEqual(manifest["status"], "BLOCKED")

    def test_cleanup_failure_is_fail(self):
        manifest = self.final_manifest(
            ["A1-baseline"],
            [{"scenario_id": "A1-baseline", "status": "PASS"}],
            cleanup_failures=[{"scenario_id": "cleanup", "cause": "verification failed"}],
        )
        self.assertEqual(manifest["status"], "FAIL")
        self.assertEqual(
            manifest["cleanup"],
            "Cleanup failed; campaign-tagged transaction removal was not verified.",
        )


if __name__ == "__main__":
    unittest.main()

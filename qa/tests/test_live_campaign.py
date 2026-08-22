import json
import sys
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.live_campaign import FreshMutationGate, evidence_transaction, fixture_import_id, fixture_amount
from lib.ynab_qa_client import PlanIdentity, QaSafetyError


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


if __name__ == "__main__":
    unittest.main()

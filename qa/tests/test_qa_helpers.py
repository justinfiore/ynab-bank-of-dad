import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.fixtures import TransactionFixture, normalize_transaction, tagged_matches
from lib.ynab_qa_client import KNOWN_NAMES, PlanIdentity, QaSafetyError, YnabQaClient


IDS = {
    name: f"00000000-0000-0000-0000-{index:012d}"
    for index, name in enumerate(sorted(KNOWN_NAMES), 1)
}


class QaClientGuardTest(unittest.TestCase):
    def setUp(self):
        self.client = YnabQaClient("test-token", IDS)
        self.parent = PlanIdentity("Jorsten's Plan", IDS["Jorsten's Plan"])

    def test_exact_name_and_id_are_both_required(self):
        with self.assertRaises(QaSafetyError):
            self.client.require_allowed(PlanIdentity("Jorsten's Plan", IDS["Borsten's Plan"]))

    def test_missing_confirmation_blocks_before_request(self):
        with patch.object(self.client, "_request") as request:
            with self.assertRaises(QaSafetyError):
                self.client.transaction_write(
                    "POST",
                    self.parent,
                    {"transaction": {"memo": "BOD QA QA-run:A4"}},
                    transaction_id=None,
                    campaign_id="QA-run",
                    expected_manifest=[{"operation": "create", "targetPlanId": self.parent.plan_id}],
                    confirmation="NO",
                )
            request.assert_not_called()

    def test_untagged_write_blocks_before_request(self):
        with patch.object(self.client, "_request") as request:
            with self.assertRaises(QaSafetyError):
                self.client.transaction_write(
                    "POST",
                    self.parent,
                    {"transaction": {"memo": "untagged"}},
                    transaction_id=None,
                    campaign_id="QA-run",
                    expected_manifest=[{"operation": "create", "targetPlanId": self.parent.plan_id}],
                    confirmation="YES",
                )
            request.assert_not_called()

    def test_delete_requires_exact_tagged_manifest_target(self):
        with patch.object(self.client, "_request") as request:
            with self.assertRaises(QaSafetyError):
                self.client.transaction_write(
                    "DELETE",
                    self.parent,
                    None,
                    transaction_id="transaction-2",
                    campaign_id="QA-run",
                    expected_manifest=[{
                        "operation": "delete",
                        "targetPlanId": self.parent.plan_id,
                        "targetTransactionId": "transaction-1",
                    }],
                    confirmation="YES",
                )
            request.assert_not_called()


class FixtureTest(unittest.TestCase):
    def test_fixture_is_small_and_uniquely_tagged(self):
        payload = TransactionFixture(
            "QA-20260821-abcd", "B1", "account", "category", -10, "QA Merchant"
        ).payload()["transaction"]
        self.assertEqual(payload["amount"], -10)
        self.assertIn("QA-20260821-abcd:B1", payload["memo"])

    def test_invalid_amount_is_rejected(self):
        with self.assertRaises(ValueError):
            TransactionFixture("QA-run", "B1", "account", None, 1001, "QA Merchant").payload()

    def test_normalizer_and_matcher_return_only_safe_schema(self):
        raw = {
            "id": "t1",
            "memo": "BOD QA QA-run:B1",
            "amount": -10,
            "unknown_personal_field": "must disappear",
        }
        result = tagged_matches([raw], "QA-run:B1")
        self.assertEqual(len(result), 1)
        self.assertNotIn("unknown_personal_field", result[0])
        self.assertEqual(normalize_transaction(raw)["id"], "t1")


if __name__ == "__main__":
    unittest.main()

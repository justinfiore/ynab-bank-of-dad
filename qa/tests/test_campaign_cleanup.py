import json
import sys
import unittest
from pathlib import Path

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_cleanup import cleanup_exact_campaign
from lib.ynab_qa_client import PlanIdentity, QaSafetyError


IDS = {
    "Jorsten's Plan": "10000000-0000-0000-0000-000000000001",
    "Jorsten Jr's Plan": "10000000-0000-0000-0000-000000000002",
    "Borsten's Plan": "10000000-0000-0000-0000-000000000003",
    "Thorsten's Plan": "10000000-0000-0000-0000-000000000004",
}


class FakeClient:
    def __init__(self, name, plans, transactions, events):
        self.name = name
        self.plans = plans
        self.transactions = transactions
        self.events = events
        self.writes = []

    def discover_plans(self):
        self.events.append(("plans", self.name))
        return {"data": {"plans": self.plans}}

    def get(self, identity, resource):
        self.events.append(("get", self.name, resource))
        return {"data": {"transactions": self.transactions}}

    def transaction_write(self, method, identity, payload, **kwargs):
        self.events.append(("write", self.name))
        self.writes.append((method, identity, payload, kwargs))
        return {"data": {}}


class ExactCampaignCleanupTest(unittest.TestCase):
    def setUp(self):
        self.identities = {name: PlanIdentity(name, value) for name, value in IDS.items()}
        self.all_plans = [{"name": name, "id": value} for name, value in IDS.items()]
        self.events = []

    def clients(self, parent_plans=None):
        clients = {}
        for name in IDS:
            plans = self.all_plans if name == "Jorsten's Plan" else [
                {"name": name, "id": IDS[name]},
            ]
            if name == "Jorsten's Plan" and parent_plans is not None:
                plans = parent_plans
            transactions = []
            if name == "Borsten's Plan":
                transactions = [
                    {"id": "exact-id", "memo": "BOD QA QA-123:B1", "deleted": False},
                    {"id": "prefix-id", "memo": "BOD QA QA-1234:B1", "deleted": False},
                    {"id": "ordinary-id", "memo": "ordinary QA-123", "deleted": False},
                    {"id": "deleted-id", "memo": "BOD QA QA-123:B2", "deleted": True},
                ]
            clients[name] = FakeClient(name, plans, transactions, self.events)
        return clients

    def test_confirmation_is_required_before_discovery(self):
        clients = self.clients()
        with self.assertRaises(QaSafetyError):
            cleanup_exact_campaign(
                "QA-123", self.identities, clients, confirmation="NO",
            )
        self.assertEqual(self.events, [])

    def test_all_exact_pairs_are_validated_before_any_delete(self):
        mismatched = [dict(item) for item in self.all_plans]
        mismatched[0]["id"] = IDS["Borsten's Plan"]
        clients = self.clients(mismatched)
        with self.assertRaises(Exception):
            cleanup_exact_campaign(
                "QA-123", self.identities, clients, confirmation="YES",
            )
        self.assertFalse(any(event[0] == "write" for event in self.events))

    def test_only_exact_bod_campaign_matches_use_manifest_bound_delete(self):
        clients = self.clients()
        artifact = cleanup_exact_campaign(
            "QA-123", self.identities, clients, confirmation="YES",
        )
        writes = [write for client in clients.values() for write in client.writes]
        self.assertEqual(len(writes), 1)
        method, identity, payload, kwargs = writes[0]
        self.assertEqual((method, identity.name, payload), ("DELETE", "Borsten's Plan", None))
        self.assertEqual(kwargs["campaign_id"], "QA-123")
        self.assertEqual(kwargs["expected_manifest"], [{
            "operation": "delete",
            "targetPlanId": IDS["Borsten's Plan"],
            "campaignId": "QA-123",
            "targetTransactionId": "exact-id",
        }])
        serialized = json.dumps(artifact)
        for transaction_id in ("exact-id", "prefix-id", "ordinary-id", "deleted-id"):
            self.assertNotIn(transaction_id, serialized)
        for plan_id in IDS.values():
            self.assertNotIn(plan_id, serialized)
        self.assertEqual(artifact["deleted_count"], 1)
        self.assertFalse(artifact["transaction_ids_retained"])


if __name__ == "__main__":
    unittest.main()

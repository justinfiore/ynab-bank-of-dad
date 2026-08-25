import io
import json
import os
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import MagicMock, patch

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.fixtures import TransactionFixture, normalize_transaction, tagged_matches
from lib.read_only_discovery import DiscoveryBlocked, run_discovery
from lib.ynab_qa_client import (
    KNOWN_NAMES,
    MAX_RETRY_AFTER_SECONDS,
    PlanIdentity,
    QaSafetyError,
    YnabQaClient,
)


IDS = {
    name: f"00000000-0000-0000-0000-{index:012d}"
    for index, name in enumerate(sorted(KNOWN_NAMES), 1)
}


class QaClientGuardTest(unittest.TestCase):
    def setUp(self):
        self.client = YnabQaClient("test-token", IDS)
        self.parent = PlanIdentity("Jorsten's Plan", IDS["Jorsten's Plan"])

    def create_payload(self):
        return {"transaction": {
            "account_id": "account-1",
            "amount": -10,
            "date": "2026-08-21",
            "payee_name": "QA Merchant",
            "memo": "BOD QA QA-run:A4",
            "import_id": "BOD-QA:QA-run:A4",
        }}

    def manifest_entry(self, operation, payload=None, transaction_id=None):
        entry = {
            "operation": operation,
            "targetPlanId": self.parent.plan_id,
            "campaignId": "QA-run",
        }
        if payload is not None:
            entry["payload"] = payload
        if transaction_id is not None:
            entry["targetTransactionId"] = transaction_id
        return entry

    def test_exact_name_and_id_are_both_required(self):
        with self.assertRaises(QaSafetyError):
            self.client.require_allowed(PlanIdentity("Jorsten's Plan", IDS["Borsten's Plan"]))

    def test_get_allows_money_movements_and_rejects_unknown_resources(self):
        with patch.object(self.client, "_request", return_value={"data": {"money_movements": []}}) as request:
            self.assertEqual(
                self.client.get(self.parent, "money_movements"),
                {"data": {"money_movements": []}},
            )
        request.assert_called_once_with("GET", f"plans/{self.parent.plan_id}/money_movements")
        with self.assertRaises(QaSafetyError):
            self.client.get(self.parent, "payees")

    def test_rate_limited_get_retries_once_but_post_does_not_retry(self):
        waits = []
        client = YnabQaClient("test-token", IDS, sleeper=waits.append)
        rate_limited = urllib.error.HTTPError(
            "https://example.test", 429, "too many requests", {"Retry-After": "2"}, None
        )
        response = MagicMock()
        response.__enter__.return_value = io.StringIO('{"data":{"plans":[]}}')
        response.__exit__.return_value = False
        with patch("urllib.request.urlopen", side_effect=[rate_limited, response]) as urlopen:
            self.assertEqual(client._request("GET", "plans"), {"data": {"plans": []}})
        self.assertEqual(urlopen.call_count, 2)
        self.assertEqual(waits, [2.0])
        waits.clear()
        with patch("urllib.request.urlopen", side_effect=rate_limited) as urlopen:
            with self.assertRaisesRegex(RuntimeError, "HTTP 429"):
                client._request("POST", "plans/example/transactions", {"transaction": {}})
        self.assertEqual(urlopen.call_count, 1)
        self.assertEqual(waits, [])

    def test_request_telemetry_retains_only_safe_aggregate_fields(self):
        client = YnabQaClient("secret-token-value", IDS, sleeper=lambda _: None)
        rate_limited = urllib.error.HTTPError(
            "https://api.ynab.com/v1/plans/secret-plan-id/transactions",
            429, "too many requests", {"Retry-After": "1", "Secret": "header"}, None,
        )
        response = MagicMock()
        response.__enter__.return_value = io.StringIO('{"data":{"transactions":[]}}')
        response.__exit__.return_value = False
        with patch("urllib.request.urlopen", side_effect=[rate_limited, response]):
            client._request("GET", "plans/secret-plan-id/transactions/secret-transaction-id")

        telemetry = client.request_telemetry()
        self.assertEqual(telemetry, [
            {
                "method": "GET", "resource_class": "transactions",
                "status_class": "2xx", "count": 1, "retry_count": 0,
            },
            {
                "method": "GET", "resource_class": "transactions",
                "status_class": "4xx", "count": 1, "retry_count": 1,
            },
        ])
        serialized = json.dumps(telemetry)
        for forbidden in (
            "secret-token-value", "header", "api.ynab.com", "secret-plan-id",
            "secret-transaction-id", "Authorization", "url", "headers",
        ):
            self.assertNotIn(forbidden, serialized)

    def test_rate_limited_get_stops_after_retry_exhaustion(self):
        waits = []
        client = YnabQaClient(
            "test-token", IDS, max_get_rate_limit_retries=2, sleeper=waits.append
        )
        rate_limited = urllib.error.HTTPError(
            "https://example.test", 429, "too many requests", {"Retry-After": "1"}, None
        )

        with patch("urllib.request.urlopen", side_effect=rate_limited) as urlopen:
            with self.assertRaisesRegex(RuntimeError, "HTTP 429"):
                client._request("GET", "plans")

        self.assertEqual(urlopen.call_count, 3)
        self.assertEqual(waits, [1.0, 1.0])

    def test_retry_after_above_allowed_range_is_clamped(self):
        client = YnabQaClient("test-token", IDS, sleeper=lambda _: None)
        rate_limited = urllib.error.HTTPError(
            "https://example.test",
            429,
            "too many requests",
            {"Retry-After": str(MAX_RETRY_AFTER_SECONDS + 1)},
            None,
        )

        self.assertEqual(client._retry_delay(rate_limited), float(MAX_RETRY_AFTER_SECONDS))

    def test_missing_confirmation_blocks_before_request(self):
        with patch.object(self.client, "_request") as request:
            with self.assertRaises(QaSafetyError):
                self.client.transaction_write(
                    "POST",
                    self.parent,
                    self.create_payload(),
                    transaction_id=None,
                    campaign_id="QA-run",
                    expected_manifest=[self.manifest_entry("create", self.create_payload())],
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
                    expected_manifest=[self.manifest_entry(
                        "create", {"transaction": {"memo": "untagged"}}
                    )],
                    confirmation="YES",
                )
            request.assert_not_called()

    def test_create_requires_one_exact_campaign_and_payload_match(self):
        payload = self.create_payload()
        with patch.object(self.client, "_request", return_value={"ok": True}) as request:
            result = self.client.transaction_write(
                "POST",
                self.parent,
                payload,
                transaction_id=None,
                campaign_id="QA-run",
                expected_manifest=[self.manifest_entry("create", payload)],
                confirmation="YES",
            )

        self.assertEqual(result, {"ok": True})
        request.assert_called_once_with(
            "POST", f"plans/{self.parent.plan_id}/transactions", payload
        )

    def test_create_rejects_payload_difference_and_unexpected_mutation_field(self):
        for changed_payload in (
            {"transaction": {**self.create_payload()["transaction"], "amount": -11}},
            {"transaction": {**self.create_payload()["transaction"], "approved": True}},
        ):
            with self.subTest(payload=changed_payload), patch.object(
                self.client, "_request"
            ) as request:
                with self.assertRaises(QaSafetyError):
                    self.client.transaction_write(
                        "POST",
                        self.parent,
                        changed_payload,
                        transaction_id=None,
                        campaign_id="QA-run",
                        expected_manifest=[self.manifest_entry("create", self.create_payload())],
                        confirmation="YES",
                    )
                request.assert_not_called()

    def test_create_rejects_wrong_campaign_and_ambiguous_matches(self):
        payload = self.create_payload()
        cases = (
            [self.manifest_entry("create", payload) | {"campaignId": "QA-other"}],
            [self.manifest_entry("create", payload), self.manifest_entry("create", payload)],
        )
        for manifest in cases:
            with self.subTest(manifest=manifest), patch.object(self.client, "_request") as request:
                with self.assertRaises(QaSafetyError):
                    self.client.transaction_write(
                        "POST", self.parent, payload, transaction_id=None,
                        campaign_id="QA-run", expected_manifest=manifest, confirmation="YES",
                    )
                request.assert_not_called()

    def test_create_requires_exact_nonempty_manifest_bound_import_id(self):
        authorized = self.create_payload()
        absent = {
            "transaction": {
                key: value for key, value in authorized["transaction"].items()
                if key != "import_id"
            }
        }
        empty = {"transaction": {**authorized["transaction"], "import_id": ""}}
        mismatched = {"transaction": {**authorized["transaction"], "import_id": "BOD-QA:other"}}
        invalid_cases = (
            (absent, absent),
            (empty, empty),
            (mismatched, authorized),
        )
        for payload, manifest_payload in invalid_cases:
            with self.subTest(payload=payload), patch.object(self.client, "_request") as request:
                with self.assertRaises(QaSafetyError):
                    self.client.transaction_write(
                        "POST", self.parent, payload, transaction_id=None,
                        campaign_id="QA-run",
                        expected_manifest=[self.manifest_entry("create", manifest_payload)],
                        confirmation="YES",
                    )
                request.assert_not_called()

    def test_each_matching_manifest_authorization_is_single_use_before_http(self):
        cases = (
            ("POST", self.create_payload(), None, "create"),
            (
                "PUT",
                {"transaction": {"amount": -20, "date": "2026-08-22"}},
                "transaction-1",
                "update",
            ),
            ("DELETE", None, "transaction-1", "delete"),
        )
        for method, payload, transaction_id, operation in cases:
            with self.subTest(operation=operation):
                client = YnabQaClient("test-token", IDS)
                manifest = [self.manifest_entry(operation, payload, transaction_id)]
                with patch.object(client, "_request", return_value={"ok": True}) as request:
                    self.assertEqual(
                        client.transaction_write(
                            method, self.parent, payload, transaction_id=transaction_id,
                            campaign_id="QA-run", expected_manifest=manifest,
                            confirmation="YES",
                        ),
                        {"ok": True},
                    )
                    with self.assertRaises(QaSafetyError):
                        client.transaction_write(
                            method, self.parent, payload, transaction_id=transaction_id,
                            campaign_id="QA-run", expected_manifest=manifest,
                            confirmation="YES",
                        )
                request.assert_called_once()

    def test_update_requires_exact_transaction_id_and_payload(self):
        payload = {"transaction": {"amount": -20, "date": "2026-08-22"}}
        manifest = [self.manifest_entry("update", payload, "transaction-1")]
        with patch.object(self.client, "_request", return_value={"ok": True}) as request:
            self.client.transaction_write(
                "PUT", self.parent, payload, transaction_id="transaction-1",
                campaign_id="QA-run", expected_manifest=manifest, confirmation="YES",
            )
        request.assert_called_once()

        for transaction_id, changed_payload in (
            ("transaction-2", payload),
            ("transaction-1", {"transaction": {"amount": -21, "date": "2026-08-22"}}),
        ):
            with self.subTest(transaction_id=transaction_id), patch.object(
                self.client, "_request"
            ) as request:
                with self.assertRaises(QaSafetyError):
                    self.client.transaction_write(
                        "PUT", self.parent, changed_payload, transaction_id=transaction_id,
                        campaign_id="QA-run", expected_manifest=manifest, confirmation="YES",
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
                        "campaignId": "QA-run",
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


class FakeReadClient:
    def __init__(self, plans, resources):
        self.plans = plans
        self.resources = resources
        self.get_calls = []

    def discover_plans(self):
        return {"data": {"plans": self.plans}}

    def get(self, identity, resource):
        self.get_calls.append((identity.name, resource))
        return self.resources[resource]


class ReadOnlyDiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.parent_name = "Jorsten's Plan"
        self.children = ["Jorsten Jr's Plan", "Borsten's Plan", "Thorsten's Plan"]
        self.identities = {
            name: PlanIdentity(name, IDS[name]) for name in [self.parent_name, *self.children]
        }
        plans = [{"id": identity.plan_id, "name": identity.name} for identity in self.identities.values()]
        categories = {
            "data": {
                "category_groups": [{
                    "categories": [{"id": "category-secret", "name": name, "deleted": False} for name in (
                        "QA Jorsten Jr Silver", "QA Jorsten Jr Bronze", "QA Borsten Silver",
                        "QA Borsten Bronze", "QA Thorsten Silver", "QA Thorsten Bronze",
                        "QA Unmapped", "QA Transfer Clearing",
                    )]
                }]
            }
        }
        tagged = {"data": {"transactions": [{
            "id": "transaction-secret", "account_id": "account-secret",
            "memo": "BOD QA QA-old:A1", "amount": -10,
        }, {"id": "untagged-secret", "memo": "ordinary"}]}}
        self.parent_client = FakeReadClient(plans, {
            "accounts": {"data": {"accounts": [
                {"id": "parent-account-secret", "name": "QA Cash", "closed": False,
                 "deleted": False, "type": "cash"},
                {"id": "closed-secret", "name": "Closed", "closed": True,
                 "deleted": False, "type": "cash"},
            ]}},
            "categories": categories,
            "transactions": tagged,
        })
        self.child_clients = {}
        for name in self.children:
            self.child_clients[name] = FakeReadClient(
                [{"id": self.identities[name].plan_id, "name": name}],
                {
                    "accounts": {"data": {"accounts": [
                        {"id": "silver-secret", "name": "Silver", "closed": False, "deleted": False},
                        {"id": "bronze-secret", "name": "Bronze", "closed": False, "deleted": False},
                    ]}},
                    "transactions": tagged,
                },
            )

    def test_validates_every_pair_before_reading_resources_and_redacts_ids(self):
        evidence = run_discovery(self.identities, self.parent_client, self.child_clients)

        self.assertTrue(evidence["all_targets_allowlisted"])
        self.assertTrue(evidence["provisioning_complete"])
        self.assertEqual(evidence["api_write_count"], 0)
        self.assertEqual(evidence["parent"]["eligible_fixture_accounts"], ["QA Cash"])
        self.assertNotIn("parent-account-secret", json.dumps(evidence))
        self.assertEqual(evidence["missing_parent_categories"], [])
        self.assertEqual(evidence["missing_child_accounts"], {})
        serialized = json.dumps(evidence)
        self.assertNotIn("00000000-", serialized)
        self.assertNotIn("transaction-secret", serialized)
        self.assertNotIn("account-secret", serialized)
        self.assertNotIn("untagged-secret", serialized)
        self.assertIn("BOD QA QA-old:A1", serialized)

    def test_pair_mismatch_aborts_before_any_resource_read(self):
        self.parent_client.plans[0]["id"] = IDS["Borsten's Plan"]

        with self.assertRaises(DiscoveryBlocked):
            run_discovery(self.identities, self.parent_client, self.child_clients)

        self.assertEqual(self.parent_client.get_calls, [])
        self.assertTrue(all(client.get_calls == [] for client in self.child_clients.values()))


if __name__ == "__main__":
    unittest.main()

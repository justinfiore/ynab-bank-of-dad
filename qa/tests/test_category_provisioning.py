import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

QA_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QA_ROOT))

from lib.category_provisioning import (
    CATEGORY_GROUP_NAME,
    ProvisioningBlocked,
    build_dry_run_manifest,
    execute_provisioning,
    load_local_identities,
)
from lib.read_only_discovery import PARENT_NAME, REQUIRED_PARENT_CATEGORIES
from lib.ynab_qa_client import KNOWN_NAMES, PlanIdentity, QaSafetyError, YnabQaClient


IDS = {
    name: f"10000000-0000-4000-8000-{index:012d}"
    for index, name in enumerate(sorted(KNOWN_NAMES), 1)
}
GROUP_ID = "20000000-0000-4000-8000-000000000001"
_UUID_TEXT_FOR_TEST = re.compile(
    r"(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
)


def identities():
    return {name: PlanIdentity(name, plan_id) for name, plan_id in IDS.items()}


def plans_response(overrides=None):
    values = [{"id": plan_id, "name": name} for name, plan_id in IDS.items()]
    if overrides is not None:
        values = overrides
    return {"data": {"plans": values}}


def categories_response(group_present=True, names=()):
    groups = []
    if group_present:
        groups.append({
            "id": GROUP_ID,
            "name": CATEGORY_GROUP_NAME,
            "deleted": False,
            "categories": [
                {
                    "id": f"30000000-0000-4000-8000-{index:012d}",
                    "category_group_id": GROUP_ID,
                    "name": name,
                    "deleted": False,
                }
                for index, name in enumerate(names, 1)
            ],
        })
    return {"data": {"category_groups": groups}}


class FakeClient:
    def __init__(self, plan_response=None, category_response=None):
        self.plan_response = plan_response or plans_response()
        self.category_response = category_response or categories_response()
        self.calls = []
        self.next_group_id = GROUP_ID
        self.category_index = 0

    def discover_plans(self):
        self.calls.append(("GET", "/v1/plans"))
        return self.plan_response

    def get(self, identity, resource):
        self.calls.append(("GET", f"/v1/plans/{identity.plan_id}/{resource}"))
        return self.category_response

    def create_category_group(self, identity, payload, **authorization):
        self.calls.append((
            "POST", f"/v1/plans/{identity.plan_id}/category_groups", payload, authorization,
        ))
        return {"data": {"category_group": {
            "id": self.next_group_id,
            "name": payload["category_group"]["name"],
            "deleted": False,
        }}}

    def create_category(self, identity, payload, **authorization):
        self.calls.append((
            "POST", f"/v1/plans/{identity.plan_id}/categories", payload, authorization,
        ))
        self.category_index += 1
        return {"data": {"category": {
            "id": f"40000000-0000-4000-8000-{self.category_index:012d}",
            "category_group_id": payload["category"]["category_group_id"],
            "name": payload["category"]["name"],
            "deleted": False,
        }}}


class ConfigLoadingTest(unittest.TestCase):
    def test_only_exact_ignored_local_config_path_and_four_pairs_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            local = root / "qa" / "config" / "qa-sync.yaml"
            local.parent.mkdir(parents=True)
            local.write_text(yaml.safe_dump({"budgets": {
                "parent": {"displayName": PARENT_NAME, "fullId": IDS[PARENT_NAME]},
                "children": [
                    {"displayName": name, "fullId": IDS[name]}
                    for name in sorted(KNOWN_NAMES - {PARENT_NAME})
                ],
            }}), encoding="utf-8")

            loaded = load_local_identities(local, required_path=local)

            self.assertEqual({name: item.plan_id for name, item in loaded.items()}, IDS)
            copied = root / "copied.yaml"
            copied.write_text(local.read_text(encoding="utf-8"), encoding="utf-8")
            with self.assertRaises(ProvisioningBlocked):
                load_local_identities(copied, required_path=local)

    def test_incomplete_non_uuid_or_duplicate_pairs_fail_closed(self):
        cases = (
            [{"displayName": PARENT_NAME, "fullId": IDS[PARENT_NAME]}],
            [
                {"displayName": name, "fullId": "not-a-full-uuid"}
                for name in sorted(KNOWN_NAMES)
            ],
            [
                {"displayName": name, "fullId": IDS[PARENT_NAME]}
                for name in sorted(KNOWN_NAMES)
            ],
        )
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / "qa-sync.yaml"
            for entries in cases:
                with self.subTest(entries=entries):
                    local.write_text(yaml.safe_dump({"budgets": {
                        "parent": entries[0], "children": entries[1:],
                    }}), encoding="utf-8")
                    with self.assertRaises(ProvisioningBlocked):
                        load_local_identities(local, required_path=local)


class DryRunManifestTest(unittest.TestCase):
    def test_fresh_exact_plan_discovery_precedes_category_read(self):
        client = FakeClient(plan_response=plans_response([
            {"id": IDS[PARENT_NAME], "name": "A family plan"},
        ]))

        with self.assertRaises(ProvisioningBlocked):
            build_dry_run_manifest(
                identities(), client, campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1"
            )

        self.assertEqual(client.calls, [("GET", "/v1/plans")])

    def test_absent_group_manifest_contains_one_group_and_exact_eight_categories(self):
        manifest = build_dry_run_manifest(
            identities(), FakeClient(category_response=categories_response(False)),
            campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
        )

        operations = manifest["operations"]
        self.assertEqual(operations[0]["kind"], "createCategoryGroup")
        self.assertEqual(
            [item["categoryName"] for item in operations[1:]],
            list(REQUIRED_PARENT_CATEGORIES),
        )
        self.assertEqual(manifest["missingCategoryNames"], list(REQUIRED_PARENT_CATEGORIES))
        self.assertEqual(manifest["childWritePlanCount"], 0)
        self.assertEqual(manifest["plannedWriteCount"], 9)
        encoded = json.dumps(manifest)
        self.assertNotIn(IDS[PARENT_NAME], encoded)
        self.assertNotIn("Authorization", encoded)

    def test_existing_group_only_plans_missing_required_names(self):
        present = REQUIRED_PARENT_CATEGORIES[:3]
        manifest = build_dry_run_manifest(
            identities(), FakeClient(category_response=categories_response(True, present)),
            campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
        )

        self.assertEqual(
            [item["categoryName"] for item in manifest["operations"]],
            list(REQUIRED_PARENT_CATEGORIES[3:]),
        )
        self.assertEqual(manifest["plannedWriteCount"], 5)

    def test_duplicate_group_or_required_name_outside_dedicated_group_is_blocked(self):
        duplicate_groups = categories_response()
        duplicate_groups["data"]["category_groups"].append(
            {"id": "20000000-0000-4000-8000-000000000002", "name": CATEGORY_GROUP_NAME,
             "deleted": False, "categories": []}
        )
        misplaced = categories_response(False)
        misplaced["data"]["category_groups"].append({
            "id": "20000000-0000-4000-8000-000000000003", "name": "Everyday",
            "deleted": False,
            "categories": [{
                "id": "30000000-0000-4000-8000-000000000099",
                "category_group_id": "20000000-0000-4000-8000-000000000003",
                "name": REQUIRED_PARENT_CATEGORIES[0], "deleted": False,
            }],
        })
        for response in (duplicate_groups, misplaced):
            with self.subTest(response=response), self.assertRaises(ProvisioningBlocked):
                build_dry_run_manifest(
                    identities(), FakeClient(category_response=response),
                    campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                )

    def test_malformed_category_shape_and_nonallowlisted_target_are_blocked(self):
        malformed = {"data": {"category_groups": [{"id": GROUP_ID, "name": CATEGORY_GROUP_NAME}]}}
        with self.assertRaises(ProvisioningBlocked):
            build_dry_run_manifest(
                identities(), FakeClient(category_response=malformed),
                campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
            )
        wrong = identities()
        wrong[PARENT_NAME] = PlanIdentity(PARENT_NAME, "90000000-0000-4000-8000-000000000001")
        with self.assertRaises(ProvisioningBlocked):
            build_dry_run_manifest(
                wrong, FakeClient(), campaign_id="qa-campaign-1",
                provisioning_tag="BOD-QA-PROVISION-1",
            )


class LiveProvisioningTest(unittest.TestCase):
    def _artifact(self, directory, client):
        manifest = build_dry_run_manifest(
            identities(), client, campaign_id="qa-campaign-1",
            provisioning_tag="BOD-QA-PROVISION-1",
        )
        path = Path(directory) / "category-provisioning-dry-run.json"
        path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return path, manifest

    def test_confirmation_and_exact_dry_run_agreement_are_required_before_write(self):
        with tempfile.TemporaryDirectory() as directory:
            client = FakeClient(category_response=categories_response(True))
            artifact, manifest = self._artifact(directory, client)
            receipt = Path(directory) / "receipt.json"
            reads_before = len(client.calls)
            with self.assertRaises(ProvisioningBlocked):
                execute_provisioning(
                    identities(), client, artifact, receipt,
                    campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                    confirmation="NO",
                )
            self.assertFalse(any(call[0] == "POST" for call in client.calls[reads_before:]))

            changed = dict(manifest)
            changed["plannedWriteCount"] = 999
            artifact.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaises(ProvisioningBlocked):
                execute_provisioning(
                    identities(), client, artifact, receipt,
                    campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                    confirmation="YES",
                )
            self.assertFalse(any(call[0] == "POST" for call in client.calls[reads_before:]))

    def test_live_creates_absent_group_then_only_eight_categories_with_local_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            client = FakeClient(category_response=categories_response(False))
            artifact, _ = self._artifact(directory, client)
            receipt_path = Path(directory) / "receipt.json"

            receipt = execute_provisioning(
                identities(), client, artifact, receipt_path,
                campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                confirmation="YES",
            )

            posts = [call for call in client.calls if call[0] == "POST"]
            self.assertEqual(len(posts), 9)
            self.assertEqual(posts[0][1].rsplit("/", 1)[-1], "category_groups")
            self.assertEqual([call[2]["category"]["name"] for call in posts[1:]],
                             list(REQUIRED_PARENT_CATEGORIES))
            for call in posts:
                self.assertNotIn("memo", json.dumps(call[2]))
                self.assertNotIn("BOD-QA-PROVISION-1", json.dumps(call[2]))
                self.assertEqual(call[3]["campaign_id"], "qa-campaign-1")
                self.assertEqual(call[3]["provisioning_tag"], "BOD-QA-PROVISION-1")
            self.assertEqual(receipt["successfulResponseObjectCount"], 9)
            persisted = receipt_path.read_text(encoding="utf-8")
            self.assertNotIn(IDS[PARENT_NAME], persisted)
            self.assertNotIn(GROUP_ID, persisted)
            self.assertNotIn("Authorization", persisted)

    def test_wrong_response_object_count_stops_and_records_no_raw_body(self):
        class BadClient(FakeClient):
            def create_category(self, identity, payload, **authorization):
                self.calls.append(("POST", "/bad", payload, authorization))
                return {"data": {"categories": []}, "raw_secret": "must-not-be-recorded"}

        with tempfile.TemporaryDirectory() as directory:
            client = BadClient(category_response=categories_response(True))
            artifact, _ = self._artifact(directory, client)
            receipt_path = Path(directory) / "receipt.json"
            with self.assertRaises(ProvisioningBlocked):
                execute_provisioning(
                    identities(), client, artifact, receipt_path,
                    campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                    confirmation="YES",
                )
            self.assertNotIn("must-not-be-recorded", receipt_path.read_text(encoding="utf-8"))


class CategoryClientRouteTest(unittest.TestCase):
    def setUp(self):
        self.client = YnabQaClient("test-token", IDS)
        self.parent = identities()[PARENT_NAME]

    def test_official_create_routes_are_explicit_and_manifest_bound(self):
        cases = (
            (
                "create_category_group", {"category_group": {"name": CATEGORY_GROUP_NAME}},
                f"plans/{self.parent.plan_id}/category_groups",
            ),
            (
                "create_category", {"category": {
                    "category_group_id": GROUP_ID, "name": REQUIRED_PARENT_CATEGORIES[0],
                }}, f"plans/{self.parent.plan_id}/categories",
            ),
        )
        for method_name, payload, route in cases:
            with self.subTest(method=method_name):
                client = YnabQaClient("test-token", IDS)
                with patch.object(client, "_request", return_value={"data": {}}) as request:
                    getattr(client, method_name)(
                        self.parent, payload,
                        campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                        expected_payload=payload, confirmation="YES",
                    )
                request.assert_called_once_with("POST", route, payload)

    def test_category_writes_reject_child_plan_confirmation_and_payload_differences(self):
        payload = {"category_group": {"name": CATEGORY_GROUP_NAME}}
        cases = (
            (identities()["Borsten's Plan"], payload, payload, "YES"),
            (self.parent, payload, {"category_group": {"name": "Different"}}, "YES"),
            (self.parent, payload, payload, "NO"),
        )
        for identity, actual, expected, confirmation in cases:
            with self.subTest(identity=identity, confirmation=confirmation), patch.object(
                self.client, "_request"
            ) as request:
                with self.assertRaises(QaSafetyError):
                    self.client.create_category_group(
                        identity, actual,
                        campaign_id="qa-campaign-1", provisioning_tag="BOD-QA-PROVISION-1",
                        expected_payload=expected, confirmation=confirmation,
                    )
                request.assert_not_called()

    def test_no_account_create_or_category_rename_surface_exists(self):
        self.assertFalse(hasattr(self.client, "create_account"))
        self.assertFalse(hasattr(self.client, "rename_category"))
        self.assertFalse(hasattr(self.client, "patch_category"))


class ProvisioningDocumentationTest(unittest.TestCase):
    def test_cli_exposes_fixed_config_dry_run_and_live_modes(self):
        result = subprocess.run(
            [sys.executable, str(QA_ROOT / "provision_categories.py"), "--help"],
            check=True, capture_output=True, text=True,
        )
        self.assertIn("--dry-run", result.stdout)
        self.assertIn("--provision", result.stdout)
        self.assertNotIn("--config", result.stdout)

    def test_docs_record_supported_routes_and_child_account_prerequisite(self):
        readme = (QA_ROOT / "README.md").read_text(encoding="utf-8")
        for expected in (
            "POST /v1/plans/{plan_id}/category_groups",
            "POST /v1/plans/{plan_id}/categories",
            "PATCH /v1/plans/{plan_id}/categories/{category_id}",
            "account creation is not supported",
            "verified prerequisites",
            "QA_CONFIRM_PROVISIONING_MUTATIONS=YES",
        ):
            self.assertIn(expected, readme)

    def test_tracked_examples_are_valid_redacted_json(self):
        for name in (
            "category-provisioning-dry-run.json.example",
            "category-provisioning-receipt.json.example",
        ):
            text = (QA_ROOT / "config" / name).read_text(encoding="utf-8")
            json.loads(text)
            self.assertIsNone(_UUID_TEXT_FOR_TEST.search(text))
            self.assertNotIn("Authorization", text)


if __name__ == "__main__":
    unittest.main()

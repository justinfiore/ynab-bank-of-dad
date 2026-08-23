#!/usr/bin/env python3
"""Fail-closed planning and execution for parent-plan QA categories only."""

from __future__ import annotations

import hashlib
import json
import re
import uuid
from pathlib import Path
from typing import Any, Mapping

import yaml

from .read_only_discovery import PARENT_NAME, REQUIRED_PARENT_CATEGORIES
from .ynab_qa_client import KNOWN_NAMES, PlanIdentity


CATEGORY_GROUP_NAME = "BOD Reconciliation QA"
LOCAL_QA_CONFIG = Path(__file__).resolve().parents[1] / "config" / "qa-sync.yaml"
GROUP_ROUTE = "/v1/plans/{plan_id}/category_groups"
CATEGORY_ROUTE = "/v1/plans/{plan_id}/categories"
_SAFE_TAG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,79}\Z")
_UUID_TEXT = re.compile(
    r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"
)


class ProvisioningBlocked(RuntimeError):
    """Raised whenever a provisioning invariant is not exactly satisfied."""


def _full_uuid(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise ProvisioningBlocked(f"{label} must be a complete UUID")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError):
        raise ProvisioningBlocked(f"{label} must be a complete UUID") from None
    if str(parsed) != value.lower():
        raise ProvisioningBlocked(f"{label} must use a complete canonical UUID")
    return value


def _fingerprint(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def _require_safe_tags(campaign_id: str, provisioning_tag: str) -> None:
    if not _SAFE_TAG.fullmatch(campaign_id or ""):
        raise ProvisioningBlocked("Campaign ID is not request-safe")
    if not _SAFE_TAG.fullmatch(provisioning_tag or ""):
        raise ProvisioningBlocked("Provisioning tag is not request-safe")
    if campaign_id == provisioning_tag or not provisioning_tag.startswith("BOD-QA-PROVISION"):
        raise ProvisioningBlocked("A distinct BOD-QA-PROVISION tag is required")


def load_local_identities(
    path: Path = LOCAL_QA_CONFIG, *, required_path: Path = LOCAL_QA_CONFIG
) -> dict[str, PlanIdentity]:
    """Load identities only from the one ignored local QA config."""
    lexical_candidate = path.absolute()
    lexical_required = required_path.absolute()
    if (
        lexical_candidate != lexical_required
        or lexical_candidate.name != "qa-sync.yaml"
        or lexical_candidate.is_symlink()
    ):
        raise ProvisioningBlocked("Only the ignored local qa-sync.yaml is accepted")
    candidate = lexical_candidate.resolve()
    if not candidate.is_file():
        raise ProvisioningBlocked("Ignored local QA config is missing")
    try:
        raw = yaml.safe_load(candidate.read_text(encoding="utf-8"))
        budgets = raw["budgets"]
        parent = budgets["parent"]
        children = budgets["children"]
    except (OSError, TypeError, KeyError, yaml.YAMLError):
        raise ProvisioningBlocked("Ignored local QA config is malformed") from None
    if not isinstance(parent, Mapping) or not isinstance(children, list) or len(children) != 3:
        raise ProvisioningBlocked("QA config must contain one parent and three children")
    entries = [parent, *children]
    if any(not isinstance(item, Mapping) for item in entries):
        raise ProvisioningBlocked("Every QA plan identity must be a name/UUID pair")
    names = [item.get("displayName") for item in entries]
    plan_ids = [item.get("fullId") for item in entries]
    if names[0] != PARENT_NAME or set(names) != KNOWN_NAMES or len(set(names)) != 4:
        raise ProvisioningBlocked("QA config must contain the four exact plan names")
    full_ids = [_full_uuid(value, "Plan ID") for value in plan_ids]
    if len(set(full_ids)) != 4:
        raise ProvisioningBlocked("QA plan UUIDs must be unique")
    return {
        str(item["displayName"]): PlanIdentity(str(item["displayName"]), plan_id)
        for item, plan_id in zip(entries, full_ids)
    }


def _validate_identity_set(identities: Mapping[str, PlanIdentity]) -> PlanIdentity:
    if set(identities) != KNOWN_NAMES or len(identities) != 4:
        raise ProvisioningBlocked("Exactly four QA plan identities are required")
    ids: list[str] = []
    for name in KNOWN_NAMES:
        identity = identities.get(name)
        if not isinstance(identity, PlanIdentity) or identity.name != name:
            raise ProvisioningBlocked("Configured QA plan identity is malformed")
        ids.append(_full_uuid(identity.plan_id, "Plan ID"))
    if len(set(ids)) != 4:
        raise ProvisioningBlocked("Configured QA plan UUIDs must be unique")
    return identities[PARENT_NAME]


def _validate_fresh_discovery(response: Mapping[str, Any], identities: Mapping[str, PlanIdentity]) -> None:
    try:
        plans = response["data"]["plans"]
    except (TypeError, KeyError):
        raise ProvisioningBlocked("Fresh GET /plans response is malformed") from None
    if not isinstance(plans, list) or any(not isinstance(item, Mapping) for item in plans):
        raise ProvisioningBlocked("Fresh GET /plans response is malformed")
    for name, identity in identities.items():
        exact = [item for item in plans if item.get("name") == name and item.get("id") == identity.plan_id]
        same_name = [item for item in plans if item.get("name") == name]
        same_id = [item for item in plans if item.get("id") == identity.plan_id]
        if len(exact) != 1 or len(same_name) != 1 or len(same_id) != 1:
            raise ProvisioningBlocked("Fresh discovery did not validate every exact QA plan pair")


def _category_state(response: Mapping[str, Any]) -> tuple[str | None, set[str]]:
    try:
        groups = response["data"]["category_groups"]
    except (TypeError, KeyError):
        raise ProvisioningBlocked("GET categories response is malformed") from None
    if not isinstance(groups, list) or any(not isinstance(group, Mapping) for group in groups):
        raise ProvisioningBlocked("GET categories response is malformed")

    seen_group_ids: set[str] = set()
    active_group_names: set[str] = set()
    seen_category_ids: set[str] = set()
    active_category_names: set[str] = set()
    dedicated_ids: list[str] = []
    required_locations: dict[str, str] = {}

    for group in groups:
        if set(("id", "name", "deleted", "categories")) - set(group):
            raise ProvisioningBlocked("Category group object is incomplete")
        group_id = _full_uuid(group.get("id"), "Category group ID")
        group_name = group.get("name")
        deleted = group.get("deleted")
        categories = group.get("categories")
        if not isinstance(group_name, str) or not group_name or not isinstance(deleted, bool):
            raise ProvisioningBlocked("Category group object is malformed")
        if not isinstance(categories, list) or any(not isinstance(item, Mapping) for item in categories):
            raise ProvisioningBlocked("Category group categories are malformed")
        if group_id in seen_group_ids:
            raise ProvisioningBlocked("Duplicate category group ID is ambiguous")
        seen_group_ids.add(group_id)
        if not deleted:
            if group_name in active_group_names:
                raise ProvisioningBlocked("Duplicate active category group name is ambiguous")
            active_group_names.add(group_name)
            if group_name == CATEGORY_GROUP_NAME:
                dedicated_ids.append(group_id)
        elif group_name == CATEGORY_GROUP_NAME:
            raise ProvisioningBlocked("Dedicated QA category group exists only as deleted")

        for category in categories:
            if set(("id", "category_group_id", "name", "deleted")) - set(category):
                raise ProvisioningBlocked("Category object is incomplete")
            category_id = _full_uuid(category.get("id"), "Category ID")
            category_group_id = _full_uuid(category.get("category_group_id"), "Category group ID")
            category_name = category.get("name")
            category_deleted = category.get("deleted")
            if category_group_id != group_id:
                raise ProvisioningBlocked("Category is attached to an unexpected group")
            if not isinstance(category_name, str) or not category_name or not isinstance(category_deleted, bool):
                raise ProvisioningBlocked("Category object is malformed")
            if category_id in seen_category_ids:
                raise ProvisioningBlocked("Duplicate category ID is ambiguous")
            seen_category_ids.add(category_id)
            if category_deleted:
                if category_name in REQUIRED_PARENT_CATEGORIES:
                    raise ProvisioningBlocked("Required QA category name exists only as deleted")
                continue
            if category_name in active_category_names:
                raise ProvisioningBlocked("Duplicate active category name is ambiguous")
            active_category_names.add(category_name)
            if category_name in REQUIRED_PARENT_CATEGORIES:
                required_locations[category_name] = group_id

    if len(dedicated_ids) > 1:
        raise ProvisioningBlocked("Dedicated QA category group is ambiguous")
    dedicated_id = dedicated_ids[0] if dedicated_ids else None
    if any(group_id != dedicated_id for group_id in required_locations.values()):
        raise ProvisioningBlocked("Required QA category name exists outside its dedicated group")
    return dedicated_id, set(required_locations)


def _operation_metadata(campaign_id: str, provisioning_tag: str) -> dict[str, str]:
    return {"campaignId": campaign_id, "provisioningTag": provisioning_tag, "storage": "local-only"}


def build_dry_run_manifest(
    identities: Mapping[str, PlanIdentity],
    client: Any,
    *,
    campaign_id: str,
    provisioning_tag: str,
) -> dict[str, Any]:
    """Perform fresh reads and return a deterministic, UUID-redacted write plan."""
    parent = _validate_identity_set(identities)
    _require_safe_tags(campaign_id, provisioning_tag)
    _validate_fresh_discovery(client.discover_plans(), identities)
    dedicated_id, existing_required = _category_state(client.get(parent, "categories"))
    missing = [name for name in REQUIRED_PARENT_CATEGORIES if name not in existing_required]
    operations: list[dict[str, Any]] = []
    if dedicated_id is None and missing:
        operations.append({
            "operationId": "create-dedicated-category-group",
            "kind": "createCategoryGroup",
            "method": "POST",
            "route": GROUP_ROUTE,
            "expectedRequest": {"category_group": {"name": CATEGORY_GROUP_NAME}},
            "expectedResponse": {"object": "category_group", "count": 1},
            "evidenceMetadata": _operation_metadata(campaign_id, provisioning_tag),
        })
    group_selector = (
        {"source": "create-dedicated-category-group"}
        if dedicated_id is None
        else {"existingIdFingerprint": _fingerprint(dedicated_id)}
    )
    for name in missing:
        operations.append({
            "operationId": "create-category-" + hashlib.sha256(name.encode("utf-8")).hexdigest()[:12],
            "kind": "createCategory",
            "method": "POST",
            "route": CATEGORY_ROUTE,
            "categoryName": name,
            "categoryGroupSelector": group_selector,
            "expectedRequestTemplate": {
                "category": {"category_group_id": "$DEDICATED_QA_CATEGORY_GROUP_ID", "name": name}
            },
            "expectedResponse": {"object": "category", "count": 1},
            "evidenceMetadata": _operation_metadata(campaign_id, provisioning_tag),
        })
    manifest = {
        "artifactType": "qa-category-provisioning-dry-run",
        "schemaVersion": 1,
        "mode": "DRY_RUN",
        "campaignId": campaign_id,
        "provisioningTag": provisioning_tag,
        "targetPlan": {"displayName": PARENT_NAME, "idFingerprint": _fingerprint(parent.plan_id)},
        "validatedExactPlanPairCount": 4,
        "categoryGroupName": CATEGORY_GROUP_NAME,
        "categoryGroupState": "ABSENT" if dedicated_id is None else "PRESENT",
        "missingCategoryNames": missing,
        "operations": operations,
        "plannedWriteCount": len(operations),
        "childWritePlanCount": 0,
        "accountWritePlanCount": 0,
        "renameWritePlanCount": 0,
    }
    _validate_manifest_scope(manifest)
    return manifest


def _validate_manifest_scope(manifest: Mapping[str, Any]) -> None:
    operations = manifest.get("operations")
    if (
        manifest.get("targetPlan", {}).get("displayName") != PARENT_NAME
        or manifest.get("childWritePlanCount") != 0
        or manifest.get("accountWritePlanCount") != 0
        or manifest.get("renameWritePlanCount") != 0
        or not isinstance(operations, list)
        or manifest.get("plannedWriteCount") != len(operations)
    ):
        raise ProvisioningBlocked("Manifest scope is not parent-category-create-only")
    allowed = {GROUP_ROUTE, CATEGORY_ROUTE}
    if any(item.get("method") != "POST" or item.get("route") not in allowed for item in operations):
        raise ProvisioningBlocked("Manifest contains a nonallowlisted route or method")
    category_names = [item.get("categoryName") for item in operations if item.get("kind") == "createCategory"]
    if len(category_names) != len(set(category_names)) or any(
        name not in REQUIRED_PARENT_CATEGORIES for name in category_names
    ):
        raise ProvisioningBlocked("Manifest category names are not the exact required subset")


def write_redacted_json(path: Path, value: Mapping[str, Any]) -> None:
    encoded = json.dumps(value, indent=2, sort_keys=True) + "\n"
    lowered = encoded.lower()
    if _UUID_TEXT.search(encoded) or "authorization" in lowered or "bearer " in lowered or "token" in lowered:
        raise ProvisioningBlocked("Refusing to persist sensitive or unredacted evidence")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(encoded, encoding="utf-8")


def _read_manifest(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise ProvisioningBlocked("Dry-run artifact is missing or malformed") from None
    if not isinstance(value, Mapping):
        raise ProvisioningBlocked("Dry-run artifact is malformed")
    return value


def _response_object(
    response: Mapping[str, Any], object_name: str, expected_name: str, group_id: str | None = None
) -> Mapping[str, Any]:
    try:
        data = response["data"]
        item = data[object_name]
    except (TypeError, KeyError):
        raise ProvisioningBlocked("Write response did not contain exactly one expected object") from None
    if not isinstance(data, Mapping) or not isinstance(item, Mapping):
        raise ProvisioningBlocked("Write response did not contain exactly one expected object")
    if isinstance(data.get(object_name + "s"), list):
        raise ProvisioningBlocked("Write response count was not exactly one")
    item_id = _full_uuid(item.get("id"), "Created object ID")
    if item.get("name") != expected_name or item.get("deleted") is not False:
        raise ProvisioningBlocked("Write response object did not match the authorized request")
    if group_id is not None and item.get("category_group_id") != group_id:
        raise ProvisioningBlocked("Created category response named an unexpected group")
    return {"idFingerprint": _fingerprint(item_id), "name": expected_name}


def execute_provisioning(
    identities: Mapping[str, PlanIdentity],
    client: Any,
    dry_run_path: Path,
    receipt_path: Path,
    *,
    campaign_id: str,
    provisioning_tag: str,
    confirmation: str | None,
) -> dict[str, Any]:
    """Re-read, compare the dry run exactly, then execute manifest-bound POSTs."""
    if confirmation != "YES":
        raise ProvisioningBlocked("QA_CONFIRM_PROVISIONING_MUTATIONS=YES is required")
    if dry_run_path.resolve() == receipt_path.resolve():
        raise ProvisioningBlocked("Receipt must not overwrite the reviewed dry-run artifact")
    expected = _read_manifest(dry_run_path)
    current = build_dry_run_manifest(
        identities, client, campaign_id=campaign_id, provisioning_tag=provisioning_tag
    )
    if expected != current:
        raise ProvisioningBlocked("Fresh dry run does not agree exactly with the reviewed artifact")
    _validate_manifest_scope(expected)
    parent = identities[PARENT_NAME]
    receipts: list[dict[str, Any]] = []
    group_id: str | None = None
    receipt: dict[str, Any] = {
        "artifactType": "qa-category-provisioning-receipt",
        "schemaVersion": 1,
        "status": "IN_PROGRESS",
        "campaignId": campaign_id,
        "provisioningTag": provisioning_tag,
        "targetPlan": {"displayName": PARENT_NAME, "idFingerprint": _fingerprint(parent.plan_id)},
        "plannedWriteCount": expected["plannedWriteCount"],
        "successfulResponseObjectCount": 0,
        "childWriteCount": 0,
        "operations": receipts,
    }
    try:
        category_response = client.get(parent, "categories")
        group_id, existing_required = _category_state(category_response)
        missing_now = [
            name for name in REQUIRED_PARENT_CATEGORIES if name not in existing_required
        ]
        state_now = "ABSENT" if group_id is None else "PRESENT"
        if (
            state_now != expected.get("categoryGroupState")
            or missing_now != expected.get("missingCategoryNames")
        ):
            raise ProvisioningBlocked("Category state changed after dry-run authorization")
        if expected.get("categoryGroupState") == "PRESENT":
            category_operations = [
                item for item in expected["operations"] if item.get("kind") == "createCategory"
            ]
            selectors = {json.dumps(item.get("categoryGroupSelector"), sort_keys=True)
                         for item in category_operations}
            expected_selector = json.dumps(
                {"existingIdFingerprint": _fingerprint(group_id)}, sort_keys=True
            )
            if selectors and selectors != {expected_selector}:
                raise ProvisioningBlocked("Dedicated group changed after dry-run authorization")
        for operation in expected["operations"]:
            if operation["kind"] == "createCategoryGroup":
                payload = operation["expectedRequest"]
                response = client.create_category_group(
                    parent,
                    payload,
                    campaign_id=campaign_id,
                    provisioning_tag=provisioning_tag,
                    expected_payload=payload,
                    confirmation=confirmation,
                )
                safe_object = _response_object(response, "category_group", CATEGORY_GROUP_NAME)
                group_id = response["data"]["category_group"]["id"]
            elif operation["kind"] == "createCategory":
                if group_id is None:
                    raise ProvisioningBlocked("No exact dedicated group ID is authorized")
                payload = {"category": {
                    "category_group_id": group_id,
                    "name": operation["categoryName"],
                }}
                response = client.create_category(
                    parent,
                    payload,
                    campaign_id=campaign_id,
                    provisioning_tag=provisioning_tag,
                    expected_payload=payload,
                    confirmation=confirmation,
                )
                safe_object = _response_object(
                    response, "category", operation["categoryName"], group_id
                )
            else:
                raise ProvisioningBlocked("Manifest operation kind is not allowlisted")
            receipts.append({
                "operationId": operation["operationId"],
                "kind": operation["kind"],
                "route": operation["route"],
                "responseObjectCount": 1,
                "responseObject": safe_object,
                "evidenceMetadata": _operation_metadata(campaign_id, provisioning_tag),
            })
            receipt["successfulResponseObjectCount"] = len(receipts)
        if len(receipts) != expected["plannedWriteCount"]:
            raise ProvisioningBlocked("Successful response count did not equal planned write count")
        receipt["status"] = "PASS"
        write_redacted_json(receipt_path, receipt)
        return receipt
    except Exception as error:
        receipt["status"] = "BLOCKED"
        receipt["reason"] = "A write response or manifest check failed closed"
        write_redacted_json(receipt_path, receipt)
        if isinstance(error, ProvisioningBlocked):
            raise
        raise ProvisioningBlocked("Provisioning transport failed closed") from None

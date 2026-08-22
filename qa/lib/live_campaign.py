#!/usr/bin/env python3
"""Fail-closed primitives shared by disposable-plan live QA campaigns."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from typing import Any

from .read_only_discovery import _require_exact_pairs
from .ynab_qa_client import KNOWN_NAMES, PlanIdentity, QaSafetyError


class FreshMutationGate:
    """Authorize one exact manifest entry after fresh parent and target discovery."""

    def __init__(self, identities: Mapping[str, PlanIdentity], campaign_id: str):
        if set(identities) != KNOWN_NAMES or not campaign_id.startswith("QA-"):
            raise QaSafetyError("Live QA requires the exact four-plan allowlist and a QA campaign ID")
        self._identities = dict(identities)
        self._campaign_id = campaign_id
        self._consumed: set[str] = set()

    def authorize(
        self,
        operation: str,
        identity: PlanIdentity,
        payload: Mapping[str, Any] | None,
        transaction_id: str | None,
        manifest: list[Mapping[str, Any]],
        parent_discovered_plans: list[Mapping[str, Any]],
        target_discovered_plans: list[Mapping[str, Any]],
        confirmation: str | None,
        *,
        existing_transaction: Mapping[str, Any] | None = None,
    ) -> None:
        if confirmation != "YES":
            raise QaSafetyError("QA_CONFIRM_LIVE_MUTATIONS=YES is required")
        if self._identities.get(identity.name) != identity:
            raise QaSafetyError("Mutation target is outside the exact QA allowlist")
        try:
            _require_exact_pairs(parent_discovered_plans, self._identities)
            _require_exact_pairs(target_discovered_plans, {identity.name: identity})
        except Exception as error:
            raise QaSafetyError("Fresh exact immutable plan discovery failed") from error
        if operation not in {"create", "update", "delete"}:
            raise QaSafetyError("Unsupported transaction mutation")
        if operation in {"update", "delete"}:
            if not transaction_id or existing_transaction is None:
                raise QaSafetyError("Existing target observation is required")
            memo = str(existing_transaction.get("memo") or "")
            if self._campaign_id not in memo or "BOD QA" not in memo:
                raise QaSafetyError("An untagged transaction must never be modified")
        elif transaction_id is not None:
            raise QaSafetyError("Create must not name an existing transaction")

        expected = {
            "operation": operation,
            "targetPlanId": identity.plan_id,
            "campaignId": self._campaign_id,
        }
        if transaction_id is not None:
            expected["targetTransactionId"] = transaction_id
        if payload is not None:
            expected["payload"] = payload
        matches = [item for item in manifest if item == expected]
        if len(matches) != 1:
            raise QaSafetyError("Mutation must match exactly one expected manifest entry")
        fingerprint = json.dumps(expected, sort_keys=True, separators=(",", ":"))
        if fingerprint in self._consumed:
            raise QaSafetyError("Expected mutation manifest entry was already consumed")
        self._consumed.add(fingerprint)


def _reference(value: Any) -> str | None:
    if value is None:
        return None
    return "ref:" + hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12]


def fixture_import_id(tag: str, ordinal: int) -> str:
    if not tag.startswith("BOD QA QA-") or ordinal < 1:
        raise QaSafetyError("Fixture import identity requires a tagged QA fixture and positive ordinal")
    digest = hashlib.sha256(f"{tag}:{ordinal}".encode("utf-8")).hexdigest()[:30]
    return "QA:" + digest


def fixture_amount(amount: int, subtransactions: list[dict[str, Any]] | None) -> int:
    """Use the component total for a YNAB split transaction fixture."""
    if subtransactions is None:
        return amount
    values = [item.get("amount") for item in subtransactions]
    if not values or any(type(value) is not int for value in values):
        raise QaSafetyError("Split fixture components must contain integer amounts")
    return sum(value for value in values if type(value) is int)


def evidence_transaction(raw: Mapping[str, Any]) -> dict[str, Any]:
    """Normalize a transaction recursively without emitting API resource identifiers."""
    allowed = {
        "id", "date", "amount", "payee_name", "memo", "account_id", "account_name",
        "category_id", "category_name", "approved", "cleared", "deleted",
        "parent_transaction_id", "transfer_transaction_id", "subtransactions",
    }
    result: dict[str, Any] = {}
    for key, value in raw.items():
        if key not in allowed:
            continue
        if key == "subtransactions":
            result[key] = [evidence_transaction(item) for item in value or []]
        elif key == "id" or key.endswith("_id"):
            result[key] = _reference(value)
        else:
            result[key] = value
    return result

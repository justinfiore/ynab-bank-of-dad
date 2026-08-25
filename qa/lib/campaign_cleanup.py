"""Exact-campaign cleanup using the existing QA transaction mutation gates."""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

from .live_campaign import FreshMutationGate, memo_has_exact_campaign
from .read_only_discovery import _plans, _require_exact_pairs
from .ynab_qa_client import KNOWN_NAMES, PlanIdentity, QaSafetyError, YnabQaClient


CAMPAIGN_ID = re.compile(r"QA-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")


def cleanup_exact_campaign(
    campaign_id: str,
    identities: Mapping[str, PlanIdentity],
    clients: Mapping[str, YnabQaClient],
    *,
    confirmation: str | None,
) -> dict[str, Any]:
    """Discover, validate, then delete only exact tagged transactions.

    The returned artifact schema intentionally contains no resource identifiers.
    """
    if confirmation != "YES":
        raise QaSafetyError("QA_CONFIRM_LIVE_MUTATIONS=YES is required")
    if CAMPAIGN_ID.fullmatch(campaign_id) is None:
        raise QaSafetyError("Cleanup requires one exact QA- campaign ID")
    if set(identities) != KNOWN_NAMES or set(clients) != KNOWN_NAMES:
        raise QaSafetyError("Cleanup requires exactly the four QA plans and clients")

    parent_name = "Jorsten's Plan"
    parent_discovery = _plans(clients[parent_name].discover_plans())
    target_discoveries = {
        name: _plans(clients[name].discover_plans()) for name in sorted(KNOWN_NAMES)
    }
    _require_exact_pairs(parent_discovery, identities)
    for name in sorted(KNOWN_NAMES):
        _require_exact_pairs(target_discoveries[name], {name: identities[name]})

    # Complete all reads before the first delete, so an identity failure cannot
    # leave a partially cleaned campaign.
    candidates: list[tuple[str, Mapping[str, Any]]] = []
    for name in sorted(KNOWN_NAMES):
        response = clients[name].get(identities[name], "transactions")
        transactions = response.get("data", {}).get("transactions")
        if not isinstance(transactions, list):
            raise QaSafetyError("Transaction discovery did not return a list")
        candidates.extend(
            (name, item) for item in transactions
            if not item.get("deleted") and memo_has_exact_campaign(item.get("memo"), campaign_id)
        )

    if any(not isinstance(item.get("id"), str) or not item.get("id") for _, item in candidates):
        raise QaSafetyError("Tagged cleanup target is missing its transaction ID")

    gate = FreshMutationGate(identities, campaign_id)
    deleted_by_plan = {name: 0 for name in sorted(KNOWN_NAMES)}
    for name, transaction in candidates:
        transaction_id = transaction.get("id")
        assert isinstance(transaction_id, str) and transaction_id
        identity = identities[name]
        manifest = [{
            "operation": "delete",
            "targetPlanId": identity.plan_id,
            "campaignId": campaign_id,
            "targetTransactionId": transaction_id,
        }]
        gate.authorize(
            "delete", identity, None, transaction_id, manifest,
            parent_discovery, target_discoveries[name], confirmation,
            existing_transaction=transaction,
        )
        clients[name].transaction_write(
            "DELETE", identity, None, transaction_id=transaction_id,
            campaign_id=campaign_id, expected_manifest=manifest,
            confirmation=confirmation,
        )
        deleted_by_plan[name] += 1

    return {
        "campaign_id": campaign_id,
        "exact_four_plan_pairs_validated": True,
        "matched_count": len(candidates),
        "deleted_count": sum(deleted_by_plan.values()),
        "deleted_by_plan_name": deleted_by_plan,
        "transaction_ids_retained": False,
    }

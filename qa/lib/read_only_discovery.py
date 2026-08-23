#!/usr/bin/env python3
"""Fail-closed, GET-only YNAB discovery with redacted evidence output."""

from __future__ import annotations

from typing import Any, Mapping

from .ynab_qa_client import KNOWN_NAMES, PlanIdentity


REQUIRED_PARENT_CATEGORIES = (
    "QA Jorsten Jr Silver",
    "QA Jorsten Jr Bronze",
    "QA Borsten Silver",
    "QA Borsten Bronze",
    "QA Thorsten Silver",
    "QA Thorsten Bronze",
    "QA Unmapped",
    "QA Transfer Clearing",
)
REQUIRED_CHILD_ACCOUNTS = ("Silver", "Bronze")
PARENT_NAME = "Jorsten's Plan"
CHILD_NAMES = ("Jorsten Jr's Plan", "Borsten's Plan", "Thorsten's Plan")


class DiscoveryBlocked(RuntimeError):
    """Raised before scoped resource reads when any immutable identity differs."""


def _plans(response: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    plans = response.get("data", {}).get("plans")
    if not isinstance(plans, list):
        raise DiscoveryBlocked("Plan discovery response did not contain a plan list")
    return plans


def _require_exact_pairs(
    discovered: list[Mapping[str, Any]], expected: Mapping[str, PlanIdentity]
) -> None:
    for name, identity in expected.items():
        exact = [item for item in discovered if item.get("name") == name and item.get("id") == identity.plan_id]
        same_name = [item for item in discovered if item.get("name") == name]
        same_id = [item for item in discovered if item.get("id") == identity.plan_id]
        if len(exact) != 1 or len(same_name) != 1 or len(same_id) != 1:
            raise DiscoveryBlocked("Exact immutable QA plan name/full-ID allowlist validation failed")


def _tagged_transactions(response: Mapping[str, Any]) -> list[dict[str, Any]]:
    transactions = response.get("data", {}).get("transactions")
    if not isinstance(transactions, list):
        raise RuntimeError("Transaction discovery response did not contain a transaction list")
    safe_fields = ("date", "amount", "memo", "payee_name", "account_name", "category_name",
                   "approved", "cleared", "deleted")
    return [
        {key: item.get(key) for key in safe_fields}
        for item in transactions
        if "BOD QA" in str(item.get("memo") or "")
    ]


def _category_names(response: Mapping[str, Any]) -> set[str]:
    groups = response.get("data", {}).get("category_groups")
    if not isinstance(groups, list):
        raise RuntimeError("Category discovery response did not contain category groups")
    return {
        str(category.get("name"))
        for group in groups
        for category in group.get("categories", [])
        if not category.get("deleted") and category.get("name")
    }


def _account_names(response: Mapping[str, Any]) -> set[str]:
    accounts = response.get("data", {}).get("accounts")
    if not isinstance(accounts, list):
        raise RuntimeError("Account discovery response did not contain an account list")
    return {
        str(account.get("name"))
        for account in accounts
        if not account.get("deleted") and not account.get("closed") and account.get("name")
    }


def _eligible_fixture_account_names(response: Mapping[str, Any]) -> list[str]:
    """Expose only names for open parent accounts usable by synthetic fixtures."""
    accounts = response.get("data", {}).get("accounts")
    if not isinstance(accounts, list):
        raise RuntimeError("Account discovery response did not contain an account list")
    return sorted({
        str(account.get("name"))
        for account in accounts
        if not account.get("deleted") and not account.get("closed") and account.get("name")
    })


def run_discovery(
    identities: Mapping[str, PlanIdentity],
    parent_client: Any,
    child_clients: Mapping[str, Any],
) -> dict[str, Any]:
    """Validate all identities first, then issue only scoped GET requests."""
    if set(identities) != KNOWN_NAMES or set(child_clients) != set(CHILD_NAMES):
        raise DiscoveryBlocked("Discovery requires exactly the four configured QA plans")

    parent_plans = _plans(parent_client.discover_plans())
    child_plan_lists = {
        name: _plans(child_clients[name].discover_plans()) for name in CHILD_NAMES
    }
    _require_exact_pairs(parent_plans, identities)
    for name in CHILD_NAMES:
        _require_exact_pairs(child_plan_lists[name], {name: identities[name]})

    parent_accounts = _eligible_fixture_account_names(
        parent_client.get(identities[PARENT_NAME], "accounts")
    )
    parent_categories = _category_names(parent_client.get(identities[PARENT_NAME], "categories"))
    tagged_by_plan = {
        PARENT_NAME: _tagged_transactions(parent_client.get(identities[PARENT_NAME], "transactions"))
    }
    missing_accounts: dict[str, list[str]] = {}
    account_observations: dict[str, dict[str, Any]] = {}
    for name in CHILD_NAMES:
        client = child_clients[name]
        account_names = _account_names(client.get(identities[name], "accounts"))
        missing = sorted(set(REQUIRED_CHILD_ACCOUNTS) - account_names)
        if missing:
            missing_accounts[name] = missing
        account_observations[name] = {
            "required_accounts": list(REQUIRED_CHILD_ACCOUNTS),
            "required_accounts_present": not missing,
        }
        tagged_by_plan[name] = _tagged_transactions(client.get(identities[name], "transactions"))

    missing_categories = sorted(set(REQUIRED_PARENT_CATEGORIES) - parent_categories)
    provisioning_complete = not missing_categories and not missing_accounts
    return {
        "discovery_mode": "READ_ONLY_GET",
        "all_targets_allowlisted": True,
        "validated_plan_names": [PARENT_NAME, *CHILD_NAMES],
        "full_immutable_id_validation": "PASS (values retained only in ignored internal config)",
        "parent": {
            "display_name": PARENT_NAME,
            "eligible_fixture_accounts": parent_accounts,
            "required_categories": list(REQUIRED_PARENT_CATEGORIES),
            "required_categories_present": not missing_categories,
        },
        "children": account_observations,
        "missing_parent_categories": missing_categories,
        "missing_child_accounts": missing_accounts,
        "provisioning_complete": provisioning_complete,
        "tagged_qa_transactions": tagged_by_plan,
        "tagged_transaction_filter": "Only memos containing 'BOD QA' retained in evidence",
        "api_write_count": 0,
    }

#!/usr/bin/env python3
"""Deterministic, uniquely tagged QA transaction fixture construction."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any


@dataclass(frozen=True)
class TransactionFixture:
    campaign_id: str
    scenario_id: str
    account_id: str
    category_id: str | None
    amount_milliunits: int
    payee_name: str
    approved: bool = True
    cleared: str = "cleared"
    transaction_date: str = date.today().isoformat()

    @property
    def tag(self) -> str:
        return f"{self.campaign_id}:{self.scenario_id}"

    def payload(self) -> dict[str, Any]:
        if not self.campaign_id.startswith("QA-") or not self.scenario_id:
            raise ValueError("Fixture requires QA campaign and scenario IDs")
        if self.amount_milliunits == 0 or abs(self.amount_milliunits) > 1000:
            raise ValueError("QA fixture amount must be $0.01-$1.00 in magnitude")
        return {
            "transaction": {
                "account_id": self.account_id,
                "category_id": self.category_id,
                "date": self.transaction_date,
                "amount": self.amount_milliunits,
                "payee_name": self.payee_name,
                "memo": f"BOD QA {self.tag}",
                "cleared": self.cleared,
                "approved": self.approved,
            }
        }


def normalize_transaction(raw: dict[str, Any]) -> dict[str, Any]:
    """Return only evidence-safe, review-relevant transaction fields."""
    return {
        key: raw.get(key)
        for key in (
            "id",
            "date",
            "amount",
            "payee_name",
            "memo",
            "account_id",
            "account_name",
            "category_id",
            "category_name",
            "approved",
            "cleared",
            "deleted",
            "parent_transaction_id",
        )
    }


def tagged_matches(transactions: list[dict[str, Any]], tag: str) -> list[dict[str, Any]]:
    return [normalize_transaction(item) for item in transactions if tag in str(item.get("memo") or "")]

#!/usr/bin/env python3
"""Minimal YNAB QA client with exact-plan and tagged-transaction guards.

The client intentionally has no account/category mutation methods. Transaction
writes are rejected unless the plan identity is allowlisted, live confirmation
is exact, an expected operation is present, and the campaign tag is in the memo.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping


API_ROOT = "https://api.ynab.com/v1"
KNOWN_NAMES = {
    "Jorsten's Plan",
    "Jorsten Jr's Plan",
    "Borsten's Plan",
    "Thorsten's Plan",
}


class QaSafetyError(RuntimeError):
    """Raised before a request when a QA safety invariant is not satisfied."""


@dataclass(frozen=True)
class PlanIdentity:
    name: str
    plan_id: str


class YnabQaClient:
    def __init__(self, token: str, allowlist: Mapping[str, str], timeout: int = 30):
        if not token or any(ch.isspace() for ch in token):
            raise QaSafetyError("A non-empty test token is required")
        if set(allowlist) != KNOWN_NAMES or len(set(allowlist.values())) != 4:
            raise QaSafetyError("Allowlist must contain exactly the four QA name/ID pairs")
        if any(not self._looks_like_uuid(value) for value in allowlist.values()):
            raise QaSafetyError("Every allowlisted plan ID must be a full UUID")
        self._token = token
        self._allowlist = dict(allowlist)
        self._timeout = timeout

    @staticmethod
    def _looks_like_uuid(value: str) -> bool:
        parts = value.split("-")
        return [len(part) for part in parts] == [8, 4, 4, 4, 12] and all(
            ch in "0123456789abcdefABCDEF" for part in parts for ch in part
        )

    def require_allowed(self, identity: PlanIdentity) -> None:
        if self._allowlist.get(identity.name) != identity.plan_id:
            raise QaSafetyError("Exact plan name/full-ID pair is not allowlisted")

    def get(self, identity: PlanIdentity, resource: str) -> dict[str, Any]:
        self.require_allowed(identity)
        resource = resource.strip("/")
        if resource not in {"accounts", "categories", "transactions"} and not resource.startswith(
            "transactions/"
        ):
            raise QaSafetyError("Read resource is outside the QA client contract")
        return self._request("GET", f"plans/{identity.plan_id}/{resource}")

    def discover_plans(self) -> dict[str, Any]:
        return self._request("GET", "plans")

    def transaction_write(
        self,
        method: str,
        identity: PlanIdentity,
        payload: Mapping[str, Any] | None,
        *,
        transaction_id: str | None,
        campaign_id: str,
        expected_manifest: list[Mapping[str, Any]],
        confirmation: str | None = None,
    ) -> dict[str, Any]:
        self.require_allowed(identity)
        if confirmation is None:
            confirmation = os.environ.get("QA_CONFIRM_LIVE_MUTATIONS")
        if confirmation != "YES":
            raise QaSafetyError("Live mutation confirmation is missing")
        operation = {"POST": "create", "PUT": "update", "DELETE": "delete"}.get(method)
        if operation is None:
            raise QaSafetyError("Only transaction POST/PUT/DELETE operations are permitted")
        expected = [item for item in expected_manifest if item.get("operation") == operation]
        if not expected or any(item.get("targetPlanId") != identity.plan_id for item in expected):
            raise QaSafetyError("Expected mutation manifest does not authorize this operation/target")
        if operation != "delete":
            transaction = (payload or {}).get("transaction", payload or {})
            memo = str(transaction.get("memo") or "")
            if not campaign_id or campaign_id not in memo:
                raise QaSafetyError("Every transaction write must carry the campaign ID in its memo")
        else:
            if not transaction_id or not any(
                item.get("targetTransactionId") == transaction_id for item in expected
            ):
                raise QaSafetyError("Delete target is not explicitly authorized by the manifest")
        suffix = f"/{transaction_id}" if transaction_id else ""
        return self._request(method, f"plans/{identity.plan_id}/transactions{suffix}", payload)

    def _request(self, method: str, path: str, payload: Mapping[str, Any] | None = None) -> dict[str, Any]:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            f"{API_ROOT}/{path}",
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self._token}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # Never include headers, token, or a response URL in evidence/logs.
            raise RuntimeError(f"YNAB API returned HTTP {error.code}") from None
        except urllib.error.URLError as error:
            raise RuntimeError(f"YNAB API request failed: {error.reason}") from None

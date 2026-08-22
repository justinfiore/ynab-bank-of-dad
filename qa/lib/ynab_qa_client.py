#!/usr/bin/env python3
"""Minimal YNAB QA client with exact-plan and manifest-bound write guards.

The client has transaction fixture methods plus category-create methods for the
one QA parent plan. It intentionally has no account-create, category-rename, or
generic mutation method.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from threading import Lock
from typing import Any, Callable, Mapping


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
    def __init__(
        self,
        token: str,
        allowlist: Mapping[str, str],
        timeout: int = 30,
        *,
        max_get_rate_limit_retries: int = 3,
        sleeper: Callable[[float], None] = time.sleep,
    ):
        if not token or any(ch.isspace() for ch in token):
            raise QaSafetyError("A non-empty test token is required")
        if set(allowlist) != KNOWN_NAMES or len(set(allowlist.values())) != 4:
            raise QaSafetyError("Allowlist must contain exactly the four QA name/ID pairs")
        if any(not self._looks_like_uuid(value) for value in allowlist.values()):
            raise QaSafetyError("Every allowlisted plan ID must be a full UUID")
        if max_get_rate_limit_retries < 0:
            raise QaSafetyError("Rate-limit retry count must be non-negative")
        self._token = token
        self._allowlist = dict(allowlist)
        self._timeout = timeout
        self._max_get_rate_limit_retries = max_get_rate_limit_retries
        self._sleeper = sleeper
        self._consumed_manifest_authorizations: set[str] = set()
        self._manifest_authorization_lock = Lock()
        self._consumed_provisioning_authorizations: set[str] = set()

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

    def create_category_group(
        self,
        identity: PlanIdentity,
        payload: Mapping[str, Any],
        *,
        campaign_id: str,
        provisioning_tag: str,
        expected_payload: Mapping[str, Any],
        confirmation: str | None = None,
    ) -> dict[str, Any]:
        return self._category_create_write(
            identity, "category_groups", payload, campaign_id=campaign_id,
            provisioning_tag=provisioning_tag, expected_payload=expected_payload,
            confirmation=confirmation,
        )

    def create_category(
        self,
        identity: PlanIdentity,
        payload: Mapping[str, Any],
        *,
        campaign_id: str,
        provisioning_tag: str,
        expected_payload: Mapping[str, Any],
        confirmation: str | None = None,
    ) -> dict[str, Any]:
        return self._category_create_write(
            identity, "categories", payload, campaign_id=campaign_id,
            provisioning_tag=provisioning_tag, expected_payload=expected_payload,
            confirmation=confirmation,
        )

    def _category_create_write(
        self,
        identity: PlanIdentity,
        resource: str,
        payload: Mapping[str, Any],
        *,
        campaign_id: str,
        provisioning_tag: str,
        expected_payload: Mapping[str, Any],
        confirmation: str | None,
    ) -> dict[str, Any]:
        self.require_allowed(identity)
        if identity.name != "Jorsten's Plan":
            raise QaSafetyError("Category provisioning is restricted to the exact QA parent plan")
        if confirmation is None:
            confirmation = os.environ.get("QA_CONFIRM_PROVISIONING_MUTATIONS")
        if confirmation != "YES":
            raise QaSafetyError("Category provisioning confirmation is missing")
        if (
            not campaign_id
            or not provisioning_tag.startswith("BOD-QA-PROVISION")
            or campaign_id == provisioning_tag
        ):
            raise QaSafetyError("Distinct QA campaign and provisioning tags are required")
        if payload != expected_payload:
            raise QaSafetyError("Category write differs from its expected manifest payload")
        if resource == "category_groups":
            expected_keys = {"category_group"}
            body = payload.get("category_group")
            valid = (
                set(payload) == expected_keys
                and isinstance(body, Mapping)
                and set(body) == {"name"}
                and body.get("name") == "BOD Reconciliation QA"
            )
        elif resource == "categories":
            expected_keys = {"category"}
            body = payload.get("category")
            valid = (
                set(payload) == expected_keys
                and isinstance(body, Mapping)
                and set(body) == {"category_group_id", "name"}
                and self._looks_like_uuid(str(body.get("category_group_id") or ""))
                and body.get("name") in {
                    "QA Jorsten Jr Silver", "QA Jorsten Jr Bronze",
                    "QA Borsten Silver", "QA Borsten Bronze",
                    "QA Thorsten Silver", "QA Thorsten Bronze",
                    "QA Unmapped", "QA Transfer Clearing",
                }
            )
        else:
            valid = False
        if not valid:
            raise QaSafetyError("Category create payload is outside the provisioning contract")
        fingerprint = json.dumps({
            "campaignId": campaign_id,
            "provisioningTag": provisioning_tag,
            "resource": resource,
            "targetPlanId": identity.plan_id,
            "payload": payload,
        }, sort_keys=True, separators=(",", ":"))
        with self._manifest_authorization_lock:
            if fingerprint in self._consumed_provisioning_authorizations:
                raise QaSafetyError("Category manifest authorization was already consumed")
            self._consumed_provisioning_authorizations.add(fingerprint)
        return self._request("POST", f"plans/{identity.plan_id}/{resource}", payload)

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
        candidates = [
            item for item in expected_manifest
            if item.get("operation") == operation
            and item.get("targetPlanId") == identity.plan_id
            and item.get("campaignId") == campaign_id
        ]
        if operation == "create":
            if transaction_id is not None:
                raise QaSafetyError("Create must not name an existing transaction")
            if not isinstance(payload, Mapping):
                raise QaSafetyError("Create requires a manifest-bound transaction payload")
            transaction = payload.get("transaction", payload)
            required = {"account_id", "amount", "date", "memo", "import_id"}
            if not isinstance(transaction, Mapping) or not required.issubset(transaction):
                raise QaSafetyError("Create payload fingerprint is incomplete")
            import_id = transaction.get("import_id")
            if (
                not isinstance(import_id, str)
                or not import_id.strip()
                or import_id != import_id.strip()
            ):
                raise QaSafetyError("Create requires a stable non-empty import_id")
            if not any(field in transaction for field in ("payee_id", "payee_name")):
                raise QaSafetyError("Create payload fingerprint must include a payee field")
            memo = str(transaction.get("memo") or "")
            if not campaign_id or campaign_id not in memo:
                raise QaSafetyError("Every transaction write must carry the campaign ID in its memo")
            candidates = [item for item in candidates if item.get("payload") == payload]
        elif operation == "update":
            if not transaction_id:
                raise QaSafetyError("Update must name the exact target transaction")
            candidates = [
                item for item in candidates
                if item.get("targetTransactionId") == transaction_id
                and item.get("payload") == payload
            ]
        else:
            if payload is not None:
                raise QaSafetyError("Delete must not carry a mutation payload")
            if not transaction_id:
                raise QaSafetyError("Delete must name the exact target transaction")
            candidates = [
                item for item in candidates
                if item.get("targetTransactionId") == transaction_id
                and "payload" not in item
            ]
        if len(candidates) != 1:
            raise QaSafetyError(
                "Mutation must match exactly one expected manifest entry without payload differences"
            )
        authorization_fingerprint = json.dumps(
            {
                "campaignId": campaign_id,
                "operation": operation,
                "payload": payload,
                "targetPlanId": identity.plan_id,
                "targetTransactionId": transaction_id,
                "importId": (
                    payload.get("transaction", payload).get("import_id")
                    if operation == "create" and isinstance(payload, Mapping)
                    else None
                ),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        with self._manifest_authorization_lock:
            if authorization_fingerprint in self._consumed_manifest_authorizations:
                raise QaSafetyError("Matching manifest authorization was already consumed")
            self._consumed_manifest_authorizations.add(authorization_fingerprint)
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
        attempts = 0
        while True:
            try:
                with urllib.request.urlopen(request, timeout=self._timeout) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if (
                    method == "GET"
                    and error.code == 429
                    and attempts < self._max_get_rate_limit_retries
                ):
                    self._sleeper(self._retry_delay(error))
                    attempts += 1
                    continue
                # Never include headers, token, or a response URL in evidence/logs.
                raise RuntimeError(f"YNAB API returned HTTP {error.code}") from None
            except urllib.error.URLError as error:
                raise RuntimeError(f"YNAB API request failed: {error.reason}") from None

    @staticmethod
    def _retry_delay(error: urllib.error.HTTPError) -> float:
        """Use a bounded numeric Retry-After, or a conservative 30-second fallback."""
        try:
            seconds = int(error.headers.get("Retry-After", ""))
            if seconds > 0:
                return float(min(seconds, 30))
        except (TypeError, ValueError):
            pass
        return 30.0

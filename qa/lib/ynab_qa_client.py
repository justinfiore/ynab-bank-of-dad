#!/usr/bin/env python3
"""Minimal YNAB QA client with exact-plan and manifest-bound write guards.

The client has transaction fixture methods plus category-create methods for the
one QA parent plan. It intentionally has no account-create, category-rename, or
generic mutation method.
"""

from __future__ import annotations

import json
import logging
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import timezone
from email.utils import parsedate_to_datetime
from threading import Lock
from typing import Any, Callable, Mapping


API_ROOT = "https://api.ynab.com/v1"
LINEAR_STEP_SECONDS = 5
MAX_RATE_LIMIT_WAIT_SECONDS = 3600
DEFAULT_CLEANUP_PACING_MS = 500
RESET_HEADERS = ("ratelimit-reset", "x-ratelimit-reset", "x-rate-limit-reset")
REMAINING_HEADERS = ("ratelimit-remaining", "x-ratelimit-remaining", "x-rate-limit-remaining")
RATE_LIMIT_HEADER_NAMES = (
    "retry-after",
    *RESET_HEADERS,
    *REMAINING_HEADERS,
    "ratelimit-limit",
    "x-ratelimit-limit",
    "x-rate-limit",
)
KNOWN_NAMES = {
    "Jorsten's Plan",
    "Jorsten Jr's Plan",
    "Borsten's Plan",
    "Thorsten's Plan",
}

LOGGER = logging.getLogger(__name__)


def parse_access_tokens(raw: str | None) -> list[str]:
    """Split a single token or CSV of tokens, trim, drop empties, de-dupe."""
    if raw is None:
        return []
    tokens: list[str] = []
    for part in str(raw).split(","):
        token = part.strip()
        if token and token not in tokens:
            tokens.append(token)
    return tokens


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
        max_rate_limit_retries: int | None = None,
        sleeper: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.time,
        request_pacing_ms: int = 0,
    ):
        tokens = parse_access_tokens(token)
        if not tokens or any(any(ch.isspace() for ch in item) for item in tokens):
            raise QaSafetyError("A non-empty test token is required")
        if set(allowlist) != KNOWN_NAMES or len(set(allowlist.values())) != 4:
            raise QaSafetyError("Allowlist must contain exactly the four QA name/ID pairs")
        if any(not self._looks_like_uuid(value) for value in allowlist.values()):
            raise QaSafetyError("Every allowlisted plan ID must be a full UUID")
        if max_rate_limit_retries is not None and (
            isinstance(max_rate_limit_retries, bool)
            or not isinstance(max_rate_limit_retries, int)
            or max_rate_limit_retries < 0
        ):
            raise QaSafetyError("Rate-limit retry count must be a non-negative integer or None")
        self._tokens = tokens
        self._token_index = 0
        self._allowlist = dict(allowlist)
        self._timeout = timeout
        self._max_rate_limit_retries = max_rate_limit_retries
        self._sleeper = sleeper
        self._clock = clock
        self._request_pacing_ms = 0
        self.set_request_pacing_ms(request_pacing_ms)
        self._consumed_manifest_authorizations: set[str] = set()
        self._manifest_authorization_lock = Lock()
        self._consumed_provisioning_authorizations: set[str] = set()
        self._request_telemetry: dict[tuple[str, str, str], dict[str, int]] = {}
        self._request_telemetry_lock = Lock()

    def set_request_pacing_ms(self, pacing_ms: int) -> None:
        if isinstance(pacing_ms, bool) or not isinstance(pacing_ms, int) or pacing_ms < 0:
            raise QaSafetyError("Request pacing must be a non-negative integer number of milliseconds")
        self._request_pacing_ms = pacing_ms

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
        if resource not in {"accounts", "categories", "transactions", "money_movements"} and not resource.startswith(
            "transactions/"
        ):
            raise QaSafetyError("Read resource is outside the QA client contract")
        return self._request("GET", f"plans/{identity.plan_id}/{resource}")

    def discover_plans(self) -> dict[str, Any]:
        return self._request("GET", "plans")

    def request_telemetry(self) -> list[dict[str, Any]]:
        """Return aggregate request evidence containing only an intentionally safe schema."""
        with self._request_telemetry_lock:
            rows = [
                {
                    "method": method,
                    "resource_class": resource_class,
                    "status_class": status_class,
                    "count": totals["count"],
                    "retry_count": totals["retry_count"],
                }
                for (method, resource_class, status_class), totals
                in self._request_telemetry.items()
            ]
        return sorted(
            rows,
            key=lambda item: (
                item["method"], item["resource_class"],
                item["status_class"],
            ),
        )

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
        resource_class = self._logical_resource_class(path)
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        backoff_cycles = 0
        tried: set[int] = set()
        delays: list[tuple[int, float | None]] = []
        while True:
            request = urllib.request.Request(
                f"{API_ROOT}/{path}",
                data=data,
                method=method,
                headers={
                    "Authorization": f"Bearer {self._tokens[self._token_index]}",
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                },
            )
            if self._request_pacing_ms:
                self._sleeper(self._request_pacing_ms / 1000.0)
            try:
                with urllib.request.urlopen(request, timeout=self._timeout) as response:
                    status = getattr(response, "status", None)
                    self._record_request(method, resource_class, self._status_class(status or 200))
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code != 429:
                    self._record_request(method, resource_class, self._status_class(error.code))
                    raise RuntimeError(f"YNAB API returned HTTP {error.code}") from None
                parsed_delay = self._parse_retry_delay(error)
                remaining = self._parse_remaining(error)
                present = self._present_rate_limit_headers(error)
                self._log_rate_limit(method, resource_class, remaining, parsed_delay, present)
                self._record_request(
                    method, resource_class, self._status_class(error.code), retried=True,
                )
                tried.add(self._token_index)
                delays.append((self._token_index, parsed_delay))
                next_token = self._next_unused_token(tried)
                if next_token is not None:
                    self._token_index = next_token
                    continue
                if (
                    self._max_rate_limit_retries is not None
                    and backoff_cycles >= self._max_rate_limit_retries
                ):
                    raise RuntimeError(f"YNAB API returned HTTP {error.code}") from None
                usable = [delay for _, delay in delays if delay is not None]
                wait = min(usable) if usable else LINEAR_STEP_SECONDS * (backoff_cycles + 1)
                wait = float(min(wait, MAX_RATE_LIMIT_WAIT_SECONDS))
                self._sleeper(wait)
                backoff_cycles += 1
                soonest = min(
                    ((idx, delay) for idx, delay in delays if delay is not None),
                    default=None,
                    key=lambda item: item[1],
                )
                self._token_index = soonest[0] if soonest is not None else 0
                tried.clear()
                delays.clear()
            except urllib.error.URLError:
                self._record_request(method, resource_class, "transport_error")
                raise RuntimeError("YNAB API request failed") from None

    @staticmethod
    def _logical_resource_class(path: str) -> str:
        parts = path.strip("/").split("/")
        if parts == ["plans"]:
            return "plans"
        resource = parts[2] if len(parts) >= 3 and parts[0] == "plans" else "unknown"
        return resource if resource in {
            "accounts", "categories", "category_groups", "transactions", "money_movements",
        } else "unknown"

    @staticmethod
    def _status_class(status: int) -> str:
        return f"{status // 100}xx" if 100 <= status <= 599 else "unknown"

    def _record_request(
        self, method: str, resource_class: str, status_class: str, *, retried: bool = False,
    ) -> None:
        key = (method, resource_class, status_class)
        with self._request_telemetry_lock:
            totals = self._request_telemetry.setdefault(key, {"count": 0, "retry_count": 0})
            totals["count"] += 1
            totals["retry_count"] += int(retried)

    def _retry_delay(self, error: urllib.error.HTTPError, attempt: int) -> float:
        """Return a bounded resume delay without retaining or exposing raw headers."""
        parsed = self._parse_retry_delay(error)
        wait = parsed if parsed is not None else LINEAR_STEP_SECONDS * (attempt + 1)
        return float(min(wait, MAX_RATE_LIMIT_WAIT_SECONDS))

    def _parse_retry_delay(self, error: urllib.error.HTTPError) -> float | None:
        def header_value(target: str) -> Any:
            for name, value in error.headers.items() if error.headers else ():
                if str(name).lower() == target:
                    return value
            return None

        waits: list[float] = []
        retry_after = header_value("retry-after")
        if retry_after is not None:
            value = str(retry_after).strip()
            try:
                seconds = int(value)
                if seconds > 0:
                    waits.append(float(seconds))
            except ValueError:
                try:
                    resume_at = parsedate_to_datetime(value)
                    if resume_at.tzinfo is None:
                        resume_at = resume_at.replace(tzinfo=timezone.utc)
                    seconds = resume_at.timestamp() - self._clock()
                    if seconds > 0:
                        waits.append(seconds)
                except (TypeError, ValueError, OverflowError):
                    pass
        for name in RESET_HEADERS:
            try:
                seconds = float(str(header_value(name) or "").strip()) - self._clock()
                if seconds > 0:
                    waits.append(seconds)
            except (TypeError, ValueError, OverflowError):
                pass
        if not waits:
            return None
        return float(min(max(waits), MAX_RATE_LIMIT_WAIT_SECONDS))

    def _parse_remaining(self, error: urllib.error.HTTPError) -> int | None:
        headers = error.headers
        if not headers:
            return None
        for target in REMAINING_HEADERS:
            for name, value in headers.items():
                if str(name).lower() == target:
                    try:
                        return int(str(value).strip())
                    except (TypeError, ValueError):
                        continue
        for name, value in headers.items():
            if str(name).lower() == "x-rate-limit" and "/" in str(value):
                used_raw, limit_raw = str(value).split("/", 1)
                try:
                    return int(limit_raw.strip()) - int(used_raw.strip())
                except ValueError:
                    return None
        return None

    def _present_rate_limit_headers(self, error: urllib.error.HTTPError) -> list[str]:
        present: list[str] = []
        if not error.headers:
            return present
        seen = {str(name).lower() for name, value in error.headers.items() if value is not None}
        for name in RATE_LIMIT_HEADER_NAMES:
            if name in seen:
                present.append(name)
        return present

    def _next_unused_token(self, tried: set[int]) -> int | None:
        if len(tried) >= len(self._tokens):
            return None
        for step in range(1, len(self._tokens) + 1):
            candidate = (self._token_index + step) % len(self._tokens)
            if candidate not in tried:
                return candidate
        return None

    def _log_rate_limit(
        self,
        method: str,
        resource_class: str,
        remaining: int | None,
        retry_delay: float | None,
        header_names: list[str],
    ) -> None:
        slot = self._token_index + 1
        total = len(self._tokens)
        parts = [f"YNAB {method} {resource_class} rate limited on token {slot} of {total}"]
        if remaining is not None:
            parts.append(f"remaining={remaining}")
        if retry_delay is not None:
            parts.append(f"retry after {retry_delay}s")
        if header_names:
            parts.append("headers=" + ",".join(header_names))
        if remaining is None and retry_delay is None:
            parts.append("no remaining/reset metadata")
        LOGGER.info("; ".join(parts))


def cleanup_pacing_ms(environ: Mapping[str, str] | None = None) -> int:
    """Parse cleanup-only request pacing without exposing environment contents."""
    source = os.environ if environ is None else environ
    raw = source.get("QA_CLEANUP_PACING_MS", "")
    value = str(raw).strip()
    if not value:
        return DEFAULT_CLEANUP_PACING_MS
    try:
        pacing_ms = int(value)
    except ValueError:
        raise QaSafetyError("QA_CLEANUP_PACING_MS must be a non-negative integer") from None
    if pacing_ms < 0:
        raise QaSafetyError("QA_CLEANUP_PACING_MS must be a non-negative integer")
    return pacing_ms


def merge_request_telemetry(clients: Mapping[str, YnabQaClient]) -> list[dict[str, Any]]:
    """Merge client metrics without retaining which token or plan issued a request."""
    totals: dict[tuple[str, str, str], dict[str, int]] = {}
    for client in clients.values():
        for row in client.request_telemetry():
            key = (row["method"], row["resource_class"], row["status_class"])
            aggregate = totals.setdefault(key, {"count": 0, "retry_count": 0})
            aggregate["count"] += row["count"]
            aggregate["retry_count"] += row["retry_count"]
    return [
        {
            "method": key[0], "resource_class": key[1], "status_class": key[2],
            "count": totals[key]["count"], "retry_count": totals[key]["retry_count"],
        }
        for key in sorted(totals)
    ]

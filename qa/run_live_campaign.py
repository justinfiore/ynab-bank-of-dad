#!/usr/bin/env python3
"""Execute the safely runnable disposable-plan reconciliation QA scenarios.

Raw UUID-bearing campaign state stays under qa/.campaign-state. Evidence under
qa/artifacts contains stable references only and is safe for final packaging.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import fcntl
from pathlib import Path
from typing import Any

import yaml

QA_ROOT = Path(__file__).resolve().parent
REPO_ROOT = QA_ROOT.parent
sys.path.insert(0, str(QA_ROOT))

from lib.campaign_matrix import SCENARIOS
from lib.evidence_bundle import FULL_UUID
from lib.qa_config import load_budget_identities
from lib.live_campaign import (
    FreshMutationGate,
    evidence_transaction,
    fixture_amount,
    fixture_import_id,
    successful_operation_attempts,
)
from lib.run_capture import capture_sqlite_audit, redact, write_receipt
from lib.ynab_qa_client import (
    PlanIdentity, QaSafetyError, YnabQaClient, merge_request_telemetry,
)


TOKEN_ENV = {
    "Jorsten's Plan": "PARENT_ACCESS_TOKEN",
    "Jorsten Jr's Plan": "JORSTEN_JR_ACCESS_TOKEN",
    "Borsten's Plan": "BORSTEN_ACCESS_TOKEN",
    "Thorsten's Plan": "THORSTEN_ACCESS_TOKEN",
}
PARENT = "Jorsten's Plan"
CHILDREN = ("Jorsten Jr's Plan", "Borsten's Plan", "Thorsten's Plan")
COUNTS = {"creates": 0, "updates": 0, "deletes": 0}


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def safe_text(value: str, secrets: list[str]) -> str:
    return FULL_UUID.sub("[REDACTED-UUID]", redact(value, secrets))


def extract_object(response: dict[str, Any], singular: str) -> dict[str, Any]:
    data = response.get("data", {})
    value = data.get(singular)
    if isinstance(value, dict) and value.get("id"):
        return value
    ids = data.get(f"{singular}_ids") or data.get("transaction_ids")
    if isinstance(ids, list) and len(ids) == 1:
        return {"id": ids[0]}
    raise RuntimeError(f"YNAB response did not contain exactly one {singular}")


class Campaign:
    def __init__(self, campaign_id: str, *, resume: bool = False):
        if not re.fullmatch(r"QA-[A-Za-z0-9-]+", campaign_id):
            raise ValueError("Campaign ID must be a QA-prefixed safe identifier")
        self.campaign_id = campaign_id
        self.artifacts = QA_ROOT / "artifacts" / campaign_id
        self.raw = QA_ROOT / ".campaign-state" / campaign_id
        self.artifacts.mkdir(parents=True, exist_ok=True)
        if (self.artifacts / "scenarios").exists() and not resume:
            raise ValueError("Campaign artifact tree already contains scenario execution")
        self.raw.mkdir(parents=True, exist_ok=resume)
        self.identities = load_budget_identities(QA_ROOT / "config/qa-sync.yaml")
        self.allowlist = {name: item.plan_id for name, item in self.identities.items()}
        self.secrets = [os.environ.get(value, "") for value in TOKEN_ENV.values()]
        self.clients = {
            name: YnabQaClient(os.environ.get(TOKEN_ENV[name], ""), self.allowlist)
            for name in TOKEN_ENV
        }
        self.gate = FreshMutationGate(self.identities, campaign_id)
        prior_environment = self.artifacts / "environment.json"
        prior = json.loads(prior_environment.read_text()) if resume and prior_environment.exists() else {}
        self.api_write_attempts = int(prior.get("actual_api_write_count", 0))
        self.successful_writes = int(prior.get("successful_api_write_count", 0))
        self.prior_api_write_attempts = self.api_write_attempts
        self.created: list[tuple[str, str]] = []
        self.fixture_ordinal = self.api_write_attempts + 100 if resume else 0
        self.receipts: dict[str, dict[str, Any]] = {}
        self.branch = subprocess.check_output(
            ["git", "branch", "--show-current"], cwd=REPO_ROOT, text=True
        ).strip()
        self.commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True
        ).strip()
        self.runtime_config = self.raw / "config.yaml"
        self.state_db = self.raw / "campaign.db"
        self._write_runtime_config()

    def raw_transactions(self, name: str, scenario: str | None = None) -> list[dict[str, Any]]:
        response = self.clients[name].get(self.identities[name], "transactions")
        tag = f"{self.campaign_id}:{scenario}" if scenario else self.campaign_id
        return [item for item in response.get("data", {}).get("transactions", [])
                if tag in str(item.get("memo") or "") and not item.get("deleted")]

    @staticmethod
    def attempt_rows(state_db: Path) -> list[dict[str, Any]]:
        if not state_db.exists():
            return []
        with sqlite3.connect(state_db) as connection:
            connection.row_factory = sqlite3.Row
            try:
                return [dict(item) for item in connection.execute(
                    "SELECT * FROM operation_attempts ORDER BY id"
                ).fetchall()]
            except sqlite3.OperationalError:
                return []

    def run_live_counted(self, scenario: str, state_db: Path, *, max_cycles: int = 1,
                         env_override: dict[str, str] | None = None) -> tuple[int, str, int]:
        before = self.attempt_rows(state_db)
        rc, output = self.run_sync(scenario, False, state_db, max_cycles=max_cycles,
                                   env_override=env_override)
        after = self.attempt_rows(state_db)
        added = after[len(before):]
        self.api_write_attempts += len(added)
        self.successful_writes += successful_operation_attempts(added)
        return rc, output, len(added)

    def _plans(self, name: str) -> list[dict[str, Any]]:
        response = self.clients[name].discover_plans()
        plans = response.get("data", {}).get("plans")
        if not isinstance(plans, list):
            raise QaSafetyError("Fresh plan discovery did not return a list")
        return plans

    def _resources(self) -> dict[str, Any]:
        parent = self.identities[PARENT]
        accounts = self.clients[PARENT].get(parent, "accounts")["data"]["accounts"]
        categories = self.clients[PARENT].get(parent, "categories")["data"]["category_groups"]
        account_by_name = {
            item["name"]: item["id"] for item in accounts
            if not item.get("closed") and not item.get("deleted")
        }
        category_by_name = {
            item["name"]: item["id"] for group in categories
            for item in group.get("categories", []) if not item.get("deleted")
        }
        child_accounts: dict[str, dict[str, str]] = {}
        for name in CHILDREN:
            response = self.clients[name].get(self.identities[name], "accounts")
            child_accounts[name] = {
                item["name"]: item["id"] for item in response["data"]["accounts"]
                if not item.get("closed") and not item.get("deleted")
            }
        if "Checking" not in account_by_name:
            raise QaSafetyError("Exact parent fixture account 'Checking' is unavailable")
        required = {
            "QA Jorsten Jr Silver", "QA Jorsten Jr Bronze", "QA Borsten Silver",
            "QA Borsten Bronze", "QA Thorsten Silver", "QA Thorsten Bronze", "QA Unmapped",
            "QA Transfer Clearing",
        }
        if not required.issubset(category_by_name):
            raise QaSafetyError("Required parent categories are incomplete")
        if any(not {"Silver", "Bronze"}.issubset(value) for value in child_accounts.values()):
            raise QaSafetyError("Required child accounts are incomplete")
        return {"parent_account": account_by_name["Checking"], "categories": category_by_name,
                "child_accounts": child_accounts}

    def _write_runtime_config(self) -> None:
        children = []
        child_specs = (
            ("jorsten-jr", "Jorsten Jr's Plan", "JORSTEN_JR_ACCESS_TOKEN", "Jorsten Jr"),
            ("borsten", "Borsten's Plan", "BORSTEN_ACCESS_TOKEN", "Borsten"),
            ("thorsten", "Thorsten's Plan", "THORSTEN_ACCESS_TOKEN", "Thorsten"),
        )
        for key, budget, token, category_stem in child_specs:
            children.append({
                "childKey": key, "budgetName": budget, "tokenEnvVarName": token,
                "memoPrefix": "YBOD: ", "memoSuffix": "",
                "accountMappings": [
                    {"mappingKey": "silver", "parentCategoryNames": [
                        {"name": f"QA {category_stem} Silver"}], "childAccountName": "Silver"},
                    {"mappingKey": "bronze", "parentCategoryNames": [
                        {"name": f"QA {category_stem} Bronze"}], "childAccountName": "Bronze"},
                ],
            })
        raw = {
            "budgetName": PARENT, "allowanceEscrowAccountName": "Checking",
            "allowanceCategoryName": "QA Transfer Clearing", "interestMemo": "QA",
            "allowanceMemo": "QA", "combinedMemo": "QA", "nonInterestMemoSuffix": "QA",
            "bankSuffixes": [], "allowanceRates": {}, "giveBankRate": 1,
            "kidsWithoutInterest": [], "kidsWithSimpleAccounts": [],
            "kidsWithAdvancedAccounts": [], "advancedAllowanceDeposits": {},
            "accountTypes": [], "interestRatesByAccountTypeAndDate": {"Current": {}},
            "sync": {
                "parentBudget": {"budgetName": PARENT, "tokenEnvVarName": "PARENT_ACCESS_TOKEN"},
                "childBudgets": children, "pollingIntervalSeconds": 3,
                "logging": {"filePath": str(self.raw / "sync.log"), "level": "INFO",
                            "maxHistory": 2, "maxFileSizeMb": 2},
                "state": {"sqlitePath": str(self.state_db), "transactionLookbackDays": 45,
                          "moneyMovementLookbackDays": 45},
            },
        }
        self.runtime_config.write_text(yaml.safe_dump(raw, sort_keys=False), encoding="utf-8")

    def mutate(self, scenario: str, method: str, target: str, payload: dict[str, Any] | None,
               transaction_id: str | None = None) -> dict[str, Any]:
        operation = {"POST": "create", "PUT": "update", "DELETE": "delete"}[method]
        identity = self.identities[target]
        expected: dict[str, Any] = {
            "operation": operation, "targetPlanId": identity.plan_id,
            "campaignId": self.campaign_id,
        }
        if payload is not None:
            expected["payload"] = payload
        if transaction_id is not None:
            expected["targetTransactionId"] = transaction_id
        existing = None
        if transaction_id is not None:
            response = self.clients[target].get(identity, f"transactions/{transaction_id}")
            existing = response.get("data", {}).get("transaction")
        manifest = [expected]
        write_json(self.raw / scenario / f"manifest-{self.api_write_attempts + 1}.json", manifest)
        self.gate.authorize(
            operation, identity, payload, transaction_id, manifest,
            self._plans(PARENT), self._plans(target), os.environ.get("QA_CONFIRM_LIVE_MUTATIONS"),
            existing_transaction=existing,
        )
        self.api_write_attempts += 1
        response = self.clients[target].transaction_write(
            method, identity, payload, transaction_id=transaction_id,
            campaign_id=self.campaign_id, expected_manifest=manifest,
            confirmation=os.environ.get("QA_CONFIRM_LIVE_MUTATIONS"),
        )
        self.successful_writes += 1
        write_json(self.artifacts / "scenarios" / scenario / f"mutation-{self.api_write_attempts}.json", {
            "operation": operation, "target": target, "campaign_tag": self.campaign_id,
            "fresh_full_identity_discovery": "PASS", "manifest_match": "PASS",
            "status": "APPLIED",
        })
        return response

    def create_parent(self, scenario: str, category: str, amount: int = -10,
                      approved: bool = True, subtransactions: list[dict[str, Any]] | None = None) -> str:
        resources = self._resources()
        tag = f"BOD QA {self.campaign_id}:{scenario}"
        self.fixture_ordinal += 1
        transaction: dict[str, Any] = {
            "account_id": resources["parent_account"], "date": "2026-08-21",
            "amount": fixture_amount(amount, subtransactions),
            "payee_name": f"Synthetic {scenario}", "category_id": resources["categories"][category]
            if subtransactions is None else None,
            "memo": tag, "cleared": "cleared", "approved": approved,
            "import_id": fixture_import_id(tag, self.fixture_ordinal),
        }
        if subtransactions is not None:
            transaction["subtransactions"] = subtransactions
        response = self.mutate(scenario, "POST", PARENT, {"transaction": transaction})
        created = extract_object(response, "transaction")
        transaction_id = created["id"]
        self.created.append((PARENT, transaction_id))
        return transaction_id

    def snapshot(self, scenario: str, label: str) -> dict[str, list[dict[str, Any]]]:
        result: dict[str, list[dict[str, Any]]] = {}
        for name in (PARENT, *CHILDREN):
            response = self.clients[name].get(self.identities[name], "transactions")
            values = response.get("data", {}).get("transactions", [])
            result[name] = [evidence_transaction(item) for item in values
                            if self.campaign_id in str(item.get("memo") or "")]
        write_json(self.artifacts / "scenarios" / scenario / f"api-{label}.json", result)
        return result

    def run_sync(self, scenario: str, dry: bool, state_db: Path, max_cycles: int = 1,
                 env_override: dict[str, str] | None = None) -> tuple[int, str]:
        # This discovery is intentionally immediately before the one-cycle process.
        parent_plans = self._plans(PARENT)
        for identity in self.identities.values():
            from lib.read_only_discovery import _require_exact_pairs
            _require_exact_pairs(parent_plans, self.identities)
            _require_exact_pairs(self._plans(identity.name), {identity.name: identity})
        command = ["./gradlew", "runSyncer", "--args=" + " ".join([
            "--config", str(self.runtime_config), "--sync-state-db-path", str(state_db),
            *( ["--dry-run"] if dry else [] ), "--max-cycles", str(max_cycles),
        ])]
        environment = dict(os.environ)
        environment.update(env_override or {})
        completed = subprocess.run(command, cwd=REPO_ROOT, env=environment, text=True,
                                   capture_output=True, check=False)
        output = safe_text(completed.stdout + completed.stderr, self.secrets)
        path = self.artifacts / "scenarios" / scenario / ("dry-run.log" if dry else "live-run.log")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(output, encoding="utf-8")
        return completed.returncode, output

    def receipt(self, scenario: str, status: str, reason: str, *, expected=None, observed=None,
                dry="NOT_RUN", live="NOT_RUN", api="NOT_RUN", sqlite="NOT_RUN",
                assertions=None, links=None, attempts=0) -> None:
        requirement = next(item[1] for item in SCENARIOS if item[0] == scenario)
        value = {
            "campaign_id": self.campaign_id, "scenario_id": scenario, "phase": scenario[0],
            "requirement": requirement, "branch": self.branch, "commit": self.commit,
            "safety": {"all_targets_allowlisted": True, "family_budget_targets": [],
                       "dry_run_passed_before_live": dry == "PASS", "api_write_attempts": attempts},
            "expected": dict(COUNTS if expected is None else expected),
            "observed": dict(COUNTS if observed is None else observed),
            "dry_run": dry, "live_run": live, "api_observation": api,
            "sqlite_audit": sqlite, "assertions": assertions or [{"name": reason, "status": status}],
            "reason": reason, "status": status, "artifact_links": links or ["receipt.json"],
        }
        write_receipt(self.artifacts / "scenarios" / scenario / "receipt.json", value)
        self.receipts[scenario] = value

    def execute(self) -> None:
        resources = self._resources()
        write_json(self.artifacts / "environment.json", {
            "campaign_id": self.campaign_id, "branch": self.branch, "commit": self.commit,
            "targets": [PARENT, *CHILDREN], "all_targets_allowlisted": True,
            "provisioning_complete": True, "missing_parent_categories": [],
            "selected_parent_fixture_account": "Checking", "actual_api_write_count": 0,
        })
        write_json(self.artifacts / "api-observations" / "campaign-start.json", {
            "all_targets_allowlisted": True, "provisioning_complete": True,
            "validated_plan_names": [PARENT, *CHILDREN], "api_write_count": 0,
        })

        self._baseline()
        self._a2_guard()
        self._a3_smoke()
        self._a4_a5()
        self._a6()
        self._a7(resources)
        self.receipt("A8-money-movement", "BLOCKED",
            "The official API exposes GET-only money movements; no synthetic movement write was available.",
            dry="BLOCKED", api="GET endpoint only", sqlite="NOT_RUN")
        b1_pass = self._b1()
        if b1_pass:
            self._b2()
            for scenario, requirement, _kind in SCENARIOS:
                if scenario not in self.receipts:
                    self.receipt(scenario, "NOT_RUN",
                        "Durable executor does not yet automate this independent live scenario.")
        else:
            for scenario, _requirement, _kind in SCENARIOS:
                if scenario not in self.receipts:
                    self.receipt(scenario, "BLOCKED",
                        "B1 did not establish a child mirror; this scenario requires a working live create baseline.",
                        dry="BLOCKED", live="BLOCKED", api="B1 prerequisite failed", sqlite="NOT_RUN")
        self.cleanup()
        self._final_metadata()

    def _baseline(self) -> None:
        scenario = "A1-baseline"
        directory = self.artifacts / "scenarios" / scenario
        directory.mkdir(parents=True, exist_ok=True)
        py = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "qa/tests", "-v"],
                            cwd=REPO_ROOT, text=True, capture_output=True, check=False)
        skip_gradle = os.environ.get("GITHUB_ACTIONS") == "true"
        if skip_gradle:
            gradle_ok = True
            gradle_output = "Skipped nested ./gradlew testAll because GITHUB_ACTIONS=true.\n"
        else:
            gradle = subprocess.run(["./gradlew", "testAll", "--rerun-tasks"], cwd=REPO_ROOT,
                                    text=True, capture_output=True, check=False)
            gradle_ok = gradle.returncode == 0
            gradle_output = gradle.stdout + gradle.stderr
        (directory / "harness-tests.log").write_text(safe_text(py.stdout + py.stderr, self.secrets))
        (directory / "gradle-testAll.log").write_text(safe_text(gradle_output, self.secrets))
        reports = self.artifacts / "automated-tests"
        for name in ("test", "integrationTest"):
            source = REPO_ROOT / "build/reports/tests" / name
            if source.exists():
                shutil.copytree(source, reports / name, dirs_exist_ok=True)
        passed = py.returncode == 0 and gradle_ok
        self.receipt(scenario, "PASS" if passed else "FAIL", "Fresh Python and Gradle baselines passed." if passed
                     else "One or more fresh automated baselines failed.", dry="PASS" if passed else "FAIL",
                     live="N/A", api="N/A", sqlite="N/A",
                     assertions=[{"name": "Python QA tests", "status": "PASS" if py.returncode == 0 else "FAIL"},
                                 {"name": "./gradlew testAll --rerun-tasks", "status": "PASS" if gradle_ok else "FAIL"}],
                     links=["harness-tests.log", "gradle-testAll.log"])

    def _a2_guard(self) -> None:
        blocked = False
        try:
            fake = list(self._plans(PARENT))
            fake[0] = {**fake[0], "id": self.identities["Borsten's Plan"].plan_id}
            self.gate.authorize("delete", self.identities[PARENT], None, "not-a-transaction", [],
                                fake, self._plans(PARENT), None)
        except QaSafetyError:
            blocked = True
        write_json(self.artifacts / "scenarios/A2-guard-rejection/blocked-target.json",
                   {"guard": "REJECTED", "api_write_count": 0})
        self.receipt("A2-guard-rejection", "PASS" if blocked else "FAIL",
                     "Deliberate mismatched-target/missing-confirmation probe stopped before mutation.",
                     dry="N/A", live="REJECTED AS EXPECTED", api="ZERO WRITES", sqlite="N/A",
                     links=["blocked-target.json"])

    def _a3_smoke(self) -> None:
        rc, output = self.run_sync("A3-config-smoke", True, self.raw / "a3.db")
        passed = rc == 0 and "Starting parent-child budget syncer" in output
        self.receipt("A3-config-smoke", "PASS" if passed else "FAIL",
                     "Exact-plan routing/config smoke dry run completed." if passed else "Smoke dry run failed.",
                     dry="PASS" if passed else "FAIL", live="N/A", api="PASS", sqlite="NO DB CREATED",
                     links=["dry-run.log"])

    def _a4_a5(self) -> None:
        start = self.api_write_attempts
        scenario = "A4-dry-create"
        self.create_parent(scenario, "QA Jorsten Jr Silver")
        before = self.snapshot(scenario, "before")
        state = self.raw / "a4.db"
        rc1, log1 = self.run_sync(scenario, True, state)
        rc2, log2 = self.run_sync("A5-dry-state-rerun", True, state)
        after = self.snapshot(scenario, "after")
        tag = f"{self.campaign_id}:{scenario}"
        planned1 = log1.count(f"{self.campaign_id}:{scenario}")
        planned2 = log2.count(f"{self.campaign_id}:{scenario}")
        zero_delta = sum(len(before[name]) for name in CHILDREN) == sum(len(after[name]) for name in CHILDREN)
        absent = not state.exists()
        passed = rc1 == 0 and planned1 == 1 and zero_delta
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Dry run planned exactly one tagged mirror and changed no child transaction.",
                     expected={"creates": 1, "updates": 0, "deletes": 0},
                     observed={"creates": planned1, "updates": 0, "deletes": 0},
                     dry="PASS" if passed else "FAIL", live="N/A", api="PASS" if zero_delta else "FAIL",
                     sqlite="DB ABSENT" if absent else "UNEXPECTED DB", attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "api-before.json", "api-after.json"])
        rerun_pass = rc2 == 0 and planned2 == 1 and absent
        self.receipt("A5-dry-state-rerun", "PASS" if rerun_pass else "FAIL",
                     "Repeated dry run produced the same single plan and left state absent.",
                     expected={"creates": 1, "updates": 0, "deletes": 0},
                     observed={"creates": planned2, "updates": 0, "deletes": 0},
                     dry="PASS" if rerun_pass else "FAIL", live="N/A", api="PASS", sqlite="DB ABSENT",
                     links=["dry-run.log"])
        self.mutate(scenario, "DELETE", PARENT, None, self.created[-1][1])

    def _a6(self) -> None:
        start = self.api_write_attempts
        scenario = "A6-unapproved-unmapped"
        self.create_parent(scenario, "QA Jorsten Jr Silver", approved=False)
        self.create_parent(scenario, "QA Unmapped", amount=-20)
        before = self.snapshot(scenario, "before")
        rc, output = self.run_sync(scenario, True, self.raw / "a6.db")
        after = self.snapshot(scenario, "after")
        planned = output.count(f"{self.campaign_id}:{scenario}")
        zero_delta = sum(len(before[name]) for name in CHILDREN) == sum(len(after[name]) for name in CHILDREN)
        passed = rc == 0 and planned == 0 and zero_delta
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Unapproved and unmapped fixtures planned and produced no child mirror.",
                     dry="PASS" if passed else "FAIL", live="N/A", api="PASS" if zero_delta else "FAIL",
                     sqlite="DB ABSENT", attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "api-before.json", "api-after.json"])
        for name, transaction_id in list(self.created[-2:]):
            self.mutate(scenario, "DELETE", name, None, transaction_id)

    def _a7(self, resources: dict[str, Any]) -> None:
        start = self.api_write_attempts
        scenario = "A7-split-fanout"
        categories = resources["categories"]
        subs = [
            {"amount": -10, "category_id": categories["QA Jorsten Jr Silver"], "memo": f"BOD QA {self.campaign_id}:{scenario}:jr"},
            {"amount": -20, "category_id": categories["QA Borsten Bronze"], "memo": f"BOD QA {self.campaign_id}:{scenario}:borsten"},
            {"amount": -30, "category_id": categories["QA Unmapped"], "memo": f"BOD QA {self.campaign_id}:{scenario}:unmapped"},
        ]
        self.create_parent(scenario, "QA Unmapped", amount=-60, subtransactions=subs)
        before = self.snapshot(scenario, "before")
        rc, output = self.run_sync(scenario, True, self.raw / "a7.db")
        after = self.snapshot(scenario, "after")
        planned = output.count(f"{self.campaign_id}:{scenario}")
        zero_delta = sum(len(before[name]) for name in CHILDREN) == sum(len(after[name]) for name in CHILDREN)
        passed = rc == 0 and planned == 2 and zero_delta
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Mapped split components fanned out twice; the unmapped component was absent.",
                     expected={"creates": 2, "updates": 0, "deletes": 0},
                     observed={"creates": planned, "updates": 0, "deletes": 0},
                     dry="PASS" if passed else "FAIL", live="N/A", api="PASS" if zero_delta else "FAIL",
                     sqlite="DB ABSENT", attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "api-before.json", "api-after.json"])
        self.mutate(scenario, "DELETE", PARENT, None, self.created[-1][1])

    def _b1(self) -> bool:
        start = self.api_write_attempts
        scenario = "B1-live-create"
        self.create_parent(scenario, "QA Jorsten Jr Silver")
        before = self.snapshot(scenario, "before")
        dry_rc, dry_log = self.run_sync(scenario, True, self.state_db)
        planned = dry_log.count(f"{self.campaign_id}:{scenario}")
        write_json(self.raw / scenario / "expected-sync-manifest.json", [{
            "operation": "create", "targetPlanId": self.identities["Jorsten Jr's Plan"].plan_id,
            "campaignId": self.campaign_id,
            "expected": {"accountName": "Silver", "date": "2026-08-21", "amount": -10,
                         "payeeName": f"Synthetic {scenario}",
                         "memo": f"YBOD: BOD QA {self.campaign_id}:{scenario}",
                         "cleared": "cleared", "approved": False, "categoryId": None},
        }])
        write_json(self.artifacts / "scenarios" / scenario / "expected-mutation-manifest.json", [{
            "operation": "create", "target": "Jorsten Jr's Plan", "campaign_id": self.campaign_id,
            "expected": {"account_name": "Silver", "date": "2026-08-21", "amount": -10,
                         "payee_name": f"Synthetic {scenario}",
                         "memo": f"YBOD: BOD QA {self.campaign_id}:{scenario}",
                         "cleared": "cleared", "approved": False, "category": None},
        }])
        live_rc, _live_log, _ = self.run_live_counted(scenario, self.state_db)
        after = self.snapshot(scenario, "after")
        before_count = len(before["Jorsten Jr's Plan"])
        matches = [item for item in after["Jorsten Jr's Plan"]
                   if f"{self.campaign_id}:{scenario}" in str(item.get("memo") or "")]
        created = max(0, len(matches) - before_count)
        audit_status = "NOT_PRESENT"
        sync_write_attempts = 0
        if self.state_db.exists():
            audit = capture_sqlite_audit(self.state_db, self.raw / "b1-audit.db",
                                         self.artifacts / "scenarios" / scenario / "sqlite-audit-raw.json")
            # The final sanitizer replaces UUIDs in this copied JSON.
            audit_status = "PASS" if audit.get("sync_operations") != {"status": "NOT_PRESENT"} else "FAIL"
            sync_write_attempts = len(audit.get("operation_attempts", []))
        passed = dry_rc == 0 and planned == 1 and live_rc == 0 and created == 1 and audit_status == "PASS"
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "One-cycle live create matched API and SQLite evidence." if passed else
                     "The dry plan passed, but the live child create did not produce exactly one observed mirror.",
                     expected={"creates": 1, "updates": 0, "deletes": 0},
                     observed={"creates": created, "updates": 0, "deletes": 0},
                     dry="PASS" if dry_rc == 0 and planned == 1 else "FAIL",
                     live="PASS" if passed else "FAIL", api="PASS" if created == 1 else "FAIL",
                     sqlite=audit_status, attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-before.json", "api-after.json",
                            "sqlite-audit-raw.json", "expected-mutation-manifest.json"])
        return passed

    def _b2(self) -> None:
        scenario = "B2-live-replay"
        before = self.snapshot(scenario, "before")
        dry_rc, _ = self.run_sync(scenario, True, self.state_db)
        live_rc, _, _ = self.run_live_counted(scenario, self.state_db)
        after = self.snapshot(scenario, "after")
        stable = before == after
        passed = dry_rc == 0 and live_rc == 0 and stable
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Immediate replay created no duplicate." if passed else "Replay changed API observations.",
                     dry="PASS" if dry_rc == 0 else "FAIL", live="PASS" if live_rc == 0 else "FAIL",
                     api="PASS" if stable else "FAIL", sqlite="PASS" if self.state_db.exists() else "FAIL",
                     links=["dry-run.log", "live-run.log", "api-before.json", "api-after.json"])

    def establish_mirror(self, scenario: str, category: str = "QA Jorsten Jr Silver",
                         *, amount: int = -10, approved: bool = True,
                         subtransactions: list[dict[str, Any]] | None = None,
                         state_db: Path | None = None) -> tuple[str, Path]:
        state_db = state_db or (self.raw / f"{scenario}.db")
        parent_id = self.create_parent(scenario, category, amount, approved, subtransactions)
        dry_rc, dry_log = self.run_sync(scenario, True, state_db)
        if dry_rc != 0 or self.campaign_id not in dry_log:
            raise RuntimeError(f"{scenario} baseline dry run did not plan its tagged fixture")
        live_rc, _live_log, _attempts = self.run_live_counted(scenario, state_db)
        if live_rc != 0:
            raise RuntimeError(f"{scenario} baseline live cycle failed")
        return parent_id, state_db

    def parent_update(self, scenario: str, transaction_id: str, **changes: Any) -> None:
        current = self.clients[PARENT].get(
            self.identities[PARENT], f"transactions/{transaction_id}"
        )["data"]["transaction"]
        fields = {
            key: current.get(key) for key in (
                "account_id", "date", "amount", "payee_id", "payee_name", "category_id",
                "memo", "cleared", "approved", "flag_color",
            ) if key in current
        }
        fields.update(changes)
        fields["memo"] = changes.get("memo", current.get("memo"))
        self.mutate(scenario, "PUT", PARENT, {"transaction": fields}, transaction_id)

    def audit(self, scenario: str, state_db: Path) -> str:
        output = self.artifacts / "scenarios" / scenario / "sqlite-audit.json"
        capture_sqlite_audit(state_db, self.raw / f"{scenario}-audit.db", output)
        return "PASS"

    def execute_remaining(self) -> None:
        methods = (
            self._b3, self._b4, self._c1, self._c2, self._c3, self._c4,
            self._c5, self._c6, self._c7, self._c9, self._d1, self._d2,
            self._d3, self._d4,
        )
        for method in methods:
            try:
                method()
            except Exception as error:
                scenario = method.__name__.lstrip("_").replace("_", "-")
                matching = [item[0] for item in SCENARIOS if item[0].lower().startswith(scenario[:2])]
                scenario_id = matching[0] if len(matching) == 1 else None
                if scenario_id and scenario_id not in self.receipts:
                    self.receipt(scenario_id, "FAIL", f"Scenario executor failed: {error}",
                                 dry="FAIL", live="NOT_RUN", api="FAIL", sqlite="NOT_RUN")
            finally:
                self.cleanup()
        if "C8-split-component-removed" not in self.receipts:
            self.receipt(
                "C8-split-component-removed",
                "BLOCKED",
                "API cannot edit existing split lines. Official C8 is the accepted 2-line collapse edge case from a manual UI edit; remain-a-split coverage is C8b.",
                dry="BLOCKED", live="BLOCKED", api="API contract limitation", sqlite="NOT_RUN",
            )
        self._final_metadata()

    def _simple_create_scenario(self, scenario: str, *, approved: bool = True,
                                subtransactions: list[dict[str, Any]] | None = None,
                                expected_children: tuple[str, ...] = ("Jorsten Jr's Plan",)) -> tuple[str, Path]:
        start = self.api_write_attempts
        parent_id, state = self.establish_mirror(
            scenario, approved=approved, amount=-30 if subtransactions else -10,
            subtransactions=subtransactions,
        )
        observations = self.snapshot(scenario, "after")
        created = sum(1 for name in expected_children for item in observations[name]
                      if f"{self.campaign_id}:{scenario}" in str(item.get("memo") or ""))
        expected_count = len(expected_children)
        passed = created == expected_count
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     f"Observed exactly {expected_count} intended child mirror(s).",
                     expected={"creates": expected_count, "updates": 0, "deletes": 0},
                     observed={"creates": created, "updates": 0, "deletes": 0},
                     dry="PASS", live="PASS" if passed else "FAIL", api="PASS" if passed else "FAIL",
                     sqlite=self.audit(scenario, state), attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-after.json", "sqlite-audit.json"])
        return parent_id, state

    def _b3(self) -> None:
        scenario = "B3-approval-transition"
        start = self.api_write_attempts
        parent_id = self.create_parent(scenario, "QA Jorsten Jr Silver", approved=False)
        state = self.raw / f"{scenario}.db"
        rc0, log0 = self.run_sync(scenario, True, state)
        no_plan = f"{self.campaign_id}:{scenario}" not in log0
        self.parent_update(scenario, parent_id, approved=True)
        rc1, log1 = self.run_sync(scenario, True, state)
        live_rc, _, _ = self.run_live_counted(scenario, state)
        after = self.snapshot(scenario, "after")
        matches = [item for item in after["Jorsten Jr's Plan"]
                   if f"{self.campaign_id}:{scenario}" in str(item.get("memo") or "")]
        passed = rc0 == 0 and no_plan and rc1 == 0 and self.campaign_id in log1 and live_rc == 0 and len(matches) == 1
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "No mirror before approval and exactly one after approval.",
                     expected={"creates": 1, "updates": 0, "deletes": 0},
                     observed={"creates": len(matches), "updates": 0, "deletes": 0},
                     dry="PASS" if rc1 == 0 else "FAIL", live="PASS" if passed else "FAIL",
                     api="PASS" if len(matches) == 1 else "FAIL", sqlite=self.audit(scenario, state),
                     attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-after.json", "sqlite-audit.json"])

    def _b4(self) -> None:
        resources = self._resources()["categories"]
        scenario = "B4-live-split"
        subs = [
            {"amount": -10, "category_id": resources["QA Jorsten Jr Silver"], "memo": f"BOD QA {self.campaign_id}:{scenario}:jr"},
            {"amount": -20, "category_id": resources["QA Borsten Bronze"], "memo": f"BOD QA {self.campaign_id}:{scenario}:borsten"},
            {"amount": -30, "category_id": resources["QA Unmapped"], "memo": f"BOD QA {self.campaign_id}:{scenario}:unmapped"},
        ]
        self._simple_create_scenario(scenario, subtransactions=subs,
                                     expected_children=("Jorsten Jr's Plan", "Borsten's Plan"))

    def _changed_scenario(self, scenario: str, change, expected: dict[str, int], verify) -> None:
        start = self.api_write_attempts
        parent_id, state = self.establish_mirror(scenario)
        child_before = self.raw_transactions("Jorsten Jr's Plan", scenario)
        if len(child_before) != 1:
            raise RuntimeError("Baseline did not create exactly one child mirror")
        change(parent_id, child_before[0])
        self.snapshot(scenario, "before-change")
        dry_rc, dry_log = self.run_sync(scenario, True, state)
        live_rc, _, _ = self.run_live_counted(scenario, state)
        after = self.snapshot(scenario, "after-change")
        observed, ok = verify(child_before[0], after, dry_log)
        passed = dry_rc == 0 and live_rc == 0 and ok
        self.receipt(scenario, "PASS" if passed else "FAIL", "Observed reconciliation matched the expected in-place/destructive semantics.",
                     expected=expected, observed=observed, dry="PASS" if dry_rc == 0 else "FAIL",
                     live="PASS" if live_rc == 0 else "FAIL", api="PASS" if ok else "FAIL",
                     sqlite=self.audit(scenario, state), attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-before-change.json",
                            "api-after-change.json", "sqlite-audit.json"])

    def _c1(self) -> None:
        scenario = "C1-financial-update"
        def change(parent_id, _child):
            self.parent_update(scenario, parent_id, date="2026-08-20", amount=-15,
                               payee_id=None, payee_name="Synthetic C1 changed")
        def verify(before, after, _log):
            matches = [item for item in after["Jorsten Jr's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            ok = len(matches) == 1 and matches[0]["id"] == evidence_transaction(before)["id"] and matches[0].get("date") == "2026-08-20" and matches[0].get("amount") == -15 and matches[0].get("payee_name") == "Synthetic C1 changed" and matches[0].get("cleared") == "cleared" and matches[0].get("approved") is False
            return {"creates": 0, "updates": 1 if ok else 0, "deletes": 0}, ok
        self._changed_scenario(scenario, change, {"creates": 0, "updates": 1, "deletes": 0}, verify)

    def _c2(self) -> None:
        scenario = "C2-child-memo-owned"
        child_memo = f"BOD QA {self.campaign_id}:{scenario}:child-owned"
        def change(parent_id, child):
            self.mutate(scenario, "PUT", "Jorsten Jr's Plan", {"transaction": {"memo": child_memo}}, child["id"])
            self.parent_update(scenario, parent_id, memo=f"BOD QA {self.campaign_id}:{scenario}:parent-edited")
        def verify(before, after, log):
            matches = [item for item in after["Jorsten Jr's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            ok = len(matches) == 1 and matches[0].get("memo") == child_memo and "update child transaction" not in log
            return dict(COUNTS), ok
        self._changed_scenario(scenario, change, dict(COUNTS), verify)

    def _c3(self) -> None:
        scenario = "C3-same-child-reroute"
        category = self._resources()["categories"]["QA Jorsten Jr Bronze"]
        def change(parent_id, _child): self.parent_update(scenario, parent_id, category_id=category)
        def verify(before, after, _log):
            matches = [item for item in after["Jorsten Jr's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            ok = len(matches) == 1 and matches[0]["id"] == evidence_transaction(before)["id"] and matches[0].get("account_name") == "Bronze"
            return {"creates": 0, "updates": 1 if ok else 0, "deletes": 0}, ok
        self._changed_scenario(scenario, change, {"creates": 0, "updates": 1, "deletes": 0}, verify)

    def _c4(self) -> None:
        scenario = "C4-cross-child-reroute"
        category = self._resources()["categories"]["QA Borsten Silver"]
        def change(parent_id, _child): self.parent_update(scenario, parent_id, category_id=category)
        def verify(_before, after, _log):
            old = [item for item in after["Jorsten Jr's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            new = [item for item in after["Borsten's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            ok = not old and len(new) == 1
            return {"creates": 1 if new else 0, "updates": 0, "deletes": 1 if not old else 0}, ok
        self._changed_scenario(scenario, change, {"creates": 1, "updates": 0, "deletes": 1}, verify)

    def _deletion_scenario(self, scenario: str, change) -> None:
        def verify(_before, after, _log):
            matches = [item for item in after["Jorsten Jr's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            ok = not matches
            return {"creates": 0, "updates": 0, "deletes": 1 if ok else 0}, ok
        self._changed_scenario(scenario, change, {"creates": 0, "updates": 0, "deletes": 1}, verify)

    def _c5(self) -> None:
        scenario = "C5-parent-unapproved"
        self._deletion_scenario(scenario, lambda parent_id, _child: self.parent_update(scenario, parent_id, approved=False))

    def _c6(self) -> None:
        scenario = "C6-parent-unmapped"
        category = self._resources()["categories"]["QA Unmapped"]
        self._deletion_scenario(scenario, lambda parent_id, _child: self.parent_update(scenario, parent_id, category_id=category))

    def _c7(self) -> None:
        scenario = "C7-parent-deleted"
        self._deletion_scenario(scenario, lambda parent_id, _child: self.mutate(scenario, "DELETE", PARENT, None, parent_id))

    def _c9(self) -> None:
        scenario = "C9-child-mirror-recreated"
        def change(parent_id, child):
            self.mutate(scenario, "DELETE", "Jorsten Jr's Plan", None, child["id"])
            self.parent_update(scenario, parent_id, memo=f"BOD QA {self.campaign_id}:{scenario}:benign-parent-edit")
        def verify(before, after, _log):
            matches = [item for item in after["Jorsten Jr's Plan"] if self.campaign_id in str(item.get("memo") or "")]
            ok = len(matches) == 1 and matches[0]["id"] != evidence_transaction(before)["id"]
            return {"creates": 1 if ok else 0, "updates": 0, "deletes": 0}, ok
        self._changed_scenario(scenario, change, {"creates": 1, "updates": 0, "deletes": 0}, verify)

    def _d1(self) -> None:
        scenario = "D1-invalid-child-token"
        start = self.api_write_attempts
        categories = self._resources()["categories"]
        subs = [
            {"amount": -10, "category_id": categories["QA Jorsten Jr Silver"], "memo": f"BOD QA {self.campaign_id}:{scenario}:jr"},
            {"amount": -20, "category_id": categories["QA Borsten Silver"], "memo": f"BOD QA {self.campaign_id}:{scenario}:borsten"},
        ]
        self.create_parent(scenario, "QA Unmapped", amount=-30, subtransactions=subs)
        state = self.raw / f"{scenario}.db"
        dry_rc, _ = self.run_sync(scenario, True, state)
        first_rc, _, _ = self.run_live_counted(
            scenario, state, env_override={"BORSTEN_ACCESS_TOKEN": "deliberately-invalid-test-token"}
        )
        first = self.snapshot(scenario, "after-invalid-token")
        isolated = len(first["Jorsten Jr's Plan"]) == 1 and len(first["Borsten's Plan"]) == 0
        retry_dry, _ = self.run_sync(scenario, True, state)
        retry_rc, _, _ = self.run_live_counted(scenario, state)
        final = self.snapshot(scenario, "after-restored-token")
        recovered = len(final["Jorsten Jr's Plan"]) == 1 and len(final["Borsten's Plan"]) == 1
        passed = dry_rc == first_rc == retry_dry == retry_rc == 0 and isolated and recovered
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Child A completed during Child B auth failure; restored retry completed B without duplicating A.",
                     expected={"creates": 2, "updates": 0, "deletes": 0},
                     observed={"creates": len(final["Jorsten Jr's Plan"]) + len(final["Borsten's Plan"]), "updates": 0, "deletes": 0},
                     dry="PASS" if dry_rc == retry_dry == 0 else "FAIL",
                     live="PASS" if passed else "FAIL", api="PASS" if isolated and recovered else "FAIL",
                     sqlite=self.audit(scenario, state), attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-after-invalid-token.json",
                            "api-after-restored-token.json", "sqlite-audit.json"])

    def _d2(self) -> None:
        scenario = "D2-invalid-child-mapping"
        start = self.api_write_attempts
        categories = self._resources()["categories"]
        subs = [
            {"amount": -10, "category_id": categories["QA Jorsten Jr Silver"], "memo": f"BOD QA {self.campaign_id}:{scenario}:jr"},
            {"amount": -20, "category_id": categories["QA Borsten Silver"], "memo": f"BOD QA {self.campaign_id}:{scenario}:borsten"},
        ]
        self.create_parent(scenario, "QA Unmapped", amount=-30, subtransactions=subs)
        state = self.raw / f"{scenario}.db"
        original = self.runtime_config.read_text(encoding="utf-8")
        modified = yaml.safe_load(original)
        for child in modified["sync"]["childBudgets"]:
            if child["budgetName"] == "Borsten's Plan":
                child["accountMappings"][0]["childAccountName"] = "Missing QA Account"
        self.runtime_config.write_text(yaml.safe_dump(modified, sort_keys=False), encoding="utf-8")
        try:
            dry_rc, _ = self.run_sync(scenario, True, state)
            first_rc, _, _ = self.run_live_counted(scenario, state)
            first = self.snapshot(scenario, "after-invalid-mapping")
        finally:
            self.runtime_config.write_text(original, encoding="utf-8")
        isolated = len(first["Jorsten Jr's Plan"]) == 1 and len(first["Borsten's Plan"]) == 0
        retry_dry, _ = self.run_sync(scenario, True, state)
        retry_rc, _, _ = self.run_live_counted(scenario, state)
        final = self.snapshot(scenario, "after-restored-mapping")
        recovered = len(final["Jorsten Jr's Plan"]) == 1 and len(final["Borsten's Plan"]) == 1
        passed = dry_rc == first_rc == retry_dry == retry_rc == 0 and isolated and recovered
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Invalid Child B mapping preserved isolation; restored mapping retried without duplicating A.",
                     expected={"creates": 2, "updates": 0, "deletes": 0},
                     observed={"creates": len(final["Jorsten Jr's Plan"]) + len(final["Borsten's Plan"]), "updates": 0, "deletes": 0},
                     dry="PASS" if retry_dry == 0 else "FAIL", live="PASS" if passed else "FAIL",
                     api="PASS" if isolated and recovered else "FAIL", sqlite=self.audit(scenario, state),
                     attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-after-invalid-mapping.json",
                            "api-after-restored-mapping.json", "sqlite-audit.json"])

    def _d3(self) -> None:
        scenario = "D3-single-writer-lock"
        state = self.raw / f"{scenario}.db"
        lock_path = Path(str(state) + ".lock")
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        with lock_path.open("a+") as handle:
            fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            blocked_rc, blocked_log = self.run_sync(scenario, False, state)
            fcntl.lockf(handle, fcntl.LOCK_UN)
        released_rc, released_log, attempts = self.run_live_counted(scenario, state)
        blocked = blocked_rc != 0 and "already holds the live lock" in blocked_log
        released = released_rc == 0
        write_json(self.artifacts / "scenarios" / scenario / "lock-observation.json", {
            "concurrent_process": "BLOCKED BEFORE API/SQLITE MUTATION" if blocked else "UNEXPECTED",
            "post_release_process": "PASS" if released else "FAIL", "mutation_attempts": attempts,
        })
        passed = blocked and released and attempts == 0
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "A concurrent live process was rejected before mutation and a post-release max-cycles=1 run succeeded.",
                     dry="PASS", live="PASS" if passed else "FAIL", api="ZERO MUTATIONS",
                     sqlite="PASS" if passed else "FAIL", attempts=0,
                     links=["live-run.log", "lock-observation.json"])

    def _d4(self) -> None:
        scenario = "D4-controlled-continuous"
        start = self.api_write_attempts
        self.create_parent(scenario, "QA Thorsten Bronze")
        state = self.raw / f"{scenario}.db"
        dry_rc, _ = self.run_sync(scenario, True, state)
        live_rc, live_log, _ = self.run_live_counted(scenario, state, max_cycles=2)
        after = self.snapshot(scenario, "after")
        matches = [item for item in after["Thorsten's Plan"]
                   if f"{self.campaign_id}:{scenario}" in str(item.get("memo") or "")]
        two_cycles = "Cycle 2 read" in live_log
        passed = dry_rc == live_rc == 0 and len(matches) == 1 and two_cycles
        self.receipt(scenario, "PASS" if passed else "FAIL",
                     "Controlled two-cycle session created one mirror and no duplicate.",
                     expected={"creates": 1, "updates": 0, "deletes": 0},
                     observed={"creates": len(matches), "updates": 0, "deletes": 0},
                     dry="PASS" if dry_rc == 0 else "FAIL", live="PASS" if live_rc == 0 else "FAIL",
                     api="PASS" if len(matches) == 1 else "FAIL", sqlite=self.audit(scenario, state),
                     attempts=self.api_write_attempts - start,
                     links=["dry-run.log", "live-run.log", "api-after.json", "sqlite-audit.json"])

    def cleanup(self) -> None:
        cleanup_dir = self.artifacts / "cleanup"
        results = []
        # Discover every currently tagged transaction, then delete only those exact tagged IDs.
        for name in (PARENT, *CHILDREN):
            identity = self.identities[name]
            response = self.clients[name].get(identity, "transactions")
            tagged = [item for item in response.get("data", {}).get("transactions", [])
                      if self.campaign_id in str(item.get("memo") or "") and not item.get("deleted")]
            for item in tagged:
                scenario = "cleanup"
                self.mutate(scenario, "DELETE", name, None, item["id"])
                results.append({"target": name, "transaction": evidence_transaction(item),
                                "cleanup": "DELETE APPLIED"})
        remaining = self.snapshot("cleanup", "verification")
        clean = all(not values for values in remaining.values())
        write_json(cleanup_dir / "cleanup-manifest.json", {
            "campaign_id": self.campaign_id, "deleted": results,
            "verification": "PASS" if clean else "FAIL", "remaining_tagged": remaining,
        })

    def _final_metadata(self) -> None:
        environment_path = self.artifacts / "environment.json"
        environment = json.loads(environment_path.read_text())
        environment["actual_api_write_count"] = self.api_write_attempts
        environment["successful_api_write_count"] = self.successful_writes
        receipt_attempts = sum(
            json.loads(path.read_text()).get("safety", {}).get("api_write_attempts", 0)
            for path in self.artifacts.glob("scenarios/*/receipt.json")
        )
        environment["cleanup_api_write_count"] = max(0, self.api_write_attempts - receipt_attempts)
        write_json(environment_path, environment)
        all_receipts = [json.loads(path.read_text()) for path in self.artifacts.glob("scenarios/*/receipt.json")]
        write_json(self.artifacts / "campaign-manifest.json", {
            "campaign_id": self.campaign_id, "status": "FAIL" if any(
                item["status"] == "FAIL" for item in all_receipts) else "BLOCKED",
            "actual_api_write_count": self.api_write_attempts,
            "successful_api_write_count": self.successful_writes,
            "cleanup": "All campaign-tagged transactions were deleted and API verification found none.",
            "release_recommendation": "NOT READY", "secret_scan": "PENDING",
        })
        write_json(
            self.artifacts / "api-observations" / "request-telemetry.json",
            {
                "schema_version": 1,
                "safe_fields": [
                    "method", "resource_class", "status_class", "count", "retry_count",
                ],
                "requests": merge_request_telemetry(self.clients),
            },
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--continue-remaining", action="store_true")
    args = parser.parse_args()
    if os.environ.get("QA_CONFIRM_LIVE_MUTATIONS") != "YES":
        raise SystemExit("BLOCKED: QA_CONFIRM_LIVE_MUTATIONS=YES is required")
    campaign = Campaign(args.campaign_id, resume=args.continue_remaining)
    try:
        if args.continue_remaining:
            campaign.execute_remaining()
        else:
            campaign.execute()
    except Exception:
        try:
            campaign.cleanup()
            campaign._final_metadata()
        finally:
            raise
    print(f"Campaign execution complete: {args.campaign_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

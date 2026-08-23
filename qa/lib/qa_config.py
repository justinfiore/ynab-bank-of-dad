"""Env-only QA tokens and in-memory qa-sync.yaml plan-ID expansion."""

from __future__ import annotations

import os
import re
from collections.abc import Mapping, MutableMapping
from pathlib import Path

import yaml

from .ynab_qa_client import PlanIdentity

TOKEN_NAMES = (
    "PARENT_ACCESS_TOKEN",
    "JORSTEN_JR_ACCESS_TOKEN",
    "BORSTEN_ACCESS_TOKEN",
    "THORSTEN_ACCESS_TOKEN",
)
PLAN_ID_ENV = {
    "Jorsten's Plan": "QA_PARENT_PLAN_ID",
    "Jorsten Jr's Plan": "QA_JORSTEN_JR_PLAN_ID",
    "Borsten's Plan": "QA_BORSTEN_PLAN_ID",
    "Thorsten's Plan": "QA_THORSTEN_PLAN_ID",
}

_PLACEHOLDER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")


class QaConfigBlocked(RuntimeError):
    """Raised before writes when tokens or plan IDs cannot be resolved."""


def load_tokens(
    environ: MutableMapping[str, str] | None = None,
    token_file: Path | None = None,
) -> dict[str, str]:
    env: MutableMapping[str, str] = os.environ if environ is None else environ
    if token_file is not None and token_file.is_file():
        for raw in token_file.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            if name in TOKEN_NAMES and value.strip() and not str(env.get(name) or "").strip():
                env[name] = value.strip()
    missing = [name for name in TOKEN_NAMES if not str(env.get(name) or "").strip()]
    if missing:
        raise QaConfigBlocked("required QA token environment values are missing")
    return {name: str(env[name]) for name in TOKEN_NAMES}


def expand_env_value(value: str, environ: Mapping[str, str]) -> str:
    def replace(match: re.Match[str]) -> str:
        name = match.group(1) or match.group(2)
        resolved = environ.get(name)
        if not resolved:
            raise QaConfigBlocked(f"unresolved config placeholder {name}")
        return resolved

    return _PLACEHOLDER.sub(replace, value)


def load_budget_identities(
    config_path: Path,
    environ: Mapping[str, str] | None = None,
) -> dict[str, PlanIdentity]:
    env: Mapping[str, str] = os.environ if environ is None else environ
    raw = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    budgets = raw["budgets"]
    identities: dict[str, PlanIdentity] = {}
    for item in [budgets["parent"], *budgets["children"]]:
        name = item["displayName"]
        full_id = expand_env_value(str(item["fullId"]), env)
        if full_id.startswith("REPLACE_WITH_") or "${" in full_id:
            raise QaConfigBlocked(f"plan fullId for {name} is not a resolved UUID")
        identities[name] = PlanIdentity(name, full_id)
    return identities

## Why

YNAB Bank of Dad currently teaches saving by writing allowance, interest, and parent-budget transactions into a single parent budget, but it does not help kids practice budgeting inside their own YNAB budgets. Parent-budget purchases and category-to-category money movements that affect a child’s bank categories must therefore be mirrored manually, which is slow, error-prone, and especially risky because YNAB money movements disappear from the API after about a month.

This repo now needs a second, separable capability: a continuously running parent/child budget syncer that watches the parent budget, detects approved child-relevant activity, and mirrors it into child budgets without changing the existing allowance workflow.

## What Changes

- Add a separate budget-sync entry point named **YNAB Parent Child Budget Syncer**, with dedicated Bash and Windows batch wrappers, so operators can run syncing independently from allowance recording.
- Keep the existing allowance recording entry point and runtime behavior isolated; allowance runs SHALL continue to post only to the parent budget and SHALL NOT directly create transactions in child budgets.
- Add a continuously running sync loop with a configurable polling interval, sync-specific dry-run support, and proper file-based logging with configurable log levels and rolling log files.
- Add support for configuring the parent budget and one or more child budgets with per-budget access-token environment variable names rather than embedding tokens in YAML.
- Add a sync workflow that reads approved parent-budget transactions and recent parent-budget money movements, including split transactions via `subtransactions`, and creates corresponding child-budget transactions using explicit mapping rules.
- Add durable sync state backed by SQLite so reruns and long-lived polling do not duplicate child-budget transactions, and so transaction IDs / subtransaction IDs / money movement IDs can be correlated to created child-budget transactions by source and target budget.
- Extend design/docs/tests so the implementation plan includes the exact YNAB API surfaces, proposed SQLite schema, dry-run behavior, and operational rollout guidance.

## Capabilities

### New Capabilities
- `parent-child-budget-syncing`: Run a standalone, continuously polling parent/child sync process that mirrors qualifying parent-budget transactions and money movements into child budgets using explicit config-driven mappings and durable idempotent state.
- `child-budget-sync-configuration`: Describe the runtime configuration contract for the standalone syncer, including per-budget token environment variable names, polling, logging, mappings, and state-database settings.

### Modified Capabilities
- `modular-allowance-processing`: Preserve the current allowance CLI untouched in behavior while allowing the repo to ship an additional sync-specific entry point and wrappers.
- `automated-test-coverage`: Extend verification expectations so parent/child sync logic is covered by automated tests, including split-transaction handling, money-movement sync, dry-run logging, replay protection, and SQLite-backed state behavior.

## Impact

- New sync-specific runtime/CLI entry point(s), wrapper scripts, and sync-focused collaborators/models under `src/main/groovy/`
- Runtime config loading in `src/main/groovy/RuntimeConfig.groovy` and example docs/config files such as `config.yaml.example`, `README.md`, and `QUICK_START.md`
- YNAB repository/API access layers for parent transaction reads, parent money-movement reads, child account resolution, and child transaction posting
- SQLite-backed sync-state persistence and associated schema/documentation
- Automated tests under `src/test/groovy/` plus OpenSpec artifacts under `openspec/changes/support-parent-child-budget-syncing/`

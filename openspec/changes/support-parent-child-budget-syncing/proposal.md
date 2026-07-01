## Why

YNAB Bank of Dad currently teaches saving by writing allowance, interest, and parent-budget transactions into a single parent budget, but it does not help kids practice budgeting inside their own YNAB budgets. Parent-budget purchases and category-to-category money movements that affect a child’s bank categories must therefore be mirrored manually, which is slow, error-prone, and especially risky because YNAB money movements disappear from the API after about a month.

## What Changes

- Add support for configuring one or more child-budget sync targets, including a child budget name, its access token source, and the mapping between that child’s parent-budget bank categories and the YNAB account/category structures used in the child budget.
- Add a sync workflow that reads approved, categorized parent-budget transactions and creates corresponding child-budget transactions in the correct child-budget YNAB account when those parent transactions affect the child’s configured bank categories.
- Add a sync workflow that reads recent parent-budget money movements involving child bank categories and materializes equivalent child-budget transactions, including cases where one movement affects two different children.
- Add durable sync safety controls such as per-source idempotency keys, configurable lookback windows, and persisted sync state so reruns do not duplicate child-budget transactions and money movements are captured before the YNAB API stops returning them.
- Extend dry-run/logging/documentation/test coverage so sync behavior can be inspected safely before posting transactions to parent or child budgets.

## Capabilities

### New Capabilities
- `parent-child-budget-syncing`: Mirror qualifying parent-budget transactions and money movements into one or more child budgets using explicit config-driven mappings and idempotent sync behavior.
- `child-budget-sync-configuration`: Describe the runtime configuration contract for child budget credentials, budget/account/category mappings, sync windows, and state-file paths.

### Modified Capabilities
- `modular-allowance-processing`: Expand the CLI/runtime contract so the application can orchestrate both the existing allowance posting flow and the new parent/child sync flow without breaking dry-run safeguards or the existing config-driven startup behavior.
- `automated-test-coverage`: Extend verification expectations so parent/child sync logic is covered by automated tests, including transaction-sync, money-movement-sync, and duplicate-prevention scenarios.

## Impact

- Runtime config loading in `src/main/groovy/RuntimeConfig.groovy` and example docs/config files such as `config.yaml.example`, `README.md`, and `QUICK_START.md`
- Parent/child YNAB data access and orchestration in `src/main/groovy/RecordAllowance.groovy`, `src/main/groovy/YnabBudgetRepository.groovy`, and new sync-focused collaborators/models under `src/main/groovy/`
- Persisted sync-state handling for money-movement retention and idempotency tracking
- Automated tests under `src/test/groovy/` plus OpenSpec artifacts under `openspec/changes/support-parent-child-budget-syncing/`

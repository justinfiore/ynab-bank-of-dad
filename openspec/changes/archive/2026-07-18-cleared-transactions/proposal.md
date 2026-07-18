## Why

When the parent-child budget sync creates transactions in child budgets, those transactions represent real bank activity (allowances, interest, transfers from parent). They should be created as "cleared" so YNAB can auto-match them against any manually-entered cleared transactions the child may have recorded. Additionally, to make the auto-created transactions easy to identify and search/filter within each child's budget, a configurable memo prefix or suffix (defaulting to "YBOD: ") should be applied per child budget.

## What Changes

- Add optional `memoPrefix` (default "YBOD: ") and `memoSuffix` fields to each `sync.childBudgets[]` entry in config.
- The prefix/suffix is applied to the memo value copied from the parent transaction (or generated for money movements) before the child transaction is created.
- All transactions created in child budgets via the sync now include `cleared: "cleared"` in the YNAB transaction payload.
- Configuration validation accepts the new optional fields; no breaking changes to existing configs.
- Planner, payload factory, state, tests, and documentation updated to support and exercise the new behavior.
- No changes to parent budget transactions or non-sync allowance recording.

## Capabilities

### New Capabilities
- `cleared-child-transactions`: Per-child-budget configuration for memo prefix/suffix tagging and forcing the cleared status on all created child transactions.

### Modified Capabilities

## Impact

Likely touched areas:
- `src/main/groovy/ynabbankofdad/config/RuntimeConfig.groovy` (ChildBudgetSyncTarget model + validation)
- `src/main/groovy/ynabbankofdad/sync/ChildTransactionPayloadFactory.groovy` (add cleared + memo transformation)
- `src/main/groovy/ynabbankofdad/sync/ChildSyncPlanner.groovy` (memo construction site)
- `src/main/groovy/ynabbankofdad/sync/ChildSyncApplier.groovy` and related models/state
- Unit and integration tests under `src/test/groovy/*Sync*Spec.groovy` and `RuntimeConfigSpec.groovy`
- `config.yaml.example`, `CONFIGURATION.md`, `QUICK_START.md`, `PARENT_CHILD_SYNC_MANUAL_TESTING.md`
- Possibly `README.md` examples

The change touches the YNAB transaction creation path for child budgets only. Per repo rules, YNAB API request shape for the `cleared` field will be confirmed against official docs during implementation; no assumptions are made here.

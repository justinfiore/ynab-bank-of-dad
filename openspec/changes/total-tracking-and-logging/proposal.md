## Why

Cycle completion already logs per-child created/updated/deleted counts, but that does not tell an operator whether child-account money still matches the parent bank categories those accounts are supposed to mirror. Before turning live sync on for real, and when hunting future drift, we need cycle-end totals and a parent-category vs child-account comparison that is visible in the logs without treating a mismatch as a sync failure.

## What Changes

- Keep the existing per-child `created` / `updated` / `deleted` cycle stats.
- Add per-child-budget, per-child-account accumulated milliunit change for the cycle's planned CREATE / UPDATE / DELETE intents.
- Cache parent category → child account at the moment transaction propagation already resolves it, then invert that cache for comparison so the report cannot disagree with the account the cycle actually used.
- In `--dry-run`, log the projected post-cycle balance of each mapped child account (`current YNAB account balance + accumulated change`) and compare it to the related parent category balance(s).
- In live mode, log the actual child-account `balance` from the YNAB accounts API after apply and compare it to the related parent category balance(s).
- Log every difference for both modes. A mismatch is **not** a run failure, does **not** change planned or applied mutations, and does **not** write extra YNAB transactions.
- No config keys, token env vars, or YNAB naming assumptions change. Parent and child budgets still resolve from existing `sync.parentBudget` / `sync.childBudgets` names and `tokenEnvVarName`.

## Capabilities

### New Capabilities

- `sync-cycle-balance-reporting`: Cycle-end accumulated change by child budget and child account, reverse mapping from the cycle's propagation cache, dry-run projected vs parent category comparison, live actual account vs parent category comparison, and mismatch logging that never fails the run.

### Modified Capabilities

- `automated-test-coverage`: Unit and WireMock coverage for net-change aggregation, cache-based reverse mapping (including multi-category-to-one-account), dry-run projected balances, live actual balances, and mismatch-is-not-failure logging.

## Impact

- `src/main/groovy/ynabbankofdad/sync/ParentChildBudgetSyncer.groovy` (`logCycleCompletion` and cycle-end reporting)
- `src/main/groovy/ynabbankofdad/sync/reconcile/DesiredMirrorFactory.groovy` (write the cycle mapping cache when a desired mirror is resolved)
- `src/main/groovy/ynabbankofdad/ynab/YnabBudgetRepository.groovy` (preserve account `balance` from `GET /v1/plans/{plan}/accounts`)
- `src/main/groovy/ynabbankofdad/model/TransactionModels.groovy` (`AccountSnapshot`)
- New focused reporter/collaborator under `src/main/groovy/ynabbankofdad/sync/` if cycle-summary math should stay out of the syncer
- Tests: `ParentChildBudgetSyncerWireMockSpec.groovy`, `YnabBudgetRepositorySpec.groovy`, plus a focused unit spec for the reporter
- Operator-visible log text in `ARCHITECTURE.md` / `PARENT_CHILD_SYNC_MANUAL_TESTING.md` if those documents list cycle-summary lines
- No new YNAB write endpoints, no SQLite schema change, no `YNAB_ACCESS_TOKEN` logging

## Why

The parent/child sync currently treats mirrored transactions as create-once events: some parent edits are silently ignored, amount or category edits can create duplicate child transactions, and parent deletion tombstones are discarded. The syncer needs explicit authoritative reconciliation semantics before it can safely keep child budgets aligned with corrected parent activity.

## What Changes

- Track parent transactions and subtransactions by stable source identity rather than mutable amount, category, date, or memo values.
- Read and persist changed, deleted, unapproved, and newly unmapped parent transaction states from YNAB delta responses.
- Reconcile each changed source against its currently recorded child mirror using durable create, update, and delete operations.
- Update date, amount, payee, approval, cleared state, and same-budget account routing in place while preserving the child memo after initial creation.
- Delete child mirrors when the parent source is deleted, becomes unapproved, becomes unmapped, or removes a previously mirrored split component.
- Delete and recreate mirrors that move to another child budget; recreate a recorded mirror when the child transaction is missing during reconciliation.
- Treat mapping configuration changes as prospective only; they do not independently trigger historical rewrites.
- Add transactional SQLite schema migrations and backfill existing successful mirror correlations. When legacy state identifies duplicate successful mirrors, retain the newest and queue deletion of older child transactions.
- Extend dry-run output and automated coverage for edits, deletions, split transitions, retries, migration, and multi-cycle reconciliation.

## Capabilities

### New Capabilities
- `parent-transaction-reconciliation`: Defines stable source identity, authoritative parent edit and deletion behavior, child mirror lifecycle, durable mutation retries, migration, and cursor safety.

### Modified Capabilities

None. The parent/child sync requirements have not yet been promoted into `openspec/specs/`; this change introduces reconciliation as a focused new capability rather than modifying unrelated main specifications.

## Impact

- Sync models, planning, orchestration, payload construction, and child application under `src/main/groovy/ynabbankofdad/sync/`.
- YNAB transaction delta parsing and child transaction get/update/delete operations in `YnabBudgetRepository` and `YnabHttpClient`.
- SQLite schema and migration behavior in `src/main/groovy/ynabbankofdad/sync/state/SyncStateStore.groovy`.
- Unit, real-SQLite, and WireMock integration specifications, including process-like multi-cycle cases.
- Sync configuration validation and documentation where destructive reconciliation and dry-run-first rollout must be explained.
- No new access tokens, environment variables, or YNAB naming conventions are introduced. Existing per-budget token variables and configured mapping/account names remain authoritative.
- The change depends on the current cleared/reviewable child transaction behavior and should be implemented after the active `cleared-transactions` change is completed.

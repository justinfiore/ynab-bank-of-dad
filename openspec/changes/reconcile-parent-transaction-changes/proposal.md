## Why

The parent/child sync currently treats mirrored transactions as create-once events: some parent edits are silently ignored, amount or category edits can create duplicate child transactions, and parent deletion tombstones are discarded. The syncer needs explicit authoritative reconciliation semantics before it can safely keep child budgets aligned with corrected parent activity.

## What Changes

- Track parent transactions and subtransactions by stable source identity rather than mutable amount, category, date, or memo values.
- Read and persist changed, deleted, unapproved, and newly unmapped parent transaction states from YNAB delta responses.
- Reconcile each changed source against its currently recorded child mirror using durable create, update, and delete operations.
- Update date, amount, payee, approval, cleared state, and same-budget account routing in place while preserving the child memo after initial creation.
- Delete child mirrors when the parent source is deleted, becomes unapproved, becomes unmapped, or removes a previously mirrored split component.
- Delete and recreate mirrors that move to another child budget; recreate a recorded mirror when the child transaction is missing during reconciliation.
- Reconcile re-observed money movements when the same stable movement ID changes, while never interpreting absence from the API or the local lookback window as a deletion.
- Treat mapping configuration changes as prospective only; they do not independently trigger historical rewrites.
- Start reconciliation state only from a new empty SQLite file. Existing state databases from earlier builds must be deleted; nonempty unversioned databases and unsupported schema versions are rejected without mutation.
- Establish `schema_versions` baseline version 1 and an ordered transactional mechanism for future contiguous migrations, including rollback, repeated-initialization safety, and newer-version rejection.
- Add a dedicated human-readable reconciliation semantics guide, linked from `README.md` and `QUICK_START.md`, with examples and operational safety guidance.
- Require focused unit tests and integration tests for every documented reconciliation semantic, including money-movement changes and limitations.

## Capabilities

### New Capabilities
- `parent-transaction-reconciliation`: Defines stable source identity, authoritative parent edit and deletion behavior, conservative money-movement change handling, child mirror lifecycle, durable mutation retries, fresh-state schema versioning, cursor safety, and mandatory automated coverage.

### Modified Capabilities

None. The parent/child sync requirements have not yet been promoted into `openspec/specs/`; this change introduces reconciliation as a focused new capability rather than modifying unrelated main specifications.

## Impact

- Sync models, planning, orchestration, payload construction, and child application under `src/main/groovy/ynabbankofdad/sync/`.
- YNAB transaction delta parsing and child transaction get/update/delete operations in `YnabBudgetRepository` and `YnabHttpClient`.
- SQLite baseline schema and future migration mechanism in `src/main/groovy/ynabbankofdad/sync/state/SyncStateStore.groovy`.
- Unit, real-SQLite, and WireMock integration specifications, including process-like multi-cycle cases.
- A new `PARENT_TRANSACTION_RECONCILIATION.md` semantics guide referenced from `README.md` and `QUICK_START.md`.
- Sync configuration validation and documentation where destructive reconciliation and dry-run-first rollout must be explained.
- No new access tokens, environment variables, or YNAB naming conventions are introduced. Existing per-budget token variables and configured mapping/account names remain authoritative.
- The change depends on the current cleared/reviewable child transaction behavior and should be implemented after the active `cleared-transactions` change is completed.

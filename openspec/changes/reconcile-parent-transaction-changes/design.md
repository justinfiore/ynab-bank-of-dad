## Context

The syncer currently derives a create-only `ChildTransactionPlan` and uses an idempotency key containing mutable category and amount values. As a result, date and memo edits are skipped, amount and category edits can create additional child transactions, removed split components remain mirrored, and YNAB deletion tombstones are not represented. The SQLite schema records attempted plans and child creates but does not model a stable parent entity, its revisions, the current child mirror, or update/delete operations.

This change makes the parent budget authoritative for transaction existence, date, amount, payee, and routing. Child memos are initialized from the parent with the configured prefix/suffix, then remain child-owned. Child transactions remain cleared and are reset to unapproved whenever an automatic update occurs. The implementation must preserve independent child-token failure isolation, dry-run safety, restart-safe retries, and the Java 25 / Gradle 9 / Groovy 5 baseline.

The active `cleared-transactions` change overlaps child payload behavior and must be completed before implementation begins. Current YNAB API request and response contracts for transaction delta tombstones, lookup, update, and deletion must be checked against the official documentation before repository methods or fixtures are finalized.

## Goals / Non-Goals

**Goals:**

- Reconcile explicit parent transaction and subtransaction changes without creating duplicates.
- Apply authoritative date, amount, payee, approval, cleared-state, and routing changes while preserving child memo edits.
- Remove child mirrors when parent activity is deleted, unapproved, unmapped, or removed from a split.
- Support ordinary-to-split, split-to-ordinary, split-add, split-edit, and split-remove transitions.
- Persist source revisions, current child mirror correlations, and retryable mutation operations.
- Migrate populated legacy state without discarding audit history and clean up known duplicate child mirrors through the normal operation pipeline.
- Prevent cursor advancement until all derived remote operations have succeeded or reached an idempotent already-complete result.

**Non-Goals:**

- Bidirectional reconciliation of arbitrary edits originating in child budgets.
- Synchronizing parent memo edits after initial child transaction creation.
- Periodically scanning all child budgets for manual edits or deletions when no parent delta is received.
- Retroactively rerouting all history solely because mapping configuration changed.
- Reconciliation of money-movement edits or expiration behavior in this change.
- Automatically changing child categories, which remain unset.

## Decisions

### 1. Separate stable source identity from mutable revisions

A top-level source identity is `(sourceBudgetId, parentTransactionId)`. A split component identity is `(sourceBudgetId, parentTransactionId, parentSubtransactionId)`. Amount, category, category name, date, memo, payee, approval, deletion state, mapping, and target account are revision data and MUST NOT participate in source identity.

Each changed parent transaction is normalized and persisted as a source revision before reconciliation. A normalized revision hash avoids generating operations for repeated equivalent deltas while preserving append-only history when meaningful source state changes.

Alternative considered: repair the current idempotency key while retaining create-only planning. Rejected because stable duplicate prevention alone cannot represent deletion, split-set changes, rerouting, or retryable updates.

### 2. Reconcile the complete desired mirror set for each changed parent transaction

For every transaction returned in a delta, reconciliation derives the complete set of desired child mirrors from its current approval, deletion, category, split, and mapping state. Existing active mirrors linked to that parent transaction are compared with this desired set:

- desired only: create;
- existing and desired in the same child budget: update when authoritative fields or account routing differ;
- existing and desired in different child budgets: delete the old mirror, then create the new mirror;
- existing only: delete;
- equivalent existing and desired state: no-op.

When a parent transaction has subtransactions, the desired set is keyed by stable subtransaction ID. This allows removed components and ordinary/split transitions to be detected without inferring deletion from absence in unrelated API reads.

Alternative considered: emit ad hoc delete flags from the existing planner. Rejected because it would not provide a complete-set comparison or reliable transition behavior.

### 3. Parent authority is field-specific

Initial creation preserves the current memo-prefix/suffix behavior. Subsequent updates set the current parent date, amount, and payee; set the resolved child account when routing changes within the same budget; enforce `cleared: "cleared"`; reset `approved` to `false`; and omit memo from the update so child memo changes survive.

A parent memo-only delta is persisted but produces no child mutation. A category-name-only change that resolves to the same target is also a no-op. Parent category or mapping changes that resolve elsewhere trigger account update or cross-budget replacement.

Alternative considered: make every copied field authoritative. Rejected because the child memo is intentionally available for child-owned context after creation.

### 4. Parent de-qualification is destructive

Explicit parent deletion, approval withdrawal, mapped-to-unmapped changes, and removed split components all remove active child mirrors. The syncer does not create compensating financial transactions. A repeated child delete that returns the documented not-found response is treated as already complete.

Alternative considered: reverse or queue removals for review. Rejected because the selected policy makes the parent ledger authoritative and requires child mirrors to represent its current qualifying state.

### 5. Child disappearance is repaired when reconciliation observes the source

If an update targets a recorded child transaction that is no longer present, the operation transitions to create and stores the replacement child transaction ID. The syncer does not poll child transaction existence independently, so a manual child deletion is repaired only when a later parent delta causes that source to be reconciled.

Alternative considered: treat child deletion as a permanent override. Rejected because it contradicts authoritative parent semantics.

### 6. Use a durable operation journal

The state layer adds four concepts, either as new tables or equivalently constrained incremental tables:

- `source_entities`: stable parent identity and current lifecycle state;
- `source_revisions`: normalized append-only parent observations and server knowledge;
- `child_mirrors`: current source-to-child transaction correlation and last applied authoritative payload hash;
- `sync_operations`: ordered create/update/delete work with pending, applied, and failed outcomes.

Operations are persisted before remote mutation. Cross-budget rerouting uses an ordered delete followed by create; the create cannot apply until the delete is complete. Attempts remain auditable and retryable across process restarts.

Alternative considered: directly mutate YNAB and then update the existing mapping row. Rejected because a crash would lose intended work or make multi-step rerouting ambiguous.

### 7. Cursor advancement remains remote-application gated

The transaction server-knowledge cursor advances only after every operation derived from that delta batch is applied successfully or recognized as idempotently complete. Partial child failures preserve successful sibling results but block the shared parent transaction cursor, allowing retry with stable operation identities.

Initial bootstrap may use the configured transaction lookback date. Once a transaction server-knowledge cursor exists, delta reads omit `since_date` unless official YNAB documentation confirms that combining the filters cannot hide old-transaction tombstones or edits.

Alternative considered: advance after operations are durably queued. Rejected to preserve the existing conservative cursor contract and simplify recovery validation.

### 8. YNAB mutation contracts stay behind the repository boundary

`YnabHttpClient` gains only the HTTP verbs required by verified official endpoints. `YnabBudgetRepository` exposes focused child transaction lookup, update, and delete methods and maps explicit transaction/subtransaction `deleted` fields. Update payloads include only supported mutable fields; `import_id` is used only for creation and is not changed later.

New create import IDs are derived from stable source and target identity using a bounded deterministic hash that conforms to the documented YNAB limit. Existing child transactions retain their historical import IDs.

### 9. Schema migration is versioned and remote cleanup is deferred

SQLite initialization gains transactional schema versioning. Migration preserves existing tables and backfills stable entities/mirrors from successful historical rows with child transaction IDs. For multiple successful mirrors sharing one stable source and target, the newest is selected by `applied_at`, with the highest row ID as a deterministic tie-breaker. Older mirrors become pending delete operations; migration itself never calls YNAB.

Missing child IDs remain historical records but cannot become active mirrors. Failed-only legacy mappings remain retryable according to current source data rather than being assumed applied.

Alternative considered: delete duplicates during migration. Rejected because schema initialization must remain local, transactional, and safe during dry runs.

### 10. Configuration changes are prospective

The syncer does not scan historical source entities solely when configuration changes. If YNAB later returns a changed source transaction, that revision is reconciled using the then-current mapping configuration. This allows future observed activity to use current configuration without a one-time config edit destructively rewriting all history.

### 11. Dry-run computes but does not persist reconciliation

Dry-run reads existing mirrors and cursors, fetches any child state needed to describe an operation, and logs ordered create/update/delete actions. It does not persist revisions or operations, mutate cursors, run destructive legacy cleanup, or call child mutation endpoints.

## Risks / Trade-offs

- [Destructive parent authority can remove reviewed child records] → Document the policy prominently, reset edited mirrors to unapproved, require dry-run review before first live reconciliation, and retain operation audit history.
- [Crash after remote mutation but before local success recording] → Use stable operation identities, idempotent delete handling, child transaction IDs, and create import IDs; add recovery tests around each mutation type.
- [Legacy duplicates may represent intentional records] → Select deterministically, expose every planned cleanup in dry-run, and execute cleanup only through normal live operations.
- [Delta filter behavior could hide old edits or tombstones] → Verify the official contract and omit `since_date` after cursor establishment unless combined-filter safety is documented.
- [Cross-budget rerouting can temporarily remove a mirror before recreate succeeds] → Persist ordered operations, retry the create, block cursor advancement, and retain the old child transaction ID in audit history.
- [Child memo preservation requires partial updates] → Verify update semantics and omit memo rather than reading and rewriting it whenever the API permits.
- [State-model expansion increases brownfield complexity] → Add versioned migrations incrementally, retain existing audit tables, and cover populated migration fixtures with real SQLite.
- [Active OpenSpec overlap] → Complete `cleared-transactions` before applying this change and rebase the payload/update rules on its final behavior.

## Migration Plan

1. Complete and archive or synchronize prerequisite parent/child and cleared-transaction specifications.
2. Add transactional schema versioning and new reconciliation tables without deleting current tables.
3. Backfill stable source entities and current child mirrors from existing successful application rows.
4. Persist pending cleanup operations for older duplicate mirrors, but make no remote calls during migration.
5. Deploy the new binary and run at least one `--dry-run --max-cycles 1` against the migrated database.
6. Review planned legacy deletions, updates, and creates before enabling a live cycle.
7. Run one live cycle, verify operation outcomes and cursor movement, then resume continuous polling.

Rollback before a live reconciliation consists of restoring a database backup and the prior binary. After live updates or deletions, rollback cannot reconstruct remote child state automatically; operation history must be used for manual recovery.

## Open Questions

- Confirm the exact current YNAB endpoint paths, mutable update fields, transaction lookup response, deletion response, and not-found behavior from official documentation during implementation.
- Confirm whether transaction delta requests with both `since_date` and `last_knowledge_of_server` can omit tombstones for older transactions; default implementation omits `since_date` after cursor establishment unless verified safe.

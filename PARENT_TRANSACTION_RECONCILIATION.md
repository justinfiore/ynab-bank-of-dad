# Parent Transaction Reconciliation Semantics

> **Status: proposed behavior, not implemented yet.** This guide documents the behavior under review in the `reconcile-parent-transaction-changes` OpenSpec change. The current released syncer still creates mirrors once and does not yet reconcile every edit or deletion described here.

This guide explains what the parent/child syncer will do after a parent transaction or money movement changes. It is written for the person operating the syncer rather than for someone reading the implementation.

## Core principle

The parent budget is authoritative for whether a mirrored financial event exists and for its financial routing. The child budget remains authoritative for descriptive memo text after the initial mirror is created.

In practical terms:

- Parent date, amount, payee, approval, deletion state, and mapped child account determine the child transaction.
- The syncer creates child transactions as cleared and unapproved.
- A later automatic update resets the child transaction to unapproved so it can be reviewed again.
- The parent memo is copied and decorated only when the child transaction is first created.
- Later parent memo edits do not overwrite a memo edited in the child budget.
- Mapping configuration changes do not scan and rewrite history by themselves.

## How reconciliation works

```text
Changed parent activity
        |
        v
Build the desired child mirror set
        |
        v
Compare with recorded active mirrors
        |
        +-- missing mirror ----------> create
        +-- authoritative change ----> update
        +-- obsolete mirror ---------> delete
        +-- different child budget --> delete, then create
        +-- no meaningful change ----> no financial mutation
```

The syncer records durable operation intent before live child mutations. If one operation fails, completed operations stay completed and unfinished operations retry later.

## Ordinary transaction changes

| Parent-side event | Child-side result |
|---|---|
| New approved mapped transaction | Create one cleared, unapproved child transaction |
| Date changes | Update the existing child transaction |
| Amount changes | Update the existing child transaction; do not add a second full transaction |
| Payee changes | Update the existing child transaction |
| Memo changes only | Keep the current child memo; record the parent revision but make no financial update |
| Category changes but still maps to the same child account | Keep the same child transaction; update only if another authoritative field changed |
| Category reroutes to another account in the same child budget | Update the existing child transaction's account |
| Category reroutes to another child budget | Delete the old child transaction, then create its replacement in the new budget |
| Approved becomes unapproved | Delete the child mirror |
| Mapped becomes unmapped | Delete the child mirror |
| Parent transaction is deleted | Delete every child mirror derived from it |

### Example: amount correction

The parent has an approved `$12.00` purchase mapped to a child's Spend account. It was mirrored into the child budget. The parent corrects the amount to `$10.00`.

The syncer updates the existing child transaction from `$12.00` to `$10.00`, marks it cleared and unapproved, and preserves the child's current memo. It does not create a second `$10.00` transaction.

### Example: memo correction

The parent memo changes from `Shoes` to `Running shoes`. The child previously changed the mirrored memo to `Blue running shoes`.

The syncer keeps `Blue running shoes`. Parent memo changes are intentionally not authoritative after creation.

### Example: moved to another child

A parent transaction was accidentally categorized to Child One and later recategorized to Child Two.

The syncer first deletes the Child One mirror. Only after that succeeds does it create the Child Two mirror. If creation fails, the delete is not repeated; creation remains pending for retry.

## Approval, deletion, and destructive behavior

The following parent changes are destructive in the child budget:

- deleting a mirrored parent transaction;
- changing it from approved to unapproved;
- changing it from mapped to unmapped;
- removing a previously mirrored split component.

The syncer deletes the corresponding child transaction rather than adding a compensating reversal. This keeps the child budget aligned with the current qualifying parent ledger, but it means a reviewed child transaction can be removed later.

Always review a one-cycle dry run before first enabling this reconciliation behavior against live budgets.

## Split transactions

Each mapped split component is treated as a stable source with its own child mirror.

| Split change | Child-side result |
|---|---|
| Add a mapped component | Create only the new component's mirror |
| Change a component's date, amount, payee, or same-budget route | Update only that component's mirror |
| Remove or delete a component | Delete only that component's mirror |
| Ordinary transaction becomes split | Delete the ordinary mirror, then create qualifying split mirrors |
| Split becomes ordinary | Delete former split mirrors, then create the ordinary mirror |
| Unrelated component remains unchanged | Leave its child mirror unchanged |

The syncer will not infer that a split component was removed unless YNAB supplied the complete current split composition. If a delta response is partial, the syncer must fetch the complete transaction before deleting anything because of absence.

### Example: one split component removed

A `$20.00` parent transaction has two mapped components:

- `$8.00` for Child One Spend;
- `$12.00` for Child Two Spend.

The parent removes Child One's `$8.00` component but leaves Child Two's component unchanged. The syncer deletes only Child One's mirror. Child Two's mirror is not reposted or modified.

## Missing child transactions

When YNAB reports later parent activity for a qualifying source, the syncer verifies that its recorded child transaction still exists. If someone manually deleted the child transaction, the syncer recreates it from current authoritative parent state and records the new child transaction ID.

This check occurs even for a memo-only or otherwise no-op parent edit. The syncer does not continuously scan every child transaction, so a manual child deletion is discovered only when later parent activity causes that source to be observed again.

## Mapping configuration changes

Mapping configuration is prospective:

- Editing configuration alone does not scan and reroute historical mirrors.
- A later YNAB change to an existing source is reconciled using the mapping configuration active at that time.

This avoids a config typo immediately rewriting large amounts of history while still allowing later observed activity to use corrected mappings.

## Money movements

Money movements have less change information than transactions in the current YNAB API. YNAB provides a stable movement ID and movement fields, but it does not document a deletion tombstone, revision timestamp, individual movement lookup, or replacement lineage.

### What can be reconciled

The syncer can safely reconcile a movement when the same movement ID is observed again with changed fields.

| Re-observed same-ID change | Child-side result |
|---|---|
| Amount changes | Update affected existing child transactions |
| Source or destination category changes | Recompute both desired sides; update, create, or reroute as needed |
| Same side moves to another account in one child budget | Update in place |
| Side moves to another child budget | Delete old mirror, then create replacement |
| Movement date changes | Update affected mirrors |
| Group ID changes | Record correlation metadata; group ID does not define identity |

A movement identity is the parent budget plus movement ID. Each mirrored side additionally includes the target child and `inflow` or `outflow`, so two sides routed to one child cannot collide.

Previously mirrored movements remain eligible for same-ID correction even after their movement date falls outside the lookback used to decide whether a newly discovered movement should be created.

### What cannot be inferred safely

| Observation | Required behavior |
|---|---|
| Previously seen movement ID is absent from a later snapshot | Keep existing child mirrors and report the source as unconfirmed |
| Movement falls outside the configured lookback | Do not delete its child mirrors |
| Similar movement appears under a new ID | Treat it as a separate movement; do not assume it replaced the old ID |
| Group ID matches another movement | Treat the group as correlation only, not proof of replacement or revision |

The syncer will not automatically delete movement-derived child transactions based only on absence. That policy can change only if YNAB documents a reliable deletion or replacement signal.

Money-movement failures remain independently retryable and do not block the parent transaction cursor.

## Failure and retry behavior

| Failure point | Retry behavior |
|---|---|
| One child update fails | Successful siblings remain complete; only unfinished work retries |
| Delete succeeds but cross-budget create fails | Delete stays complete; create retries |
| Child transaction was already deleted | Documented already-absent result counts as successful deletion |
| Child transaction is missing during update | Create a replacement and activate its new ID |
| Process stops after remote success but before local completion | Recover using stable operation/import identity without creating a second financial effect |
| One movement operation fails | Movement work retries independently; transaction cursor can still advance |

Every remote attempt remains in operation history for troubleshooting.

## Cursor behavior

Transaction deltas are grouped into durable ingestion batches. The transaction cursor advances only after all operations derived from that batch complete successfully or are recognized as already complete.

An empty successful transaction delta can still advance server knowledge. Migration cleanup and money-movement operations are not part of a transaction delta batch and do not block the transaction cursor.

## Existing SQLite state and duplicate cleanup

The first implementation adds a versioned migration for existing sync databases.

Migration will:

1. preserve current audit tables and rows;
2. create stable source and active mirror records from successful live rows with child transaction IDs;
3. ignore failed, dry-run, and missing-child-ID rows as active mirrors while preserving their history;
4. keep the newest successful mirror when older mutable identities produced duplicates;
5. queue deletion of older duplicate child transactions through normal operation handling.

Schema migration itself never calls YNAB. A dry run projects migration and cleanup in memory without changing the SQLite file. Operators must review projected destructive cleanup before a live migrated run.

## Dry-run guarantees

Dry-run reports planned:

- creates;
- in-place updates;
- deletions;
- cross-budget replacements;
- missing-child recreation;
- split transitions;
- same-ID money-movement changes;
- legacy duplicate cleanup.

Dry-run performs no child mutation and no SQLite schema, row, operation, mirror, revision, or cursor write.

## Test coverage contract

Every normative semantic must have both:

- a focused unit test covering the responsible decision in isolation;
- an integration test covering simulated YNAB HTTP behavior and/or real SQLite persistence.

The implementation will maintain a scenario-level matrix naming both tests. The expected test homes are:

| Semantic area | Focused unit coverage | Integration coverage |
|---|---|---|
| Stable identities and source normalization | `SourceRevisionNormalizerSpec` | `SyncStateStoreIntegrationSpec` and sync WireMock scenarios |
| Desired-set planning and transitions | `ParentTransactionReconcilerSpec` | `ParentChildBudgetSyncerWireMockSpec` |
| Child mutation payloads and retries | `ReconciliationOperationApplierSpec` and payload specs | `YnabTransactionMutationWireMockSpec` |
| Migration and operation constraints | Migration/state helper specs | `ReconciliationMigrationIntegrationSpec` or `SyncStateStoreIntegrationSpec` |
| Cursor completion and process restarts | `SyncRunCoordinatorSpec` | Multi-cycle `ParentChildBudgetSyncerWireMockSpec` scenarios |
| Money-movement changes and limitations | Movement normalization/reconciler specs | Snapshot-based WireMock plus real-SQLite scenarios |
| Dry-run and destructive reporting | Reconciler/applier dry-run specs | WireMock dry-run with unchanged SQLite verification |

No live YNAB credentials are required for this coverage. `./gradlew testAll` executes both layers.

## Safe rollout checklist

Before enabling live reconciliation:

1. Back up the configured SQLite state database.
2. Read this guide and confirm the deletion and unapproval semantics match your expectations.
3. Run `--dry-run --max-cycles 1` using the exact production config and state path.
4. Review every planned update, deletion, reroute, recreation, and legacy cleanup.
5. Run one live cycle and inspect child budgets and operation history.
6. Enable continuous polling only after that verification succeeds.

After live updates or deletions, restoring an older binary cannot reconstruct remote child state automatically. Use operation history and your SQLite backup for manual recovery.

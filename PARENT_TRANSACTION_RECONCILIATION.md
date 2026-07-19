# Parent Transaction Reconciliation Semantics

This guide explains what the parent/child syncer does after a parent transaction or money movement changes. It is written for the person operating the syncer rather than for someone reading the implementation.

## Core principle

The parent budget is authoritative for whether a mirrored financial event exists and for its financial routing. The child budget remains authoritative for descriptive memo text after the initial mirror is created.

| Ownership | Fields and behavior |
|---|---|
| Parent-authoritative | Existence and eligibility (explicit deletion, approval, mapping, and split composition), date, amount, payee, and resolved child budget/account routing |
| Syncer-enforced | A created or automatically updated child mirror is cleared and unapproved |
| Child-owned after creation | Memo text; the configured prefix and suffix decorate the parent memo only when the mirror is created |

Later reconciliation does not copy a parent memo edit over the child memo. Child edits to parent-authoritative fields are not synchronized back to the parent and can be corrected the next time that source is observed. Mapping configuration changes do not scan and rewrite history by themselves.

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
| Category changes but still maps to the same child account | Keep the same child transaction; update only if another authoritative or syncer-enforced field differs |
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

Before the first live reconciliation, back up the configured SQLite database and review a `--dry-run --max-cycles 1` using the exact live configuration and state path. Migration can queue deletion of legacy duplicate child transactions, so this requirement also applies to an existing database that predates reconciliation.

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

The syncer does not infer that a split component was removed from a partial response. It fetches complete transaction detail before deleting a component because it is absent.

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

The syncer does not automatically delete movement-derived child transactions based only on absence. YNAB currently documents no reliable deletion or replacement signal for this data.

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

Every reconciliation operation attempt remains in operation history for troubleshooting.

## Cursor behavior

Transaction deltas are grouped into durable ingestion batches. The transaction cursor advances only after all operations derived from that batch complete successfully or are recognized as already complete.

An empty successful transaction delta can still advance server knowledge. Migration cleanup and money-movement operations are not part of a transaction delta batch and do not block the transaction cursor.

The configured transaction lookback is used for bootstrap. After a transaction cursor exists, delta requests omit the date filter so the syncer does not intentionally exclude older edits or deletion tombstones. Money movements are read as complete, unfiltered snapshots without an undocumented movement cursor; their ingestion and retries remain independent of the transaction cursor.

## Existing SQLite state and duplicate cleanup

The syncer applies a transactional, versioned migration to existing sync databases.

Migration:

1. preserve current audit tables and rows;
2. create stable source and active mirror records from successful live rows with child transaction IDs;
3. ignore failed, dry-run, and missing-child-ID rows as active mirrors while preserving their history;
4. keep the newest successful mirror when older mutable identities produced duplicates;
5. queue deletion of older duplicate child transactions through normal operation handling.

Schema migration itself never calls YNAB. It queues older duplicate IDs as pending delete operations; normal live operation handling later sends those destructive deletes. A dry run projects migration and cleanup in memory without changing the SQLite file. Back up the SQLite state and review that projection before a live migrated run.

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

## Operation history and manual recovery

The SQLite database retains the information needed to investigate a partial run and plan manual recovery:

| Table | Useful recovery information |
|---|---|
| `sync_runs` | Run start/completion, status, and error summary |
| `source_entities` | Stable parent transaction, subtransaction, or movement identity and current lifecycle |
| `source_revisions` | Append-only normalized parent observations, revision hashes, and server knowledge |
| `child_mirrors` | Every recorded child transaction ID, target budget/account, status, and lineage timestamps, including replaced, missing, superseded, and deleted mirrors |
| `ingestion_batches` | Transaction-delta or movement-snapshot server knowledge and completion status |
| `sync_operations` | Immutable ordered create/update/delete intent, target budget, child ID, payload, dependency, and current status |
| `operation_attempts` | Append-only attempt time, outcome, failure reason, and returned child transaction ID |

The older `source_events`, `sync_mappings`, and `applied_transactions` tables are preserved as legacy audit history. Inspect operation intent together with its attempts and mirror lineage before making a manual YNAB correction; do not assume a local failure means the remote mutation failed.

## Safe rollout checklist

Before enabling live reconciliation:

1. Stop the syncer and back up the configured SQLite state database, including any SQLite sidecar files or by using SQLite's backup facility.
2. Read this guide and confirm the deletion and unapproval semantics match your expectations.
3. Run `--dry-run --max-cycles 1` using the exact production config and state path.
4. Review every planned update, deletion, reroute, recreation, and legacy cleanup.
5. Run one live cycle and inspect child budgets and operation history.
6. Enable continuous polling only after that verification succeeds.

Before any live child mutation, rollback is the prior binary plus the SQLite backup. After a remote create, update, or delete succeeds, restoring an older binary or database cannot undo or reconstruct that remote child state automatically. Use `sync_operations`, `operation_attempts`, `child_mirrors`, and `source_revisions` to identify the financial effect and make any required manual YNAB correction.

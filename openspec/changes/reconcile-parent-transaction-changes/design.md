## Context

The reconciliation design tracks stable parent entities, their revisions, current child mirrors, and durable create/update/delete operations. Stable identity is required so date, amount, category, split-composition, and deletion changes reconcile one lineage rather than creating unrelated financial effects.

This change makes the parent budget authoritative for transaction existence, date, amount, payee, and routing. Child memos are initialized from the parent with the configured prefix/suffix, then remain child-owned. Child transactions remain cleared and are reset to unapproved whenever an automatic update occurs. The implementation must preserve independent child-token failure isolation, dry-run safety, restart-safe retries, and the Java 25 / Gradle 9 / Groovy 5 baseline.

The `cleared-transactions` change overlaps child payload behavior and must be completed before implementation begins. Current YNAB API request and response contracts for transaction delta tombstones, lookup, update, and deletion must be checked against the official documentation before repository methods or fixtures are finalized.

Money movements have a weaker official contract than transactions. The current YNAB API exposes a stable movement ID, optional group ID, movement fields, and response server knowledge, but no deletion tombstone, revision timestamp, individual lookup, or mutation endpoint. Official prose says money-movement endpoints support delta requests, while the current OpenAPI operation omits the request cursor parameter. This design therefore supports observed same-ID changes from complete snapshots and does not infer deletion from absence.

## Goals / Non-Goals

**Goals:**

- Reconcile explicit parent transaction and subtransaction changes without creating duplicates.
- Apply authoritative date, amount, payee, approval, cleared-state, and routing changes while preserving child memo edits.
- Remove child mirrors when parent activity is deleted, unapproved, unmapped, or removed from a split.
- Support ordinary-to-split, split-to-ordinary, split-add, split-edit, and split-remove transitions.
- Reconcile observed same-ID money-movement changes without inventing unsupported deletion semantics.
- Persist source revisions, current child mirror correlations, and retryable mutation operations.
- Initialize reconciliation state only in a new empty database and reject unsupported existing state without mutation.
- Preserve an ordered transactional schema-version mechanism for future upgrades.
- Prevent cursor advancement until all derived remote operations have succeeded or reached an idempotent already-complete result.
- Provide complete human-facing semantics documentation and require both unit and integration coverage for every semantic.

**Non-Goals:**

- Bidirectional reconciliation of arbitrary edits originating in child budgets.
- Synchronizing parent memo edits after initial child transaction creation.
- Periodically scanning all child budgets for manual edits or deletions when no parent delta is received.
- Retroactively rerouting all history solely because mapping configuration changed.
- Deleting child mirrors merely because a money movement is absent from a later API response or falls outside the configured local lookback.
- Assuming that a replacement movement ID, group ID, or `moved_at` value proves an edit or deletion when YNAB does not document that relationship.
- Automatically changing child categories, which remain unset.

## Decisions

### Verified YNAB API contracts (2026-07-18)

The implementation was checked against the official YNAB API documentation and canonical OpenAPI 1.86.0 schema at <https://api.ynab.com/>, <https://api.ynab.com/v1>, and <https://api.ynab.com/papi/open_api_spec.yaml>.

- `TransactionDetail.deleted` and `SubTransactionBase.deleted` are required booleans. Deleted transactions and subtransactions are included only in delta responses. The official contract does not guarantee that a changed split response contains the complete current subtransaction set, so absence cannot drive deletion without a complete transaction lookup.
- `GET /v1/plans/{plan_id}/transactions` accepts `since_date` and `last_knowledge_of_server` and returns required `transactions` and `server_knowledge` fields. Official documentation does not define how the two filters interact or guarantee that a date filter preserves older edits and tombstones. Since API 1.85.0, omitting `since_date` defaults transaction listings to one year ago; no cursor-specific exception or tombstone-retention period is documented. Established-cursor requests therefore omit the configured bootstrap lookback as required by this change, while this remaining upstream limitation is treated as an explicit operational risk.
- Child lookup uses `GET /v1/plans/{plan_id}/transactions/{transaction_id}` and returns `data.transaction` plus `data.server_knowledge`; missing lookup is documented as `404` with `ErrorResponse`.
- A focused child update uses `PUT` on the same path with `{ "transaction": { ... } }`. The supported request fields are `account_id`, `date`, `amount`, `payee_id`, `payee_name`, `category_id`, `memo`, `cleared`, `approved`, `flag_color`, and `subtransactions`; this implementation sends only the narrower authoritative fields needed for reconciliation and omits memo. The operation does not document missing-transaction `404` behavior, so a missing update is recognized through a preceding documented lookup rather than assuming an undocumented update response.
- Child deletion uses `DELETE` on the same path. Success is `200` with `TransactionResponse`; already absent is documented as `404` with `ErrorResponse` and is treated as idempotent completion by the application layer.
- Creation `import_id` has a maximum length of 36 characters. It is not accepted by the single-transaction update schema, and the bulk update documentation states that it can identify but cannot change an existing transaction. New imports therefore use bounded stable identity while existing import IDs remain unchanged.
- Plan-wide money-movement endpoints return `server_knowledge`, and official overview prose says they support delta requests, but OpenAPI 1.86.0 does not declare `last_knowledge_of_server` as a request parameter. Money movements expose no deletion, replacement, supersession, individual lookup, or retention guarantee. The implementation therefore uses complete unfiltered snapshots, sends no movement cursor, and never treats absence, lookback expiry, a similar new ID, or a group ID as destructive evidence.

### 1. Separate stable source identity from mutable revisions

A top-level source identity is `(sourceBudgetId, parentTransactionId)`. A split component identity is `(sourceBudgetId, parentTransactionId, parentSubtransactionId)`. Amount, category, category name, date, memo, payee, approval, deletion state, mapping, and target account are revision data and MUST NOT participate in source identity.

Each changed parent transaction is normalized and persisted as a source revision before reconciliation. A normalized revision hash avoids generating operations for repeated equivalent deltas while preserving append-only history when meaningful source state changes.

Alternative considered: rely only on stable duplicate prevention. Rejected because identity alone cannot represent deletion, split-set changes, rerouting, or retryable updates.

### 2. Reconcile the complete desired mirror set for each changed parent transaction

For every transaction returned in a delta, reconciliation derives the complete set of desired child mirrors from its current approval, deletion, category, split, and mapping state. Existing active mirrors linked to that parent transaction are compared with this desired set:

- desired only: create;
- existing and desired in the same child budget: update when authoritative fields or account routing differ;
- existing and desired in different child budgets: delete the old mirror, then create the new mirror;
- existing only: delete;
- equivalent existing and desired state: no-op.

When a parent transaction has subtransactions, the desired set is keyed by stable subtransaction ID. Before absence is interpreted as split-component removal, the implementation must verify that the delta contains the complete current split composition or fetch the full transaction by ID. This allows removed components and ordinary/split transitions to be detected without treating a partial delta as complete state.

Alternative considered: emit ad hoc delete flags from the existing planner. Rejected because it would not provide a complete-set comparison or reliable transition behavior.

### 3. Parent authority is field-specific

Initial creation preserves the current memo-prefix/suffix behavior. Subsequent updates set the current parent date, amount, and payee; set the resolved child account when routing changes within the same budget; enforce `cleared: "cleared"`; reset `approved` to `false`; and omit memo from the update so child memo changes survive.

A parent memo-only delta is persisted but produces no child mutation. A category-name-only change that resolves to the same target is also a no-op. Parent category or mapping changes that resolve elsewhere trigger account update or cross-budget replacement.

Alternative considered: make every copied field authoritative. Rejected because the child memo is intentionally available for child-owned context after creation.

### 4. Parent de-qualification is destructive

Explicit parent deletion, approval withdrawal, mapped-to-unmapped changes, and removed split components all remove active child mirrors. The syncer does not create compensating financial transactions. A repeated child delete that returns the documented not-found response is treated as already complete.

Alternative considered: reverse or queue removals for review. Rejected because the selected policy makes the parent ledger authoritative and requires child mirrors to represent its current qualifying state.

### 5. Child disappearance is repaired when reconciliation observes the source

Every observed qualifying source with an active mirror verifies that the recorded child transaction still exists before concluding reconciliation, including memo-only and otherwise no-op revisions. If it is absent, reconciliation creates a replacement from current authoritative source state and stores the replacement child transaction ID. The syncer does not poll child existence independently, so manual child deletion is repaired only after a later parent delta causes that source to be observed.

Alternative considered: treat child deletion as a permanent override. Rejected because it contradicts authoritative parent semantics.

### 6. Use a durable operation journal

The state layer adds six concepts, either as new tables or equivalently constrained incremental tables:

- `source_entities`: stable parent identity and current lifecycle state;
- `source_revisions`: normalized append-only parent observations and server knowledge;
- `child_mirrors`: current source-to-child transaction correlation and last applied authoritative payload hash;
- `ingestion_batches`: response server knowledge and completion state tying revisions and operations to cursor eligibility;
- `sync_operations`: immutable ordered create/update/delete intent with pending, applied, and retryable-failed status;
- `operation_attempts`: append-only remote attempt outcomes and failure details.

Operations are persisted before remote mutation. Every mixed transition, including cross-budget and ordinary/split replacement, deletes obsolete mirrors before creating replacements. A dependent create cannot apply until its deletes complete. Attempts remain auditable and retryable across process restarts, and failed operation intent returns to retryable processing without losing attempt history.

Alternative considered: directly mutate YNAB and then update the existing mapping row. Rejected because a crash would lose intended work or make multi-step rerouting ambiguous.

### 7. Live mode enforces a single writer with an OS file lock

Live `ParentChildBudgetSyncer.main` acquires an exclusive `FileChannel` lock on `<sqlitePath>.lock` before constructing the live syncer and entering the poll loop. A second live process that loses `tryLock` aborts immediately with an error that names the database and lock path. The lock is released in `finally` on normal exit; the OS releases it after crash or `kill -9`, so the next run is not permanently locked out. Dry-run never takes the lock. This is preferred over a SQLite `sync_runs` lease row because abrupt death must not require a heartbeat TTL or manual unlock.

### 8. Cursor advancement remains remote-application gated across all transaction batches

The transaction server-knowledge cursor advances only when every `transaction_delta` ingestion batch is complete: each batch must have no unfinished operations, and no transaction batch may remain `pending`. Completion is evaluated for the whole kind, not only the batch created for the current response. This closes the restart hole where a later delta from an unadvanced cursor could fully apply while an older retryable batch still blocked correctness. Partial child failures preserve successful sibling results but block the shared parent transaction cursor until every transaction batch is done. Money-movement snapshot batches use the same all-batches-of-kind rule independently and never gate the transaction cursor.

Initial bootstrap may use the configured transaction lookback date. Once a transaction server-knowledge cursor exists, delta reads omit `since_date` unless official YNAB documentation confirms that combining the filters cannot hide old-transaction tombstones or edits.

Alternative considered: advance after only the current response batch completes. Rejected because two distinct responses can be read from one unadvanced cursor while earlier operations remain retryable.

### 9. YNAB mutation contracts stay behind the repository boundary

`YnabHttpClient` gains only the HTTP verbs required by verified official endpoints. `YnabBudgetRepository` exposes focused child transaction lookup, update, and delete methods and maps explicit transaction/subtransaction `deleted` fields. Update payloads include only supported mutable fields; `import_id` is used only for creation and is not changed later.

New create import IDs are derived from stable source and target identity using a bounded deterministic hash that conforms to the documented YNAB limit. Existing child transactions retain their historical import IDs.

### 10. State starts fresh and schema evolution is versioned

The first supported reconciliation database is baseline schema version 1. Initialization of a missing or empty database creates exactly the nine current tables and records version 1 in `schema_versions`. Repeated initialization at the supported version is a no-op.

Existing databases from earlier builds are not upgraded. Operators must delete them and let the syncer create a fresh database. A nonempty database without `schema_versions`, a noncontiguous version history, or a schema newer than this binary supports is rejected before any schema or data mutation. Dry-run follows the same compatibility checks for an existing file, reads a supported database without changing its bytes, and creates no file when the configured database is absent.

Future schema changes use migrations numbered consecutively after version 1. Initialization validates a contiguous applied-version history, runs every pending migration in ascending order, and records each version in the same SQLite transaction. Any failure rolls back all schema changes and version rows from that initialization attempt. A binary must reject a database whose highest contiguous version is newer than `CURRENT_SCHEMA_VERSION` rather than attempting a downgrade.

Alternative considered: infer and upgrade an unversioned database. Rejected because its provenance and shape cannot be established safely enough to mutate it automatically.

### 11. Routing failure is per source and never looks like unmapping

Failed child plan/account resolution is attached to each transaction result as a set of blocked source components. Planning uses healthy child contexts, suppresses deletes for blocked sources or failed child targets, and retains intents for healthy components of the same split. Lifecycle writes skip only blocked component sources so an incomplete desired set is not persisted as `deleted`. Money movements retain whole-movement `routingBlocked` because their sides share one observed source. Transaction-level routing failure still blocks transaction batch completion and cursor advancement because the delta cannot be considered fully applied.

### 12. Configuration changes are prospective

The syncer does not scan historical source entities solely when configuration changes. If YNAB later returns a changed source transaction, that revision is reconciled using the then-current mapping configuration. This allows future observed activity to use current configuration without a one-time config edit destructively rewriting all history.

### 13. Dry-run computes but does not persist reconciliation

Dry-run reads mirrors and cursors from a supported versioned database, fetches any child state needed to describe an operation, and logs ordered create/update/delete actions. It does not persist revisions or operations, mutate cursors, create a missing database, or call child mutation endpoints. Unsupported existing state is rejected without mutation.

### 14. Money movements use stable observed-state reconciliation

A money movement entity is identified by `(sourceBudgetId, moneyMovementId)`. Each child mirror is identified by the movement entity, target child budget, and logical `inflow` or `outflow` side. Amount, category, category name, date, group ID, mapping, and target account are mutable revision/routing data rather than identity.

The syncer reads the complete unfiltered money-movement snapshot and persists normalized observations before applying the configured lookback eligibility rule for new mirrors. If the same movement ID is observed later with changed amount, date, categories, or routing, its existing sides are reconciled with the same update, same-budget reroute, and cross-budget replacement rules as transaction mirrors. Memo remains child-owned after initial creation.

An ID missing from a later snapshot, absent from a delta, or outside the local lookback is marked unconfirmed and logged; its child mirrors are not deleted. A newly observed replacement ID is a separate movement because the API does not document replacement lineage. Group ID is correlation metadata only and never identity or proof of replacement.

Money-movement reads and operation retries are independent of the transaction ingestion cursor. Because the current official OpenAPI and prose disagree about the movement delta request parameter, the initial implementation uses complete snapshots and does not invent a movement cursor. The implementation may adopt a cursor only after the official contract is clarified and covered by contract tests.

Alternative considered: infer movement deletion from absence in a complete response. Rejected because YNAB exposes no deletion tombstone or retention guarantee, making destructive cleanup unsafe.

### 15. Human semantics and tests are first-class deliverables

`PARENT_TRANSACTION_RECONCILIATION.md` explains the authoritative/child-owned boundary, ordinary and split transitions, money-movement limitations, retries, fresh-state requirement, schema versioning, dry-run behavior, single-writer lock, routing-failure safety, and rollout in user language. `README.md` and `QUICK_START.md` link to it.

Every normative scenario in the delta specification must map to at least one focused unit test and at least one integration test using WireMock and/or real SQLite. Unit tests prove normalization, planning, payload, state, and retry decisions in isolation; integration tests prove HTTP contracts, persistence, process restarts, operation ordering, and observable side effects. A maintained coverage matrix in the semantics guide or test documentation records both test names for each semantic.

## Risks / Trade-offs

- [Destructive parent authority can remove reviewed child records] → Document the policy prominently, reset edited mirrors to unapproved, require dry-run review before first live reconciliation, and retain operation audit history.
- [Deleting old state does not remove child transactions created by an earlier syncer] → Treat first-run creates as possible duplicate financial effects for previously deployed installations and require operators to remove or otherwise account for those transactions before live mode.
- [Crash after remote mutation but before local success recording] → Use stable operation identities, idempotent delete handling, child transaction IDs, and create import IDs; add recovery tests around each mutation type.
- [Deleting old local state removes replay history] → Require operators to stop the syncer, delete the unsupported database deliberately, and review a one-cycle dry run before the first live run.
- [Delta filter behavior could hide old edits or tombstones] → Verify the official contract and omit `since_date` after cursor establishment unless combined-filter safety is documented.
- [Cross-budget rerouting can temporarily remove a mirror before recreate succeeds] → Persist ordered operations, retry the create, block cursor advancement, and retain the old child transaction ID in audit history.
- [Child memo preservation requires partial updates] → Verify update semantics and omit memo rather than reading and rewriting it whenever the API permits.
- [Future schema changes can partially apply] → Require contiguous versions, execute pending migrations and version inserts in one transaction, and cover rollback with real SQLite.
- [Active OpenSpec overlap] → Complete `cleared-transactions` before applying this change and rebase the payload/update rules on its final behavior.
- [Money-movement disappearance is ambiguous] → Never delete from absence; log unconfirmed movements and reconcile only re-observed stable IDs.
- [Complete movement snapshots may be expensive] → Fetch once per cycle, normalize deterministically, and avoid child calls when snapshots are unchanged.
- [Large semantic surface can drift from user expectations] → Maintain the linked semantics guide and require paired unit/integration tests for every normative scenario.

## Rollout Plan

1. Complete and archive or synchronize prerequisite parent/child and cleared-transaction specifications.
2. Stop every process using the configured state path.
3. Delete any state database created by an earlier build; it is intentionally unsupported and will not be modified by this binary.
4. Deploy the new binary and run `--dry-run --max-cycles 1` with the intended path. A missing database remains absent during dry-run.
5. Review planned deletions, updates, and creates before enabling a live cycle.
6. Run one live cycle so baseline schema version 1 is created, then verify the nine tables, operation outcomes, and cursor movement.
7. Resume continuous polling only after the human semantics guide and test-coverage matrix have been reviewed.

After the version-1 live run, rollback cannot use an old incompatible state database with this binary. After live updates or deletions, restoring local files cannot reconstruct remote child state automatically; operation history must be used for manual recovery.

## Open Questions

- Confirm the exact current YNAB endpoint paths, mutable update fields, transaction lookup response, deletion response, and not-found behavior from official documentation during implementation.
- Confirm whether transaction delta requests with both `since_date` and `last_knowledge_of_server` can omit tombstones for older transactions; default implementation omits `since_date` after cursor establishment unless verified safe.
- Ask YNAB whether `last_knowledge_of_server` is accepted for money-movement endpoints despite its omission from the current OpenAPI operation, and whether movement deletion or replacement has a documented observable form. Until confirmed, complete snapshots and non-destructive absence semantics remain authoritative.

## ADDED Requirements

### Requirement: Parent transaction reconciliation SHALL use stable source identity
The syncer SHALL identify a parent transaction by source budget ID and parent transaction ID, and SHALL identify a parent subtransaction by source budget ID, parent transaction ID, and parent subtransaction ID. Mutable transaction values including amount, category, category name, date, memo, payee, approval, deletion state, mapping, and target account SHALL NOT create a new source identity. An active transaction mirror SHALL be unique by stable source identity and target child budget; mapping and account remain mutable routing state.

#### Scenario: Amount edit retains source identity
- **WHEN** YNAB returns a changed parent transaction with the same transaction ID and a different amount
- **THEN** the syncer SHALL reconcile the existing source entity rather than treating the edit as a new independent source event

#### Scenario: Split component retains source identity
- **WHEN** YNAB returns a changed split component with the same parent transaction ID and subtransaction ID
- **THEN** the syncer SHALL reconcile the existing split source entity even when its amount or category changed

#### Scenario: Ordinary and split identities remain distinct
- **WHEN** one parent transaction changes between ordinary and split forms
- **THEN** its ordinary source identity SHALL NOT collide with any subtransaction source identity

### Requirement: The syncer SHALL ingest complete changed and deleted parent state
The syncer SHALL map transaction and subtransaction deletion indicators from YNAB delta responses and SHALL process changed transactions even when they are deleted, unapproved, unmapped, or otherwise no longer eligible for creation. After a transaction server-knowledge cursor exists, transaction delta reads SHALL avoid a date filter that can exclude older transaction edits or tombstones.

#### Scenario: Deleted transaction tombstone is retained
- **WHEN** a transaction delta contains a transaction marked deleted
- **THEN** the syncer SHALL represent that tombstone as changed source state rather than discarding it or planning it as a new child transaction

#### Scenario: Empty delta advances knowledge
- **WHEN** YNAB returns no changed transactions with a newer response server knowledge value
- **THEN** a successful sync cycle SHALL advance the transaction cursor to that response server knowledge

#### Scenario: Older transaction edit is requested through delta
- **WHEN** a transaction cursor has already been established
- **THEN** the syncer SHALL request transaction deltas in a way that does not intentionally restrict results to the configured bootstrap lookback date

#### Scenario: Partial split data cannot imply removal
- **WHEN** a changed split transaction response does not guarantee the complete current subtransaction set
- **THEN** the syncer SHALL fetch complete transaction detail before deleting a mirror because its subtransaction is absent

### Requirement: Parent edits SHALL authoritatively update existing child mirrors
For a currently qualifying source with an existing mirror in the same child budget, the syncer SHALL update the existing child transaction when its authoritative date, amount, payee, cleared state, approval state, or resolved child account differs. The update SHALL set the current parent date, amount, and payee, SHALL use the currently resolved account, SHALL mark the transaction cleared, and SHALL reset approval to false.

#### Scenario: Date amount and payee edit updates in place
- **WHEN** a mirrored parent source changes date, amount, or payee while continuing to resolve to the same child budget
- **THEN** the syncer SHALL update the recorded child transaction ID rather than creating an additional child transaction
- **AND** the updated child transaction SHALL be cleared and unapproved

#### Scenario: Same-budget account reroute updates in place
- **WHEN** a changed parent source resolves to a different configured account in the same child budget
- **THEN** the syncer SHALL update the recorded child transaction to use the newly resolved account
- **AND** it SHALL NOT create a second child transaction

#### Scenario: Child approval drift is corrected when source is observed
- **WHEN** a qualifying source is observed and its child mirror is cleared incorrectly or remains approved from an earlier review
- **THEN** reconciliation SHALL restore the documented cleared state and reset approval to false

### Requirement: Child memos SHALL become child-owned after initial creation
The syncer SHALL apply the configured memo prefix and suffix when initially creating a child mirror, but SHALL NOT overwrite the child memo when reconciling later parent edits.

#### Scenario: Parent memo-only edit is a child no-op
- **WHEN** a mirrored parent source changes only its memo
- **THEN** the syncer SHALL persist the observed source revision without issuing a child update

#### Scenario: Financial edit preserves child memo
- **WHEN** a date, amount, payee, or account edit requires a child update
- **THEN** the update request SHALL omit the memo or otherwise preserve the child transaction's current memo

#### Scenario: Memo-only edit still verifies mirror existence
- **WHEN** a parent source changes only its memo
- **THEN** reconciliation SHALL verify that its recorded child transaction still exists before concluding that no child mutation is required

### Requirement: Parent deletion and de-qualification SHALL delete child mirrors
The syncer SHALL delete active child mirrors when their source transaction or subtransaction is explicitly deleted, becomes unapproved, becomes unmapped, or is removed from the current split composition. It SHALL NOT create compensating reversal transactions for these removals.

#### Scenario: Parent deletion removes mirror
- **WHEN** YNAB returns a deletion tombstone for a mirrored parent transaction
- **THEN** the syncer SHALL delete every active child mirror derived from that parent transaction

#### Scenario: Approval withdrawal removes mirror
- **WHEN** a mirrored parent transaction changes from approved to unapproved
- **THEN** the syncer SHALL delete its active child mirrors

#### Scenario: Mapped source becomes unmapped
- **WHEN** a changed parent transaction or subtransaction no longer matches any configured child mapping
- **THEN** the syncer SHALL delete its previously active child mirror

#### Scenario: Repeated deletion is idempotent
- **WHEN** a retry attempts to delete a child mirror that the child API reports as already absent
- **THEN** the syncer SHALL treat the delete operation as successfully complete

### Requirement: Split and routing transitions SHALL reconcile the desired mirror set
For each changed parent transaction, the syncer SHALL compare the complete current desired mirror set with all active mirrors linked to that transaction. It SHALL update same-budget mirrors, delete obsolete mirrors, create newly desired mirrors, and use delete-then-create when a mirror moves to another child budget.

#### Scenario: Ordinary transaction becomes split
- **WHEN** a previously mirrored ordinary parent transaction changes into a split transaction
- **THEN** the syncer SHALL delete the former ordinary child mirror
- **AND** it SHALL create one mirror for each currently qualifying mapped split component

#### Scenario: Split transaction becomes ordinary
- **WHEN** a previously mirrored split parent transaction becomes an ordinary mapped transaction
- **THEN** the syncer SHALL delete all former split mirrors before creating the ordinary mirror

#### Scenario: Split component is added or edited
- **WHEN** a changed split adds a qualifying component or changes authoritative fields on an existing stable component
- **THEN** the syncer SHALL create only the new component and update only the changed existing component
- **AND** it SHALL preserve unrelated split mirrors

#### Scenario: Split component is removed
- **WHEN** a previously mirrored subtransaction is absent from the changed parent transaction's current split set or is marked deleted
- **THEN** the syncer SHALL delete that subtransaction's child mirror without altering unrelated split mirrors

#### Scenario: Cross-budget reroute replaces mirror
- **WHEN** a changed source resolves to a different child budget than its active mirror
- **THEN** the syncer SHALL complete deletion of the old child mirror before creating the replacement in the new child budget

#### Scenario: Configuration changes alone do not rewrite history
- **WHEN** mapping configuration changes but YNAB returns no changed source transaction
- **THEN** the syncer SHALL NOT scan and reroute historical mirrors solely because of that configuration change

### Requirement: Missing child mirrors SHALL be recreated when observed
Whenever a qualifying source with an active mirror is observed, reconciliation SHALL verify that the recorded child transaction still exists. If it no longer exists, the syncer SHALL create a replacement mirror from current authoritative source state, SHALL preserve the prior child transaction ID in audit history, and SHALL record the replacement child transaction ID as active.

#### Scenario: Manually deleted mirror is recreated on parent edit
- **WHEN** a later parent edit causes reconciliation of a source whose recorded child transaction is missing
- **THEN** the syncer SHALL create a replacement child transaction and update the active mirror correlation

#### Scenario: Missing mirror is recreated after a no-op source edit
- **WHEN** a memo-only or otherwise non-authoritative source edit observes that the recorded child transaction is missing
- **THEN** the syncer SHALL recreate the mirror even though no authoritative field update was otherwise required

### Requirement: Reconciliation operations SHALL be durable and cursor-safe
The syncer SHALL persist a durable ingestion batch, immutable ordered create/update/delete operation intent, and append-only operation attempts before and during live child mutation. Failed operation intent SHALL remain retryable across process restarts, successful sibling operations SHALL remain recorded, and the parent transaction cursor SHALL advance only after every operation derived from that ingestion batch is successfully complete. All mixed replacement transitions SHALL complete obsolete-mirror deletions before dependent creates.

#### Scenario: Update failure blocks cursor
- **WHEN** a child transaction update fails
- **THEN** the operation SHALL remain retryable
- **AND** the parent transaction cursor SHALL NOT advance beyond the delta that produced it

#### Scenario: Cross-budget create fails after delete
- **WHEN** an old mirror is deleted successfully but creation in the new child budget fails
- **THEN** the successful deletion SHALL remain recorded
- **AND** the replacement create SHALL retry on a later process cycle
- **AND** the parent transaction cursor SHALL remain unchanged until creation succeeds

#### Scenario: Three process-like cycles recover mutation work
- **WHEN** a first cycle partially applies reconciliation, a second fresh process retries the failure, and a third fresh process receives new incremental work
- **THEN** completed operations SHALL not repeat, failed operations SHALL retry, and the final cursor SHALL reflect the newest fully applied server knowledge

#### Scenario: Remote mutation succeeds before local completion write fails
- **WHEN** a create, update, or delete succeeds remotely but recording local completion fails
- **THEN** the next cycle SHALL recover without creating a second financial effect
- **AND** it SHALL record a new operation attempt while preserving prior attempt history

### Requirement: Reconciliation state SHALL start fresh and evolve transactionally
SQLite initialization SHALL create baseline schema version 1 only in a missing or empty database. The baseline SHALL contain exactly `schema_versions`, `sync_runs`, `sync_cursors`, `source_entities`, `ingestion_batches`, `source_revisions`, `child_mirrors`, `sync_operations`, and `operation_attempts`. Operators SHALL delete databases created by earlier builds; the syncer SHALL NOT infer, upgrade, copy, or clean old state. Deleting local state SHALL NOT be represented as deleting child transactions created by an earlier syncer. A nonempty unversioned database, noncontiguous version history, schema newer than the binary supports, or unsupported current table/index/trigger shape SHALL be rejected without mutation.

Future migrations SHALL have contiguous integer versions after version 1. Initialization SHALL validate applied versions, execute every pending migration in ascending order, and insert its `schema_versions` row in one transaction. A failure SHALL roll back the complete initialization attempt, and initialization at the current supported version SHALL be repeatable without adding version rows.

#### Scenario: Fresh initialization creates baseline version 1
- **WHEN** live initialization opens a missing or empty state database
- **THEN** it SHALL create exactly the nine baseline tables and one `schema_versions` row for version 1

#### Scenario: Repeated initialization is unchanged
- **WHEN** initialization runs more than once against a supported version-1 database
- **THEN** it SHALL leave the table set and single version row unchanged

#### Scenario: Nonempty unversioned database is rejected unchanged
- **WHEN** initialization opens a nonempty database without `schema_versions`
- **THEN** it SHALL direct the operator to delete the old database and SHALL leave its bytes and tables unchanged

#### Scenario: Schema version history must be contiguous
- **WHEN** `schema_versions` contains a gap
- **THEN** initialization SHALL reject the database without inserting, deleting, or reordering version rows

#### Scenario: Newer schema version is rejected unchanged
- **WHEN** a database has a contiguous schema version newer than this binary supports
- **THEN** initialization SHALL reject it without attempting a downgrade or changing version rows

#### Scenario: Baseline failure rolls back atomically
- **WHEN** any baseline schema step fails before version 1 is committed
- **THEN** the initialization transaction SHALL roll back all baseline tables and the version row

### Requirement: Dry-run SHALL report reconciliation without side effects
Dry-run reconciliation SHALL read existing source, mirror, cursor, and required child state from a supported database and SHALL report ordered create, update, delete, reroute, and recreation operations. It SHALL NOT mutate YNAB, create a missing SQLite file, change SQLite bytes or schema, persist source revisions or operations, or advance cursors. It SHALL reject unsupported existing state without mutation.

#### Scenario: Dry-run reports destructive work
- **WHEN** a dry-run observes a parent deletion or reroute
- **THEN** it SHALL identify every child transaction that would be deleted or replaced
- **AND** it SHALL perform no child mutation or SQLite write

#### Scenario: Dry-run missing database creates nothing
- **WHEN** dry-run uses a state path that does not exist
- **THEN** it SHALL read empty state and SHALL NOT create a database file

#### Scenario: Dry-run reads supported state without changing bytes
- **WHEN** dry-run opens a supported version-1 database
- **THEN** it SHALL read cursors, sources, and mirrors while leaving the database byte-for-byte unchanged

#### Scenario: Dry-run rejects unsupported state without mutation
- **WHEN** dry-run opens a nonempty unversioned or otherwise unsupported database
- **THEN** it SHALL reject the database and leave its bytes and tables unchanged

### Requirement: Child mutation API contracts SHALL be verified and isolated
The implementation SHALL use the official YNAB transaction lookup, update, and delete contracts through repository methods backed by the local HTTP client. It SHALL treat supported successful response shapes explicitly, SHALL surface non-idempotent API failures with child context, and SHALL never log access tokens.

#### Scenario: Verified update payload is sent
- **WHEN** reconciliation updates a child mirror
- **THEN** the repository SHALL send only fields supported by the official YNAB update contract
- **AND** the request SHALL use the child budget token without exposing it in logs

#### Scenario: Verified delete endpoint is idempotent
- **WHEN** reconciliation deletes a child mirror
- **THEN** the repository SHALL use the documented delete endpoint and response semantics
- **AND** documented already-absent behavior SHALL not cause duplicate work or cursor starvation

### Requirement: Money movements SHALL use stable observed identity
The syncer SHALL identify a money movement by source budget ID and movement ID. Each derived mirror SHALL additionally identify its target child budget and logical inflow or outflow side. Amount, categories, category names, movement date, group ID, mapping, and target account SHALL remain mutable revision or routing data and SHALL NOT create a second movement identity.

#### Scenario: Same-ID amount or category change retains movement identity
- **WHEN** a later complete money-movement snapshot contains the same movement ID with changed amount, category, date, group, or routing fields
- **THEN** the syncer SHALL reconcile the existing movement entity and mirror lineage rather than creating an unrelated source event

#### Scenario: Inflow and outflow sides cannot collide
- **WHEN** one money movement maps both sides into the same child budget
- **THEN** inflow and outflow SHALL retain distinct mirror and import identities

### Requirement: Re-observed money-movement changes SHALL reconcile conservatively
The syncer SHALL compare each complete unfiltered money-movement snapshot with persisted normalized observations. Re-observed same-ID changes SHALL update same-budget mirrors, delete and recreate cross-budget reroutes, create newly mapped sides, and remove sides that the same observed movement now explicitly routes away from. Initial creation SHALL still honor the configured movement lookback, while previously observed mirrors SHALL remain reconcilable after they age beyond that window.

#### Scenario: Same-ID movement amount edit updates existing sides
- **WHEN** a movement with existing child mirrors is re-observed under the same ID with a different amount
- **THEN** the syncer SHALL update each affected existing child transaction rather than posting additional full-value transactions

#### Scenario: Same-ID movement category reroute reconciles sides
- **WHEN** a re-observed movement changes source or destination category under the same movement ID
- **THEN** the syncer SHALL reconcile the complete desired side set using in-place same-budget updates and ordered cross-budget replacements

#### Scenario: Old observed movement remains eligible for correction
- **WHEN** a previously mirrored movement is re-observed with changes after its movement date falls outside the new-movement lookback
- **THEN** the syncer SHALL reconcile that existing mirror instead of ignoring the correction

### Requirement: Money-movement absence SHALL NOT imply deletion
Because the documented YNAB money-movement contract provides no deletion tombstone or replacement lineage, the syncer SHALL NOT delete child mirrors merely because a movement ID is absent from a later snapshot or delta, no longer falls within the configured lookback, or is replaced by a newly observed ID. Missing previously observed IDs SHALL be recorded or logged as unconfirmed.

#### Scenario: Movement disappears from a later snapshot
- **WHEN** a previously mirrored movement ID is absent from a later successful complete snapshot
- **THEN** the syncer SHALL leave its child mirrors unchanged and report the movement as unconfirmed

#### Scenario: New movement ID does not prove replacement
- **WHEN** a new movement resembles a previously observed movement whose ID is now absent
- **THEN** the syncer SHALL treat the new ID as a separate movement and SHALL NOT delete or rewrite the prior mirrors based on similarity or group ID

### Requirement: Money-movement processing SHALL be independent from transaction cursor completion
Money-movement snapshot ingestion and retryable child operations SHALL use independent completion state and SHALL NOT reuse or block the parent transaction server-knowledge cursor. The initial implementation SHALL NOT send an undocumented movement delta cursor unless the official request contract is clarified and covered by contract tests.

#### Scenario: Movement mutation failure does not block transaction cursor
- **WHEN** a transaction ingestion batch succeeds while a money-movement update or create remains failed
- **THEN** the transaction cursor SHALL advance and the movement operation SHALL remain independently retryable

#### Scenario: Repeated complete snapshot does not duplicate movement work
- **WHEN** unchanged money movements are returned in repeated complete snapshots
- **THEN** stable observation and operation identities SHALL prevent duplicate child mutations

### Requirement: Reconciliation semantics SHALL be documented for human operators
The repository SHALL provide `PARENT_TRANSACTION_RECONCILIATION.md` with plain-language tables and examples covering authoritative parent fields, child-owned memos, deletion and unapproval, mapping changes, ordinary/split transitions, money-movement capabilities and limitations, missing child transactions, retries, the fresh-state requirement, versioned schema evolution, dry-run behavior, and rollout safety. `README.md` and `QUICK_START.md` SHALL link to that guide.

#### Scenario: Operator reviews destructive semantics before rollout
- **WHEN** an operator follows README or quick-start sync guidance
- **THEN** the operator SHALL be directed to the semantics guide before enabling live reconciliation
- **AND** the guide SHALL explain destructive cases with concrete examples

### Requirement: Every reconciliation semantic SHALL have unit and integration coverage
Every normative scenario in this capability SHALL map to a focused executable test and at least one integration test. Behavioral coverage SHALL use focused unit tests for normalization, reconciliation, payload, operation, and coordinator decisions. Schema initialization, transactional rollback, compatibility rejection, and byte-preservation scenarios SHALL use their exact real-SQLite integration features because those guarantees depend on SQLite behavior. Integration coverage SHALL exercise semantics through simulated YNAB HTTP interactions and/or real SQLite persistence, including observable operation ordering and process-restart behavior. A maintained coverage matrix SHALL name both references for each semantic.

#### Scenario: Coverage matrix is complete
- **WHEN** implementation is considered complete
- **THEN** every scenario in this specification SHALL identify a passing focused unit test and a passing integration test
- **AND** `./gradlew testAll` SHALL execute both layers without live YNAB credentials

#### Scenario: Semantics change during implementation
- **WHEN** a requirement or scenario is revised because of a verified YNAB contract
- **THEN** the human semantics guide, focused unit test, integration test, and coverage matrix SHALL be updated together

## ADDED Requirements

### Requirement: Parent transaction reconciliation SHALL use stable source identity
The syncer SHALL identify a parent transaction by source budget ID and parent transaction ID, and SHALL identify a parent subtransaction by source budget ID, parent transaction ID, and parent subtransaction ID. Mutable transaction values including amount, category, category name, date, memo, payee, approval, deletion state, mapping, and target account SHALL NOT create a new source identity.

#### Scenario: Amount edit retains source identity
- **WHEN** YNAB returns a changed parent transaction with the same transaction ID and a different amount
- **THEN** the syncer SHALL reconcile the existing source entity rather than treating the edit as a new independent source event

#### Scenario: Split component retains source identity
- **WHEN** YNAB returns a changed split component with the same parent transaction ID and subtransaction ID
- **THEN** the syncer SHALL reconcile the existing split source entity even when its amount or category changed

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

### Requirement: Child memos SHALL become child-owned after initial creation
The syncer SHALL apply the configured memo prefix and suffix when initially creating a child mirror, but SHALL NOT overwrite the child memo when reconciling later parent edits.

#### Scenario: Parent memo-only edit is a child no-op
- **WHEN** a mirrored parent source changes only its memo
- **THEN** the syncer SHALL persist the observed source revision without issuing a child update

#### Scenario: Financial edit preserves child memo
- **WHEN** a date, amount, payee, or account edit requires a child update
- **THEN** the update request SHALL omit the memo or otherwise preserve the child transaction's current memo

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
When reconciliation requires an update and the recorded child transaction no longer exists, the syncer SHALL create a replacement mirror from the current authoritative source state and SHALL record the replacement child transaction ID.

#### Scenario: Manually deleted mirror is recreated on parent edit
- **WHEN** a later parent edit causes reconciliation of a source whose recorded child transaction is missing
- **THEN** the syncer SHALL create a replacement child transaction and update the active mirror correlation

### Requirement: Reconciliation operations SHALL be durable and cursor-safe
The syncer SHALL persist ordered create, update, and delete operations before applying live child mutations. Failed operations SHALL remain retryable across process restarts, successful sibling operations SHALL remain recorded, and the parent transaction cursor SHALL advance only after every operation derived from the delta batch is successfully complete.

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

### Requirement: Reconciliation state SHALL migrate populated legacy databases safely
SQLite initialization SHALL use transactional schema versioning and SHALL preserve existing audit tables. Migration SHALL backfill stable source entities and active child mirrors from successful historical rows with child transaction IDs. If multiple successful legacy mirrors exist for one stable source and target, the newest SHALL remain active and older mirrors SHALL become pending delete operations rather than being deleted during migration.

#### Scenario: Single successful legacy mirror is backfilled
- **WHEN** migration reads one successful legacy mapping with a child transaction ID
- **THEN** it SHALL create one active child mirror correlated to the stable parent source

#### Scenario: Duplicate successful legacy mirrors are normalized
- **WHEN** migration finds multiple successful child transaction IDs for one stable parent source and target
- **THEN** it SHALL select the row with the newest applied timestamp, using the highest row ID as a tie-breaker
- **AND** it SHALL queue delete operations for the older child transaction IDs

#### Scenario: Migration failure rolls back
- **WHEN** a schema or backfill migration step fails
- **THEN** the migration transaction SHALL roll back without leaving a partially upgraded schema version

### Requirement: Dry-run SHALL report reconciliation without side effects
Dry-run reconciliation SHALL read existing source, mirror, cursor, and required child state and SHALL report ordered create, update, delete, reroute, recreation, and legacy cleanup operations. It SHALL NOT mutate YNAB, persist source revisions or operations, execute legacy cleanup, or advance cursors.

#### Scenario: Dry-run reports destructive work
- **WHEN** a dry-run observes a parent deletion, reroute, or queued legacy duplicate cleanup
- **THEN** it SHALL identify every child transaction that would be deleted or replaced
- **AND** it SHALL perform no child mutation or SQLite write

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

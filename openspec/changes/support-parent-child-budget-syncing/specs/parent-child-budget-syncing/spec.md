## ADDED Requirements

### Requirement: The syncer SHALL run as a standalone continuously polling entry point
The repository SHALL provide a standalone sync entry point for parent/child budget syncing that can be run independently from allowance recording. The syncer SHALL support continuous execution with a configurable polling interval, sync-specific dry-run behavior, and separate Bash/Batch wrappers.

#### Scenario: Syncer runs independently from allowance recording
- **WHEN** an operator starts the parent/child budget syncer
- **THEN** it SHALL run without invoking the allowance-recording workflow
- **AND** the existing allowance entry point SHALL remain separately runnable

#### Scenario: Syncer polls continuously until stopped
- **WHEN** the syncer starts with a configured polling interval
- **THEN** it SHALL repeatedly read parent-budget source data, plan/apply-or-log child-budget actions, sleep for the configured interval, and repeat until terminated

### Requirement: The syncer SHALL mirror approved parent-budget transactions into mapped child budgets
The syncer SHALL read transactions from the configured parent YNAB budget and SHALL create corresponding child-budget transactions only when the parent transaction is approved and has a mapped parent category, or when one of its `subtransactions` is approved-by-context and has a mapped parent category. Each qualifying parent transaction or relevant subtransaction SHALL be mirrored into the mapped child budget account configured for that child and SHALL use deterministic idempotency so reruns do not create duplicates.

#### Scenario: Approved categorized parent transaction creates one child-budget transaction
- **WHEN** the parent budget contains an approved transaction whose top-level category matches a configured child parent-bank category mapping
- **THEN** the syncer SHALL create one child-budget transaction draft for that child budget using the mapped child budget account
- **AND** the child-budget post SHALL occur only once for that parent transaction even if the sync is rerun

#### Scenario: Split transaction creates one child-budget transaction per relevant subtransaction
- **WHEN** the parent budget contains an approved split transaction whose relevant child categories appear only on one or more `subtransactions`
- **THEN** the syncer SHALL create one child-budget transaction draft for each relevant subtransaction
- **AND** it SHALL use the subtransaction `id` as part of replay protection / correlation tracking

#### Scenario: Unapproved parent transaction is ignored
- **WHEN** the parent budget contains a transaction mapped to a child bank category but the transaction is not approved
- **THEN** the syncer SHALL NOT create or post a child-budget transaction for that parent transaction yet

#### Scenario: Uncategorized or unmapped parent activity is ignored
- **WHEN** the parent budget contains an approved transaction or subtransaction that does not have a configured child parent-bank category mapping
- **THEN** the syncer SHALL NOT create or post a child-budget transaction for that activity

### Requirement: The syncer SHALL mirror qualifying parent-budget money movements into child budgets before source data expires
The syncer SHALL read recent money movements from the configured parent YNAB budget and SHALL convert each movement that debits or credits a mapped child parent-bank category into one or more child-budget transactions. Because YNAB money movements are transient, the syncer SHALL support a configurable recent-history sync window and SHALL persist replay-protection state so the same movement is not posted twice to the same child budget.

#### Scenario: Money movement into one child category creates one child-budget transaction
- **WHEN** the parent budget returns a recent money movement that affects one mapped child parent-bank category
- **THEN** the syncer SHALL create the configured child-budget transaction effect for that child budget
- **AND** rerunning the same sync window SHALL NOT duplicate that child-budget transaction

#### Scenario: Money movement between two child categories fans out to two child budgets
- **WHEN** the parent budget returns one recent money movement that decreases one child’s mapped parent-bank category and increases another child’s mapped parent-bank category
- **THEN** the syncer SHALL derive the required child-budget transactions for both affected child budgets
- **AND** replay protection SHALL be tracked separately for each child-budget target derived from that one money movement

### Requirement: The syncer SHALL preserve source transaction details while leaving child category unset
When creating child-budget transactions, the syncer SHALL copy over as much information as is available and meaningful from the source event, including but not limited to date, memo, and amount. The created child-budget transaction SHALL leave category unset in the first implementation.

#### Scenario: Mirrored purchase preserves parent transaction fields
- **WHEN** the syncer creates a child-budget transaction from a qualifying approved parent transaction
- **THEN** the created child-budget transaction SHALL preserve the source date, memo, and amount
- **AND** the created child-budget transaction SHALL leave category unspecified

#### Scenario: Money movement derives payee and memo conventions
- **WHEN** the syncer creates a child-budget transaction from a parent money movement
- **THEN** the memo SHALL be `From <sourceCategory> to <destinationCategory>`
- **AND** the payee SHALL be `From <sourceCategory>` for child inflows or `To <destinationCategory>` for child outflows
- **AND** the child-budget category SHALL be unspecified

### Requirement: The syncer SHALL support dry-run-safe multi-budget execution
The syncer SHALL extend `--dry-run` behavior to the standalone parent/child sync flow so operators can inspect planned child-budget posts and planned SQLite state writes without writing child-budget transactions or persisted sync-state updates.

#### Scenario: Dry-run shows child sync work without posting
- **WHEN** the syncer is run with `--dry-run` and qualifying parent transactions or money movements are found
- **THEN** it SHALL log the child-budget transaction drafts it would create
- **AND** it SHALL log the sync-state records it would write
- **AND** it SHALL NOT post transactions to any child budget
- **AND** it SHALL NOT mark those source events as processed in sync state

### Requirement: The syncer SHALL isolate failures per child sync target
If one child-budget target cannot be resolved or posted, the syncer SHALL surface that failure with child-specific context and SHALL NOT treat unrelated child-budget targets as already synced unless their own posts completed successfully.

#### Scenario: One child token fails while another child succeeds
- **WHEN** a sync run derives child-budget transactions for multiple child budgets and one child budget request fails authentication or lookup
- **THEN** the syncer SHALL report which child target failed
- **AND** it SHALL preserve successful results for other child targets from the same run
- **AND** it SHALL avoid recording the failed child target as processed

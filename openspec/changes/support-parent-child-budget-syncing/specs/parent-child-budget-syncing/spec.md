## ADDED Requirements

### Requirement: The CLI SHALL mirror approved parent-budget transactions into mapped child budgets
The application SHALL read transactions from the configured parent YNAB budget and SHALL create corresponding child-budget transactions only when the parent transaction is approved, has a mapped parent category, and targets a child sync definition configured in the runtime YAML. Each qualifying parent transaction SHALL be mirrored into the mapped child budget account configured for that child and SHALL use deterministic idempotency so reruns do not create duplicates.

#### Scenario: Approved categorized parent transaction creates one child-budget transaction
- **WHEN** the parent budget contains an approved transaction whose category matches a configured child parent-bank category mapping
- **THEN** the CLI SHALL create one child-budget transaction draft for that child budget using the mapped child budget account
- **AND** the child-budget post SHALL occur only once for that parent transaction even if the sync is rerun

#### Scenario: Unapproved parent transaction is ignored
- **WHEN** the parent budget contains a transaction mapped to a child bank category but the transaction is not approved
- **THEN** the CLI SHALL NOT create or post a child-budget transaction for that parent transaction

#### Scenario: Uncategorized or unmapped parent transaction is ignored
- **WHEN** the parent budget contains a transaction that is approved but does not have a configured child parent-bank category mapping
- **THEN** the CLI SHALL NOT create or post a child-budget transaction for that transaction

### Requirement: The CLI SHALL mirror qualifying parent-budget money movements into child budgets before source data expires
The application SHALL read recent money movements from the configured parent YNAB budget and SHALL convert each movement that debits or credits a mapped child parent-bank category into one or more child-budget transactions. Because YNAB money movements are transient, the CLI SHALL support a configurable recent-history sync window and SHALL persist replay-protection state so the same movement is not posted twice to the same child budget.

#### Scenario: Money movement into one child category creates one child-budget transaction
- **WHEN** the parent budget returns a recent money movement that affects one mapped child parent-bank category
- **THEN** the CLI SHALL create the configured child-budget transaction effect for that child budget
- **AND** rerunning the same sync window SHALL NOT duplicate that child-budget transaction

#### Scenario: Money movement between two child categories fans out to two child budgets
- **WHEN** the parent budget returns one recent money movement that decreases one child’s mapped parent-bank category and increases another child’s mapped parent-bank category
- **THEN** the CLI SHALL derive the required child-budget transactions for both affected child budgets
- **AND** replay protection SHALL be tracked separately for each child-budget target derived from that one money movement

### Requirement: The CLI SHALL support dry-run-safe multi-budget sync execution
The application SHALL extend the existing `--dry-run` behavior to the parent/child sync flow so operators can inspect planned child-budget posts without writing child-budget transactions or persisted sync-state updates.

#### Scenario: Dry-run shows child sync work without posting
- **WHEN** the CLI is run with `--dry-run` and qualifying parent transactions or money movements are found
- **THEN** the CLI SHALL log or print the child-budget transaction drafts it would create
- **AND** it SHALL NOT post transactions to any child budget
- **AND** it SHALL NOT mark those source events as processed in sync state

### Requirement: The CLI SHALL isolate failures per child sync target
If one child-budget target cannot be resolved or posted, the CLI SHALL surface that failure with child-specific context and SHALL NOT treat unrelated child-budget targets as already synced unless their own posts completed successfully.

#### Scenario: One child token fails while another child succeeds
- **WHEN** a sync run derives child-budget transactions for multiple child budgets and one child budget request fails authentication or lookup
- **THEN** the CLI SHALL report which child target failed
- **AND** it SHALL preserve successful results for other child targets from the same run
- **AND** it SHALL avoid recording the failed child target as processed

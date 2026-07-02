## MODIFIED Requirements

### Requirement: The syncer SHALL retry failed child work and advance cursors only after recoverable work is safe
The parent/child syncer SHALL treat planned mappings, failed child attempts, and successfully applied child transactions as distinct states. A failed child-budget lookup, authentication failure, or transaction post failure SHALL be recorded for audit but SHALL NOT cause future runs to skip that source event as already applied. The syncer SHALL only advance `transactions.last_server_knowledge` after all child work derived from the transaction batch is either successfully applied or already known to have been successfully applied.

#### Scenario: Failed child post is retried on a later run
- **WHEN** a live sync cycle derives a child transaction and the child transaction post fails
- **THEN** the syncer SHALL record the failed attempt for audit
- **AND** it SHALL NOT treat the idempotency key as successfully applied
- **AND** a later run that sees the same source event SHALL retry the child post

#### Scenario: Missing child account is retried after configuration or YNAB data is fixed
- **WHEN** a child target fails because the configured child account cannot be resolved
- **THEN** the syncer SHALL record the failed child target for audit
- **AND** it SHALL NOT suppress a later retry after the account becomes resolvable

#### Scenario: Partial child failure prevents cursor advancement
- **WHEN** a sync cycle reads parent transactions with server knowledge and one or more child targets fail
- **THEN** the sync run SHALL be marked partial or failed according to the final outcome
- **AND** `transactions.last_server_knowledge` SHALL NOT advance for that cycle

#### Scenario: Fully successful cycle advances cursor
- **WHEN** a sync cycle reads parent transactions and all derived child work succeeds or was already successfully applied
- **THEN** the sync run SHALL be marked succeeded
- **AND** `transactions.last_server_knowledge` SHALL advance to the latest server knowledge returned by YNAB

### Requirement: Sync state SHALL distinguish planned, failed, and applied outcomes
SQLite-backed sync state SHALL enforce enough structure to preserve parent event correlation, target child correlation, retry safety, and auditability. Columns named as IDs SHALL store IDs rather than names. Foreign-key relationships and practical domain constraints SHALL be enforced where supported by SQLite.

#### Scenario: Failed attempts remain auditable without blocking retry
- **WHEN** a child application attempt fails
- **THEN** the state database SHALL retain the source event, mapping, and failed attempt details
- **AND** duplicate detection SHALL still report the idempotency key as not successfully applied

#### Scenario: Target budget IDs are stored consistently
- **WHEN** the syncer records a mapping or applied child transaction
- **THEN** fields named `target_budget_id` SHALL contain the resolved child plan/budget ID
- **AND** budget names needed for lookup SHALL be kept separately from ID columns or only in in-memory planning models

#### Scenario: SQLite constraints reject invalid state where practical
- **WHEN** invalid status, direction, event-type, or foreign-key data is written directly to the state database
- **THEN** SQLite SHALL reject that invalid state where compatible with the implemented schema

### Requirement: The syncer SHALL use documented YNAB plan endpoints
YNAB repository methods SHALL use documented `/v1/plans` endpoint paths rather than the older `/v1/budgets` path family for plan listing, account/category reads, transaction reads, money movement reads, and transaction writes.

#### Scenario: Repository reads plan data from documented paths
- **WHEN** repository methods list plans or read accounts, categories, transactions, or money movements
- **THEN** HTTP requests SHALL target `/v1/plans` or `/v1/plans/{plan_id}/...` paths
- **AND** tests SHALL fail if those calls regress to `/v1/budgets` paths

#### Scenario: Repository posts child transactions to documented paths
- **WHEN** the syncer posts child-budget transactions
- **THEN** the HTTP request SHALL target the documented `/v1/plans/{plan_id}/...` transaction creation path chosen for the implementation
- **AND** the request body SHALL preserve the existing transaction payload semantics

### Requirement: Sync logging SHALL use configured rolling file logging
The standalone syncer SHALL configure actual Logback logging for continuous operation using `sync.logging.filePath`, `sync.logging.level`, `sync.logging.maxHistory`, and `sync.logging.maxFileSizeMb`.

#### Scenario: Configured sync log file receives syncer log lines
- **WHEN** the syncer starts with valid logging configuration
- **THEN** actual syncer log output SHALL be written to the configured log file
- **AND** the configured log level SHALL be applied

#### Scenario: Rolling policy is configured from YAML
- **WHEN** the syncer configures file logging
- **THEN** the rolling policy SHALL use the configured history and file-size values

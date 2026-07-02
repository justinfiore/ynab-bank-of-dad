# modular-allowance-processing Specification

## Purpose
Define the modularization boundaries and runtime-behavior guarantees for the allowance-processing CLI as responsibilities move out of the monolithic script into focused collaborators.
## Requirements
### Requirement: The application SHALL separate allowance-processing responsibilities into focused modules
The codebase SHALL refactor the current monolithic allowance-processing implementation so that CLI startup/orchestration, YNAB data access, business-rule calculation, and transaction assembly responsibilities are expressed through smaller focused classes or modules instead of remaining concentrated inside one large script-oriented entry point.

#### Scenario: Major responsibilities are no longer concentrated in one script
- **WHEN** a developer inspects the application code after the refactor
- **THEN** they SHALL find distinct modules or classes for the major concerns of startup/orchestration, YNAB interaction, and allowance/interest transaction calculation
- **AND** `RecordAllowance` SHALL remain as the executable entry point or thin coordinator rather than continuing to house all substantive behavior

### Requirement: The refactor SHALL preserve the current CLI runtime contract
The modularized implementation SHALL preserve the current required environment variable `YNAB_ACCESS_TOKEN`, support for `--date`, `--dry-run`, and `--help`, and add `-c` / `--config` for explicitly choosing the YAML config path. The implementation SHALL load its allowance/account/rate configuration from a user-supplied `config.yaml` file at startup, while the repository SHALL provide a safe `config.yaml.example` template and SHALL NOT require personal budget names, child names, or other family-specific values to remain in committed source files.

#### Scenario: Existing invocation behavior remains intact after modularization
- **WHEN** the application is run on the supported Java 25 / Gradle 9 / Groovy 5 toolchain with the same CLI inputs used before the refactor
- **THEN** it SHALL continue to accept the same flags and environment variable requirements
- **AND** it SHALL preserve the same dry-run-safe behavior and YNAB API usage unless another approved spec explicitly changes them

#### Scenario: Equivalent private config preserves current transaction semantics
- **WHEN** a local `config.yaml` contains the same kid lists, allowance rates, account types, deposit mappings, and dated interest-rate tables as the previous embedded defaults
- **THEN** the CLI SHALL generate the same transaction set for the same input date and YNAB data that it would have produced before the configuration externalization
- **AND** moving those values out of `RecordAllowance.groovy` SHALL NOT by itself change posting behavior or YNAB endpoint usage

#### Scenario: Committed example config is safe to share
- **WHEN** the repository ships `config.yaml.example`
- **THEN** that file SHALL use example budget and child/account names rather than the maintainer's real family-specific values
- **AND** committed documentation, tests, and scripts SHALL avoid the maintainer's real child names

### Requirement: The refactor SHALL make business-rule logic callable without hidden posting side effects
Allowance, interest, and transaction-building logic SHALL be organized so that core calculations can be exercised independently from the side-effecting bulk-post path to YNAB.

#### Scenario: Calculation paths can be tested without posting transactions
- **WHEN** automated tests exercise the extracted calculation and transaction-assembly collaborators
- **THEN** those tests SHALL be able to verify generated transaction data without requiring a live YNAB API call or performing a bulk transaction post

#### Scenario: Posting remains an explicit orchestration decision
- **WHEN** the application runs without `--dry-run`
- **THEN** the actual bulk transaction post to `/v1/plans/{budgetId}/transactions/bulk` SHALL still occur only from an explicit top-level execution path
- **AND** extracted helpers SHALL not perform hidden posting as a side effect of data lookup or calculation


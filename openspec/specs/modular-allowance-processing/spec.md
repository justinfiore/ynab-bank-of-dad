# modular-allowance-processing Specification

## Purpose
TBD - created by archiving change refactor-script-into-modular-components. Update Purpose after archive.
## Requirements
### Requirement: The application SHALL separate allowance-processing responsibilities into focused modules
The codebase SHALL refactor the current monolithic allowance-processing implementation so that CLI startup/orchestration, YNAB data access, business-rule calculation, and transaction assembly responsibilities are expressed through smaller focused classes or modules instead of remaining concentrated inside one large script-oriented entry point.

#### Scenario: Major responsibilities are no longer concentrated in one script
- **WHEN** a developer inspects the application code after the refactor
- **THEN** they SHALL find distinct modules or classes for the major concerns of startup/orchestration, YNAB interaction, and allowance/interest transaction calculation
- **AND** `RecordAllowance` SHALL remain as the executable entry point or thin coordinator rather than continuing to house all substantive behavior

### Requirement: The refactor SHALL preserve the current CLI runtime contract
The modularized implementation SHALL preserve the current required environment variable `YNAB_ACCESS_TOKEN`, support for `--date`, `--dry-run`, `--help`, and `-c/--config`, a dry-run-safe execution pattern, and configurable YNAB lookup names loaded from runtime configuration rather than hard-coded personal values.

#### Scenario: Existing invocation behavior remains intact after modularization
- **WHEN** the application is run on the supported Java 25 / Gradle 9 / Groovy 5 toolchain with the same CLI inputs used before the refactor
- **THEN** it SHALL continue to accept the same flags and environment variable requirements plus the explicit config-path override
- **AND** it SHALL preserve dry-run-safe behavior while resolving budget/account/category names from the configured runtime file unless another approved spec explicitly changes them

### Requirement: The refactor SHALL make business-rule logic callable without hidden posting side effects
Allowance, interest, and transaction-building logic SHALL be organized so that core calculations can be exercised independently from the side-effecting bulk-post path to YNAB.

#### Scenario: Calculation paths can be tested without posting transactions
- **WHEN** automated tests exercise the extracted calculation and transaction-assembly collaborators
- **THEN** those tests SHALL be able to verify generated transaction data without requiring a live YNAB API call or performing a bulk transaction post

#### Scenario: Posting remains an explicit orchestration decision
- **WHEN** the application runs without `--dry-run`
- **THEN** the actual bulk transaction post to `/v1/budgets/{budgetId}/transactions/bulk` SHALL still occur only from an explicit top-level execution path
- **AND** extracted helpers SHALL not perform hidden posting as a side effect of data lookup or calculation


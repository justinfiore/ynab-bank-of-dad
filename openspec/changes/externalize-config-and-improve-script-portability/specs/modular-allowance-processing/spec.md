## MODIFIED Requirements

### Requirement: The refactor SHALL preserve the current CLI runtime contract
The modularized implementation SHALL preserve the current required environment variable `YNAB_ACCESS_TOKEN`, support for `--date`, `--dry-run`, and `--help`, and the current YNAB naming assumptions around the `Fiores` budget, `Allowance Escrow` account, `Allowance` category, and existing category naming conventions. The implementation SHALL load its allowance/account/rate configuration from the checked-in top-level `config.yaml` at startup rather than defining that runtime domain model only as hard-coded literals inside `RecordAllowance.groovy`.

#### Scenario: Existing invocation behavior remains intact after modularization
- **WHEN** the application is run on the supported Java 25 / Gradle 9 / Groovy 5 toolchain with the same CLI inputs used before the refactor
- **THEN** it SHALL continue to accept the same flags and environment variable requirements
- **AND** it SHALL preserve the same YNAB lookup assumptions and dry-run-safe behavior unless another approved spec explicitly changes them

#### Scenario: Equivalent config preserves current transaction semantics
- **WHEN** `config.yaml` contains the same kid lists, allowance rates, account types, deposit mappings, and dated interest-rate tables as the previous embedded defaults
- **THEN** the CLI SHALL generate the same transaction set for the same input date and YNAB data that it would have produced before the configuration externalization
- **AND** moving those values out of `RecordAllowance.groovy` SHALL NOT by itself change posting behavior or YNAB endpoint usage

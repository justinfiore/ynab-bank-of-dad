## MODIFIED Requirements

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

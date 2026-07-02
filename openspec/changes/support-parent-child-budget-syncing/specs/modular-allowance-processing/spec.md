## MODIFIED Requirements

### Requirement: The allowance CLI SHALL preserve its current runtime contract and remain isolated from sync side effects
The modularized implementation SHALL preserve the current required environment variable `YNAB_ACCESS_TOKEN`, support for `--date`, `--dry-run`, and `--help`, and add `-c` / `--config` for explicitly choosing the YAML config path. The implementation SHALL load its allowance/account/rate configuration from a user-supplied `config.yaml` file at startup, while the repository SHALL provide a safe `config.yaml.example` template and SHALL NOT require personal budget names, child names, or other family-specific values to remain in committed source files.

The addition of parent/child budget syncing SHALL NOT change the current allowance command’s side-effect model: allowance recording SHALL continue to target only the parent budget, and child-budget transactions SHALL be created only by the standalone sync entry point.

#### Scenario: Existing invocation behavior remains intact after sync feature addition
- **WHEN** the allowance application is run on the supported Java 25 / Gradle 9 / Groovy 5 toolchain with the same CLI inputs used before the sync feature
- **THEN** it SHALL continue to accept the same flags and environment variable requirements
- **AND** it SHALL preserve the same dry-run-safe behavior and YNAB API usage unless another approved spec explicitly changes them

#### Scenario: Allowance run does not directly create child-budget transactions
- **WHEN** an operator runs the existing allowance command with sync configuration also present in `config.yaml`
- **THEN** the allowance command SHALL NOT create transactions directly in child budgets
- **AND** any child-budget mirroring SHALL be deferred to the standalone syncer’s subsequent run

#### Scenario: Committed example config is safe to share
- **WHEN** the repository ships `config.yaml.example`
- **THEN** that file SHALL use example budget and child/account names rather than the maintainer's real family-specific values
- **AND** committed documentation, tests, and scripts SHALL avoid the maintainer's real child names

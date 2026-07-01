# portable-runtime-configuration Specification

## Purpose
Define the repo's portable runtime-configuration contract so allowance/interest behavior is driven by user-supplied YAML configuration and portable helper scripts rather than hard-coded personal values.
## Requirements
### Requirement: The CLI SHALL load runtime domain configuration from `config.yaml`
The repository SHALL include a checked-in top-level `config.yaml` file that defines the runtime domain configuration currently needed to calculate allowance and interest transactions, including the kid/account lists, bank suffixes, allowance-rate values, advanced allowance deposit mappings, supported advanced account types, and dated interest-rate tables. The CLI SHALL load that file during startup instead of requiring those values to remain embedded as Groovy literals inside `RecordAllowance.groovy`.

#### Scenario: Startup uses the checked-in configuration file
- **WHEN** a user runs the CLI from the repository with a valid `config.yaml` and a configured `YNAB_ACCESS_TOKEN`
- **THEN** the application SHALL load the required allowance and interest configuration from `config.yaml` before generating transactions
- **AND** it SHALL preserve the current transaction behavior when `config.yaml` contains the same values that were previously hard-coded

#### Scenario: Invalid configuration fails explicitly
- **WHEN** `config.yaml` is missing, unreadable, or lacks a required configuration section or key
- **THEN** the application SHALL fail before posting transactions
- **AND** it SHALL emit an explicit error identifying the missing or invalid configuration input

### Requirement: Helper scripts SHALL be repo-relative and credential-safe
The repository SHALL provide checked-in helper scripts for both Windows batch and Bash shells that invoke the build/install/run flow from repo-relative paths, SHALL NOT embed a literal `YNAB_ACCESS_TOKEN`, and SHALL NOT require a hard-coded user-specific workspace path or fixed machine-specific JDK installation path in version-controlled script content.

#### Scenario: Windows helper scripts avoid machine-specific values
- **WHEN** a user inspects or runs the checked-in Windows helper scripts from a repository checkout
- **THEN** the scripts SHALL resolve paths relative to the script or repository location rather than `C:\Users\Justin\...`
- **AND** they SHALL rely on caller-provided environment configuration for `YNAB_ACCESS_TOKEN` and Java availability instead of embedding those values directly in the script

#### Scenario: Bash helper scripts provide comparable launch flows
- **WHEN** a Linux or macOS user inspects the repository helper scripts
- **THEN** they SHALL find Bash scripts that provide weekly-run and date-specific-run flows comparable to the Windows helper scripts
- **AND** those scripts SHALL support a safe invocation path that can include `--dry-run`


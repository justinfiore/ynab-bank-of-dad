# safe-startup-logging Specification

## Purpose
Define startup logging requirements for YNAB Bank of Dad so the application never exposes the raw `YNAB_ACCESS_TOKEN` in normal initialization logs while preserving existing CLI behavior and authentication flow.

## Requirements
### Requirement: Startup logs do not expose the YNAB access token
The CLI SHALL NOT print the raw value of the `YNAB_ACCESS_TOKEN` environment variable in startup logs, console output, or other normal logging emitted during application initialization.

#### Scenario: Startup with configured token
- **WHEN** the application starts with `YNAB_ACCESS_TOKEN` set
- **THEN** initialization logs SHALL NOT include the raw token value
- **AND** the application SHALL continue using `YNAB_ACCESS_TOKEN` for YNAB API authentication

#### Scenario: Missing token still fails explicitly
- **WHEN** the application starts without `YNAB_ACCESS_TOKEN` set
- **THEN** the application SHALL fail with an explicit error indicating that `YNAB_ACCESS_TOKEN` must be set

### Requirement: Removing token logging does not change transaction behavior
Removing raw access-token logging SHALL NOT change CLI flags, budget/account/category lookup behavior, generated transaction content, or YNAB REST API request structure.

#### Scenario: Existing dry-run behavior remains unchanged
- **WHEN** the application is run with an otherwise valid configuration and the `--dry-run` flag
- **THEN** it SHALL continue calculating and logging the same transaction set as before
- **AND** it SHALL avoid posting real transactions as before

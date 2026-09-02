## ADDED Requirements

### Requirement: Automated tests SHALL cover auto-create child account configuration and routing
The Spock unit suite and WireMock suite SHALL cover the new child-budget auto-create settings and the find-or-create routing path without calling the live YNAB API.

#### Scenario: Config validation coverage
- **WHEN** a developer runs `./gradlew test`
- **THEN** the suite SHALL include tests that `autoCreateAccounts` defaults to `false`, rejects non-booleans, stores a valid `accountCreationNameStripRegex`, rejects an invalid strip regex, defaults `createdAccountOnBudget` to `true` when auto-create is enabled, and rejects a non-boolean `createdAccountOnBudget`

#### Scenario: Simulated create-account coverage
- **WHEN** WireMock fixtures simulate `POST /v1/plans/{plan_id}/accounts` success and missing-account list responses
- **THEN** the suite SHALL verify a live auto-create run issues that POST with `type` `checking` (on-budget) or `otherAsset` (off-budget) and `balance` `0`
- **AND** it SHALL verify `--dry-run` and `autoCreateAccounts: false` do not issue that POST
- **AND** it SHALL verify an existing derived-name account is reused without a second POST

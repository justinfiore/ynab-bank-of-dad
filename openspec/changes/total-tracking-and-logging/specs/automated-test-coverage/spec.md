## ADDED Requirements

### Requirement: Automated tests SHALL cover cycle-end balance reporting
The Spock unit suite and WireMock suite SHALL cover accumulated change by account, reverse parent-category-to-child-account mapping, dry-run projected balances, live actual account balances, and mismatch logging without calling the live YNAB API.

#### Scenario: Unit tests cover net change and reverse mapping
- **WHEN** a developer runs `./gradlew test`
- **THEN** the suite SHALL include tests that CREATE, UPDATE, and DELETE milliunit contributions roll up per child account
- **AND** it SHALL verify that multiple parent categories cached to the same child account sum on the parent side
- **AND** it SHALL verify the reverse mapping uses the cached propagation account, not a second `accountMappings` pass
- **AND** it SHALL verify that an unequal child vs parent total is reported without being treated as a failure

#### Scenario: Simulated dry-run and live comparison coverage
- **WHEN** WireMock fixtures simulate parent category `balance` values and child account `balance` values on `GET /v1/plans/{plan}/categories` and `GET /v1/plans/{plan}/accounts`
- **THEN** the suite SHALL verify `--dry-run` logs projected child balance as current account balance plus accumulated change
- **AND** it SHALL verify a live cycle logs the post-apply account `balance` without adding accumulated change again
- **AND** it SHALL verify a mismatch is present in the logs while the cycle does not record that mismatch as a run failure

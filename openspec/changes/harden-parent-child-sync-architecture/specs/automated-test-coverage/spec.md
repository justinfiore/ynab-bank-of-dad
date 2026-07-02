## MODIFIED Requirements

### Requirement: Parent/child sync hardening SHALL be verified with post-refactor unit and integration coverage
After retry, cursor, `/plans`, logging, packaging, and SQLite architecture changes are implemented, the test suite SHALL be reassessed against the resulting code boundaries. Missing coverage SHALL then be added at the appropriate unit, WireMock integration, or SQLite integration level.

#### Scenario: Coverage analysis happens after architecture changes
- **WHEN** the implementation of findings 1 through 12 is complete
- **THEN** maintainers SHALL review the final production classes and identify remaining behavior not covered by tests
- **AND** the resulting missing tests SHALL be added before the change is considered complete

#### Scenario: Retry and partial-failure behavior is regression-tested
- **WHEN** a child target fails and later becomes available
- **THEN** automated tests SHALL prove the syncer retries and eventually applies the work
- **AND** tests SHALL prove cursors do not advance past unresolved work

#### Scenario: `/plans` endpoint usage is regression-tested
- **WHEN** repository and syncer integration tests run against WireMock
- **THEN** they SHALL assert documented `/plans` request paths rather than deprecated `/budgets` paths

#### Scenario: Logging and SQLite constraints are regression-tested
- **WHEN** the test suite runs
- **THEN** it SHALL include coverage for actual configured sync log output and hardened SQLite state behavior

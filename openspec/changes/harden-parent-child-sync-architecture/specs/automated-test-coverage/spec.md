## MODIFIED Requirements

### Requirement: Parent/child sync hardening SHALL be verified with post-refactor unit and integration coverage
After retry, cursor, `/plans`, logging, packaging, and SQLite architecture changes are implemented, the test suite SHALL be reassessed against the resulting code boundaries. The reassessment SHALL explicitly analyze the known review gaps, map each gap to the final refactored code owner, produce a concrete test plan, and then add the approved coverage at the appropriate unit, WireMock integration, SQLite integration, or build/documentation verification level.

#### Scenario: Coverage analysis happens after architecture changes
- **WHEN** the implementation of findings 1 through 12 is complete
- **THEN** maintainers SHALL review the final production classes and identify remaining behavior not covered by tests
- **AND** the analysis SHALL include the previously identified gaps for failed child retry, partial-failure cursor behavior, missing-account retry, child auth failures, parent API failures, no-work cycles, single-child money movements, money-movement date fallback/lookback behavior, child post response variants, import ID determinism, environment-variable validation, logging behavior, and Gradle `test` task behavior
- **AND** the resulting missing tests SHALL be planned before implementation

#### Scenario: Test plan is created before filling gaps
- **WHEN** post-refactor gaps are analyzed
- **THEN** maintainers SHALL produce a concrete test plan naming target test files/classes, fixture setup, expected assertions, and test type for each still-applicable gap
- **AND** any obsolete or intentionally deferred gaps SHALL be recorded with rationale

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

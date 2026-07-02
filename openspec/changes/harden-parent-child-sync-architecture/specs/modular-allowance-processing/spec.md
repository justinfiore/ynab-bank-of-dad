## MODIFIED Requirements

### Requirement: The codebase SHALL organize sync functionality into cohesive packages and services
Parent/child syncer implementation SHALL be organized into packages and focused collaborators rather than default-package classes with broad responsibilities. The standalone sync CLI SHALL remain runnable after package reorganization.

#### Scenario: Sync code is package-organized
- **WHEN** a maintainer inspects sync production classes
- **THEN** sync orchestration, models, state, YNAB boundaries, config, and allowance code SHALL live in appropriate packages
- **AND** Gradle application/main-class wiring SHALL reference the packaged entry points

#### Scenario: Sync planning is testable without external APIs or SQLite
- **WHEN** planner unit tests exercise transaction and money-movement mapping behavior
- **THEN** they SHALL instantiate a focused planning service without WireMock, live YNAB credentials, or real SQLite

#### Scenario: Child apply behavior is isolated from planning
- **WHEN** tests exercise retry, post, and state-write behavior
- **THEN** they SHALL target a focused child-apply/coordinator boundary rather than relying only on monolithic `ParentChildBudgetSyncer` methods

### Requirement: Repo guidance SHALL reflect current package, API, logging, and test conventions
Repo-local contributor/agent guidance SHALL describe the current YNAB `/plans` API usage, supported Gradle verification command, token handling, and logging behavior without retaining stale warnings that no longer apply.

#### Scenario: Guidance does not preserve stale token/logging warnings
- **WHEN** maintainers read `AGENTS.md`
- **THEN** it SHALL NOT claim checked-in token material or raw token logging is present if those issues have been removed
- **AND** it SHALL describe current token handling accurately

#### Scenario: Verification guidance uses the real test command
- **WHEN** maintainers read OpenSpec or repo guidance for verification
- **THEN** it SHALL direct them to the actual verification task, `./gradlew testAll`, unless the Gradle `test` task has been rewired to execute the intended suite

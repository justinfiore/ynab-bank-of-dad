## MODIFIED Requirements

### Requirement: The project SHALL provide Spock-based automated unit tests for core business logic
The project SHALL include automated Spock specifications that run under the supported Gradle workflow and validate the current Groovy CLI's core calculation, transaction-generation behavior, and error handling without calling the live YNAB API, including focused tests for extracted collaborators introduced by modular refactors.

#### Scenario: Unit tests cover failure and boundary behavior
- **WHEN** helper and business-rule methods encounter invalid or missing inputs under the current domain model assumptions
- **THEN** the automated unit suite SHALL verify the current explicit failure behavior, including malformed CD category names, unsupported CD origination derivation, and other deterministic rule violations that should fail fast

#### Scenario: Extracted collaborators have focused behavior coverage
- **WHEN** the codebase introduces smaller classes or modules for calculations, lookups, or transaction assembly during a refactor
- **THEN** the automated unit suite SHALL include focused Spock specifications for those extracted collaborators
- **AND** those specs SHALL verify preserved behavior without requiring full CLI orchestration for every business-rule assertion

### Requirement: Test enablement SHALL preserve production runtime behavior
Any code changes introduced to make the script testable SHALL preserve the application's current CLI flags, environment-variable requirements, YNAB endpoint usage, and generated transaction semantics outside the test harness, even when logic is moved into new collaborators or helper modules.

#### Scenario: Testing seams do not change runtime contract
- **WHEN** the application is run outside the automated tests with its normal inputs and flags
- **THEN** it SHALL continue to require `YNAB_ACCESS_TOKEN`, use the same YNAB API endpoint patterns, and preserve the current transaction-generation behavior aside from any changes explicitly covered by other approved specs

#### Scenario: Refactor-oriented test seams avoid hidden side effects
- **WHEN** tests invoke extracted calculation or transaction-building components directly
- **THEN** those test seams SHALL not require posting real YNAB transactions or mutating external state in order to verify the expected outputs
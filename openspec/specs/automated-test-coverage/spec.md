# automated-test-coverage Specification

## Purpose
Define the required automated test coverage, reporting, and verification expectations for YNAB Bank of Dad so code changes are validated on the repository's supported Java / Groovy / Gradle toolchain before they are considered complete.
## Requirements
### Requirement: The project SHALL provide Spock-based automated unit tests for core business logic
The project SHALL include automated Spock specifications that run under the supported Gradle workflow and validate the current Groovy CLI's core calculation, transaction-generation behavior, and error handling without calling the live YNAB API, including focused tests for extracted collaborators introduced by modular refactors.

#### Scenario: Unit tests cover failure and boundary behavior
- **WHEN** helper and business-rule methods encounter invalid or missing inputs under the current domain model assumptions
- **THEN** the automated unit suite SHALL verify the current explicit failure behavior, including malformed CD category names, unsupported CD origination derivation, and other deterministic rule violations that should fail fast

#### Scenario: Extracted collaborators have focused behavior coverage
- **WHEN** the codebase introduces smaller classes or modules for calculations, lookups, or transaction assembly during a refactor
- **THEN** the automated unit suite SHALL include focused Spock specifications for those extracted collaborators
- **AND** those specs SHALL verify preserved behavior without requiring full CLI orchestration for every business-rule assertion

### Requirement: The project SHALL provide simulated YNAB API integration tests using WireMock
The project SHALL include automated integration-style tests that simulate the YNAB REST API through WireMock HTTP fixtures so the CLI's current API interaction paths can be verified without live YNAB credentials or real transaction posting.

#### Scenario: Missing or invalid budget data is validated without live API access
- **WHEN** the integration suite simulates `/v1/plans` responses that omit any budget matching the configured runtime `budgetName` or include malformed budget metadata
- **THEN** the suite SHALL verify the application’s explicit failure behavior rather than allowing a silent null path or unhelpful collection error

#### Scenario: Missing account or category data is validated through simulated API responses
- **WHEN** the integration tests simulate `/v1/plans/{budgetId}/accounts` or `/v1/plans/{budgetId}/categories` responses that omit required application data
- **THEN** the suite SHALL verify the current observable behavior for unresolved `Allowance Escrow`, `Allowance`, or other required categories/accounts

#### Scenario: Failed bulk transaction posting is validated without posting to YNAB
- **WHEN** the integration tests simulate `/v1/plans/{budgetId}/transactions/bulk` failure responses
- **THEN** they SHALL verify that the application surfaces the YNAB client failure rather than masking it

### Requirement: Automated tests SHALL remain compatible with the supported repo toolchain
The automated test implementation SHALL remain compatible with the repository's supported Java, Groovy, Spock, and Gradle wrapper baseline rather than being tied only to the legacy Java 8, Groovy 2.4.x, and Gradle 4.2.1 stack, and the repository SHALL provide a documented GitHub Actions workflow that exercises `./gradlew testAll` remotely on pushes and pull requests while packaging verification is also exercised through `./gradlew installDist`.

#### Scenario: Test suite runs on the supported Gradle workflow
- **WHEN** a developer runs `./gradlew testAll` using the repository's documented supported Java/toolchain baseline
- **THEN** the automated tests SHALL execute successfully without requiring the legacy Java 8 / Groovy 2.4 / Gradle 4 environment described before this modernization change

#### Scenario: Remote CI exercises the same supported test path
- **WHEN** a contributor pushes a branch or opens a pull request in the GitHub-hosted repository
- **THEN** the repository's GitHub Actions CI workflow SHALL execute `./gradlew testAll` on the documented supported Java baseline
- **AND** the workflow SHALL preserve the generated test results for remote inspection

### Requirement: Test enablement SHALL preserve production runtime behavior
Any code changes introduced to make the script testable SHALL preserve the application's current CLI flags, environment-variable requirements, YNAB endpoint usage, and generated transaction semantics outside the test harness, even when logic is moved into new collaborators or helper modules.

#### Scenario: Testing seams do not change runtime contract
- **WHEN** the application is run outside the automated tests with its normal inputs and flags
- **THEN** it SHALL continue to require `YNAB_ACCESS_TOKEN`, use the same YNAB API endpoint patterns, and preserve the current transaction-generation behavior aside from any changes explicitly covered by other approved specs

#### Scenario: Refactor-oriented test seams avoid hidden side effects
- **WHEN** tests invoke extracted calculation or transaction-building components directly
- **THEN** those test seams SHALL not require posting real YNAB transactions or mutating external state in order to verify the expected outputs


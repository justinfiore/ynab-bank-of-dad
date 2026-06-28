# automated-test-coverage Specification

## Purpose
Define the required automated test coverage, reporting, and verification expectations for YNAB Bank of Dad so code changes are validated on the current Java 8 / Groovy 2.4 / Gradle toolchain before they are considered complete.
## Requirements
### Requirement: The project SHALL provide Spock-based automated unit tests for core business logic
The project SHALL include automated Spock specifications that run under the existing Gradle workflow and validate the current Groovy CLI's core calculation and transaction-generation behavior without calling the live YNAB API.

#### Scenario: Unit tests cover core deterministic logic
- **WHEN** the automated test suite is run via Gradle
- **THEN** it SHALL execute Spock specifications that cover deterministic helper and business-rule behavior including money conversion, CD origination date derivation, interest-rate lookup, and transaction generation for existing allowance flows

#### Scenario: Unit tests preserve existing domain assumptions
- **WHEN** the specifications exercise category/account naming and allowance logic
- **THEN** they SHALL reflect the current hard-coded assumptions used by the application, including the `Allowance` category, `Allowance Escrow` account, advanced account type names, and the existing CLI date-handling rules

### Requirement: The project SHALL provide simulated YNAB API integration tests using WireMock
The project SHALL include automated integration-style tests that simulate the YNAB REST API through WireMock HTTP fixtures so the CLI's current API interaction paths can be verified without live YNAB credentials or real transaction posting.

#### Scenario: Latest Fiores budget selection is validated without live API access
- **WHEN** the integration test suite simulates multiple YNAB budgets including more than one budget named `Fiores`
- **THEN** the test suite SHALL verify that the application selects the `Fiores` budget with the newest `last_modified_on` timestamp

#### Scenario: Category and account lookup behavior is validated through simulated API responses
- **WHEN** the integration tests simulate `/v1/budgets/{budgetId}/accounts` and `/v1/budgets/{budgetId}/categories` responses
- **THEN** the suite SHALL verify that the application resolves the `Allowance Escrow` account and flattens category-group responses by category name using the current YNAB response shape assumptions

#### Scenario: Bulk transaction posting is validated without posting to YNAB
- **WHEN** the integration tests simulate `/v1/budgets/{budgetId}/transactions/bulk`
- **THEN** they SHALL verify the request path and transaction body shape used by the application
- **AND** they SHALL complete without requiring a live `YNAB_ACCESS_TOKEN` or creating real YNAB transactions

### Requirement: Automated tests SHALL remain compatible with the current repo toolchain
The initial automated test implementation SHALL remain compatible with the repository's current Java 8, Groovy 2.4.x, and existing Gradle wrapper constraints rather than requiring the separate tech-stack-upgrade change first.

#### Scenario: Test suite runs on the current Gradle workflow
- **WHEN** a developer runs `./gradlew test` in the repository's current toolchain
- **THEN** the automated tests SHALL execute successfully without first upgrading Java, Groovy, or Gradle as part of this change

### Requirement: Test enablement SHALL preserve production runtime behavior
Any code changes introduced to make the script testable SHALL preserve the application's current CLI flags, environment-variable requirements, YNAB endpoint usage, and generated transaction semantics outside the test harness.

#### Scenario: Testing seams do not change runtime contract
- **WHEN** the application is run outside the automated tests with its normal inputs and flags
- **THEN** it SHALL continue to require `YNAB_ACCESS_TOKEN`, use the same YNAB API endpoint patterns, and preserve the current transaction-generation behavior aside from any changes explicitly covered by other approved specs


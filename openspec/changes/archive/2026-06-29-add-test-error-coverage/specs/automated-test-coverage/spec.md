## MODIFIED Requirements

### Requirement: The project SHALL provide Spock-based automated unit tests for core business logic
The project SHALL include automated Spock specifications that run under the existing Gradle workflow and validate the current Groovy CLI's core calculation, transaction-generation behavior, and error handling without calling the live YNAB API.

#### Scenario: Unit tests cover failure and boundary behavior
- **WHEN** helper and business-rule methods encounter invalid or missing inputs under the current domain model assumptions
- **THEN** the automated unit suite SHALL verify the current explicit failure behavior, including malformed CD category names, unsupported CD origination derivation, and other deterministic rule violations that should fail fast

### Requirement: The project SHALL provide simulated YNAB API integration tests using WireMock
The project SHALL include automated integration-style tests that simulate the YNAB REST API through WireMock HTTP fixtures so the CLI's current API interaction paths can be verified without live YNAB credentials or real transaction posting.

#### Scenario: Missing or invalid budget data is validated without live API access
- **WHEN** the integration suite simulates `/v1/budgets` responses that omit any matching `Fiores` budget or include malformed budget metadata
- **THEN** the suite SHALL verify the application’s explicit failure behavior rather than allowing a silent null path or unhelpful collection error

#### Scenario: Missing account or category data is validated through simulated API responses
- **WHEN** the integration tests simulate `/v1/budgets/{budgetId}/accounts` or `/v1/budgets/{budgetId}/categories` responses that omit required application data
- **THEN** the suite SHALL verify the current observable behavior for unresolved `Allowance Escrow`, `Allowance`, or other required categories/accounts

#### Scenario: Failed bulk transaction posting is validated without posting to YNAB
- **WHEN** the integration tests simulate `/v1/budgets/{budgetId}/transactions/bulk` failure responses
- **THEN** they SHALL verify that the application surfaces the YNAB client failure rather than masking it

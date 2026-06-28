## Why

The repository currently has no automated tests for either its transaction-calculation logic or its YNAB API integration paths. That makes changes risky in a money-moving CLI whose business rules, date logic, and YNAB naming assumptions are concentrated in one script.

This should be addressed now because test coverage is the top TODO item in the repo, and Justin has explicitly chosen a BDD-style Groovy test stack based on Spock plus OpenWire-backed HTTP integration coverage rather than deferring HTTP testing to a later change.

## What Changes

- Add an automated test strategy using Spock as the primary Groovy BDD testing framework.
- Add unit tests for core calculation, date, rate-selection, and transaction-generation behavior in `RecordAllowance`.
- Add API integration-style tests that simulate YNAB HTTP interactions through OpenWire so request/response behavior can be validated without live YNAB calls.
- Introduce only the smallest code seams needed to make the current script testable while preserving Java 8, the current Groovy/Gradle line, existing CLI flags, and real runtime behavior.
- Document the chosen testing approach and how to run it with the repo's Gradle workflow.

## Capabilities

### New Capabilities
- `automated-test-coverage`: Define and maintain automated unit and integration test coverage for transaction-calculation logic and YNAB API interaction paths using Spock and OpenWire-compatible test infrastructure.

### Modified Capabilities
- None.

## Impact

- Affected code: `build.gradle`, `src/main/groovy/RecordAllowance.groovy`, and new test sources under `src/test/groovy/`.
- Dependencies/tooling: add Spock and OpenWire-compatible testing dependencies that remain compatible with Java 8, Groovy 2.4.x, and the current Gradle wrapper.
- Runtime/environment: production runtime behavior continues to require `YNAB_ACCESS_TOKEN`; tests must avoid live YNAB credentials and real transaction posting.
- External dependency behavior: YNAB REST API contracts for budgets, accounts, categories, and bulk transactions will be exercised via simulated HTTP flows instead of live calls.
- OpenSpec artifacts: `openspec/changes/add-automated-tests/` proposal, design, specs, and tasks files for this change.

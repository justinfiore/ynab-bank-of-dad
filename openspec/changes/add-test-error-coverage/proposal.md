## Why

The first automated test pass established a basic happy-path test stack, but it is still light on failure-mode coverage. Justin specifically called out that the current integration coverage is thin, and the existing suite does not adequately exercise how the script behaves when YNAB data is missing, malformed, or otherwise inconsistent with the hard-coded assumptions in `RecordAllowance`.

Adding targeted unit and WireMock-backed integration error tests now will make the suite more trustworthy by proving the current behavior around missing budgets, missing accounts/categories, malformed CD category names, and failed transaction-posting flows.

## What Changes

- Add unit tests for current error and boundary behavior in pure/business-logic helpers.
- Add WireMock-backed integration-style tests for error cases in YNAB budget/account/category/transaction API interactions.
- Introduce small production guardrails only where needed to make failure behavior explicit and testable without changing the successful runtime contract.
- Keep compatibility with the current Java 8 / Groovy 2.4 / Gradle 4.2.1-era toolchain.

## Capabilities

### Modified Capabilities
- `automated-test-coverage`: expand the automated suite so it verifies failure behavior and missing-data handling in both unit and simulated API integration scenarios.

## Impact

- Affected code: `src/test/groovy/RecordAllowanceSpec.groovy`, `src/test/groovy/RecordAllowanceWireMockSpec.groovy`, and possibly narrow guardrails in `src/main/groovy/RecordAllowance.groovy`.
- Verification: `./gradlew test`
- OpenSpec artifacts: new change under `openspec/changes/add-test-error-coverage/`.

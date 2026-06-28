## 1. Add test infrastructure

- [x] 1.1 Update `build.gradle` with Java 8 / Groovy 2.4-compatible test dependencies for Spock and OpenWire.
- [x] 1.2 Create the initial `src/test/groovy/` test layout and shared test fixtures/helpers as needed.

## 2. Add Spock unit coverage

- [x] 2.1 Add unit specs for helper/date/rate-selection logic such as milliunit conversion, CD origination date derivation, and interest-rate lookup.
- [x] 2.2 Add unit specs for transaction-generation behavior, including advanced-account interest, allowance generation, offsetting transactions, and non-interest-bearing flows.

## 3. Enable safe testability seams

- [x] 3.1 Introduce the smallest production-code seams needed to test `RecordAllowance` without live YNAB dependencies.
- [x] 3.2 Verify those seams preserve existing CLI flags, environment-variable requirements, and runtime semantics.

## 4. Add OpenWire-backed API integration coverage

- [x] 4.1 Add integration-style specs that simulate YNAB budget selection behavior, including choosing the newest `Fiores` budget by `last_modified_on`.
- [x] 4.2 Add integration-style specs for account/category lookup and bulk transaction posting request shape.
- [x] 4.3 Ensure the integration suite runs without live credentials and without posting real transactions.

## 5. Verify and document

- [x] 5.1 Run `./gradlew test` and fix any compatibility issues with the current toolchain.
- [x] 5.2 Update repository documentation to explain the chosen Spock + OpenWire test approach and how to run the tests.
- [x] 5.3 Review the implementation against `openspec/changes/add-automated-tests/specs/automated-test-coverage/spec.md` before considering the change ready for apply.

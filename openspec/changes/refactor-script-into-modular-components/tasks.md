## 1. Lock down current behavior before refactoring

- [x] 1.1 Strengthen ordering and grouping assertions in the existing Spock tests so the current transaction sequencing behavior is explicitly verified before production code moves.
- [x] 1.2 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test` and commit the pre-refactor test-hardening changes as their own commit.

## 2. Establish the modular structure

- [x] 2.1 Inspect `RecordAllowance.groovy` and identify the concrete seams for calculation logic, YNAB data access, lookup helpers, and transaction assembly.
- [x] 2.2 Extract calculation logic into focused classes/modules under `src/main/groovy/` while keeping `RecordAllowance` as the executable entry point.
- [x] 2.3 Extract YNAB lookup/repository access into focused collaborators with explicit side-effect boundaries.
- [x] 2.4 Extract transaction assembly into focused helpers and introduce small value objects where they improve readability over loose Maps while preserving the YNAB request shape.
- [x] 2.5 Move remaining substantive business logic out of `RecordAllowance.groovy` so the entry point becomes a thin coordinator with explicit orchestration responsibilities.

## 3. Preserve behavior with focused tests

- [x] 3.1 Reshape or add Spock unit tests for the extracted collaborators so core calculations, lookups, and transaction-building behavior can be exercised without full CLI orchestration.
- [x] 3.2 Keep or update the broader integration-style coverage so YNAB request/response behavior and explicit bulk-post orchestration remain verified without live API access.

## 4. Verify, review, and document the refactor

- [x] 4.1 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test` and fix any regressions introduced by the refactor.
- [x] 4.2 If the new module boundaries change how developers understand or test the code, update `README.md` with concise structure/testing notes while keeping runtime guidance accurate.
- [x] 4.3 Run a dry-run-safe verification path if needed to confirm the refactor preserves the top-level CLI flow without posting real YNAB transactions.
- [x] 4.4 Perform a final branch review against the spec/design, address any findings, and keep commit history scoped for PR review.
- [ ] 4.5 Archive the OpenSpec change after the implementation, review, and verification work are complete.
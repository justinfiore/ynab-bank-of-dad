## 1. Expand unit error coverage

- [x] 1.1 Add deterministic unit tests for malformed or unsupported CD category names.
- [x] 1.2 Add unit tests for other current rule-boundary or missing-data behaviors that should fail fast with clear exceptions.

## 2. Expand integration error coverage

- [x] 2.1 Add WireMock-backed tests for `/v1/budgets` responses that do not contain a usable `Fiores` budget.
- [x] 2.2 Add WireMock-backed tests for missing required account/category data from YNAB responses.
- [x] 2.3 Add WireMock-backed tests for failed `/transactions/bulk` posting.

## 3. Tighten failure behavior only where needed

- [x] 3.1 Add minimal production guardrails if current failures are too implicit or low-signal to test well.
- [x] 3.2 Preserve the current successful runtime contract while improving failure clarity.

## 4. Verify

- [x] 4.1 Run `./gradlew test` and confirm the expanded suite passes on Java 8.

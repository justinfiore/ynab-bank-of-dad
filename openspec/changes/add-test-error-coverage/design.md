## Context

The current test suite proves the main happy paths but leaves several brittle areas untested. `RecordAllowance` relies on hard-coded budget names, account names, category names, and category-name parsing for CDs. It also assumes YNAB returns usable account/category/budget data. Today those failure paths are either untested or would fail with low-signal runtime exceptions.

## Goals / Non-Goals

**Goals:**
- Add unit coverage for deterministic failure cases and boundaries.
- Add WireMock-backed integration coverage for missing-data and API-failure scenarios.
- Improve production exceptions only where a small change materially improves testability and operator feedback.
- Preserve the current happy-path behavior and existing toolchain compatibility.

**Non-Goals:**
- Broader refactoring of `RecordAllowance`.
- CLI-level retry behavior or new recovery logic.
- Upgrading Java, Gradle, Groovy, or test libraries.

## Decisions

### Decision: Add explicit guardrails where current failure modes are too implicit
Some current error cases likely fail with low-signal exceptions such as indexing into an empty list when no matching budget exists. In those narrow cases, it is worth tightening the production code to throw clear `IllegalStateException`/`IllegalArgumentException` messages so the tests verify meaningful operator-visible behavior.

### Decision: Keep integration tests focused on observable API-failure behavior
The integration suite should test what happens when budget/account/category/transaction API responses are missing required data or fail, not the HTTP client library internals.

### Decision: Prefer behavior-level unit tests over implementation-coupled negative tests
For pure logic, the new unit tests should assert explicit exceptions and messages for malformed CD names or missing rate-table matches only when those behaviors are deterministic and owned by the script.

## Risks / Trade-offs

- Tightening failure messages may require a small production-code change, which slightly expands scope beyond test-only changes.
- Some existing behaviors may be awkward or inconsistent; tests may need to capture current behavior first, then justify a small cleanup.
- Over-testing library-thrown exceptions would be brittle; tests should focus on script-owned failures.

## Plan

1. Identify the highest-value current error paths in `RecordAllowance`.
2. Add unit tests for deterministic failure cases.
3. Add WireMock integration tests for missing `Fiores` budget, unresolved account/category data, and bulk-post failures.
4. Add minimal guardrails in production code where current exceptions are too implicit.
5. Verify with `./gradlew test`.

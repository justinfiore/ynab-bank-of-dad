## Context

`RecordAllowance` is a single Groovy script that mixes CLI parsing, HTTP client setup, YNAB API access, budget/account/category lookup, and transaction-calculation rules in one class. The repo currently has no automated tests and no test dependencies configured in `build.gradle`.

The requested change must preserve the current Java 8 + Groovy 2.4.x + Gradle 4.2.1-era toolchain and should not combine with the separate tech-stack-upgrade TODO. Justin has explicitly chosen Spock for BDD-style testing and WireMock immediately for simulating YNAB API interactions rather than postponing HTTP coverage.

## Goals / Non-Goals

**Goals:**
- Add an automated test stack that works on the repo's current Java/Groovy/Gradle versions.
- Cover core pure/business logic with fast Spock specs.
- Cover YNAB API interaction paths with simulated HTTP integration tests using OpenWire.
- Introduce the smallest practical refactorings needed to test the current script safely.
- Keep verification runnable through `./gradlew test`.

**Non-Goals:**
- Upgrading Java, Gradle, Groovy, or the main runtime stack.
- Redesigning the application into a full multi-class architecture.
- Changing CLI semantics, category/account naming conventions, transaction formulas, or YNAB request contracts except as needed to expose them to tests.
- Exercising tests against live YNAB credentials or posting real transactions.

## Decisions

### Decision: Use Spock as both the specification framework and first-line mocking/stubbing tool
Spock is the best fit for the requested Groovy BDD-style test suite and is compatible with the current Groovy line when pinned to the appropriate version. Its native mocks/stubs keep the initial dependency footprint smaller than adding a second mocking framework.

Alternatives considered:
- JUnit-based tests. Rejected because they do not match the requested Groovy BDD style as well as Spock.
- Adding a separate mocking library immediately. Rejected unless later gaps appear, because Spock is likely sufficient for this repo's first test wave.

### Decision: Add WireMock-backed HTTP simulation in the first testing change
The current code's most important external behavior is how it reads budgets/accounts/categories and posts bulk transactions to YNAB. Those paths should be covered in the first testing increment rather than deferred. WireMock-backed simulation gives a way to verify request/response behavior without requiring real credentials or real YNAB side effects.

Alternatives considered:
- Unit tests only first, HTTP later. Rejected because the user explicitly requested OpenWire immediately.
- Live API tests. Rejected because they would require real credentials and could create or mutate budgeting data.

### Decision: Create narrow test seams instead of broad architecture changes
The current constructor immediately configures the HTTP client and resolves the latest budget ID, which makes isolated testing awkward. The implementation should add the smallest seam necessary, such as injectable client/base URI/startup collaborators or helper methods that can be overridden in tests, rather than rewriting the entire script.

Alternatives considered:
- Full extraction into service classes before testing. Rejected because it broadens scope and mixes refactoring with test enablement.
- No production refactor at all. Rejected because HTTP and constructor-side-effect testing would remain unnecessarily brittle.

### Decision: Split tests into logic specs and HTTP integration specs
Fast logic specs should cover money conversion, CD date handling, rate selection, and transaction generation. Separate HTTP-focused specs should validate newest-budget selection, account/category lookup, and bulk transaction posting against simulated YNAB responses.

Alternatives considered:
- One monolithic spec file. Rejected because it would blur concerns and become hard to maintain.

## Risks / Trade-offs

- [Old toolchain dependency friction] → Mitigation: pin Spock/WireMock-compatible versions that match Groovy 2.4 and Java 8, and verify with `./gradlew test`.
- [Testing seams accidentally alter runtime behavior] → Mitigation: keep refactors narrow and preserve existing CLI/env/API behavior in specs.
- [HTTP simulation may not match YNAB closely enough] → Mitigation: model fixtures around the exact endpoints currently used in `RecordAllowance` and validate request paths/body shapes explicitly.
- [Single-script design still limits elegance of tests] → Mitigation: accept some pragmatic seams now and leave broader decomposition for a later refactor/modernization change.

## Migration Plan

1. Add test dependencies and source layout in Gradle.
2. Add Spock unit specs for pure logic and transaction generation.
3. Introduce minimal production seams required for stable testing.
4. Add WireMock-based HTTP integration specs for the YNAB interaction paths.
5. Verify with `./gradlew test` and update documentation for running tests.

Rollback is straightforward: revert the branch if the test-stack addition proves incompatible with the current toolchain.

## Open Questions

- Is a base-URI injection seam sufficient, or will tests also need budget/client initialization to be decoupled from the constructor?
- Should dry-run behavior receive dedicated CLI-level tests in this change, or is transaction-generation plus non-posting verification enough for the first slice?

## Compatibility Note

Current tool-based dependency research confirms `org.spockframework:spock-core:1.3-groovy-2.4` as a compatible fit for this repo's Groovy 2.4 / Java 8 stack. Justin clarified that WireMock was the intended HTTP mocking library, and `com.github.tomakehurst:wiremock:1.58` fits this repo's stack and YNAB API testing needs. The dependency choice is verified by running `./gradlew test`.

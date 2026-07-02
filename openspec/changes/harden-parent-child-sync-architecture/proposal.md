## Why

The initial `support-parent-child-budget-syncing` implementation added the standalone syncer, SQLite state, docs, and WireMock coverage, but a follow-up review found several correctness, architecture, and spec-alignment gaps that should be resolved before the feature is considered merge-ready.

The most important issue is recovery safety: failed child sync work is currently recorded in a way that can make later runs treat it as already processed, and the parent transaction cursor can advance even when one child target failed. That creates a risk of silently losing child-budget sync work after transient API failures, missing child account configuration, or child-token/auth problems.

The implementation also still uses the older undocumented `/v1/budgets` YNAB API path family. The documented forward-looking API uses `/v1/plans`, so the repository should migrate its YNAB calls to `/plans` while preserving behavior.

Finally, the code is split across files but still lacks meaningful package/module organization and cohesive service boundaries. The review identified remaining architecture work around planning, child application, run coordination, state abstractions, SQLite constraints, and real sync logging.

## What Changes

- Correct sync retry/idempotency semantics so failed child work is recorded for audit but does not suppress future retry.
- Correct cursor advancement and run-status behavior so parent high-water marks are not advanced past failed child work unless the retry design can prove recovery safety.
- Migrate `YnabBudgetRepository.groovy` from `/v1/budgets...` endpoints to documented `/v1/plans...` endpoints, including transaction, account, category, user/plan list, and post paths where applicable.
- Update OpenSpec artifacts and repo guidance that still reference `/budgets`, `./gradlew test`, or stale token/logging caveats.
- Implement real sync logging configuration with Logback rolling-file behavior using `sync.logging.filePath`, `level`, `maxHistory`, and `maxFileSizeMb`, or otherwise align the spec and docs if a narrower logging contract is intentionally chosen.
- Reorganize sync-related code into packages and cohesive classes/services instead of keeping orchestration, planning, applying, payload construction, state, and CLI logic bundled in one class/default package.
- Add a state abstraction and strengthen SQLite behavior with foreign-key enforcement and meaningful schema constraints where practical.
- Normalize target budget identity storage so columns named `target_budget_id` contain IDs, not budget names.
- Reassess missing unit and integration tests after the refactor/hardening work lands, then add the missing coverage against the final architecture.

## Scope

In scope:
- `src/main/groovy/**` package reorganization and sync architecture refactor
- YNAB endpoint path migration from `/budgets` to `/plans`
- SQLite state-store correctness fixes and schema hardening
- sync logging implementation and tests
- update `AGENTS.md`, OpenSpec artifacts, docs, and tests as needed
- coverage analysis after refactor plus the resulting unit/WireMock/SQLite tests

Out of scope:
- New security redaction/file-permission work from review findings 13, 14, 15, and 17
- Changing the user-visible allowance/business rules unrelated to endpoint-path migration and package refactor
- Live YNAB verification with real credentials
- Merging the PR without explicit approval

## Impact

Likely touched areas:
- `src/main/groovy/YnabBudgetRepository.groovy`
- `src/main/groovy/YnabHttpClient.groovy` if endpoint migration requires helper naming/doc updates
- `src/main/groovy/ParentChildBudgetSyncer.groovy` and extracted sync collaborators
- `src/main/groovy/SyncStateStore.groovy` and related state interfaces/models
- `src/main/resources/logback.groovy` or sync logging bootstrap classes
- `src/test/groovy/*Syncer*Spec.groovy`, repository specs, SQLite integration specs, and new focused service specs
- `build.gradle` if package/main-class/test wiring changes
- `AGENTS.md`, `README.md`, `QUICK_START.md`, `CONFIGURATION.md`, and OpenSpec change artifacts

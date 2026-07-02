## Context

The parent/child syncer has moved from initial implementation into hardening. The code now has a standalone entry point, SQLite state, documentation, and WireMock integration tests, but the review identified several merge-blocking issues:

1. `SyncStateStore.hasAppliedIdempotencyKey()` checks for a mapping row rather than a successful applied transaction row. Failed sync attempts can therefore suppress future retries.
2. `ParentChildBudgetSyncer.runOnce()` advances the transaction cursor and marks the run succeeded even when `applyPlans()` caught one or more child failures.
3. `SyncLoggingBootstrap` only creates/appends a bootstrap file; it does not wire Logback rolling-file logging despite the spec requiring it.
4. The design artifacts mention documented `/plans` API paths, but implementation still uses older `/budgets` paths.
5. The syncer is split across files but not organized into packages/services, and `ParentChildBudgetSyncer` still owns too much orchestration and domain logic.
6. SQLite constraints and target-budget identity semantics are weaker than the design intended.
7. Some repo guidance is stale and still describes old token/logging concerns or old test commands.

The user explicitly confirmed that `/budgets` is the old working path family and `/plans` is the currently documented API. The hardening implementation should therefore migrate to `/plans` for future-proofing.

## Goals / Non-Goals

**Goals:**
- Make failed child sync work retryable and auditable.
- Prevent cursor advancement past unresolved child failures.
- Represent run status accurately (`succeeded`, `failed`, `partial`, etc.) based on final child target outcomes.
- Use documented YNAB `/v1/plans` endpoints in repository methods and tests.
- Organize code into packages with cohesive sync planning, payload, applying, coordination, state, config, and YNAB boundary classes.
- Implement actual rolling-file sync logging or explicitly narrow the accepted logging contract in specs/docs.
- Harden SQLite state behavior with applied-success duplicate checks, FK enforcement, practical constraints, and consistent budget identity storage.
- Update stale OpenSpec/docs/AGENTS guidance.
- Re-run test coverage analysis after architectural changes and then add the resulting missing unit and integration tests.

**Non-Goals:**
- Implementing the previously reviewed logging redaction, arbitrary-path restrictions, failure-message sanitization, or POSIX file-permission hardening findings unless they naturally fall out of the chosen design.
- Changing allowance calculations, interest rules, or child mapping business semantics beyond what is needed to preserve existing behavior during refactor.
- Verifying against live YNAB credentials.

## Decisions

### 1. Duplicate detection is based on successful application, not planning

A `sync_mappings` row means work was planned or attempted. It does not mean work reached the child budget. Duplicate prevention must check a successful applied state, such as an `applied_transactions` row with `status = 'applied'` for the idempotency key.

Failed attempts may remain in `applied_transactions` for audit, but must not block later retry.

### 2. Partial failures do not advance transaction cursor

If any child target fails during a cycle, the run should be marked `partial` or `failed` according to the final outcome, and `transactions.last_server_knowledge` should not be advanced for that cycle. This preserves the ability to re-read source data and retry unresolved child work.

A future enhancement could track per-source-event cursor safety more granularly, but this hardening change should prefer conservative correctness.

### 3. YNAB repository uses `/plans` endpoints

Repository methods should call documented paths such as:

- `GET /v1/plans`
- `GET /v1/plans/{plan_id}/accounts`
- `GET /v1/plans/{plan_id}/categories`
- `GET /v1/plans/{plan_id}/transactions`
- `GET /v1/plans/{plan_id}/money_movements`
- `POST /v1/plans/{plan_id}/transactions/bulk` if supported by the documented contract, otherwise the apply step must verify the documented equivalent and update design/tests accordingly.

Tests should assert the `/plans` paths so future changes do not regress back to deprecated `/budgets` paths.

### 4. Sync architecture should be package-organized and service-oriented

Use packages rather than default-package classes. Preserve CLI compatibility by updating Gradle `mainClass`/`runSyncer` wiring and scripts as needed.

Recommended structure:

```text
ynabbankofdad/
  allowance/
  config/
  ynab/
  sync/
  sync/model/
  sync/state/
```

At minimum, extract:
- planning logic into a pure planner service
- YNAB payload creation/import-id logic into a payload factory
- child application/retry/state write behavior into an applier service
- run lifecycle/cursor policy into a coordinator or result object

### 5. SQLite should enforce the contract it relies on

Enable `PRAGMA foreign_keys = ON` per connection. Add practical constraints for status/direction/event type and source event anchors where compatible with the current migration approach. Store actual target budget IDs in columns named `target_budget_id`; use a separate budget-name field or planning-only model property where the name is still needed for lookup.

### 6. Logging configuration must be real or the spec must be narrowed

Since the existing spec requires rolling file logs, implement Logback rolling-file appenders from `SyncLoggingConfig` and test that actual sync log lines are written to the configured file. Use `maxHistory`, `maxFileSizeMb`, and `level` instead of writing only a bootstrap marker.

### 7. Test coverage analysis happens after refactor

The architecture refactor will change where tests should live. Do not blindly add every previously listed missing test before the code is reorganized. First implement findings 1–12, then reassess coverage against the resulting package/service boundaries and add missing unit, WireMock integration, and SQLite integration tests.

## Risks / Trade-offs

- Package migration can be noisy in Groovy default-package projects. Mitigation: move incrementally, update tests/imports, and keep commits scoped.
- Conservative cursor behavior may re-read more parent data after partial failures. Mitigation: idempotency makes re-reading safe, and correctness is more important than avoiding reads.
- SQLite schema hardening can require test fixture updates. Mitigation: update real-SQLite integration tests alongside schema changes.
- `/plans` endpoint behavior should be verified against docs/WireMock fixtures. If a specific old `/budgets` endpoint has no documented `/plans` equivalent, update the proposal/design before coding around it.

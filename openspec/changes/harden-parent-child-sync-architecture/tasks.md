## 1. Correct sync recovery semantics

- [x] 1.1 Replace mapping-existence duplicate checks with successful-applied-state checks so failed child attempts remain retryable.
- [x] 1.2 Introduce explicit child-apply/run result modeling so `ParentChildBudgetSyncer` can distinguish succeeded, partial, failed, skipped, and duplicate outcomes.
- [x] 1.3 Prevent `transactions.last_server_knowledge` cursor advancement when any derived child work failed or remains unresolved in the current cycle.
- [x] 1.4 Mark sync runs with accurate status (`succeeded`, `partial`, or `failed`) and failure summaries based on final child-target outcomes.

## 2. Migrate YNAB API usage to documented `/plans` endpoints

- [x] 2.1 Update `YnabBudgetRepository.groovy` methods and naming to use `/v1/plans` and `/v1/plans/{plan_id}/...` paths instead of `/v1/budgets` paths.
- [x] 2.2 Update repository and WireMock tests to assert `/plans` paths for plan listing, account/category/transaction/money-movement reads, and transaction posting.
- [x] 2.3 Update docs/OpenSpec/AGENTS references that still describe `/budgets` as the active API path.

## 3. Implement real sync logging

- [x] 3.1 Replace bootstrap-only file creation with Logback rolling-file logging configured from `SyncLoggingConfig`.
- [x] 3.2 Use `filePath`, `level`, `maxHistory`, and `maxFileSizeMb` in actual logging configuration.
- [x] 3.3 Add automated tests proving actual sync log lines are written to the configured file and invalid logging config still fails fast.

## 4. Refactor internal architecture and packages

- [x] 4.1 Move production classes out of the default package into appropriate packages such as `ynabbankofdad.allowance`, `ynabbankofdad.config`, `ynabbankofdad.ynab`, `ynabbankofdad.sync`, `ynabbankofdad.sync.model`, and `ynabbankofdad.sync.state`.
- [x] 4.2 Update Gradle application/runSyncer main-class wiring, wrapper scripts, tests, and docs for packaged class names.
- [x] 4.3 Extract pure sync planning into a focused planner service with unit tests.
- [x] 4.4 Extract child transaction payload/import-id creation into a focused factory with unit tests.
- [x] 4.5 Extract child apply/retry/state-write behavior into a focused applier service with unit and WireMock-backed integration tests.
- [x] 4.6 Extract run lifecycle/cursor advancement policy into a focused coordinator/result model with unit tests.

## 5. Harden SQLite state behavior

- [x] 5.1 Add a state abstraction/interface where useful so orchestration can be unit tested without SQLite.
- [x] 5.2 Enable SQLite foreign-key enforcement per connection.
- [x] 5.3 Add practical schema constraints for event types, statuses, directions, and source anchors where compatible with the current schema.
- [x] 5.4 Store actual target budget IDs in `target_budget_id`; avoid writing budget names to ID columns.
- [x] 5.5 Update real-SQLite integration tests for the hardened schema, FK enforcement, and retry-safe idempotency semantics.

## 6. Update stale guidance and verification tasks

- [x] 6.1 Update `AGENTS.md` to remove stale notes about checked-in token material and access-token logging if those issues are no longer present.
- [x] 6.2 Update OpenSpec task/design text that still says `./gradlew test` when the real verification command is `./gradlew testAll`, or rewire Gradle so `test` runs the intended unit suite.
- [x] 6.3 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew testAll` after the refactor/hardening changes.
- [x] 6.4 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist` after package/main-class updates.

## 7. Reassess and complete tests after implementation

- [x] 7.1 Create a post-refactor test-gap analysis document after findings 1–12 are implemented and package/service boundaries are final. The analysis SHALL map each known gap below to the final class/service that should own the test, classify it as unit, WireMock integration, SQLite integration, or build/documentation verification, and identify any obsolete or superseded gaps.
- [x] 7.2 Turn the post-refactor analysis into a concrete test plan before writing the tests. The plan SHALL list test names, target files, fixture/state setup, expected assertions, and whether each test should be written before or after any remaining code changes.
- [x] 7.3 Known critical gaps to analyze and plan:
  - [x] 7.3.1 Failed child transaction post is retried on a later run instead of being skipped as already mapped.
  - [x] 7.3.2 Parent transaction cursor does not advance when a child target partially fails.
  - [x] 7.3.3 Missing child account is retried after the account becomes available.
- [x] 7.4 Known high-priority WireMock integration gaps to analyze and plan:
  - [x] 7.4.1 Child auth failure scenarios, including 401/403 during child plan discovery, child account lookup, or child transaction posting.
  - [x] 7.4.2 Parent API failure scenarios, including parent plan list, parent transaction read, parent category read, and parent money-movement read failures.
  - [x] 7.4.3 No qualifying work scenario where only unapproved, unmapped, or outside-lookback events are returned and no child post/state mapping is created.
  - [x] 7.4.4 Single-child money movement scenarios for movement into and out of one mapped child category.
- [x] 7.5 Known medium-priority behavior gaps to analyze and plan:
  - [x] 7.5.1 Money movement date fallback and lookback behavior, including `moved_at`, `month`, fallback date, and outside-lookback filtering.
  - [x] 7.5.2 Child post response variants, including `data.bulk.transaction_ids` and `data.transactions` response shapes.
  - [x] 7.5.3 Import ID determinism, uniqueness, sanitization, and length/format constraints.
  - [x] 7.5.4 `fromConfig` environment-variable validation for missing/blank parent and child token variables plus CLI sync-state override behavior.
  - [x] 7.5.5 Actual logging behavior after the logging implementation, including configured file output, level application, and invalid path/config failure behavior.
  - [x] 7.5.6 Default Gradle `test` task behavior, either proving it runs the intended suite or documenting/validating `testAll` as the required command.
- [x] 7.6 Implement the approved missing unit tests identified by the post-refactor test plan.
- [x] 7.7 Implement the approved missing WireMock integration tests identified by the post-refactor test plan.
- [x] 7.8 Implement the approved missing SQLite integration tests identified by the post-refactor test plan.
- [x] 7.9 Implement the approved build/documentation verification tests identified by the post-refactor test plan.
- [x] 7.10 Record the final coverage analysis, test plan, implemented tests, intentionally deferred gaps, and verification results in this change’s notes before applying or archiving.

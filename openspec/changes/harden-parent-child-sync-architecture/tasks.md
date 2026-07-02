## 1. Correct sync recovery semantics

- [ ] 1.1 Replace mapping-existence duplicate checks with successful-applied-state checks so failed child attempts remain retryable.
- [ ] 1.2 Introduce explicit child-apply/run result modeling so `ParentChildBudgetSyncer` can distinguish succeeded, partial, failed, skipped, and duplicate outcomes.
- [ ] 1.3 Prevent `transactions.last_server_knowledge` cursor advancement when any derived child work failed or remains unresolved in the current cycle.
- [ ] 1.4 Mark sync runs with accurate status (`succeeded`, `partial`, or `failed`) and failure summaries based on final child-target outcomes.

## 2. Migrate YNAB API usage to documented `/plans` endpoints

- [ ] 2.1 Update `YnabBudgetRepository.groovy` methods and naming to use `/v1/plans` and `/v1/plans/{plan_id}/...` paths instead of `/v1/budgets` paths.
- [ ] 2.2 Update repository and WireMock tests to assert `/plans` paths for plan listing, account/category/transaction/money-movement reads, and transaction posting.
- [ ] 2.3 Update docs/OpenSpec/AGENTS references that still describe `/budgets` as the active API path.

## 3. Implement real sync logging

- [ ] 3.1 Replace bootstrap-only file creation with Logback rolling-file logging configured from `SyncLoggingConfig`.
- [ ] 3.2 Use `filePath`, `level`, `maxHistory`, and `maxFileSizeMb` in actual logging configuration.
- [ ] 3.3 Add automated tests proving actual sync log lines are written to the configured file and invalid logging config still fails fast.

## 4. Refactor internal architecture and packages

- [ ] 4.1 Move production classes out of the default package into appropriate packages such as `ynabbankofdad.allowance`, `ynabbankofdad.config`, `ynabbankofdad.ynab`, `ynabbankofdad.sync`, `ynabbankofdad.sync.model`, and `ynabbankofdad.sync.state`.
- [ ] 4.2 Update Gradle application/runSyncer main-class wiring, wrapper scripts, tests, and docs for packaged class names.
- [ ] 4.3 Extract pure sync planning into a focused planner service with unit tests.
- [ ] 4.4 Extract child transaction payload/import-id creation into a focused factory with unit tests.
- [ ] 4.5 Extract child apply/retry/state-write behavior into a focused applier service with unit and WireMock-backed integration tests.
- [ ] 4.6 Extract run lifecycle/cursor advancement policy into a focused coordinator/result model with unit tests.

## 5. Harden SQLite state behavior

- [ ] 5.1 Add a state abstraction/interface where useful so orchestration can be unit tested without SQLite.
- [ ] 5.2 Enable SQLite foreign-key enforcement per connection.
- [ ] 5.3 Add practical schema constraints for event types, statuses, directions, and source anchors where compatible with the current schema.
- [ ] 5.4 Store actual target budget IDs in `target_budget_id`; avoid writing budget names to ID columns.
- [ ] 5.5 Update real-SQLite integration tests for the hardened schema, FK enforcement, and retry-safe idempotency semantics.

## 6. Update stale guidance and verification tasks

- [ ] 6.1 Update `AGENTS.md` to remove stale notes about checked-in token material and access-token logging if those issues are no longer present.
- [ ] 6.2 Update OpenSpec task/design text that still says `./gradlew test` when the real verification command is `./gradlew testAll`, or rewire Gradle so `test` runs the intended unit suite.
- [ ] 6.3 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew testAll` after the refactor/hardening changes.
- [ ] 6.4 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist` after package/main-class updates.

## 7. Reassess and complete tests after implementation

- [ ] 7.1 Create a post-refactor test-gap analysis document after findings 1–12 are implemented and package/service boundaries are final. The analysis SHALL map each known gap below to the final class/service that should own the test, classify it as unit, WireMock integration, SQLite integration, or build/documentation verification, and identify any obsolete or superseded gaps.
- [ ] 7.2 Turn the post-refactor analysis into a concrete test plan before writing the tests. The plan SHALL list test names, target files, fixture/state setup, expected assertions, and whether each test should be written before or after any remaining code changes.
- [ ] 7.3 Known critical gaps to analyze and plan:
  - [ ] 7.3.1 Failed child transaction post is retried on a later run instead of being skipped as already mapped.
  - [ ] 7.3.2 Parent transaction cursor does not advance when a child target partially fails.
  - [ ] 7.3.3 Missing child account is retried after the account becomes available.
- [ ] 7.4 Known high-priority WireMock integration gaps to analyze and plan:
  - [ ] 7.4.1 Child auth failure scenarios, including 401/403 during child plan discovery, child account lookup, or child transaction posting.
  - [ ] 7.4.2 Parent API failure scenarios, including parent plan list, parent transaction read, parent category read, and parent money-movement read failures.
  - [ ] 7.4.3 No qualifying work scenario where only unapproved, unmapped, or outside-lookback events are returned and no child post/state mapping is created.
  - [ ] 7.4.4 Single-child money movement scenarios for movement into and out of one mapped child category.
- [ ] 7.5 Known medium-priority behavior gaps to analyze and plan:
  - [ ] 7.5.1 Money movement date fallback and lookback behavior, including `moved_at`, `month`, fallback date, and outside-lookback filtering.
  - [ ] 7.5.2 Child post response variants, including `data.bulk.transaction_ids` and `data.transactions` response shapes.
  - [ ] 7.5.3 Import ID determinism, uniqueness, sanitization, and length/format constraints.
  - [ ] 7.5.4 `fromConfig` environment-variable validation for missing/blank parent and child token variables plus CLI sync-state override behavior.
  - [ ] 7.5.5 Actual logging behavior after the logging implementation, including configured file output, level application, and invalid path/config failure behavior.
  - [ ] 7.5.6 Default Gradle `test` task behavior, either proving it runs the intended suite or documenting/validating `testAll` as the required command.
- [ ] 7.6 Implement the approved missing unit tests identified by the post-refactor test plan.
- [ ] 7.7 Implement the approved missing WireMock integration tests identified by the post-refactor test plan.
- [ ] 7.8 Implement the approved missing SQLite integration tests identified by the post-refactor test plan.
- [ ] 7.9 Implement the approved build/documentation verification tests identified by the post-refactor test plan.
- [ ] 7.10 Record the final coverage analysis, test plan, implemented tests, intentionally deferred gaps, and verification results in this change’s notes before applying or archiving.

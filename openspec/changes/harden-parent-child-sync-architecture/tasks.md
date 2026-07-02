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

- [ ] 7.1 Redo test coverage analysis after findings 1–12 are implemented and the package/service boundaries are final.
- [ ] 7.2 Add the missing unit tests identified by the post-refactor coverage analysis.
- [ ] 7.3 Add the missing WireMock integration tests identified by the post-refactor coverage analysis, especially retry, partial failure, child auth failure, parent API failure, no-work, and single-child money-movement scenarios if still applicable.
- [ ] 7.4 Add the missing SQLite integration tests identified by the post-refactor coverage analysis.
- [ ] 7.5 Record the final coverage analysis and verification results in this change’s notes before applying or archiving.

# Post-refactor Test Gap Analysis

## Refactored code owners

- `ChildSyncPlanner`: pure transaction/subtransaction/money-movement planning from parent events and category mappings.
- `ChildTransactionPayloadFactory`: child transaction payload, import ID generation, and created-transaction response parsing.
- `ChildSyncApplier`: child budget/account resolution, duplicate checks, child posting, success/failure state writes, and retry-safe failed attempt recording.
- `SyncRunCoordinator`: run completion status and transaction cursor advancement policy.
- `SyncStateStore`: SQLite schema, FK/constraint enforcement, mappings, applied-state duplicate detection, and cursors.
- `YnabBudgetRepository`: documented `/v1/plans` HTTP path contract and YNAB response mapping.
- `SyncLoggingBootstrap`: Logback rolling file configuration from sync logging config.
- `ParentChildBudgetSyncer.fromConfig`: environment-token validation and CLI state override wiring.

## Gap mapping and final test plan/status

| Gap | Final owner | Test type | Target test file | Final status |
|---|---|---:|---|---|
| Failed child transaction post is retried on a later run | `ChildSyncApplier` + top-level orchestrator | WireMock + SQLite integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented as `failed child transaction post is retried on a later run`; asserts failed then applied rows and cursor advancement only after retry succeeds. |
| Cursor does not advance on partial child failure | `SyncRunCoordinator` + top-level orchestrator | Unit + WireMock integration | `SyncRunCoordinatorSpec`, `ParentChildBudgetSyncerWireMockSpec` | Implemented in coordinator unit test and WireMock failure/missing-account/auth assertions; partial runs leave cursor null. |
| Missing child account retries after account appears | `ChildSyncApplier` + top-level orchestrator | WireMock + SQLite integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented as `missing child account is retried after the account becomes available`. |
| Child auth failure during discovery/account/post | `YnabBudgetRepository`/`ChildSyncApplier` | WireMock integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented with child plan discovery 403, child account lookup 401, and child transaction post 403 cases; each asserts failed audit row, other child isolation, and no cursor advancement. |
| Parent API failures | `ParentChildBudgetSyncer` orchestration + `YnabBudgetRepository` | WireMock integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented for parent plan discovery, category read, transaction read, and money-movement read failures; each asserts no child posts and no cursor advancement. |
| No qualifying work | `ChildSyncPlanner` + top-level orchestrator | WireMock integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented; asserts no child account/post/mapping/applied rows and successful no-op cursor behavior. |
| Single-child money movement | `ChildSyncPlanner` + top-level orchestrator | Unit/WireMock integration | `ParentChildBudgetSyncerSpec`, `ParentChildBudgetSyncerWireMockSpec` | Implemented via WireMock integration covering movement into and out of one mapped child category with sign/payee/memo assertions; existing planner unit test still covers two-child fan-out. |
| Money movement date fallback/lookback | `YnabBudgetRepository` | Unit-ish WireMock repository integration | `YnabBudgetRepositorySpec` | Implemented for `moved_at` lookback filtering plus `month` fallback and current-date fallback. |
| Child post response variants | `ChildTransactionPayloadFactory` | Unit | `ChildTransactionPayloadFactorySpec` | Implemented for `data.bulk.transaction_ids`, `data.transactions`, and null response. |
| Import ID determinism/uniqueness/sanitization | `ChildTransactionPayloadFactory` | Unit | `ChildTransactionPayloadFactorySpec` | Implemented for deterministic IDs, child-key uniqueness, alphanumeric sanitization, and bounded suffix/overall length. |
| Env var validation and sync state override | `ParentChildBudgetSyncer.fromConfig` | Unit | `ParentChildBudgetSyncerSpec` | Implemented via injectable environment map and CLI override assertion. |
| Actual logging behavior | `SyncLoggingBootstrap` | Unit/integration | `SyncLoggingBootstrapSpec` | Implemented file-output/appender/level assertion. |
| Default Gradle `test` behavior | `build.gradle` | Build verification | command line | Implemented with `test` as the unit/spec task; removed the redundant custom `unitTest` task; verified `./gradlew tasks --all` no longer lists `unitTest` and `./gradlew test` executes unit tests. |
| SQLite FK/constraints | `SyncStateStore` | SQLite integration | `SyncStateStoreIntegrationSpec` | Implemented explicit invalid run status, event type, mapping direction, and FK rejection assertions. |

## Deferred/remaining notes

All gaps identified in this analysis now have automated coverage. Security redaction/path/permission findings 13, 14, 15, and 17 remain intentionally out of scope per user instruction.

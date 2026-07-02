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

## Gap mapping and test plan

| Gap | Final owner | Test type | Target test file | Plan/status |
|---|---|---:|---|---|
| Failed child transaction post is retried on a later run | `ChildSyncApplier` + top-level orchestrator | WireMock + SQLite integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented as `failed child transaction post is retried on a later run`; asserts failed then applied rows and cursor advancement only after retry succeeds. |
| Cursor does not advance on partial child failure | `SyncRunCoordinator` + top-level orchestrator | Unit + WireMock integration | `SyncRunCoordinatorSpec`, `ParentChildBudgetSyncerWireMockSpec` | Implemented in coordinator unit test and existing failure/missing-account WireMock assertions; partial runs leave cursor null. |
| Missing child account retries after account appears | `ChildSyncApplier` + top-level orchestrator | WireMock + SQLite integration | `ParentChildBudgetSyncerWireMockSpec` | Implemented as `missing child account is retried after the account becomes available`. |
| Child auth failure during discovery/account/post | `YnabBudgetRepository`/`ChildSyncApplier` | WireMock integration | `ParentChildBudgetSyncerWireMockSpec` | Add child 401/403 tests after refactor; should assert failed audit row, other child isolation, and no cursor advancement. |
| Parent API failures | `ParentChildBudgetSyncer` orchestration + `YnabBudgetRepository` | WireMock integration | `ParentChildBudgetSyncerWireMockSpec` | Add parent plan/category/transaction/money-movement failure tests; assert run fails/no child posts/no cursor when run row exists. |
| No qualifying work | `ChildSyncPlanner` + top-level orchestrator | WireMock integration | `ParentChildBudgetSyncerWireMockSpec` | Add no-work test; assert no child account/post/mapping/applied rows and successful run/cursor behavior. |
| Single-child money movement | `ChildSyncPlanner` + top-level orchestrator | Unit/WireMock integration | `ParentChildBudgetSyncerSpec`, `ParentChildBudgetSyncerWireMockSpec` | Existing planner test covers two-child fan-out; add focused single-child in/out movement assertions if final behavior remains distinct. |
| Money movement date fallback/lookback | `YnabBudgetRepository` | Unit-ish WireMock repository integration | `YnabBudgetRepositorySpec` | Existing moved_at/lookback coverage exists; add `month` fallback and now fallback assertions. |
| Child post response variants | `ChildTransactionPayloadFactory` | Unit | `ChildTransactionPayloadFactorySpec` | Implemented for `data.bulk.transaction_ids`, `data.transactions`, and null response. |
| Import ID determinism/uniqueness/sanitization | `ChildTransactionPayloadFactory` | Unit | `ChildTransactionPayloadFactorySpec` | Implemented for deterministic and child-key-different IDs; sanitization/length may be extended if stricter limits become required. |
| Env var validation and sync state override | `ParentChildBudgetSyncer.fromConfig` | Unit | `ParentChildBudgetSyncerSpec` | Implemented via injectable environment map and CLI override assertion. |
| Actual logging behavior | `SyncLoggingBootstrap` | Unit/integration | `SyncLoggingBootstrapSpec` | Implemented file-output/appender/level assertion. |
| Default Gradle `test` behavior | `build.gradle` | Build verification | command line | Implemented by re-enabling `test` as unit-test task; verified `./gradlew test` executes unit tests. |
| SQLite FK/constraints | `SyncStateStore` | SQLite integration | `SyncStateStoreIntegrationSpec` | Schema updated; add explicit invalid-state rejection assertions. |

## Deferred/remaining notes

The high-priority auth/parent/no-work/single-movement and SQLite constraint tests should be added after the initial hardening pass because the refactor now gives them stable owners. None are obsolete. Security redaction/path/permission findings 13, 14, 15, and 17 remain intentionally out of scope per user instruction.

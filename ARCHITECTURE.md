# Architecture

This document explains how YNAB Bank of Dad is organized, how its two command-line workflows execute, and how they share infrastructure. The [Parent Transaction Reconciliation](#parent-transaction-reconciliation) section is intentionally detailed so it can be used as a review map for the reconciliation implementation.

For operator-facing behavior and rollout guidance, also read:

- [CONFIGURATION.md](CONFIGURATION.md)
- [QUICK_START.md](QUICK_START.md)
- [PARENT_TRANSACTION_RECONCILIATION.md](PARENT_TRANSACTION_RECONCILIATION.md)
- [PARENT_CHILD_SYNC_MANUAL_TESTING.md](PARENT_CHILD_SYNC_MANUAL_TESTING.md)

## System Overview

YNAB Bank of Dad is a small Gradle and Groovy command-line application with two independent workflows:

| Workflow | Entry point | Gradle task | Purpose |
|---|---|---|---|
| Record Allowance | `ynabbankofdad.allowance.RecordAllowance` | `run` | Calculate allowance and interest transactions and post one bulk transaction set to one YNAB budget. |
| Parent/Child Syncer | `ynabbankofdad.sync.ParentChildBudgetSyncer` | `runSyncer` | Poll a parent budget and reconcile mapped activity into one or more child budgets. |

Both workflows share:

- the YAML configuration loader in `RuntimeConfig`;
- the YNAB transport boundary in `YnabHttpClient`;
- the YNAB response and request mapping boundary in `YnabBudgetRepository`;
- immutable Groovy data models;
- SLF4J and Logback logging;
- Gradle, Spock, WireMock, and the JUnit Platform.

Only the Parent/Child Syncer uses SQLite. Record Allowance is a single-process, read-calculate-post flow with no local persistence or replay protection.

## Technology Stack

| Concern | Technology |
|---|---|
| Supported and CI runtime | Java 25; the Gradle build does not currently declare a Java toolchain |
| Build and packaging | Gradle 9.6.1 wrapper, `groovy` and `application` plugins |
| Application language | Groovy 5.0.6 |
| CLI parsing | Groovy Picocli `CliBuilder` and the syncer's small custom parser |
| Configuration | SnakeYAML 2.5 |
| HTTP | JDK `java.net.http.HttpClient` |
| JSON | Groovy JSON |
| Local state | SQLite through `sqlite-jdbc` 3.50.3.0 |
| Logging | SLF4J 1.7.36 and Logback 1.5.20 |
| Unit tests | Spock 2.4 for Groovy 5 |
| HTTP integration tests | WireMock 3.0.1 and MockWebServer |
| Persistence integration tests | Real temporary SQLite databases |
| CI | GitHub Actions on Java 25 |

The application does not use a dependency injection framework, ORM, web framework, or generated YNAB client. Dependencies are constructed explicitly by each entry point. SQL and YNAB JSON contracts remain visible in repository-local code.

## Build And Test Topology

`build.gradle` defines:

- `run`, whose main class is `RecordAllowance`;
- `runSyncer`, whose main class is `ParentChildBudgetSyncer`;
- `test`, which excludes class names ending in `WireMockSpec` or `IntegrationSpec`;
- `integrationTest`, which includes those two patterns;
- `testAll`, which runs unit tests before integration tests;
- `installDist`, which creates the Record Allowance launcher and runtime classpath under `build/install/YNABBankOfDad/`.

The filename convention is therefore part of the test architecture. A test using WireMock does not run in `integrationTest` unless its class name matches one of the configured patterns.

`runSyncer` is a separate `JavaExec` task, not an application start script, so `installDist` does not currently install a Parent/Child Syncer launcher.

### Checked-In Launchers

| Launcher | Behavior |
|---|---|
| `run-weekly-allowance.sh`, `RunWeeklyAllowance.bat` | Build and run Record Allowance live with the current date. |
| `run-specific-allowance.sh`, `RunSpecificAllowance.bat` | Build and run Record Allowance live for a supplied date. |
| `run-parent-child-sync.sh` | Run one live sync cycle; it does not add `--dry-run`. |
| `RunParentChildSync.bat` | Run one dry-run sync cycle. |

The allowance wrappers validate Java 25 or later. The sync wrappers currently require only a nonblank `JAVA_HOME`; Gradle itself does not enforce Java 25 through a toolchain. For predictable behavior, use the documented Java 25 runtime for every entry point.

## Source Organization

```text
src/main/groovy/ynabbankofdad/
├── allowance/
│   ├── RecordAllowance.groovy
│   ├── AllowanceCalculationService.groovy
│   └── TransactionAssemblyService.groovy
├── config/
│   └── RuntimeConfig.groovy
├── model/
│   └── TransactionModels.groovy
├── sync/
│   ├── ParentChildBudgetSyncer.groovy
│   ├── ReconciliationOperationApplier.groovy
│   ├── SyncRunCoordinator.groovy
│   ├── SyncCliOptions.groovy
│   ├── SyncLoggingBootstrap.groovy
│   ├── ChildTransactionPayloadFactory.groovy
│   ├── model/
│   │   ├── SyncModels.groovy
│   │   └── SyncResults.groovy
│   ├── reconcile/
│   │   ├── ReconciliationModels.groovy
│   │   ├── SourceRevisionNormalizer.groovy
│   │   ├── DesiredMirrorFactory.groovy
│   │   ├── ParentTransactionReconciler.groovy
│   │   └── MoneyMovementReconciliation.groovy
│   └── state/
│       ├── SyncStateStore.groovy
│       └── ReconciliationStateModels.groovy
└── ynab/
    ├── YnabHttpClient.groovy
    ├── YnabBudgetRepository.groovy
    └── YnabLogFormatter.groovy
```

## Shared Infrastructure

### Configuration

`RuntimeConfig.load` reads YAML, converts it to typed immutable configuration objects, and validates both allowance and optional sync sections. The same file can configure both workflows.

Operational names such as budgets, accounts, categories, mappings, and environment-variable names are configuration, not source-code constants. Most YNAB name matching is exact and case-sensitive. Sync account mappings support literal and explicit regex matchers, with literal matches taking precedence.

One consequence of the shared loader is that an invalid `sync:` section can reject an allowance run even though Record Allowance does not otherwise use sync configuration.

### HTTP Boundary

`YnabHttpClient` is a deliberately small wrapper over JDK `HttpClient`. It owns:

- bearer authorization;
- `Accept` and JSON content headers;
- connection and request timeouts;
- GET, POST, PUT, PATCH, and DELETE request construction;
- JSON serialization and parsing;
- contextual non-success, timeout, interruption, and malformed-response errors.

The access token is used only in the authorization header and is not included in application logs.

`YnabBudgetRepository` sits above the transport. It owns YNAB-specific paths, request wrappers, and conversion into internal models. Transaction deltas, movement snapshots, child lookup/update/delete, and import recovery validate required response objects and server knowledge. Plan, account, and category discovery are more permissive and treat missing collections as empty; bulk-create ID validation is completed by the payload factory and operation applier. Business logic should depend on repository methods rather than constructing YNAB request maps or paths directly.

### Logging

Record Allowance uses the console Logback configuration in `src/main/resources/logback.groovy`.

The syncer calls `SyncLoggingBootstrap.configure`, retains the existing console appender, adds a configured rolling file appender, and applies the configured level to the root logger. Reconciliation logs include financial and routing metadata needed for audit, but never access tokens. Operators should treat both logs and SQLite backups as sensitive financial data.

`YnabLogFormatter` converts milliunit amounts into dollar-formatted strings for readable logs without mutating the actual YNAB payload.

## Record Allowance

### Responsibility

Record Allowance calculates a configured set of allowance and interest transactions for one YNAB budget. It reads current category balances, builds transactions in memory, displays the proposed bulk payload, and either stops in dry-run mode or submits one bulk POST.

It is not a reconciliation system:

- it does not look for prior allowance transactions;
- it does not assign stable import IDs;
- it does not persist local state;
- it does not retry or repair uncertain writes;
- repeating a live run for the same date can create duplicates.

### Main Components

| Class | Responsibility |
|---|---|
| `RecordAllowance` | CLI parsing, dependency construction, YNAB discovery, top-level orchestration, dry-run decision, and compatibility adapters. |
| `AllowanceCalculationService` | Milliunit conversion, interest calculation, account-type selection, CD maturity and origination logic, and historical-rate selection. |
| `TransactionAssemblyService` | Build immutable allowance, interest, offset, and non-interest transaction drafts. |
| `TransactionDraft` | Internal immutable transaction representation and conversion to YNAB request fields. |
| `YnabBudgetRepository` | Resolve the latest matching plan, account and category IDs, and submit the bulk transaction request. |

### End-To-End Flow

```text
RecordAllowance.main
  -> read YNAB_ACCESS_TOKEN
  -> parse --date, --config, --dry-run, --help
  -> RuntimeConfig.load
  -> construct YnabHttpClient and YnabBudgetRepository
  -> GET /v1/plans
  -> choose newest exact-name budget
  -> GET /v1/plans/{budget}/categories
  -> GET /v1/plans/{budget}/accounts
  -> resolve allowance escrow account
  -> calculate and assemble transaction groups
  -> normalize TransactionDraft/map values into YNAB maps
  -> log the complete proposed payload
  -> dry-run: stop
  -> live: POST /v1/plans/{budget}/transactions/bulk
```

Dry-run is write-free but not offline. It still requires a valid token and successful YNAB plan, category, and account reads.

### Calculation Model

YNAB amounts are integer milliunits: `$1.00` is `1000`.

Advanced-account interest is calculated from current category balances. A category qualifies when its name contains both a configured kid name and a configured account-type substring. Non-CD types use the `Current` rate table. Categories whose account type starts with `Gold CD` derive an origination date from the maturity date embedded in the category name, then select a historical rate table.

Interest is calculated as:

```text
(configured percentage / 100) * category balance in dollars
```

The result is rounded with three significant digits, multiplied by 1000, truncated toward zero to an integer milliunit amount, and omitted when the resulting amount is not positive. This production path is distinct from the separate half-up compatibility conversion exposed by `RecordAllowance`.

Configured advanced allowance deposits are resolved by exact category name. Missing configured categories fail the run rather than silently skipping a deposit.

### Transaction Assembly Order

The generated collection is ordered as follows:

1. simple-account interest, currently inactive and empty;
2. simple-account allowance, currently inactive and empty;
3. advanced-account interest;
4. advanced-account allowance deposits;
5. one balancing offset transaction;
6. one combined transaction for each configured non-interest kid.

The offset is the negative sum of the advanced/simple component amounts, so that component set nets to zero in the allowance escrow account. Non-interest transactions are appended after the offset and are not part of that balancing calculation.

The assembly layer creates `TransactionDraft` instances, while retained compatibility methods in `RecordAllowance` return some maps. `toYnabTransactions` is the final adaptation boundary that accepts either representation.

### Allowance Failure Model

Configuration, date parsing, missing plan/account/category, malformed YNAB response, HTTP failure, and calculation errors propagate and terminate the process. There is no local retry loop or compensating rollback.

The bulk submission is one HTTP request, but the application does not add an independent atomicity guarantee beyond YNAB's endpoint behavior. An uncertain response should be reviewed manually before rerunning because the workflow has no replay protection.

### Allowance Tests

- `AllowanceCalculationServiceSpec` covers rates, CD dates, maturity, account types, and rounding.
- `TransactionAssemblyServiceSpec` covers draft construction and assembly failures.
- `RecordAllowanceSpec` covers the coordinator's compatibility surface and transaction totals/order.
- `RecordAllowanceWireMockSpec` covers YNAB discovery and the final bulk request.
- `RuntimeConfigSpec`, `YnabHttpClientSpec`, and `YnabBudgetRepositorySpec` cover shared boundaries.
- `ScriptWrapperBehaviorSpec` verifies shell and batch wrapper behavior.

## Parent/Child Syncer

### Responsibility

The Parent/Child Syncer polls one configured parent budget and maintains child-budget mirrors for mapped parent transactions, split components, and money-movement sides. Parent financial and routing fields are authoritative. Child memos become child-owned after initial creation.

The syncer is a stateful reconciliation system. It persists stable source observations, current and historical child mirror IDs, immutable remote operation intent, attempts, ingestion batches, and the transaction cursor in SQLite.

### Runtime Composition

`ParentChildBudgetSyncer.fromConfig` constructs:

1. a parent repository authenticated with the configured parent token;
2. a persistent `SyncStateStore`, or a read-only `DryRunSyncStateRepository` facade;
3. one `ChildSyncContext` and separately authenticated repository per child budget;
4. `ReconciliationOperationApplier` for live mode;
5. `SyncRunCoordinator` for run completion and transaction cursor updates.

Each child context caches its resolved YNAB budget ID and account IDs for the process lifetime.

### Polling Model

`runLoop` calls `runOnce` until `--max-cycles` is reached. Without that option, the default is effectively continuous polling. Between cycles it sleeps for `sync.pollingIntervalSeconds`.

Parent plan, category, transaction-delta, full-detail transaction, and unrecoverable pre-run read failures propagate out of `runOnce`. Because `runLoop` does not catch them, they terminate the polling process before a `sync_runs` row is created. Money-movement reads, per-child routing failures, and per-operation failures are isolated so successfully reconciled transaction work can still advance the transaction cursor where its current batch is eligible.

## Parent Transaction Reconciliation

### Review Orientation

The reconciliation implementation is split into four layers:

| Layer | Primary files | Review question |
|---|---|---|
| YNAB ingestion and mutation boundary | `SyncModels.groovy`, `YnabBudgetRepository.groovy`, `YnabHttpClient.groovy` | Are the documented YNAB request and response contracts represented exactly? |
| Pure normalization and planning | `sync/reconcile/*.groovy` | Does stable source state produce the complete and correctly ordered desired operation set? |
| Durable state | `SyncStateStore.groovy`, `ReconciliationStateModels.groovy` | Is intent persisted, immutable, retryable, and schema-version safe? |
| Orchestration and application | `ParentChildBudgetSyncer.groovy`, `ReconciliationOperationApplier.groovy`, `SyncRunCoordinator.groovy` | Are reads, persistence, remote effects, retries, and cursor advancement ordered safely? |

A productive review order is:

1. `ReconciliationModels.groovy` and `ReconciliationStateModels.groovy`;
2. `SourceRevisionNormalizer.groovy` and `DesiredMirrorFactory.groovy`;
3. `ParentTransactionReconciler.groovy` and `MoneyMovementReconciliation.groovy`;
4. the versioned schema and operation methods in `SyncStateStore.groovy`;
5. `ReconciliationOperationApplier.groovy`;
6. `ParentChildBudgetSyncer.runOnce` and its persistence helpers;
7. focused unit tests, then real-SQLite and WireMock integration tests.

### Architectural Invariants

The following invariants define intended behavior:

1. Source identity excludes every mutable financial, descriptive, lifecycle, mapping, and routing field.
2. A top-level transaction and its split components have distinct identities.
3. A changed split is reconciled only after complete composition is known.
4. Parent date, amount, payee, approval eligibility, existence, and routing are authoritative.
5. Child memo is initialized from the parent, then preserved on later updates.
6. Automatic child updates restore `cleared` and reset `approved` to `false`.
7. Parent deletion, unapproval, unmapping, and confirmed split removal delete mirrors; they do not create reversals.
8. Cross-budget replacement deletes obsolete mirrors before creating replacements.
9. Remote mutation intent is durable before the remote call.
10. Applied sibling operations survive another operation's failure.
11. Child transaction IDs remain in mirror history after deletion, replacement, or recreation.
12. Transaction cursor advancement is gated by completion of every `transaction_delta` ingestion batch (including older unfinished batches) and transaction routing success.
13. Money-movement absence is never treated as deletion evidence.
14. Movement failures do not block the transaction cursor.
15. Dry-run performs no child mutation and no SQLite schema or data write.

### One Reconciliation Cycle

```text
runOnce
  -> initialize or inspect SQLite state
  -> resolve parent budget and categories
  -> read saved transaction cursor
  -> read transaction bootstrap/delta
  -> read complete movement snapshot independently
  -> fetch full detail for each non-deleted changed transaction
  -> resolve required child budgets and accounts
  -> normalize source revisions
  -> load active mirrors
  -> derive desired mirror sets
  -> compare desired and active mirrors
  -> dry-run: report decisions and stop
  -> live: persist batches, revisions, lifecycle, operations, dependencies
  -> apply globally ready operations
  -> complete all transaction_delta batches and all money_movement_snapshot batches independently
  -> finalize run
  -> advance transaction cursor only when every transaction_delta batch is complete
```

#### State Initialization

Live `SyncStateStore.initialize` creates baseline version 1 in a missing or empty database. It validates a current database's contiguous `schema_versions` history and applies any future pending versions in order in one SQLite transaction. It rejects nonempty unversioned databases, version gaps, newer schemas, and unsupported shapes without mutation. Dry-run creates no missing database, reads supported state without changing bytes, and rejects unsupported existing state unchanged.

#### Parent Reads

The syncer resolves the newest exact-name parent plan and reads categories. Transaction reads use two modes:

- bootstrap: `since_date` based on `transactionLookbackDays` when no cursor exists;
- incremental: `last_knowledge_of_server` without the configured bootstrap date filter;
- force lookback: `sync.state.forceLookback: true` uses the bootstrap `since_date` listing even when a cursor exists. The stored cursor is ignored for the request and still advances after successful transaction work. Existing child mirrors are matched from sync state, so already-processed sources are verified or updated rather than created again.

An empty transaction delta still carries response server knowledge and can advance the cursor after successful processing.

Money movements are read as a complete, unfiltered snapshot. The implementation intentionally sends no movement cursor because current official YNAB prose and OpenAPI do not agree on the request contract.

#### Complete Transaction Composition

Bootstrap listings (`since_date`, no cursor) treat the list payload as complete current composition, including `subtransactions`. YNAB's list endpoint returns transaction detail with split lines, and a first-cycle lookback can contain hundreds of parent transactions; fetching each by id exceeds the 200-request hourly token cap.

Incremental deltas (`last_knowledge_of_server`) still cannot treat omitted split lines as deletion by themselves. `completeTransactionDelta` performs `GET /plans/{plan_id}/transactions/{transaction_id}` only when an active subtransaction mirror would otherwise be deleted because its id is absent from the listed composition. Deleted top-level tombstones are retained directly because the full lookup may no longer exist.

That extra read is what makes destructive split removal safe: absence is interpreted only after a complete detail response.

### Stable Source Identity

`SourceEntityKey` represents all supported source types.

Top-level transaction identity:

```text
(sourceBudgetId, transaction, parentTransactionId)
```

Split component identity:

```text
(sourceBudgetId, subtransaction, parentTransactionId, parentSubtransactionId)
```

Money-movement identity:

```text
(sourceBudgetId, money_movement, moneyMovementId)
```

Amount, category, name, date, memo, payee, approval, deletion, group ID, mapping, target account, and target budget do not create a new source identity.

SQLite stores a deterministic, length-prefixed identity string so delimiter characters in YNAB IDs cannot make two tuples collide.

### Normalized Revisions

`SourceRevisionNormalizer` converts a complete transaction event into a canonical `ParentSourceRevision`:

- stable parent identity;
- date and top-level amount;
- memo;
- approval and deletion state;
- category and payee fields;
- sorted full component composition;
- complete/fetch-required safety marker;
- canonical JSON and SHA-256 revision hash.

The semantic hash excludes response server knowledge. The first persisted instance of a semantic revision stores its server knowledge, batch, and observation time. A later equivalent observation is deduplicated with `ON CONFLICT DO NOTHING`; it neither creates a revision nor updates that first observation's metadata. The later read remains represented by its ingestion batch.

Each split component also receives its own persisted component revision. Its parent date and lifecycle fields are combined with component amount, category, memo, and payee.

`ReconciliationCanonicalizer` recursively sorts maps before JSON serialization and hashing. Collection order is preserved, so callers sort identity-bearing component lists before canonicalization.

### Desired Child Mirrors

`DesiredMirrorFactory` examines one qualifying source against every resolved child context.

For each child:

1. resolve the source category name;
2. choose the first literal mapping, otherwise the first regex mapping;
3. require the child budget and mapped account IDs to have been resolved;
4. construct authoritative child state;
5. compute an authoritative payload hash;
6. separately compute the decorated creation memo.

Authoritative child state contains:

```text
account_id
date
amount
payee_id
payee_name
cleared = cleared
approved = false
```

The child category is explicitly unset on creation and recreation. Later update and verification paths neither mutate nor compare category, so a category manually added in the child remains child-owned. The decorated memo is included for creation and recreation, but excluded from the authoritative hash so later parent memo changes do not overwrite child-owned memo text.

### Mirror Identity

SQLite permits one active mirror per:

```text
(source entity, target child budget, logical direction)
```

Logical direction distinguishes `inflow` and `outflow` money-movement sides. For ordinary transactions, direction is creation-time mirror metadata derived from amount sign. Transaction matching and import identity ignore it, updates do not change it, and it may therefore remain unchanged after a later amount-sign edit.

### Desired-State Comparison

`ParentTransactionReconciler.compare` performs deterministic complete-set comparison.

| Existing mirror | Desired mirror | Decision |
|---|---|---|
| absent | present | `CREATE` |
| present in same child budget | same authoritative state | `NO_OP` plus existence verification |
| present in same child budget | authoritative fields/account differ | `UPDATE` |
| present in old child budget | same source desired in new child budget | `DELETE`, then dependent `CREATE` |
| present | absent | `DELETE` |

Existing-only deletion covers:

- explicit parent tombstone;
- approval withdrawal;
- mapped-to-unmapped change;
- deleted or removed split component;
- obsolete ordinary mirror after ordinary-to-split conversion;
- obsolete split mirrors after split-to-ordinary conversion.

The deterministic order is:

1. deletes sorted by operation key;
2. updates and no-ops sorted by operation key;
3. creates sorted by operation key.

Replacement creates depend on prior deletes. Persistence serializes multiple deletes and makes a replacement create depend on the final delete in that chain.

### No-Op Verification

There is no durable `NO_OP` operation type in SQLite. A no-op decision is persisted as an update-shaped verification operation containing the complete recreation payload.

The applier performs a documented child transaction GET:

- present and matching: record `already_complete`, perform no financial mutation;
- absent or marked deleted: create a replacement and retain the previous child ID in mirror history.

This is why a memo-only parent edit can repair a manually deleted child transaction even though memo itself is not authoritative.

### Operation Identity

The planner's operation key hashes:

- action;
- stable source identity;
- target child budget;
- movement direction where applicable;
- existing child transaction ID;
- authoritative payload hash.

The persisted operation key additionally incorporates:

- ingestion batch ID;
- source revision hash;
- stable source entity row ID;
- operation type;
- target and payload identity.

`sync_operations.operation_key` is unique. Normally, recreating an existing key requires complete `ReconciliationOperationIntent` equality. Replayed creates have a deliberate narrower exception: key, batch, source, target budget, child ID, payload JSON, and payload hash must match, while mirror ID, sequence, and dependency may differ. This exception supports replayed replacement creates and deserves focused review because those omitted fields remain immutable in SQLite.

### Stable Remote Import Identity

New child creates use a bounded deterministic import ID:

```text
PCBS:<31 hexadecimal SHA-256 characters>
```

The hash uses stable source and target identity. It excludes mutable amount, date, category, mapping, and memo. Money-movement direction is included so inflow and outflow cannot collide. Transaction direction is omitted.

The 36-character format respects the documented YNAB limit. Existing child transactions retain their historical import IDs because `import_id` is not a mutable transaction field.

### Durable SQLite Model

Baseline schema version 1 contains exactly nine tables.

#### Version 1 Tables

| Table | Role |
|---|---|
| `schema_versions` | Applied ordered schema versions. |
| `sync_runs` | Process-cycle status and errors. Live singleton enforcement uses `<sqlitePath>.lock`, not this table. |
| `sync_cursors` | Named cursors, including transaction server knowledge. |
| `source_entities` | Stable source identity and current lifecycle. |
| `ingestion_batches` | Cursor-eligible transaction or independent movement work. |
| `source_revisions` | Append-only, deduplicated canonical semantic revisions with first-observation metadata. |
| `child_mirrors` | Current and historical child transaction lineage. |
| `sync_operations` | Immutable ordered create/update/delete intent and dependency. |
| `operation_attempts` | Append-only remote attempt outcomes and failure reasons. |

#### Status Values

Source lifecycle:

```text
active | deleted | unconfirmed
```

Mirror lifecycle:

```text
active | deleted | replaced | missing
```

Operation status:

```text
pending | applied | retryable_failed
```

Attempt outcome:

```text
applied | failed | already_complete
```

SQLite triggers prevent mutation or deletion of append-only revisions and attempts, prevent changes to immutable operation intent, and preserve mirror identity/history rows.

### Schema Initialization And Future Migrations

Version 1 is the first supported state schema. Operators must delete databases created by earlier builds; initialization does not inspect their rows to infer a conversion. Deleting SQLite state does not delete child transactions that an earlier syncer already created, so a previously deployed installation must inspect the complete first dry-run and remove or otherwise account for those transactions before enabling live reconciliation. A nonempty database without `schema_versions` is rejected and left byte-for-byte unchanged.

`schema_versions` is an ordered migration ledger. Applied versions must be contiguous from 1. On startup, initialization validates that history and the current table, index, and trigger shape, rejects a highest version greater than `CURRENT_SCHEMA_VERSION`, and executes each pending migration in ascending order. Schema statements and their version inserts share one transaction, so any failure rolls back the complete initialization attempt. Repeated initialization at the current version adds no rows and changes no tables.

### Applying Durable Operations

`ReconciliationOperationApplier.applyReadyOperations` repeatedly queries ready operations, up to 100 distinct operations per invocation. A dependent operation is ready only after its dependency is applied. Failures are isolated per operation.

#### Create

1. Remove the internal logical-direction field from the outgoing payload.
2. Generate the stable import ID.
3. Reuse a previously recorded successful attempt if one returned a child ID.
4. Otherwise send the bulk create.
5. If YNAB reports a duplicate import ID without a created ID, recover through documented bulk PATCH by import identity. This sends the desired fields and may update the matched transaction; it is not a read-only lookup.
6. Append the attempt.
7. Activate the returned child mirror and mark the operation applied in one local transaction.

#### Update

1. GET the recorded child transaction.
2. If absent/deleted, execute recreation.
3. Build a partial update containing only account, date, amount, and payee fields.
4. Enforce `cleared` and `approved = false`.
5. Omit memo, category, and import ID.
6. Skip PUT when remote state already matches.
7. Append the attempt and atomically update mirror state plus operation completion.

#### Delete

1. Skip another DELETE when a prior successful attempt is already recorded.
2. Otherwise call the documented transaction DELETE endpoint.
3. Treat documented 404 absence as idempotent completion.
4. Append the attempt and atomically retire the mirror plus complete the operation.

#### Failure

An exception for one operation:

- records a failed attempt where possible;
- marks intent `retryable_failed`;
- preserves successful sibling effects;
- leaves dependent work blocked;
- contributes to partial run status.

There is currently no retry count, backoff schedule, permanent-failure status, or dead-letter mechanism.

### Crash-Window Recovery

The operation journal is designed around remote success followed by local failure.

| Crash window | Recovery behavior |
|---|---|
| Create succeeded before attempt/completion write | Stable import ID makes the retry a duplicate; bulk PATCH by import identity applies desired fields and recovers the child ID. |
| Create attempt recorded before mirror/operation completion | The recorded returned child ID is reused without another create. |
| Update succeeded before local completion | GET observes matching remote state and records `already_complete`. |
| Delete succeeded before local completion | Retried DELETE returns 404 and is recorded as `already_complete`. |

This avoids a second financial effect while preserving a new append-only attempt for the recovery cycle.

### Ingestion Batches And Cursor Safety

Transaction and movement reads create separate ingestion batches.

Transaction batch identity includes:

- parent budget ID;
- response server knowledge;
- ordered semantic revision hashes.

Movement batch identity includes:

- parent budget ID;
- response server knowledge;
- ordered observed movement hashes.

`completeIngestionBatchIfReady` marks one batch complete only when none of its operations remain pending or retryable-failed.

`completeIngestionBatchesOfKindIfReady(sourceKind)` walks every batch of that kind, completes each ready batch, and returns true only when none remain incomplete. Live `runOnce` uses this for `transaction_delta` and `money_movement_snapshot` separately.

`SyncRunCoordinator` advances `transactions.last_server_knowledge` only when transaction routing succeeded and every transaction_delta batch is complete. Overall run status can still be partial because movement work failed; that failure does not gate the transaction cursor.

### Money-Movement Reconciliation

Money movements use the same desired-mirror and durable-operation machinery, but deliberately weaker deletion semantics.

Each stable movement can derive two mirrors:

| Side | Category mapping | Amount | Payee | Direction |
|---|---|---:|---|---|
| Destination | `to_category_id` | movement amount | `From <source category>` | `inflow` |
| Source | `from_category_id` | negative movement amount | `To <destination category>` | `outflow` |

The configured movement lookback applies only when deciding whether a newly observed movement should create its first mirrors. A previously mirrored movement remains eligible for update or reroute after aging beyond the lookback.

When an earlier movement ID is absent from a later complete snapshot, its lifecycle becomes `unconfirmed`. Its child mirrors are not deleted. Similar amounts, dates, group IDs, or a new replacement-looking ID do not establish replacement lineage.

Movement operations and failures are independent of the parent transaction cursor.

### Routing-Failure Safety

Child plan/account resolution can fail because of authentication, network, configuration, or missing-account problems. The syncer separates resolved and failed child contexts.

A source component whose applicable category could route into a failed context is recorded in `routingBlockedSources` and is not destructively reconciled against an incomplete desired set. Healthy components of the same split still plan and persist operations. Persistence skips lifecycle updates only for blocked components so temporary lookup failure cannot mark their `source_entities` deleted.

Transaction routing failure blocks transaction batch completion and cursor advancement. Movement routing failure blocks only movement-batch completion.

### Dry-Run Architecture

`DryRunSyncStateRepository` is a read-only facade:

- initialization does not create or migrate a database;
- cursor, source, and mirror reads are allowed when supported state exists;
- every reconciliation write method either does nothing where appropriate or throws a dry-run write error;
- no operation applier is constructed.

Dry-run still performs parent and child reads required to resolve routing and distinguish an existing child from a recreation. It must not send POST, PUT, PATCH, or DELETE and must not change SQLite bytes.

Dry-run reports:

- creates;
- updates;
- deletes;
- cross-budget replacements;
- existence checks;
- missing-child recreations;
- unconfirmed money movements.

### Auditability

There are two audit channels.

#### SQLite

The durable record is the combination of:

- source identity and normalized revision JSON;
- ingestion batch and server knowledge;
- immutable ordered operation payload and dependency;
- every remote attempt and failure reason;
- every historical and active child transaction ID;
- run status and transaction cursor.

#### INFO Logs

For normal transaction and movement operations passed through `persistOperation`, INFO logging records a decision after operation insertion or key reuse:

- planned action;
- operation ID and stable key;
- batch and sequence;
- stable source identifiers;
- target budget and child transaction ID;
- dependency ID;
- formatted payload.

When an operation resolves, INFO logging records:

- create, recreate, update, or delete;
- `applied` or `already_complete` outcome;
- operation and stable source identity;
- target budget and child transaction ID;
- direction/account where applicable;
- formatted outgoing payload.

Cycle completion also logs per-child created/updated/deleted counts, then per cached child account:

- `netChange` of that cycle's CREATE/UPDATE/DELETE milliunits
- dry-run `current` + `projected` (`current + netChange`) versus parent category `balance` (summed when several parent categories mapped to the same account)
- live `actual` account `balance` from a post-apply `GET /v1/plans/{plan}/accounts` versus the same parent side

A nonzero `diff` is WARN (`balance mismatch`) and never a run failure. Reverse mapping comes from the cycle's parent-category → child-account cache written when desired mirrors are resolved, not from a second `accountMappings` pass.

Failures remain ERROR and unconfirmed movements remain WARN. Tokens are never logged. Payees, memos, IDs, and financial data are logged and persisted intentionally for audit, so retention and backup access should be controlled.

### YNAB API Contracts Used

| Purpose | Endpoint |
|---|---|
| Plan discovery | `GET /v1/plans` |
| Accounts | `GET /v1/plans/{plan}/accounts` |
| Categories | `GET /v1/plans/{plan}/categories` |
| Transaction bootstrap/delta | `GET /v1/plans/{plan}/transactions` |
| Complete transaction/child lookup | `GET /v1/plans/{plan}/transactions/{transaction}` |
| Child create | `POST /v1/plans/{plan}/transactions/bulk` |
| Child update | `PUT /v1/plans/{plan}/transactions/{transaction}` |
| Import-ID recovery/update by identity | `PATCH /v1/plans/{plan}/transactions` |
| Child delete | `DELETE /v1/plans/{plan}/transactions/{transaction}` |
| Movement snapshot | `GET /v1/plans/{plan}/money_movements` |

Transaction delta, movement snapshot, child mutation, and import-recovery methods validate their required response objects and `server_knowledge` instead of treating malformed 2xx responses as success. Discovery methods remain permissive, and bulk-create ID validation occurs in the calling payload/application layer.

Important upstream limitations are documented in the OpenSpec design and operator guide:

- interaction and retention guarantees for transaction date and delta filters are incomplete;
- money movements expose no deletion tombstone, individual lookup, mutation endpoint, or replacement lineage;
- official movement-delta prose and OpenAPI disagree;
- import ID is immutable;
- update-not-found behavior is not assumed, so a documented GET precedes update.

### Tests As Executable Architecture

The reconciliation tests are intentionally split by responsibility.

| Concern | Primary tests |
|---|---|
| Stable identities and revision hashes | `SourceRevisionNormalizerSpec`, `MoneyMovementNormalizerSpec` |
| Desired-state decisions | `ParentTransactionReconcilerSpec`, `MoneyMovementReconcilerSpec` |
| SQLite baseline, version checks, dependencies, lineage | `SyncStateStoreIntegrationSpec`, `ReconciliationStateStoreIntegrationSpec` |
| Create/update/delete/recreation and crash windows | `ReconciliationOperationApplierSpec`, `ReconciliationMutationIntegrationSpec` |
| Process restart and cursor recovery | `ReconciliationRecoveryIntegrationSpec`, `SyncRunCoordinatorSpec` |
| Full transaction/split planning | `ReconciliationPlanningIntegrationSpec` |
| Complete movement behavior | `MoneyMovementReconciliationIntegrationSpec` |
| Parent/child HTTP and token isolation | `ParentChildBudgetSyncerWireMockSpec`, `YnabTransactionMutationWireMockSpec` |
| Dry-run byte and HTTP safety | `DryRunReconciliationIntegrationSpec` |
| Spec/scenario coverage | `ReconciliationCoverageContractSpec`, `ReconciliationCoverageIntegrationSpec` |
| Operator-document agreement | `DocumentationContractSpec`, `ReconciliationDocumentationIntegrationSpec` |

The scenario-to-test mapping is maintained in `openspec/changes/reconcile-parent-transaction-changes/test-coverage-matrix.md` and checked by tests.

Those documentation tests cover README, quick-start, the operator reconciliation guide, and the scenario matrix. `DocumentationContractSpec` also requires README discoverability and the major workflow, reconciliation, and review sections in this architecture guide; detailed factual accuracy still depends on code review.

### Code Review Checklist

Use this checklist when reviewing reconciliation changes:

#### Identity And Normalization

- Verify no mutable field has entered `SourceEntityKey`, mirror identity, or import identity.
- Verify collection ordering is deterministic before hashing.
- Verify server knowledge remains metadata rather than semantic hash input.
- Verify split payee/category/amount fields come from the component, while shared date/lifecycle comes from the parent.

#### Planning

- Verify complete transaction detail is fetched before any split absence becomes deletion.
- Verify literal mapping precedence and configured first-match behavior.
- Verify memo does not enter authoritative update comparison.
- Verify same-budget routing changes update in place.
- Verify cross-budget changes produce delete-before-create dependencies.
- Verify unrelated mirrors survive split edits.
- Verify routing lookup failures cannot produce destructive absence or deleted lifecycle from an empty desired set.
- Verify unrelated sources still reconcile when another child is routing-blocked.

#### Persistence

- Verify operation intent is deterministic and immutable.
- Verify repeated non-create keys describe identical intent, and review whether the narrower replayed-create comparison is safe for mirror, sequence, and dependency differences.
- Verify every remote effect is preceded by persisted intent.
- Verify append-only triggers and uniqueness constraints match domain invariants.
- Verify baseline version 1 creates exactly the nine documented tables.
- Verify unsupported state is rejected without mutation and future versions remain contiguous and transactional.

#### Application And Recovery

- Verify update payloads exclude memo, category, and import ID.
- Verify missing-child recreation retains old child ID history.
- Verify duplicate-import recovery cannot create a second financial effect.
- Verify delete 404 is idempotent success.
- Verify applied dependencies and siblings are not repeated after another operation fails.
- Verify every operation branch records an attempt and emits an audit outcome.

#### Cursor And Dry-Run

- Verify failure in any unfinished transaction_delta batch blocks cursor advancement.
- Verify older incomplete transaction batches block a later server-knowledge value until they complete.
- Verify movement work does not block that cursor.
- Verify empty deltas can advance server knowledge.
- Verify dry-run cannot reach any child mutation or state-write method.
- Verify dry-run creates no missing database, preserves supported database bytes, and rejects unsupported state unchanged.

### Review Hotspots And Design Questions

The following areas deserve extra scrutiny. They describe current boundaries or risks, not additional guarantees.

#### Cursor Eligibility Across Multiple Pending Batches

Resolved: `runOnce` advances the transaction cursor only when `completeIngestionBatchesOfKindIfReady('transaction_delta')` is true, so an older unfinished transaction batch blocks a newer server-knowledge value until its operations are applied or recognized as already complete.

#### Multiple Concurrent Syncer Processes

Live `ParentChildBudgetSyncer.main` acquires an exclusive OS file lock on `<sqlitePath>.lock` beside the configured state database before initializing state or entering `runLoop`. A second live process that cannot obtain the lock aborts immediately with a clear message and performs no YNAB mutation or SQLite write. The lock is released in a `finally` block on normal exit; the OS also releases it if the process dies (including `kill -9`), so a later run is not permanently locked out. Dry-run does not take the lock. Ready operations are still not atomically claimed inside SQLite; the process lock is the singleton guard for one live writer per state path.

#### Batch Persistence Atomicity

Schema initialization/migrations and operation completion are transactional, but ingestion batch creation, revision insertion, lifecycle updates, and operation insertion are separate state calls/connections. Deterministic keys support restart recovery, but reviewers should reason through crashes between every persistence step.

#### Per-Source Routing Failure State

Resolved: each transaction result carries its blocked component sources, while movement decisions retain whole-movement `routingBlocked`. Transaction planning uses healthy child contexts, suppresses deletes for blocked sources or failed child targets, and keeps healthy split-component intents. Lifecycle updates skip only blocked component sources so an incomplete desired set is never stored as `deleted`. Unrelated sources and healthy siblings in the same split still reconcile in the same cycle.

#### Operation Limit And Backlog Ordering

The applier handles at most 100 distinct operations per invocation. Large transaction batches can require later cycles. Review expected backlog behavior and whether the limit should become configuration.

#### Pending Intent Versus Configuration Changes

Operation intent is immutable, while each reread replans from current configuration. Review recovery behavior when memo decoration, mappings, or target configuration changes while an earlier operation remains pending. Durable pending work should not silently mutate.

#### Recreation Import IDs

Recreation intentionally reuses stable source/target import identity. Review YNAB behavior for duplicate import IDs associated with deleted child transactions and ensure import-ID recovery cannot incorrectly treat a deleted transaction as the active replacement.

#### Configuration Removal With Pending Work

Applying an operation requires a current child context and token for its target budget. Removing a child from configuration while delete/update work remains pending can strand that operation. Operators should retain target credentials until operation backlog is empty.

## Operational Boundaries

- Live mode enforces one syncer process per SQLite state database via `<sqlitePath>.lock`; a second live process aborts rather than sharing the database.
- Delete any state database created by an earlier build before first reconciliation; unsupported state is rejected without mutation.
- Run `--dry-run --max-cycles 1` with the exact live configuration and state path before live rollout.
- Keep child credentials configured until all operations targeting that child have completed.
- Do not delete the state database after live use; it contains replay protection, cursor state, and manual recovery history.
- Restoring an old binary or database after a remote update/delete cannot reconstruct prior remote child state automatically.
- Use `sync_operations`, `operation_attempts`, `child_mirrors`, and `source_revisions` for manual recovery analysis.

## Continuous Integration

GitHub Actions provisions Java 25 and runs:

```bash
./gradlew testAll
./gradlew installDist
```

Unit and integration reports plus the installed distribution are uploaded as artifacts. The full suite requires no live YNAB credentials.

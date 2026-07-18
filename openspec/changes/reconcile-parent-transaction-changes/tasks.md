## 1. Prerequisites And YNAB Contracts

- [ ] 1.1 Complete the active `cleared-transactions` change and reconcile this change's cleared/unapproved update rules with its final artifacts and implementation.
- [ ] 1.2 Check the current official YNAB API documentation for transaction and subtransaction deletion fields, delta response guarantees, and the interaction between `since_date` and `last_knowledge_of_server`; record confirmed behavior in `design.md`.
- [ ] 1.3 Check the official child transaction lookup, update, and delete endpoints, mutable request fields, response shapes, not-found semantics, and import ID length/immutability rules; record confirmed contracts in `design.md` before writing fixtures.
- [ ] 1.4 Confirm with official YNAB documentation or API support whether money-movement endpoints accept `last_knowledge_of_server`, whether deletion/replacement has an observable form, and whether complete snapshots have retention limits; retain full-snapshot/non-destructive semantics unless confirmed otherwise.
- [ ] 1.5 Add failing repository/model tests for parent payee fields, top-level deletion tombstones, deleted subtransactions, typed cursor-only empty deltas, cursor-based requests without bootstrap lookback, and complete unfiltered money-movement snapshots.

## 2. Transaction Models And YNAB Repository Operations

- [ ] 2.1 Extend parent transaction and subtransaction models and repository mapping with the verified deletion and payee fields while preserving current nullable-field behavior.
- [ ] 2.2 Add the verified HTTP verbs and response handling to `YnabHttpClient`, including contextual errors and interrupt/timeout behavior without logging tokens.
- [ ] 2.3 Add focused `YnabBudgetRepository` methods for child transaction lookup, partial update, and deletion using only documented request fields and response shapes.
- [ ] 2.4 Add unit/WireMock tests for successful child lookup, update, delete, documented already-absent deletion, malformed success responses, authentication failures, validation failures, and server errors.
- [ ] 2.5 Replace mutable/truncated creation identity with a bounded deterministic import ID derived from stable source and target identity, leaving existing recorded child import IDs unchanged.
- [ ] 2.6 Add import-ID tests for determinism, source/target distinction, punctuation, bounds, and compatibility with the verified YNAB limit.
- [ ] 2.7 Return typed transaction-delta and money-movement-snapshot results carrying response server knowledge directly instead of storing response metadata in mutable repository fields.

## 3. Versioned Reconciliation State

- [ ] 3.1 Introduce transactional SQLite schema versioning that recognizes the current unversioned schema and applies ordered migrations atomically.
- [ ] 3.2 Add constrained state tables for stable source entities, append-only source revisions, current child mirrors, ingestion batches, immutable ordered reconciliation operation intent, and append-only operation attempts.
- [ ] 3.3 Implement state repository methods to upsert stable source identity, append deduplicated revisions, query all mirrors for one parent transaction, and create deterministic operations.
- [ ] 3.4 Implement operation dependency and retry queries so cross-budget delete completes before replacement create and completed operations are never applied twice.
- [ ] 3.5 Implement mirror lifecycle updates for create, in-place update, deletion, replacement, and recreation with full child transaction ID audit history.
- [ ] 3.6 Backfill stable transaction and money-movement entities and active child mirrors from successful non-dry-run legacy mappings that contain child transaction IDs while retaining all legacy audit rows.
- [ ] 3.7 Select the newest successful legacy mirror by `applied_at` and row ID, and create pending delete operations for older duplicate child transaction IDs without calling YNAB during migration.
- [ ] 3.8 Add real-SQLite integration fixtures for empty migration, populated single-mirror migration, transaction and movement duplicate migration, failed/dry-run-only history, missing child IDs, repeated initialization, in-memory dry-run projection, and transactional rollback on migration failure.

## 4. Source Normalization And Desired-State Planning

- [ ] 4.1 Add stable transaction and subtransaction identity builders that exclude amount, category, name, date, memo, payee, approval, deletion state, mapping, and target account.
- [ ] 4.2 Normalize each changed parent transaction into a revision containing its full current ordinary-or-split composition, approval/deletion state, source fields, and server knowledge.
- [ ] 4.3 Derive the complete desired child mirror set for qualifying ordinary transactions and qualifying split components using current mapping configuration.
- [ ] 4.4 Compare desired mirrors with every active mirror linked to the changed parent transaction and produce create, update, delete, or no-op operations.
- [ ] 4.5 Plan in-place updates for authoritative date, amount, payee, cleared state, unapproved state, and same-budget account routing while excluding memo from update state.
- [ ] 4.6 Plan ordered delete-then-create operations for cross-budget reroutes and delete operations for deletion, unapproval, mapped-to-unmapped changes, and removed split components.
- [ ] 4.7 Cover ordinary-to-split, split-to-ordinary, split addition, split edit, split removal, category rename without route change, memo-only edits, and unchanged repeated deltas in focused planner/reconciler tests.
- [ ] 4.8 Verify through tests that configuration changes alone do not scan or rewrite historical mirrors, while a later YNAB source delta uses the then-current mapping.
- [ ] 4.9 Add stable money-movement identity using source budget plus movement ID, with target child and inflow/outflow side distinguishing mirrors and import IDs.
- [ ] 4.10 Persist complete normalized movement observations and reconcile re-observed same-ID amount, date, category, side, same-budget route, and cross-budget route changes while preserving child memos.
- [ ] 4.11 Treat movement absence, lookback expiry, similar replacement IDs, and group IDs as non-destructive/unconfirmed observations and cover each decision in focused tests.

## 5. Durable Child Mutation Application

- [ ] 5.1 Refactor child application around persisted reconciliation operations while preserving per-child failure isolation and successful sibling outcomes.
- [ ] 5.2 Apply create operations with stable import IDs and record the returned child transaction ID as the active mirror.
- [ ] 5.3 Apply same-budget update operations to the existing child transaction ID, preserving memo and resetting the child transaction to cleared and unapproved.
- [ ] 5.4 Convert an update whose lookup/update reports a missing child transaction into a retry-safe replacement create and record the new child transaction ID.
- [ ] 5.5 Apply delete operations and treat the documented already-absent result as idempotent success.
- [ ] 5.6 Enforce operation dependencies so replacement creation cannot run before cross-budget deletion succeeds.
- [ ] 5.7 Record each mutation attempt and failure reason without marking failed operations complete or duplicating already applied operations.
- [ ] 5.8 Add focused application tests for create, update, delete, missing-child recreation on authoritative and no-op edits, repeated delete, partial batches, child isolation, state-write failures, and process-restart retries.
- [ ] 5.9 Define and test crash-window recovery after remote create, update, and delete success but before local completion recording.

## 6. Sync Orchestration And Cursor Safety

- [ ] 6.1 Separate bootstrap transaction reads from established-cursor delta reads and omit `since_date` after cursor establishment unless the verified YNAB contract proves the combined filters safe.
- [ ] 6.2 Persist changed source revisions and deterministic pending operations before applying any live child mutation.
- [ ] 6.3 Update cycle result aggregation so pending or failed create/update/delete work prevents transaction cursor advancement while idempotently completed work permits it.
- [ ] 6.4 Preserve successful operation outcomes when another operation or child fails, and ensure rereading the blocked delta retries only unfinished work.
- [ ] 6.5 Ensure parent tombstones, unapproved changes, unmapped changes, and empty deltas participate in run status and cursor behavior even when they create no child transaction.
- [ ] 6.6 Extend dry-run orchestration to read existing mirrors/cursors and log ordered creates, updates, deletions, reroutes, recreations, and legacy cleanup without YNAB mutations or SQLite writes.
- [ ] 6.7 Add coordinator and run-loop tests for no-op edits, successful mutation batches, partial failures, empty deltas, dry-run behavior, and cursor advancement after recovery.
- [ ] 6.8 Process complete money-movement snapshots and retry movement operations independently so movement failures never block transaction ingestion-batch cursor completion.

## 7. Paired Unit And Integration Coverage

- [ ] 7.1 Create focused unit specs for source normalization, desired-state reconciliation, operation application, stable import identity, cursor completion, and migration selection; keep each semantic in the smallest responsible spec.
- [ ] 7.2 Add paired unit and WireMock integration coverage proving date/amount/payee/approval/cleared edits update one child transaction, preserve its memo, and retain stable source/mirror lineage.
- [ ] 7.3 Add paired unit and integration coverage for same-budget account updates, cross-budget delete-before-create rerouting, dependency failures, exact token separation, and retry without repeating completed deletes.
- [ ] 7.4 Add paired unit and integration coverage for parent deletion, unapproval, mapped-to-unmapped changes, no reversal creation, idempotent repeated deletion, and cursor completion.
- [ ] 7.5 Add paired unit and integration coverage for ordinary-to-split, split-to-ordinary, split add/edit/remove, partial-composition fetch safety, deterministic ordering, and unrelated mirror preservation.
- [ ] 7.6 Add paired unit and integration coverage for missing-child recreation on authoritative and no-op source edits while preserving prior child-ID audit history.
- [ ] 7.7 Add paired unit and integration coverage for same-ID money-movement edits/reroutes, inflow/outflow identity, lookback corrections, non-destructive absence, replacement-ID limitations, unchanged snapshot idempotency, and cursor independence.
- [ ] 7.8 Add paired unit and real-SQLite integration coverage for every migration eligibility rule, duplicate tie-breaker, movement replay preservation, in-memory dry-run projection, repeated initialization, and rollback.
- [ ] 7.9 Add paired unit and integration coverage for dry-run reporting of every operation type with zero HTTP mutations and byte-for-byte unchanged SQLite state.
- [ ] 7.10 Add a three-process-cycle integration scenario where the first cycle partially applies transaction and movement update/delete work, the second retries only unfinished work, and the third applies new incremental work with independent cursor outcomes.
- [ ] 7.11 Add crash-window integration coverage for remote create/update/delete success followed by local completion failure, proving recovery creates no second financial effect.
- [ ] 7.12 Maintain a scenario-level coverage matrix naming at least one focused unit test and one integration test for every normative scenario in `specs/parent-transaction-reconciliation/spec.md`; fail implementation review if any row is missing or skipped without a revised requirement.
- [ ] 7.13 Remove or update create-only assertions that conflict with authoritative reconciliation while preserving all unrelated allowance and sync behavior.

## 8. Documentation And Operational Rollout

- [ ] 8.1 Finalize `PARENT_TRANSACTION_RECONCILIATION.md` with human-readable authoritative/child-owned semantics, transaction and split examples, money-movement capabilities and limitations, failure/retry behavior, migration, dry-run, and rollout safety; remove the proposed-status notice only when implementation is complete.
- [ ] 8.2 Keep `README.md` and `QUICK_START.md` linked to the semantics guide and update configuration/manual-testing guidance to match the final reviewed behavior.
- [ ] 8.3 Document that migration can queue destructive legacy duplicate cleanup and require operators to back up SQLite state and run `--dry-run --max-cycles 1` before the first live reconciliation.
- [ ] 8.4 Document rollback limits after remote updates/deletions and the operation-history information available for manual recovery.
- [ ] 8.5 Verify the human semantics guide, normative spec, and scenario-level unit/integration coverage matrix agree exactly.
- [ ] 8.6 Run `./gradlew testAll` and resolve every unit and integration failure.
- [ ] 8.7 Run `./gradlew installDist` to verify packaged entry points and runtime classpaths.
- [ ] 8.8 Run a documented one-cycle sync dry run against a non-production/test configuration and verify that planned reconciliation performs no child or SQLite writes.

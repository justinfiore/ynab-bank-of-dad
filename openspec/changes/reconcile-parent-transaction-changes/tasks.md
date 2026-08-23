## 1. Prerequisites And YNAB Contracts

- [x] 1.1 Complete the active `cleared-transactions` change and reconcile this change's cleared/unapproved update rules with its final artifacts and implementation.
- [x] 1.2 Check the current official YNAB API documentation for transaction and subtransaction deletion fields, delta response guarantees, and the interaction between `since_date` and `last_knowledge_of_server`; record confirmed behavior in `design.md`.
- [x] 1.3 Check the official child transaction lookup, update, and delete endpoints, mutable request fields, response shapes, not-found semantics, and import ID length/immutability rules; record confirmed contracts in `design.md` before writing fixtures.
- [x] 1.4 Confirm with official YNAB documentation or API support whether money-movement endpoints accept `last_knowledge_of_server`, whether deletion/replacement has an observable form, and whether complete snapshots have retention limits; retain full-snapshot/non-destructive semantics unless confirmed otherwise.
- [x] 1.5 Add failing repository/model tests for parent payee fields, top-level deletion tombstones, deleted subtransactions, typed cursor-only empty deltas, cursor-based requests without bootstrap lookback, and complete unfiltered money-movement snapshots.

## 2. Transaction Models And YNAB Repository Operations

- [x] 2.1 Extend parent transaction and subtransaction models and repository mapping with the verified deletion and payee fields while preserving current nullable-field behavior.
- [x] 2.2 Add the verified HTTP verbs and response handling to `YnabHttpClient`, including contextual errors and interrupt/timeout behavior without logging tokens.
- [x] 2.3 Add focused `YnabBudgetRepository` methods for child transaction lookup, partial update, and deletion using only documented request fields and response shapes.
- [x] 2.4 Add unit/WireMock tests for successful child lookup, update, delete, documented already-absent deletion, malformed success responses, authentication failures, validation failures, and server errors.
- [x] 2.5 Replace mutable/truncated creation identity with a bounded deterministic import ID derived from stable source and target identity, leaving existing recorded child import IDs unchanged.
- [x] 2.6 Add import-ID tests for determinism, source/target distinction, punctuation, bounds, and compatibility with the verified YNAB limit.
- [x] 2.7 Return typed transaction-delta and money-movement-snapshot results carrying response server knowledge directly instead of storing response metadata in mutable repository fields.

## 3. Versioned Reconciliation State

- [x] 3.1 Introduce `schema_versions` baseline version 1 and apply future contiguous ordered migrations atomically.
- [x] 3.2 Add constrained state tables for stable source entities, append-only source revisions, current child mirrors, ingestion batches, immutable ordered reconciliation operation intent, and append-only operation attempts.
- [x] 3.3 Implement state repository methods to upsert stable source identity, append deduplicated revisions, query all mirrors for one parent transaction, and create deterministic operations.
- [x] 3.4 Implement operation dependency and retry queries so cross-budget delete completes before replacement create and completed operations are never applied twice.
- [x] 3.5 Implement mirror lifecycle updates for create, in-place update, deletion, replacement, and recreation with full child transaction ID audit history.
- [x] 3.6 Remove old state tables and conversion logic so baseline version 1 contains exactly the nine reconciliation tables.
- [x] 3.7 Reject nonempty unversioned, noncontiguous, newer, and otherwise unsupported schemas without mutation; require operators to delete old databases.
- [x] 3.8 Add real-SQLite integration fixtures for fresh baseline creation, repeated initialization, compatibility rejection, contiguous-version validation, newer-version rejection, and transactional rollback.

## 4. Source Normalization And Desired-State Planning

- [x] 4.1 Add stable transaction and subtransaction identity builders that exclude amount, category, name, date, memo, payee, approval, deletion state, mapping, and target account.
- [x] 4.2 Normalize each changed parent transaction into a revision containing its full current ordinary-or-split composition, approval/deletion state, source fields, and server knowledge.
- [x] 4.3 Derive the complete desired child mirror set for qualifying ordinary transactions and qualifying split components using current mapping configuration.
- [x] 4.4 Compare desired mirrors with every active mirror linked to the changed parent transaction and produce create, update, delete, or no-op operations.
- [x] 4.5 Plan in-place updates for authoritative date, amount, payee, cleared state, unapproved state, and same-budget account routing while excluding memo from update state.
- [x] 4.6 Plan ordered delete-then-create operations for cross-budget reroutes and delete operations for deletion, unapproval, mapped-to-unmapped changes, and removed split components.
- [x] 4.7 Cover ordinary-to-split, split-to-ordinary, split addition, split edit, split removal, category rename without route change, memo-only edits, and unchanged repeated deltas in focused planner/reconciler tests.
- [x] 4.8 Verify through tests that configuration changes alone do not scan or rewrite historical mirrors, while a later YNAB source delta uses the then-current mapping.
- [x] 4.9 Add stable money-movement identity using source budget plus movement ID, with target child and inflow/outflow side distinguishing mirrors and import IDs.
- [x] 4.10 Persist complete normalized movement observations and reconcile re-observed same-ID amount, date, category, side, same-budget route, and cross-budget route changes while preserving child memos.
- [x] 4.11 Treat movement absence, lookback expiry, similar replacement IDs, and group IDs as non-destructive/unconfirmed observations and cover each decision in focused tests.

## 5. Durable Child Mutation Application

- [x] 5.1 Refactor child application around persisted reconciliation operations while preserving per-child failure isolation and successful sibling outcomes.
- [x] 5.2 Apply create operations with stable import IDs and record the returned child transaction ID as the active mirror.
- [x] 5.3 Apply same-budget update operations to the existing child transaction ID, preserving memo and resetting the child transaction to cleared and unapproved.
- [x] 5.4 Convert an update whose lookup/update reports a missing child transaction into a retry-safe replacement create and record the new child transaction ID.
- [x] 5.5 Apply delete operations and treat the documented already-absent result as idempotent success.
- [x] 5.6 Enforce operation dependencies so replacement creation cannot run before cross-budget deletion succeeds.
- [x] 5.7 Record each mutation attempt and failure reason without marking failed operations complete or duplicating already applied operations.
- [x] 5.8 Add focused application tests for create, update, delete, missing-child recreation on authoritative and no-op edits, repeated delete, partial batches, child isolation, state-write failures, and process-restart retries.
- [x] 5.9 Define and test crash-window recovery after remote create, update, and delete success but before local completion recording.

## 6. Sync Orchestration And Cursor Safety

- [x] 6.1 Separate bootstrap transaction reads from established-cursor delta reads and omit `since_date` after cursor establishment unless the verified YNAB contract proves the combined filters safe.
- [x] 6.2 Persist changed source revisions and deterministic pending operations before applying any live child mutation.
- [x] 6.3 Update cycle result aggregation so pending or failed create/update/delete work prevents transaction cursor advancement while idempotently completed work permits it.
- [x] 6.4 Preserve successful operation outcomes when another operation or child fails, and ensure rereading the blocked delta retries only unfinished work.
- [x] 6.5 Ensure parent tombstones, unapproved changes, unmapped changes, and empty deltas participate in run status and cursor behavior even when they create no child transaction.
- [x] 6.6 Extend dry-run orchestration to read supported mirrors/cursors and log ordered creates, updates, deletions, reroutes, and recreations without YNAB mutations or SQLite writes; reject unsupported state unchanged.
- [x] 6.7 Add coordinator and run-loop tests for no-op edits, successful mutation batches, partial failures, empty deltas, dry-run behavior, and cursor advancement after recovery.
- [x] 6.8 Process complete money-movement snapshots and retry movement operations independently so movement failures never block transaction ingestion-batch cursor completion.

## 7. Paired Unit And Integration Coverage

- [x] 7.1 Create focused unit specs for source normalization, desired-state reconciliation, operation application, stable import identity, and cursor completion; keep each semantic in the smallest responsible spec.
- [x] 7.2 Add paired unit and WireMock integration coverage proving date/amount/payee/approval/cleared edits update one child transaction, preserve its memo, and retain stable source/mirror lineage.
- [x] 7.3 Add paired unit and integration coverage for same-budget account updates, cross-budget delete-before-create rerouting, dependency failures, exact token separation, and retry without repeating completed deletes.
- [x] 7.4 Add paired unit and integration coverage for parent deletion, unapproval, mapped-to-unmapped changes, no reversal creation, idempotent repeated deletion, and cursor completion.
- [x] 7.5 Add paired unit and integration coverage for ordinary-to-split, split-to-ordinary, split add/edit/remove, partial-composition fetch safety, deterministic ordering, and unrelated mirror preservation.
- [x] 7.6 Add paired unit and integration coverage for missing-child recreation on authoritative and no-op source edits while preserving prior child-ID audit history.
- [x] 7.7 Add paired unit and integration coverage for same-ID money-movement edits/reroutes, inflow/outflow identity, lookback corrections, non-destructive absence, replacement-ID limitations, unchanged snapshot idempotency, and cursor independence.
- [x] 7.8 Add exact real-SQLite coverage for baseline version 1, all compatibility rejections, missing/supported dry-run state, repeated initialization, and rollback.
- [x] 7.9 Add paired unit and integration coverage for dry-run reporting of every operation type with zero HTTP mutations and byte-for-byte unchanged SQLite state.
- [x] 7.10 Add a three-process-cycle integration scenario where the first cycle partially applies transaction and movement update/delete work, the second retries only unfinished work, and the third applies new incremental work with independent cursor outcomes.
- [x] 7.11 Add crash-window integration coverage for remote create/update/delete success followed by local completion failure, proving recovery creates no second financial effect.
- [x] 7.12 Maintain a scenario-level coverage matrix naming at least one focused unit test and one integration test for every normative scenario in `specs/parent-transaction-reconciliation/spec.md`; fail implementation review if any row is missing or skipped without a revised requirement.
- [x] 7.13 Remove the superseded planner/runtime surface and assertions while preserving unrelated allowance and sync behavior.

## 8. Documentation And Operational Rollout

- [x] 8.1 Finalize `PARENT_TRANSACTION_RECONCILIATION.md` with human-readable authoritative/child-owned semantics, transaction and split examples, money-movement capabilities and limitations, failure/retry behavior, fresh-state schema versioning, dry-run, and rollout safety.
- [x] 8.2 Keep `README.md` and `QUICK_START.md` linked to the semantics guide and update configuration/manual-testing guidance to match the final reviewed behavior.
- [x] 8.3 Document that old databases must be deleted, unsupported schemas are rejected without mutation, and operators must run `--dry-run --max-cycles 1` before the first live reconciliation.
- [x] 8.4 Document rollback limits after remote updates/deletions and the operation-history information available for manual recovery.
- [x] 8.5 Verify the human semantics guide, normative spec, and scenario-level unit/integration coverage matrix agree exactly.
- [x] 8.6 Run `./gradlew testAll` and resolve every unit and integration failure.
- [x] 8.7 Run `./gradlew installDist` to verify packaged entry points and runtime classpaths.
- [x] 8.8 Run a documented one-cycle sync dry run against a non-production/test configuration and verify that planned reconciliation performs no child or SQLite writes.
- [x] 8.9 Remove old-state conversion, historical-table, automatic-cleanup, and superseded runtime claims from every active change artifact and owned operator document.
- [x] 8.10 Rewrite manual SQLite checks for the current nine tables and decision/outcome audit records.

## Context

The current CLI has one primary responsibility: calculate allowance/interest transactions and post them to a single parent YNAB budget selected by `budgetName`. It already has useful building blocks for a sync feature — config-driven startup, a small YNAB repository wrapper, transaction draft models, and dry-run semantics — but it does not yet read parent-budget transactions, read money movements, resolve multiple budgets/tokens, run continuously, or persist sync cursors/idempotency state.

The new feature must bridge two different YNAB modeling styles. In the parent budget, each child’s “bank accounts” are categories. In the child budget, those same “bank accounts” must be represented as YNAB accounts so the child can budget against account-backed transactions. The sync therefore needs explicit mapping data and cannot rely on the existing allowance-only naming heuristics.

A second complication is that parent-budget money movements are time-limited in the YNAB API. If the tool does not poll and persist what it has already seen, a later rerun could either miss movements entirely or recreate them more than once in child budgets. Because the sync can create real transactions in more than one budget and is intended to run continuously, dry-run visibility, file-based logging, and durable idempotent state are mandatory.

## Goals / Non-Goals

**Goals:**
- Add a separate sync-focused entry point and wrapper scripts so parent/child syncing can be run independently from allowance recording.
- Keep the existing allowance/interest flow intact and operationally isolated so allowance runs continue to affect only the parent budget.
- Add a continuously polling sync workflow that reads approved/categorized parent-budget transactions and recent money movements, derives child-budget transaction drafts, and posts them only to the matching child budgets.
- Support split transactions by inspecting both top-level `category_id` and any `subtransactions[*].category_id`, creating one child-budget transaction per relevant subtransaction when needed.
- Persist enough sync state in SQLite to avoid duplicate child-budget transactions across reruns/poll iterations and to capture ephemeral money movements before they age out of the YNAB API.
- Preserve the existing Java 25 / Gradle 9 / Groovy 5 baseline while adding proper file-based rolling logs, configurable log levels, and sync-specific operational controls.

**Non-Goals:**
- Making the allowance command directly create child-budget transactions.
- Replacing the current allowance/interest feature with a separate application or removing the existing CLI.
- Full bidirectional reconciliation of edits made later in child budgets.
- Automatically discovering child mappings from YNAB naming conventions alone.
- Syncing every possible YNAB entity (goals, scheduled transactions, reconciliations, notes, or attachments).

## Decisions

### 1. Budget syncing will be a separate entry point with separate Bash/Batch wrappers
The sync capability should be runnable independently from the allowance command. The repo should therefore ship a dedicated sync entry point (for example a new main class such as `ParentChildBudgetSyncer`) plus sync-specific Bash and `.bat` wrappers. This keeps the “YNAB Parent Child Budget Syncer” usable as a standalone tool even for families that do not use the allowance/interest feature.

Alternatives considered:
- Reusing the existing `RecordAllowance` entry point with a mode flag was rejected because the user explicitly wants the syncer to be separately runnable and operationally distinct.
- Folding sync into the allowance run was rejected because allowance recording must not directly create child-budget transactions.

### 2. The allowance workflow remains operationally isolated from the sync workflow
The current allowance entry point should continue to resolve one parent budget and post allowance/interest transactions only there. Child-budget creation is deferred entirely to the syncer’s next polling cycle. This preserves existing behavior and reduces coupling between periodic allowance posting and continuous child-budget mirroring.

Alternatives considered:
- Dual-writing child transactions directly from the allowance flow was rejected explicitly by the user.
- Sharing one orchestration path for both commands was rejected because the commands have materially different runtime contracts.

### 3. The syncer will run continuously with configurable polling and dry-run-safe behavior
Instead of a one-shot command only, the syncer should run in a loop: poll parent source data, plan child mutations, log/apply them, sleep for a configured interval, and repeat until stopped. In `--dry-run`, it must still perform reads, planning, filtering, lookup validation, and state-calculation work, but only log what transactions and SQLite writes it would perform.

Alternatives considered:
- Pure one-shot CLI sync was rejected because the user wants continuous operation.
- Writing state during dry-run was rejected because it would suppress future real work without posting transactions.

### 4. Per-budget access tokens will be configured indirectly via environment-variable names
The YAML config should identify, for each parent/child budget, the environment-variable name that contains that budget’s YNAB access token. This keeps secrets out of committed config and allows arbitrary numbers of budgets. Runtime validation should fail fast if a required env var name is missing from config or resolves to a blank environment value.

Alternatives considered:
- Storing access tokens directly in YAML was rejected for security/portability reasons.
- Reusing one global `YNAB_ACCESS_TOKEN` for all budgets was rejected because different budgets may belong to different accounts or sharing models.

### 5. Qualifying parent transactions include both top-level categories and split subtransactions
The syncer must inspect approved parent transactions for relevant child-bank categories in either:
- the transaction’s own `category_id`, or
- any `subtransactions[*].category_id`

If a split transaction contains multiple relevant child subtransactions, the syncer should create one child-budget transaction per relevant subtransaction. Because split parents may have `category_id = null`, the implementation cannot depend solely on the top-level category field.

Alternatives considered:
- Ignoring splits was rejected explicitly by the user.
- Mirroring one aggregate child transaction per split parent was rejected because the user wants one child transaction per relevant subtransaction.

### 6. Child-budget mirrored transactions should preserve source information but leave category unset
When creating child-budget transactions, the syncer should copy over as much source information as possible, including date, memo, and amount. The child-side category should be left empty/unset because the parent budget’s category represents the child account in the sync model rather than a reusable child-side category assignment.

For money movements where source metadata is sparse, the first implementation should derive:
- memo: `From <sourceCategory> to <destinationCategory>`
- payee for child-budget inflow: `From <sourceCategory>`
- payee for child-budget outflow: `To <destinationCategory>`
- category: empty/unset

Alternatives considered:
- Copying the parent category into the child budget was rejected because the parent category semantically maps to the child account.
- Inventing child-side categories by naming heuristics was rejected because category assignment should remain explicit or empty.

### 7. Sync state will use SQLite with explicit source/target correlation records
The syncer should persist state in a SQLite database, defaulting to `syncstate.db` in the local working directory, with a CLI override `--sync-state-db-path`. The schema should track source budget IDs, target budget IDs, parent transaction IDs, parent subtransaction IDs, money movement IDs, and created child transaction IDs so reruns can identify already-processed source events per child target.

Alternatives considered:
- Flat files / JSON state were rejected because the user explicitly asked for SQLite and relational correlation data.
- Timestamp-only state was rejected because it cannot safely model partial failures or fan-out across multiple child budgets.

### 8. File-based logging with rolling files and configurable log levels is part of the feature, not an afterthought
A continuously running syncer needs operational logging that survives terminal sessions. The sync entry point should therefore use file appenders with log rotation/rolling policy and configurable levels, while still supporting console visibility when helpful. Dry-run should log both planned child-budget transactions and planned SQLite writes.

Alternatives considered:
- Console-only logging was rejected because the process is intended to run continuously.
- Reusing allowance logging defaults without sync-specific controls was rejected because the syncer has different operational needs.

## Proposed SQLite Schema

The exact table/column names can still be refined during implementation, but the first design should include at least the following:

### `sync_runs`
Tracks each polling iteration / execution attempt.

Columns:
- `id` INTEGER PRIMARY KEY
- `started_at` TEXT NOT NULL
- `completed_at` TEXT NULL
- `dry_run` INTEGER NOT NULL
- `status` TEXT NOT NULL -- e.g. running/succeeded/failed/partial
- `error_summary` TEXT NULL
- `polling_interval_seconds` INTEGER NULL
- `source_budget_id` TEXT NOT NULL

### `source_events`
Normalizes the parent-side event that may drive sync behavior.

Columns:
- `id` INTEGER PRIMARY KEY
- `source_budget_id` TEXT NOT NULL
- `event_type` TEXT NOT NULL -- `transaction`, `subtransaction`, or `money_movement`
- `parent_transaction_id` TEXT NULL
- `parent_subtransaction_id` TEXT NULL
- `money_movement_id` TEXT NULL
- `money_movement_group_id` TEXT NULL
- `event_date` TEXT NULL
- `ynab_server_knowledge` INTEGER NULL
- `fingerprint` TEXT NOT NULL UNIQUE
- `raw_summary_json` TEXT NULL
- `created_at` TEXT NOT NULL

Constraints / notes:
- exactly one of (`parent_transaction_id`, `parent_subtransaction_id`, `money_movement_id`) must anchor the event type
- `fingerprint` should encode enough information to distinguish a top-level categorized transaction from a relevant subtransaction or money movement

### `sync_mappings`
Represents the derived target work item for a given source event and child target.

Columns:
- `id` INTEGER PRIMARY KEY
- `source_event_id` INTEGER NOT NULL REFERENCES `source_events`(`id`)
- `target_budget_id` TEXT NOT NULL
- `target_child_key` TEXT NOT NULL -- stable config key/name for the child target
- `target_account_id` TEXT NULL
- `direction` TEXT NOT NULL -- `inflow` or `outflow`
- `planned_amount` INTEGER NOT NULL
- `planned_date` TEXT NOT NULL
- `planned_payee_name` TEXT NULL
- `planned_memo` TEXT NULL
- `planned_category_id` TEXT NULL -- expected to remain null for first implementation
- `idempotency_key` TEXT NOT NULL UNIQUE
- `last_planned_at` TEXT NOT NULL

### `applied_transactions`
Records actual child-budget writes corresponding to a planned mapping.

Columns:
- `id` INTEGER PRIMARY KEY
- `sync_mapping_id` INTEGER NOT NULL REFERENCES `sync_mappings`(`id`)
- `sync_run_id` INTEGER NOT NULL REFERENCES `sync_runs`(`id`)
- `target_budget_id` TEXT NOT NULL
- `created_child_transaction_id` TEXT NULL
- `applied_at` TEXT NULL
- `status` TEXT NOT NULL -- planned/applied/failed/skipped
- `failure_reason` TEXT NULL
- `dry_run` INTEGER NOT NULL

### `sync_cursors`
Stores resumable/high-water-mark style sync state.

Columns:
- `key` TEXT PRIMARY KEY
- `value_text` TEXT NULL
- `value_integer` INTEGER NULL
- `updated_at` TEXT NOT NULL

Suggested keys:
- `transactions.last_server_knowledge`
- `money_movements.last_server_knowledge`
- optional `last_successful_poll_started_at`

### Why this shape
- lets us correlate one source event to many child-target outcomes
- supports dry-run planning without pretending writes happened
- supports partial failure reporting per child target
- keeps money movement IDs, transaction IDs, and budget IDs all queryable
- leaves room for future reconciliation/reporting queries

## YNAB API surfaces to ground the implementation

Research from the current YNAB API docs indicates the sync plan should explicitly account for these surfaces:

### Plans / budgets
- `GET /v1/plans`
  - returns plan summaries with `id`, `name`, and `last_modified_on`
- `GET /v1/plans/{plan_id}`
  - supports `last_knowledge_of_server`
  - returns a full plan export including `transactions`, `subtransactions`, `category_groups`, and `server_knowledge`

### Transactions
- `GET /v1/plans/{plan_id}/transactions`
  - returns plan transactions excluding pending transactions
  - supports `since_date`, `until_date`, `type`, and `last_knowledge_of_server`
  - docs show transaction fields including `id`, `date`, `amount`, `memo`, `cleared`, `approved`, `account_id`, plus expanded fields such as `account_name`, `payee_name`, `category_name`, and nested `subtransactions`
- transaction filtering note from docs:
  - `type` supports `uncategorized` and `unapproved`
  - for this sync feature we should still fetch the broader set needed for mapped categories and then skip `approved == false`
- docs show nested `subtransactions` with fields including:
  - `id`
  - `transaction_id`
  - `amount`
  - `memo`
  - `payee_id`
  - `payee_name`
  - `category_id`
  - `category_name`
  - `transfer_account_id`
  - `transfer_transaction_id`
- `POST /v1/plans/{plan_id}/transactions`
  - creates a single transaction or multiple transactions
  - supports `import_id`
  - request body supports `subtransactions` for split creation, though the first sync implementation will create ordinary child transactions rather than child-side split rewrites
  - docs note that if `import_id` is provided, the created transaction is treated as imported and YNAB attempts duplicate matching against existing same-account, same-amount, near-date transactions

### Money movements
- `GET /v1/plans/{plan_id}/money_movements`
  - returns all money movements
  - docs show fields including:
    - `id`
    - `month`
    - `moved_at`
    - `note`
    - `money_movement_group_id`
    - `performed_by_user_id`
    - `from_category_id`
    - `to_category_id`
    - `amount`
- `GET /v1/plans/{plan_id}/months/{month}/money_movements`
  - returns all money movements for a specific month
- `GET /v1/plans/{plan_id}/money_movement_groups`
  - returns grouping metadata such as `id`, `group_created_at`, `month`, and `note`

### Accounts / categories
- `GET /v1/plans/{plan_id}/accounts`
  - used to resolve child account names to child account IDs and parent account metadata as needed
- `GET /v1/plans/{plan_id}/categories`
  - used to resolve parent category names to category IDs and to identify child-linked bank categories

## Risks / Trade-offs

- **Config complexity increases** → Mitigation: keep sync config nested, validate required fields up front, and ship updated example config/docs with one clear example per child.
- **Continuous runtime introduces operational concerns** → Mitigation: add file-based rolling logs, explicit polling interval config, and clear shutdown/error behavior.
- **YNAB API response details may drift from current doc assumptions** → Mitigation: isolate API-shape handling in repository/model classes and cover parsing with fixture-backed tests.
- **Duplicate postings after partial failure** → Mitigation: persist applied child-target state only after successful child-budget post completion and keep per-target status rows for retries.
- **Missed money movements because they age out** → Mitigation: use a configurable lookback window and/or server-knowledge cursor, plus durable processed fingerprints, so the tool can safely reread recent history.
- **One parent event may need fan-out across multiple children** → Mitigation: define planning models that expand one source event into N child drafts and store idempotency per target child.
- **Independent child tokens may fail independently** → Mitigation: surface child-by-child logging/results and keep sync state scoped so one failed child target does not mark other targets as complete.

## Migration Plan

1. Add sync-specific config models and validation for parent/child budgets, token env var names, logging, polling, and SQLite-state settings while leaving existing allowance-only configs valid.
2. Add a dedicated sync entry point and wrapper scripts without changing the current allowance entry point semantics.
3. Add repository/model support for reading parent transactions, parent subtransactions, parent money movements, child accounts/categories, and writing child transactions.
4. Implement SQLite schema bootstrap and repositories for source events, per-child mappings, applied transactions, and cursors.
5. Implement sync planning/orchestration with dry-run output first, then real posting and persisted idempotency state.
6. Add/update automated tests for config validation, split-transaction handling, money-movement fan-out, duplicate prevention, logging/dry-run behavior, and SQLite persistence.
7. Update `config.yaml.example`, `README.md`, and `QUICK_START.md` with safe setup guidance, including separate token env vars and recommended dry-run-first rollout.
8. Verify with `./gradlew test`, `./gradlew installDist`, and a documented sync dry-run invocation before any real posting run.

## Resolved Questions

- **Should the sync run automatically as part of the normal allowance command?** No. It should be a separate entry point with separate wrappers and lifecycle.
- **How should sparse money-movement child transactions derive payee/memo/category?** Memo should be `From <sourceCategory> to <destinationCategory>`; payee should be `From <sourceCategory>` for inflows and `To <destinationCategory>` for outflows; category should be empty.
- **Where should sync state live?** In SQLite, defaulting to local `syncstate.db`, with CLI override `--sync-state-db-path`.

## Context

The current CLI has one primary responsibility: calculate allowance/interest transactions and post them to a single parent YNAB budget selected by `budgetName`. It already has the right building blocks for a sync feature — config-driven startup, a small YNAB repository wrapper, transaction draft models, and a dry-run mode — but it does not yet read parent-budget transactions, read money movements, resolve multiple budgets/tokens, or persist sync cursors/idempotency state.

The new feature must bridge two different YNAB modeling styles. In the parent budget, each child’s “bank accounts” are categories. In the child budget, those same “bank accounts” must be represented as YNAB accounts so the child can budget against account-backed transactions. The sync therefore needs explicit mapping data and cannot rely on the existing allowance-only naming heuristics.

A second complication is that parent-budget money movements are time-limited in the YNAB API. If the tool does not poll and persist what it has already seen, a later rerun could either miss movements entirely or recreate them more than once in child budgets. Because the CLI can create real transactions in more than one budget, dry-run visibility and idempotent posting are mandatory.

## Goals / Non-Goals

**Goals:**
- Add a config-driven way to define one or more child budget sync targets, including per-child access token indirection, parent category mappings, child account names, and child-side categorization defaults.
- Add a sync workflow that reads approved/categorized parent-budget transactions and recent money movements, derives child-budget transaction drafts, and posts them only to the matching child budgets.
- Persist enough sync state to avoid duplicate child-budget transactions across reruns and to capture ephemeral money movements before they age out of the YNAB API.
- Preserve the existing Java 25 / Gradle 9 / Groovy 5 baseline and keep `RecordAllowance` as a thin orchestrator that delegates to focused sync collaborators.
- Keep the existing allowance/interest flow intact so users can still run the current behavior with the same dry-run safety expectations.

**Non-Goals:**
- Replacing the current allowance/interest feature with a separate application or service.
- Introducing webhooks, daemons, or a permanently running scheduler in this change; polling remains CLI-driven.
- Automatically discovering child mappings from YNAB naming conventions alone.
- Attempting to sync every possible YNAB entity (goals, scheduled transactions, reconciliations, notes, or attachments).
- Solving conflict resolution for child-budget edits made after the mirrored transaction is created; this change focuses on parent-to-child creation, not full bidirectional sync.

## Decisions

### 1. Add explicit sync configuration instead of inferring children from the existing allowance config
The existing config is organized around allowance rates and category suffix heuristics. Parent/child syncing needs richer data: parent budget token/budget identity, child budget token source, child budget name, mapped parent category names, mapped child account name, optional child category name/payee naming, and sync/state options. A new nested config section keeps this data explicit and testable.

Alternatives considered:
- Reusing only `kidsWithAdvancedAccounts` and suffix matching was rejected because sync targets may use independent YNAB accounts, independent tokens, and non-standard account/category names.
- Hard-coding one child-budget structure in code was rejected because the repo already prefers portable YAML configuration.

### 2. Keep one CLI entry point but split sync work into dedicated collaborators
`RecordAllowance` should remain the executable entry point, but parent/child syncing is cross-cutting enough to deserve dedicated collaborators such as:
- a transaction/money-movement reader in `YnabBudgetRepository`
- sync planning models for parent source events and child posting drafts
- a `ParentChildSyncService` or similarly named orchestrator
- a small persisted `SyncStateRepository` for cursors/idempotency keys

This matches the repo’s modularization direction and avoids expanding `RecordAllowance` back into a monolith.

Alternatives considered:
- A separate standalone executable for syncing was rejected for now because the user asked for one remaining repo task and the existing runtime already owns YNAB auth/config/logging/dry-run behavior.
- Embedding all sync logic directly in `RecordAllowance` was rejected because it would undo the recent modularity work.

### 3. Mirror only approved, categorized parent transactions
The user’s process depends on the parent reviewing imported transactions first. The sync should therefore ignore unapproved or uncategorized parent transactions and only mirror transactions whose category is mapped to a child bank category. This makes the child budget reflect deliberate parent review rather than raw bank feed noise.

Alternatives considered:
- Mirroring unapproved imports was rejected because it would teach the child from tentative or incorrectly categorized data.
- Syncing all transactions in a child-related YNAB account regardless of category was rejected because child bank balances in the parent budget are category-based, not account-based.

### 4. Treat money movements as independent source events with durable replay protection
Money movements lack the full metadata of normal transactions and disappear after roughly a month, so they need their own polling and idempotency path. The sync state should record a stable fingerprint per processed money movement and a last-seen watermark/lookback policy so reruns can revisit a recent window without double-posting.

Alternatives considered:
- Relying only on “last run timestamp” was rejected because clock skew or partially failed runs could cause missed or duplicated movements.
- Ignoring money movements until a later phase was rejected because the user explicitly called them out as required behavior.

### 5. Model each child-budget posting as an idempotent derived transaction draft
Every mirrored child transaction should be produced from a source-event fingerprint (parent transaction id or derived money-movement fingerprint + child target), then checked against persisted state before posting. This lets one money movement fan out to multiple child budgets safely while keeping reruns deterministic.

Alternatives considered:
- Deduplicating only by payee/date/amount was rejected because identical purchases or transfers can legitimately recur.
- Relying only on YNAB import de-duplication was rejected because child budgets may use different accounts/categories and the tool should own its own replay guarantees.

### 6. Preserve dry-run semantics across both parent posting and child sync posting
The current CLI already has a `--dry-run` safety contract. The sync design extends that contract so dry-run prints/logs the planned child-budget transaction drafts and state updates without writing either YNAB transactions or sync state. This keeps first-run rollout safe, especially with multiple access tokens and budgets involved.

Alternatives considered:
- Writing sync state during dry-run was rejected because it would hide future work without actually posting transactions.
- Adding a separate sync-only dry-run flag was rejected because the repo already has an established global dry-run behavior.

## Risks / Trade-offs

- **Config complexity increases** → Mitigation: keep child-sync config nested, validate required fields up front, and ship updated example config/docs with one clear example per child.
- **YNAB API response details for money movements/transactions may differ from assumptions** → Mitigation: implement against the live YNAB API contract, cover parsing with fixture-backed tests, and isolate API-shape handling in repository/model classes.
- **Duplicate postings after partial failure** → Mitigation: persist processed source fingerprints only after successful child-budget post completion and cover partial-failure behavior in tests.
- **Missed money movements because they age out** → Mitigation: use a configurable lookback window plus durable processed fingerprints so the tool can safely re-read recent history on every run.
- **One parent event may need fan-out across multiple children** → Mitigation: define planning models that expand one source event into N child drafts and store idempotency per target child, not just per source event.
- **Independent child tokens may fail independently** → Mitigation: surface child-by-child logging/results and keep the sync state scoped so one failed child target does not mark other targets as complete.

## Migration Plan

1. Extend config parsing and validation for child sync definitions, token environment variable names, and sync-state settings while leaving existing allowance-only configs valid when sync is disabled.
2. Add repository/model support for reading parent transactions, parent money movements, child accounts/categories, and writing child transactions.
3. Implement sync planning/orchestration with dry-run output first, then real posting and persisted idempotency state.
4. Add/update automated tests for config validation, source-event filtering, money-movement fan-out, duplicate prevention, and dry-run behavior.
5. Update `config.yaml.example`, `README.md`, and `QUICK_START.md` with safe setup guidance, including separate parent/child tokens and recommended dry-run-first rollout.
6. Verify with `./gradlew test`, `./gradlew installDist`, and a documented dry-run invocation before any real posting run.

## Open Questions

- Should the sync run automatically as part of the normal allowance command every time, or should there also be a sync-specific CLI mode/flag for running only the parent/child mirroring path?
- For child-budget mirrored transactions created from money movements, what default child-side category/payee conventions should the first implementation use when the source movement metadata is sparse?
- Do we want to persist sync state in a repo-local file path configured in YAML, or in a fixed default file next to `config.yaml` with an override option?

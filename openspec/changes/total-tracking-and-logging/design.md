## Context

See proposal.md for motivation. Cycle completion already lives in `ParentChildBudgetSyncer.logCycleCompletion` and prints per-child created/updated/deleted counts from in-memory planned intents. Parent category `balance` is already fetched each cycle via `GET /v1/plans/{plan}/categories`. Child account lists are already fetched via `GET /v1/plans/{plan}/accounts`, but `YnabBudgetRepository.accountIdByName` discards `balance`. `AccountSnapshot` currently has only `id` and `name`. Config already maps parent category matchers → `childAccountName`; comparison needs the inverse.

This change is read-only relative to YNAB writes and SQLite. `--dry-run` must stay write-free. A logged mismatch must not become a `SyncRunResult` failure.

## Goals / Non-Goals

**Goals:**

- Keep cycle-summary math in a focused collaborator so `logCycleCompletion` stays a logger
- Reuse existing category and account GETs; add at most one extra accounts GET per child after live apply
- Reverse mapping MUST invert the parent-category → child-account resolutions already computed during transaction propagation, not re-run `accountMappings` matchers
- Format logged money with `YnabLogFormatter.formatAmount`

**Non-Goals:**

- Changing reconciliation, cursors, SQLite schema, or run success/failure
- New config keys or CLI flags
- Comparing child *category* balances (child mirrors leave category unset)
- Calling live YNAB from `./gradlew test` / `testAll`
- Auto-creating accounts solely so a comparison can run

## Decisions

### 1. Extract `CycleBalanceReporter` instead of growing the syncer

`ParentChildBudgetSyncer.logCycleCompletion` keeps the existing count lines, then asks a new collaborator to emit net-change and comparison lines.

The reporter is a pure function over:

- the cycle's parent-category → child-account cache (see Decision 3)
- parent `CategorySnapshot`s (already loaded)
- planned mutating intents
- child `AccountSnapshot`s (`id`, `name`, `balance`)
- dry-run vs live

**Alternative considered:** inline everything in `logCycleCompletion`. Rejected because net-change, reverse mapping, and comparison are independently unit-testable and would bury the existing summary.

### 2. Preserve account `balance` from the existing accounts GET

YNAB account list responses in this repo already include `balance` (milliunits). Extend `AccountSnapshot` with `Integer balance = 0` and have `YnabBudgetRepository` return name → snapshot (existing `accountIdByName` can derive ids from that map). Do not add a new endpoint.

Live mode re-GETs each resolved child's accounts **after** apply so the child side is the API total, not start-of-cycle + math. Dry-run reuses the routing-time list (no mutations) and projects `current + netChange`. Missing accounts project from `0`.

**Alternative considered:** always project even in live mode. Rejected; the proposal requires live mode to use actual API account totals.

### 3. Cache the propagation mapping; reverse it for comparison

`DesiredMirrorFactory.forSource` already decides parent category → child account (`childAccountName` vs derived auto-create name, plus `accountId`). Re-running `accountMappings` matchers in the reporter can disagree with that (derived vs configured name, overlapping regexes, categories that never propagated).

Cycle-scoped in-memory cache, owned by the syncer and written at the moment a desired mirror is resolved:

- key: parent category name (and id when known)
- value: `childKey` + resolved `accountId` + resolved account name

Same category resolved twice in one cycle is idempotent. One parent category MAY cache into more than one child (different `childKey`s). Do not persist the cache; start empty each `runOnce`.

The reporter inverts that cache. Parent side for a child account = sum of `balance` for parent categories that actually mapped to it this cycle. Categories that never produced a desired mirror are not compared. Accounts that never appeared in the cache are not compared.

**Alternative considered:** a second matcher pass over every parent category. Rejected; it can deviate from the account the cycle actually used.

### 4. Net change comes from in-memory intents, with prior amount on UPDATE/DELETE

CREATE / UPDATE / DELETE net milliunits:

| Action | Net |
|---|---|
| CREATE | `payload.amount` |
| UPDATE | `payload.amount − priorAmount` |
| DELETE | `−priorAmount` |
| NO_OP | ignored |

`PlannedReconciliationIntent` does not currently carry account id or prior child amount. DELETE has no payload. Stamp two optional in-memory fields when planning (`targetAccountId`, `priorAmount`) so the reporter can roll up by account. Do **not** add SQLite columns; apply already has the data it needs, and cycle reporting uses this cycle's in-memory intents.

Account **name** for the log line comes from the child account snapshot (id → name), falling back to the mapping/derived name.

**Alternative considered:** re-GET each child transaction to learn prior amounts. Rejected; extra rate-limit cost, and planning already observed those children for UPDATE.

### 5. Compare cached accounts only; mismatches are WARN

Every child account that appears in this cycle's propagation cache is logged, including `netChange=0` (NO_OP / no mutating intents). Children that never resolved, and mapped accounts that never appeared in a desired mirror this cycle, are omitted. Existing routing-failure lines already cover unresolved children.

Log levels:

- INFO: per-account net change, child side, parent side, and `diff=$0.00`
- WARN: `diff` ≠ 0, including both sides and which child value was used (`projected` vs `actual`)
- Never add the mismatch to `SyncRunResult.failureMessages`

**Alternative considered:** ERROR or a failed run on mismatch. Rejected by the proposal; historical child activity and parent budgeting can diverge even when sync is correct.

### 6. Reporting GETs are read-only and must not create accounts

If a mapped account is missing, dry-run projects from zero. Live logs that the account was absent and skips a numeric compare for that row rather than calling create-account. Auto-create remains the existing routing path only.

## Risks / Trade-offs

- [Live extra accounts GET per child] → Mitigation: one list call per resolved child after apply; no per-account or per-transaction GETs.
- [Parent category `balance` is Available, child `balance` is account total] → Mitigation: document in the WARN line that mismatch is informational; do not fail the run.
- [Intent field additions vs persist] → Mitigation: keep new fields out of SQLite; reconstruct-from-DB paths default them to null.
- [WireMock account stubs omit `balance` today] → Mitigation: default missing `balance` to `0` so existing fixtures keep working; tests that assert comparison will set explicit balances.
- [UPDATE/DELETE without `priorAmount`] → Mitigation: treat missing prior as `0` and WARN that net change may be incomplete rather than inventing a number.

## Migration Plan

Logging-only. Deploy by shipping the build; no state migration. Rollback by reverting the commit. Existing `--dry-run` first remains the safe way to inspect the new lines against real budgets.

## Open Questions

None. Log wording can be tuned during implementation as long as child side, parent side, diff, and dry-run vs live are present.

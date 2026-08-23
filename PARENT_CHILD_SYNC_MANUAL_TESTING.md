# Parent/Child Budget Sync Manual Testing Guide

This guide is the step-by-step checklist to use when connecting the parent/child syncer to your real YNAB parent budget and your kids' real YNAB child budgets.

The syncer can create **real child-budget transactions** when it is run without `--dry-run`. Work through the dry-run scenarios first, then do only a small single-cycle live verification before considering continuous live polling.

The automated WireMock suite verifies default/custom memo decoration, `cleared: "cleared"`, and dry-run no-write behavior without live credentials. Run `./gradlew testAll` before using this guide; manual dry runs remain an operator safety check for real names and mappings, not a development verification requirement.

## What this guide covers

- Preparing real parent and child budget configuration
- Dry-run-only scenarios that should not create child transactions or persist replay state
- Live single-cycle scenarios that should create child transactions and persist replay state
- Idempotency/replay checks so reruns do not duplicate child transactions
- Failure checks for bad child tokens or missing child account mappings
- Final rollout checklist for continuous polling

## Important safety rules

1. **Never start live continuous polling as the first live test.** Use `--max-cycles 1` first.
2. **Always dry run with the exact `config.yaml` and token env vars you plan to use live.**
3. **Keep the SQLite state file once live testing starts.** Deleting it removes replay protection and can allow duplicate child transactions.
4. **Use small, reversible test transactions in YNAB.** Prefer obvious memo text like `SYNC TEST - child one spend` so cleanup is easy.
5. **Do not put raw YNAB tokens in `config.yaml`.** Put only env var names in YAML.
6. **Keep parent and child tokens separate if possible.** This makes token-scope mistakes easier to detect.

---

## Phase 0 — Prepare your local environment

### 0.1 Confirm the repo builds locally

From the repo root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testAll
./gradlew installDist
```

Expected result:

- `testAll` passes.
- `installDist` passes.
- No live YNAB transactions are created by either command.

### 0.2 Create or update your real local config

If you have not already created a local config:

```bash
cp config.yaml.example config.yaml
```

Edit only your local `config.yaml`; it is gitignored and should hold your real budget/account/category names.

At minimum, verify these fields under `sync:`:

```yaml
sync:
  parentBudget:
    budgetName: Your Real Parent Budget Name
    tokenEnvVarName: YNAB_PARENT_TOKEN

  childBudgets:
    - childKey: child-one
      budgetName: Your Child One Budget Name
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      memoPrefix: "[Child One] "
      memoSuffix: " (synced)"
      accountMappings:
        - mappingKey: spend
          parentCategoryNames:
            - name: "Exact Parent Category For Child One Spend/Bank"
          childAccountName: Exact Child One Spend Account Name
        - mappingKey: save
          parentCategoryNames:
            - name: "Exact Parent Category For Child One Save/Bank"
            - name: "Child One Gold CD.*"
              regex: true
          childAccountName: Exact Child One Save/CD Account Name

  pollingIntervalSeconds: 300

  logging:
    filePath: logs/parent-child-sync.log
    level: INFO
    maxHistory: 7
    maxFileSizeMb: 10

  state:
    sqlitePath: syncstate.db
    transactionLookbackDays: 45
    moneyMovementLookbackDays: 45
```

For every child, check:

- `childKey` is stable and unique, such as `sam`, `alex`, or `child-one`.
- `budgetName` exactly matches the YNAB child budget name.
- `tokenEnvVarName` is the **name** of the env var that will hold that child's token.
- `memoPrefix` and `memoSuffix` are optional strings. They default to `"YBOD: "` and `""`; empty strings are allowed.
- The final decorated memo is trimmed. Decoration and cleared status apply only to synced child transactions.
- `accountMappings[*].mappingKey` values are stable and unique within that child.
- `accountMappings[*].parentCategoryNames[*].name` entries exactly match parent categories unless `regex: true` is set.
- `accountMappings[*].childAccountName` exactly matches the child account that should receive that mapping's mirrored transactions.
- Literal mappings should be listed for normal category names; use `regex: true` only for intentional pattern matching. Exact literal matches win before regex matches, then first matching mapping wins.

### 0.3 Export real tokens

Use the env var names from your `config.yaml`:

```bash
export YNAB_PARENT_TOKEN='parent-token-here'
export YNAB_CHILD_ONE_TOKEN='child-one-token-here'
export YNAB_CHILD_TWO_TOKEN='child-two-token-here'
```

Expected result:

- The parent token can read the parent budget.
- Each child token can read and write only the intended child budget, or at least can access the intended child budget.

### 0.4 Choose a real state database path

For dry-run testing, use a disposable path so you can inspect behavior without touching live replay state:

```bash
DRY_STATE_DB=build/tmp/manual-sync-dry-run.db
rm -f "$DRY_STATE_DB"
```

For live testing, use the long-lived path you intend to keep:

```bash
LIVE_STATE_DB=syncstate.db
```

If `$LIVE_STATE_DB` was created by an earlier build, stop every syncer process and delete it before testing this build. The syncer requires fresh state and rejects nonempty unversioned, newer, noncontiguous, and otherwise unsupported schemas without mutation. Deleting state does not remove child transactions created by an earlier syncer, so resolve any matching creates reported by the first dry run before live mode. After live transactions have been posted with the supported schema, do **not** delete `$LIVE_STATE_DB` unless you intentionally want to reset replay protection.

### 0.5 Start with logs visible

In a second terminal, tail the sync log configured in `config.yaml`:

```bash
tail -f logs/parent-child-sync.log
```

If the file does not exist yet, start the first dry run, then run the `tail` command after the syncer creates the log directory/file.

---

## Phase 1 — Dry-run scenarios

Dry-run mode should read real YNAB parent and child metadata, plan work, and log planned child mutations. It should **not** post child transactions and should **not** persist mutable sync-state records.

Use this command shape for every dry-run scenario:

```bash
./gradlew runSyncer --args="--dry-run --config config.yaml --sync-state-db-path $DRY_STATE_DB --max-cycles 1"
```

Expected common dry-run behavior:

- The process exits after one cycle.
- Logs show `Starting parent-child budget syncer`.
- Logs show `Cycle 1 read ... parent transactions and ... money movements`.
- For qualifying work, logs include `[DRY RUN] <action> child transaction for <childKey> ... payload=...`.
- No new transaction appears in any child budget.
- Mutable replay-protection rows should not be persisted for the planned work.
- A missing dry-run database remains absent; a supported database remains byte-for-byte unchanged.

### 1.1 Smoke test: config, token, budget, account, log, and SQLite bootstrap

1. Confirm all real tokens are exported.
2. Confirm `DRY_STATE_DB` points to a disposable file.
3. Run:

   ```bash
   ./gradlew runSyncer --args="--dry-run --config config.yaml --sync-state-db-path $DRY_STATE_DB --max-cycles 1"
   ```

4. Inspect terminal output and `logs/parent-child-sync.log`.

Pass criteria:

- The run reaches YNAB successfully; no HTTP 401/403 errors.
- The configured parent budget is found.
- Every configured child budget is found if there is qualifying work for that child.
- Every configured `accountMappings[*].childAccountName` is found if there is qualifying work for that child.
- The run exits by itself because of `--max-cycles 1`.

If it fails:

- HTTP 401/403 usually means a missing/wrong token env var or token permission issue.
- Budget lookup failure usually means `budgetName` does not exactly match YNAB.
- Account lookup failure usually means `accountMappings[*].childAccountName` does not exactly match the child budget account.
- Category mismatch usually means `accountMappings[*].parentCategoryNames[*].name` does not exactly match parent budget category names.

### 1.2 No-op dry run: no qualifying parent activity

Purpose: prove unrelated or unmapped parent activity is ignored.

1. In the parent budget, make sure there are no recent approved transactions or money movements in the configured account mapping category matchers, or temporarily choose a child mapping category with no recent activity.
2. Run the dry-run command.

Pass criteria:

- Logs show the cycle completed.
- Logs include `No qualifying child sync work found`, or no `[DRY RUN] child transaction` lines.
- No child-budget transactions are created.

### 1.3 Approved parent transaction mirrors to one child

Purpose: verify the core parent transaction → child transaction path.

1. In the parent budget, create a small test transaction using a category configured for one child, for example `$0.01` or `$1.00`.
2. Use an obvious memo, for example `SYNC TEST - child one approved transaction`.
3. Approve the parent transaction in YNAB.
4. Run the dry-run command.
5. Inspect the `[DRY RUN] child transaction for <childKey> -> ...` JSON in logs.

Pass criteria:

- Exactly the expected child has a planned child transaction.
- The planned child transaction uses the configured child account.
- The planned date matches the parent transaction date.
- The planned amount matches the parent transaction amount.
- The planned memo preserves the source memo where available.
- The planned memo has the configured prefix/suffix and no leading or trailing whitespace after final trimming.
- The planned payload contains `cleared: "cleared"`.
- The planned child category is unset/unspecified.
- No actual child transaction is created.

### 1.4 Unapproved parent transaction is ignored

Purpose: prove pending/unapproved parent transactions are not mirrored too early.

1. In the parent budget, create a small test transaction in a mapped child category.
2. Leave it unapproved.
3. Use memo `SYNC TEST - unapproved should not sync`.
4. Run the dry-run command.

Pass criteria:

- There is no `[DRY RUN] child transaction` for that unapproved transaction.
- No child transaction appears.

Then approve the same parent transaction and rerun the dry-run command.

Pass criteria after approval:

- The planned child transaction appears after approval.

### 1.5 Unmapped parent transaction is ignored

Purpose: prove only configured parent categories mirror into child budgets.

1. In the parent budget, create and approve a small transaction in a category **not** listed in any `sync.childBudgets[*].accountMappings[*].parentCategoryNames[*].name`.
2. Use memo `SYNC TEST - unmapped should not sync`.
3. Run the dry-run command.

Pass criteria:

- No child transaction is planned for this parent transaction.
- No child-budget transaction is created.

### 1.6 Split parent transaction fans out by subtransaction category

Purpose: verify split handling and stable per-subtransaction source identity.

1. In the parent budget, create one approved split transaction.
2. Add at least two subtransactions:
   - one subtransaction categorized to child one's mapped parent category
   - one subtransaction categorized to child two's mapped parent category, or to a second mapped category for the same child if you only want to test one child
3. Use memo `SYNC TEST - split fanout`.
4. Run the dry-run command.
5. Inspect logs.

Pass criteria:

- The syncer plans one child transaction per relevant mapped subtransaction.
- Each planned child transaction goes to the expected child target.
- Amounts match the subtransaction amounts, not the entire parent split amount.
- Unmapped subtransactions, if any, are ignored.
- No child-budget transactions are created.

### 1.7 Parent money movement into one child category creates one child plan

Purpose: verify recent parent money movement conversion.

1. In the parent budget, create a small money movement that affects one mapped child parent-bank category.
2. Run the dry-run command soon after creating it, while it is still in the configured `moneyMovementLookbackDays` window.
3. Inspect the planned transaction log.

Pass criteria:

- The syncer plans one child transaction for the affected child.
- The memo follows the convention `From <sourceCategory> to <destinationCategory>`.
- The payee is derived as:
  - `From <sourceCategory>` for child inflows
  - `To <destinationCategory>` for child outflows
- The child category is unset/unspecified.
- No child-budget transaction is created.

### 1.8 Parent money movement between two child categories fans out to both children

Purpose: verify one parent money movement can create separate child effects.

1. In the parent budget, create a small movement from one mapped child category to another mapped child category.
2. Run the dry-run command.
3. Inspect planned child transaction logs.

Pass criteria:

- The syncer plans work for both affected child targets.
- Replay/idempotency keys are separate per child target.
- Amount direction makes sense for each child.
- No child-budget transactions are created.

### 1.9 Multi-child, multi-account mapping matrix dry run

Purpose: validate the new `accountMappings` behavior end to end before live posting. This is the most important manual coverage for the account-mapping change because it exercises multiple child budgets, multiple child accounts inside each budget, exact literals, regex matchers, multiple parent categories mapped to a single child account, top-level transactions, split transactions, and money movements in one run.

Recommended setup:

1. Configure at least four child budgets, each with at least two child accounts and preferably three account mappings, for example:
   - spend/checking account
   - save/give account
   - CD or long-term savings account
2. For at least two children, map multiple parent categories to the same child account, for example:
   - `Child One Save Bank` and regex `Child One Bonus.*` -> `Child One Save Account`
   - `Child Four Spend Bank` and regex `Child Four Bonus.*` -> `Child Four Spend Account`
3. Include at least one `regex: true` matcher that should match a date-like or suffix-like category, for example `Child Two Gold CD.*` or `Child Four Gold CD.*`.
4. Include at least one literal category whose name contains punctuation or text that could be confused with regex so you can confirm literal entries still match literally when `regex` is omitted.
5. Run the dry-run command once after creating all test parent activity below.

Create parent activity:

1. Create approved top-level transactions in mapped parent categories for all four children.
2. Create at least one approved split transaction with mapped subtransactions for multiple children and multiple destination child accounts.
3. Include one unmapped split line to prove it is ignored.
4. Create one money movement from a mapped category for one child to a mapped category for another child.
5. Create one money movement involving a regex-matched CD/long-term category and another child's literal mapped category.
6. Leave one mapped-category transaction unapproved to prove it is ignored until approval.

Pass criteria:

- Logs show planned child transactions for all four child targets.
- Every planned child transaction lands on the configured child account for the selected mapping, not just the first account configured for that child.
- Literal-only categories match exactly with `regex` omitted/defaulted to `false`.
- Regex categories match only where `regex: true` is configured.
- Multiple parent categories mapped to one child account all plan against that same child account.
- Split transaction subtransactions fan out by each subtransaction category and amount.
- Money movements produce the expected inflow/outflow effects, memo, and payee on each affected child side.
- The unapproved mapped transaction and unmapped split line do not create planned child transactions.
- No actual child-budget transactions are created in dry-run mode.

### 1.10 Dry-run replay check: rerun the same dry run

Purpose: confirm dry-run does not mark source events as processed.

1. Pick any scenario above that produces `[DRY RUN] child transaction` lines.
2. Run the same dry-run command twice with the same `$DRY_STATE_DB`.

Pass criteria:

- The same planned work appears on both dry runs.
- No child transactions are created.
- This is expected because dry-run logs planned SQLite state but does not persist mutable replay-protection records.

### 1.10 Bad child token dry-run failure isolation

Purpose: verify failures identify the child target.

1. Temporarily point one child's token env var to an invalid value:

   ```bash
   export YNAB_CHILD_ONE_TOKEN='intentionally-invalid-token'
   ```

2. Leave at least one other child token valid.
3. Run a dry run that has qualifying work for multiple children.
4. Restore the real token immediately afterward.

Pass criteria:

- Logs identify the failed child target, for example `Child sync target child-one failed`.
- The error message contains useful authentication/lookup context.
- Other child targets are not mislabeled as the failed child.
- No child transactions are posted because this is dry-run mode.

Restore the real token:

```bash
export YNAB_CHILD_ONE_TOKEN='real-child-one-token-here'
```

---

## Phase 2 — Live single-cycle scenarios

Live mode can post child transactions and write SQLite replay-protection state. Keep every live test small and inspect YNAB after each run.

Use this command shape for live single-cycle tests:

```bash
./gradlew runSyncer --args="--config config.yaml --sync-state-db-path $LIVE_STATE_DB --max-cycles 1"
```

Expected common live behavior:

- The process exits after one cycle.
- Qualifying child transactions are posted to child budgets.
- Logs include `Reconciliation decision action=...` before remote work and `Reconciliation outcome action=... outcome=...` after completion.
- SQLite state is written to `$LIVE_STATE_DB`.
- Rerunning the same cycle should skip already-applied idempotency keys instead of duplicating child transactions.

### 2.1 Pre-live checkpoint

Before your first live command:

1. Confirm the latest dry run for the exact same config looks correct.
2. Confirm child budgets do **not** already contain the planned test child transactions.
3. Confirm `$LIVE_STATE_DB` is the state file you intend to keep.
4. Delete `$LIVE_STATE_DB` if it came from an earlier build. Do not expect this build to convert it.
5. Keep the YNAB UI open for the parent and target child budgets.

### 2.2 Live approved parent transaction mirrors once

1. In the parent budget, create a new small approved transaction in one mapped child category.
2. Use memo `SYNC LIVE TEST - child one approved transaction`.
3. Dry run once and verify the planned output.
4. Run one live cycle:

   ```bash
   ./gradlew runSyncer --args="--config config.yaml --sync-state-db-path $LIVE_STATE_DB --max-cycles 1"
   ```

5. Open the child budget in YNAB.

Pass criteria:

- Exactly one new child transaction appears in the expected child budget/account.
- Date, amount, and memo match the source transaction behavior expected from dry run.
- The memo has the configured final-trimmed decoration and the child transaction is cleared.
- Child transaction category is unset/blank.
- Logs include a create decision and `Reconciliation outcome action=create` with the created child transaction ID.
- `$LIVE_STATE_DB` exists.

### 2.3 Live idempotency rerun does not duplicate

Immediately after scenario 2.2:

1. Run the same live command again:

   ```bash
   ./gradlew runSyncer --args="--config config.yaml --sync-state-db-path $LIVE_STATE_DB --max-cycles 1"
   ```

2. Inspect logs and the child budget.

Pass criteria:

- No second child transaction is created for the same parent source event.
- Logs contain no second applied create outcome for the same source, or report an idempotent `already_complete` outcome.
- Existing child transaction remains unchanged.

### 2.4 Live unapproved transaction remains ignored

1. In the parent budget, create a mapped-category transaction with memo `SYNC LIVE TEST - unapproved should not sync`.
2. Leave it unapproved.
3. Run one live cycle.

Pass criteria:

- No child transaction is created.
- There is no applied create outcome for that parent transaction.

Then approve the parent transaction and run one live cycle again.

Pass criteria after approval:

- One child transaction is created after approval.
- A later rerun does not duplicate it.

### 2.5 Live split transaction fan-out

1. In the parent budget, create a new approved split transaction with mapped subtransactions for two child targets.
2. Use memo `SYNC LIVE TEST - split fanout`.
3. Dry run and verify the planned child effects.
4. Run one live cycle.
5. Inspect both child budgets.

Pass criteria:

- Each relevant subtransaction creates one child transaction in the expected child budget/account.
- Amounts match each mapped subtransaction.
- Unmapped split lines do not create child transactions.
- Rerunning the live command does not duplicate either child transaction.

### 2.6 Live money movement into one child category

1. Create a small parent budget money movement affecting one mapped child category.
2. Dry run immediately and verify the planned payee/memo.
3. Run one live cycle.
4. Inspect the target child budget.

Pass criteria:

- One child transaction appears.
- Memo is `From <sourceCategory> to <destinationCategory>`.
- Payee is `From <sourceCategory>` for child inflows or `To <destinationCategory>` for child outflows.
- Child category is unset/blank.
- Rerunning does not duplicate it.

### 2.7 Live money movement between two child categories

1. Create a small parent movement from one mapped child category to another mapped child category.
2. Dry run and confirm two child effects.
3. Run one live cycle.
4. Inspect both child budgets.

Pass criteria:

- Both affected children receive the expected transaction effect.
- The idempotency/replay behavior is independent per child target.
- Rerunning does not duplicate either transaction.

### 2.8 Live multi-child, multi-account mapping matrix

Only run this after the dry-run matrix in scenario 1.9 looks exactly right.

1. Back up `$LIVE_STATE_DB`.
2. Create a fresh, small set of parent test activity using unique `SYNC LIVE TEST - mapping matrix` memo text:
   - one approved top-level transaction for each of four children
   - one approved split transaction with mapped subtransactions across multiple children/accounts
   - one mapped-to-mapped money movement across two children
   - one money movement involving a regex-matched CD/long-term category
   - one unapproved mapped transaction that should not sync
3. Dry run once and verify every planned child budget/account destination.
4. Run one live cycle.
5. Inspect all four child budgets.
6. Run the same live cycle command again to verify idempotency.

Pass criteria:

- All four child budgets receive only their expected child transactions.
- Each child transaction appears in the account named by the selected `accountMappings[*].childAccountName`.
- Multiple parent categories configured under one mapping create child transactions in the same target account.
- Regex-matched parent categories route to the expected regex mapping account.
- Split subtransactions create separate child transactions using subtransaction amounts.
- Money movements create the expected positive/negative child-side effects with the expected memo/payee.
- The unapproved transaction is still absent until it is approved.
- The second live run creates no duplicate child transactions.
- SQLite `child_mirrors` rows show the selected target budget/account and child transaction lineage.

Helpful SQLite audit query after the live matrix:

```bash
sqlite3 "$LIVE_STATE_DB" \
  "select target_budget_id, target_account_id, direction, status, count(*)
   from child_mirrors
   group by target_budget_id, target_account_id, direction, status
   order by target_budget_id, target_account_id, direction, status;"
```

### 2.9 Live child failure isolation

Use this only after the happy-path tests pass.

1. Create test source activity that would affect two child targets.
2. Temporarily break one child token or one child account name in `config.yaml`.
3. Run one live cycle.
4. Restore the valid token/config immediately after the test.

Pass criteria:

- Logs identify the failed child target.
- A healthy child target can still be applied successfully if its own budget/account/token are valid.
- The failed child target should not be recorded as successfully applied.
- After fixing the token/config and rerunning, the failed child's work can still be applied.
- Already-successful child work is not duplicated on the retry.

After restoring config, rerun a dry run before another live run.

---

## Phase 3 — Inspect SQLite state after live tests

The live state DB should contain replay/audit data. You do not normally need to edit it manually.

To verify baseline version 1 and inspect all nine table counts:

```bash
sqlite3 "$LIVE_STATE_DB" \
  "select 'schema_versions', count(*) from schema_versions union all
   select 'sync_runs', count(*) from sync_runs union all
   select 'sync_cursors', count(*) from sync_cursors union all
   select 'source_entities', count(*) from source_entities union all
   select 'ingestion_batches', count(*) from ingestion_batches union all
   select 'source_revisions', count(*) from source_revisions union all
   select 'child_mirrors', count(*) from child_mirrors union all
   select 'sync_operations', count(*) from sync_operations union all
   select 'operation_attempts', count(*) from operation_attempts;"
```

Pass criteria after live scenarios:

- `select version from schema_versions order by version;` returns exactly `1` for the current baseline.
- `sync_runs` has entries for live cycles.
- source entities/revisions, ingestion batches, mirrors, operations, and attempts increase as corresponding work is observed and applied.
- `sync_operations.status` shows `applied`, `pending`, or `retryable_failed`; `operation_attempts.outcome` shows `applied`, `failed`, or `already_complete`.

Useful failure inspection query:

```bash
sqlite3 "$LIVE_STATE_DB" \
  "select o.id, o.operation_type, o.target_budget_id, o.child_transaction_id,
          o.status, a.attempted_at, a.outcome, a.failure_reason,
          a.returned_child_transaction_id
   from sync_operations o
   left join operation_attempts a on a.sync_operation_id = o.id
   order by o.id desc, a.id desc
   limit 20;"
```

Do **not** manually edit this DB to force a test to pass. If you need to reset a test, use new unique parent test transactions/memos instead.

### 3.1 Inspect mapping/account routing

For mapped-account scenarios, inspect current and historical mirror routing directly:

```bash
sqlite3 "$LIVE_STATE_DB" \
  "select m.id, e.entity_type, e.parent_transaction_id, e.parent_subtransaction_id,
          e.money_movement_id, m.target_budget_id, m.target_account_id,
          m.direction, m.child_transaction_id, m.status
   from child_mirrors m
   join source_entities e on e.id = m.source_entity_id
   order by m.id desc
   limit 30;"
```

Pass criteria:

- `target_budget_id` matches the child budget that received the transaction.
- `target_account_id` matches the YNAB account resolved from the configured mapping.
- transaction, subtransaction, or movement identity columns identify the source lineage.
- `child_transaction_id`, `direction`, and `status` distinguish each historical and active mirror.

---

## Phase 4 — Continuous polling rollout

Only do this after the dry-run and single-cycle live scenarios pass.

### 4.1 Run a short controlled continuous session

1. Set `sync.pollingIntervalSeconds` to a conservative value, for example `300`.
2. Start continuous live polling:

   ```bash
   ./gradlew runSyncer --args="--config config.yaml --sync-state-db-path $LIVE_STATE_DB"
   ```

3. Let it run for two or three cycles.
4. Stop it with `Ctrl+C`.

Pass criteria:

- Logs show sleep/wake cycles.
- No duplicate child transactions appear across cycles.
- New qualifying parent activity is mirrored once.
- Unapproved/unmapped parent activity remains ignored.

### 4.2 Decide how you will run it long-term

Before leaving it running unattended, decide:

- where the long-lived `$LIVE_STATE_DB` will live
- where the rolling log file will live
- how you will start/stop the process
- how you will monitor failures
- how often you want polling to happen

Recommended final command shape:

```bash
./gradlew runSyncer --args="--config config.yaml --sync-state-db-path syncstate.db"
```

---

## Final sign-off checklist

Use this as the final go/no-go list before trusting the syncer with normal family use.

### Configuration

- [ ] `config.yaml` contains the real parent budget name.
- [ ] Every child budget name exactly matches YNAB.
- [ ] Every child account name exactly matches YNAB.
- [ ] Every mapped parent category is intentional.
- [ ] No raw YNAB token is stored in YAML.
- [ ] Parent and child token env vars are exported and point to the intended accounts.

### Dry run

- [ ] Single-cycle dry run completes with real tokens.
- [ ] Approved mapped parent transaction is planned correctly.
- [ ] Unapproved mapped parent transaction is ignored.
- [ ] Unmapped approved parent transaction is ignored.
- [ ] Split transaction plans one child transaction per mapped subtransaction.
- [ ] Money movement plans expected payee/memo behavior.
- [ ] Four-child/multi-account dry-run matrix covers literals, regex, shared-account mappings, split transactions, money movements, unapproved work, and unmapped work.
- [ ] Dry-run creates no child transactions.
- [ ] Dry-run does not mark planned work as processed.

### Live single-cycle

- [ ] First live run uses `--max-cycles 1`.
- [ ] Approved mapped parent transaction creates exactly one child transaction.
- [ ] Rerun does not duplicate the child transaction.
- [ ] Split transaction fan-out creates the expected child transactions only.
- [ ] Money movement fan-out creates the expected child transactions only.
- [ ] Four-child/multi-account live matrix creates expected transactions only, in the expected child accounts, and does not duplicate on rerun.
- [ ] SQLite `child_mirrors` rows show the expected target budget/account, direction, child ID, and lineage status.
- [ ] Child-target failure identifies the failed child and can be retried after fixing config/token.
- [ ] SQLite state DB is present and backed up if desired.

### Continuous polling

- [ ] Continuous run was tested for at least two cycles.
- [ ] No duplicates appeared across cycles.
- [ ] Logs are written to the configured rolling log path.
- [ ] You know where `syncstate.db` lives and will not delete it casually.

## Cleanup after testing

After manual testing:

1. In YNAB, delete or mark any `SYNC TEST` / `SYNC LIVE TEST` transactions according to how you want your real budgets to look.
2. Keep `$LIVE_STATE_DB` if any live sync posted transactions that you do not want duplicated later.
3. Remove disposable dry-run DBs if desired:

   ```bash
   rm -f build/tmp/manual-sync-dry-run.db
   ```

4. Keep the log file if you want an audit trail; otherwise rotate or archive it.

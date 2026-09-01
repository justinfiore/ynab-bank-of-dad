# QUICK START — YNAB Bank of Dad

Use these steps to configure `config.yaml` safely and run a dry run before any live posting.

This repo now has **two** safe-first flows:
1. the original allowance/interest workflow
2. the standalone parent/child syncer workflow

For the full field-by-field configuration reference, active allowance modeling, and syncer-specific YAML guidance, see [CONFIGURATION.md](CONFIGURATION.md).

Before enabling live parent/child syncing, read [PARENT_TRANSACTION_RECONCILIATION.md](PARENT_TRANSACTION_RECONCILIATION.md). It explains parent-authoritative and child-owned fields, destructive cases, split transitions, retries and cursors, fresh-state schema versioning, money-movement limitations, and rollback limits.

## 1. Install prerequisites

1. Install Java JDK 25.
2. Set `JAVA_HOME` to that JDK.
3. Decide which workflow you are preparing:
   - allowance CLI: export `YNAB_ACCESS_TOKEN` (single token or comma-separated list)
   - parent/child syncer: export `YNAB_PARENT_TOKEN` plus one child token variable per configured child budget (each may be a CSV of tokens)

Linux/macOS example for the allowance CLI:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export YNAB_ACCESS_TOKEN='your-token-here'
```

Linux/macOS example for the syncer:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export YNAB_PARENT_TOKEN='parent-token-here'
export YNAB_CHILD_ONE_TOKEN='child-one-token-here'
export YNAB_CHILD_TWO_TOKEN='child-two-token-here'
```

## 2. Create your local config

1. Copy the example file:

```bash
cp config.yaml.example config.yaml
```

2. Read [CONFIGURATION.md](CONFIGURATION.md) before editing if you need help with allowance and syncer configuration.
3. Edit `config.yaml` and replace the example values with your real setup.
4. Update at least these allowance fields if you will run the allowance CLI:
   - `budgetName`
   - `allowanceEscrowAccountName`
   - `allowanceCategoryName`
   - your Advanced-account settings and/or non-interest kid settings
   - leave `kidsWithSimpleAccounts: []` unless you are intentionally working on the currently inactive Simple-account generation path
   - any category names, memo text, or rate tables that differ in your budget
5. Update at least these syncer fields if you will run the parent/child syncer:
   - `sync.parentBudget.budgetName`
   - `sync.parentBudget.tokenEnvVarName`
   - each `sync.childBudgets[*].childKey`
   - each `sync.childBudgets[*].budgetName`
   - each `sync.childBudgets[*].tokenEnvVarName`
   - optional `sync.childBudgets[*].memoPrefix` / `memoSuffix` (defaults: `"YBOD: "` / `""`; empty strings are allowed)
   - each `sync.childBudgets[*].accountMappings[*].mappingKey`
   - each `sync.childBudgets[*].accountMappings[*].parentCategoryNames[*].name` (literal by default; add `regex: true` only for regex patterns)
   - each `sync.childBudgets[*].accountMappings[*].childAccountName`
   - `sync.pollingIntervalSeconds`
   - `sync.logging.filePath`
   - `sync.state.sqlitePath`

`config.yaml` is gitignored and is intended for your personal values.

## 3. Run tests

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew testAll
```

## 4. Do a safe allowance dry run

Default config path:

```bash
./gradlew run --args='--dry-run --config config.yaml'
```

Specific date:

```bash
./gradlew run --args='--dry-run --date 2025-08-03 --config config.yaml'
```

## 5. Do a safe parent/child sync dry run

Stop any running syncer. If `syncstate.db` was created by an earlier build, delete it first. This build does not convert old state and rejects nonempty unversioned, newer, noncontiguous, or otherwise unsupported schemas without mutation. Deleting state does not delete child transactions created by an earlier syncer; treat matching creates in the first dry run as potential duplicate financial effects and resolve them before live mode.

Recommended one-cycle dry run:

```bash
./gradlew runSyncer --args='--dry-run --config config.yaml --sync-state-db-path syncstate.db --max-cycles 1'
```

Equivalent helper script on Linux/macOS:

```bash
./run-parent-child-sync.sh config.yaml syncstate.db
```

Equivalent helper script on Windows:

```bat
RunParentChildSync.bat config.yaml syncstate.db
```

What this validates safely:
1. config loading
2. sync logging bootstrap
3. read-only access to a supported versioned SQLite database, or empty state when the path is missing
4. parent/child mapping construction
5. planned child-budget mutations without live posting

New child mirrors are planned as cleared and unapproved. Their final `memoPrefix + source memo + memoSuffix` value is trimmed; after creation, the child memo is preserved during reconciliation. Dry-run performs no child mutation and no SQLite schema, row, operation, revision, mirror, or cursor write.

## 6. Review the dry-run output

Before any live run, verify:

### Allowance CLI
1. the correct budget was selected
2. the correct account and category names were found
3. the generated transactions match your expectations
4. your configured rates and account mappings are correct

### Parent/child syncer
1. the correct parent budget and child budgets are configured
2. each child token env var name points to the intended secret
3. parent category mappings match the child you expect to mirror into
4. planned creates and updates have the expected date, amount, payee, account, cleared, and unapproved behavior
5. planned deletions, split transitions, cross-budget replacements, missing-child recreations, and money-movement changes are expected
6. custom/default memo decoration is trimmed on creation and existing child memos remain child-owned on later updates
7. the SQLite path is where you want long-lived replay-protection, cursor, mirror-lineage, and operation-attempt history to live
8. the rolling log path is where you want continuous sync logs written

## 7. Optional live allowance run

Only after the allowance dry run looks correct:

```bash
./gradlew run --args='--config config.yaml'
```

Or for a specific date:

```bash
./gradlew run --args='--date 2025-08-03 --config config.yaml'
```

## 8. Optional live syncer rollout

Start with a single live cycle after your sync dry run looks correct:

```bash
./gradlew runSyncer --args='--config config.yaml --sync-state-db-path syncstate.db --max-cycles 1'
```

If that looks correct, you can allow continuous polling:

```bash
./gradlew runSyncer --args='--config config.yaml --sync-state-db-path syncstate.db'
```

Live syncer rollout guidance:
- confirm a database from an earlier build was deleted before rollout; it cannot be used by this build
- do not skip the single-cycle dry run
- prefer a single-cycle live verification before running continuously
- do not delete `syncstate.db` casually once live syncing has started, because it contains replay-protection and cursor state
- after a remote update or delete, restoring an old binary or database cannot automatically reconstruct the prior child state; use `sync_operations`, `operation_attempts`, `child_mirrors`, and `source_revisions` for manual recovery
- keep the parent and child tokens separate; do not reuse a single personal token across all budgets unless that is intentionally how your YNAB setup is administered

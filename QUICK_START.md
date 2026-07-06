# QUICK START — YNAB Bank of Dad

Use these steps to configure `config.yaml` safely and run a dry run before any live posting.

This repo now has **two** safe-first flows:
1. the original allowance/interest workflow
2. the standalone parent/child syncer workflow

For the full field-by-field configuration reference, active allowance modeling, and syncer-specific YAML guidance, see [CONFIGURATION.md](CONFIGURATION.md).

## 1. Install prerequisites

1. Install Java JDK 25.
2. Set `JAVA_HOME` to that JDK.
3. Decide which workflow you are preparing:
   - allowance CLI: export `YNAB_ACCESS_TOKEN`
   - parent/child syncer: export `YNAB_PARENT_TOKEN` plus one child token variable per configured child budget

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
3. SQLite path/bootstrap readiness
4. parent/child mapping construction
5. planned child-budget mutations without live posting

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
4. the planned child transactions have the expected date, amount, memo, and payee behavior
5. the SQLite path is where you want long-lived replay-protection state to live
6. the rolling log path is where you want continuous sync logs written

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
- do not skip the single-cycle dry run
- prefer a single-cycle live verification before running continuously
- do not delete `syncstate.db` casually once live syncing has started, because it contains replay-protection and cursor state
- keep the parent and child tokens separate; do not reuse a single personal token across all budgets unless that is intentionally how your YNAB setup is administered

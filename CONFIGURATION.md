# Configuration Reference

This document explains every property in `config.yaml` / `config.yaml.example`, how the tool uses it, and how the currently active allowance models work.

It also documents the standalone **parent/child syncer** configuration that mirrors approved parent-budget activity into child budgets with SQLite-backed replay protection.

If you only want the shortest path to a safe first run, start with [QUICK_START.md](QUICK_START.md). If you want the full runtime overview, examples, and field-by-field reference, use this document together with `config.yaml.example`.

---

## Overview

The application reads its runtime behavior from YAML. The recommended setup is:

- committed template: `config.yaml.example`
- personal local file: `config.yaml` (gitignored)

Create your personal file like this:

```bash
cp config.yaml.example config.yaml
```

Then edit `config.yaml` with the exact names and rules from your own YNAB budget(s).

---

## How the tool uses configuration

This repository now contains two related but distinct runtime flows.

### Allowance CLI flow

At runtime, the allowance tool:

1. reads your YAML config
2. authenticates to YNAB using `YNAB_ACCESS_TOKEN`
3. finds the newest budget matching `budgetName`
4. finds the account named `allowanceEscrowAccountName`
5. reads categories from the target budget
6. generates allowance and interest transactions from your rules
7. posts them in bulk, unless `--dry-run` is enabled

Because of this, **exact names matter** for many fields. If a configured budget/account/category name does not match what exists in YNAB, the run will fail.

### Parent/child syncer flow

At runtime, the syncer:

1. reads the same YAML file but uses the nested `sync:` section
2. authenticates the parent and each child budget using separate environment variables named in config
3. finds the latest parent budget matching `sync.parentBudget.budgetName`
4. polls approved parent-budget transactions and recent parent money movements
5. maps configured parent categories to one or more child targets
6. derives child transaction plans with idempotency keys
7. posts child-budget transactions unless `--dry-run` is enabled
8. writes replay-protection and run-history state into SQLite unless `--dry-run` is enabled

Because of this, exact names also matter for:
- `sync.parentBudget.budgetName`
- each `sync.childBudgets[*].budgetName`
- each `sync.childBudgets[*].accountMappings[*].parentCategoryNames[*].name`
- each `sync.childBudgets[*].accountMappings[*].childAccountName`

---

## Syncer safety model

The parent/child syncer is intended to be rolled out in this order:

1. configure parent and child budget references in YAML
2. export the separate token environment variables named in YAML
3. run `./gradlew testAll`
4. run a **single-cycle dry run** with `--dry-run --max-cycles 1`
5. inspect the planned child mutations and logging/bootstrap behavior
6. optionally run a **single-cycle live** verification with `--max-cycles 1`
7. only then allow continuous polling

The syncer uses SQLite state for:
- `sync_runs` — run lifecycle/audit trail
- `source_events` — parent-event fingerprints
- `sync_mappings` — planned parent→child mapping records
- `applied_transactions` — child-transaction application records
- `sync_cursors` — incremental-read cursors such as transaction server knowledge

If live syncing has already started, do not delete the SQLite file casually; doing so removes replay-protection history and cursors.

---

## Allowance account models

The configuration supports three account-modeling sections. The currently active generated allowance/interest logic is concentrated in the Advanced-account and Non-interest paths; Simple-account fields are still parsed and validated for compatibility, but the current implementation does not generate Simple-account transactions.

### Simple-account compatibility fields
Simple-account fields remain in the config schema for compatibility and for future reactivation of suffix-driven account generation.

Example category pattern:

- `Sam Spend Bank`
- `Sam Save Bank`
- `Sam Give Bank`

The related fields are:

- `kidsWithSimpleAccounts`
- `bankSuffixes`
- `allowanceRates`

Current behavior:
- the config loader validates that `allowanceRates` keys appear in `bankSuffixes`
- the allowance CLI does **not** currently generate Simple-account interest or allowance transactions from `kidsWithSimpleAccounts`
- keep `kidsWithSimpleAccounts: []` unless you are intentionally working on the Simple-account implementation path

### Advanced accounts

Use **Advanced** accounts when a kid’s weekly deposits must be configured by exact category name.

Example advanced categories:

- `Child One Silver Account`
- `Child One Give Bank`
- `Child Four Bronze Account`
- `Child Four Gold CD 2-Month 08/15/25`

For advanced accounts, the tool uses:

- `kidsWithAdvancedAccounts`
- `advancedAllowanceDeposits`
- `accountTypes`
- `interestRatesByAccountTypeAndDate`

Use advanced accounts when:

- category names are not simple kid-name + suffix combinations
- different kids have different account structures
- you need explicit deposit control by exact category
- you use interest-bearing account types such as Bronze, Silver, CDs, or other named savings buckets

### Non-interest kids

A third pattern exists for kids listed in `kidsWithoutInterest`.

These kids receive:

- one combined weekly allowance transaction
- no interest transactions

This is useful when you want a simplified non-interest flow rather than per-account interest-bearing categories.

---

## Configuration layout

The example file is organized into:

1. global allowance budget/account/memo settings
2. simple-account compatibility fields
3. active advanced-account and non-interest allowance configuration
4. parent/child syncer configuration

---

## Property reference

## Global settings

### `budgetName`
The YNAB budget name to target.

Behavior:
- the tool finds all budgets with this exact name
- if more than one matches, it uses the most recently modified one

Example:

```yaml
budgetName: Demo Family Budget
```

Guidance:
- must match the budget name in YNAB exactly
- useful if you keep separate test and production budgets with distinct names

---

### `allowanceEscrowAccountName`
The account that funds generated allowance and interest transactions.

Example:

```yaml
allowanceEscrowAccountName: Allowance Escrow
```

Guidance:
- must match the YNAB account name exactly
- typically represents the parent-controlled funding account for these transfers

---

### `allowanceCategoryName`
The category used for the offsetting transaction that balances the allowance/interest postings.

Example:

```yaml
allowanceCategoryName: Family Allowance
```

Guidance:
- must match a YNAB category exactly
- the tool uses this for the balancing entry rather than for every child deposit

---

### `interestMemo`
Memo text applied to generated interest transactions.

Example:

```yaml
interestMemo: Interest
```

---

### `allowanceMemo`
Memo text applied to generated allowance deposit transactions.

Example:

```yaml
allowanceMemo: Allowance
```

---

### `combinedMemo`
Memo text used on the single offsetting transaction posted to the allowance category.

Example:

```yaml
combinedMemo: Allowance and Interest combined
```

---

### `nonInterestMemoSuffix`
Suffix appended in memos for kids in `kidsWithoutInterest`.

Example:

```yaml
nonInterestMemoSuffix: Piggy Banks
```

If the kid is `Sam`, the resulting memo might look like:

```text
To Sam Piggy Banks
```

---

## Simple-account compatibility fields

### `bankSuffixes`
Ordered list of suffixes retained for compatibility with the Simple-account config schema.

Example:

```yaml
bankSuffixes:
  - " Spend Bank"
  - " Save Bank"
  - " Give Bank"
```

If `kidsWithSimpleAccounts` contains `Sam`, these suffixes describe category names such as:

- `Sam Spend Bank`
- `Sam Save Bank`
- `Sam Give Bank`

Guidance:
- leading spaces are intentional in the example format
- suffixes must align with real YNAB category names
- keep ordering stable if you want predictable transaction ordering

---

### `allowanceRates`
Weekly allowance amounts, in dollars, keyed by simple-account suffix.

Example:

```yaml
allowanceRates:
  " Spend Bank": 1.0
  " Save Bank": 0.5
  " Give Bank": 0.5
```

Guidance:
- keys should match `bankSuffixes`
- values are dollar amounts, not milliunits
- retained for Simple-account compatibility; the current allowance CLI does not generate Simple-account allowance transactions

---

### `kidsWithSimpleAccounts`
Kids retained in the Simple-account compatibility list.

Example:

```yaml
kidsWithSimpleAccounts: []
```

Current guidance:
- keep this empty for normal use unless you are intentionally reactivating or extending Simple-account generation
- active generated allowance/interest transactions currently come from `kidsWithAdvancedAccounts`, `advancedAllowanceDeposits`, and `kidsWithoutInterest`

---

### `kidsWithoutInterest`
Kids who get one combined weekly allowance transaction and no interest calculations.

Example:

```yaml
kidsWithoutInterest:
  - Sam
```

Guidance:
- this is separate from the advanced-account interest-bearing flow
- useful for simplified setups or non-interest-bearing children’s buckets

---

### `giveBankRate`
Dollar amount used when creating the non-interest-bearing combined weekly allowance transaction.

Example:

```yaml
giveBankRate: 0.5
```

Guidance:
- this specifically affects `kidsWithoutInterest`
- keep it aligned with your intended simplified non-interest weekly amount logic

---

## Advanced-account configuration

### `kidsWithAdvancedAccounts`
List of kids whose weekly deposits are driven by explicit category mappings in `advancedAllowanceDeposits`.

Example:

```yaml
kidsWithAdvancedAccounts:
  - Child One
  - Child Two
  - Child Three
  - Child Four
```

Guidance:
- every kid listed here should also have a matching top-level key in `advancedAllowanceDeposits`
- use this section for the currently active per-category allowance and interest-bearing account flow
- do not list a kid here unless you want explicit category-by-category control

---

### `advancedAllowanceDeposits`
Explicit weekly allowance deposits, in dollars, for advanced-account kids.

Example:

```yaml
advancedAllowanceDeposits:
  Child One:
    "Child One Silver Account": 3.0
    "Child One Give Bank": 0.5
  Child Two:
    "Child Two Silver Account": 3.0
    "Child Two Give Bank": 0.5
```

Guidance:
- each top-level key must match a value in `kidsWithAdvancedAccounts`
- each nested key must match a real YNAB category name exactly
- values are weekly dollar deposits
- this is the main allowance configuration for advanced-account kids

---

### `accountTypes`
Ordered list of account-type labels used to detect which interest-rate rule applies to a category name.

Example:

```yaml
accountTypes:
  - Bronze
  - Silver
  - Gold CD 2-Month
  - Gold CD 3-Month
  - Gold CD 6-Month
  - First Car Fund
```

Guidance:
- these labels are matched against category names
- longer/more-specific labels should come before shorter overlapping ones when applicable
- if you introduce a new interest-bearing category type, add it here and also add rates for it in the rate tables

---

### `interestRatesByAccountTypeAndDate`
Interest-rate tables keyed first by effective date, then by account type.

Example:

```yaml
interestRatesByAccountTypeAndDate:
  Current:
    Bronze: 0.1
    Silver: 0.15
    Gold CD 2-Month: 0.25
  "2025-06-01":
    Bronze: 0.1
    Silver: 0.5
    Gold CD 2-Month: 0.75
```

How it works:
- `Current` is the default/current rate table
- dated tables are used for historical/origination-date CD logic
- keys must be either:
  - `Current`
  - a quoted date in `YYYY-MM-DD` format

Guidance:
- each nested key should match one of the `accountTypes`
- values are weekly interest percentages as used by the current business rules
- if a category’s account type is missing from the applicable table, behavior may fail or produce incomplete calculations depending on the path

Historical-table example:

```yaml
interestRatesByAccountTypeAndDate:
  Current:
    Bronze: 0.1
    Silver: 0.15
    Gold CD 2-Month: 0.25
    Gold CD 3-Month: 0.35
    Gold CD 6-Month: 0.65
    First Car Fund: 0.65
  "2025-04-14":
    Bronze: 0.25
    Silver: 0.75
    Gold CD 2-Month: 1.5
    Gold CD 3-Month: 1.75
    Gold CD 6-Month: 2.0
    First Car Fund: 2.0
```

---

## Parent/child syncer configuration

All syncer-specific settings live under the top-level `sync:` key.

### `sync.parentBudget`
Identifies the source parent budget and the environment variable that contains its token.

Example:

```yaml
sync:
  parentBudget:
    budgetName: Demo Parent Budget
    tokenEnvVarName: YNAB_PARENT_TOKEN
```

Guidance:
- `budgetName` must exactly match the parent budget name in YNAB
- `tokenEnvVarName` is the **name** of the env var, not the token value itself
- do not put raw tokens in YAML

### `sync.childBudgets`
List of child-target mappings.

Example:

```yaml
sync:
  childBudgets:
    - childKey: child-one
      budgetName: Demo Child One Budget
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      accountMappings:
        - mappingKey: spend
          parentCategoryNames:
            - name: "Child One Spend Bank"
          childAccountName: Spend Account
        - mappingKey: save
          parentCategoryNames:
            - name: "Child One Save Bank"
            - name: "Child One Gold CD.*"
              regex: true
          childAccountName: Save Account
```

Field guidance:
- `childKey` — stable internal identifier used for grouping and replay protection; must be unique
- `budgetName` — exact YNAB child budget name
- `tokenEnvVarName` — env var name that holds this child budget’s token
- `accountMappings` — non-empty list of parent-category-to-child-account mappings for this child budget
- `accountMappings[*].mappingKey` — stable unique key within the child target, used in logs/state/idempotency
- `accountMappings[*].parentCategoryNames[*].name` — parent-budget category matcher; literal exact match by default
- `accountMappings[*].parentCategoryNames[*].regex` — optional boolean; set `true` only when `name` is a regex pattern
- `accountMappings[*].childAccountName` — exact child-budget account name that receives mirrored transactions for that mapping; must be unique within the child budget (use multiple `parentCategoryNames` on one mapping to route several categories to the same account)

Notes:
- literal matchers are evaluated first and win over overlapping regex mappings, even if the regex appears earlier
- without a literal exact match, the first matching mapping in config order wins
- one mapping can list multiple literal and/or regex parent category matchers for the same child account
- one child budget can define multiple mappings to different child accounts
- money movements can fan out when both the source and destination categories belong to configured mappings

### `sync.pollingIntervalSeconds`
How often the continuous syncer wakes up between cycles.

Example:

```yaml
sync:
  pollingIntervalSeconds: 300
```

Guidance:
- value must be a positive integer
- lower values increase API polling frequency and log volume
- start conservatively unless you need near-real-time mirroring

### `sync.logging`
File-based logging settings for the continuously running syncer.

Example:

```yaml
sync:
  logging:
    filePath: logs/parent-child-sync.log
    level: INFO
    maxHistory: 7
    maxFileSizeMb: 10
```

Field guidance:
- `filePath` — rolling log output path; parent directories are created if needed
- `level` — one of `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`
- `maxHistory` — positive integer number of history files to retain
- `maxFileSizeMb` — positive integer maximum file size in MB before roll

### `sync.state`
SQLite replay-protection and lookback settings.

Example:

```yaml
sync:
  state:
    sqlitePath: syncstate.db
    transactionLookbackDays: 45
    moneyMovementLookbackDays: 45
```

Field guidance:
- `sqlitePath` — default SQLite file location used unless overridden by `--sync-state-db-path`
- `transactionLookbackDays` — how far back to re-read parent transactions safely
- `moneyMovementLookbackDays` — how far back to re-read money movements before YNAB ages them out

Operational guidance:
- keep this file on persistent storage if you want long-lived replay protection
- treat deleting or relocating the file as an operational reset
- dry-run mode does not persist mutable sync state

---

## Example patterns

## Example 1: Minimal compatibility setup with one sync target

```yaml
budgetName: Demo Family Budget
allowanceEscrowAccountName: Allowance Escrow
allowanceCategoryName: Family Allowance
interestMemo: Interest
allowanceMemo: Allowance
combinedMemo: Allowance and Interest combined
nonInterestMemoSuffix: Piggy Banks

bankSuffixes:
  - " Spend Bank"
  - " Save Bank"
  - " Give Bank"

allowanceRates:
  " Spend Bank": 1.0
  " Save Bank": 0.5
  " Give Bank": 0.5

kidsWithSimpleAccounts: []

kidsWithoutInterest: []
giveBankRate: 0.5

kidsWithAdvancedAccounts: []
advancedAllowanceDeposits: {}

accountTypes:
  - Bronze
  - Silver

interestRatesByAccountTypeAndDate:
  Current:
    Bronze: 0.1
    Silver: 0.15

sync:
  parentBudget:
    budgetName: Demo Parent Budget
    tokenEnvVarName: YNAB_PARENT_TOKEN
  childBudgets:
    - childKey: sam
      budgetName: Sam Budget
      tokenEnvVarName: YNAB_CHILD_SAM_TOKEN
      accountMappings:
        - mappingKey: spend-save
          parentCategoryNames:
            - name: "Sam Spend Bank"
            - name: "Sam Save Bank"
          childAccountName: Sam Checking
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

---

## Example 2: Advanced-account-only setup

```yaml
budgetName: Demo Family Budget
allowanceEscrowAccountName: Allowance Escrow
allowanceCategoryName: Family Allowance
interestMemo: Interest
allowanceMemo: Allowance
combinedMemo: Allowance and Interest combined
nonInterestMemoSuffix: Piggy Banks

bankSuffixes:
  - " Spend Bank"
  - " Save Bank"
  - " Give Bank"

allowanceRates: {}
kidsWithSimpleAccounts: []
kidsWithoutInterest: []
giveBankRate: 0.5

kidsWithAdvancedAccounts:
  - Child One
  - Child Two

advancedAllowanceDeposits:
  Child One:
    "Child One Silver Account": 3.0
    "Child One Give Bank": 0.5
  Child Two:
    "Child Two Silver Account": 3.0
    "Child Two Give Bank": 0.5

accountTypes:
  - Bronze
  - Silver
  - Gold CD 2-Month

interestRatesByAccountTypeAndDate:
  Current:
    Bronze: 0.1
    Silver: 0.15
    Gold CD 2-Month: 0.25

sync:
  parentBudget:
    budgetName: Demo Parent Budget
    tokenEnvVarName: YNAB_PARENT_TOKEN
  childBudgets:
    - childKey: child-one
      budgetName: Demo Child One Budget
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      accountMappings:
        - mappingKey: silver-give
          parentCategoryNames:
            - name: "Child One Silver Account"
            - name: "Child One Give Bank"
          childAccountName: Child One Checking
    - childKey: child-two
      budgetName: Demo Child Two Budget
      tokenEnvVarName: YNAB_CHILD_TWO_TOKEN
      accountMappings:
        - mappingKey: silver-give
          parentCategoryNames:
            - name: "Child Two Silver Account"
            - name: "Child Two Give Bank"
          childAccountName: Child Two Checking
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

---

## Safe rollout checklist

Before a live allowance run:
1. validate your YAML edits
2. run `./gradlew testAll`
3. run the allowance CLI with `--dry-run`
4. inspect the selected budget/account/category behavior
5. inspect generated transactions carefully
6. only then run without `--dry-run`

Example:

```bash
./gradlew testAll
./gradlew run --args='--dry-run --config config.yaml'
```

Before a live syncer rollout:
1. validate the `sync:` section and env-var names
2. run `./gradlew testAll`
3. run `./gradlew installDist`
4. run the syncer in a single-cycle dry run
5. inspect the planned child mutations, log path, and SQLite path
6. optionally run a single-cycle live verification
7. only then allow continuous polling

Example:

```bash
export YNAB_PARENT_TOKEN='***'
export YNAB_CHILD_ONE_TOKEN='***'
export YNAB_CHILD_TWO_TOKEN='***'
./gradlew installDist
./gradlew runSyncer --args='--dry-run --config config.yaml --sync-state-db-path syncstate.db --max-cycles 1'
```

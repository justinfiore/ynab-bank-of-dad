# Configuration Reference

This document explains every property in `config.yaml` / `config.yaml.example`, how the tool uses it, and how to decide whether a kid/account setup should be modeled as **Simple** or **Advanced**.

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

Then edit `config.yaml` with the exact names and rules from your own YNAB budget.

---

## How the tool uses configuration

At runtime, the tool:

1. reads your YAML config
2. authenticates to YNAB using `YNAB_ACCESS_TOKEN`
3. finds the newest budget matching `budgetName`
4. finds the account named `allowanceEscrowAccountName`
5. reads categories from the target budget
6. generates allowance and interest transactions from your rules
7. posts them in bulk, unless `--dry-run` is enabled

Because of this, **exact names matter** for many fields. If a configured budget/account/category name does not match what exists in YNAB, the run will fail.

---

## Simple vs. Advanced accounts

The configuration supports two patterns for kid accounts.

### Simple accounts

Use **Simple** accounts when a kid follows a standard naming pattern and receives weekly allowance according to common suffix-based rules.

Example category pattern:

- `Sam Spend Bank`
- `Sam Save Bank`
- `Sam Give Bank`

For simple accounts, the tool combines:

- `kidsWithSimpleAccounts`
- `bankSuffixes`
- `allowanceRates`

That means you do **not** list every category name individually. Instead, the tool derives them by combining each kid name with each suffix.

Use simple accounts when:

- category names are predictable from a kid name plus suffix
- each kid gets the same suffix set
- weekly amounts are standardized by suffix

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

1. global budget/account/memo settings
2. simple-account configuration
3. advanced-account configuration

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
Suffix appended to memos for kids in `kidsWithoutInterest`.

Example:

```yaml
nonInterestMemoSuffix: Piggy Banks
```

If the kid is `Sam`, the resulting memo might look like:

```text
To Sam Piggy Banks
```

---

## Simple-account configuration

### `bankSuffixes`
Ordered list of suffixes used to derive simple-account category names.

Example:

```yaml
bankSuffixes:
  - " Spend Bank"
  - " Save Bank"
  - " Give Bank"
```

If `kidsWithSimpleAccounts` contains `Sam`, the tool will look for categories such as:

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
- used for simple-account allowance generation

---

### `kidsWithSimpleAccounts`
Kids whose simple-account categories are derived from `bankSuffixes` and `allowanceRates`.

Example:

```yaml
kidsWithSimpleAccounts:
  - Sam
  - Taylor
```

This would make the tool look for categories such as:

- `Sam Spend Bank`
- `Sam Save Bank`
- `Sam Give Bank`
- `Taylor Spend Bank`
- `Taylor Save Bank`
- `Taylor Give Bank`

Guidance:
- use plain kid display names here
- each derived category must exist in YNAB
- do not put a kid here if they need explicit per-category advanced deposits instead

---

### `kidsWithoutInterest`
Kids who get one combined weekly allowance transaction and no interest calculations.

Example:

```yaml
kidsWithoutInterest:
  - Sam
```

Guidance:
- this is separate from simple-account interest-bearing flows
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

## Example patterns

## Example 1: Simple-account-only setup

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

kidsWithSimpleAccounts:
  - Sam
  - Taylor

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

allowanceRates:
  " Spend Bank": 1.0
  " Save Bank": 0.5
  " Give Bank": 0.5

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
```

---

## Example 3: Mixed setup

```yaml
kidsWithSimpleAccounts:
  - Sam

kidsWithAdvancedAccounts:
  - Child One

kidsWithoutInterest:
  - Max
```

This means:
- `Sam` uses suffix-derived simple categories
- `Child One` uses explicit advanced deposit mappings
- `Max` gets one combined non-interest transaction

---

## Editing checklist

Before running the tool, verify that you updated:

- `budgetName`
- `allowanceEscrowAccountName`
- `allowanceCategoryName`
- simple-account kid names and suffix/rate mappings, if used
- advanced-account kid names and explicit category mappings, if used
- interest account types and rate tables, if used
- memo fields, if you want different generated transaction text

---

## Safe validation workflow

After editing `config.yaml`:

1. run tests
2. run a dry run
3. inspect the selected budget/account/category behavior
4. inspect generated transactions carefully
5. only then run without `--dry-run`

Example:

```bash
./gradlew testAll
./gradlew run --args='--dry-run --config config.yaml'
```

For the overall first-run procedure, see [QUICK_START.md](QUICK_START.md).

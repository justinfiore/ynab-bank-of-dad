# YNAB Bank of Dad
This project allows you to implement "Bank of Dad" concepts (from the book ["The First National Bank of Dad"](<https://a.co/d/0iDelQff>)) using YNAB as your budgeting software.

## Git Operations
This repo (at least as of now) is a git repo stored on another server. Not Gitlab, Github or anything that supports PRs.

So for features, we will create branches, commit to those branches, and then push the branches to the remote.
When I ask to "merge", this means merging the branch to the master branch and then pushing that to the remote.
When I ask to make changes on the "default" branch or "master" or "main" branch, this means to add commits directly to the `master` branch (after pulling the latest commits from the remote first).

## Project Overview
- This is a small Gradle + Groovy command-line application.
- The entry point is `RecordAllowance` in `src/main/groovy/RecordAllowance.groovy`.
- The Gradle `application` plugin is enabled, and `mainClassName` is `RecordAllowance`.
- The tool talks directly to the YNAB REST API at `https://api.youneedabudget.com` using `http-builder-ng-apache`.
- The main workflow computes weekly allowance and interest transactions, then posts them in bulk to the most recently modified YNAB budget named `Fiores`.

## Current Runtime Behavior
- Requires environment variable `YNAB_ACCESS_TOKEN`.
- Supports CLI flags:
  - `--date YYYY-MM-DD` to run for a specific date.
  - `--dry-run` to calculate/log transactions without posting them.
  - `--help` for usage.
- Looks up the YNAB account named `Allowance Escrow` and the budget category named `Allowance`.
- Loads all categories and maps them by category name.
- Generates several transaction groups:
  - interest for simple accounts
  - new allowance for simple accounts
  - interest for advanced accounts
  - new allowance for advanced accounts
  - a single offsetting transaction against the `Allowance` category
  - non-interest-bearing kid transactions (currently based on `kidsWithoutInterest`)

## Domain Model Encoded in the Script
### Kids and account styles
- Simple accounts are supported, but the current live configuration uses:
  - `kidsWithSimpleAccounts = []`
  - `kidsWithAdvancedAccounts = [Jack, Evan, Emily, Colin]`
- Non-interest accounts are also supported via `kidsWithoutInterest`, which is currently empty.

### Bank/category naming conventions
The script depends heavily on YNAB category names matching exact strings.

Common suffixes:
- ` Spend Bank`
- ` Save Bank`
- ` Give Bank`

Advanced-account examples currently hard-coded in `advancedAllowanceDeposits` include:
- `Jack Silver Account`
- `Jack Give Bank`
- `Evan Silver Account`
- `Emily Silver Account`
- `Colin Silver Account`
- `Colin Bronze Account`

Supported advanced account types are detected by substring matching:
- `Bronze`
- `Silver`
- `Gold CD 2-Month`
- `Gold CD 3-Month`
- `Gold CD 6-Month`
- `First Car Fund`

For CDs, category names are expected to end with a maturity date formatted as `MM/dd/yy` so the script can derive origination dates and determine the correct historical rate table.

## Interest/allowance rules currently hard-coded
- Base simple-account allowance rates:
  - Spend Bank: `$1.00`
  - Save Bank: `$0.50`
  - Give Bank: `$0.50`
- Advanced-account weekly deposit amounts are hard-coded per child in `advancedAllowanceDeposits`.
- Interest rates are versioned by date in `interestRatesByAccountTypeAndDate`.
- `Current` rates are used for non-CD advanced accounts.
- CD rates are selected based on derived origination date against the dated rate table.

## Important implementation details
- Money is converted between YNAB milliunits and dollars using helper methods:
  - `toDollars(milliunits)`
  - `toMilliUnits(dollars)`
- Transactions are posted with `/v1/budgets/$budgetId/transactions/bulk`.
- Budget selection is based on the newest `last_modified_on` timestamp among budgets named `Fiores`.
- The script logs the access token at startup (`log.info("Using accessToken: ...")`), which is convenient for debugging but unsafe for production/shared logs.
- The Windows helper scripts currently contain a literal `YNAB_ACCESS_TOKEN` value and machine-specific `JAVA_HOME` paths; treat them as sensitive/local convenience scripts, not portable documentation.

## Logging and debugging
- Logging is configured in `src/main/resources/logback.groovy`.
- Default log level is `INFO` with console output.
- There are several extra debug-style `log.info(...)` lines in the simple-account interest path that may be noisy but are useful when tracing rate lookup behavior.
- Commented logger entries in `logback.groovy` can be enabled for deeper HTTP client tracing.

## Build and run notes
- Wrapper scripts are present: `./gradlew` and `gradlew.bat`.
- Typical safe first run pattern on Linux/macOS:
  1. export `YNAB_ACCESS_TOKEN`
  2. run `./gradlew installDist`
  3. run the app with `--dry-run`
- Example:
  - `YNAB_ACCESS_TOKEN=... ./gradlew run --args='--dry-run --date 2025-08-03'`
- On this Hermes host, Java is not currently installed/configured, so Gradle verification cannot run here until a JDK is available.

## Safe change guidance for future work
- Preserve exact YNAB category/account names unless you are intentionally updating the corresponding lookup logic.
- Be careful when editing interest rate tables: CD logic depends on date ordering and account-type key names matching exactly.
- If adding a new child or account type, update all related structures together:
  - kid lists
  - allowance deposit maps
  - account type detection
  - interest rate tables
  - any README examples
- Prefer `--dry-run` first for any behavior change that could create YNAB transactions.
- Before modifying the default branch (`master`), pull latest changes from remote first.

## Known issues / tech debt observed during inspection
- No README existed prior to this documentation pass.
- No automated tests are present in the repo today.
- No Java toolchain is installed on this Hermes machine, so `./gradlew tasks --all` currently fails until Java is configured.
- Sensitive token material appears in the checked-in Windows batch helper scripts and should be rotated/removed if those values are real.
- The script currently logs the access token, which should likely be removed or masked.
- Most business rules are hard-coded inside one large script; future refactors may benefit from extracting configuration and calculation logic.

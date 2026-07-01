# YNAB Bank of Dad
This project allows you to implement "Bank of Dad" concepts (from the book ["The First National Bank of Dad"](<https://a.co/d/0iDelQff>)) using YNAB as your budgeting software.

## Git Operations
This repo is hosted on GitHub and uses a branch + PR workflow.

- Any commits that are made on non-default branches must be pushed to the remote.
- When work on a branch is complete, create a GitHub pull request using the `gh` CLI.
- All pull requests must request `justinfiore` as a reviewer.
- When I ask to make changes on the "default" branch or "master" or "main" branch, treat that as working directly on the `master` branch after pulling the latest commits from the remote first.
- When I ask to update documentation or repo workflow guidance like this file, commit that change and push it to `master` unless I explicitly ask for a different branch workflow.

## Project Overview
- This is a small Gradle + Groovy command-line application.
- The entry point is `RecordAllowance` in `src/main/groovy/RecordAllowance.groovy`.
- The Gradle `application` plugin is enabled, and `mainClassName` is `RecordAllowance`.
- The tool talks directly to the YNAB REST API at `https://api.youneedabudget.com` using the repo-local `YnabHttpClient` wrapper over JDK `java.net.http.HttpClient`.
- The main workflow computes weekly allowance and interest transactions, then posts them in bulk to the most recently modified YNAB budget whose name is configured in `config.yaml`.

## Current Runtime Behavior
- Requires environment variable `YNAB_ACCESS_TOKEN`.
- Supports CLI flags:
  - `--date YYYY-MM-DD` to run for a specific date.
  - `-c`, `--config PATH` to load an explicit YAML config file.
  - `--dry-run` to calculate/log transactions without posting them.
  - `--help` for usage.
- Looks up the YNAB account and budget category names configured in `config.yaml`.
- Loads all categories and maps them by category name.
- Generates several transaction groups:
  - interest for simple accounts
  - new allowance for simple accounts
  - interest for advanced accounts
  - new allowance for advanced accounts
  - a single offsetting transaction against the `Allowance` category
  - non-interest-bearing kid transactions (currently based on `kidsWithoutInterest`)

## Domain Model Configured in YAML
### Kids and account styles
- Simple accounts are supported via `kidsWithSimpleAccounts` in `config.yaml`.
- Advanced-account kids and deposit mappings are configured via `kidsWithAdvancedAccounts` and `advancedAllowanceDeposits` in `config.yaml`.
- Non-interest accounts are supported via `kidsWithoutInterest` in `config.yaml`.

### Bank/category naming conventions
The script depends heavily on YNAB category names matching exact strings from `config.yaml`.

Common suffixes are configured in `bankSuffixes`, for example:
- ` Spend Bank`
- ` Save Bank`
- ` Give Bank`

Supported advanced account types are detected by substring matching against the configured `accountTypes` list:
- `Bronze`
- `Silver`
- `Gold CD 2-Month`
- `Gold CD 3-Month`
- `Gold CD 6-Month`
- `First Car Fund`

For CDs, category names are expected to end with a maturity date formatted as `MM/dd/yy` so the script can derive origination dates and determine the correct historical rate table.

## Interest/allowance rules configured in YAML
- Base simple-account allowance rates come from `allowanceRates`.
- Advanced-account weekly deposit amounts come from `advancedAllowanceDeposits`.
- Interest rates are versioned by date in `interestRatesByAccountTypeAndDate`.
- `Current` rates are used for non-CD advanced accounts.
- CD rates are selected based on derived origination date against the dated rate table.

## Important implementation details
- The project's primary external dependency is the YNAB REST API.
- YNAB API docs: `https://api.ynab.com/`
- Always consult the YNAB API docs when making or planning changes to API calls.
- Do not guess at YNAB API contracts, field names, request bodies, or response structures.
- Money is converted between YNAB milliunits and dollars using helper methods:
  - `toDollars(milliunits)`
  - `toMilliUnits(dollars)`
- Transactions are posted with `/v1/budgets/$budgetId/transactions/bulk`.
- Budget selection is based on the newest `last_modified_on` timestamp among budgets whose name matches `budgetName` in `config.yaml`.
- The script should not log the raw access token.
- Helper scripts should remain repo-relative and rely on the caller's environment rather than checked-in secrets or machine-specific Java paths.

## Logging and debugging
- Logging is configured in `src/main/resources/logback.groovy`.
- Default log level is `INFO` with console output.
- There are several extra debug-style `log.info(...)` lines in the simple-account interest path that may be noisy but are useful when tracing rate lookup behavior.
- Commented logger entries in `logback.groovy` can be enabled for deeper HTTP client tracing.

## Build and run notes
- Wrapper scripts are present: `./gradlew` and `gradlew.bat`.
- Java JDK 25 is now installed and verified on this Hermes host at `/usr/lib/jvm/java-25-openjdk-amd64`.
- Verified build commands on this host:
  - `./gradlew tasks --all`
  - `./gradlew installDist`
  - `./gradlew test`
- Tests are part of apply completion for code changes: feature, bug-fix, and other non-doc-only changes should add/update automated tests and must pass `./gradlew test` before considering `openspec-apply` complete.
- Doc-only changes do not require running the test suite.
- Test reports are written in both JUnit XML and HTML formats under `build/test-results/` and `build/reports/tests/`.
- Typical safe first run pattern on Linux/macOS:
  1. export `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`
  2. export `YNAB_ACCESS_TOKEN`
  3. run `./gradlew installDist`
  4. run the app with `--dry-run`
- Example:
  - `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 YNAB_ACCESS_TOKEN=... ./gradlew run --args='--dry-run --date 2025-08-03 --config config.yaml'`
- Run tests with:
  - `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test`

## HTTP client migration note
- `http-builder-ng-apache` was removed during the Java 25 / Gradle 9 / Groovy 5 modernization because it is archived and failed under the upgraded Groovy runtime.
- The replacement is intentionally small and local: `src/main/groovy/YnabHttpClient.groovy` wraps JDK `java.net.http.HttpClient` for the limited JSON GET/POST behavior this repo needs.
- Keep that wrapper easy to understand; do not grow it into a clever internal DSL unless the repo's needs genuinely expand.
- WireMock-backed integration tests remain the preferred way to verify end-to-end YNAB request/response behavior.

## OpenSpec status
- OpenSpec is initialized in this repo.
- Project configuration lives in `openspec/config.yaml` and has been customized for this codebase.
- Repo-local OpenSpec command/skill guidance exists under `.claude/commands/opsx/` and `.claude/skills/`.
- For brownfield OpenSpec work in this repo, prefer delta specs for the slice being changed instead of trying to spec the whole system up front.

## Safe change guidance for future work
- Preserve the configured YNAB category/account names unless you are intentionally updating `config.yaml` and the corresponding lookup logic.
- Be careful when editing interest rate tables: CD logic depends on date ordering and account-type key names matching exactly.
- If adding a new child or account type, update all related config structures together:
  - kid lists
  - allowance deposit maps
  - account type list
  - interest rate tables
  - any README / QUICK_START / example config entries
- Prefer `--dry-run` first for any behavior change that could create YNAB transactions.
- Before modifying the default branch (`master`), pull latest changes from remote first.

## Known issues / tech debt observed during inspection
- No README existed prior to this documentation pass.
- Automated tests are present and should remain authoritative on the upgraded toolchain.
- Sensitive token material appears in the checked-in Windows batch helper scripts and should be rotated/removed if those values are real.
- The script currently logs the access token, which should likely be removed or masked.
- Most business rules are hard-coded inside one large script; future refactors may benefit from extracting configuration and calculation logic.

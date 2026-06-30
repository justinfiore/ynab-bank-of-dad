# YNABBankOfDad

[![CI](https://github.com/justinfiore/ynab-bank-of-dad/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/justinfiore/ynab-bank-of-dad/actions/workflows/ci.yml)

YNABBankOfDad is a Groovy/Gradle command-line tool for running a family "Bank of Dad" workflow inside [YNAB](https://www.ynab.com/). It calculates weekly allowance and interest transactions based on the repository's current rules, then posts them to YNAB in a single bulk API request.

This project is inspired by the book [*The First National Bank of Dad*](https://a.co/d/0iDelQff).

## Who this is for

This repository is best suited to people who:
- already use YNAB
- are comfortable running a command-line tool
- want to adapt a "Bank of Dad"-style family budgeting workflow
- understand that the current implementation is still opinionated and not fully generalized

This is **not** currently a plug-and-play budgeting app. The tool still depends on exact YNAB naming conventions and some hard-coded family/account rules described below.

## What the tool does

The current CLI:
- connects to the YNAB API using `YNAB_ACCESS_TOKEN`
- finds the most recently modified budget named `Fiores`
- looks up the account named `Allowance Escrow`
- reads all budget categories
- calculates weekly allowance and interest transactions using the repo's current rules
- posts those transactions in one bulk request
- supports `--dry-run` so you can inspect proposed transactions before posting anything live

## Safety first

This tool can create **real YNAB transactions** when you do not use `--dry-run`.

Before any live run:
- verify your YNAB category and account names match this repo's assumptions
- review the hard-coded kid/account/rate rules in the source
- run a dry run first
- inspect the generated transactions carefully

If you only want the shortest path to a first safe run, start with [QUICK_START.md](QUICK_START.md).

## Current constraints and assumptions

The current implementation is intentionally documented honestly so new users know what must be adapted before reuse.

### Required runtime inputs
- Java JDK 25
- `JAVA_HOME` pointing at your JDK 25 installation
- `YNAB_ACCESS_TOKEN`
- network access to `https://api.youneedabudget.com`

### YNAB names that must currently match exactly
- budget name: `Fiores`
- account name: `Allowance Escrow`
- category name: `Allowance`

### Simple-account suffixes currently expected
- ` Spend Bank`
- ` Save Bank`
- ` Give Bank`

### Advanced account types currently recognized
- `Bronze`
- `Silver`
- `Gold CD 2-Month`
- `Gold CD 3-Month`
- `Gold CD 6-Month`
- `First Car Fund`

### CD naming rule
CD-style category names are expected to end with a maturity date formatted as `MM/dd/yy`.

### Current kid/account model baked into the repo
Advanced-account kids currently configured in code:
- Jack
- Evan
- Emily
- Colin

The legacy simple-account path still exists but is currently disabled:
- `kidsWithSimpleAccounts = []`

The non-interest-bearing path also exists but is currently empty:
- `kidsWithoutInterest = []`

## Supported toolchain

This repository currently targets:
- Java 25
- Gradle 9.6.1 wrapper
- Groovy 5.0.x
- Spock 2.4 (`groovy-5.0`)

Verified commands on the Hermes Linux host used during recent maintenance:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew tasks --all
./gradlew test
./gradlew installDist
```

Typical Linux setup for this environment:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

To verify the JDK:

```bash
java -version
javac -version
echo "$JAVA_HOME"
```

## Running the tool

### Dry run
A safe first run is:

```bash
./gradlew run --args='--dry-run'
```

### Dry run for a specific date

```bash
./gradlew run --args='--dry-run --date 2025-08-03'
```

### Build a distributable install

```bash
./gradlew installDist
```

### CLI options
- `--date YYYY-MM-DD` — run calculations for a specific date
- `--dry-run` — print what would be posted without creating YNAB transactions
- `--help` — show usage information

## Testing

Run tests with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew test
```

The test suite currently uses:
- Spock for unit/spec-style testing
- WireMock for simulated YNAB HTTP integration testing
- focused tests around the repo-local JDK `HttpClient` wrapper

Test outputs are written to:
- `build/test-results/`
- `build/reports/tests/`

## Continuous integration

GitHub Actions runs CI on pushes and pull requests targeting `master`.

The workflow currently:
- runs on GitHub-hosted Ubuntu runners
- provisions Temurin JDK 25
- runs `./gradlew testAll`
- runs `./gradlew installDist`
- uploads `build/test-results/`, `build/reports/tests/`, and `build/install/`
- publishes an inline GitHub job summary with suite counts and failing test names when applicable

Workflow file:
- `.github/workflows/ci.yml`

GitHub Actions runs and artifacts:
- https://github.com/justinfiore/ynab-bank-of-dad/actions

## Project structure

```text
.
├── build.gradle
├── gradlew
├── gradlew.bat
├── RunSpecificAllowance.bat
├── RunWeeklyAllowance.bat
├── QUICK_START.md
├── src/
│   └── main/
│       ├── groovy/
│       │   ├── AllowanceCalculationService.groovy
│       │   ├── RecordAllowance.groovy
│       │   ├── TransactionAssemblyService.groovy
│       │   ├── TransactionModels.groovy
│       │   └── YnabBudgetRepository.groovy
│       └── resources/
│           └── logback.groovy
└── gradle/
    └── wrapper/
```

## Implementation notes for adopters

A few current design choices matter if you plan to adapt the tool:
- business rules are still largely hard-coded in the repo
- exact YNAB naming matters for correct lookups
- the code talks directly to the YNAB REST API
- transaction posting uses `/v1/budgets/$budgetId/transactions/bulk`
- the project uses a small in-repo `YnabHttpClient` wrapper over JDK `java.net.http.HttpClient`

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## Current limitations

Known limitations of the current repo shape:
- configuration is not yet externalized into a reusable config file
- some helper scripts remain environment-specific
- the repo is documented for outside readers, but the runtime model is still opinionated toward the current family workflow
- parent/child budget syncing is not implemented

## GitHub workflow status

This repository is already hosted on GitHub. Remaining open-source/public-hosting readiness work is about documentation clarity, license clarity, and future generalization work — not moving the repository to a different host.

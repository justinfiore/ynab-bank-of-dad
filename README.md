# YNABBankOfDad

[![CI](https://github.com/justinfiore/ynab-bank-of-dad/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/justinfiore/ynab-bank-of-dad/actions/workflows/ci.yml)

YNABBankOfDad is a Groovy/Gradle command-line tool for running a family "Bank of Dad" workflow inside [YNAB](https://www.ynab.com/). It calculates weekly allowance and interest transactions from a YAML configuration file, then posts them to YNAB in a single bulk API request.

This project is inspired by the book [*The First National Bank of Dad*](https://a.co/d/0iDelQff).

## Who this is for

This repository is best suited to people who:
- already use YNAB
- are comfortable running a command-line tool
- want to adapt a "Bank of Dad"-style family budgeting workflow
- want a configurable starting point rather than a plug-and-play budgeting app

## What the tool does

The current CLI:
- connects to the YNAB API using `YNAB_ACCESS_TOKEN`
- loads budget/account/category/rate rules from a YAML config file
- finds the most recently modified budget matching the configured `budgetName`
- looks up the configured allowance escrow account and allowance category names
- reads all budget categories
- calculates weekly allowance and interest transactions from the configured rules
- posts those transactions in one bulk request
- supports `--dry-run` so you can inspect proposed transactions before posting anything live
- supports `-c` / `--config` so you can choose a config file path explicitly

## Safety first

This tool can create **real YNAB transactions** when you do not use `--dry-run`.

Before any live run:
- create your own local `config.yaml` from `config.yaml.example`
- verify your configured budget/account/category names match your actual YNAB setup
- run a dry run first
- inspect the generated transactions carefully

If you only want the shortest safe path to a first run, start with [QUICK_START.md](QUICK_START.md).
For the full configuration guide, see [CONFIGURATION.md](CONFIGURATION.md).

## Required runtime inputs

- Java JDK 25
- `JAVA_HOME` pointing at your JDK 25 installation
- `YNAB_ACCESS_TOKEN`
- a config file in the repository format (`config.yaml.example` is the starting template)
- network access to `https://api.youneedabudget.com`

## Configuration

Use these files together:
- `config.yaml.example` — commented example template
- `CONFIGURATION.md` — detailed field-by-field documentation, examples, and Simple vs. Advanced account guidance
- `config.yaml` — your personal local file (gitignored)

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
./gradlew testAll
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

### Dry run with the default local config

```bash
./gradlew run --args='--dry-run --config config.yaml'
```

### Dry run for a specific date

```bash
./gradlew run --args='--dry-run --date 2025-08-03 --config config.yaml'
```

### Build a distributable install

```bash
./gradlew installDist
```

### CLI options
- `--date YYYY-MM-DD` — run calculations for a specific date
- `-c`, `--config PATH` — use a specific YAML config file
- `--dry-run` — print what would be posted without creating YNAB transactions
- `--help` — show usage information

## Helper scripts

Windows:
- `RunWeeklyAllowance.bat`
- `RunSpecificAllowance.bat YYYY-MM-DD [config-path]`

Linux/macOS:
- `./run-weekly-allowance.sh`
- `./run-specific-allowance.sh YYYY-MM-DD [config-path]`

These wrappers are repo-relative and expect `YNAB_ACCESS_TOKEN` to already be set in your environment.

## Testing

Run tests with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew testAll
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
├── CONFIGURATION.md
├── config.yaml.example
├── gradlew
├── gradlew.bat
├── QUICK_START.md
├── RunSpecificAllowance.bat
├── RunWeeklyAllowance.bat
├── run-specific-allowance.sh
├── run-weekly-allowance.sh
├── src/
│   └── main/
│       ├── groovy/
│       │   ├── AllowanceCalculationService.groovy
│       │   ├── RecordAllowance.groovy
│       │   ├── RuntimeConfig.groovy
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
- the code talks directly to the YNAB REST API
- transaction posting uses `/v1/budgets/$budgetId/transactions/bulk`
- the project uses a small in-repo `YnabHttpClient` wrapper over JDK `java.net.http.HttpClient`
- account/category naming still matters, but those names now belong in config rather than source code

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## GitHub workflow status

This repository is already hosted on GitHub. Remaining open-source/public-hosting readiness work is about documentation clarity, license clarity, and future generalization work — not moving the repository to a different host.

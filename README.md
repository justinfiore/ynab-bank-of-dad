# YNAB Bank of Dad

A small Groovy/Gradle command-line utility for managing a family "Bank of Dad" system in [YNAB](https://www.ynab.com/).

The idea is to model kid-specific allowance, saving, giving, and interest-bearing "accounts" as YNAB categories, then use this script to calculate and post the appropriate weekly transactions automatically.

This project is inspired by the book [*The First National Bank of Dad*](https://a.co/d/0iDelQff).

## What this project does

The app:
- connects to the YNAB API using a personal access token
- finds the most recently modified budget named `Fiores`
- looks up the `Allowance Escrow` account
- reads all budget categories
- calculates weekly allowance and interest transactions based on hard-coded rules
- posts those transactions to YNAB in a single bulk request
- supports a `--dry-run` mode so you can inspect the generated transactions before posting anything

## Current family/account model

The current script is set up around a "Bank of Dad" approach with multiple kids and account styles.

## Testing

Automated tests now use:
- Spock for specification-style unit tests
- WireMock for simulated YNAB HTTP integration tests on the upgraded Java 25 / Groovy 5 toolchain
- a focused `YnabHttpClientSpec` to validate the in-repo JDK `HttpClient` wrapper directly

Run the test suite with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew test
```

Test execution writes reports in both formats:
- JUnit XML: `build/test-results/`
- HTML: `build/reports/tests/`

Expectations for future changes:
- code changes should add or update automated tests when behavior changes
- `./gradlew test` must pass before considering an OpenSpec apply flow complete
- doc-only changes do not require running the test suite

The integration tests simulate YNAB endpoints locally and do not require a live `YNAB_ACCESS_TOKEN`.

### Kids currently configured
The code currently lists these advanced-account kids:
- Jack
- Evan
- Emily
- Colin

The legacy simple-account path still exists in the code, but it is currently disabled because:
- `kidsWithSimpleAccounts = []`

There is also support for non-interest-bearing kids, but that list is currently empty:
- `kidsWithoutInterest = []`

## How money is modeled in YNAB

This project assumes specific YNAB category names exist and match exactly.

### Bank suffixes used for simple accounts
- ` Spend Bank`
- ` Save Bank`
- ` Give Bank`

### Advanced account/category types currently recognized
- `Bronze`
- `Silver`
- `Gold CD 2-Month`
- `Gold CD 3-Month`
- `Gold CD 6-Month`
- `First Car Fund`

### Important naming rule for CDs
For CD-style categories, the script expects the category name to end with a maturity date in this format:
- `MM/dd/yy`

That date is used to back-calculate the CD origination date and choose the appropriate historical interest-rate table.

## Current allowance behavior

### Base simple-account weekly allowance rates
- Spend Bank: `$1.00`
- Save Bank: `$0.50`
- Give Bank: `$0.50`

### Advanced-account weekly deposits currently hard-coded
- Jack
  - `Jack Silver Account`: `$3.00`
  - `Jack Give Bank`: `$0.50`
- Evan
  - `Evan Silver Account`: `$3.00`
  - `Evan Give Bank`: `$0.50`
- Emily
  - `Emily Silver Account`: `$1.00`
  - `Emily Give Bank`: `$0.50`
- Colin
  - `Colin Silver Account`: `$1.00`
  - `Colin Bronze Account`: `$0.50`
  - `Colin Give Bank`: `$0.50`

## Interest behavior

Interest rates are hard-coded in `RecordAllowance.groovy`.

The script supports:
- current/default rates for active account types
- historical rate tables keyed by date
- CD-specific rate lookup based on the CD origination date
- skipping matured CDs when the run date is after maturity

## Project structure

```text
.
├── build.gradle
├── gradlew
├── gradlew.bat
├── RunSpecificAllowance.bat
├── RunWeeklyAllowance.bat
├── src/
│   └── main/
│       ├── groovy/
│       │   └── RecordAllowance.groovy
│       └── resources/
│           └── logback.groovy
└── gradle/
    └── wrapper/
```

## Requirements

To run this project you need:
- Java JDK 25 installed
- `JAVA_HOME` set to your JDK 25 installation directory
- Gradle wrapper support (`./gradlew` is included)
- a valid YNAB personal access token in `YNAB_ACCESS_TOKEN`

This project now targets Java 25. On the Linux build host used for verification, the working installation is:

```text
openjdk version "25.0.3" 2026-10-20
OpenJDK Runtime Environment (build 25.0.3+6-Ubuntu-0ubuntu124.04)
OpenJDK 64-Bit Server VM (build 25.0.3+6-Ubuntu-0ubuntu124.04, mixed mode, sharing)
```

### Installing Java JDK 25 on Ubuntu / Linux Mint

```bash
sudo apt-get update
sudo apt-get install -y openjdk-25-jdk
```

### Setting `JAVA_HOME`

Typical Linux value for this environment:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

To verify:

```bash
java -version
javac -version
echo "$JAVA_HOME"
```

If you want this to persist across shells, add the `export` lines to your shell profile such as `~/.bashrc` or `~/.zshrc`.

PowerShell example:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Running the app

### 1. Set your YNAB token
Linux/macOS:

```bash
export YNAB_ACCESS_TOKEN='your-token-here'
```

PowerShell:

```powershell
$env:YNAB_ACCESS_TOKEN = 'your-token-here'
```

### 2. Build or run
A safe first run is a dry run.

```bash
./gradlew run --args='--dry-run'
```

Run for a specific date:

```bash
./gradlew run --args='--dry-run --date 2025-08-03'
```

You can also build a distributable installation:

```bash
./gradlew installDist
```

### Verified build note
The project build environment was verified after Java 25 installation by successfully running:

```bash
./gradlew tasks --all
```

## CLI options

- `--date YYYY-MM-DD` — run calculations using a specific date
- `--dry-run` — show what would be posted without creating YNAB transactions
- `--help` — print usage information

## Important safety notes

- This script can create real YNAB transactions when not in `--dry-run` mode.
- Always use `--dry-run` first after changing rates, kids, category names, or transaction logic.
- The code relies on exact category/account names; mismatches will break lookups or produce incorrect results.
- The current source logs the access token at startup. Be careful with logs until that is removed or masked.
- The checked-in Windows helper `.bat` files contain machine-specific paths and appear to contain a literal token value; treat those as unsafe examples and rotate/remove the token if it is real.

## Development notes

- Language: Groovy
- Build tool: Gradle
- Main entry point: `RecordAllowance`
- HTTP client: in-repo `YnabHttpClient` wrapper over JDK `java.net.http.HttpClient`
- Logging: Logback (`src/main/resources/logback.groovy`)

## Why the repo now uses a small JDK `HttpClient` wrapper

The project previously used `http-builder-ng-apache` because it was lightweight and pleasant to use from Groovy. That library is now archived and proved incompatible with the Groovy 5 migration in this repo.

The current replacement strategy is intentionally conservative:
- use the JDK's built-in `java.net.http.HttpClient`
- wrap it in a tiny repo-local `YnabHttpClient` helper
- keep the wrapper focused only on the YNAB JSON GET/POST behavior this script actually needs

This approach was chosen because it:
- avoids adding another third-party HTTP client dependency
- keeps long-term compatibility anchored to the JDK we already require
- still gives the application simple call sites such as `getJson(...)` and `postJson(...)`
- keeps the HTTP integration logic easy to understand and document for future maintainers

## Git workflow for this repo

This repository is hosted on a private Git server rather than GitHub/GitLab.

Current expected workflow:
- feature work normally happens on branches
- commits are pushed directly to the remote git server
- "merge" means merge the feature branch into `master` and push `master`
- when asked to work directly on `master`/`main`/default branch, pull latest remote changes first, then commit directly to `master`

## Limitations observed during documentation pass

- No automated tests are currently present.
- Most business logic is hard-coded inside a single Groovy file.

## Suggested next improvements

Potential high-value follow-ups:
- remove or mask token logging
- remove hard-coded secrets from helper scripts
- move kid/rate/account configuration into a config file
- add automated tests around interest and allowance calculations
- split API, rate, and transaction-building logic into smaller units

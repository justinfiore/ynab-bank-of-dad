# YNABBankOfDad

[![CI](https://github.com/justinfiore/ynab-bank-of-dad/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/justinfiore/ynab-bank-of-dad/actions/workflows/ci.yml)

**Your kids have a bank. You have YNAB. This is the teller window.**

Allowance day used to mean a spreadsheet, a calculator, and the quiet dread of "wait, whose piggy bank earned interest this week?" YNAB Bank of Dad turns that ritual into a command: it reads your family budget, pays the weekly allowance, compounds the interest, and posts the whole payday in one tidy batch. Then, when a parent budget moves money that a kid should actually see, it mirrors that activity into their own budget — same date, same amount, no copy-paste, no "I already did that one."

Think of it as *The First National Bank of Dad*, except the vault is [YNAB](https://www.ynab.com/) and the bank never closes. A continuous syncer watches the parent budget and keeps each kid's budget in step as money moves, so nobody has to wait for the next family finance meeting to see what happened. Two windows, one bank:

1. **Payday.** An allowance and interest calculator that posts one bulk transaction set into a single budget.
2. **The mirror.** A parent/child syncer that reflects approved parent-budget activity into one or more child budgets, with category mappings and replay protection so it never pays the same dollar twice.

Inspired by the book [*The First National Bank of Dad*](https://a.co/d/0iDelQff). Built for parents who already live in YNAB and would rather teach money than reconcile it by hand.

---

Under the hood, YNABBankOfDad is a Groovy/Gradle command-line tool. The two workflows above are independent:

## Who this is for

This is a configurable starting point, not a plug-and-play budgeting app. It fits if you:
- already use YNAB
- are comfortable running a command-line tool
- want to adapt a "Bank of Dad"-style family budgeting workflow

## What the tool does

### Allowance CLI
The original CLI:
- connects to the YNAB API using `YNAB_ACCESS_TOKEN`
- loads budget/account/category/rate rules from a YAML config file
- finds the most recently modified budget matching the configured `budgetName`
- looks up the configured allowance escrow account and allowance category names
- reads all budget categories
- calculates weekly allowance and interest transactions from the configured rules
- posts those transactions in one bulk request
- supports `--dry-run` so you can inspect proposed transactions before posting anything live
- supports `-c` / `--config` so you can choose a config file path explicitly

### Parent/child syncer
The standalone syncer:
- loads the `sync:` section from the same YAML config file
- authenticates parent and child budgets with separate environment variables
- polls the configured parent budget on an interval
- reads parent transaction deltas, including changed or deleted state, plus complete money-movement snapshots
- maps configured parent categories to child-budget targets
- creates cleared, unapproved child-budget transactions with copied date/amount/payee, no child category, derived payee/memo text for money movements, and per-child memo prefix/suffix decoration (defaults `"YBOD: "` / `""`; final memo is trimmed)
- reconciles later parent edits, deletions, unapproval, mapping and split changes, same-ID money-movement changes, and missing recorded child mirrors
- preserves child memo edits after creation while treating parent financial fields and routing as authoritative
- writes SQLite state for stable source revisions, child-mirror lineage, durable operations and attempts, sync runs, and cursors
- supports `--dry-run`, `--sync-state-db-path`, `--max-cycles`, and explicit `--config` handling

## Safety first

This tool can create **real YNAB transactions** when you do not use `--dry-run`.

Before any live run:
- create your own local `config.yaml` from `config.yaml.example`
- verify your configured budget/account/category names match your actual YNAB setup
- run a dry run first
- inspect the generated transactions carefully
- for the syncer, verify every parent/child token environment variable and category mapping before allowing continuous live polling
- before the syncer's first live reconciliation, delete any state database from an earlier build and run `--dry-run --max-cycles 1` with the exact live config and state path

If you only want the shortest safe path to a first run, start with [QUICK_START.md](QUICK_START.md).
For the full configuration guide, see [CONFIGURATION.md](CONFIGURATION.md).
For the overall system design, code organization, runtime flows, and a detailed reconciliation review guide, see [ARCHITECTURE.md](ARCHITECTURE.md).
For step-by-step real-account validation before continuous parent/child syncing, use [PARENT_CHILD_SYNC_MANUAL_TESTING.md](PARENT_CHILD_SYNC_MANUAL_TESTING.md).

Before enabling live parent/child syncing, read [PARENT_TRANSACTION_RECONCILIATION.md](PARENT_TRANSACTION_RECONCILIATION.md). It documents parent-authoritative and child-owned fields, destructive transaction and split changes, movement limitations, retries, fresh-state schema versioning, dry-run guarantees, and rollback limits.

## Required runtime inputs

### Allowance CLI
- Java JDK 25
- `JAVA_HOME` pointing at your JDK 25 installation
- `YNAB_ACCESS_TOKEN` (single token, or comma-separated personal access tokens for the same YNAB account)
- a config file in the repository format (`config.yaml.example` is the starting template)
- network access to `https://api.ynab.com`

### Parent/child syncer
- Java JDK 25
- `JAVA_HOME` pointing at your JDK 25 installation
- `YNAB_PARENT_TOKEN`
- one token env var per configured child budget (for example `YNAB_CHILD_ONE_TOKEN`, `YNAB_CHILD_TWO_TOKEN`)
- each token env var may be a single token or a comma-separated list; on HTTP 429 the client switches to the next token immediately and sleeps only after every token for that account is rate-limited
- production 429 logs identify the budget by name, identify rate-limited tokens by their 1-based slot (never by secret value), and show the rolling API-call count from the last hour for every configured token; at DEBUG level they also include the complete 429 response headers and body; retry durations are shown as `1H`, `30M`, or `5S` without Java's `PT` prefix
- a config file containing a valid `sync:` section
- network access to `https://api.ynab.com`
- a writable SQLite state path such as `syncstate.db`

## Configuration

Use these files together:
- `config.yaml.example` — commented example template for both allowance and syncer workflows
- `CONFIGURATION.md` — detailed field-by-field documentation, examples, and active allowance/syncer configuration guidance
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
./gradlew test
./gradlew integrationTest
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

## Running the allowance tool

### Dry run with the default local config

```bash
./gradlew run --args='--dry-run --config config.yaml'
```

### Dry run for a specific date

```bash
./gradlew run --args='--dry-run --date 2025-08-03 --config config.yaml'
```

### Allowance CLI options
- `--date YYYY-MM-DD` — run calculations for a specific date
- `-c`, `--config PATH` — use a specific YAML config file
- `--dry-run` — print what would be posted without creating YNAB transactions
- `--help` — show usage information

## Running the parent/child syncer

### Recommended dry-run-first invocation

Stop any running syncer. If the configured SQLite path contains a database from an earlier build, delete it before using this build; old databases are not converted. Deleting state does not delete child transactions created by an earlier syncer, so review the first dry run for potential duplicate financial effects before live mode. Nonempty unversioned, newer, noncontiguous, and otherwise unsupported schemas are rejected without mutation.

```bash
export YNAB_PARENT_TOKEN='parent-token'
export YNAB_CHILD_ONE_TOKEN='child-one-token'
export YNAB_CHILD_TWO_TOKEN='child-two-token'
./gradlew runSyncer --args='--dry-run --config config.yaml --sync-state-db-path syncstate.db --max-cycles 1'
```

### One-cycle live verification after dry run

```bash
./gradlew runSyncer --args='--config config.yaml --sync-state-db-path syncstate.db --max-cycles 1'
```

### Continuous live polling

```bash
./gradlew runSyncer --args='--config config.yaml --sync-state-db-path syncstate.db'
```

### Syncer CLI options
- `-c`, `--config PATH` — use a specific YAML config file
- `--sync-state-db-path PATH` — override the SQLite replay-protection database path
- `--dry-run` — read and plan only; do not post child transactions or persist mutable sync state
- `--max-cycles N` — run N polling cycles, then exit
- `--help` — show usage information

### Syncer state and logging behavior
- the default SQLite state path comes from `sync.state.sqlitePath` and defaults to `syncstate.db` in the example config
- `schema_versions` records the contiguous applied schema versions; baseline version 1 is created transactionally
- `sync_cursors` stores incremental read cursors such as transaction server knowledge
- `sync_runs` records each live run lifecycle
- `source_entities`, `source_revisions`, and `child_mirrors` retain stable source observations and child transaction ID lineage
- `ingestion_batches`, `sync_operations`, and `operation_attempts` retain cursor eligibility, ordered mutation intent, outcomes, and failure reasons for retry and manual recovery
- the baseline contains exactly those nine tables; old databases must be deleted, and unsupported existing schemas are rejected without mutation
- the syncer bootstrap writes to the configured rolling log file path such as `logs/parent-child-sync.log`
- dry-run mode intentionally suppresses live YNAB writes and SQLite mutation while still exercising config loading, planning, and logging/bootstrap behavior
- memo decoration applies when a child mirror is created; later reconciliation preserves its child-owned memo, while automatic updates restore cleared and unapproved state

Restoring an older binary or SQLite backup after a remote update or delete does not reconstruct the prior child transaction. Use the operation, attempt, mirror, and source-revision history described in the [reconciliation guide](PARENT_TRANSACTION_RECONCILIATION.md) to plan manual recovery.

## Helper scripts

### Allowance wrappers
Windows:
- `RunWeeklyAllowance.bat`
- `RunSpecificAllowance.bat YYYY-MM-DD [config-path]`

Linux/macOS:
- `./run-weekly-allowance.sh`
- `./run-specific-allowance.sh YYYY-MM-DD [config-path]`

These wrappers are repo-relative and expect `YNAB_ACCESS_TOKEN` to already be set in your environment.

### Syncer wrappers
Windows:
- `RunParentChildSync.bat [config-path] [state-db-path]`

Linux/macOS:
- `./run-parent-child-sync.sh [config-path] [state-db-path]`

These wrappers intentionally run a **single dry-run cycle** with `--max-cycles 1` so you can validate config, logging bootstrap, and planned child mutations safely before any live continuous run.

## Building a distributable install

```bash
./gradlew installDist
```

Installed launchers are written under `build/install/YNABBankOfDad/`.

## Testing

Run unit tests with the standard Gradle `test` task, or run the full unit + integration suite with `testAll`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew test
./gradlew testAll
```

`./gradlew test` runs the unit/spec suite only. The old custom `unitTest` task has intentionally been removed. `./gradlew integrationTest` runs the WireMock-backed and SQLite integration specs directly when you need that slice.

The test suite currently uses:
- Spock for unit/spec-style testing
- WireMock for simulated YNAB HTTP integration testing
- real throwaway SQLite integration tests for sync-state persistence behavior
- credential-free WireMock coverage for default/custom child memo decoration, cleared payloads, and dry-run no-write behavior
- focused tests around the repo-local JDK `HttpClient` wrapper
- script-contract tests for wrapper behavior

Test outputs are written to:
- `build/test-results/`
- `build/reports/tests/`

## Continuous integration

GitHub Actions runs CI on pushes and pull requests targeting `master`.

The workflow currently:
- runs on GitHub-hosted Ubuntu runners
- provisions Temurin JDK 25
- runs `./gradlew testAll`, which invokes `test` for unit/spec tests and then `integrationTest`
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
├── RunParentChildSync.bat
├── RunSpecificAllowance.bat
├── RunWeeklyAllowance.bat
├── run-parent-child-sync.sh
├── run-specific-allowance.sh
├── run-weekly-allowance.sh
├── src/
│   └── main/
│       ├── groovy/
│       │   └── ynabbankofdad/
│       │       ├── allowance/
│       │       ├── config/
│       │       ├── model/
│       │       ├── sync/
│       │       │   ├── model/
│       │       │   └── state/
│       │       └── ynab/
│       └── resources/
│           └── logback.groovy
└── gradle/
    └── wrapper/
```

## Implementation notes for adopters

A few current design choices matter if you plan to adapt the tool:
- the code talks directly to the YNAB REST API
- transaction posting uses `/v1/plans/$budgetId/transactions/bulk`
- the parent/child syncer uses a separate polling loop and SQLite-backed replay-protection state store
- the project uses a small in-repo `YnabHttpClient` wrapper over JDK `java.net.http.HttpClient`
- account/category naming still matters, but those names now belong in config rather than source code

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## GitHub workflow status

This repository is already hosted on GitHub. Remaining open-source/public-hosting readiness work is about documentation clarity, license clarity, and future generalization work — not moving the repository to a different host.

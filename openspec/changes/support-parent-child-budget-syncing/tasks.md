## 1. Extend runtime configuration for a standalone syncer

- [x] 1.1 Add nested runtime-config models and validation for the syncer in `src/main/groovy/RuntimeConfig.groovy`, including parent budget identity, per-budget token environment variable names, child budget mappings, polling interval, logging settings, and SQLite sync-state defaults/overrides.
- [x] 1.2 Add sync-specific CLI options and defaults for the standalone sync entry point, including `--sync-state-db-path`, `--dry-run`, and any explicit config-path handling needed for parity with the existing repo conventions.
- [x] 1.3 Update `config.yaml.example` and checked-in config documentation to show a safe example of one or more child sync targets, token env var names, polling interval, logging config, and `syncstate.db` usage.
- [x] 1.4 Add or update `src/test/groovy/RuntimeConfigSpec.groovy` coverage for valid sync config loading, missing required mapping fields, missing env-var-name fields, invalid polling/logging settings, and invalid replay-protection settings.

## 2. Add standalone sync entry point, wrappers, and logging

- [x] 2.1 Add a dedicated sync entry point (for example `ParentChildBudgetSyncer`) that is independent from `RecordAllowance` and can run continuously.
- [x] 2.2 Add separate Bash and Windows batch wrapper scripts for the syncer while preserving the existing allowance wrappers and entry point unchanged.
- [x] 2.3 Add sync-specific logging configuration with file appenders, rolling/rotation policy, and configurable log levels suitable for a continuously running process.
- [x] 2.4 Add automated coverage for sync entry-point option parsing and logging/bootstrap behavior where practical.

## 3. Add parent/child sync data-access and SQLite state primitives

- [x] 3.1 Extend `src/main/groovy/YnabBudgetRepository.groovy` and supporting models to read parent-budget transactions, parent `subtransactions`, parent money movements, money movement groups, child-budget accounts/categories as needed, and to post child-budget transactions with explicit budget/token context.
- [x] 3.2 Add sync-focused models/repositories under `src/main/groovy/` for source-event fingerprints, child transaction drafts, and SQLite-backed sync state used for idempotency and lookback replay protection.
- [x] 3.3 Implement SQLite schema bootstrap/migration logic for the proposed state tables (`sync_runs`, `source_events`, `sync_mappings`, `applied_transactions`, `sync_cursors`) or a closely related final schema.
- [x] 3.4 Add or update automated tests such as `src/test/groovy/YnabBudgetRepositorySpec.groovy` and new sync-state specs to cover YNAB response parsing, split/subtransaction handling, and SQLite persistence behavior without live credentials.

## 4. Implement sync planning and continuous orchestration

- [x] 4.1 Add a sync orchestration service that filters approved parent transactions, inspects both top-level categories and `subtransactions[*].category_id`, maps them to configured child targets, and derives idempotent child-budget transaction drafts.
- [x] 4.2 Extend the sync orchestration to process recent parent-budget money movements, including fan-out when one movement affects more than one child target, while tracking replay protection per child target.
- [x] 4.3 Implement child-budget transaction creation rules that copy date/memo/amount where available, leave child category unset, and derive money-movement payee/memo text from source/destination categories as specified in the design.
- [x] 4.4 Implement the continuous polling loop so the syncer performs read/plan/apply-or-log/sleep cycles with child-specific failure reporting and graceful dry-run suppression of all mutable side effects.
- [x] 4.5 Add or update automated tests for approved-transaction mirroring, unapproved filtering, unmapped filtering, split-transaction fan-out, money-movement fan-out, dry-run suppression of YNAB/SQLite writes, and duplicate prevention.

## 5. Verify and document safe rollout

- [ ] 5.1 Update `README.md` and `QUICK_START.md` with syncer setup, separate token guidance, polling/logging behavior, SQLite state behavior, and dry-run-first rollout instructions.
- [x] 5.2 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test` and confirm the sync-related automated coverage passes on the supported toolchain.
- [ ] 5.3 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist` and a documented sync `--dry-run` invocation that exercises config loading, SQLite bootstrap, and planned child-budget mutations without posting real transactions.

## Notes

- `./gradlew testAll` passed on Java 25 after implementation.
- `./gradlew installDist` passed on Java 25 after implementation.
- The syncer dry-run reaches startup/config/log/bootstrap successfully, but end-to-end dry-run verification against the live YNAB API is still blocked without valid parent/child YNAB credentials and accessible demo budgets; a run with placeholder/demo tokens failed at the first real `/v1/budgets` call with HTTP 401.
- Follow-up requested after initial PR: ignore local `logs/` output in `.gitignore` and expand SQLite state-store coverage into full real-SQLite integration tests that exercise every table interaction against a throwaway database.

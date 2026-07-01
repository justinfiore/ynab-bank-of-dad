## 1. Extend runtime configuration for sync targets and replay protection

- [ ] 1.1 Add nested runtime-config models and validation for parent/child sync settings in `src/main/groovy/RuntimeConfig.groovy`, including child budget identity, token source, mapped parent category names, child account mapping, sync-state path, and lookback window fields.
- [ ] 1.2 Update `config.yaml.example` and any checked-in config documentation to show a safe example of one or more child sync targets without real family-specific names or tokens.
- [ ] 1.3 Add or update `src/test/groovy/RuntimeConfigSpec.groovy` coverage for valid sync config loading, missing required mapping fields, and invalid replay-protection settings.

## 2. Add parent/child sync data-access and state primitives

- [ ] 2.1 Extend `src/main/groovy/YnabBudgetRepository.groovy` and supporting models to read parent-budget transactions, parent-budget money movements, child-budget accounts/categories as needed, and to post child-budget transactions with explicit budget/token context.
- [ ] 2.2 Add sync-focused models/repositories under `src/main/groovy/` for source-event fingerprints, child transaction drafts, and persisted sync state used for idempotency and lookback replay protection.
- [ ] 2.3 Add or update automated tests such as `src/test/groovy/YnabBudgetRepositorySpec.groovy` and any new sync-state specs to cover YNAB response parsing and replay-state persistence behavior without live credentials.

## 3. Implement sync planning and orchestration

- [ ] 3.1 Add a sync orchestration service (for example `ParentChildSyncService`) that filters approved categorized parent transactions, maps them to configured child targets, and derives idempotent child-budget transaction drafts.
- [ ] 3.2 Extend the sync orchestration to process recent parent-budget money movements, including fan-out when one movement affects more than one child target, while tracking replay protection per child target.
- [ ] 3.3 Integrate the sync orchestrator into `src/main/groovy/RecordAllowance.groovy` so the existing CLI entry point can run sync work alongside the allowance workflow while preserving `--dry-run` semantics and child-specific error reporting.
- [ ] 3.4 Add or update automated tests in `src/test/groovy/RecordAllowanceSpec.groovy`, `src/test/groovy/TransactionAssemblyServiceSpec.groovy`, or new sync-specific specs for approved-transaction mirroring, unapproved/unmapped filtering, money-movement fan-out, dry-run suppression of side effects, and duplicate prevention.

## 4. Verify and document safe rollout

- [ ] 4.1 Update `README.md` and `QUICK_START.md` with parent/child sync setup, separate token guidance, sync-state behavior, and dry-run-first rollout instructions.
- [ ] 4.2 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test` and confirm the sync-related automated coverage passes on the supported toolchain.
- [ ] 4.3 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist` and a documented `--dry-run` invocation that exercises sync configuration without posting real parent or child transactions.

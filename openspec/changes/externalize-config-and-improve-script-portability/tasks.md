## 1. Externalize runtime configuration

- [x] 1.1 Add a checked-in top-level `config.yaml` seeded with the current kid/account lists, allowance rates, advanced allowance deposits, account types, and dated interest-rate tables.
- [x] 1.2 Implement configuration loading/validation for `RecordAllowance` (and any supporting helper class) so startup reads `config.yaml` while continuing to require `YNAB_ACCESS_TOKEN` from the environment.
- [x] 1.3 Add or update automated tests that prove equivalent configuration preserves current transaction behavior and that invalid/missing config fails explicitly.

## 2. Make helper scripts portable across Windows and Bash

- [x] 2.1 Rewrite `RunWeeklyAllowance.bat` and `RunSpecificAllowance.bat` to use repo-relative paths and caller-provided environment values instead of machine-specific workspace, Java, and token literals.
- [x] 2.2 Add Bash helper scripts with comparable weekly-run and date-specific-run flows for Linux/macOS users.
- [x] 2.3 Update helper-script usage to support a safe first-run pattern that can include `--dry-run`.

## 3. Document and verify the new runtime contract

- [x] 3.1 Update `README.md` (and any quick-start/setup docs if added) to explain `config.yaml`, required environment variables, portable script usage, and safe dry-run startup.
- [x] 3.2 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test` and `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist` after the change.
- [x] 3.3 Run an applicable dry-run-oriented invocation path using the new helper flow, then validate the OpenSpec change with `openspec validate externalize-config-and-improve-script-portability`.

## Notes

- The dry-run invocation reached the live YNAB budget lookup path with `config.yaml.example` and a placeholder `YNAB_ACCESS_TOKEN`, which predictably returned HTTP 401 Unauthorized. This still verified the CLI/config path, argument parsing, and runtime wiring without using real credentials.

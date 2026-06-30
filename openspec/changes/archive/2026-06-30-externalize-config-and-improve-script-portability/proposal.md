## Why

The next TODO item calls out two concrete portability problems that still make this CLI hard to reuse outside Justin’s current machine: most family/account configuration is embedded directly in `RecordAllowance.groovy`, and the checked-in Windows helper scripts still hard-code a local workspace path, JDK path, and a literal `YNAB_ACCESS_TOKEN`. Externalizing the runtime configuration and replacing machine-specific helper scripts with documented cross-platform launchers will make the tool safer to share and easier to run on both Windows and Bash-based environments without changing the underlying YNAB transaction model.

## What Changes

- Add a repo-level `config.yaml` that holds the currently hard-coded allowance/account configuration, including kid lists, category/account naming assumptions, advanced allowance deposits, supported account types, and dated interest-rate tables.
- Update the CLI startup path so `RecordAllowance` loads required runtime configuration from `config.yaml` while continuing to require `YNAB_ACCESS_TOKEN` from the environment and preserving the current `--date`, `--dry-run`, and `--help` flags.
- Add Bash helper scripts comparable to the current Windows helper flows for weekly and date-specific runs.
- Replace machine-specific values in the checked-in Windows helper scripts with portable, repo-relative launch behavior that uses the caller’s environment instead of embedding a workstation path, fixed `JAVA_HOME`, or a literal YNAB token.
- Update README/setup documentation to explain the new configuration file, required environment variables, and the supported Windows/Linux/macOS helper-script usage.

## Capabilities

### New Capabilities
- `portable-runtime-configuration`: Externalized YAML-backed runtime configuration and cross-platform helper scripts for repeatable CLI execution.

### Modified Capabilities
- `modular-allowance-processing`: The CLI startup/orchestration path now loads configuration from `config.yaml` instead of keeping the allowance/domain model embedded directly in `RecordAllowance.groovy`, while preserving the existing transaction semantics.

## Impact

- `src/main/groovy/RecordAllowance.groovy` and any new configuration-loading support classes
- New repo-level `config.yaml` and possibly supporting resources/templates
- Helper scripts such as `RunWeeklyAllowance.bat`, `RunSpecificAllowance.bat`, and new Bash equivalents
- `README.md` and any quick-start/setup guidance describing environment variables and script usage
- OpenSpec change artifacts under `openspec/changes/externalize-config-and-improve-script-portability/`
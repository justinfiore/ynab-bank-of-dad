## Context

The codebase has already been refactored so transaction calculation, transaction assembly, and YNAB repository access are separated into smaller collaborators, but `RecordAllowance` still owns a large embedded domain-configuration block for kids, account types, allowance deposits, suffixes, and dated interest rates. The next TODO item also points out that the checked-in Windows `.bat` helpers are tied to a specific local checkout, a legacy Java 8 path, and a literal `YNAB_ACCESS_TOKEN`, and there are no equivalent Bash launchers for Linux/macOS users.

This change should keep the current Java 25 / Gradle 9 / Groovy 5 baseline and preserve the live YNAB runtime contract: `YNAB_ACCESS_TOKEN` remains an environment variable, `--date`, `--dry-run`, and `--help` continue to work, the tool still targets the configured budget/account/category names supplied via YAML, and the current transaction semantics remain unchanged unless the externalized config intentionally mirrors today’s values.

## Goals / Non-Goals

**Goals:**
- Move the current runtime domain configuration out of hard-coded Groovy literals and into a checked-in `config.yaml` that is readable/editable without changing code.
- Make the CLI load that YAML configuration at startup while preserving the current command-line contract and YNAB behavior.
- Provide portable helper scripts for both Windows and Bash environments that run from the repo root or script location without machine-specific absolute paths.
- Remove checked-in secret-like token values and fixed workstation-specific Java paths from the helper scripts.
- Update README/setup guidance so a new user can understand what must be configured in `config.yaml` versus what must still come from environment variables.

**Non-Goals:**
- Changing the current family/business rules beyond encoding the same values in YAML.
- Replacing `YNAB_ACCESS_TOKEN` with a different secret-management mechanism.
- Introducing a new GUI, installer, or packaged release workflow.
- Changing the YNAB API integration, posting endpoint, or dry-run semantics.

## Decisions

### Decision: Use one checked-in `config.yaml.example` as the shareable source of runtime domain configuration
The repo should add a single top-level `config.yaml.example` that captures a safe example domain model for bank suffixes, simple-account allowance rates, lists of kids by account type, advanced allowance deposit mappings, recognized account types, and dated interest-rate tables. Each user should copy that file to a local `config.yaml`, tailor it to their own budget, and keep that personal file out of version control.

**Why this over keeping defaults in code with partial overrides?**
- The TODO item explicitly asks to factor hard-coded configuration into `config.yaml`.
- A checked-in example plus a gitignored personal file gives other users one obvious place to start without committing private budget details.
- Keeping the initial personal config values identical to today’s local values reduces behavioral risk during the migration.

### Decision: Keep secrets and host-specific runtime values in environment variables, not YAML
`YNAB_ACCESS_TOKEN` should remain environment-provided, and the helper scripts should rely on either `JAVA_HOME`/`PATH` already being configured or accept a caller-provided override rather than storing machine-specific paths in version-controlled files.

**Why this over moving everything into `config.yaml`?**
- The access token is a credential and should not be committed or normalized into a shared config file.
- `JAVA_HOME` and working-directory paths vary by machine and operating system, so freezing them in repo scripts recreates the portability problem this change is meant to solve.
- The current runtime contract already requires `YNAB_ACCESS_TOKEN`, so preserving that interface keeps the change incremental.

### Decision: Add repo-relative Bash and Windows launcher scripts with matching behavior
The implementation should provide Bash scripts comparable to the Windows helper flows and should rewrite the Windows scripts so both platforms invoke the built distribution from repo-relative paths after running `gradlew(.bat) installDist`.

**Why this over documenting only raw `gradlew run` commands?**
- The TODO item specifically asks for Bash scripts comparable to the existing Windows batch scripts.
- Matching helper flows on both platforms reduce onboarding friction and make it easier to verify portability during implementation.
- Repo-relative scripts let the documentation stay simple without preserving unsafe absolute-path examples.

### Decision: Introduce a small configuration-loading seam rather than another broad refactor
`RecordAllowance` should delegate YAML parsing/normalization into a focused helper or configuration object, then pass the resulting values into the existing calculation and transaction-assembly paths.

**Why this over another large architecture pass?**
- The repo already completed a modularity refactor; this change should build on that work rather than reopen it.
- A small configuration-loading seam is enough to satisfy the TODO item while preserving the current transaction behavior.
- Keeping the migration narrow lowers the risk of changing real-money transaction logic accidentally.

### Decision: Document the config/runtime split explicitly in README
The README should explain which values live in `config.yaml`, which values still come from environment variables, how to run the Windows/Bash helpers, and how to do a safe first run with `--dry-run`.

**Why this over code-only changes?**
- Externalizing configuration only helps reuse if new users can discover the contract quickly.
- The existing README already documents current hard-coded assumptions and can be updated to point readers to `config.yaml` instead of stale embedded values.

## Risks / Trade-offs

- **[YAML/config parsing errors break startup]** → Fail fast with explicit validation/errors when required config sections or keys are missing or malformed.
- **[Behavior drifts during hard-coded-to-YAML migration]** → Seed `config.yaml.example` with safe representative values, keep a gitignored `config.yaml` for local real values, and cover the loader/default path with automated tests before changing behavior.
- **[Portable scripts diverge across Windows and Bash]** → Keep both script families minimal, repo-relative, and aligned to the same install/run flow.
- **[Users may expect secrets in config.yaml]** → Document clearly that `YNAB_ACCESS_TOKEN` remains environment-only and must never be committed.
- **[Helper scripts may still assume too much about Java installation]** → Prefer using existing `JAVA_HOME`/`PATH` and emit actionable errors when Java is unavailable rather than hard-coding machine paths.

## Migration Plan

1. Create `config.yaml.example` with safe representative values and a local `config.yaml` with private real values.
2. Add a configuration loader/validator and wire `RecordAllowance` to use it while preserving the existing CLI flags and runtime behavior.
3. Replace the machine-specific Windows scripts with repo-relative wrappers and add equivalent Bash scripts.
4. Update README/setup guidance for config editing, environment variables, helper scripts, and safe dry-run usage.
5. Verify with `./gradlew test`, `./gradlew installDist`, and at least one dry-run-oriented invocation path that does not post real YNAB transactions.

Rollback is straightforward: revert the loader, config file, and portable helper scripts together so the prior embedded configuration path becomes authoritative again.

## Open Questions

- None currently. The proposal assumes `config.yaml.example` captures the shareable structure while each local `config.yaml` mirrors real budget values outside version control.

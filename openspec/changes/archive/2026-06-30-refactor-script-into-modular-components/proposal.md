## Why

`RecordAllowance.groovy` currently concentrates CLI parsing, YNAB API orchestration, account/category lookups, allowance and interest calculations, transaction assembly, and logging decisions inside one large script-oriented entry point. That structure makes the code harder to understand, raises the cost of safe changes, and limits how precisely tests can target business logic without exercising broad side effects.

## What Changes

- Refactor the current script-oriented application into smaller focused components with clear responsibilities for CLI startup, YNAB data access, transaction calculation, and transaction assembly.
- Introduce small explicit value objects for transaction-oriented data where they improve readability over ad hoc Maps while preserving the current external YNAB request contract.
- Preserve the existing runtime contract, including `YNAB_ACCESS_TOKEN`, `--date`, `--dry-run`, YNAB naming assumptions, and current transaction semantics, while reducing hidden coupling and implicit shared state.
- Strengthen ordering and grouping assertions in the existing tests before the structural refactor so the branch has a clearer behavioral safety net.
- Keep the change scoped to internal structure and testability improvements rather than externalizing configuration, renaming the project, or adding parent/child budget syncing.

## Capabilities

### New Capabilities
- `modular-allowance-processing`: Define the required module boundaries and preserved behavior for a refactored allowance-processing flow that splits the current monolithic script into smaller units.

### Modified Capabilities
- `automated-test-coverage`: Expand test expectations so the refactored structure remains covered at component-level seams while preserving existing behavior verification.

## Impact

- Likely code changes in `src/main/groovy/RecordAllowance.groovy` and new supporting Groovy classes under `src/main/groovy/`.
- Likely test changes in `src/test/groovy/RecordAllowanceSpec.groovy`, `src/test/groovy/RecordAllowanceWireMockSpec.groovy`, and possible new focused specs for extracted components.
- Possible documentation updates in `README.md` if developer-facing structure or testing guidance needs clarification.
- New OpenSpec artifacts under `openspec/changes/refactor-script-into-modular-components/` and a delta spec touching `openspec/specs/automated-test-coverage/spec.md` via a change-local spec file.
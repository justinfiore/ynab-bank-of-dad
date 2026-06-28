## Why

The current CLI logs the raw `YNAB_ACCESS_TOKEN` at startup. That exposes a live credential in console output and any captured logs, which is an unnecessary security risk for a tool that can create real YNAB transactions.

This should be fixed now because the unsafe logging behavior is already present in the checked-in code and the change is small, low-risk, and easy to verify without changing the budgeting workflow.

## What Changes

- Remove raw access-token logging from application startup.
- Preserve useful startup logging without printing the credential value.
- Keep existing YNAB API authentication behavior unchanged: the application must still require `YNAB_ACCESS_TOKEN` and send it as the bearer token on API requests.
- Document and verify the change through OpenSpec artifacts for this repo.

## Capabilities

### New Capabilities
- `safe-startup-logging`: Ensure startup logs do not expose the raw `YNAB_ACCESS_TOKEN` while preserving normal CLI startup behavior.

### Modified Capabilities
- None.

## Impact

- Affected code: `src/main/groovy/RecordAllowance.groovy`
- Runtime/environment: `YNAB_ACCESS_TOKEN` remains required, but must no longer be printed to logs.
- External dependency: YNAB REST API authentication continues to use the same bearer-token contract.
- OpenSpec artifacts: `openspec/changes/remove-access-token-logging/proposal.md`, plus follow-on design, specs, and tasks artifacts for this change.

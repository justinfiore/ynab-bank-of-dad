## Context

`RecordAllowance` currently logs the raw `YNAB_ACCESS_TOKEN` in its constructor before initializing the HTTP client. That token is the application's authentication credential for the YNAB REST API, so exposing it in startup logs creates unnecessary credential leakage risk in terminal history, captured logs, and any shared troubleshooting output.

This is a small brownfield change in a single Groovy script. The application must continue to require `YNAB_ACCESS_TOKEN`, keep its Java 8 / Gradle / Groovy runtime unchanged, and preserve all transaction-generation behavior.

## Goals / Non-Goals

**Goals:**
- Stop printing the raw `YNAB_ACCESS_TOKEN` to logs.
- Preserve useful startup behavior and existing YNAB API authentication.
- Keep the change narrowly scoped to logging behavior.
- Make the change easy to verify with the repo's existing Gradle commands.

**Non-Goals:**
- Changing how the token is loaded from the environment.
- Changing YNAB request shapes, headers, endpoints, or budget/category/account lookup behavior.
- Refactoring broader logging strategy across the whole script.
- Rotating or removing any checked-in tokens from helper scripts as part of this change.

## Decisions

### Decision: Remove raw token logging instead of masking most of the token
The safest default is to stop logging the credential value entirely. Even partially masked credentials can still create avoidable security review questions, and the startup log does not need the token value to confirm normal operation.

Alternative considered:
- Mask the token in logs (for example first/last few characters). Rejected because the value is not operationally necessary for this CLI and complete removal is simpler and safer.

### Decision: Preserve startup/authentication flow and only change the log message
The constructor should continue reading `YNAB_ACCESS_TOKEN`, fail if it is missing, and configure the YNAB HTTP client exactly as before. Only the unsafe log statement should change.

Alternative considered:
- Rework startup/authentication handling more broadly. Rejected because it expands scope beyond the targeted onboarding-sized security fix.

### Decision: Verify with build commands rather than live API calls
Because this change does not alter API contracts or business rules, verification should focus on ensuring the code still builds under the known-good Java 8 toolchain.

Alternative considered:
- Exercise a real YNAB dry run. Rejected for the proposal because it would require live credentials and is unnecessary to validate this narrow logging change.

## Risks / Trade-offs

- [Reduced startup visibility] → Mitigation: Keep other normal startup logging in place so operators still have context without seeing the secret.
- [Accidentally changing auth behavior while editing nearby code] → Mitigation: Limit implementation to the logging line and verify the project still builds with existing Gradle commands.
- [Security fix remains incomplete if other token exposures exist elsewhere] → Mitigation: Keep this change explicitly scoped to raw startup logging; track other exposures separately.

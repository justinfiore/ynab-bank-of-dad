# Reconciliation QA evidence: Phase A / Task 1

This phase provides an offline, read-only provisioning inspector. It compares a manually prepared discovery snapshot with the required parent categories and child accounts, then writes `missing-provisioning.json`. It does not call YNAB, does not read or manage access tokens, does not run the syncer, and does not perform live writes.

## Safe setup

1. Copy `qa/config/qa-sync.yaml.example` to `qa/config/qa-sync.yaml`.
2. Replace each `fullId` placeholder with the complete immutable plan UUID for the exact configured display name. Never enter only a known suffix and never construct or guess an ID.
3. Copy `qa/config/discovered-provisioning.json.example` to `qa/discovered-provisioning.json`.
4. Using information gathered manually outside this tool, enter the same exact budget display names and full IDs, the parent category names, and each child's account names. Do not put credentials in either file.

The required parent categories are `QA Jorsten Jr Silver`, `QA Jorsten Jr Bronze`, `QA Borsten Silver`, `QA Borsten Bronze`, `QA Thorsten Silver`, `QA Thorsten Bronze`, `QA Unmapped`, and `QA Transfer Clearing`. Every child plan requires accounts named exactly `Silver` and `Bronze`.

## Exact invocation

Run the inspector with Gradle offline mode so dependency resolution cannot make a network request:

```bash
./gradlew qaInspectProvisioning --offline --args="--config qa/config/qa-sync.yaml --discovery qa/discovered-provisioning.json --artifact qa/artifacts/missing-provisioning.json"
```

The command creates only the caller-selected local artifact. `complete: true` means all required names were present. Otherwise, `missingParentCategories` and each child's `missingAccounts` list are the provisioning gaps. The inspector never creates a category or account.

The code also includes pure safety validation for a possible future live QA runner: confirmation must be exactly `QA_CONFIRM_LIVE_MUTATIONS=YES`, a non-empty expected mutation manifest is mandatory, and every target must match both an allowlisted display name and its config-provided full immutable ID. Phase A / Task 1 does not expose or claim a live mutation command; the manifest example is documentation for that future integration only.

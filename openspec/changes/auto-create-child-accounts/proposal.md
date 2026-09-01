## Why

Parent/child sync currently requires every mapped child account to already exist in the child budget. If `getAccountId` cannot find `childAccountName`, that child's routing fails for the cycle and no mirror is planned. Operators then have to pre-create every child account by hand before a mapped parent category can sync.

YNAB now supports creating accounts through the API (`POST /v1/plans/{plan_id}/accounts`). An opt-in per-child setting can create the missing Savings account from the parent category name so new mapped categories can sync without a manual child-budget setup step.

## What Changes

- Add an opt-in child-budget setting `autoCreateAccounts` (boolean, default `false`). When `false`, missing child accounts keep today's fail-and-skip behavior.
- When `autoCreateAccounts: true`, a parent category that matches that child's `accountMappings` but does not resolve to an existing child account SHALL cause the syncer to create a YNAB Savings account in that child budget, then use the new account for the rest of the cycle.
- Created account names SHALL come from the matching parent category name, not from `childAccountName`.
- Add optional `accountCreationNameStripRegex` on the same child budget. When set, every regex match in the parent category name is replaced with an empty string before the account is created or looked up by derived name.
- Whether the created account is on-budget (`budget`) or off-budget (`tracking`) SHALL be configurable per child budget via `createdAccountOnBudget` (boolean, default `true` when auto-create is enabled).
- Account creation is a live YNAB write. `--dry-run` SHALL report planned account creates and SHALL NOT call the create-account endpoint.
- Unmapped parent categories remain ignored. Auto-create does not invent mappings for categories that match no `accountMappings` matcher on that child.
- No change to parent tokens, child tokens, or environment-variable names. Child budgets still authenticate with `tokenEnvVarName`.

## Capabilities

### New Capabilities

- None. This extends existing parent/child sync configuration and routing.

### Modified Capabilities

- `child-budget-sync-configuration`: per-child `autoCreateAccounts`, `accountCreationNameStripRegex`, and `createdAccountOnBudget` parsing, defaults, and validation.
- `parent-child-budget-syncing`: find-or-create child Savings accounts during routing when auto-create is enabled; dry-run must stay write-free for account creates.
- `automated-test-coverage`: unit and WireMock coverage for config validation, name stripping, create-account HTTP contract, dry-run, and idempotent reuse of an already-created account.

## Impact

Likely touched areas:

- `src/main/groovy/ynabbankofdad/config/RuntimeConfig.groovy` (`ChildBudgetSyncTarget` fields and validation)
- `src/main/groovy/ynabbankofdad/ynab/YnabBudgetRepository.groovy` (create-account wrapper over existing `YnabHttpClient`)
- `src/main/groovy/ynabbankofdad/sync/ParentChildBudgetSyncer.groovy` (`resolveChildRouting`)
- sync context/cache in `src/main/groovy/ynabbankofdad/sync/model/SyncModels.groovy`
- `src/test/groovy/RuntimeConfigSpec.groovy`, `YnabBudgetRepositorySpec.groovy`, `ParentChildBudgetSyncerWireMockSpec.groovy`, and related routing tests
- `config.yaml.example`, `CONFIGURATION.md`, and parent/child sync docs/examples
- Apply must implement the create payload against the live YNAB OpenAPI `SaveAccount` contract (`name`, `type`, `balance` are documented required fields; `type` is `savings`). Do not guess additional request fields.

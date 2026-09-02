## ADDED Requirements

### Requirement: The syncer SHALL create a missing child account when auto-create is enabled
When a parent category matches a child target's `accountMappings` and that mapping's `childAccountName` does not exist in the child budget, and the child target has `autoCreateAccounts: true`, and the run is not `--dry-run`, the syncer SHALL create a YNAB account in that child budget and use the returned account id for planning in the same cycle. The created account type SHALL be the live YNAB `SaveAccountType` string `checking` when `createdAccountOnBudget` is `true`, and `otherAsset` when `createdAccountOnBudget` is `false`. The starting `balance` SHALL be `0` milliunits. The create call SHALL use `POST /v1/plans/{plan_id}/accounts` with a JSON body wrapping `account`. The wrapper SHALL NOT be called when `autoCreateAccounts` is `false`.

#### Scenario: Missing mapped account is created from the parent category name
- **WHEN** parent category `Child One Spend Bank` matches a mapping whose `childAccountName` is `Spend`
- **AND** the child budget has no account named `Spend`
- **AND** `autoCreateAccounts` is `true`
- **AND** the run is live (not `--dry-run`)
- **AND** `accountCreationNameStripRegex` is omitted
- **THEN** the syncer SHALL POST a create-account request for name `Child One Spend Bank`, type `checking`, balance `0`
- **AND** subsequent planning for that category SHALL use the created account id

#### Scenario: Existing mapped account is not created
- **WHEN** the mapping's `childAccountName` already exists in the child budget
- **AND** `autoCreateAccounts` is `true`
- **THEN** the syncer SHALL use that existing account
- **AND** it SHALL NOT POST a create-account request

#### Scenario: Auto-create stays off by default
- **WHEN** `autoCreateAccounts` is omitted or `false`
- **AND** the mapping's `childAccountName` does not exist
- **THEN** the syncer SHALL fail routing for that child with the current missing-account error
- **AND** it SHALL NOT POST a create-account request

#### Scenario: Unmapped categories do not create accounts
- **WHEN** a parent category matches no `accountMappings` matcher on the child
- **AND** `autoCreateAccounts` is `true`
- **THEN** the syncer SHALL ignore that category
- **AND** it SHALL NOT POST a create-account request for it

### Requirement: Auto-created account names SHALL apply accountCreationNameStripRegex
When deriving an auto-created account name, the syncer SHALL start from the matching parent category name. If `accountCreationNameStripRegex` is set, the syncer SHALL replace every regex match with an empty string, then trim whitespace. Lookup of an existing account by the derived name SHALL use exact string equality on that result. If the derived name is empty, the syncer SHALL fail that child's routing and SHALL NOT POST a create-account request.

#### Scenario: Strip regex removes matching suffix
- **WHEN** the parent category name is `Child One Spend Bank`
- **AND** `accountCreationNameStripRegex` is ` Bank$`
- **AND** auto-create will run
- **THEN** the derived account name SHALL be `Child One Spend`

#### Scenario: Existing derived-name account is reused
- **WHEN** `childAccountName` is missing from the child budget
- **AND** an account named `Child One Spend` already exists
- **AND** the derived name is `Child One Spend`
- **THEN** the syncer SHALL use the existing account id
- **AND** it SHALL NOT POST a create-account request

#### Scenario: Empty derived name fails without creating
- **WHEN** `accountCreationNameStripRegex` matches the entire parent category name
- **THEN** routing for that child SHALL fail with an error identifying the child key and original category name
- **AND** the syncer SHALL NOT POST a create-account request

### Requirement: Dry-run SHALL NOT create child accounts
`--dry-run` SHALL remain write-free for account creation. When auto-create would create an account on a live run, dry-run SHALL log the derived account name, the live `SaveAccountType` that would be sent, and the configured `createdAccountOnBudget` value, and SHALL NOT call `POST /v1/plans/{plan_id}/accounts`. Dry-run SHALL NOT plan child financial mutations for categories whose account would only exist after that create.

#### Scenario: Dry-run reports a planned account create
- **WHEN** auto-create would create `Child One Spend` on a live run
- **AND** the operator passes `--dry-run`
- **THEN** the syncer SHALL log that it would create that account with the mapped `SaveAccountType`
- **AND** no create-account POST SHALL be sent

### Requirement: Created-account on-budget flag SHALL select the live SaveAccount type
The create payload SHALL include the documented required fields `name`, `type`, and `balance` (`0`). Live YNAB `SaveAccount` (OpenAPI v1.86.0) does not accept `on_budget`; on-budget vs tracking SHALL be expressed by `type`. `createdAccountOnBudget: true` SHALL send `type: checking`. `createdAccountOnBudget: false` SHALL send `type: otherAsset` (YNAB tracking "Asset (e.g. Investment)"). The request SHALL NOT include `on_budget`.

#### Scenario: On-budget create uses checking type
- **WHEN** `createdAccountOnBudget` is `true` and a live auto-create runs
- **THEN** the request `account.type` SHALL be `checking`
- **AND** `account.balance` SHALL be `0`

#### Scenario: Off-budget create uses otherAsset type
- **WHEN** `createdAccountOnBudget` is `false` and a live auto-create runs
- **THEN** the request `account.type` SHALL be `otherAsset`
- **AND** `account.balance` SHALL be `0`
- **AND** the syncer SHALL NOT fail routing solely because tracking was requested

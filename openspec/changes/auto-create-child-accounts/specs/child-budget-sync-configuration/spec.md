## ADDED Requirements

### Requirement: Child sync targets SHALL support opt-in automatic child account creation
Each `sync.childBudgets[]` entry SHALL accept an optional boolean `autoCreateAccounts`. When the key is omitted, the loader SHALL treat it as `false`. When present, the value SHALL be a boolean. A non-boolean value SHALL fail startup with a validation error identifying the child config path.

#### Scenario: Omitted autoCreateAccounts defaults to false
- **WHEN** a child sync target omits `autoCreateAccounts`
- **THEN** the loaded target SHALL have `autoCreateAccounts` equal to `false`

#### Scenario: Explicit true is preserved
- **WHEN** a child sync target sets `autoCreateAccounts: true`
- **THEN** the loaded target SHALL have `autoCreateAccounts` equal to `true`

#### Scenario: Non-boolean autoCreateAccounts fails fast
- **WHEN** a child sync target sets `autoCreateAccounts` to a non-boolean value such as `"yes"`
- **THEN** startup SHALL fail with a validation error identifying `sync.childBudgets[<index>].autoCreateAccounts`

### Requirement: Child sync targets SHALL support stripping characters from auto-created account names
Each `sync.childBudgets[]` entry SHALL accept an optional `accountCreationNameStripRegex` string. When omitted, no strip pattern SHALL be stored. When present, the value SHALL be a non-empty string that compiles as a Java regular expression. Any match of that regex against a parent category name SHALL be replaced with an empty string when deriving an auto-created account name.

#### Scenario: Valid strip regex is stored
- **WHEN** a child sync target sets `accountCreationNameStripRegex: " Bank$"`
- **THEN** the loaded target SHALL retain that pattern

#### Scenario: Invalid strip regex fails fast
- **WHEN** a child sync target sets `accountCreationNameStripRegex` to a syntactically invalid Java regex
- **THEN** startup SHALL fail with a validation error identifying the child key and the invalid pattern

#### Scenario: Empty strip regex fails fast
- **WHEN** a child sync target sets `accountCreationNameStripRegex` to an empty string
- **THEN** startup SHALL fail with a validation error identifying `sync.childBudgets[<index>].accountCreationNameStripRegex`

### Requirement: Child sync targets SHALL configure whether auto-created accounts are on-budget
Each `sync.childBudgets[]` entry SHALL accept an optional boolean `createdAccountOnBudget`. When `autoCreateAccounts` is `true` and `createdAccountOnBudget` is omitted, the loader SHALL treat it as `true` (on-budget / budget account). When present, the value SHALL be a boolean. `true` means a budget (on-budget) account. `false` means a tracking (off-budget) account.

#### Scenario: Omitted createdAccountOnBudget defaults to on-budget when auto-create is enabled
- **WHEN** a child sync target sets `autoCreateAccounts: true` and omits `createdAccountOnBudget`
- **THEN** the loaded target SHALL have `createdAccountOnBudget` equal to `true`

#### Scenario: Tracking auto-created accounts can be selected
- **WHEN** a child sync target sets `autoCreateAccounts: true` and `createdAccountOnBudget: false`
- **THEN** the loaded target SHALL have `createdAccountOnBudget` equal to `false`

#### Scenario: Non-boolean createdAccountOnBudget fails fast
- **WHEN** a child sync target sets `createdAccountOnBudget` to a non-boolean value
- **THEN** startup SHALL fail with a validation error identifying `sync.childBudgets[<index>].createdAccountOnBudget`

## ADDED Requirements

### Requirement: Child budgets support per-child memo prefix and suffix configuration
Each `sync.childBudgets[]` entry SHALL accept optional `memoPrefix` and `memoSuffix` string fields.
When omitted, `memoPrefix` defaults to `"YBOD: "` and `memoSuffix` defaults to empty string.
The prefix (if present) SHALL be prepended and the suffix (if present) SHALL be appended to the memo value inherited from the parent transaction (or generated for money-movement plans). The complete decorated value SHALL then be trimmed before the child transaction payload is constructed.
Different child budgets MAY have different prefix/suffix values.

#### Scenario: Default prefix applied
- **WHEN** a child budget entry omits both memo fields
- **THEN** every created child transaction memo starts with "YBOD: " followed by the original memo text

#### Scenario: Custom prefix and suffix
- **WHEN** a child budget specifies `memoPrefix: "[Kid] "` and `memoSuffix: " (auto)"`
- **THEN** the resulting memo is `"[Kid] Original memo (auto)"`

#### Scenario: Final decorated memo is trimmed
- **WHEN** the configured decoration produces leading or trailing whitespace around the complete memo
- **THEN** the resulting child payload memo has that leading and trailing whitespace removed

#### Scenario: Empty memo handling
- **WHEN** the source memo is null or empty
- **THEN** the resulting memo is the trimmed result of prefix + empty string + suffix

### Requirement: Child transactions created by sync are marked cleared
All transactions created in child budgets by the parent-child sync SHALL include `cleared: "cleared"` in the YNAB create-transaction request payload.
This applies to allowance/interest transfers, money movements, and any other planned child transactions.

#### Scenario: Cleared flag present on payload
- **WHEN** `ChildTransactionPayloadFactory.buildTransaction` is called for any plan destined for a child budget
- **THEN** the returned map contains the key `cleared` with value `"cleared"`

#### Scenario: YNAB auto-matching enabled
- **WHEN** a synced transaction is created with cleared status in a child budget that also contains manually entered uncleared transactions with matching payee/amount/date
- **THEN** YNAB is expected to offer or perform auto-matching (observable via YNAB UI behavior)

### Requirement: Config validation accepts new memo fields without breaking existing configs
Existing `sync.childBudgets[]` entries that omit `memoPrefix`/`memoSuffix` SHALL continue to validate and use the documented defaults.
New fields SHALL be validated only for type (string) if present; empty strings are allowed and mean "no prefix/suffix".

#### Scenario: Legacy config loads successfully
- **WHEN** a config file with no memo* fields on any childBudget is loaded
- **THEN** validation succeeds and the default "YBOD: " prefix behavior is used

#### Scenario: Empty memo decoration values are accepted
- **WHEN** a child budget explicitly configures an empty string for either memo field
- **THEN** validation succeeds and the empty string is preserved

#### Scenario: Non-string memo decoration values are rejected
- **WHEN** a child budget configures a non-string value for either memo field
- **THEN** validation fails with the full `sync.childBudgets[index].memoPrefix` or `memoSuffix` config path

## Purpose

Report cycle-end child-account money movement and compare each mapped child account to its parent-budget category balances so operators can gain confidence in live sync without treating drift as a failure.

## ADDED Requirements

### Requirement: Cycle stats SHALL include accumulated change by child account
At the end of each sync cycle the syncer SHALL keep logging per-child created, updated, and deleted counts and SHALL also log the net milliunit change of that cycle's planned CREATE, UPDATE, and DELETE intents grouped by child budget (`childKey`) and mapped child account. CREATE SHALL contribute the new amount, UPDATE SHALL contribute new amount minus the prior child amount, and DELETE SHALL contribute the negation of the prior child amount. NO_OP intents SHALL NOT contribute. Amounts in the logs SHALL be formatted with the existing dollar formatter (`YnabLogFormatter.formatAmount`), not raw milliunits.

#### Scenario: Create updates and deletes roll up per account
- **WHEN** a cycle plans a CREATE of `-1200` milliunits, an UPDATE from `-500` to `-800` milliunits, and a DELETE of a `400` milliunit child transaction against the same child account
- **THEN** the cycle summary SHALL log net change `-1900` milliunits for that child budget and account
- **AND** it SHALL still log created, updated, and deleted counts for that child

#### Scenario: Cached accounts with no mutations log zero net change
- **WHEN** transaction propagation resolves a parent category to a child account and that account has no CREATE, UPDATE, or DELETE intents in the cycle
- **THEN** the cycle summary SHALL log net change `0` for that account
- **AND** it SHALL still include that account in the balance comparison

### Requirement: Reverse mapping SHALL invert the cycle's propagation cache
When transaction propagation resolves a parent category to a child account, the syncer SHALL record that pair in a cycle-scoped cache. Cycle-end comparison SHALL invert that cache and SHALL NOT re-match parent categories against `accountMappings`. When multiple cached parent categories resolve to the same child account, the parent side of the comparison SHALL be the sum of those categories' `balance` values from `GET /v1/plans/{plan}/categories`. Parent categories that did not resolve to a child account this cycle SHALL NOT be compared.

#### Scenario: One propagated parent category maps to one child account
- **WHEN** this cycle's transaction propagation resolves parent category `Child One Spend Bank` to child `child-one` account `Child One Checking`
- **THEN** the comparison for `child-one` / `Child One Checking` SHALL use that category's `balance`

#### Scenario: Several propagated parent categories map to one child account
- **WHEN** this cycle's transaction propagation resolves parent categories `Child Two Gold CD 07/31/26` and `Child Two Gold CD 08/31/26` to the same child account
- **THEN** the parent side of that account's comparison SHALL be the sum of both category `balance` values

#### Scenario: Comparison uses the account propagation actually chose
- **WHEN** transaction propagation resolves a parent category to a derived auto-create account name instead of the mapping's `childAccountName`
- **THEN** the reverse mapping SHALL use that derived account
- **AND** it SHALL NOT substitute `childAccountName` from a second matcher pass

#### Scenario: Unpropagated parent categories are not compared
- **WHEN** the parent budget contains a category that this cycle never resolved to a child account
- **THEN** that category SHALL NOT appear in the reverse mapping or balance comparison

### Requirement: Dry-run SHALL log projected child account balances versus parent categories
When the syncer is run with `--dry-run`, the cycle summary SHALL use each cached child account's current `balance` from `GET /v1/plans/{plan}/accounts` plus that account's accumulated cycle change as the projected child balance. It SHALL compare that projected balance to the related parent category balance(s). It SHALL NOT post child transactions, create accounts, or write sync state in order to produce the report. A missing child account SHALL be treated as current balance `0` when projecting, including accounts that dry-run only logged as would-create.

#### Scenario: Dry-run projects a new balance after planned creates
- **WHEN** a dry-run cycle sees child account `Child One Checking` with YNAB `balance` `100000` and plans CREATE intents totaling `-1200` milliunits for that account
- **THEN** it SHALL log a projected child balance of `98800` milliunits for that account
- **AND** it SHALL compare that projected balance to the mapped parent category balance(s)
- **AND** it SHALL NOT POST or PUT child transactions

#### Scenario: Dry-run projects a missing account from zero
- **WHEN** a dry-run cycle would create a mapped child account that does not yet exist
- **THEN** the projected child balance SHALL be `0` plus that account's accumulated cycle change

### Requirement: Live mode SHALL compare actual YNAB account balances to parent categories
When the syncer is not in `--dry-run`, the cycle summary SHALL compare each cached child account's actual `balance` from `GET /v1/plans/{plan}/accounts` after apply against the related parent category balance(s). It SHALL NOT use the dry-run projected formula as the live child side of the comparison.

#### Scenario: Live cycle uses post-apply account totals
- **WHEN** a live cycle applies child mutations and YNAB then reports account `Child One Checking` `balance` `98800`
- **THEN** the cycle summary SHALL use `98800` as the child side of the comparison
- **AND** it SHALL NOT add the cycle's accumulated change on top of that live total

### Requirement: Balance mismatches SHALL be logged and SHALL NOT fail the cycle
The cycle summary SHALL log the child side, parent side, and difference for each compared account. When the child side and parent side are not equal, the syncer SHALL call out the mismatch in the logs. A mismatch SHALL NOT add a failure to the run result, SHALL NOT skip remaining children, and SHALL NOT change planned or applied reconciliation.

#### Scenario: Equal balances are logged as a match
- **WHEN** the child side and parent side are both `50000` milliunits
- **THEN** the cycle summary SHALL log that they match

#### Scenario: Unequal balances are called out without failing
- **WHEN** the child side is `50000` milliunits and the parent side is `48000` milliunits
- **THEN** the cycle summary SHALL log both values and the `2000` milliunit difference
- **AND** a dry-run cycle SHALL still complete without posting
- **AND** a live cycle SHALL still finish with success unless an unrelated reconciliation failure occurred

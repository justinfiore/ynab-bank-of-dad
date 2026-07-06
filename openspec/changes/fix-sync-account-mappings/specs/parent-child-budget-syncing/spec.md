## MODIFIED Requirements

### Requirement: The syncer SHALL derive child-budget transaction plans from approved parent transactions and money movements
The standalone syncer SHALL read approved parent-budget transactions and recent parent-budget money movements, match parent category names against configured child `accountMappings[*].parentCategoryNames` matcher objects, and derive child-budget transaction plans for the resolved mapping. Each derived plan SHALL target the mapped child account name for the selected mapping. Literal matchers SHALL use exact string equality and SHALL be the default when `regex` is omitted or false. Regex matchers SHALL be used only when `regex: true`. Parent transaction categories MAY come from a top-level transaction category or from split subtransaction categories. The syncer SHALL skip unapproved transactions and parent categories that match no configured mapping.

#### Scenario: Top-level parent category maps to the configured child account by literal name
- **WHEN** an approved parent transaction has category `Child One Spend Bank`
- **AND** child `child-one` has an account mapping with `parentCategoryNames: [{ name: "Child One Spend Bank" }]` and child account `Spend Account`
- **THEN** the syncer SHALL plan one child transaction for `child-one` targeting `Spend Account`

#### Scenario: Different parent categories map to different accounts in the same child budget
- **WHEN** approved parent transactions include categories `Child One Spend Bank` and `Child One Give Bank`
- **AND** the same child budget maps those literal categories to `Spend Account` and `Give Account` respectively
- **THEN** the syncer SHALL plan child transactions for the same child budget using the corresponding mapped account for each source category

#### Scenario: Multiple parent category names map to one child account
- **WHEN** an account mapping contains multiple literal and/or regex parent category name matchers that all target `CD Account`
- **THEN** any matching parent category SHALL produce child work targeting `CD Account`

#### Scenario: Regex parent category name maps grouped categories
- **WHEN** a parent category named `Child One Gold CD 2-Month 07/31/26` is evaluated
- **AND** an account mapping contains `parentCategoryNames: [{ name: "Child One Gold CD.*", regex: true }]` targeting `CD Account`
- **THEN** the syncer SHALL plan child work targeting `CD Account`

#### Scenario: Literal parent category name does not require regex escaping
- **WHEN** a parent category name contains regex metacharacters such as `Child One CD (2-Month) [07/31/26]`
- **AND** the matching config entry omits `regex` or sets `regex: false`
- **THEN** the syncer SHALL compare that value literally and SHALL plan matching child work without requiring regex escaping

#### Scenario: Split parent transaction maps each relevant subtransaction to its configured child account
- **WHEN** an approved split parent transaction contains one subtransaction categorized as `Child One Spend Bank` and another categorized as `Child One Give Bank`
- **THEN** the syncer SHALL create separate child transaction plans for each relevant subtransaction
- **AND** each plan SHALL target the child account resolved from that subtransaction's category mapping

#### Scenario: Exact literal match takes precedence over overlapping regex mapping
- **WHEN** a parent category name matches a literal matcher in one mapping and a regex matcher in another mapping for the same child target
- **THEN** the syncer SHALL select the mapping containing the exact literal match
- **AND** this literal precedence SHALL apply even if the regex mapping appears earlier in the config

#### Scenario: First matching regex mapping wins when no literal exact match exists
- **WHEN** a parent category name matches multiple regex matchers across mappings for the same child target
- **AND** it does not exactly match any literal matcher
- **THEN** the syncer SHALL select the first matching mapping in config order

#### Scenario: First matching literal mapping wins when duplicate literal names exist
- **WHEN** a parent category name exactly matches literal matchers in multiple mappings for the same child target
- **THEN** the syncer SHALL select the first matching literal mapping in config order

#### Scenario: Unmapped category is ignored
- **WHEN** a parent transaction or money movement uses a category that matches no configured mapping
- **THEN** the syncer SHALL not create a child transaction plan for that category

### Requirement: The syncer SHALL keep child category unset when creating child transactions
The syncer SHALL create child-budget transactions with the resolved child account and SHALL leave the child-side category unset unless a future explicit requirement changes that behavior.

#### Scenario: Planned child transaction has account but no category
- **WHEN** a parent category matches an account mapping
- **THEN** the planned child transaction SHALL include the mapped child account name or resolved account ID
- **AND** the planned child transaction SHALL omit child category assignment

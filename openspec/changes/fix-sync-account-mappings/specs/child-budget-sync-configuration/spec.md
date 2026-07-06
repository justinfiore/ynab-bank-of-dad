## MODIFIED Requirements

### Requirement: Runtime config SHALL describe standalone sync settings and per-budget credentials explicitly
The runtime YAML SHALL support explicit parent/child sync configuration for the standalone syncer that names the parent budget used as the sync source, one or more child sync targets, and the mapping data needed to translate parent-budget category activity into child-budget transactions. Each budget definition SHALL include the environment-variable name that holds that budget’s YNAB access token rather than embedding the token value in YAML. Each child sync target SHALL include the child budget name and an `accountMappings` array. Each account mapping SHALL include a stable `mappingKey`, one or more `parentCategoryNames` matcher objects, and the child budget account name that receives mirrored transactions for parent categories matching those matcher objects. Each matcher object SHALL include a `name` string and MAY include `regex: true`; when `regex` is omitted or false, `name` SHALL be treated as an exact literal category name rather than a regex pattern.

#### Scenario: Config loads one child sync target with multiple account mappings and env-var indirection
- **WHEN** the operator provides a config file containing one parent budget definition, one child sync target, token environment-variable names, and multiple `accountMappings` entries
- **THEN** the syncer SHALL load and validate that sync target successfully
- **AND** each mapping SHALL retain its `mappingKey`, `parentCategoryNames`, and `childAccountName`

#### Scenario: Literal parent category names default to exact matching
- **WHEN** an account mapping contains `parentCategoryNames: [{ name: "Child One Spend Bank" }]`
- **THEN** the matcher SHALL match the exact parent category name `Child One Spend Bank`
- **AND** it SHALL NOT interpret the name as a regex pattern

#### Scenario: Regex parent category names are opt-in per matcher
- **WHEN** an account mapping contains `parentCategoryNames: [{ name: "Child One Gold CD.*", regex: true }]`
- **THEN** the matcher SHALL treat `name` as a regex pattern and match parent categories such as `Child One Gold CD 2-Month 07/31/26`

#### Scenario: One mapping mixes literal and regex category names
- **WHEN** an account mapping contains both a literal matcher and a `regex: true` matcher
- **THEN** parent categories matching either matcher SHALL map to that mapping's child account

#### Scenario: Multiple parent category names map to one child account
- **WHEN** one account mapping contains multiple parent category name matchers and one child account name
- **THEN** parent categories matching any of those matchers SHALL map to that child account

#### Scenario: One child budget maps different parent category groups to different child accounts
- **WHEN** one child sync target defines separate mappings for literal `Child One Spend Bank`, literal `Child One Give Bank`, and regex `Child One Gold CD.*`
- **THEN** the syncer SHALL preserve those as distinct mappings that can route matching parent categories to `Spend Account`, `Give Account`, and `CD Account` respectively

#### Scenario: Missing required account mapping fails fast
- **WHEN** a child sync target is missing `accountMappings` or an account mapping is missing `mappingKey`, `parentCategoryNames`, a matcher `name`, or `childAccountName`
- **THEN** startup SHALL fail with a validation error identifying the missing configuration field

#### Scenario: Invalid regex matcher fails fast
- **WHEN** an account mapping matcher has `regex: true` and contains a syntactically invalid regex pattern
- **THEN** startup SHALL fail with a validation error identifying the child key, mapping key, and invalid pattern

#### Scenario: Literal names containing regex metacharacters are valid literals
- **WHEN** an account mapping matcher has `regex` omitted or false and `name` contains characters that are meaningful in regex syntax
- **THEN** startup SHALL treat the value as a literal string and SHALL NOT reject it as an invalid regex

#### Scenario: Duplicate mapping keys fail fast
- **WHEN** two account mappings under the same child sync target use the same `mappingKey`
- **THEN** startup SHALL fail with a validation error identifying the duplicate key

### Requirement: Runtime config SHALL support continuous polling and rolling-log settings
The runtime YAML SHALL allow operators to configure the polling interval, log file location, rolling/rotation behavior, and log level used by the standalone syncer.

#### Scenario: Config supplies explicit polling and logging settings
- **WHEN** the operator sets a polling interval, log file path, rolling-log policy inputs, and log level in the config
- **THEN** the syncer SHALL use those values for continuous operation and logging bootstrap

#### Scenario: Invalid polling or logging settings are rejected
- **WHEN** the config provides a non-positive polling interval or malformed logging settings
- **THEN** startup SHALL fail with a validation error rather than running with unsafe sync behavior

### Requirement: Runtime config and CLI SHALL support durable SQLite replay-protection settings
The runtime YAML SHALL allow operators to configure the default SQLite sync-state location and any recent-history lookback / replay-protection settings, while the sync CLI SHALL allow overriding the database path with `--sync-state-db-path`.

#### Scenario: Config supplies default SQLite state path and CLI overrides it
- **WHEN** the operator sets a default SQLite sync-state path in config and also passes `--sync-state-db-path`
- **THEN** the syncer SHALL use the CLI-provided database path for that run

#### Scenario: Invalid replay-protection settings are rejected
- **WHEN** the config provides a non-positive or malformed recent-history lookback value
- **THEN** startup SHALL fail with a validation error rather than running with unsafe sync behavior

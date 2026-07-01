## ADDED Requirements

### Requirement: Runtime config SHALL describe standalone sync settings and per-budget credentials explicitly
The runtime YAML SHALL support explicit parent/child sync configuration for the standalone syncer that names the parent budget used as the sync source, one or more child sync targets, and the mapping data needed to translate parent-budget category activity into child-budget transactions. Each budget definition SHALL include the environment-variable name that holds that budget’s YNAB access token rather than embedding the token value in YAML. Each child sync target SHALL include the child budget name, the mapped parent category names that belong to that child, and the child budget account name that receives mirrored transactions.

#### Scenario: Config loads one child sync target with explicit mappings and env-var indirection
- **WHEN** the operator provides a config file containing one parent budget definition, one child sync target, token environment-variable names, mapped parent category names, and a child budget account name
- **THEN** the syncer SHALL load and validate that sync target successfully

#### Scenario: Missing required child mapping fails fast
- **WHEN** a child sync target is missing a required field such as child budget name, token env var name, mapped parent category name, or child budget account name
- **THEN** startup SHALL fail with a validation error identifying the missing configuration field

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

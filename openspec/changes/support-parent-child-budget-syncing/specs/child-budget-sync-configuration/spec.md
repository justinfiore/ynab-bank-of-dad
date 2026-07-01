## ADDED Requirements

### Requirement: Runtime config SHALL describe parent and child sync mappings explicitly
The runtime YAML SHALL support explicit parent/child sync configuration that names the parent budget used as the sync source, one or more child sync targets, and the mapping data needed to translate parent-budget category activity into child-budget transactions. Each child sync target SHALL include the child budget name, the access-token environment variable name or other configured credential source for that child budget, the mapped parent category names that belong to that child, and the child budget account name that receives mirrored transactions.

#### Scenario: Config loads one child sync target with explicit mappings
- **WHEN** the operator provides a config file containing one child sync target with a parent budget name, child budget name, token source, mapped parent category names, and child budget account name
- **THEN** the CLI SHALL load and validate that sync target successfully

#### Scenario: Missing required child mapping fails fast
- **WHEN** a child sync target is missing a required field such as child budget name, token source, mapped parent category name, or child budget account name
- **THEN** startup SHALL fail with a validation error identifying the missing configuration field

### Requirement: Runtime config SHALL support durable replay-protection settings
The runtime YAML SHALL allow operators to configure the persisted sync-state location and the recent-history lookback window used to re-read parent transactions and money movements safely across reruns.

#### Scenario: Config supplies explicit sync-state path and lookback window
- **WHEN** the operator sets a sync-state file path and recent-history lookback value in the config
- **THEN** the CLI SHALL use those values for replay protection and recent source-event polling

#### Scenario: Invalid replay-protection settings are rejected
- **WHEN** the config provides a non-positive or malformed recent-history lookback value
- **THEN** startup SHALL fail with a validation error rather than running with unsafe sync behavior

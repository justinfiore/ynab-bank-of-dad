## 1. Update sync configuration model

- [ ] 1.1 Replace `ChildBudgetSyncTarget.parentCategoryNames` and `childAccountName` with `accountMappings` in config/runtime models.
- [ ] 1.2 Add a `ChildAccountMapping` model with `mappingKey`, `parentCategoryNames`, and `childAccountName`.
- [ ] 1.3 Add a `ParentCategoryNameMatcher` model with required `name` and optional `regex` defaulting to `false`.
- [ ] 1.4 Validate `accountMappings` is non-empty for every child target.
- [ ] 1.5 Validate `mappingKey` values are unique within each child target.
- [ ] 1.6 Validate every `parentCategoryNames` entry has a non-empty `name` and boolean-or-omitted `regex`.
- [ ] 1.7 Validate only `regex: true` entries as syntactically valid regex patterns; do not regex-compile literal names.
- [ ] 1.8 Fail fast with path-specific errors for missing/invalid `accountMappings` fields.

## 2. Update planning behavior

- [ ] 2.1 Resolve parent category names against each child target's account mappings using exact literal matching and opt-in regex matching.
- [ ] 2.2 Plan child transactions using the selected mapping's `childAccountName`.
- [ ] 2.3 Preserve support for multiple literal and/or regex parent category names mapping to one child account.
- [ ] 2.4 Support multiple child account mappings under the same child budget.
- [ ] 2.5 Implement disambiguation: exact literal matches win before regex matches, then config-order first match wins.
- [ ] 2.6 Include `mappingKey`/resolved child account details in plan models, logs, and idempotency/audit data.

## 3. Update apply/state behavior

- [ ] 3.1 Resolve and cache child account IDs per child budget/account name rather than once per child target.
- [ ] 3.2 Ensure state records and idempotency keys distinguish target mappings/accounts safely.
- [ ] 3.3 Preserve retry-safe behavior for missing child accounts after the account mapping is corrected.

## 4. Update automated coverage

- [ ] 4.1 Add config tests for valid `accountMappings` with multiple mappings under one child target.
- [ ] 4.2 Add config tests for literal `parentCategoryNames` entries where `regex` is omitted and defaults to false.
- [ ] 4.3 Add config tests for mixed literal and `regex: true` parent category names in one mapping.
- [ ] 4.4 Add config tests for invalid regex only when `regex: true`, duplicate mapping keys, invalid/non-boolean `regex`, and missing matcher `name`.
- [ ] 4.5 Add planner tests proving `Child One Spend Bank` can map to `Spend Account` while `Child One Give Bank` maps to `Give Account` for the same child budget.
- [ ] 4.6 Add planner tests proving literal names containing regex metacharacters are treated literally when `regex` is omitted or false.
- [ ] 4.7 Add planner tests proving `name: Child One Gold CD.*` with `regex: true` maps matching categories to `CD Account`.
- [ ] 4.8 Add planner/apply tests proving account lookup is per mapped child account, not per child target.
- [ ] 4.9 Add disambiguation tests proving exact literal matches beat overlapping regex matches even when the regex mapping appears earlier.
- [ ] 4.10 Add disambiguation tests proving first matching mapping wins when multiple regex mappings match and no literal exact match exists.
- [ ] 4.11 Add disambiguation tests proving first literal mapping wins when duplicate exact literals are present across mappings.
- [ ] 4.12 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew testAll`.
- [ ] 4.13 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist`.

## 5. Update documentation and examples

- [ ] 5.1 Update `config.yaml.example` to show only the new `sync.childBudgets[*].accountMappings[*].parentCategoryNames[*].name` shape with optional `regex: true`.
- [ ] 5.2 Update `CONFIGURATION.md` to document only the new account-mapping config shape, literal default matching, opt-in regex matching, mixed literal/regex lists, and disambiguation rules.
- [ ] 5.3 Update `QUICK_START.md` to show simple literal-only config first and regex as an advanced example, using only the new config shape.
- [ ] 5.4 Update `README.md` references to the sync config shape and matching behavior where present, using only the new config shape.
- [ ] 5.5 Update `PARENT_CHILD_SYNC_MANUAL_TESTING.md` so manual tests cover multiple child accounts for one child budget, literal matching, regex matching, and disambiguation expectations, using only the new config shape.
- [ ] 5.6 Remove the old child-target-level `parentCategoryNames` + `childAccountName` shape from docs, examples, and tests rather than documenting migration or backward compatibility.

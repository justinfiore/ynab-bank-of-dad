## Why

The current parent/child sync implementation models each child budget with a flat `parentCategoryNames` list and one `childAccountName`. That allows multiple parent categories to feed one child account, but it does not allow a single child budget to define multiple parent-category groups that map to different child accounts.

That is incorrect for the intended Bank of Dad model. A child can have multiple account-like categories in the parent budget, such as `Child One Spend Bank`, `Child One Give Bank`, and `Child One Gold CD ...`, and those categories may need to create transactions in different child-budget accounts such as `Spend Account`, `Give Account`, and `CD Account`.

Most users should be able to configure literal category names without learning regular expressions or escaping regex metacharacters. Regex matching is still useful for advanced grouped categories such as `Child One Gold CD.*`, so the config needs to support both literal names and opt-in regex patterns in the same mapping list.

## What Changes

- Replace the per-child flat `parentCategoryNames` + single `childAccountName` sync mapping with per-child `accountMappings` as the only supported sync mapping shape.
- Each `accountMappings[]` entry maps one or more parent category name matchers to exactly one child account name.
- Each parent category matcher uses `name` plus optional `regex: true`; omitted/false `regex` means exact literal matching.
- Multiple mapping entries may point to the same child account name, so the feature still supports many parent categories mapping to one child account.
- Multiple mapping entries may also point to different child account names inside the same child budget.
- Parent category matching supports mixed literal and regex matchers in one list.
- Disambiguation is deterministic: exact literal matches win first; otherwise the first matching mapping in config order wins.
- Sync planning, idempotency, state, logs, docs, and manual testing guides report the resolved mapping/account used for each planned child transaction.
- Configuration validation rejects missing mapping fields, duplicate mapping keys, invalid regex patterns for `regex: true` entries, and malformed `regex` flags before live sync starts.
- No migration or backward-compatible legacy config support is needed; docs and tests should describe only the new `accountMappings` shape.

## Scope

In scope:
- `RuntimeConfig` sync config parsing and validation
- sync planner logic that resolves parent category names to child account mappings
- child account lookup/application behavior for mapped accounts
- idempotency/state details needed to distinguish child account targets safely
- examples in `config.yaml.example`, `CONFIGURATION.md`, `QUICK_START.md`, `README.md`, and `PARENT_CHILD_SYNC_MANUAL_TESTING.md` that show only the new `accountMappings` config shape
- unit, WireMock, and relevant integration tests for literal matching, opt-in regex matching, and disambiguation behavior

Out of scope:
- changing allowance category/account naming rules unrelated to the syncer
- introducing bidirectional child-budget reconciliation
- live YNAB verification with real credentials
- automatic discovery of child account mappings without explicit config

## Impact

Likely touched areas:
- `src/main/groovy/ynabbankofdad/config/RuntimeConfig.groovy`
- `src/main/groovy/ynabbankofdad/sync/ChildSyncPlanner.groovy`
- `src/main/groovy/ynabbankofdad/sync/ChildSyncApplier.groovy`
- sync model/state classes under `src/main/groovy/ynabbankofdad/sync/**`
- sync tests under `src/test/groovy/*Sync*Spec.groovy` and `RuntimeConfigSpec.groovy`
- `config.yaml.example`, `CONFIGURATION.md`, `QUICK_START.md`, `README.md`, and `PARENT_CHILD_SYNC_MANUAL_TESTING.md`

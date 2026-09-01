## Context

`ParentChildBudgetSyncer.resolveChildRouting` currently looks up each mapped `childAccountName` with `YnabBudgetRepository.getAccountId`. That method GETs `/v1/plans/{plan_id}/accounts` and throws if the exact name is missing. The child's routing then fails for the cycle.

YNAB documents account creation:

- Endpoint: `POST /v1/plans/{plan_id}/accounts` (legacy `/v1/budgets/{budget_id}/accounts` still exists; this repo uses `/v1/plans`)
- Documented required `SaveAccount` fields: `name`, `type`, `balance`
- Create `type` values documented for this endpoint include `savings`
- Success: HTTP 201 with `data.account` including `id` and `on_budget`
- `on_budget` is documented on the account *response*. Published SaveAccount request models used by SDKs only require `name`, `type`, and `balance`

`YnabHttpClient.postJsonWithMetadata` already exists. There is no create-account wrapper yet.

Existing mapping rules stay in place: a parent category must match a child's `accountMappings` before any child work is considered. Auto-create does not invent mappings.

## Goals / Non-Goals

**Goals:**

- Opt-in per child budget: `autoCreateAccounts: true`
- When a mapped parent category has no existing child account, create a Savings account named from the parent category (after optional regex strip)
- Configurable on-budget vs tracking for created accounts, implemented only through fields the live YNAB SaveAccount contract actually accepts
- Keep `--dry-run` free of account-create POSTs
- Reuse an existing account with the derived name so a second cycle does not create duplicates

**Non-Goals:**

- Auto-creating accounts for parent categories that match no `accountMappings` matcher on that child
- Creating accounts in the parent budget
- Changing allowance Record Allowance account lookup
- Bidirectional child-to-parent sync
- Live disposable QA as part of this proposal (WireMock only unless Justin later asks)
- Silently changing `type` away from `savings` to fake tracking accounts

## Decisions

### 1. Auto-create is a child-budget flag with two companion settings

```yaml
sync:
  childBudgets:
    - childKey: child-one
      budgetName: Demo Child One Budget
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      autoCreateAccounts: true
      createdAccountOnBudget: true
      accountCreationNameStripRegex: " Bank$"
      accountMappings:
        - mappingKey: spend
          parentCategoryNames:
            - name: "Child One Spend Bank"
          childAccountName: Spend
```

| Key | Default | Meaning |
|---|---|---|
| `autoCreateAccounts` | `false` | When false, missing accounts keep today's fail-and-skip behavior |
| `createdAccountOnBudget` | `true` when auto-create is on | `true` = budget / on-budget; `false` = tracking / off-budget |
| `accountCreationNameStripRegex` | omitted | Java regex; every match in the parent category name is replaced with `""` |

`childAccountName` stays required on each mapping so existing configs keep working. It is the first lookup key. Auto-create is the fallback when that name is not in the child budget.

Startup validation:

- `autoCreateAccounts` and `createdAccountOnBudget`, when present, MUST be booleans
- `accountCreationNameStripRegex`, when present, MUST be a non-empty string that compiles as a Java `Pattern`
- Invalid regex fails fast with child key and pattern in the message
- Companion keys MAY appear when `autoCreateAccounts` is false; they are validated but unused at runtime

### 2. Eligibility is still mapping-based

A parent category is eligible for auto-create on a child only when it matches that child's `accountMappings` (same literal-then-regex disambiguation as today).

Unmapped parent categories stay ignored. That prevents one child's `autoCreateAccounts: true` from creating accounts for every other kid's categories in the parent budget.

### 3. Find-or-create order during routing

For each needed mapping on a child with at least one matching category in the cycle:

1. If an open-or-current account named `mapping.childAccountName` exists, cache that id and use it. Do not create.
2. Else if `autoCreateAccounts` is false, throw the current "Could not find account named '...'" error so routing fails for that child as today.
3. Else derive a name from the matching parent category:
   - start with the parent category name
   - if `accountCreationNameStripRegex` is set, `replaceAll` every match with `""`
   - trim whitespace
   - if the result is empty, fail that child's routing with an error that names the child key and original category
4. If an account with the derived name already exists, cache and use it. Do not POST.
5. Else if this run is `--dry-run`, log that a Savings account with that derived name would be created (`createdAccountOnBudget` included in the log) and do **not** POST. Do not plan financial mutations for those categories in this dry-run cycle.
6. Else POST create, cache the returned `id`, and continue planning in the same live cycle.

Same derived name in one cycle is created at most once. Later categories reuse the cache.

Example: mapping `childAccountName: Spend` is missing; parent category `Child One Spend Bank` with strip regex ` Bank$` creates/reuses `Child One Spend`.

### 4. YNAB create-account wrapper, no guessed request fields

Add `YnabBudgetRepository.createAccount(String planId, String name, String type, long balance)` (plus `on_budget` only if the live schema includes it).

Documented live request:

```json
{
  "account": {
    "name": "Child One Spend",
    "type": "savings",
    "balance": 0
  }
}
```

Path: `POST /v1/plans/{plan_id}/accounts` via existing `postJsonWithMetadata`.

Require HTTP 201 and a non-empty `data.account.id`. Map the returned account enough to cache `id` and `name`. Do not log tokens.

**Budget vs tracking:** Apply MUST re-read the current official OpenAPI (`SaveAccount` / `PostAccountWrapper`) rather than copying this design's field list blindly.

- If live `SaveAccount` accepts `on_budget`, send `createdAccountOnBudget` as `on_budget` along with `type: savings` and `balance: 0`.
- If live `SaveAccount` does not accept `on_budget` and `createdAccountOnBudget` is `true`, omit `on_budget` (savings accounts are on-budget in YNAB).
- If live `SaveAccount` does not accept `on_budget` and `createdAccountOnBudget` is `false`, fail fast with a clear error. Do not switch `type` to `otherAsset` or any other type to approximate tracking. The operator asked for a Savings account; faking a different type is out of scope.

### 5. List-then-lookup instead of throw-first

Routing should list child accounts once per child per cycle (or reuse the GET that `getAccountId` already performs) and search by exact name. That avoids a throw/catch around "missing account" when auto-create is on, and lets derived-name reuse happen without an extra round trip per mapping.

`getAccountId` used by Record Allowance can stay throw-on-miss.

### 6. Preserve mapping, memo, and reconciliation behavior

Once an account id is cached, `DesiredMirrorFactory` and reconciliation stay the same: child transactions still use the resolved account, leave child category unset, and stay cleared/unapproved on create/update.

Auto-create does not change parent-authoritative reconciliation rules. It only supplies a missing child account id before planning.

## Risks / Trade-offs

- [Live account creates] → Account creation is a YNAB write. Gate it on `autoCreateAccounts: true` and skip the POST in `--dry-run`. First live run with the flag on can create several accounts.
- [Per-category accounts when the mapped name is missing] → If `childAccountName` is absent in YNAB, each matching parent category can get its own derived-name account (for example each Gold CD category). If the mapped name exists, all those categories still share it. Document this in config docs.
- [Tracking + savings may be impossible] → Official create docs require `type` and do not document `on_budget` as a request field. Fail closed when tracking is requested and the live contract cannot honor it, rather than creating a non-savings type.
- [Empty names after strip] → A too-greedy `accountCreationNameStripRegex` can wipe the name. Fail that child's routing instead of posting a blank name.
- [Name collisions] → Derived names are exact-match lookups. Two parent categories that strip to the same string share one child account. That is accepted.
- [Dry-run does not show downstream transactions for new accounts] → Dry-run cannot mint real YNAB ids. It logs planned creates and skips financial plans for those categories until a live run creates the account.

## Migration Plan

- Default `autoCreateAccounts: false` keeps current behavior for every existing config.
- No SQLite schema change.
- Update `config.yaml.example` and sync docs with a commented example of the three new keys.
- Rollback: set `autoCreateAccounts: false`. Already-created YNAB accounts remain; the syncer will keep using them if they match `childAccountName` or a later derived-name lookup.

## Open Questions

None for proposal review. Apply must confirm the live `SaveAccount` schema before choosing whether `on_budget` is sent. If tracking-savings is unsupported, the implementation fails as in Decision 4 rather than inventing a type workaround.

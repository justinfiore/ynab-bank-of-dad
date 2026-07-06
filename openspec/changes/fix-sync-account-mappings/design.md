## Context

The first implementation treats each child sync target as:

```yaml
sync:
  childBudgets:
    - childKey: child-one
      budgetName: Child One Budget
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      parentCategoryNames:
        - "Child One Spend Bank"
        - "Child One Give Bank"
      childAccountName: Child One Checking
```

That shape can only route all configured parent categories for `child-one` to one child account. The desired model is one child budget with multiple account-level mappings, while keeping literal category names easy for non-regex users:

```yaml
sync:
  childBudgets:
    - childKey: child-one
      budgetName: Child One Budget
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      accountMappings:
        - mappingKey: spend
          parentCategoryNames:
            - name: Child One Spend Bank
          childAccountName: Spend Account
        - mappingKey: give
          parentCategoryNames:
            - name: Child One Give Bank
          childAccountName: Give Account
        - mappingKey: cd
          parentCategoryNames:
            - name: Child One Gold CD.*
              regex: true
          childAccountName: CD Account
```

This still supports many parent categories to one child account by listing multiple `parentCategoryNames` entries in a single mapping, or by using multiple mapping entries with the same `childAccountName` when that is clearer operationally. Literal category names remain the default so operators do not need to escape regex metacharacters for normal YNAB category names.

## Decisions

### 1. Use `accountMappings` as the canonical sync mapping shape

Each `ChildBudgetSyncTarget` should contain `accountMappings: List<ChildAccountMapping>` instead of the flat `parentCategoryNames` + `childAccountName` pair.

Proposed model:

```groovy
@Immutable
class ChildBudgetSyncTarget {
    String childKey
    String budgetName
    String tokenEnvVarName
    List<ChildAccountMapping> accountMappings
}

@Immutable
class ChildAccountMapping {
    String mappingKey
    List<ParentCategoryNameMatcher> parentCategoryNames
    String childAccountName
}

@Immutable
class ParentCategoryNameMatcher {
    String name
    Boolean regex = false
}
```

`mappingKey` gives state/log/test output a stable operator-facing identifier for the mapping. It should be unique within a child target.

### 2. Parent category names support literal matching by default and regex matching as an opt-in

Each `parentCategoryNames[]` item is an object with:
- `name`: required non-empty string. When `regex` is false or omitted, this is a literal YNAB parent category name. When `regex: true`, this is a Java/Groovy regex pattern.
- `regex`: optional boolean, default `false`.

Literal matching must use exact string equality against the full parent category name, not regex interpretation. This lets normal users paste category names directly without escaping characters like `(`, `)`, `.`, `+`, `[`, or `$`.

Regex matching is the advanced path for grouped categories. For example:

```yaml
parentCategoryNames:
  - name: Child One Gold CD.*
    regex: true
```

Validation should fail fast when a `regex: true` pattern is syntactically invalid. Startup should identify the child key, mapping key, and bad pattern in the error message. Literal names should not be regex-compiled.

### 3. Disambiguation is deterministic, not an error

A single parent category name may match more than one mapping, especially when a broad regex overlaps a literal category. The planner must resolve that deterministically instead of failing or skipping:

1. If the category exactly matches any literal (`regex: false` or omitted) parent category name, use the first mapping containing such an exact literal match.
2. Otherwise, use the first mapping whose `parentCategoryNames` matcher matches the category, evaluating mappings in config order and names inside each mapping in config order.
3. If no matcher matches, no child work is planned for that child.

This means literal exact matches take precedence over regex matches even if the regex mapping appears earlier in the config. When there are multiple literal exact matches, config order still breaks the tie. When only regexes match, the first matching mapping wins.

The implementation and docs should warn that overlapping mappings are allowed but order-sensitive; operators should place broad regex mappings after more specific mappings for readability even though literal matches always win.

### 4. Planning and state should preserve the resolved mapping/account

`ChildTransactionPlan` should include the resolved `mappingKey` in addition to `childAccountName` and `parentCategoryName`. Idempotency should include enough target identity to distinguish mappings/accounts safely. At minimum, include child key, child account name or resolved target account id, event type/anchor, category id/name, movement direction, and amount. Including `mappingKey` is useful for audit and avoids silently collapsing mappings that share an account but represent different operator intent.

State/log output should make manual verification easy:
- child key
- mapping key
- parent category name
- whether the match came from a literal or regex matcher when helpful
- child account name
- event type and idempotency key

### 5. Child account lookup should be per mapping/account, not per child target

The applier currently caches one resolved account id per child context. After this change it must resolve/cache account ids by child budget plus `childAccountName`, because one child budget can target several child accounts in the same sync cycle.

A practical implementation can keep a map such as `accountIdsByName` on `ChildSyncContext` or inside `ChildSyncApplier` for the cycle.

### 6. Docs and tests should only reflect the new config shape

Because no one has started using the syncer yet, the implementation does not need a migration or backward-compatibility path for the flat child-target-level `parentCategoryNames` + `childAccountName` shape. Docs, examples, and tests should remove the old shape rather than documenting both forms.

Docs and examples should show only `accountMappings` and explain:
- literal `parentCategoryNames` entries using `name` only
- regex entries using `name` plus `regex: true`
- mixed literal and regex entries in the same list
- multiple parent category names to one child account
- multiple child accounts inside the same child budget
- deterministic disambiguation: literal exact match first, otherwise first matching mapping wins

## Open Questions

None. The requested behavior is explicit: `accountMappings` are required, literal category names are the default, regex category names are opt-in with `regex: true`, and disambiguation must prefer exact literal matches before falling back to first-match-wins behavior.

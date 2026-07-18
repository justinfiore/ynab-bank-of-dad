## Context

The parent-child budget sync (ParentChildBudgetSyncer, ChildSyncPlanner, ChildSyncApplier, ChildTransactionPayloadFactory) copies transaction details from a parent budget into one or more child budgets. Currently the memo is passed through verbatim and the `cleared` field is never set in the payload (defaults to uncleared on YNAB side). 

The requested enhancement adds two small, orthogonal behaviors:
1. Force `cleared: "cleared"` on every created child transaction so YNAB treats them as bank-cleared and can auto-match against manual entries.
2. Allow per-child `memoPrefix` / `memoSuffix` configuration so operators can tag or namespace the auto-created transactions for easy filtering/search in the child's YNAB budget.

All changes remain within the existing Groovy 5 / Gradle 9 / JDK 25 stack and follow the brownfield incremental style used by prior sync enhancements.

## Goals / Non-Goals

**Goals:**
- Make synced child transactions appear cleared to YNAB.
- Provide a simple, per-childBudget string prefix/suffix mechanism (default "YBOD: ").
- Preserve all existing behavior and config compatibility.
- Keep the change small and testable.

**Non-Goals:**
- Changing how memos are generated in the planner (only transformation at payload time or plan time).
- Adding prefix/suffix to parent-budget or non-sync transactions.
- Any migration tooling or backward-compat shims.
- Altering YNAB API interaction patterns beyond the minimal payload addition (official docs will be consulted for the exact `cleared` value semantics).

## Decisions

**Decision: Apply prefix/suffix in ChildTransactionPayloadFactory.buildTransaction (or immediately before) rather than mutating the plan object**
Rationale: The ChildTransactionPlan is also persisted to the state DB and used for idempotency keys and logging. Keeping the original memo in the plan preserves auditability while only the wire payload receives the decorated memo. This avoids side-effects on the planner and state layers.

**Decision: Store memoPrefix/memoSuffix directly on ChildBudgetSyncTarget (immutable record)**
Rationale: The config model already centralizes per-child settings. Adding two optional String fields with defaults applied at load/validation time keeps the surface minimal and makes the setting visible in `RuntimeConfig` dumps and error messages. No new wrapper types needed.

**Decision: Default prefix = "YBOD: " (with trailing space), suffix = ""**
Rationale: Matches the explicit request; the trailing space prevents the tag from running into the original memo text. Empty suffix is the natural default.

**Decision: Trim the final decorated memo**
Rationale: The payload factory constructs `prefix + source memo + suffix` and trims that final value. Configured whitespace inside the decorated value is preserved, but leading and trailing whitespace is not. A null or empty source memo follows the same rule, including producing an empty string when the decoration contains only whitespace.

**Decision: cleared value = "cleared" (string literal)**
Rationale: Standard YNAB transaction cleared status. The exact casing and type will be verified against the current YNAB API reference during implementation; the design intentionally does not hard-code unverified details.

**Decision: No changes to import_id or approved fields**
Rationale: These remain as currently implemented; cleared is an independent concern.

## Risks / Trade-offs

- [YNAB API contract] The exact key name and allowed values for the cleared flag could differ slightly from the assumed shape → Mitigation: consult official YNAB REST docs (https://api.ynab.com) before writing the payload change; add a small test that the key appears.
- [Searchability] If a child manually enters a transaction with the exact same memo prefix, filtering may return both; acceptable because the prefix is intended as a namespace.
- [Payload size] Negligible; memo strings remain short.

## Migration Plan

No migration required. Existing configs continue to work with the documented default prefix behavior. New fields are purely additive.

After implementation, run `./gradlew testAll` (with JAVA_HOME set). WireMock coverage simulates a `--dry-run` sync cycle with custom decoration and verifies the cleared payload plan without credentials, child POSTs, or SQLite mutation. Operators should still use `--dry-run` before any live run that creates real transactions.

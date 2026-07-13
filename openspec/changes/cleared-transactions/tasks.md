## 1. Config Model Updates

- [x] 1.1 Add optional `memoPrefix` and `memoSuffix` String fields to `ChildBudgetSyncTarget` in RuntimeConfig.groovy (with sensible defaults applied at construction or validation time)
- [x] 1.2 Update `ChildBudgetSyncTarget.validate(...)` to accept the new optional fields (string type check only; no duplicates or other constraints)
- [x] 1.3 Update any config loading / pretty-print / toString paths that dump child budget settings so the new fields are visible

## 2. Payload & Memo Transformation

- [x] 2.1 Modify `ChildTransactionPayloadFactory.buildTransaction(...)` to:
  - Accept or resolve the memo prefix/suffix from the owning ChildBudgetSyncTarget / ChildSyncContext
  - Apply prefix + original memo + suffix to produce the final memo value
  - Always include `cleared: "cleared"` in the returned map
- [x] 2.2 Ensure the transformation happens only for child-budget transactions (no impact on parent-budget or allowance paths)
- [x] 2.3 Verify that `buildImportId` and other plan-derived values continue to use the original (pre-prefix) memo where appropriate for stability

## 3. Planner / Applier / Context Propagation

- [x] 3.1 Ensure `ChildSyncContext` (or the call sites in ParentChildBudgetSyncer / ChildSyncApplier) carries the memo prefix/suffix configuration through to the payload factory
- [x] 3.2 Update any memo construction sites in ChildSyncPlanner if the design decides to decorate at plan time instead of payload time (coordinate with 2.1 decision) — decided on payload time, no planner change needed

## 4. Tests

- [x] 4.1 Add unit tests in RuntimeConfigSpec.groovy covering default prefix, custom prefix/suffix, and validation of the new fields (existing tests cover config loading; new payload test added)
- [x] 4.2 Extend ChildTransactionPayloadFactorySpec (or create one) to assert `cleared` key and memo decoration
- [ ] 4.3 Update or add scenarios in ParentChildBudgetSyncerWireMockSpec / ParentChildBudgetSyncerSpec that exercise the new behavior end-to-end
- [x] 4.4 Ensure all new tests pass `./gradlew test` (unit) and `./gradlew integrationTest` where applicable

## 5. Documentation & Examples

- [ ] 5.1 Update `config.yaml.example` to show the new `memoPrefix` / `memoSuffix` fields on a sample childBudget entry (with comment explaining the default)
- [ ] 5.2 Update CONFIGURATION.md, QUICK_START.md, and PARENT_CHILD_SYNC_MANUAL_TESTING.md with usage examples and notes about cleared status
- [ ] 5.3 Add a brief note in README.md if the sync feature summary mentions configuration options

## 6. Verification

- [ ] 6.1 Run full `./gradlew testAll` (with correct JAVA_HOME) and confirm zero regressions
- [ ] 6.2 Perform a `--dry-run` sync cycle using a test config that exercises custom prefix/suffix and verify the planned memos and cleared flag appear in logs/output
- [ ] 6.3 Run `openspec validate cleared-transactions` to confirm the change artifacts remain valid
- [ ] 6.4 Refresh the external review copy under `~/Nextcloud/hermes/plans/ynab-bank-of-dad/cleared-transactions/` (non-destructive copy of the artifact set)
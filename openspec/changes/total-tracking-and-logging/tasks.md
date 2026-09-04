## 1. Account snapshots

- [ ] 1.1 Add `balance` (milliunits, default `0`) to `AccountSnapshot` and verify existing constructors/tests still compile
- [ ] 1.2 Make `YnabBudgetRepository` preserve `balance` from `GET /v1/plans/{plan}/accounts` (missing field → `0`) and verify `YnabBudgetRepositorySpec` covers present, missing, and deleted accounts
- [ ] 1.3 Keep `accountIdByName` working as a derived id map so current callers need no behavior change; verify existing account lookup tests still pass via `./gradlew test --tests YnabBudgetRepositorySpec`

## 2. Intent metadata for net change

- [ ] 2.1 Add optional in-memory `targetAccountId` and `priorAmount` to `PlannedReconciliationIntent` without new SQLite columns and verify persist/reconstruct tests still pass
- [ ] 2.2 Stamp CREATE with `targetAccountId` and null `priorAmount`, UPDATE/DELETE with account id plus observed child amount, and verify reconciler unit tests assert those fields

## 3. CycleBalanceReporter

- [ ] 3.1 Add a cycle-scoped parent-category → child-account cache written from `DesiredMirrorFactory` when a desired mirror is resolved, invert it in `CycleBalanceReporter`, and verify a focused Spock spec uses the cached account (including derived names) rather than re-running `accountMappings`
- [ ] 3.2 Implement net change (CREATE = amount, UPDATE = new − prior, DELETE = −prior, NO_OP ignored) grouped by `childKey` + account and verify the unit spec covers the `-1200` / `-500→-800` / `+400` delete case (`net=-1600`)
- [ ] 3.3 Implement dry-run projected child side (`current + net`, missing account = `0`) versus live actual account `balance` and verify both formulas in the unit spec
- [ ] 3.4 Log matches at INFO and mismatches at WARN with child side, parent side, diff, and `projected` vs `actual`; verify a mismatch does not appear as a failure message
- [ ] 3.5 Format all logged money with `YnabLogFormatter.formatAmount` and verify the unit spec asserts dollar strings rather than raw milliunits

## 4. Wire into cycle completion

- [ ] 4.1 Call the reporter from `logCycleCompletion` after the existing created/updated/deleted lines, passing the cycle mapping cache, parent categories, and child account snapshots, and verify existing cycle-summary log assertions still pass
- [ ] 4.2 On live runs, re-GET each resolved child's accounts after apply before reporting; on `--dry-run`, reuse the routing-time list and issue no extra writes; verify with WireMock request counts
- [ ] 4.3 Skip comparison for children that failed routing and skip numeric compare when a live cached account is missing; verify those cases log without failing the run

## 5. Tests and docs

- [ ] 5.1 Extend `ParentChildBudgetSyncerWireMockSpec` so dry-run logs projected balance and live logs post-apply account `balance`, including an explicit mismatch that is not a `SyncRunResult` failure
- [ ] 5.2 Cover multi-category-to-one-account summing from the propagation cache, `netChange=0` for cached accounts with no mutations, and no comparison for categories that never resolved this cycle
- [ ] 5.3 Update `ARCHITECTURE.md` and `PARENT_CHILD_SYNC_MANUAL_TESTING.md` cycle-summary examples to include net change and parent vs child comparison lines
- [ ] 5.4 Run `./gradlew testAll` and confirm it passes

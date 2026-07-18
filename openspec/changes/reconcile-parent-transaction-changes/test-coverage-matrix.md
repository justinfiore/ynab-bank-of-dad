# Reconciliation Test Coverage Matrix

This matrix is part of the implementation contract. Every normative scenario in `specs/parent-transaction-reconciliation/spec.md` has a planned focused unit test and a planned integration test. Test names may be refined during implementation, but no row may be removed or left uncovered without revising the corresponding requirement.

| Normative scenario | Focused unit test | Integration test |
|---|---|---|
| Amount edit retains source identity | `SourceRevisionNormalizerSpec: top-level identity excludes mutable fields` | `ParentChildBudgetSyncerWireMockSpec: amount edit retains source and mirror lineage` |
| Split component retains source identity | `SourceRevisionNormalizerSpec: split identity excludes mutable fields` | `ParentChildBudgetSyncerWireMockSpec: split edit retains component lineage` |
| Ordinary and split identities remain distinct | `SourceRevisionNormalizerSpec: ordinary and split identities do not collide` | `SyncStateStoreIntegrationSpec: ordinary and split source uniqueness` |
| Deleted transaction tombstone is retained | `SourceRevisionNormalizerSpec: deletion normalizes as tombstone` | `YnabTransactionMutationWireMockSpec: transaction tombstone survives repository mapping` |
| Empty delta advances knowledge | `SyncRunCoordinatorSpec: successful empty batch advances knowledge` | `ParentChildBudgetSyncerWireMockSpec: empty delta advances cursor without mutations` |
| Older transaction edit is requested through delta | `YnabBudgetRepositorySpec: cursor request omits bootstrap lookback` | `ParentChildBudgetSyncerWireMockSpec: established cursor reads unrestricted delta` |
| Partial split data cannot imply removal | `ParentTransactionReconcilerSpec: partial split requires full fetch` | `ParentChildBudgetSyncerWireMockSpec: partial split fetches detail before delete` |
| Date amount and payee edit updates in place | `ParentTransactionReconcilerSpec: authoritative changes plan one update` | `ParentChildBudgetSyncerWireMockSpec: authoritative edit updates one child ID` |
| Same-budget account reroute updates in place | `ParentTransactionReconcilerSpec: same-budget reroute plans update` | `ParentChildBudgetSyncerWireMockSpec: same-budget reroute sends no create` |
| Child approval drift is corrected when source is observed | `ParentTransactionReconcilerSpec: observed approval drift plans update` | `ParentChildBudgetSyncerWireMockSpec: observed mirror resets cleared and approval` |
| Parent memo-only edit is a child no-op | `ParentTransactionReconcilerSpec: memo-only edit plans no financial mutation` | `ParentChildBudgetSyncerWireMockSpec: memo-only edit persists revision without update` |
| Financial edit preserves child memo | `ReconciliationOperationApplierSpec: update payload preserves child memo` | `YnabTransactionMutationWireMockSpec: financial update omits or preserves memo` |
| Memo-only edit still verifies mirror existence | `ParentTransactionReconcilerSpec: no-op edit requires mirror verification` | `ParentChildBudgetSyncerWireMockSpec: memo-only edit performs child existence lookup` |
| Parent deletion removes mirror | `ParentTransactionReconcilerSpec: tombstone plans all linked deletes` | `ParentChildBudgetSyncerWireMockSpec: parent tombstone deletes all linked mirrors` |
| Approval withdrawal removes mirror | `ParentTransactionReconcilerSpec: unapproval plans mirror deletion` | `ParentChildBudgetSyncerWireMockSpec: unapproval deletes child mirror` |
| Mapped source becomes unmapped | `ParentTransactionReconcilerSpec: mapped-to-unmapped plans delete` | `ParentChildBudgetSyncerWireMockSpec: unmapped delta deletes existing mirror` |
| Repeated deletion is idempotent | `ReconciliationOperationApplierSpec: absent delete target completes operation` | `YnabTransactionMutationWireMockSpec: documented absent delete is idempotent` |
| Ordinary transaction becomes split | `ParentTransactionReconcilerSpec: ordinary-to-split orders delete then creates` | `ParentChildBudgetSyncerWireMockSpec: ordinary-to-split replaces mirror set` |
| Split transaction becomes ordinary | `ParentTransactionReconcilerSpec: split-to-ordinary orders deletes then create` | `ParentChildBudgetSyncerWireMockSpec: split-to-ordinary replaces mirror set` |
| Split component is added or edited | `ParentTransactionReconcilerSpec: split add and edit touch only desired components` | `ParentChildBudgetSyncerWireMockSpec: split add and edit preserve siblings` |
| Split component is removed | `ParentTransactionReconcilerSpec: split removal deletes matching component only` | `ParentChildBudgetSyncerWireMockSpec: split removal preserves unrelated mirrors` |
| Cross-budget reroute replaces mirror | `ParentTransactionReconcilerSpec: cross-budget reroute creates dependency chain` | `ParentChildBudgetSyncerWireMockSpec: reroute deletes old child before new create` |
| Configuration changes alone do not rewrite history | `ParentTransactionReconcilerSpec: config-only cycle plans no historical work` | `ParentChildBudgetSyncerWireMockSpec: config-only cycle sends no mutations` |
| Manually deleted mirror is recreated on parent edit | `ReconciliationOperationApplierSpec: missing update target converts to create` | `ParentChildBudgetSyncerWireMockSpec: parent edit recreates missing mirror` |
| Missing mirror is recreated after a no-op source edit | `ParentTransactionReconcilerSpec: missing mirror overrides no-op decision` | `ParentChildBudgetSyncerWireMockSpec: memo-only edit recreates missing mirror` |
| Update failure blocks cursor | `SyncRunCoordinatorSpec: failed update blocks ingestion batch` | `ParentChildBudgetSyncerWireMockSpec: update failure preserves cursor` |
| Cross-budget create fails after delete | `ReconciliationOperationApplierSpec: failed dependent create preserves delete` | `ParentChildBudgetSyncerWireMockSpec: reroute create retry does not repeat delete` |
| Three process-like cycles recover mutation work | `SyncRunCoordinatorSpec: batch completion survives retry sequencing` | `ParentChildBudgetSyncerWireMockSpec: three fresh processes retry unfinished work only` |
| Remote mutation succeeds before local completion write fails | `ReconciliationOperationApplierSpec: remote-success crash window is recoverable` | `ParentChildBudgetSyncerWireMockSpec: remote success plus state failure has no second effect` |
| Migration cleanup does not block transaction cursor | `SyncRunCoordinatorSpec: cleanup operations are outside transaction batch` | `ReconciliationMigrationIntegrationSpec: cleanup retry and transaction cursor are independent` |
| Single successful legacy mirror is backfilled | `LegacyMirrorSelectorSpec: eligible row becomes active candidate` | `ReconciliationMigrationIntegrationSpec: single legacy mirror backfills` |
| Duplicate successful legacy mirrors are normalized | `LegacyMirrorSelectorSpec: newest timestamp and row ID win` | `ReconciliationMigrationIntegrationSpec: duplicates queue older deletes` |
| Migration failure rolls back | `SchemaMigrationPlanSpec: failed step does not advance version` | `ReconciliationMigrationIntegrationSpec: injected failure rolls back schema and data` |
| Failed dry-run and missing-ID history is preserved but inactive | `LegacyMirrorSelectorSpec: ineligible rows never activate` | `ReconciliationMigrationIntegrationSpec: ineligible history remains audit-only` |
| Dry-run reports destructive work | `ParentTransactionReconcilerSpec: dry-run describes every operation type` | `ParentChildBudgetSyncerWireMockSpec: destructive dry-run has zero mutations` |
| Dry-run projects legacy migration without changing SQLite | `DryRunMigrationProjectorSpec: legacy cleanup projects in memory` | `ReconciliationMigrationIntegrationSpec: dry-run leaves database byte-identical` |
| Verified update payload is sent | `ChildTransactionPayloadFactorySpec: update contains verified mutable fields only` | `YnabTransactionMutationWireMockSpec: update uses documented endpoint and payload` |
| Verified delete endpoint is idempotent | `YnabBudgetRepositorySpec: delete maps success and documented absence` | `YnabTransactionMutationWireMockSpec: delete contract is contextual and idempotent` |
| Same-ID amount or category change retains movement identity | `MoneyMovementNormalizerSpec: movement identity excludes mutable fields` | `ParentChildBudgetSyncerWireMockSpec: movement edit retains source lineage` |
| Inflow and outflow sides cannot collide | `MoneyMovementNormalizerSpec: direction distinguishes same-child sides` | `SyncStateStoreIntegrationSpec: movement side uniqueness preserves both mirrors` |
| Same-ID movement amount edit updates existing sides | `MoneyMovementReconcilerSpec: amount edit plans side updates` | `ParentChildBudgetSyncerWireMockSpec: movement amount edit updates without duplicate creates` |
| Same-ID movement category reroute reconciles sides | `MoneyMovementReconcilerSpec: category edit compares complete side set` | `ParentChildBudgetSyncerWireMockSpec: movement reroute updates and replaces correct sides` |
| Old observed movement remains eligible for correction | `MoneyMovementReconcilerSpec: existing mirror bypasses new-movement lookback` | `ParentChildBudgetSyncerWireMockSpec: old movement correction updates mirror` |
| Movement disappears from a later snapshot | `MoneyMovementReconcilerSpec: absent ID becomes unconfirmed without delete` | `ParentChildBudgetSyncerWireMockSpec: absent movement sends no child deletion` |
| New movement ID does not prove replacement | `MoneyMovementReconcilerSpec: similarity and group do not establish lineage` | `ParentChildBudgetSyncerWireMockSpec: replacement-like ID does not rewrite prior mirror` |
| Movement mutation failure does not block transaction cursor | `SyncRunCoordinatorSpec: movement completion is cursor-independent` | `ParentChildBudgetSyncerWireMockSpec: movement failure and transaction success advance cursor` |
| Repeated complete snapshot does not duplicate movement work | `MoneyMovementReconcilerSpec: unchanged snapshot plans no operation` | `ParentChildBudgetSyncerWireMockSpec: repeated movement snapshot sends no duplicate mutation` |
| Operator reviews destructive semantics before rollout | `DocumentationContractSpec: onboarding links semantics guide` | `ReconciliationDocumentationIntegrationSpec: guide covers destructive dry-run workflow` |
| Coverage matrix is complete | `ReconciliationCoverageContractSpec: every normative scenario has matrix row` | `ReconciliationCoverageIntegrationSpec: every row names an executable integration feature` |
| Semantics change during implementation | `ReconciliationCoverageContractSpec: spec guide and matrix scenario sets agree` | `ReconciliationCoverageIntegrationSpec: testAll verifies revised paired coverage` |

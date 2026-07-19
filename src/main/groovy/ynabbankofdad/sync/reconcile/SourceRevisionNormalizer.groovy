package ynabbankofdad.sync.reconcile

import ynabbankofdad.sync.model.ParentSubtransactionEvent
import ynabbankofdad.sync.model.ParentTransactionEvent
import ynabbankofdad.sync.state.SourceEntityKey
import ynabbankofdad.sync.state.SourceEntityType

class SourceRevisionNormalizer {
    static SourceEntityKey transactionIdentity(String sourceBudgetId, String transactionId) {
        requireIdentity(sourceBudgetId, transactionId, 'transaction')
        new SourceEntityKey(sourceBudgetId, SourceEntityType.TRANSACTION, transactionId, null, null)
    }

    static SourceEntityKey subtransactionIdentity(String sourceBudgetId, String transactionId, String subtransactionId) {
        requireIdentity(sourceBudgetId, transactionId, 'transaction')
        requireIdentity(sourceBudgetId, subtransactionId, 'subtransaction')
        new SourceEntityKey(sourceBudgetId, SourceEntityType.SUBTRANSACTION, transactionId, subtransactionId, null)
    }

    ParentSourceRevision normalize(String sourceBudgetId, ParentTransactionEvent event,
                                   Integer responseServerKnowledge = null, boolean completeComposition = true) {
        if (!event) {
            throw new IllegalArgumentException('Parent transaction event is required')
        }
        SourceEntityKey parentSource = transactionIdentity(sourceBudgetId, event.id)
        List<NormalizedSourceComponent> components = event.subtransactions ?
            event.subtransactions.collect { ParentSubtransactionEvent sub ->
                new NormalizedSourceComponent(
                    subtransactionIdentity(sourceBudgetId, event.id, sub.id),
                    sub.categoryId, sub.categoryName, sub.amount, sub.memo, sub.deleted == true,
                    sub.payeeId, sub.payeeName)
            }.sort { it.source.parentSubtransactionId } :
            [new NormalizedSourceComponent(parentSource, event.categoryId, event.categoryName,
                event.amount, event.memo, event.deleted == true, event.payeeId, event.payeeName)]

        CompositionSafety safety = !completeComposition ?
            CompositionSafety.FETCH_REQUIRED : CompositionSafety.COMPLETE
        Integer knowledge = responseServerKnowledge != null ? responseServerKnowledge : event.serverKnowledge
        Map normalized = [
            source            : sourceMap(parentSource),
            date              : event.date,
            amount            : event.amount,
            memo              : event.memo,
            approved          : event.approved,
            deleted           : event.deleted == true,
            categoryId        : event.categoryId,
            categoryName      : event.categoryName,
            payeeId           : event.payeeId,
            payeeName         : event.payeeName,
            compositionSafety : safety.name(),
            components        : components.collect { component ->
                [source: sourceMap(component.source), categoryId: component.categoryId,
                 categoryName: component.categoryName, amount: component.amount,
                  memo: component.memo, deleted: component.deleted,
                  payeeId: component.payeeId, payeeName: component.payeeName]
            }
        ]
        String json = ReconciliationCanonicalizer.json(normalized)
        new ParentSourceRevision(parentSource, event.date, event.amount, event.memo, event.approved,
            event.deleted == true, event.categoryId, event.categoryName, event.payeeId, event.payeeName,
            knowledge, components, safety, json, ReconciliationCanonicalizer.hashJson(json))
    }

    static Map<String, Object> sourceMap(SourceEntityKey source) {
        [sourceBudgetId: source.sourceBudgetId, type: source.type.databaseValue,
         parentTransactionId: source.parentTransactionId,
         parentSubtransactionId: source.parentSubtransactionId,
         moneyMovementId: source.moneyMovementId]
    }

    private static void requireIdentity(String sourceBudgetId, String id, String label) {
        if (!sourceBudgetId || !id) {
            throw new IllegalArgumentException("Stable ${label} identity requires source budget and ID")
        }
    }
}

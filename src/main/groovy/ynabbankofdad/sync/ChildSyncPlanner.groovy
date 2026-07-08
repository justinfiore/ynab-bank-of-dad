package ynabbankofdad.sync

import ynabbankofdad.config.ChildAccountMapping
import ynabbankofdad.config.ParentCategoryNameMatcher
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.*

class ChildSyncPlanner {
    private final List<ChildSyncContext> childContexts

    ChildSyncPlanner(List<ChildSyncContext> childContexts) {
        this.childContexts = childContexts
    }

    List<ChildTransactionPlan> buildPlans(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<ParentTransactionEvent> transactions,
        List<MoneyMovementEvent> moneyMovements
    ) {
        List<ChildTransactionPlan> plans = []
        plans.addAll(planTransactions(parentBudgetId, parentCategoriesById, transactions))
        plans.addAll(planMoneyMovements(parentBudgetId, parentCategoriesById, moneyMovements))
        plans
    }

    List<ChildTransactionPlan> planTransactions(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<ParentTransactionEvent> transactions
    ) {
        List<ChildTransactionPlan> plans = []
        transactions.each { ParentTransactionEvent event ->
            if (!event.approved) {
                return
            }

            if (event.subtransactions) {
                event.subtransactions.each { ParentSubtransactionEvent subtransaction ->
                    plans.addAll(planFromCategory(
                        parentBudgetId,
                        parentCategoriesById,
                        subtransaction.categoryId,
                        subtransaction.categoryName,
                        event.date,
                        subtransaction.memo ?: event.memo,
                        subtransaction.amount,
                        'subtransaction',
                        event.id,
                        subtransaction.id,
                        null,
                        null,
                        null,
                        event.id
                    ))
                }
                return
            }

            plans.addAll(planFromCategory(
                parentBudgetId,
                parentCategoriesById,
                event.categoryId,
                event.categoryName,
                event.date,
                event.memo,
                event.amount,
                'transaction',
                event.id,
                null,
                null,
                null,
                null,
                event.id
            ))
        }
        plans
    }

    List<ChildTransactionPlan> planMoneyMovements(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<MoneyMovementEvent> moneyMovements
    ) {
        List<ChildTransactionPlan> plans = []
        moneyMovements.each { MoneyMovementEvent movement ->
            String sourceName = parentCategoriesById[movement.fromCategoryId]?.name
            String destinationName = parentCategoriesById[movement.toCategoryId]?.name
            String memo = "From ${sourceName ?: 'Unknown'} to ${destinationName ?: 'Unknown'}"

            plans.addAll(planFromCategory(
                parentBudgetId,
                parentCategoriesById,
                movement.toCategoryId,
                destinationName,
                movement.eventDate,
                memo,
                movement.amount,
                'money_movement',
                null,
                null,
                movement.id,
                movement.groupId,
                'inflow',
                movement.id,
                "From ${sourceName ?: 'Unknown'}"
            ))
            plans.addAll(planFromCategory(
                parentBudgetId,
                parentCategoriesById,
                movement.fromCategoryId,
                sourceName,
                movement.eventDate,
                memo,
                -movement.amount,
                'money_movement',
                null,
                null,
                movement.id,
                movement.groupId,
                'outflow',
                movement.id,
                "To ${destinationName ?: 'Unknown'}"
            ))
        }
        plans
    }

    List<ChildTransactionPlan> planFromCategory(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        String categoryId,
        String categoryName,
        String date,
        String memo,
        Integer amount,
        String eventType,
        String transactionId,
        String subtransactionId,
        String moneyMovementId,
        String moneyMovementGroupId,
        String movementDirection,
        String eventAnchor,
        String explicitPayeeName = null
    ) {
        if (!categoryId) {
            return []
        }
        String resolvedCategoryName = categoryName ?: parentCategoriesById[categoryId]?.name
        if (!resolvedCategoryName) {
            return []
        }

        childContexts.findResults { ChildSyncContext child ->
            ChildAccountMapping mapping = resolveMapping(child, resolvedCategoryName)
            if (!mapping) {
                return null
            }
            String targetAccountId = child.resolveAccountId(mapping.childAccountName) ?: ''
            String idempotencyKey = ChildSyncIdempotency.composeKey(
                parentBudgetId,
                child.target.childKey,
                mapping.mappingKey,
                targetAccountId,
                eventType,
                transactionId,
                subtransactionId,
                moneyMovementId,
                movementDirection,
                categoryId,
                resolvedCategoryName,
                amount
            )
            new ChildTransactionPlan(
                sourceBudgetId: parentBudgetId,
                targetChildKey: child.target.childKey,
                targetBudgetName: child.target.budgetName,
                mappingKey: mapping.mappingKey,
                parentCategoryName: resolvedCategoryName,
                eventType: eventType,
                parentTransactionId: transactionId,
                parentSubtransactionId: subtransactionId,
                moneyMovementId: moneyMovementId,
                moneyMovementGroupId: moneyMovementGroupId,
                idempotencyKey: idempotencyKey,
                childAccountName: mapping.childAccountName,
                date: date,
                amount: amount,
                memo: memo,
                payeeName: explicitPayeeName,
                approved: true
            )
        }
    }

    private ChildAccountMapping resolveMapping(ChildSyncContext child, String categoryName) {
        ChildAccountMapping literalMatch = child.target.accountMappings.find { ChildAccountMapping mapping ->
            mapping.parentCategoryNames.any { ParentCategoryNameMatcher matcher ->
                !matcher.regex && matcher.name == categoryName
            }
        }
        if (literalMatch) {
            return literalMatch
        }

        child.target.accountMappings.find { ChildAccountMapping mapping ->
            mapping.parentCategoryNames.any { ParentCategoryNameMatcher matcher ->
                matcher.regex && categoryName ==~ matcher.name
            }
        }
    }
}

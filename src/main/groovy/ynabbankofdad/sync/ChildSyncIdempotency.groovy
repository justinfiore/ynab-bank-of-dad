package ynabbankofdad.sync

import ynabbankofdad.sync.model.ChildTransactionPlan

class ChildSyncIdempotency {
    static String composeKey(
        String sourceBudgetId,
        String targetChildKey,
        String mappingKey,
        String targetAccountId,
        String eventType,
        String parentTransactionId,
        String parentSubtransactionId,
        String moneyMovementId,
        String movementDirection,
        String categoryId,
        String categoryName,
        Integer amount
    ) {
        [
            sourceBudgetId,
            targetChildKey,
            mappingKey,
            targetAccountId ?: '',
            eventType,
            parentTransactionId ?: '',
            parentSubtransactionId ?: '',
            moneyMovementId ?: '',
            movementDirection ?: '',
            categoryId ?: '',
            categoryName ?: '',
            amount
        ].join('|')
    }

    static String buildKey(ChildTransactionPlan plan, String targetAccountId) {
        List<String> parts = (plan.idempotencyKey ?: '').split('\\|', -1) as List
        String movementDirection = parts.size() > 8 ? parts[8] : ''
        String categoryId = parts.size() > 9 ? parts[9] : ''
        String categoryName = plan.parentCategoryName ?: (parts.size() > 10 ? parts[10] : '')
        composeKey(
            plan.sourceBudgetId,
            plan.targetChildKey,
            plan.mappingKey,
            targetAccountId,
            plan.eventType,
            plan.parentTransactionId,
            plan.parentSubtransactionId,
            plan.moneyMovementId,
            movementDirection,
            categoryId,
            categoryName,
            plan.amount
        )
    }

    static ChildTransactionPlan withKey(ChildTransactionPlan plan, String idempotencyKey) {
        new ChildTransactionPlan(
            plan.sourceBudgetId,
            plan.targetChildKey,
            plan.targetBudgetName,
            plan.mappingKey,
            plan.parentCategoryName,
            plan.eventType,
            plan.parentTransactionId,
            plan.parentSubtransactionId,
            plan.moneyMovementId,
            plan.moneyMovementGroupId,
            idempotencyKey,
            plan.childAccountName,
            plan.date,
            plan.amount,
            plan.memo,
            plan.payeeName,
            plan.approved
        )
    }
}
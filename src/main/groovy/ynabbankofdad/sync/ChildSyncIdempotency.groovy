package ynabbankofdad.sync

import ynabbankofdad.sync.model.ChildTransactionPlan

class ChildSyncIdempotency {
    static String buildKey(ChildTransactionPlan plan, String targetAccountId) {
        List<String> parts = (plan.idempotencyKey ?: '').split('\\|', -1) as List
        String movementDirection = parts.size() > 8 ? parts[8] : ''
        String categoryId = parts.size() > 9 ? parts[9] : ''
        String categoryName = plan.parentCategoryName ?: (parts.size() > 10 ? parts[10] : '')
        [
            plan.sourceBudgetId,
            plan.targetChildKey,
            plan.mappingKey,
            targetAccountId ?: '',
            plan.eventType,
            plan.parentTransactionId ?: '',
            plan.parentSubtransactionId ?: '',
            plan.moneyMovementId ?: '',
            movementDirection,
            categoryId,
            categoryName,
            plan.amount
        ].join('|')
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
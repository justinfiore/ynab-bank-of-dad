package ynabbankofdad.sync

import ynabbankofdad.sync.model.ChildTransactionPlan
import ynabbankofdad.sync.state.SourceEntityKey

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ChildTransactionPayloadFactory {

    Map<String, Object> buildTransaction(ChildTransactionPlan plan, String accountId, String memoPrefix, String memoSuffix) {
        String finalMemo = ((memoPrefix ?: "") + (plan.memo ?: "") + (memoSuffix ?: "")).trim()
        [
            account_id : accountId,
            date       : plan.date,
            amount     : plan.amount,
            payee_name : plan.payeeName,
            category_id: null,
            memo       : finalMemo,
            cleared    : "cleared",
            approved   : plan.approved,
            import_id  : buildImportId(plan)
        ]
    }

    String buildImportId(ChildTransactionPlan plan) {
        if (!plan?.sourceBudgetId || !plan?.targetChildKey || !plan?.eventType) {
            throw new IllegalArgumentException('Child transaction plan must have stable source and target identity')
        }
        String movementDirection = movementDirection(plan)
        hashImportIdentity([
            plan.sourceBudgetId, plan.targetChildKey, plan.eventType,
            plan.parentTransactionId ?: '', plan.parentSubtransactionId ?: '',
            plan.moneyMovementId ?: '', movementDirection
        ])
    }

    String buildImportId(SourceEntityKey source, String targetBudgetId, String direction) {
        if (!source?.sourceBudgetId || !source.type || !targetBudgetId || !direction) {
            throw new IllegalArgumentException('Reconciliation create must have stable source and target identity')
        }
        hashImportIdentity([
            source.sourceBudgetId, targetBudgetId, source.type.databaseValue,
            source.parentTransactionId ?: '', source.parentSubtransactionId ?: '',
            source.moneyMovementId ?: '',
            source.type == ynabbankofdad.sync.state.SourceEntityType.MONEY_MOVEMENT ? direction : ''
        ])
    }

    private static String hashImportIdentity(List<String> identityParts) {
        String stableIdentity = identityParts.join('\u001f')
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(stableIdentity.getBytes(StandardCharsets.UTF_8))
        String hash = digest.encodeHex().toString()
        "PCBS:${hash.substring(0, 31)}"
    }

    private static String movementDirection(ChildTransactionPlan plan) {
        if (plan.eventType != 'money_movement') {
            return ''
        }
        List<String> keyParts = (plan.idempotencyKey ?: '').split('\\|', -1) as List
        keyParts.size() > 8 ? keyParts[8] : ''
    }

    String extractCreatedTransactionId(def response) {
        def ids = response?.data?.bulk?.transaction_ids
        if (ids instanceof List && !ids.isEmpty()) {
            return ids.first() as String
        }
        def transactions = response?.data?.transactions
        if (transactions instanceof List && !transactions.isEmpty()) {
            return transactions.first().id as String
        }
        null
    }
}

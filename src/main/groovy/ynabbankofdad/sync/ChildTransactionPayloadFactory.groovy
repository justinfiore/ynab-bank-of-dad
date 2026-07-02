package ynabbankofdad.sync

import ynabbankofdad.sync.model.ChildTransactionPlan

class ChildTransactionPayloadFactory {
    Map<String, Object> buildTransaction(ChildTransactionPlan plan, String accountId) {
        [
            account_id : accountId,
            date       : plan.date,
            amount     : plan.amount,
            payee_name : plan.payeeName,
            category_id: null,
            memo       : plan.memo,
            approved   : plan.approved,
            import_id  : buildImportId(plan)
        ]
    }

    String buildImportId(ChildTransactionPlan plan) {
        String compact = plan.idempotencyKey.replaceAll(/[^A-Za-z0-9]/, '').takeRight(28)
        String datePart = (plan.date ?: '1970-01-01').replace('-', '')
        long amountAbs = Math.abs((plan.amount ?: 0) as long)
        "PCBS:${datePart}:${amountAbs}:${compact}"
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

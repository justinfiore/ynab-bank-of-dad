package ynabbankofdad.sync

import ynabbankofdad.sync.state.SourceEntityKey
import ynabbankofdad.sync.state.SourceEntityType

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ChildTransactionPayloadFactory {
    String buildImportId(SourceEntityKey source, String targetBudgetId, String direction) {
        if (!source?.sourceBudgetId || !source.type || !targetBudgetId || !direction) {
            throw new IllegalArgumentException('Reconciliation create must have stable source and target identity')
        }
        hashImportIdentity([
            source.sourceBudgetId, targetBudgetId, source.type.databaseValue,
            source.parentTransactionId ?: '', source.parentSubtransactionId ?: '',
            source.moneyMovementId ?: '',
            source.type == SourceEntityType.MONEY_MOVEMENT ? direction : ''
        ])
    }

    private static String hashImportIdentity(List<String> identityParts) {
        String stableIdentity = identityParts.join('\u001f')
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(stableIdentity.getBytes(StandardCharsets.UTF_8))
        String hash = digest.encodeHex().toString()
        "PCBS:${hash.substring(0, 31)}"
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

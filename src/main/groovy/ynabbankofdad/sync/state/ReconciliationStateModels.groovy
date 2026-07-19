package ynabbankofdad.sync.state

import groovy.transform.Immutable

enum SourceEntityType {
    TRANSACTION('transaction'),
    SUBTRANSACTION('subtransaction'),
    MONEY_MOVEMENT('money_movement')

    final String databaseValue

    SourceEntityType(String databaseValue) {
        this.databaseValue = databaseValue
    }
}

enum ReconciliationOperationType {
    CREATE('create'), UPDATE('update'), DELETE('delete')

    final String databaseValue

    ReconciliationOperationType(String databaseValue) {
        this.databaseValue = databaseValue
    }
}

@Immutable
class SourceEntityKey {
    String sourceBudgetId
    SourceEntityType type
    String parentTransactionId
    String parentSubtransactionId
    String moneyMovementId
}

@Immutable
class ChildMirrorState {
    long id
    long sourceEntityId
    String targetBudgetId
    String direction
    String childTransactionId
    String targetAccountId
    String authoritativePayloadHash
    String status
    String createdAt
    String endedAt
}

@Immutable
class ReconciliationOperationIntent {
    String operationKey
    Long ingestionBatchId
    long sourceEntityId
    Long childMirrorId
    int operationSequence
    ReconciliationOperationType operationType
    String targetBudgetId
    String childTransactionId
    String payloadJson
    String payloadHash
    Long dependsOnOperationId
}

@Immutable
class ReconciliationOperation {
    long id
    ReconciliationOperationIntent intent
    String status
    String createdAt
    String completedAt
}

@Immutable
class ReconciliationOperationAttempt {
    long id
    long operationId
    String outcome
    String failureReason
    String returnedChildTransactionId
    String attemptedAt
}

@Immutable
class LegacyMirrorProjection {
    SourceEntityKey source
    String targetBudgetId
    String direction
    String childTransactionId
    boolean active
}

@Immutable
class MigrationProjection {
    List<LegacyMirrorProjection> mirrors
    List<ReconciliationOperationIntent> cleanupOperations
}

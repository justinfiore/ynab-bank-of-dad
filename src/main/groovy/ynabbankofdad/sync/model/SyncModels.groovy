package ynabbankofdad.sync.model

import groovy.transform.Immutable
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.ynab.YnabBudgetRepository

class ChildSyncContext {
    final ChildBudgetSyncTarget target
    final YnabBudgetRepository repository
    String budgetId
    Map<String, String> accountIdsByName = [:]

    ChildSyncContext(ChildBudgetSyncTarget target, YnabBudgetRepository repository, String budgetId = null, String accountId = null) {
        this.target = target
        this.repository = repository
        this.budgetId = budgetId
        if (accountId) {
            target?.accountMappings?.collect { it.childAccountName }?.unique()?.each { String accountName ->
                this.accountIdsByName[accountName] = accountId
            }
        }
    }

    String resolveAccountId(String accountName) {
        accountIdsByName[accountName]
    }

    void cacheAccountId(String accountName, String accountId) {
        accountIdsByName[accountName] = accountId
    }
}

@Immutable
class ChildTransactionPlan {
    String sourceBudgetId
    String targetChildKey
    String targetBudgetName
    String mappingKey
    String parentCategoryName
    String eventType
    String parentTransactionId
    String parentSubtransactionId
    String moneyMovementId
    String moneyMovementGroupId
    String idempotencyKey
    String childAccountName
    String date
    Integer amount
    String memo
    String payeeName
    Boolean approved

    Map<String, Object> toSummaryMap() {
        [
            sourceBudgetId        : sourceBudgetId,
            targetChildKey        : targetChildKey,
            targetBudgetName      : targetBudgetName,
            mappingKey            : mappingKey,
            parentCategoryName    : parentCategoryName,
            eventType             : eventType,
            parentTransactionId   : parentTransactionId,
            parentSubtransactionId: parentSubtransactionId,
            moneyMovementId       : moneyMovementId,
            moneyMovementGroupId  : moneyMovementGroupId,
            childAccountName      : childAccountName,
            date                  : date,
            amount                : amount,
            memo                  : memo,
            payeeName             : payeeName,
            approved              : approved
        ]
    }
}

@Immutable
class ParentTransactionEvent {
    String id
    String date
    Integer amount
    String memo
    Boolean approved
    Integer serverKnowledge
    String categoryId
    String categoryName
    List<ParentSubtransactionEvent> subtransactions = []
}

@Immutable
class ParentSubtransactionEvent {
    String id
    String transactionId
    Integer amount
    String memo
    String categoryId
    String categoryName
}

@Immutable
class MoneyMovementEvent {
    String id
    String groupId
    String eventDate
    String fromCategoryId
    String toCategoryId
    Integer amount
}

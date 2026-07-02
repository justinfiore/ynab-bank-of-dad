import groovy.transform.Immutable

class ChildSyncContext {
    final ChildBudgetSyncTarget target
    final YnabBudgetRepository repository
    String budgetId
    String accountId

    ChildSyncContext(ChildBudgetSyncTarget target, YnabBudgetRepository repository, String budgetId = null, String accountId = null) {
        this.target = target
        this.repository = repository
        this.budgetId = budgetId
        this.accountId = accountId
    }
}

@Immutable
class ChildTransactionPlan {
    String sourceBudgetId
    String targetChildKey
    String targetBudgetName
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

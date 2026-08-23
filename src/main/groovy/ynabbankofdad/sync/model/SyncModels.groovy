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
    String payeeId
    String payeeName
    Boolean deleted
}

@Immutable
class ParentSubtransactionEvent {
    String id
    String transactionId
    Integer amount
    String memo
    String categoryId
    String categoryName
    Boolean deleted
    String payeeId
    String payeeName
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

@Immutable
class TransactionDelta {
    List<ParentTransactionEvent> transactions = []
    Integer serverKnowledge
}

@Immutable
class MoneyMovementSnapshot {
    List<MoneyMovementEvent> movements = []
    Integer serverKnowledge
}

@Immutable
class ChildTransaction {
    String id
    String accountId
    String date
    Integer amount
    String payeeId
    String payeeName
    String categoryId
    String memo
    String cleared
    Boolean approved
    String flagColor
    Boolean deleted
}

@Immutable
class ChildTransactionResult {
    ChildTransaction transaction
    Integer serverKnowledge
}

@Immutable
class ChildTransactionLookupResult {
    ChildTransaction transaction
    Integer serverKnowledge

    boolean found() {
        transaction != null
    }
}

@Immutable
class ChildTransactionDeleteResult {
    ChildTransaction transaction
    Integer serverKnowledge
    Boolean alreadyAbsent
}

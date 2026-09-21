package ynabbankofdad.sync.model

import groovy.transform.Immutable
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.model.AccountSnapshot
import ynabbankofdad.ynab.YnabBudgetRepository

class ChildSyncContext {
    final ChildBudgetSyncTarget target
    final YnabBudgetRepository repository
    String budgetId
    Map<String, String> accountIdsByName = [:]
    Map<String, AccountSnapshot> accountSnapshotsByName = [:]

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
        AccountSnapshot existing = accountSnapshotsByName[accountName]
        if (existing == null || existing.id != accountId) {
            accountSnapshotsByName[accountName] = new AccountSnapshot(
                accountId, accountName, existing?.balance ?: 0,
                existing?.transferPayeeId, existing?.onBudget)
        }
    }

    void cacheAccountSnapshot(AccountSnapshot snapshot) {
        if (!snapshot?.name) {
            return
        }
        accountSnapshotsByName[snapshot.name] = snapshot
        if (snapshot.id) {
            accountIdsByName[snapshot.name] = snapshot.id
        }
    }

    AccountSnapshot resolveAccountSnapshot(String accountName) {
        accountSnapshotsByName[accountName]
    }

    AccountSnapshot resolveAccountSnapshotById(String accountId) {
        if (!accountId) {
            return null
        }
        accountSnapshotsByName.values().find { it.id == accountId }
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

/**
 * One child-budget transaction list read. {@code transactionsById} is the full set returned by
 * YNAB for the request (since_date lookback or last_knowledge_of_server delta). Child mirrors are
 * flat transactions, so this map is enough for existence checks without per-id GETs.
 */
@Immutable
class ChildTransactionListResult {
    Map<String, ChildTransaction> transactionsById = [:]
    Integer serverKnowledge
}

@Immutable
class ChildTransactionDeleteResult {
    ChildTransaction transaction
    Integer serverKnowledge
    Boolean alreadyAbsent
}

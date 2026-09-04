package ynabbankofdad.model

import groovy.transform.Immutable
import groovy.transform.Immutable

@Immutable(knownImmutableClasses = [Date])
class TransactionDraft {
    String accountId
    String date
    Integer amount
    String payeeName
    String categoryId
    String memo
    Boolean approved = true

    Map<String, Object> toYnabTransaction() {
        [
            account_id : accountId,
            date       : date,
            amount     : amount,
            payee_name : payeeName,
            category_id: categoryId,
            memo       : memo,
            approved   : approved
        ]
    }
}

@Immutable
class CategorySnapshot {
    String id
    String name
    Integer balance = 0
}

@Immutable
class AccountSnapshot {
    String id
    String name
    Integer balance = 0
}

@Immutable(knownImmutableClasses = [Date])
class BudgetSummary {
    String id
    String name
    Date lastModifiedOn
}

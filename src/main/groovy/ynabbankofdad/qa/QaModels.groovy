package ynabbankofdad.qa

import groovy.transform.Immutable

@Immutable
class QaBudgetIdentity {
    String displayName
    String fullId
}

@Immutable
class QaExpectedMutation {
    String operation
    QaBudgetIdentity budget
    String targetResourceId
}

@Immutable
class QaProvisioningRequirements {
    QaBudgetIdentity parentBudget
    List<QaBudgetIdentity> childBudgets
    Set<String> parentCategoryNames
    Map<QaBudgetIdentity, Set<String>> childAccountNames
}

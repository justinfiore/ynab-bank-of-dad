package ynabbankofdad.sync.reconcile

import groovy.transform.Immutable

@Immutable
class ParentCategoryAccountMapping {
    String parentCategoryId
    String parentCategoryName
    String childKey
    String accountId
    String accountName
}

class ParentCategoryAccountCache {
    private final List<ParentCategoryAccountMapping> mappings = []

    void record(String parentCategoryId, String parentCategoryName, String childKey,
                String accountId, String accountName) {
        if (!parentCategoryName || !childKey || !accountName) {
            return
        }
        boolean exists = mappings.any {
            it.parentCategoryName == parentCategoryName &&
                it.childKey == childKey &&
                it.accountId == accountId &&
                it.accountName == accountName
        }
        if (!exists) {
            mappings << new ParentCategoryAccountMapping(
                parentCategoryId, parentCategoryName, childKey, accountId, accountName)
        }
    }

    List<ParentCategoryAccountMapping> mappings() {
        mappings.asImmutable()
    }
}

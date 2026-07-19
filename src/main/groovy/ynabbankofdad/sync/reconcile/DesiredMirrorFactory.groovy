package ynabbankofdad.sync.reconcile

import ynabbankofdad.config.ChildAccountMapping
import ynabbankofdad.config.ParentCategoryNameMatcher
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.state.SourceEntityKey

class DesiredMirrorFactory {
    static final String LOGICAL_DIRECTION_FIELD = '_reconciliation_direction'
    private final List<ChildSyncContext> childContexts
    private final Map<String, CategorySnapshot> categoriesById

    DesiredMirrorFactory(List<ChildSyncContext> childContexts, Map<String, CategorySnapshot> categoriesById = [:]) {
        this.childContexts = childContexts ?: []
        this.categoriesById = categoriesById ?: [:]
    }

    List<DesiredMirror> forSource(SourceEntityKey source, String categoryId, String categoryName,
                                  String date, Integer amount, String payeeId, String payeeName,
                                  String memo, String direction = null) {
        String resolvedName = categoryName ?: categoriesById[categoryId]?.name
        if (!categoryId || !resolvedName) {
            return []
        }
        childContexts.findResults { ChildSyncContext child ->
            ChildAccountMapping mapping = resolveMapping(child, resolvedName)
            if (!mapping) {
                return null
            }
            String accountId = child.resolveAccountId(mapping.childAccountName)
            if (!child.budgetId || !accountId) {
                throw new IllegalStateException("Child '${child.target.childKey}' routing is not resolved")
            }
            Map payload = [account_id: accountId, date: date, amount: amount,
                           payee_id: payeeId, payee_name: payeeName,
                           cleared: 'cleared', approved: false]
            String payloadJson = ReconciliationCanonicalizer.json(payload)
            String decoratedMemo = ((child.target.memoPrefix ?: '') + (memo ?: '') +
                (child.target.memoSuffix ?: '')).trim()
            new DesiredMirror(source, child.target.childKey, child.budgetId, direction, accountId,
                mapping.childAccountName, date, amount, payeeId, payeeName, decoratedMemo, mapping.mappingKey,
                payloadJson, ReconciliationCanonicalizer.hashJson(payloadJson))
        }.sort { DesiredMirror left, DesiredMirror right ->
            mirrorSortKey(left) <=> mirrorSortKey(right)
        }
    }

    private static String mirrorSortKey(DesiredMirror mirror) {
        [mirror.source.type.databaseValue, mirror.source.parentSubtransactionId ?: '',
         mirror.direction ?: '', mirror.targetBudgetId].join('|')
    }

    private static ChildAccountMapping resolveMapping(ChildSyncContext child, String categoryName) {
        ChildAccountMapping literal = child.target.accountMappings.find { ChildAccountMapping mapping ->
            mapping.parentCategoryNames.any { ParentCategoryNameMatcher matcher ->
                !matcher.regex && matcher.name == categoryName
            }
        }
        literal ?: child.target.accountMappings.find { ChildAccountMapping mapping ->
            mapping.parentCategoryNames.any { ParentCategoryNameMatcher matcher ->
                matcher.regex && categoryName ==~ matcher.name
            }
        }
    }
}

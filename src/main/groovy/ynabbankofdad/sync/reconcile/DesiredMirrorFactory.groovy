package ynabbankofdad.sync.reconcile

import ynabbankofdad.config.ChildAccountMapping
import ynabbankofdad.config.ParentCategoryNameMatcher
import ynabbankofdad.model.AccountSnapshot
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.state.SourceEntityKey

class DesiredMirrorFactory {
    static final String LOGICAL_DIRECTION_FIELD = '_reconciliation_direction'
    static final String IMPORT_ID_NAMESPACE_FIELD = '_import_id_namespace'
    /**
     * Internal: destination child account for a same-budget transfer create. Stripped before the
     * YNAB request; used so cycle balance reporting can net both legs of one POST.
     */
    static final String TRANSFER_DESTINATION_ACCOUNT_FIELD = '_transfer_destination_account_id'

    private final List<ChildSyncContext> childContexts
    private final Map<String, CategorySnapshot> categoriesById
    private final ParentCategoryAccountCache mappingCache

    DesiredMirrorFactory(List<ChildSyncContext> childContexts, Map<String, CategorySnapshot> categoriesById = [:],
                         ParentCategoryAccountCache mappingCache = null) {
        this.childContexts = childContexts ?: []
        this.categoriesById = categoriesById ?: [:]
        this.mappingCache = mappingCache
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
            String derivedName = child.target.derivedAccountName(resolvedName)
            String accountName = child.resolveAccountId(mapping.childAccountName) ?
                mapping.childAccountName : derivedName
            String accountId = child.resolveAccountId(mapping.childAccountName) ?:
                (derivedName ? child.resolveAccountId(derivedName) : null)
            mappingCache?.record(categoryId, resolvedName, child.target.childKey, accountId, accountName)
            if (!child.budgetId || !accountId) {
                if (child.target.autoCreateAccounts) {
                    return null
                }
                throw new IllegalStateException("Child '${child.target.childKey}' routing is not resolved")
            }
            // Payee IDs are budget-scoped and cannot be copied from the parent
            // plan into a child plan. Send only the display name so YNAB resolves
            // the payee in the child budget.
            Map payload = [account_id: accountId, date: date, amount: amount,
                           payee_id: null, payee_name: payeeName,
                           cleared: 'cleared', approved: false]
            String payloadJson = ReconciliationCanonicalizer.json(payload)
            String decoratedMemo = decorateMemo(child, memo)
            new DesiredMirror(source, child.target.childKey, child.budgetId, direction, accountId,
                accountName, date, amount, null, payeeName, decoratedMemo, mapping.mappingKey,
                payloadJson, ReconciliationCanonicalizer.hashJson(payloadJson), child.target.importIdNamespace)
        }.sort { DesiredMirror left, DesiredMirror right ->
            mirrorSortKey(left) <=> mirrorSortKey(right)
        }
    }

    /**
     * One outflow-side child transfer. YNAB creates the linked inflow when {@code payee_id} is the
     * destination account's {@code transfer_payee_id}. Category is left unset: on-budget↔on-budget
     * needs none; YNAB assigns Ready to Assign when cash leaves/enters the plan via tracking.
     */
    DesiredMirror forAccountTransfer(SourceEntityKey source, ChildSyncContext child,
                                     String fromAccountId, String fromAccountName,
                                     String toAccountId, String toAccountName,
                                     String transferPayeeId, String date, Integer outflowAmount,
                                     String memo, String mappingKey) {
        if (!child?.budgetId || !fromAccountId || !toAccountId || !transferPayeeId || outflowAmount == null) {
            return null
        }
        Map payload = [
            account_id                              : fromAccountId,
            date                                    : date,
            amount                                  : outflowAmount,
            payee_id                                : transferPayeeId,
            payee_name                              : null,
            cleared                                 : 'cleared',
            approved                                : false,
            (TRANSFER_DESTINATION_ACCOUNT_FIELD)    : toAccountId
        ]
        String payloadJson = ReconciliationCanonicalizer.json(payload)
        String decoratedMemo = decorateMemo(child, memo)
        new DesiredMirror(source, child.target.childKey, child.budgetId, 'outflow', fromAccountId,
            fromAccountName, date, outflowAmount, transferPayeeId, null, decoratedMemo, mappingKey,
            payloadJson, ReconciliationCanonicalizer.hashJson(payloadJson), child.target.importIdNamespace)
    }

    ChildSyncContext childContextForBudget(String targetBudgetId) {
        childContexts.find { it.budgetId == targetBudgetId }
    }

    AccountSnapshot accountSnapshot(ChildSyncContext child, String accountId) {
        child?.resolveAccountSnapshotById(accountId)
    }

    private static String decorateMemo(ChildSyncContext child, String memo) {
        ((child.target.memoPrefix ?: '') + (memo ?: '') + (child.target.memoSuffix ?: '')).trim()
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

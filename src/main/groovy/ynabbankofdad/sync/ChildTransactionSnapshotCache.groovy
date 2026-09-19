package ynabbankofdad.sync

import groovy.util.logging.Slf4j
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.ChildTransaction
import ynabbankofdad.sync.model.ChildTransactionListResult
import ynabbankofdad.sync.model.ChildTransactionLookupResult
import ynabbankofdad.sync.state.SyncStateRepository

/**
 * Per-cycle child transaction views loaded with one list GET per child budget.
 * Never issues per-id GETs: child mirrors are flat transactions and do not need split detail.
 *
 * <ul>
 *   <li>{@code forceLookback} or no cursor → {@code since_date} full lookback window.
 *       Id absent from the list means absent (same as 404).</li>
 *   <li>Otherwise → per-budget {@code last_knowledge_of_server} delta. Id present (including
 *       {@code deleted:true}) uses the delta row. Id absent from the delta means unchanged since
 *       the cursor: treat as still present with unknown fields so apply will PUT desired state
 *       rather than recreate.</li>
 * </ul>
 * Cursor keys are {@code child.transactions.last_server_knowledge.<budgetId>} and must never be
 * shared across budgets.
 */
@Slf4j
class ChildTransactionSnapshotCache {
    /**
     * Marker account_id for "still present since cursor, fields not in this delta".
     * {@link ynabbankofdad.sync.ReconciliationOperationApplier} treats this as already matching
     * so existence-check updates do not PUT.
     */
    static final String UNCHANGED_SINCE_CURSOR_MARKER = '__ybod_unchanged_since_cursor__'

    private final Map<String, Map<String, ChildTransaction>> byBudgetId = [:]
    private final Map<String, Integer> serverKnowledgeByBudgetId = [:]
    private final Set<String> deltaModeBudgetIds = [] as Set
    private final Set<String> loadedBudgetIds = [] as Set

    void load(List<ChildSyncContext> children, SyncStateRepository stateStore,
              boolean forceLookback, int transactionLookbackDays) {
        (children ?: []).findAll { it?.budgetId && it.repository }.each { ChildSyncContext child ->
            if (loadedBudgetIds.contains(child.budgetId)) {
                return
            }
            String cursorKey = SyncRunCoordinator.childTransactionCursorKey(child.budgetId)
            Integer storedCursor = stateStore?.getCursor(cursorKey)
            Integer requestCursor = forceLookback ? null : storedCursor
            boolean deltaMode = requestCursor != null
            if (forceLookback) {
                log.info(
                    "Child transaction list for budget '{}' ({}) using since_date lookback of {} days instead of last_knowledge_of_server={}",
                    child.budgetId, child.target?.childKey, transactionLookbackDays, storedCursor)
            } else if (deltaMode) {
                log.info(
                    "Child transaction list for budget '{}' ({}) using last_knowledge_of_server={}",
                    child.budgetId, child.target?.childKey, requestCursor)
            } else {
                log.info(
                    "Child transaction list for budget '{}' ({}) using since_date lookback of {} days (no stored cursor)",
                    child.budgetId, child.target?.childKey, transactionLookbackDays)
            }
            ChildTransactionListResult listed = child.repository.getChildTransactions(
                child.budgetId, transactionLookbackDays, requestCursor)
            Map<String, ChildTransaction> snapshot = new LinkedHashMap<>(listed.transactionsById ?: [:])
            byBudgetId[child.budgetId] = snapshot
            serverKnowledgeByBudgetId[child.budgetId] = listed.serverKnowledge
            if (deltaMode) {
                deltaModeBudgetIds << child.budgetId
            }
            loadedBudgetIds << child.budgetId
            log.info(
                "Cached {} child transactions for budget '{}' ({}) mode={} server_knowledge={}",
                snapshot.size(), child.budgetId, child.target?.childKey,
                deltaMode ? 'delta' : 'lookback', listed.serverKnowledge)
        }
    }

    ChildTransactionLookupResult lookup(String budgetId, String transactionId) {
        ensureLoaded(budgetId)
        Map<String, ChildTransaction> snapshot = byBudgetId[budgetId] ?: [:]
        Integer knowledge = serverKnowledgeByBudgetId[budgetId]
        if (snapshot.containsKey(transactionId)) {
            return new ChildTransactionLookupResult(snapshot[transactionId], knowledge)
        }
        if (deltaModeBudgetIds.contains(budgetId)) {
            // Unchanged since cursor: still present. Marker tells applyUpdate to treat fields as
            // already matching (no PUT, no recreate). Real field changes arrive in the delta.
            return new ChildTransactionLookupResult(
                unchangedSinceCursor(transactionId), knowledge)
        }
        new ChildTransactionLookupResult(null, knowledge)
    }

    static ChildTransaction unchangedSinceCursor(String transactionId) {
        new ChildTransaction(
            transactionId, UNCHANGED_SINCE_CURSOR_MARKER, null, null, null, null, null, null,
            null, null, null, false)
    }

    static boolean isUnchangedSinceCursor(ChildTransaction transaction) {
        transaction?.accountId == UNCHANGED_SINCE_CURSOR_MARKER
    }

    void put(String budgetId, ChildTransaction transaction) {
        if (!budgetId || !transaction?.id) {
            return
        }
        ensureLoaded(budgetId)
        Map<String, ChildTransaction> snapshot = byBudgetId[budgetId]
        if (snapshot == null) {
            snapshot = [:]
            byBudgetId[budgetId] = snapshot
        }
        snapshot[transaction.id] = transaction
    }

    void remove(String budgetId, String transactionId) {
        if (!budgetId || !transactionId) {
            return
        }
        Map<String, ChildTransaction> snapshot = byBudgetId[budgetId]
        if (snapshot == null) {
            return
        }
        // Tombstone so a later lookup in delta mode does not treat the id as "unchanged present".
        snapshot[transactionId] = new ChildTransaction(
            transactionId, null, null, null, null, null, null, null, null, null, null, true)
    }

    void persistCursors(SyncStateRepository stateStore) {
        if (stateStore == null) {
            return
        }
        serverKnowledgeByBudgetId.each { String budgetId, Integer knowledge ->
            if (knowledge != null) {
                stateStore.setCursor(SyncRunCoordinator.childTransactionCursorKey(budgetId), knowledge)
            }
        }
    }

    private void ensureLoaded(String budgetId) {
        if (!budgetId) {
            throw new IllegalArgumentException('Child budget id is required for transaction lookup')
        }
        if (!loadedBudgetIds.contains(budgetId)) {
            loadedBudgetIds << budgetId
            byBudgetId.putIfAbsent(budgetId, [:])
        }
    }
}

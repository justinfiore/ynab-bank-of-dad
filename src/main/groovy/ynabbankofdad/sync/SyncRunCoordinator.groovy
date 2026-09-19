package ynabbankofdad.sync

import ynabbankofdad.sync.model.SyncRunResult
import ynabbankofdad.sync.state.SyncStateRepository

class SyncRunCoordinator {
    static final String TRANSACTION_CURSOR_KEY = 'transactions.last_server_knowledge'
    /** Prefix for per-child-budget transaction list cursors. Never share across budgets. */
    static final String CHILD_TRANSACTION_CURSOR_KEY_PREFIX = 'child.transactions.last_server_knowledge.'

    private final SyncStateRepository stateStore
    private final boolean dryRun

    SyncRunCoordinator(SyncStateRepository stateStore, boolean dryRun) {
        this.stateStore = stateStore
        this.dryRun = dryRun
    }

    static String childTransactionCursorKey(String childBudgetId) {
        if (!childBudgetId?.trim()) {
            throw new IllegalArgumentException('Child budget id is required for a child transaction cursor key')
        }
        CHILD_TRANSACTION_CURSOR_KEY_PREFIX + childBudgetId.trim()
    }

    void finishRun(long runId, SyncRunResult result, Integer transactionServerKnowledge,
                   boolean transactionWorkComplete = !result.hasFailures()) {
        if (dryRun || runId <= 0) {
            return
        }
        stateStore.finishRun(runId, result.status(), result.errorSummary())
        if (transactionWorkComplete && transactionServerKnowledge != null) {
            stateStore.setCursor(TRANSACTION_CURSOR_KEY, transactionServerKnowledge)
        }
    }

    void failRun(long runId, Exception ex) {
        if (!dryRun && runId > 0) {
            stateStore.finishRun(runId, 'failed', ex.message)
        }
    }
}

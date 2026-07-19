package ynabbankofdad.sync

import ynabbankofdad.sync.model.SyncRunResult
import ynabbankofdad.sync.state.SyncStateRepository

class SyncRunCoordinator {
    static final String TRANSACTION_CURSOR_KEY = 'transactions.last_server_knowledge'

    private final SyncStateRepository stateStore
    private final boolean dryRun

    SyncRunCoordinator(SyncStateRepository stateStore, boolean dryRun) {
        this.stateStore = stateStore
        this.dryRun = dryRun
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

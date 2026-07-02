package ynabbankofdad.sync

import ynabbankofdad.sync.model.SyncRunResult
import ynabbankofdad.sync.state.SyncStateRepository
import ynabbankofdad.ynab.YnabBudgetRepository

class SyncRunCoordinator {
    static final String TRANSACTION_CURSOR_KEY = 'transactions.last_server_knowledge'

    private final SyncStateRepository stateStore
    private final YnabBudgetRepository parentRepository
    private final boolean dryRun

    SyncRunCoordinator(SyncStateRepository stateStore, YnabBudgetRepository parentRepository, boolean dryRun) {
        this.stateStore = stateStore
        this.parentRepository = parentRepository
        this.dryRun = dryRun
    }

    void finishRun(long runId, SyncRunResult result, List transactions) {
        if (dryRun || runId <= 0) {
            return
        }
        stateStore.finishRun(runId, result.status(), result.errorSummary())
        if (!result.hasFailures()) {
            Integer latestServerKnowledge = parentRepository.latestServerKnowledge(transactions)
            if (latestServerKnowledge != null) {
                stateStore.setCursor(TRANSACTION_CURSOR_KEY, latestServerKnowledge)
            }
        }
    }

    void failRun(long runId, Exception ex) {
        if (!dryRun && runId > 0) {
            stateStore.finishRun(runId, 'failed', ex.message)
        }
    }
}

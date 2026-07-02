import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import spock.lang.Specification

class SyncRunCoordinatorSpec extends Specification {
    def "successful run advances cursor to latest server knowledge"() {
        given:
        def state = new InMemorySyncStateRepository()
        def parent = new FakeYnabBudgetRepository()
        def coordinator = new SyncRunCoordinator(state, parent, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(1, 0, 0, []), [
            new ParentTransactionEvent('txn-1', '2026-07-01', -100, 'memo', true, 10, 'cat', 'Category', []),
            new ParentTransactionEvent('txn-2', '2026-07-01', -100, 'memo', true, 12, 'cat', 'Category', [])
        ])

        then:
        state.finished == [[runId: 7L, status: 'succeeded', errorSummary: null]]
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
    }

    def "partial run does not advance cursor"() {
        given:
        def state = new InMemorySyncStateRepository()
        def parent = new FakeYnabBudgetRepository()
        def coordinator = new SyncRunCoordinator(state, parent, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(0, 0, 1, ['child-one: failed']), [
            new ParentTransactionEvent('txn-1', '2026-07-01', -100, 'memo', true, 12, 'cat', 'Category', [])
        ])

        then:
        state.finished == [[runId: 7L, status: 'partial', errorSummary: 'child-one: failed']]
        !state.cursors.containsKey(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)
    }
}

class InMemorySyncStateRepository implements SyncStateRepository {
    Map<String, Integer> cursors = [:]
    List<Map> finished = []

    void initialize() {}
    long startRun(boolean dryRun, int pollingIntervalSeconds, String sourceBudgetId) { 1L }
    void finishRun(long runId, String status, String errorSummary) { finished << [runId: runId, status: status, errorSummary: errorSummary] }
    long recordSourceEvent(ChildTransactionPlan plan) { 1L }
    long recordMapping(long sourceEventId, ChildTransactionPlan plan, String targetBudgetId, String accountId) { 1L }
    void recordAppliedTransaction(long mappingId, long runId, String targetBudgetId, String createdChildTransactionId, String status, String failureReason, boolean dryRun) {}
    boolean hasAppliedIdempotencyKey(String idempotencyKey) { false }
    Integer getCursor(String key) { cursors[key] }
    void setCursor(String key, Integer value) { cursors[key] = value }
}

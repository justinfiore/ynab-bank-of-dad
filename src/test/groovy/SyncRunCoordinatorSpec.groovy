import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import spock.lang.Specification

class SyncRunCoordinatorSpec extends Specification {
    def "successful run advances cursor to latest server knowledge"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(1, 0, 0, []), 12)

        then:
        state.finished == [[runId: 7L, status: 'succeeded', errorSummary: null]]
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
    }

    def "partial run does not advance cursor"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(0, 0, 1, ['child-one: failed']), 12)

        then:
        state.finished == [[runId: 7L, status: 'partial', errorSummary: 'child-one: failed']]
        !state.cursors.containsKey(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)
    }

    def "failed update blocks transaction cursor until ingestion recovery"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(0, 0, 1, ['child update failed']), 12, false)

        then:
        state.finished*.status == ['partial']
        !state.cursors.containsKey(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)

        when:
        coordinator.finishRun(8L, new SyncRunResult(1, 0, 0, []), 12, true)

        then:
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
    }

    def "movement failure permits cursor when transaction batch completed"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(1, 0, 1, ['movement failed']), 12, true)

        then:
        state.finished == [[runId: 7L, status: 'partial', errorSummary: 'movement failed']]
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
    }

    def "independent migration cleanup cannot block completed transaction cursor"() {
        given:
        def state = new InMemorySyncStateRepository()

        when:
        new SyncRunCoordinator(state, false).finishRun(9L,
            new SyncRunResult(0, 0, 1, ['legacy cleanup remains retryable']), 44, true)

        then:
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 44
        state.finished*.status == ['partial']
    }

    def "failed run is finalized with the exception message"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.failRun(9L, new IllegalStateException('parent read failed'))

        then:
        state.finished == [[runId: 9L, status: 'failed', errorSummary: 'parent read failed']]
    }

    def "dry run and invalid run ids never persist completion or cursors"() {
        given:
        def state = new InMemorySyncStateRepository()
        when:
        new SyncRunCoordinator(state, true)
            .finishRun(7L, SyncRunResult.empty(), 12)
        new SyncRunCoordinator(state, false)
            .finishRun(0L, SyncRunResult.empty(), 12)

        then:
        state.finished.empty
        state.cursors.isEmpty()
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

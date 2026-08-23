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
        coordinator.finishRun(7L, SyncRunResult.empty(), 12)

        then:
        state.finished == [[runId: 7L, status: 'succeeded', errorSummary: null]]
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
    }

    def "partial run does not advance cursor"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(['child-one: failed']), 12)

        then:
        state.finished == [[runId: 7L, status: 'partial', errorSummary: 'child-one: failed']]
        !state.cursors.containsKey(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)
    }

    def "failed update blocks transaction cursor until ingestion recovery"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(['child update failed']), 12, false)

        then:
        state.finished*.status == ['partial']
        !state.cursors.containsKey(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)

        when:
        coordinator.finishRun(8L, SyncRunResult.empty(), 12, true)

        then:
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
    }

    def "cursor advances only when caller reports all transaction batches complete"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when: 'a newer response is fully applied but an older batch is still incomplete'
        coordinator.finishRun(7L, new SyncRunResult(['older transaction batch unfinished']), 200, false)

        then:
        !state.cursors.containsKey(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)

        when: 'every transaction_delta batch is complete'
        coordinator.finishRun(8L, SyncRunResult.empty(), 200, true)

        then:
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 200
    }

    def "movement failure permits cursor when transaction batch completed"() {
        given:
        def state = new InMemorySyncStateRepository()
        def coordinator = new SyncRunCoordinator(state, false)

        when:
        coordinator.finishRun(7L, new SyncRunResult(['movement failed']), 12, true)

        then:
        state.finished == [[runId: 7L, status: 'partial', errorSummary: 'movement failed']]
        state.cursors[SyncRunCoordinator.TRANSACTION_CURSOR_KEY] == 12
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
    long startRun(int pollingIntervalSeconds, String sourceBudgetId) { 1L }
    void finishRun(long runId, String status, String errorSummary) { finished << [runId: runId, status: status, errorSummary: errorSummary] }
    Integer getCursor(String key) { cursors[key] }
    void setCursor(String key, Integer value) { cursors[key] = value }
}

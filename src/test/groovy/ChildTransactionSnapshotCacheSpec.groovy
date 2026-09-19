import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.sync.ChildTransactionSnapshotCache
import ynabbankofdad.sync.SyncRunCoordinator
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.SyncStateStore
import ynabbankofdad.ynab.YnabBudgetRepository

import java.nio.file.Path

class ChildTransactionSnapshotCacheSpec extends Specification {

    @TempDir
    Path tempDir

    SyncStateStore store
    RecordingChildRepository repository

    def setup() {
        store = new SyncStateStore(tempDir.resolve('state.db').toString())
        store.initialize()
        repository = new RecordingChildRepository()
    }

    def "force lookback uses since_date and treats missing ids as absent without per-id GET"() {
        given:
        repository.listResponses << new ChildTransactionListResult([
            'present': txn('present', 'acct', 100)
        ], 50)
        def cache = new ChildTransactionSnapshotCache()

        when:
        cache.load([context('child-budget')], store, true, 45)

        then:
        repository.listCalls.size() == 1
        repository.listCalls[0].lastServerKnowledge == null
        repository.listCalls[0].lookbackDays == 45
        repository.perIdCalls == 0
        cache.lookup('child-budget', 'present').found()
        !cache.lookup('child-budget', 'missing').found()
    }

    def "incremental mode uses per-budget cursor and does not share cursors across budgets"() {
        given:
        store.setCursor(SyncRunCoordinator.childTransactionCursorKey('child-a'), 10)
        store.setCursor(SyncRunCoordinator.childTransactionCursorKey('child-b'), 20)
        def repoA = new RecordingChildRepository()
        def repoB = new RecordingChildRepository()
        repoA.listResponses << new ChildTransactionListResult([:], 11)
        repoB.listResponses << new ChildTransactionListResult([:], 21)
        def cache = new ChildTransactionSnapshotCache()

        when:
        cache.load([
            context('child-a', repoA),
            context('child-b', repoB)
        ], store, false, 45)
        cache.persistCursors(store)

        then:
        repoA.listCalls[0].lastServerKnowledge == 10
        repoB.listCalls[0].lastServerKnowledge == 20
        store.getCursor(SyncRunCoordinator.childTransactionCursorKey('child-a')) == 11
        store.getCursor(SyncRunCoordinator.childTransactionCursorKey('child-b')) == 21
        store.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY) == null
        repoA.perIdCalls == 0
        repoB.perIdCalls == 0
    }

    def "delta mode treats ids absent from the delta as unchanged present without PUT pressure marker mismatch"() {
        given:
        store.setCursor(SyncRunCoordinator.childTransactionCursorKey('child-budget'), 5)
        repository.listResponses << new ChildTransactionListResult([
            'changed': txn('changed', 'acct', 200)
        ], 6)
        def cache = new ChildTransactionSnapshotCache()
        cache.load([context('child-budget')], store, false, 45)

        when:
        def changed = cache.lookup('child-budget', 'changed')
        def unchanged = cache.lookup('child-budget', 'still-there')

        then:
        changed.found()
        changed.transaction.amount == 200
        unchanged.found()
        ChildTransactionSnapshotCache.isUnchangedSinceCursor(unchanged.transaction)
        repository.perIdCalls == 0
    }

    def "delta mode deleted tombstone is found and marked deleted"() {
        given:
        store.setCursor(SyncRunCoordinator.childTransactionCursorKey('child-budget'), 5)
        repository.listResponses << new ChildTransactionListResult([
            'gone': new ChildTransaction('gone', 'acct', '2026-07-01', -100, null, null, null, null,
                'cleared', false, null, true)
        ], 6)
        def cache = new ChildTransactionSnapshotCache()
        cache.load([context('child-budget')], store, false, 45)

        expect:
        cache.lookup('child-budget', 'gone').found()
        cache.lookup('child-budget', 'gone').transaction.deleted
    }

    private ChildSyncContext context(String budgetId, YnabBudgetRepository repo = repository) {
        def target = new ChildBudgetSyncTarget(childKey: budgetId, budgetName: budgetId,
            tokenEnvVarName: 'TOKEN', accountMappings: [], memoPrefix: '', memoSuffix: '')
        new ChildSyncContext(target, repo, budgetId)
    }

    private static ChildTransaction txn(String id, String accountId, int amount) {
        new ChildTransaction(id, accountId, '2026-07-01', amount, null, 'Payee', null, 'memo',
            'cleared', false, null, false)
    }

    static class RecordingChildRepository extends YnabBudgetRepository {
        List<ChildTransactionListResult> listResponses = []
        List<Map> listCalls = []
        int perIdCalls
        int listIndex

        RecordingChildRepository() { super(null) }

        @Override
        ChildTransactionListResult getChildTransactions(String budgetId, int lookbackDays,
                                                        Integer lastServerKnowledge) {
            listCalls << [budgetId: budgetId, lookbackDays: lookbackDays,
                          lastServerKnowledge: lastServerKnowledge]
            if (listIndex >= listResponses.size()) {
                throw new IllegalStateException("no list response for call ${listIndex}")
            }
            listResponses[listIndex++]
        }

        @Override
        ChildTransactionLookupResult getChildTransaction(String budgetId, String transactionId) {
            perIdCalls++
            new ChildTransactionLookupResult(null, null)
        }
    }
}

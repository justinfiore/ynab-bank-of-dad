import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.sync.model.ChildTransactionPlan
import ynabbankofdad.sync.state.SyncStateStore

import java.nio.file.Path
import java.sql.DriverManager

class LegacyMigrationSelectionSpec extends Specification {
    @TempDir
    Path tempDir

    def "migration selection excludes failed dry-run and missing-ID rows"() {
        given:
        def store = store()
        add(store, plan('eligible', 'txn'), 'child-live', 'applied', false, '2026-01-01T00:00:00Z')
        add(store, plan('failed', 'failed'), 'child-failed', 'failed', false, '2026-01-02T00:00:00Z')
        add(store, plan('dry', 'dry'), 'child-dry', 'applied', true, '2026-01-03T00:00:00Z')
        add(store, plan('missing', 'missing'), null, 'applied', false, '2026-01-04T00:00:00Z')

        when:
        def projection = store.projectLegacyMigration()

        then:
        projection.mirrors*.childTransactionId == ['child-live']
        projection.cleanupOperations.empty
    }

    def "migration selection uses newest timestamp then highest row ID and preserves movement sides"() {
        given:
        def store = store()
        add(store, plan('old', 'txn'), 'old', 'applied', false, '2026-01-01T00:00:00Z')
        add(store, plan('tie-low', 'txn'), 'tie-low', 'applied', false, '2026-02-01T00:00:00Z')
        add(store, plan('tie-high', 'txn'), 'winner', 'applied', false, '2026-02-01T00:00:00Z')
        add(store, movement('out', -100), 'movement-out', 'applied', false, '2026-01-01T00:00:00Z')
        add(store, movement('in', 100), 'movement-in', 'applied', false, '2026-01-01T00:00:00Z')

        when:
        def projection = store.projectLegacyMigration()

        then:
        projection.mirrors.findAll { it.source.parentTransactionId == 'txn' }.find { it.active }.childTransactionId == 'winner'
        projection.cleanupOperations*.childTransactionId as Set == ['tie-low', 'old'] as Set
        projection.mirrors.findAll { it.source.moneyMovementId == 'movement' && it.active }*.direction as Set ==
            ['inflow', 'outflow'] as Set
    }

    private SyncStateStore store() {
        def value = new SyncStateStore(tempDir.resolve('legacy.db').toString())
        value.initialize()
        value
    }

    private void add(SyncStateStore store, ChildTransactionPlan plan, String childId, String status,
                     boolean dryRun, String appliedAt) {
        long event = store.recordSourceEvent(plan)
        long mapping = store.recordMapping(event, plan, 'child-budget', 'account')
        long run = store.startRun(dryRun, 60, 'parent')
        store.recordAppliedTransaction(mapping, run, 'child-budget', childId, status,
            status == 'failed' ? 'failed' : null, dryRun)
        def connection = DriverManager.getConnection("jdbc:sqlite:${store.databasePath}")
        try {
            def statement = connection.prepareStatement(
                'UPDATE applied_transactions SET applied_at = ? WHERE id = (SELECT MAX(id) FROM applied_transactions)')
            statement.setString(1, appliedAt)
            statement.executeUpdate()
        } finally {
            connection.close()
        }
    }

    private static ChildTransactionPlan plan(String key, String transactionId) {
        new ChildTransactionPlan('parent', 'child', 'Child', 'mapping', 'Spend', 'transaction',
            transactionId, null, null, null, key, 'Account', '2026-01-01', -100, 'memo', null, true)
    }

    private static ChildTransactionPlan movement(String key, int amount) {
        new ChildTransactionPlan('parent', 'child', 'Child', 'mapping', 'Spend', 'money_movement',
            null, null, 'movement', 'group', key, 'Account', '2026-01-01', amount, 'memo', null, true)
    }
}

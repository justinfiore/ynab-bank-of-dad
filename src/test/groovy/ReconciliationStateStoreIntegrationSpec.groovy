import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.sync.model.ChildTransactionPlan
import ynabbankofdad.sync.state.*

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class ReconciliationStateStoreIntegrationSpec extends Specification {

    @TempDir
    Path tempDir

    def "empty and repeated initialization applies ordered schema versions"() {
        given:
        def store = new SyncStateStore(databasePath())

        when:
        store.initialize()
        store.initialize()

        then:
        scalar('SELECT MAX(version) FROM schema_versions') == SyncStateStore.CURRENT_SCHEMA_VERSION
        scalar('SELECT COUNT(*) FROM schema_versions') == 2
        tableNames().containsAll([
            'source_entities', 'source_revisions', 'child_mirrors', 'ingestion_batches',
            'sync_operations', 'operation_attempts'
        ])
    }

    def "single successful live legacy transaction becomes one active mirror"() {
        given:
        def store = legacyStore()
        addLegacyResult(store, transactionPlan('one', 'txn-1'), 'child-budget', 'child-1',
            'applied', false, '2026-01-01T00:00:00Z')
        makeUnversioned()

        when:
        store.initialize()

        then:
        scalar('SELECT COUNT(*) FROM source_entities') == 1
        rows("SELECT child_transaction_id, status FROM child_mirrors") ==
            [[child_transaction_id: 'child-1', status: 'active']]
        scalar('SELECT COUNT(*) FROM sync_operations') == 0
        scalar('SELECT COUNT(*) FROM applied_transactions') == 1
    }

    def "transaction duplicates select newest timestamp then row id and queue distinct deletes"() {
        given:
        def store = legacyStore()
        addLegacyResult(store, transactionPlan('old', 'txn-1'), 'child-budget', 'child-old',
            'applied', false, '2026-01-01T00:00:00Z')
        addLegacyResult(store, transactionPlan('winner-low-id', 'txn-1'), 'child-budget', 'child-tied-old',
            'applied', false, '2026-02-01T00:00:00Z')
        addLegacyResult(store, transactionPlan('winner-high-id', 'txn-1'), 'child-budget', 'child-winner',
            'applied', false, '2026-02-01T00:00:00Z')
        addLegacyResult(store, transactionPlan('repeat-winner', 'txn-1'), 'child-budget', 'child-winner',
            'applied', false, '2026-01-15T00:00:00Z')
        makeUnversioned()

        when:
        store.initialize()

        then:
        rows('SELECT child_transaction_id, status FROM child_mirrors ORDER BY id') == [
            [child_transaction_id: 'child-winner', status: 'active'],
            [child_transaction_id: 'child-tied-old', status: 'superseded'],
            [child_transaction_id: 'child-old', status: 'superseded']
        ]
        rows('SELECT operation_type, child_transaction_id, status FROM sync_operations ORDER BY id') == [
            [operation_type: 'delete', child_transaction_id: 'child-tied-old', status: 'pending'],
            [operation_type: 'delete', child_transaction_id: 'child-old', status: 'pending']
        ]
        store.findPendingMigrationCleanupOperations()*.intent*.childTransactionId ==
            ['child-tied-old', 'child-old']
    }

    def "movement duplicate grouping preserves side and target identity"() {
        given:
        def store = legacyStore()
        addLegacyResult(store, movementPlan('out-old', 'mm-1', -100), 'child-budget', 'movement-out-old',
            'applied', false, '2026-01-01T00:00:00Z')
        addLegacyResult(store, movementPlan('out-new', 'mm-1', -100), 'child-budget', 'movement-out-new',
            'applied', false, '2026-02-01T00:00:00Z')
        addLegacyResult(store, movementPlan('in', 'mm-1', 100), 'child-budget', 'movement-in',
            'applied', false, '2026-01-15T00:00:00Z')
        makeUnversioned()

        when:
        store.initialize()

        then:
        scalar('SELECT COUNT(*) FROM source_entities') == 1
        rows('SELECT direction, child_transaction_id, status FROM child_mirrors ORDER BY direction, status') == [
            [direction: 'inflow', child_transaction_id: 'movement-in', status: 'active'],
            [direction: 'outflow', child_transaction_id: 'movement-out-new', status: 'active'],
            [direction: 'outflow', child_transaction_id: 'movement-out-old', status: 'superseded']
        ]
        rows('SELECT child_transaction_id FROM sync_operations') == [[child_transaction_id: 'movement-out-old']]
    }

    def "failed dry-run and missing child ID history remains legacy-only"() {
        given:
        def store = legacyStore()
        addLegacyResult(store, transactionPlan('failed', 'txn-failed'), 'child-budget', 'failed-child',
            'failed', false, '2026-01-01T00:00:00Z')
        addLegacyResult(store, transactionPlan('dry', 'txn-dry'), 'child-budget', 'dry-child',
            'applied', true, '2026-01-02T00:00:00Z')
        addLegacyResult(store, transactionPlan('missing', 'txn-missing'), 'child-budget', null,
            'applied', false, '2026-01-03T00:00:00Z')
        makeUnversioned()

        when:
        store.initialize()

        then:
        scalar('SELECT COUNT(*) FROM applied_transactions') == 3
        scalar('SELECT COUNT(*) FROM source_entities') == 0
        scalar('SELECT COUNT(*) FROM child_mirrors') == 0
        scalar('SELECT COUNT(*) FROM sync_operations') == 0
    }

    def "dry-run projection reports migration without changing unversioned SQLite bytes"() {
        given:
        def store = legacyStore()
        addLegacyResult(store, transactionPlan('old', 'txn-1'), 'child-budget', 'child-old',
            'applied', false, '2026-01-01T00:00:00Z')
        addLegacyResult(store, transactionPlan('new', 'txn-1'), 'child-budget', 'child-new',
            'applied', false, '2026-02-01T00:00:00Z')
        makeUnversioned()
        byte[] before = Files.readAllBytes(Path.of(databasePath()))

        when:
        MigrationProjection projection = store.projectLegacyMigration()

        then:
        projection.mirrors*.childTransactionId == ['child-new', 'child-old']
        projection.mirrors*.active == [true, false]
        projection.cleanupOperations*.childTransactionId == ['child-old']
        Files.readAllBytes(Path.of(databasePath())) == before
        !tableNames().contains('schema_versions')
    }

    def "migration failure rolls schema and backfill back atomically"() {
        given:
        def original = legacyStore()
        addLegacyResult(original, transactionPlan('one', 'txn-1'), 'child-budget', 'child-1',
            'applied', false, '2026-01-01T00:00:00Z')
        makeUnversioned()
        def failingStore = new SyncStateStore(databasePath(), { int version, Connection ignored ->
            if (version == 2) throw new IllegalStateException('injected migration failure')
        })

        when:
        failingStore.initialize()

        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'injected migration failure'
        !tableNames().contains('schema_versions')
        !tableNames().contains('source_entities')
        scalar('SELECT COUNT(*) FROM applied_transactions') == 1
    }

    def "stable revisions operations dependencies retries and attempts are deterministic"() {
        given:
        def store = new SyncStateStore(databasePath())
        store.initialize()
        def source = new SourceEntityKey('parent', SourceEntityType.TRANSACTION, 'txn-1', null, null)
        long entityId = store.upsertSourceEntity(source)
        long batchId = store.createIngestionBatch('batch-1', 'transaction_delta', 42)

        expect:
        store.upsertSourceEntity(source) == entityId
        store.appendSourceRevision(entityId, 'revision-1', '{"amount":1}', 42, batchId) ==
            store.appendSourceRevision(entityId, 'revision-1', '{"amount":1}', 42, batchId)
        scalar('SELECT COUNT(*) FROM source_revisions') == 1

        when:
        long mirrorId = store.recordMirrorCreated(entityId, 'old-budget', 'outflow', 'old-child')
        long deleteId = store.createOperation(operation('delete-1', batchId, entityId, mirrorId, 0,
            ReconciliationOperationType.DELETE, 'old-budget', 'old-child', null))
        long createId = store.createOperation(operation('create-1', batchId, entityId, null, 1,
            ReconciliationOperationType.CREATE, 'new-budget', null, deleteId))

        then:
        store.createOperation(operation('delete-1', batchId, entityId, mirrorId, 0,
            ReconciliationOperationType.DELETE, 'old-budget', 'old-child', null)) == deleteId
        store.findReadyOperations()*.id == [deleteId]

        when:
        store.recordOperationAttempt(deleteId, 'failed', 'temporary')
        store.markOperationRetryable(deleteId)

        then:
        store.findReadyOperations()*.id == [deleteId]

        when:
        store.recordOperationAttempt(deleteId, 'applied')
        store.markOperationApplied(deleteId)

        then:
        store.findReadyOperations()*.id == [createId]
        scalar('SELECT COUNT(*) FROM operation_attempts') == 2

        when:
        store.markOperationApplied(createId)

        then:
        store.findReadyOperations().empty
    }

    def "mirror lifecycle retains every child transaction ID in audit history"() {
        given:
        def store = new SyncStateStore(databasePath())
        store.initialize()
        long entityId = store.upsertSourceEntity(
            new SourceEntityKey('parent', SourceEntityType.TRANSACTION, 'txn-1', null, null))

        when:
        long first = store.recordMirrorCreated(entityId, 'child-budget', 'outflow', 'child-1', 'account-1', 'hash-1')
        store.recordMirrorUpdated(first, 'account-2', 'hash-2')
        long second = store.recordMirrorReplacement(first, 'child-2', 'child-budget', 'outflow')
        long third = store.recordMirrorRecreation(second, 'child-3', 'child-budget', 'outflow')
        store.recordMirrorDeleted(third)

        then:
        store.findMirrorsForParent('parent', 'txn-1').empty
        store.findMirrorsForParent('parent', 'txn-1', true)*.childTransactionId == ['child-1', 'child-2', 'child-3']
        store.findMirrorsForParent('parent', 'txn-1', true)*.status == ['replaced', 'missing', 'deleted']
        rows('SELECT target_account_id, authoritative_payload_hash FROM child_mirrors WHERE id = ?', first) ==
            [[target_account_id: 'account-2', authoritative_payload_hash: 'hash-2']]
    }

    private SyncStateStore legacyStore() {
        def store = new SyncStateStore(databasePath())
        store.initialize()
        store
    }

    private void addLegacyResult(SyncStateStore store, ChildTransactionPlan plan, String targetBudgetId,
                                 String childTransactionId, String status, boolean dryRun, String appliedAt) {
        long eventId = store.recordSourceEvent(plan)
        long mappingId = store.recordMapping(eventId, plan, targetBudgetId, 'account-1')
        long runId = store.startRun(dryRun, 60, 'parent')
        store.recordAppliedTransaction(mappingId, runId, targetBudgetId, childTransactionId, status,
            status == 'failed' ? 'failed' : null, dryRun)
        execute("UPDATE applied_transactions SET applied_at = ? WHERE id = (SELECT MAX(id) FROM applied_transactions)", appliedAt)
    }

    private void makeUnversioned() {
        ['operation_attempts', 'sync_operations', 'child_mirrors', 'source_revisions',
         'ingestion_batches', 'source_entities', 'schema_versions'].each { String table ->
            execute("DROP TABLE ${table}")
        }
    }

    private static ChildTransactionPlan transactionPlan(String idempotencyKey, String transactionId) {
        new ChildTransactionPlan('parent', 'child', 'Child', 'spend', 'Spend', 'transaction',
            transactionId, null, null, null, idempotencyKey, 'Checking', '2026-01-01', -100,
            'memo', 'payee', true)
    }

    private static ChildTransactionPlan movementPlan(String idempotencyKey, String movementId, int amount) {
        new ChildTransactionPlan('parent', 'child', 'Child', 'spend', 'Spend', 'money_movement',
            null, null, movementId, 'group', idempotencyKey, 'Checking', '2026-01-01', amount,
            'memo', 'payee', true)
    }

    private static ReconciliationOperationIntent operation(String key, Long batchId, long entityId,
                                                            Long mirrorId, int sequence,
                                                            ReconciliationOperationType type,
                                                            String budgetId, String childId,
                                                            Long dependencyId) {
        new ReconciliationOperationIntent(key, batchId, entityId, mirrorId, sequence, type, budgetId,
            childId, null, null, dependencyId)
    }

    private String databasePath() {
        tempDir.resolve('state.db').toString()
    }

    private List<String> tableNames() {
        rows("SELECT name FROM sqlite_master WHERE type = 'table'")*.name
    }

    private Object scalar(String sql) {
        rows(sql)[0].values().first()
    }

    private List<Map<String, Object>> rows(String sql, Object... parameters) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement(sql)
            parameters.eachWithIndex { Object parameter, int index -> statement.setObject(index + 1, parameter) }
            def result = statement.executeQuery()
            List<Map<String, Object>> found = []
            while (result.next()) {
                Map<String, Object> row = [:]
                (1..result.metaData.columnCount).each { int index ->
                    row[result.metaData.getColumnLabel(index)] = result.getObject(index)
                }
                found << row
            }
            found
        }
    }

    private void execute(String sql, Object... parameters) {
        withConnection { Connection connection ->
            connection.createStatement().execute('PRAGMA foreign_keys = ON')
            def statement = connection.prepareStatement(sql)
            parameters.eachWithIndex { Object parameter, int index -> statement.setObject(index + 1, parameter) }
            statement.executeUpdate()
        }
    }

    private Object withConnection(Closure work) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${databasePath()}")
        try {
            work(connection)
        } finally {
            connection.close()
        }
    }
}

import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.sync.state.*

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class ReconciliationStateStoreIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    def "dry-run missing database reads empty and creates nothing"() {
        given:
        Path database = tempDir.resolve('missing.db')
        def dry = new DryRunSyncStateRepository(new SyncStateStore(database.toString()), database.toString())

        expect:
        !dry.reconciliationSchemaAvailable()
        dry.getCursor('missing') == null
        dry.findMirrorsForParent('parent', 'transaction').empty
        dry.findSourceEntities('parent', SourceEntityType.TRANSACTION).empty

        when:
        dry.findSourceEntityKey(1)

        then:
        thrown(IllegalStateException)
        !Files.exists(database)
    }

    def "dry-run reads supported state without changing database bytes"() {
        given:
        def store = initializedStore()
        long batch = store.createIngestionBatch('batch', 'transaction_delta', 4)
        long source = store.upsertSourceEntity(key())
        store.appendSourceRevision(source, 'revision', '{}', 4, batch)
        store.setCursor('cursor', 4)
        store.recordMirrorCreated(source, 'child-budget', 'outflow', 'child')
        byte[] before = Files.readAllBytes(database())
        def dry = new DryRunSyncStateRepository(store, database().toString())

        expect:
        dry.reconciliationSchemaAvailable()
        dry.getCursor('cursor') == 4
        dry.findMirrorsForSource(key())*.childTransactionId == ['child']
        dry.findSourceEntities('parent', SourceEntityType.TRANSACTION) == [key()]
        Files.readAllBytes(database()) == before
    }

    def "dry-run rejects unsupported old database without mutation"() {
        given:
        execute('CREATE TABLE source_events(id INTEGER)')
        byte[] before = Files.readAllBytes(database())
        def dry = new DryRunSyncStateRepository(new SyncStateStore(database().toString()), database().toString())

        when:
        dry.findMirrorsForParent('parent', 'transaction')

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('delete it')
        Files.readAllBytes(database()) == before
        userTables() == ['source_events'] as Set
    }

    def "batch kinds and non-null batch ownership are constrained"() {
        given:
        def store = initializedStore()
        long source = store.upsertSourceEntity(key())
        long batch = store.createIngestionBatch('transactions', 'transaction_delta', 1)

        when:
        store.createIngestionBatch('migration', 'migration', null)

        then:
        thrown(Exception)

        when:
        execute("INSERT INTO source_revisions(source_entity_id, revision_hash, normalized_json, ingestion_batch_id, observed_at) VALUES (${source}, 'bad', '{}', NULL, 'now')")

        then:
        thrown(Exception)

        when:
        execute("INSERT INTO sync_operations(operation_key, ingestion_batch_id, source_entity_id, operation_sequence, operation_type, target_budget_id, status, created_at) VALUES ('bad', NULL, ${source}, 0, 'create', 'child', 'pending', 'now')")

        then:
        thrown(Exception)

        when:
        store.appendSourceRevision(source, 'good', '{}', 1, batch)

        then:
        noExceptionThrown()
    }

    def "source revisions attempts operation intent and mirror lineage remain immutable"() {
        given:
        def store = initializedStore()
        long batch = store.createIngestionBatch('batch', 'transaction_delta', 1)
        long source = store.upsertSourceEntity(key())
        long revision = store.appendSourceRevision(source, 'revision', '{}', 1, batch)
        long mirror = store.recordMirrorCreated(source, 'child', 'outflow', 'child-1')
        long operation = store.createOperation(intent('delete', batch, source, mirror, 0,
            ReconciliationOperationType.DELETE, 'child-1', null))
        store.recordOperationAttempt(operation, 'failed', 'temporary')

        when:
        execute("UPDATE source_revisions SET normalized_json = '{\"changed\":true}' WHERE id = ${revision}")

        then:
        thrown(Exception)

        when:
        execute("UPDATE operation_attempts SET outcome = 'applied'")

        then:
        thrown(Exception)

        when:
        execute("UPDATE sync_operations SET operation_sequence = 9 WHERE id = ${operation}")

        then:
        thrown(Exception)

        when:
        execute("DELETE FROM child_mirrors WHERE id = ${mirror}")

        then:
        thrown(Exception)

        when:
        execute("UPDATE child_mirrors SET status = 'superseded' WHERE id = ${mirror}")

        then:
        thrown(Exception)
    }

    def "runtime revisions mirrors operations dependencies retries and completion work"() {
        given:
        def store = initializedStore()
        long source = store.upsertSourceEntity(key())
        long firstBatch = store.createIngestionBatch('first', 'transaction_delta', 10)
        long secondBatch = store.createIngestionBatch('second', 'money_movement_snapshot', 11)

        expect:
        store.upsertSourceEntity(key()) == source
        store.appendSourceRevision(source, 'revision', '{}', 10, firstBatch) ==
            store.appendSourceRevision(source, 'revision', '{}', 10, firstBatch)

        when:
        long mirror = store.recordMirrorCreated(source, 'child', 'outflow', 'old', 'account', 'old-hash')
        long delete = store.createOperation(intent('delete', firstBatch, source, mirror, 0,
            ReconciliationOperationType.DELETE, 'old', null))
        long create = store.createOperation(intent('create', firstBatch, source, null, 1,
            ReconciliationOperationType.CREATE, null, delete))
        long later = store.createOperation(intent('later', secondBatch, source, null, 0,
            ReconciliationOperationType.CREATE, null, null))

        then:
        store.findReadyOperations()*.id == [delete, later]
        !store.completeIngestionBatchIfReady(firstBatch)

        when:
        store.recordOperationAttempt(delete, 'failed', 'temporary')
        store.markOperationRetryable(delete)

        then:
        store.findSuccessfulOperationAttempt(delete) == null
        store.findReadyOperations()*.id == [delete, later]

        when:
        store.recordOperationAttempt(delete, 'applied')
        store.completeDeleteOperation(delete, mirror)

        then:
        store.findReadyOperations()*.id == [create, later]
        store.findSuccessfulOperationAttempt(delete).outcome == 'applied'

        when:
        store.recordOperationAttempt(create, 'applied', null, 'new')
        store.completeCreateOperation(create, source, null, 'child', 'outflow', 'new', 'account', 'new-hash', false)

        then:
        store.completeIngestionBatchIfReady(firstBatch)
        store.findMirrorsForSource(key(), true)*.status == ['deleted', 'active']
        store.findMirrorsForSource(key())*.childTransactionId == ['new']
        store.findSourceEntityKey(source) == key()
        rows("SELECT status FROM ingestion_batches WHERE id = ${firstBatch}")*.status == ['completed']
    }

    def "transaction kind completion requires every older unfinished transaction batch"() {
        given:
        def store = initializedStore()
        long source = store.upsertSourceEntity(key())
        long older = store.createIngestionBatch('older-delta', 'transaction_delta', 10)
        long newer = store.createIngestionBatch('newer-delta', 'transaction_delta', 20)
        long movement = store.createIngestionBatch('movement', 'money_movement_snapshot', 20)
        long mirror = store.recordMirrorCreated(source, 'child', 'outflow', 'child-1')
        long olderOp = store.createOperation(intent('older-op', older, source, mirror, 0,
            ReconciliationOperationType.UPDATE, 'child-1', null))
        long newerOp = store.createOperation(intent('newer-op', newer, source, mirror, 0,
            ReconciliationOperationType.UPDATE, 'child-1', null))

        expect:
        !store.completeIngestionBatchesOfKindIfReady('transaction_delta')
        rows("SELECT status FROM ingestion_batches WHERE id = ${older}")*.status == ['pending']
        rows("SELECT status FROM ingestion_batches WHERE id = ${newer}")*.status == ['pending']

        when:
        store.recordOperationAttempt(newerOp, 'applied')
        store.completeUpdateOperation(newerOp, mirror, 'account', 'hash-newer-op')

        then:
        !store.completeIngestionBatchesOfKindIfReady('transaction_delta')
        rows("SELECT status FROM ingestion_batches WHERE id = ${newer}")*.status == ['completed']
        rows("SELECT status FROM ingestion_batches WHERE id = ${older}")*.status == ['pending']

        when:
        store.recordOperationAttempt(olderOp, 'applied')
        store.completeUpdateOperation(olderOp, mirror, 'account', 'hash-older-op')

        then:
        store.completeIngestionBatchesOfKindIfReady('transaction_delta')
        store.completeIngestionBatchesOfKindIfReady('money_movement_snapshot')
        rows("SELECT status FROM ingestion_batches WHERE id IN (${older}, ${newer}, ${movement}) ORDER BY id")*.status ==
            ['completed', 'completed', 'completed']
    }

    private static SourceEntityKey key() {
        new SourceEntityKey('parent', SourceEntityType.TRANSACTION, 'transaction', null, null)
    }

    private static ReconciliationOperationIntent intent(String operationKey, long batch, long source,
                                                         Long mirror, int sequence,
                                                         ReconciliationOperationType type, String child,
                                                         Long dependency) {
        new ReconciliationOperationIntent(operationKey, batch, source, mirror, sequence, type,
            'child', child, type == ReconciliationOperationType.DELETE ? null : '{}',
            type == ReconciliationOperationType.DELETE ? null : "hash-${operationKey}", dependency)
    }

    private SyncStateStore initializedStore() {
        def store = new SyncStateStore(database().toString())
        store.initialize()
        store
    }

    private Path database() {
        tempDir.resolve('state.db')
    }

    private Set<String> userTables() {
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")*.name as Set
    }

    private List<Map<String, Object>> rows(String sql) {
        withConnection { Connection connection ->
            def result = connection.createStatement().executeQuery(sql)
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

    private void execute(String sql) {
        withConnection { Connection connection ->
            connection.createStatement().execute('PRAGMA foreign_keys = ON')
            connection.createStatement().execute(sql)
        }
    }

    private Object withConnection(Closure work) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${database()}")
        try {
            work(connection)
        } finally {
            connection.close()
        }
    }
}

import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.sync.ReconciliationOperationApplier
import ynabbankofdad.sync.SyncRunCoordinator
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.YnabBudgetRepository

import java.nio.file.Path
import java.sql.DriverManager

class ReconciliationRecoveryIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    def "three fresh processes recover mixed transaction and movement update delete work independently"() {
        given:
        String path = tempDir.resolve('recovery.db').toString()
        def firstStore = new SyncStateStore(path)
        firstStore.initialize()
        def remote = new RecoveringRepository()
        long transactionBatch = firstStore.createIngestionBatch('transaction-100', 'transaction_delta', 100)
        long movementBatch = firstStore.createIngestionBatch('movement-100', 'money_movement_snapshot', 100)
        long transactionSource = source(firstStore, SourceEntityType.TRANSACTION, 'txn', null)
        long movementSource = source(firstStore, SourceEntityType.MONEY_MOVEMENT, null, 'movement')
        long transactionUpdate = mirror(firstStore, transactionSource, remote, 'transaction-update')
        long transactionDelete = mirror(firstStore, transactionSource, remote, 'transaction-delete', 'inflow')
        long movementUpdate = mirror(firstStore, movementSource, remote, 'movement-update')
        long movementDelete = mirror(firstStore, movementSource, remote, 'movement-delete', 'inflow')
        operation(firstStore, 'txn-update', transactionBatch, transactionSource, transactionUpdate,
            ReconciliationOperationType.UPDATE, 'transaction-update', payload(-200), 0)
        operation(firstStore, 'txn-delete', transactionBatch, transactionSource, transactionDelete,
            ReconciliationOperationType.DELETE, 'transaction-delete', null, 1)
        operation(firstStore, 'movement-update', movementBatch, movementSource, movementUpdate,
            ReconciliationOperationType.UPDATE, 'movement-update', payload(-300), 0)
        operation(firstStore, 'movement-delete', movementBatch, movementSource, movementDelete,
            ReconciliationOperationType.DELETE, 'movement-delete', null, 1)
        remote.failOnce.addAll(['transaction-update', 'movement-delete'])

        when: 'the first process applies successful siblings and leaves failures durable'
        def first = apply(firstStore, remote)
        boolean firstTransactionComplete = firstStore.completeIngestionBatchesOfKindIfReady('transaction_delta')
        boolean firstMovementComplete = firstStore.completeIngestionBatchesOfKindIfReady('money_movement_snapshot')

        then:
        first.applied == 2
        first.failed == 2
        !firstTransactionComplete
        !firstMovementComplete
        remote.calls.count('transaction-delete') == 1
        remote.calls.count('movement-update') == 1

        when: 'a second fresh process retries only unfinished operations'
        def secondStore = new SyncStateStore(path)
        secondStore.initialize()
        def second = apply(secondStore, remote)
        boolean secondTransactionComplete = secondStore.completeIngestionBatchesOfKindIfReady('transaction_delta')
        boolean secondMovementComplete = secondStore.completeIngestionBatchesOfKindIfReady('money_movement_snapshot')
        long secondRun = secondStore.startRun(60, 'parent')
        new SyncRunCoordinator(secondStore, false).finishRun(secondRun,
            new SyncRunResult(second.failures), 100, secondTransactionComplete)

        then:
        second.applied == 2
        second.failed == 0
        secondTransactionComplete
        secondMovementComplete
        remote.calls.count('transaction-delete') == 1
        remote.calls.count('movement-update') == 1
        secondStore.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY) == 100

        when: 'a third fresh process applies new transaction work while new movement work fails'
        def thirdStore = new SyncStateStore(path)
        thirdStore.initialize()
        long transaction101 = thirdStore.createIngestionBatch('transaction-101', 'transaction_delta', 101)
        long movement101 = thirdStore.createIngestionBatch('movement-101', 'money_movement_snapshot', 101)
        operation(thirdStore, 'txn-new', transaction101, transactionSource, transactionUpdate,
            ReconciliationOperationType.UPDATE, 'transaction-update', payload(-400), 0)
        operation(thirdStore, 'movement-new', movement101, movementSource, movementUpdate,
            ReconciliationOperationType.UPDATE, 'movement-update', payload(-500), 0)
        remote.failOnce << 'movement-update'
        def third = apply(thirdStore, remote)
        boolean thirdTransactionComplete = thirdStore.completeIngestionBatchesOfKindIfReady('transaction_delta')
        boolean thirdMovementComplete = thirdStore.completeIngestionBatchesOfKindIfReady('money_movement_snapshot')
        long thirdRun = thirdStore.startRun(60, 'parent')
        new SyncRunCoordinator(thirdStore, false).finishRun(thirdRun,
            new SyncRunResult(third.failures), 101, thirdTransactionComplete)

        then:
        third.applied == 1
        third.failed == 1
        thirdTransactionComplete
        !thirdMovementComplete
        thirdStore.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY) == 101
        attempts(path, 'txn-delete') == 1
        attempts(path, 'movement-update') == 1
        attempts(path, 'movement-new') == 1
    }

    def "older incomplete transaction batch blocks cursor while newer batch is fully applied"() {
        given:
        String path = tempDir.resolve('older-batch.db').toString()
        def store = new SyncStateStore(path)
        store.initialize()
        def remote = new RecoveringRepository()
        long olderBatch = store.createIngestionBatch('transaction-100', 'transaction_delta', 100)
        long newerBatch = store.createIngestionBatch('transaction-200', 'transaction_delta', 200)
        long source = source(store, SourceEntityType.TRANSACTION, 'txn', null)
        long olderMirror = mirror(store, source, remote, 'older-child')
        long newerMirror = mirror(store, source, remote, 'newer-child', 'inflow')
        operation(store, 'older-update', olderBatch, source, olderMirror,
            ReconciliationOperationType.UPDATE, 'older-child', payload(-200), 0)
        operation(store, 'newer-update', newerBatch, source, newerMirror,
            ReconciliationOperationType.UPDATE, 'newer-child', payload(-300), 0)
        remote.failOnce << 'older-child'

        when: 'newer work succeeds while older work remains retryable'
        def first = apply(store, remote)
        boolean firstComplete = store.completeIngestionBatchesOfKindIfReady('transaction_delta')
        long firstRun = store.startRun(60, 'parent')
        new SyncRunCoordinator(store, false).finishRun(firstRun, new SyncRunResult(first.failures), 200, firstComplete)

        then:
        first.applied == 1
        first.failed == 1
        !firstComplete
        store.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY) == null
        !store.completeIngestionBatchIfReady(olderBatch)

        when: 'a later process finishes the older batch and only then advances the cursor'
        def resumed = new SyncStateStore(path)
        resumed.initialize()
        def second = apply(resumed, remote)
        boolean secondComplete = resumed.completeIngestionBatchesOfKindIfReady('transaction_delta')
        long secondRun = resumed.startRun(60, 'parent')
        new SyncRunCoordinator(resumed, false).finishRun(secondRun,
            new SyncRunResult(second.failures), 200, secondComplete)

        then:
        second.applied == 1
        second.failed == 0
        secondComplete
        resumed.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY) == 200
        resumed.completeIngestionBatchIfReady(olderBatch)
        resumed.completeIngestionBatchIfReady(newerBatch)
    }

    private static long source(SyncStateStore store, SourceEntityType type, String transactionId, String movementId) {
        store.upsertSourceEntity(new SourceEntityKey('parent', type, transactionId, null, movementId))
    }

    private static long mirror(SyncStateStore store, long sourceId, RecoveringRepository remote,
                               String childId, String direction = 'outflow') {
        remote.remote[childId] = child(childId, -100)
        store.recordMirrorCreated(sourceId, 'child-budget', direction, childId, 'account', 'old')
    }

    private static long operation(SyncStateStore store, String key, long batch, long sourceId, long mirrorId,
                                  ReconciliationOperationType type, String childId, Map payload, int sequence) {
        store.createOperation(new ReconciliationOperationIntent(key, batch, sourceId, mirrorId, sequence,
            type, 'child-budget', childId, payload == null ? null : JsonOutput.toJson(payload),
            "hash-${key}", null))
    }

    private static Map payload(int amount) {
        [account_id: 'account', date: '2026-07-18', amount: amount, payee_name: 'Payee',
         memo: 'memo', cleared: 'cleared', approved: false,
         _reconciliation_direction: amount >= 0 ? 'inflow' : 'outflow']
    }

    private static ChildTransaction child(String id, int amount) {
        new ChildTransaction(id, 'account', '2026-07-18', amount, null, 'Payee', null,
            'child memo', 'cleared', false, null, false)
    }

    private static def apply(SyncStateStore store, RecoveringRepository remote) {
        def target = new ChildBudgetSyncTarget('child', 'Child', 'TOKEN', [], '', '')
        def context = new ChildSyncContext(target, remote, 'child-budget')
        new ReconciliationOperationApplier(store, [context]).applyReadyOperations()
    }

    private static int attempts(String path, String operationKey) {
        def connection = DriverManager.getConnection("jdbc:sqlite:${path}")
        try {
            def statement = connection.prepareStatement('''
                SELECT COUNT(*) FROM operation_attempts attempt
                JOIN sync_operations operation ON operation.id = attempt.sync_operation_id
                WHERE operation.operation_key = ?
            ''')
            statement.setString(1, operationKey)
            def result = statement.executeQuery()
            result.next()
            result.getInt(1)
        } finally {
            connection.close()
        }
    }

    static class RecoveringRepository extends YnabBudgetRepository {
        Map<String, ChildTransaction> remote = [:]
        Set<String> failOnce = [] as Set
        List<String> calls = []

        RecoveringRepository() { super(null) }

        @Override
        ChildTransactionLookupResult getChildTransaction(String budgetId, String transactionId) {
            new ChildTransactionLookupResult(remote[transactionId], 1)
        }

        @Override
        ChildTransactionResult updateChildTransaction(String budgetId, String transactionId, Map<String, Object> fields) {
            calls << transactionId
            fail(transactionId)
            remote[transactionId] = child(transactionId, fields.amount as int)
            new ChildTransactionResult(remote[transactionId], 2)
        }

        @Override
        ChildTransactionDeleteResult deleteChildTransaction(String budgetId, String transactionId) {
            calls << transactionId
            fail(transactionId)
            def removed = remote.remove(transactionId)
            new ChildTransactionDeleteResult(removed, 3, removed == null)
        }

        private void fail(String transactionId) {
            if (failOnce.remove(transactionId)) {
                throw new IllegalStateException("injected failure for ${transactionId}")
            }
        }
    }
}

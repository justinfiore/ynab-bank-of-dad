import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.sync.ReconciliationOperationApplier
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.YnabBudgetRepository

import java.nio.file.Path
import java.sql.DriverManager

class ReconciliationMutationIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    SyncStateStore store
    StatefulChildRepository child
    long sourceId
    long transactionBatchId

    def setup() {
        store = new SyncStateStore(tempDir.resolve('mutations.db').toString())
        store.initialize()
        transactionBatchId = store.createIngestionBatch('transaction-delta', 'transaction_delta', 1)
        sourceId = store.upsertSourceEntity(new SourceEntityKey(
            'parent-budget', SourceEntityType.TRANSACTION, 'parent-transaction', null, null))
        child = new StatefulChildRepository()
    }

    def "authoritative update preserves child memo and stable source mirror lineage"() {
        given:
        long mirrorId = store.recordMirrorCreated(sourceId, 'child-budget', 'outflow', 'child-1', 'old-account', 'old-hash')
        child.remote['child-1'] = transaction('child-1', 'old-account', '2026-07-01', -100, 'Old', 'child-owned memo', 'uncleared', true)
        operation('authoritative-update', sourceId, ReconciliationOperationType.UPDATE, mirrorId, 'child-1',
            payload(account_id: 'new-account', date: '2026-07-02', amount: -250, payee_name: 'New'))

        when:
        def result = applier(context('child-budget')).applyReadyOperations()

        then:
        result.failed == 0
        child.updateCalls == 1
        child.lastUpdate == [account_id: 'new-account', amount: -250, date: '2026-07-02',
                             payee_id: null, payee_name: 'New', cleared: 'cleared', approved: false]
        child.remote['child-1'].memo == 'child-owned memo'
        child.remote['child-1'].cleared == 'cleared'
        !child.remote['child-1'].approved
        store.findSourceEntityKey(sourceId).parentTransactionId == 'parent-transaction'
        store.findMirrorsForParent('parent-budget', 'parent-transaction', true)*.childTransactionId == ['child-1']
        store.findMirrorsForParent('parent-budget', 'parent-transaction').first().targetAccountId == 'new-account'
    }

    def "cross-budget replacement waits for delete and retry never repeats completed delete"() {
        given:
        long mirrorId = store.recordMirrorCreated(sourceId, 'old-budget', 'outflow', 'old-child')
        child.remote['old-child'] = transaction('old-child', 'old-account', '2026-07-01', -100, 'Old', 'memo')
        long deleteId = operation('reroute-delete', sourceId, ReconciliationOperationType.DELETE,
            mirrorId, 'old-child', null, 'old-budget')
        operation('reroute-create', sourceId, ReconciliationOperationType.CREATE,
            mirrorId, null, payload(), 'new-budget', deleteId)
        child.failNextCreate = true

        when:
        def first = applier(context('old-budget'), context('new-budget')).applyReadyOperations()

        then:
        first.applied == 1
        first.failed == 1
        child.events == ['delete:old-budget:old-child', 'create:new-budget']
        scalar("SELECT status FROM sync_operations WHERE operation_key = 'reroute-delete'") == 'applied'
        scalar("SELECT status FROM sync_operations WHERE operation_key = 'reroute-create'") == 'retryable_failed'

        when:
        def second = new ReconciliationOperationApplier(new SyncStateStore(store.databasePath),
            [context('old-budget'), context('new-budget')]).applyReadyOperations()

        then:
        second.applied == 1
        second.failed == 0
        child.events == ['delete:old-budget:old-child', 'create:new-budget', 'create:new-budget']
        store.findMirrorsForParent('parent-budget', 'parent-transaction')*.targetBudgetId == ['new-budget']
        scalar('SELECT COUNT(*) FROM operation_attempts WHERE sync_operation_id = ?', deleteId) == 1
    }

    def "deletion unapproval and unmapping intents delete without reversal and absence is idempotent"() {
        given:
        ['deleted', 'unapproved', 'unmapped'].eachWithIndex { String reason, int index ->
            long entity = index == 0 ? sourceId : store.upsertSourceEntity(new SourceEntityKey(
                'parent-budget', SourceEntityType.TRANSACTION, "parent-${reason}", null, null))
            long mirror = store.recordMirrorCreated(entity, 'child-budget', 'outflow', "child-${reason}")
            if (reason != 'unmapped') {
                child.remote["child-${reason}"] = transaction("child-${reason}", 'account', '2026-07-01', -100, null, 'memo')
            }
            operation("remove-${reason}", entity, ReconciliationOperationType.DELETE, mirror,
                "child-${reason}", null)
        }

        when:
        def first = applier(context('child-budget')).applyReadyOperations()
        def second = applier(context('child-budget')).applyReadyOperations()

        then:
        first.applied == 3
        first.failed == 0
        second.applied == 0
        child.createCalls == 0
        child.deleteCalls == 3
        scalar("SELECT COUNT(*) FROM operation_attempts WHERE outcome = 'already_complete'") == 1
        store.findMirrorsForParent('parent-budget', 'parent-transaction').empty
    }

    def "missing child recreation retains prior child ID audit history"() {
        given:
        long mirrorId = store.recordMirrorCreated(sourceId, 'child-budget', 'outflow', 'missing-child')
        operation('verify-or-recreate', sourceId, ReconciliationOperationType.UPDATE,
            mirrorId, 'missing-child', payload(memo: 'creation memo'))

        when:
        applier(context('child-budget')).applyReadyOperations()

        then:
        child.lookupCalls == 1
        child.updateCalls == 0
        child.createCalls == 1
        store.findMirrorsForParent('parent-budget', 'parent-transaction', true)*.childTransactionId ==
            ['missing-child', 'created-1']
        store.findMirrorsForParent('parent-budget', 'parent-transaction', true)*.status == ['missing', 'active']
    }

    def "remote create update and delete crash windows recover without a second financial effect"() {
        given:
        def crashing = new CompletionFailingStore(store.databasePath)
        long createSource = crashing.upsertSourceEntity(new SourceEntityKey(
            'parent-budget', SourceEntityType.TRANSACTION, 'create-source', null, null))
        operation('create-crash', createSource, ReconciliationOperationType.CREATE, null, null, payload())
        crashing.failType = ReconciliationOperationType.CREATE

        when:
        new ReconciliationOperationApplier(crashing, [context('child-budget')]).applyReadyOperations()
        new ReconciliationOperationApplier(new SyncStateStore(store.databasePath), [context('child-budget')]).applyReadyOperations()

        then:
        child.createCalls == 1

        when:
        long updateMirror = store.recordMirrorCreated(sourceId, 'child-budget', 'outflow', 'update-child')
        child.remote['update-child'] = transaction('update-child', 'old', '2026-07-01', -100, null, 'memo')
        operation('update-crash', sourceId, ReconciliationOperationType.UPDATE, updateMirror,
            'update-child', payload(amount: -200))
        crashing.failType = ReconciliationOperationType.UPDATE
        new ReconciliationOperationApplier(crashing, [context('child-budget')]).applyReadyOperations()
        new ReconciliationOperationApplier(new SyncStateStore(store.databasePath), [context('child-budget')]).applyReadyOperations()

        then:
        child.updateCalls == 1

        when:
        operation('delete-crash', sourceId, ReconciliationOperationType.DELETE, updateMirror,
            'update-child', null)
        crashing.failType = ReconciliationOperationType.DELETE
        new ReconciliationOperationApplier(crashing, [context('child-budget')]).applyReadyOperations()
        new ReconciliationOperationApplier(new SyncStateStore(store.databasePath), [context('child-budget')]).applyReadyOperations()

        then:
        child.deleteCalls == 1
        scalar("SELECT COUNT(*) FROM operation_attempts WHERE sync_operation_id IN (SELECT id FROM sync_operations WHERE operation_key LIKE '%-crash')") == 9
    }

    private ReconciliationOperationApplier applier(ChildSyncContext... contexts) {
        new ReconciliationOperationApplier(store, contexts as List)
    }

    private ChildSyncContext context(String budgetId) {
        def target = new ChildBudgetSyncTarget(budgetId, budgetId, 'TOKEN', [], '', '')
        new ChildSyncContext(target, child, budgetId)
    }

    private long operation(String key, long entityId, ReconciliationOperationType type, Long mirrorId,
                           String childId, Map values, String budgetId = 'child-budget', Long dependency = null) {
        store.createOperation(new ReconciliationOperationIntent(key, transactionBatchId, entityId, mirrorId, 0, type,
            budgetId, childId, values == null ? null : JsonOutput.toJson(values), "hash-${key}", dependency))
    }

    private static Map payload(Map overrides = [:]) {
        [account_id: 'account', date: '2026-07-18', amount: -100, payee_id: null,
         payee_name: 'Payee', category_id: null, memo: 'source memo', cleared: 'cleared', approved: false,
         _reconciliation_direction: 'outflow'] + overrides
    }

    private static ChildTransaction transaction(String id, String account, String date, int amount,
                                                String payee, String memo, String cleared = 'cleared',
                                                boolean approved = false) {
        new ChildTransaction(id, account, date, amount, null, payee, null, memo,
            cleared, approved, null, false)
    }

    private Object scalar(String sql, Object... parameters) {
        def connection = DriverManager.getConnection("jdbc:sqlite:${store.databasePath}")
        try {
            def statement = connection.prepareStatement(sql)
            parameters.eachWithIndex { Object value, int index -> statement.setObject(index + 1, value) }
            def result = statement.executeQuery()
            result.next()
            result.getObject(1)
        } finally {
            connection.close()
        }
    }

    static class StatefulChildRepository extends YnabBudgetRepository {
        Map<String, ChildTransaction> remote = [:]
        Map<String, String> importIds = [:]
        List<String> events = []
        int nextId = 1
        int createCalls
        int lookupCalls
        int updateCalls
        int deleteCalls
        boolean failNextCreate
        Map lastUpdate

        StatefulChildRepository() { super(null) }

        @Override
        def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
            createCalls++
            events << "create:${budgetId}"
            if (failNextCreate) {
                failNextCreate = false
                throw new IllegalStateException('injected create failure')
            }
            Map values = transactions.first()
            String id = importIds[values.import_id as String]
            if (!id) {
                id = "created-${nextId++}"
                importIds[values.import_id as String] = id
                remote[id] = transaction(id, values.account_id as String, values.date as String,
                    values.amount as int, values.payee_name as String, values.memo as String,
                    values.cleared as String, values.approved as boolean)
            }
            [data: [bulk: [transaction_ids: [id]]]]
        }

        @Override
        ChildTransactionLookupResult getChildTransaction(String budgetId, String transactionId) {
            lookupCalls++
            new ChildTransactionLookupResult(remote[transactionId], 1)
        }

        @Override
        ChildTransactionResult updateChildTransaction(String budgetId, String transactionId, Map<String, Object> fields) {
            updateCalls++
            lastUpdate = new LinkedHashMap(fields)
            ChildTransaction old = remote[transactionId]
            remote[transactionId] = transaction(transactionId, fields.account_id as String,
                fields.date as String, fields.amount as int, fields.payee_name as String,
                old.memo, fields.cleared as String, fields.approved as boolean)
            new ChildTransactionResult(remote[transactionId], 2)
        }

        @Override
        ChildTransactionDeleteResult deleteChildTransaction(String budgetId, String transactionId) {
            deleteCalls++
            events << "delete:${budgetId}:${transactionId}"
            ChildTransaction removed = remote.remove(transactionId)
            new ChildTransactionDeleteResult(removed, 3, removed == null)
        }
    }

    static class CompletionFailingStore extends SyncStateStore {
        ReconciliationOperationType failType

        CompletionFailingStore(String path) { super(path) }

        @Override
        void completeCreateOperation(long operationId, long sourceEntityId, Long oldMirrorId,
                                     String targetBudgetId, String direction, String childTransactionId,
                                     String targetAccountId, String payloadHash, boolean recreation) {
            failOnce(ReconciliationOperationType.CREATE)
            super.completeCreateOperation(operationId, sourceEntityId, oldMirrorId, targetBudgetId,
                direction, childTransactionId, targetAccountId, payloadHash, recreation)
        }

        @Override
        void completeUpdateOperation(long operationId, long mirrorId, String accountId, String hash) {
            failOnce(ReconciliationOperationType.UPDATE)
            super.completeUpdateOperation(operationId, mirrorId, accountId, hash)
        }

        @Override
        void completeDeleteOperation(long operationId, Long mirrorId) {
            failOnce(ReconciliationOperationType.DELETE)
            super.completeDeleteOperation(operationId, mirrorId)
        }

        private void failOnce(ReconciliationOperationType type) {
            if (failType == type) {
                failType = null
                throw new IllegalStateException("injected ${type.databaseValue} completion failure")
            }
        }
    }
}

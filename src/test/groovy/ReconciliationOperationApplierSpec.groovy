import groovy.json.JsonOutput
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.sync.ChildTransactionPayloadFactory
import ynabbankofdad.sync.ReconciliationOperationApplier
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.reconcile.DesiredMirrorFactory
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.YnabBudgetRepository

import java.nio.file.Path
import java.sql.DriverManager

class ReconciliationOperationApplierSpec extends Specification {

    @TempDir
    Path tempDir

    SyncStateStore store
    FakeChildRepository repository
    long sourceId
    long transactionBatchId

    def setup() {
        store = new CrashWindowStateStore(tempDir.resolve('state.db').toString())
        store.initialize()
        transactionBatchId = store.createIngestionBatch('transaction-delta', 'transaction_delta', 1)
        sourceId = store.upsertSourceEntity(
            new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION, 'parent-txn', null, null))
        repository = new FakeChildRepository()
    }

    def "create uses stable import identity activates returned mirror and never reapplies"() {
        given:
        long operationId = createOperation('create', ReconciliationOperationType.CREATE, null, null, payload())
        def applier = applier(store)

        when:
        def first = applier.applyReadyOperations()
        def second = applier.applyReadyOperations()

        then:
        first.applied == 1
        second.applied == 0
        repository.postCalls == 1
        repository.lastCreated.import_id.toString().startsWith('PCBS:')
        repository.lastCreated.import_id.toString().size() == 36
        store.findMirrorsForParent('parent-budget', 'parent-txn')*.childTransactionId == ['child-1']
        scalar('SELECT status FROM sync_operations WHERE id = ?', operationId) == 'applied'
        scalar('SELECT COUNT(*) FROM operation_attempts WHERE sync_operation_id = ?', operationId) == 1
    }

    def "create incorporates persisted import id namespace metadata without sending it"() {
        given:
        createOperation('namespaced-create', ReconciliationOperationType.CREATE, null, null,
            payload((DesiredMirrorFactory.IMPORT_ID_NAMESPACE_FIELD): 'reseed-v2'))

        when:
        def result = applier(store).applyReadyOperations()

        then:
        result.failed == 0
        repository.lastCreated.import_id == new ChildTransactionPayloadFactory().buildImportId(
            store.findSourceEntityKey(sourceId), 'child-budget', 'outflow', null, 'reseed-v2')
        !repository.lastCreated.containsKey(DesiredMirrorFactory.IMPORT_ID_NAMESPACE_FIELD)
    }

    def "update is partial preserves memo enforces cleared unapproved and skips an already matching retry"() {
        given:
        long mirrorId = store.recordMirrorCreated(sourceId, 'child-budget', 'outflow', 'existing', 'old-account', 'old')
        repository.remote['existing'] = child('existing', 'old-account', 'old memo', 'uncleared', true)
        long operationId = createOperation('update', ReconciliationOperationType.UPDATE, mirrorId, 'existing', payload())

        when:
        def result = applier(store).applyReadyOperations()

        then:
        result.failed == 0
        repository.updateCalls == 1
        !repository.lastUpdate.containsKey('memo')
        !repository.lastUpdate.containsKey('import_id')
        !repository.lastUpdate.containsKey('category_id')
        repository.lastUpdate.cleared == 'cleared'
        repository.lastUpdate.approved == false
        repository.remote.existing.memo == 'old memo'
        store.findMirrorsForParent('parent-budget', 'parent-txn').first().targetAccountId == 'account-1'

        when: 'the local completion is retried after the same remote state is observed'
        execute("UPDATE sync_operations SET status = 'retryable_failed', completed_at = NULL WHERE id = ?", operationId)
        def retry = applier(store).applyReadyOperations()

        then:
        retry.applied == 1
        repository.updateCalls == 1
        scalar('SELECT COUNT(*) FROM operation_attempts WHERE sync_operation_id = ?', operationId) == 2
    }

    def "missing update recreates on authoritative or no-op intent and retains child lineage"() {
        given:
        long mirrorId = store.recordMirrorCreated(sourceId, 'child-budget', 'outflow', 'missing', 'account-1', 'old')
        createOperation('missing-update', ReconciliationOperationType.UPDATE, mirrorId, 'missing', payload())

        when:
        def result = applier(store).applyReadyOperations()

        then:
        result.applied == 1
        repository.updateCalls == 0
        repository.postCalls == 1
        store.findMirrorsForParent('parent-budget', 'parent-txn', true)*.childTransactionId == ['missing', 'child-1']
        store.findMirrorsForParent('parent-budget', 'parent-txn', true)*.status == ['missing', 'active']
    }

    def "recreation posts a new import identity when YNAB already consumed the deleted child's import id"() {
        given:
        createOperation('original-create', ReconciliationOperationType.CREATE, null, null, payload())
        applier(store).applyReadyOperations()
        String originalImportId = repository.lastCreated.import_id as String
        String originalChildId = store.findMirrorsForParent('parent-budget', 'parent-txn').first().childTransactionId
        long originalMirrorId = store.findMirrorsForParent('parent-budget', 'parent-txn').first().id
        repository.remote.remove(originalChildId)
        createOperation('recreate-after-delete', ReconciliationOperationType.UPDATE, originalMirrorId,
            originalChildId, payload())

        when:
        def result = applier(store).applyReadyOperations()

        then:
        result.applied == 1
        result.failed == 0
        repository.postCalls == 2
        repository.recoveryCalls == 0
        repository.lastCreated.import_id != originalImportId
        store.findMirrorsForParent('parent-budget', 'parent-txn', true)*.status == ['missing', 'active']
    }

    def "delete treats already absent as success and unlocks dependent create in the same batch"() {
        given:
        long oldMirror = store.recordMirrorCreated(sourceId, 'old-budget', 'outflow', 'old-child')
        long deleteId = createOperation('delete', ReconciliationOperationType.DELETE, oldMirror, 'old-child', null,
            'old-budget', null)
        createOperation('replacement', ReconciliationOperationType.CREATE, oldMirror, null, payload(),
            'child-budget', deleteId)

        when:
        def result = applier(store, contexts(
            context('old-budget', repository), context('child-budget', repository))).applyReadyOperations()

        then:
        result.applied == 2
        result.failed == 0
        repository.deleteCalls == 1
        repository.postCalls == 1
        scalar("SELECT outcome FROM operation_attempts WHERE sync_operation_id = ?", deleteId) == 'already_complete'
        store.findMirrorsForParent('parent-budget', 'parent-txn')*.targetBudgetId == ['child-budget']
    }

    def "every mutation outcome emits an INFO audit record with stable identifiers"() {
        given:
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        Logger logger = LoggerFactory.getLogger(ReconciliationOperationApplier) as Logger
        logger.addAppender(appender)
        long updateSource = store.upsertSourceEntity(
            new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION, 'update-source', null, null))
        long recreateSource = store.upsertSourceEntity(
            new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION, 'recreate-source', null, null))
        long deleteSource = store.upsertSourceEntity(
            new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION, 'delete-source', null, null))
        long updateMirror = store.recordMirrorCreated(updateSource, 'child-budget', 'outflow', 'update-child')
        long recreateMirror = store.recordMirrorCreated(recreateSource, 'child-budget', 'outflow', 'missing-child')
        long deleteMirror = store.recordMirrorCreated(deleteSource, 'child-budget', 'outflow', 'delete-child')
        repository.remote['update-child'] = child('update-child', 'old-account', 'child memo', 'uncleared', true)
        repository.remote['delete-child'] = child('delete-child', 'account-1', 'child memo', 'cleared', false)
        createOperation('audit-create', ReconciliationOperationType.CREATE, null, null, payload())
        createOperationForSource('audit-update', updateSource, ReconciliationOperationType.UPDATE,
            updateMirror, 'update-child', payload())
        createOperationForSource('audit-recreate', recreateSource, ReconciliationOperationType.UPDATE,
            recreateMirror, 'missing-child', payload())
        createOperationForSource('audit-delete', deleteSource, ReconciliationOperationType.DELETE,
            deleteMirror, 'delete-child', null)

        when:
        def result = applier(store).applyReadyOperations()
        List<String> messages = appender.list.findAll { it.level.levelStr == 'INFO' }*.formattedMessage

        then:
        result.applied == 4
        ['create', 'update', 'recreate', 'delete'].every { action ->
            messages.any { it.contains("Reconciliation outcome action=${action}") }
        }
        messages.any { it.contains('transaction=update-source') && it.contains('childTransaction=update-child') }
        messages.any { it.contains('transaction=recreate-source') && it.contains('childTransaction=child-') }
        messages.any { it.contains('transaction=delete-source') && it.contains('childTransaction=delete-child') }
        messages.findAll { it.startsWith('Reconciliation outcome') }.every {
            it.contains('operation=') && it.contains('targetBudget=child-budget') && !it.contains('TOKEN')
        }

        cleanup:
        logger.detachAppender(appender)
    }

    def "failed child remains retryable while successful sibling is retained"() {
        given:
        long otherSource = store.upsertSourceEntity(
            new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION, 'sibling', null, null))
        createOperation('first', ReconciliationOperationType.CREATE, null, null, payload())
        createOperationForSource('second', otherSource, ReconciliationOperationType.CREATE, null, null,
            payload(amount: -200))
        repository.failAmounts << -100

        when:
        def firstRun = applier(store).applyReadyOperations()

        then:
        firstRun.applied == 1
        firstRun.failed == 1
        scalar("SELECT status FROM sync_operations WHERE operation_key = 'first'") == 'retryable_failed'
        scalar("SELECT status FROM sync_operations WHERE operation_key = 'second'") == 'applied'

        when:
        repository.failAmounts.clear()
        def secondRun = applier(store).applyReadyOperations()

        then:
        secondRun.applied == 1
        repository.financialEffects == 2
        scalar("SELECT COUNT(*) FROM operation_attempts WHERE sync_operation_id = (SELECT id FROM sync_operations WHERE operation_key = 'first')") == 2
    }

    def "create crash windows recover without a second financial effect"() {
        given:
        createOperation('create-crash', ReconciliationOperationType.CREATE, null, null, payload())
        store.failCompletion = ReconciliationOperationType.CREATE

        when:
        def crashed = applier(store).applyReadyOperations()
        def restarted = applier(new SyncStateStore(store.databasePath)).applyReadyOperations()

        then:
        crashed.failed == 1
        restarted.applied == 1
        repository.postCalls == 1
        repository.financialEffects == 1
        scalar("SELECT GROUP_CONCAT(outcome, ',') FROM operation_attempts") == 'applied,failed,already_complete'
    }

    def "stable create identity recovers when the remote success attempt cannot be recorded"() {
        given:
        createOperation('create-attempt-crash', ReconciliationOperationType.CREATE, null, null, payload())
        store.failNextAppliedAttempt = true

        when:
        def crashed = applier(store).applyReadyOperations()
        def restarted = applier(new SyncStateStore(store.databasePath)).applyReadyOperations()

        then:
        crashed.failed == 1
        restarted.applied == 1
        repository.postCalls == 2
        repository.recoveryCalls == 1
        repository.financialEffects == 1
        scalar("SELECT GROUP_CONCAT(outcome, ',') FROM operation_attempts") == 'failed,already_complete'
    }

    def "zero amount create applies explicit outflow direction without sending internal metadata"() {
        given:
        createOperation('zero-outflow', ReconciliationOperationType.CREATE, null, null,
            payload(amount: 0, (DesiredMirrorFactory.LOGICAL_DIRECTION_FIELD): 'outflow'))

        when:
        def result = applier(store).applyReadyOperations()

        then:
        result.failed == 0
        store.findMirrorsForParent('parent-budget', 'parent-txn')*.direction == ['outflow']
        repository.lastCreated.amount == 0
        !repository.lastCreated.containsKey(DesiredMirrorFactory.LOGICAL_DIRECTION_FIELD)
    }

    def "one cycle drains more ready operations than the fetch page size so later creates are not starved"() {
        given:
        // forceLookback can re-queue many transaction existence-check updates ahead of
        // money-movement creates. A hard 100-op cap left those creates pending forever
        // while each cycle only re-applied the first page of ready work.
        int aheadOfCreate = 5
        (1..aheadOfCreate).each { int index ->
            long entityId = store.upsertSourceEntity(
                new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION,
                    "ahead-txn-${index}", null, null))
            long mirrorId = store.recordMirrorCreated(entityId, 'child-budget', 'outflow',
                "existing-${index}", 'account-1', "hash-update-${index}")
            repository.remote["existing-${index}"] = child("existing-${index}", 'account-1',
                'memo', 'cleared', false)
            createOperationForSource("update-${index}", entityId, ReconciliationOperationType.UPDATE,
                mirrorId, "existing-${index}", payload())
        }
        createOperation('late-create', ReconciliationOperationType.CREATE, null, null, payload())

        when:
        def result = applier(store).applyReadyOperations(3)

        then:
        result.failed == 0
        result.applied == aheadOfCreate + 1
        repository.postCalls == 1
        scalar('SELECT COUNT(*) FROM sync_operations WHERE status = ?', 'pending') == 0
        scalar('SELECT COUNT(*) FROM sync_operations WHERE status = ?', 'applied') == aheadOfCreate + 1
        store.findMirrorsForParent('parent-budget', 'parent-txn')
            .any { it.childTransactionId == 'child-1' }
    }

    def "failed ready operations are not retried endlessly within the same drain"() {
        given:
        long siblingSource = store.upsertSourceEntity(
            new SourceEntityKey('parent-budget', SourceEntityType.TRANSACTION, 'sibling-txn', null, null))
        createOperation('failing', ReconciliationOperationType.CREATE, null, null,
            payload(amount: -999))
        repository.failAmounts << -999
        createOperationForSource('sibling', siblingSource, ReconciliationOperationType.CREATE,
            null, null, payload())

        when:
        def result = applier(store).applyReadyOperations(1)

        then:
        result.failed == 1
        result.applied == 1
        repository.postCalls == 2
        scalar('SELECT status FROM sync_operations WHERE operation_key = ?', 'failing') == 'retryable_failed'
        scalar('SELECT status FROM sync_operations WHERE operation_key = ?', 'sibling') == 'applied'
    }

    def "update and delete crash windows recover from remote state or successful attempts"() {
        given:
        long updateMirror = store.recordMirrorCreated(sourceId, 'child-budget', 'outflow', 'update-child')
        repository.remote['update-child'] = child('update-child', 'old-account', 'memo', 'uncleared', true)
        createOperation('update-crash', ReconciliationOperationType.UPDATE, updateMirror, 'update-child', payload())
        store.failCompletion = ReconciliationOperationType.UPDATE

        when:
        def updateCrash = applier(store).applyReadyOperations()
        def updateRecovery = applier(new SyncStateStore(store.databasePath)).applyReadyOperations()

        then:
        updateCrash.failed == 1
        updateRecovery.applied == 1
        repository.updateCalls == 1

        when:
        long deleteMirror = store.findMirrorsForParent('parent-budget', 'parent-txn').first().id
        createOperation('delete-crash', ReconciliationOperationType.DELETE, deleteMirror, 'update-child', null)
        store.failCompletion = ReconciliationOperationType.DELETE
        def deleteCrash = applier(store).applyReadyOperations()
        def deleteRecovery = applier(new SyncStateStore(store.databasePath)).applyReadyOperations()

        then:
        deleteCrash.failed == 1
        deleteRecovery.applied == 1
        repository.deleteCalls == 1
        repository.financialEffects == 2
        store.findMirrorsForParent('parent-budget', 'parent-txn').empty
    }

    private ReconciliationOperationApplier applier(ReconciliationOperationStateRepository state,
                                                    List<ChildSyncContext> childContexts = contexts(context('child-budget', repository))) {
        new ReconciliationOperationApplier(state, childContexts)
    }

    private long createOperation(String key, ReconciliationOperationType type, Long mirrorId,
                                 String childId, Map transaction, String budgetId = 'child-budget',
                                 Long dependencyId = null) {
        createOperationForSource(key, sourceId, type, mirrorId, childId, transaction, budgetId, dependencyId)
    }

    private long createOperationForSource(String key, long entityId, ReconciliationOperationType type,
                                          Long mirrorId, String childId, Map transaction,
                                          String budgetId = 'child-budget', Long dependencyId = null) {
        store.createOperation(new ReconciliationOperationIntent(
            key, transactionBatchId, entityId, mirrorId, 0, type, budgetId, childId,
            transaction == null ? null : JsonOutput.toJson(transaction), "hash-${key}", dependencyId))
    }

    private static Map payload(Map overrides = [:]) {
        [account_id: 'account-1', date: '2026-07-18', amount: -100, payee_name: 'Store',
         category_id: null, memo: 'parent memo', cleared: 'cleared', approved: false,
         (DesiredMirrorFactory.LOGICAL_DIRECTION_FIELD): 'outflow'] + overrides
    }

    private static ChildTransaction child(String id, String accountId, String memo, String cleared, boolean approved) {
        new ChildTransaction(id, accountId, '2026-07-18', -100, null, 'Store', null, memo,
            cleared, approved, null, false)
    }

    private static ChildSyncContext context(String budgetId, FakeChildRepository repository) {
        def target = new ChildBudgetSyncTarget(childKey: 'child', budgetName: 'Child', tokenEnvVarName: 'TOKEN',
            accountMappings: [], memoPrefix: '', memoSuffix: '')
        new ChildSyncContext(target, repository, budgetId)
    }

    private static List<ChildSyncContext> contexts(ChildSyncContext... values) {
        values as List
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

    private void execute(String sql, Object... parameters) {
        def connection = DriverManager.getConnection("jdbc:sqlite:${store.databasePath}")
        try {
            def statement = connection.prepareStatement(sql)
            parameters.eachWithIndex { Object value, int index -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        } finally {
            connection.close()
        }
    }

    static class FakeChildRepository extends YnabBudgetRepository {
        Map<String, ChildTransaction> remote = [:]
        Map<String, String> idsByImport = [:]
        Set<Integer> failAmounts = [] as Set
        int nextId = 1
        int postCalls
        int recoveryCalls
        int updateCalls
        int deleteCalls
        int financialEffects
        Map lastCreated
        Map lastUpdate

        FakeChildRepository() { super(null) }

        @Override
        def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
            postCalls++
            Map transaction = new LinkedHashMap(transactions.first())
            lastCreated = transaction
            if (failAmounts.contains(transaction.amount as Integer)) {
                throw new IllegalStateException("create failed for ${transaction.amount}")
            }
            String id = idsByImport[transaction.import_id as String]
            if (!id) {
                id = "child-${nextId++}"
                idsByImport[transaction.import_id as String] = id
                remote[id] = fromMap(id, transaction, transaction.memo as String)
                financialEffects++
                return [data: [bulk: [transaction_ids: [id]]]]
            }
            [data: [bulk: [transaction_ids: [], duplicate_import_ids: [transaction.import_id]]]]
        }

        @Override
        ChildTransactionResult recoverChildTransactionByImportId(String budgetId, String importId,
                                                                   Map<String, Object> fields) {
            recoveryCalls++
            String id = idsByImport[importId]
            if (!id) throw new IllegalStateException('missing recovery target')
            remote[id] = fromMap(id, fields, remote[id].memo, remote[id])
            new ChildTransactionResult(remote[id], 2)
        }

        @Override
        ChildTransactionLookupResult getChildTransaction(String budgetId, String transactionId) {
            new ChildTransactionLookupResult(remote[transactionId], 1)
        }

        @Override
        ChildTransactionResult updateChildTransaction(String budgetId, String transactionId, Map<String, Object> fields) {
            updateCalls++
            lastUpdate = new LinkedHashMap(fields)
            ChildTransaction existing = remote[transactionId]
            if (existing == null) throw new IllegalStateException('missing update target')
            remote[transactionId] = fromMap(transactionId, fields, existing.memo, existing)
            financialEffects++
            new ChildTransactionResult(remote[transactionId], 2)
        }

        @Override
        ChildTransactionDeleteResult deleteChildTransaction(String budgetId, String transactionId) {
            deleteCalls++
            ChildTransaction removed = remote.remove(transactionId)
            if (removed != null) financialEffects++
            new ChildTransactionDeleteResult(removed, 3, removed == null)
        }

        private static ChildTransaction fromMap(String id, Map values, String memo,
                                                ChildTransaction original = null) {
            new ChildTransaction(
                id, (values.account_id ?: original?.accountId) as String,
                (values.date ?: original?.date) as String,
                (values.amount == null ? original?.amount : values.amount) as Integer,
                (values.payee_id ?: original?.payeeId) as String,
                (values.payee_name ?: original?.payeeName) as String,
                original?.categoryId, memo,
                (values.cleared ?: original?.cleared) as String,
                (values.approved == null ? original?.approved : values.approved) as Boolean,
                original?.flagColor, false)
        }
    }

    static class CrashWindowStateStore extends SyncStateStore {
        ReconciliationOperationType failCompletion
        boolean failNextAppliedAttempt

        CrashWindowStateStore(String path) { super(path) }

        @Override
        void recordOperationAttempt(long operationId, String outcome, String failureReason = null,
                                    String returnedChildTransactionId = null) {
            if (failNextAppliedAttempt && outcome == 'applied') {
                failNextAppliedAttempt = false
                throw new IllegalStateException('injected attempt recording failure')
            }
            super.recordOperationAttempt(operationId, outcome, failureReason, returnedChildTransactionId)
        }

        @Override
        void completeCreateOperation(long operationId, long sourceEntityId, Long oldMirrorId,
                                     String targetBudgetId, String direction, String childTransactionId,
                                     String targetAccountId, String payloadHash, boolean recreation) {
            failOnce(ReconciliationOperationType.CREATE)
            super.completeCreateOperation(operationId, sourceEntityId, oldMirrorId, targetBudgetId,
                direction, childTransactionId, targetAccountId, payloadHash, recreation)
        }

        @Override
        void completeUpdateOperation(long operationId, long mirrorId, String targetAccountId, String payloadHash) {
            failOnce(ReconciliationOperationType.UPDATE)
            super.completeUpdateOperation(operationId, mirrorId, targetAccountId, payloadHash)
        }

        @Override
        void completeDeleteOperation(long operationId, Long mirrorId) {
            failOnce(ReconciliationOperationType.DELETE)
            super.completeDeleteOperation(operationId, mirrorId)
        }

        private void failOnce(ReconciliationOperationType type) {
            if (failCompletion == type) {
                failCompletion = null
                throw new IllegalStateException("injected ${type.databaseValue} completion failure")
            }
        }
    }
}

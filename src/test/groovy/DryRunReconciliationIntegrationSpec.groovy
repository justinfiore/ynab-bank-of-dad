import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.*
import ynabbankofdad.sync.ParentChildBudgetSyncer
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.YnabBudgetRepository
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path

class DryRunReconciliationIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    def "dry run reports create update delete reroute and recreation with byte-identical SQLite"() {
        given:
        Path database = tempDir.resolve('dry-run.db')
        def persistent = new SyncStateStore(database.toString())
        persistent.initialize()
        long entity = persistent.upsertSourceEntity(new SourceEntityKey(
            'parent', SourceEntityType.TRANSACTION, 'source', null, null))
        persistent.recordMirrorCreated(entity, 'child-budget', 'outflow', 'existing')
        byte[] before = Files.readAllBytes(database)
        def dryState = new DryRunSyncStateRepository(persistent, database.toString())
        def childRepository = new ReadOnlyChildRepository()
        childRepository.remote['existing'] = child('existing')
        def context = new ChildSyncContext(new ChildBudgetSyncTarget(
            'child', 'Child', 'TOKEN', [], '', ''), childRepository, 'child-budget')
        def syncer = new ParentChildBudgetSyncer(new RuntimeConfig(sync: config(database)), config(database),
            true, database.toString(), 1, null, dryState, [context])
        def source = new SourceEntityKey('parent', SourceEntityType.TRANSACTION, 'source', null, null)
        def rerouteDelete = intent('reroute-delete', PlannedAction.DELETE, source, 'old-budget', 'old-child')
        List intents = [
            intent('create', PlannedAction.CREATE, source, 'child-budget', null),
            intent('update', PlannedAction.UPDATE, source, 'child-budget', 'existing', [], true),
            intent('delete', PlannedAction.DELETE, source, 'child-budget', 'delete-child'),
            rerouteDelete,
            intent('reroute-create', PlannedAction.CREATE, source, 'child-budget', null,
                [rerouteDelete.operationKey]),
            intent('recreate', PlannedAction.NO_OP, source, 'child-budget', 'missing-child', [], true)
        ]
        def results = [new ParentReconciliationResult(null, [], intents, false)]
        Logger logger = LoggerFactory.getLogger(ParentChildBudgetSyncer) as Logger
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        when:
        def method = ParentChildBudgetSyncer.getDeclaredMethod('reportDryRun', List, Class.forName('ynabbankofdad.sync.MovementPlanning'))
        method.accessible = true
        method.invoke(syncer, results, null)

        then:
        childRepository.lookupCalls == 2
        childRepository.mutationCalls == 0
        Files.readAllBytes(database) == before
        def messages = appender.list*.formattedMessage
        messages.any { it.contains('[DRY RUN] create child transaction') }
        messages.any { it.contains('[DRY RUN] update child transaction') }
        messages.count { it.contains('[DRY RUN] delete child transaction') } == 2
        messages.any { it.contains('[DRY RUN] recreation child transaction') }
        intents*.operationKey == ['create', 'update', 'delete', 'reroute-delete', 'reroute-create', 'recreate']

        cleanup:
        logger.detachAppender(appender)
    }

    def "dry run reports persisted retryable migration cleanup without writing migrated SQLite"() {
        given:
        Path database = tempDir.resolve('migrated-cleanup.db')
        def persistent = new SyncStateStore(database.toString())
        persistent.initialize()
        long entityId = persistent.upsertSourceEntity(new SourceEntityKey(
            'parent', SourceEntityType.TRANSACTION, 'legacy-source', null, null))
        long mirrorId = persistent.recordMirrorCreated(entityId, 'child-budget', 'outflow', 'legacy-duplicate')
        long operationId = persistent.createOperation(new ReconciliationOperationIntent(
            'legacy-cleanup', null, entityId, mirrorId, 0, ReconciliationOperationType.DELETE,
            'child-budget', 'legacy-duplicate', null, null, null))
        persistent.markOperationRetryable(operationId)
        byte[] before = Files.readAllBytes(database)
        def dryState = new DryRunSyncStateRepository(persistent, database.toString())
        def syncer = new ParentChildBudgetSyncer(new RuntimeConfig(sync: config(database)), config(database),
            true, database.toString(), 1, null, dryState, [])
        Logger logger = LoggerFactory.getLogger(ParentChildBudgetSyncer) as Logger
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        when:
        def method = ParentChildBudgetSyncer.getDeclaredMethod('reportDryRun', List,
            Class.forName('ynabbankofdad.sync.MovementPlanning'))
        method.accessible = true
        method.invoke(syncer, [], null)

        then:
        appender.list*.formattedMessage.any {
            it.contains('[DRY RUN] legacy cleanup delete child transaction legacy-duplicate from budget child-budget')
        }
        Files.readAllBytes(database) == before
        persistent.findPendingMigrationCleanupOperations()*.status == ['retryable_failed']

        cleanup:
        logger.detachAppender(appender)
    }

    private SyncConfig config(Path database) {
        new SyncConfig(new BudgetRef('Parent', 'PARENT'), [], 60,
            new SyncLoggingConfig(tempDir.resolve('sync.log').toString(), 'INFO', 1, 1),
            new SyncStateConfig(database.toString(), 45, 45))
    }

    private static PlannedReconciliationIntent intent(String key, PlannedAction action, SourceEntityKey source,
                                                       String budget, String childId,
                                                       List<String> dependencies = [], boolean verify = false) {
        new PlannedReconciliationIntent(key, 1, action, source, 'child', budget, 'outflow', 1L,
            childId, '{"account_id":"account","date":"2026-07-18","amount":-100}',
            'hash', dependencies, verify)
    }

    private static ChildTransaction child(String id) {
        new ChildTransaction(id, 'account', '2026-07-18', -100, null, null, null,
            'memo', 'cleared', false, null, false)
    }

    static class ReadOnlyChildRepository extends YnabBudgetRepository {
        Map<String, ChildTransaction> remote = [:]
        int lookupCalls
        int mutationCalls

        ReadOnlyChildRepository() { super(null) }

        @Override
        ChildTransactionLookupResult getChildTransaction(String budgetId, String transactionId) {
            lookupCalls++
            new ChildTransactionLookupResult(remote[transactionId], 1)
        }

        @Override
        def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
            mutationCalls++
            throw new AssertionError('dry run posted a transaction')
        }

        @Override
        ChildTransactionResult updateChildTransaction(String budgetId, String transactionId, Map<String, Object> fields) {
            mutationCalls++
            throw new AssertionError('dry run updated a transaction')
        }

        @Override
        ChildTransactionDeleteResult deleteChildTransaction(String budgetId, String transactionId) {
            mutationCalls++
            throw new AssertionError('dry run deleted a transaction')
        }
    }
}

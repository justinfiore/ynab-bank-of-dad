import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.*
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.*

import java.nio.file.Path

class ReconciliationPlanningIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    SyncStateStore store
    SourceRevisionNormalizer normalizer = new SourceRevisionNormalizer()
    long transactionBatchId

    def setup() {
        store = new SyncStateStore(tempDir.resolve('planning.db').toString())
        store.initialize()
        transactionBatchId = store.createIngestionBatch('transaction-delta', 'transaction_delta', 2)
    }

    def "stable ordinary split and component identities remain distinct in SQLite lineage"() {
        given:
        def ordinary = SourceRevisionNormalizer.transactionIdentity('parent', 'txn')
        def split = SourceRevisionNormalizer.subtransactionIdentity('parent', 'txn', 'sub')

        when:
        long ordinaryId = store.upsertSourceEntity(ordinary)
        long splitId = store.upsertSourceEntity(split)
        store.appendSourceRevision(ordinaryId, 'ordinary-v1', '{"amount":100}', 1, transactionBatchId)
        store.appendSourceRevision(ordinaryId, 'ordinary-v2', '{"amount":200}', 2, transactionBatchId)
        store.appendSourceRevision(splitId, 'split-v1', '{"amount":50}', 2, transactionBatchId)

        then:
        ordinaryId != splitId
        store.upsertSourceEntity(SourceRevisionNormalizer.transactionIdentity('parent', 'txn')) == ordinaryId
        store.findSourceEntities('parent', SourceEntityType.TRANSACTION) == [ordinary]
        store.findSourceEntities('parent', SourceEntityType.SUBTRANSACTION) == [split]
    }

    def "ordinary split transitions are complete deterministic and preserve unrelated mirrors"() {
        given:
        def reconciler = new ParentTransactionReconciler([context('child', 'budget', 'account', 'Spend')])
        def ordinaryRevision = revision()
        def ordinary = reconciler.reconcile(ordinaryRevision).desiredMirrors.first()
        def ordinaryMirror = persistedMirror(ordinary, 'ordinary-child')
        def splitRevision = revision(subtransactions: [sub(id: 'b', amount: -60), sub(id: 'a', amount: -40)])

        when:
        def toSplit = reconciler.reconcile(splitRevision, [ordinaryMirror])

        then:
        toSplit.intents*.action == [PlannedAction.DELETE, PlannedAction.CREATE, PlannedAction.CREATE]
        toSplit.intents*.sequence == [1, 2, 3]
        toSplit.intents.drop(1)*.source*.parentSubtransactionId as Set == ['a', 'b'] as Set
        reconciler.reconcile(splitRevision, [ordinaryMirror]).intents*.operationKey == toSplit.intents*.operationKey
        toSplit.intents.drop(1).every { it.dependsOnOperationKeys == [toSplit.intents.first().operationKey] }

        when:
        def splitMirrors = toSplit.desiredMirrors.withIndex().collect { DesiredMirror desired, int index ->
            persistedMirror(desired, "split-${index}")
        }
        def toOrdinary = reconciler.reconcile(ordinaryRevision, splitMirrors)

        then:
        toOrdinary.intents*.action == [PlannedAction.DELETE, PlannedAction.DELETE, PlannedAction.CREATE]
        toOrdinary.intents.last().dependsOnOperationKeys as Set == toOrdinary.intents.take(2)*.operationKey as Set
    }

    def "split add edit remove touches only matching components and partial fetch cannot delete"() {
        given:
        def reconciler = new ParentTransactionReconciler([context('child', 'budget', 'account', 'Spend')])
        def original = revision(subtransactions: [sub(id: 'keep', amount: -30), sub(id: 'remove', amount: -70)])
        def mirrors = reconciler.reconcile(original).desiredMirrors.withIndex().collect { DesiredMirror desired, int index ->
            persistedMirror(desired, "child-${index}")
        }

        when:
        def changed = reconciler.reconcile(
            revision(subtransactions: [sub(id: 'keep', amount: -40), sub(id: 'add', amount: -60)]), mirrors)
        def partial = normalizer.normalize('parent', transaction(subtransactions: [sub(id: 'keep')]), 3, false)
        def unsafe = reconciler.reconcile(partial, mirrors)

        then:
        changed.intents*.action.count(PlannedAction.DELETE) == 1
        changed.intents*.action.count(PlannedAction.UPDATE) == 1
        changed.intents*.action.count(PlannedAction.CREATE) == 1
        changed.intents.find { it.action == PlannedAction.DELETE }.source.parentSubtransactionId == 'remove'
        changed.intents.find { it.action == PlannedAction.UPDATE }.source.parentSubtransactionId == 'keep'
        changed.intents.find { it.action == PlannedAction.CREATE }.source.parentSubtransactionId == 'add'
        unsafe.fetchRequired
        unsafe.intents.empty
    }

    def "configuration-only cycle plans nothing and a later delta uses current routing"() {
        given:
        def current = new ParentTransactionReconciler([context('new-child', 'new-budget', 'new-account', 'Spend')])

        expect:
        current.reconcileChanged([], []).empty
        current.reconcileChanged([revision()], []).first().desiredMirrors*.targetBudgetId == ['new-budget']
    }

    def "memo-only observation persists revision and verifies mirror without financial mutation"() {
        given:
        def reconciler = new ParentTransactionReconciler([context('child', 'budget', 'account', 'Spend')])
        def oldRevision = revision(memo: 'old memo')
        def desired = reconciler.reconcile(oldRevision).desiredMirrors.first()
        def mirror = persistedMirror(desired, 'child-memo')
        long entityId = store.upsertSourceEntity(oldRevision.parentSource)
        store.appendSourceRevision(entityId, oldRevision.revisionHash, oldRevision.normalizedJson, 1,
            transactionBatchId)

        when:
        def newRevision = revision(memo: 'new memo')
        def result = reconciler.reconcile(newRevision, [mirror])
        store.appendSourceRevision(entityId, newRevision.revisionHash, newRevision.normalizedJson, 2,
            transactionBatchId)

        then:
        result.intents*.action == [PlannedAction.NO_OP]
        result.intents.first().requiresExistenceCheck
        store.upsertSourceEntity(newRevision.parentSource) == entityId
    }

    private ParentSourceRevision revision(Map overrides = [:]) {
        normalizer.normalize('parent', transaction(overrides), 2, true)
    }

    private static ParentTransactionEvent transaction(Map overrides = [:]) {
        new ParentTransactionEvent([id: 'txn', date: '2026-07-01', amount: -100, memo: 'memo',
            approved: true, serverKnowledge: 1, categoryId: 'cat', categoryName: 'Spend',
            subtransactions: [], payeeId: 'payee', payeeName: 'Payee', deleted: false] + overrides)
    }

    private static ParentSubtransactionEvent sub(Map overrides = [:]) {
        new ParentSubtransactionEvent([id: 'sub', transactionId: 'txn', amount: -100, memo: 'sub',
            categoryId: 'cat', categoryName: 'Spend', deleted: false] + overrides)
    }

    private static ChildSyncContext context(String key, String budgetId, String accountId, String... names) {
        def mapping = new ChildAccountMapping('mapping', names.collect { new ParentCategoryNameMatcher(it, false) }, 'Checking')
        def target = new ChildBudgetSyncTarget(key, key, 'TOKEN', [mapping], 'YBOD: ', '')
        def context = new ChildSyncContext(target, null, budgetId, null)
        context.cacheAccountId('Checking', accountId)
        context
    }

    private ActiveMirrorReference persistedMirror(DesiredMirror desired, String childId) {
        long entityId = store.upsertSourceEntity(desired.source)
        String direction = desired.direction ?: (desired.amount >= 0 ? 'inflow' : 'outflow')
        long mirrorId = store.recordMirrorCreated(entityId, desired.targetBudgetId, direction,
            childId, desired.targetAccountId, desired.authoritativePayloadHash)
        new ActiveMirrorReference(desired.source,
            store.findMirrorsForSource(desired.source).find { it.id == mirrorId },
            desired.targetChildKey, desired.direction, null)
    }
}

import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.*
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.*

import java.nio.file.Path
import java.time.LocalDate

class MoneyMovementReconciliationIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    SyncStateStore store
    MoneyMovementNormalizer normalizer = new MoneyMovementNormalizer()
    long movementBatchId
    Map categories = [from: new CategorySnapshot('from', 'From', 0),
                      to: new CategorySnapshot('to', 'To', 0),
                      rerouted: new CategorySnapshot('rerouted', 'Rerouted', 0)]

    def setup() {
        store = new SyncStateStore(tempDir.resolve('movements.db').toString())
        store.initialize()
        movementBatchId = store.createIngestionBatch('movement-snapshot', 'money_movement_snapshot', 2)
    }

    def "same movement ID edits retain persisted lineage and update both independent sides"() {
        given:
        def reconciler = new MoneyMovementReconciler([context('child', 'budget', 'account', 'From', 'To')], categories)
        def initial = reconciler.reconcile(snapshot(movement(amount: 100))).first()
        def mirrors = initial.desiredMirrors.collect { persist(it) }
        long entityId = store.upsertSourceEntity(initial.source)
        store.appendSourceRevision(entityId, snapshot(movement(amount: 100)).observations.first().revisionHash,
            snapshot(movement(amount: 100)).observations.first().normalizedJson, 1, movementBatchId)

        when:
        def changedObservation = snapshot(movement(amount: 250, eventDate: '2026-07-02'))
        def changed = reconciler.reconcile(changedObservation, mirrors).first()
        store.appendSourceRevision(entityId, changedObservation.observations.first().revisionHash,
            changedObservation.observations.first().normalizedJson, 2, movementBatchId)

        then:
        changed.source == initial.source
        changed.intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
        changed.intents*.direction as Set == ['inflow', 'outflow'] as Set
        store.findMirrorsForSource(initial.source)*.direction as Set == ['inflow', 'outflow'] as Set
        store.upsertSourceEntity(changed.source) == entityId
    }

    def "same-ID category reroute uses updates and ordered cross-budget replacement"() {
        given:
        def old = new MoneyMovementReconciler([context('old', 'old-budget', 'old-account', 'From', 'To')], categories)
        def initial = old.reconcile(snapshot(movement())).first()
        def mirrors = initial.desiredMirrors.collect { persist(it) }
        def changed = new MoneyMovementReconciler([
            context('old', 'old-budget', 'old-account', 'To'),
            context('new', 'new-budget', 'new-account', 'Rerouted')
        ], categories)

        when:
        def intents = changed.reconcile(snapshot(movement(fromCategoryId: 'rerouted')), mirrors).first().intents

        then:
        intents*.action.count(PlannedAction.DELETE) == 1
        intents*.action.count(PlannedAction.UPDATE) == 1
        intents*.action.count(PlannedAction.CREATE) == 1
        intents.find { it.action == PlannedAction.CREATE }.dependsOnOperationKeys ==
            intents.findAll { it.action == PlannedAction.DELETE }*.operationKey
    }

    def "old observed movement remains correctable beyond lookback and unchanged snapshots are idempotent"() {
        given:
        def reconciler = new MoneyMovementReconciler([context('child', 'budget', 'account', 'From', 'To')], categories)
        def initial = reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01'))).first()
        def mirrors = initial.desiredMirrors.collect { persist(it) }

        expect:
        reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01', amount: 200)), mirrors,
            LocalDate.parse('2026-01-01')).first().intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
        reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01')), mirrors).first().intents*.action ==
            [PlannedAction.NO_OP, PlannedAction.NO_OP]
    }

    def "snapshot absence and replacement-like IDs preserve prior mirrors non-destructively"() {
        given:
        def prior = MoneyMovementNormalizer.movementIdentity('parent', 'old')
        long entityId = store.upsertSourceEntity(prior)
        store.recordMirrorCreated(entityId, 'budget', 'outflow', 'prior-child')
        def current = normalizer.normalizeSnapshot('parent', [movement(id: 'replacement', groupId: 'shared')],
            categories, [prior], 3)

        when:
        def decisions = new MoneyMovementReconciler([context('child', 'budget', 'account', 'From', 'To')], categories)
            .reconcile(current, store.findMirrorsForSource(prior).collect {
                new ActiveMirrorReference(prior, it, 'child', it.direction, null)
            })

        then:
        decisions.find { it.source == prior }.status == MovementObservationStatus.UNCONFIRMED
        decisions.find { it.source == prior }.intents.empty
        decisions.find { it.source.moneyMovementId == 'replacement' }.intents*.action ==
            [PlannedAction.CREATE, PlannedAction.CREATE]
        store.findMirrorsForSource(prior)*.childTransactionId == ['prior-child']
    }

    private MovementSnapshotObservation snapshot(MoneyMovementEvent event) {
        normalizer.normalizeSnapshot('parent', [event], categories, [], 1)
    }

    private static MoneyMovementEvent movement(Map overrides = [:]) {
        new MoneyMovementEvent([id: 'movement', groupId: 'group', eventDate: '2026-07-01',
            fromCategoryId: 'from', toCategoryId: 'to', amount: 100] + overrides)
    }

    private static ChildSyncContext context(String key, String budgetId, String accountId, String... names) {
        def mapping = new ChildAccountMapping('mapping', names.collect { new ParentCategoryNameMatcher(it, false) }, 'Checking')
        def target = new ChildBudgetSyncTarget(key, key, 'TOKEN', [mapping], '', '')
        def context = new ChildSyncContext(target, null, budgetId, null)
        context.cacheAccountId('Checking', accountId)
        context
    }

    private ActiveMirrorReference persist(DesiredMirror desired) {
        long entityId = store.upsertSourceEntity(desired.source)
        long mirrorId = store.recordMirrorCreated(entityId, desired.targetBudgetId, desired.direction,
            "child-${desired.direction}", desired.targetAccountId, desired.authoritativePayloadHash)
        new ActiveMirrorReference(desired.source,
            store.findMirrorsForSource(desired.source).find { it.id == mirrorId },
            desired.targetChildKey, desired.direction, null)
    }
}

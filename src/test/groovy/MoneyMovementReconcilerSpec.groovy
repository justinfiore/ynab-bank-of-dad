import spock.lang.Specification
import ynabbankofdad.config.ChildAccountMapping
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.config.ParentCategoryNameMatcher
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.MoneyMovementEvent
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.ChildMirrorState

import java.time.LocalDate

class MoneyMovementReconcilerSpec extends Specification {
    def categories = [from: new CategorySnapshot('from', 'From', 0),
                      to: new CategorySnapshot('to', 'To', 0),
                      newFrom: new CategorySnapshot('newFrom', 'New From', 0)]
    def normalizer = new MoneyMovementNormalizer()

    def "direction distinguishes same-child sides"() {
        given:
        def reconciler = reconciler(context('child', 'budget', 'account', 'From', 'To'))

        when:
        def decision = reconciler.reconcile(snapshot(movement())).first()

        then:
        decision.desiredMirrors*.direction as Set == ['inflow', 'outflow'] as Set
        decision.intents*.operationKey.toSet().size() == 2
    }

    def "zero amount sides retain distinct logical directions in operation payloads"() {
        given:
        def reconciler = reconciler(context('child', 'budget', 'account', 'From', 'To'))

        when:
        def decision = reconciler.reconcile(snapshot(movement(amount: 0))).first()

        then:
        decision.desiredMirrors*.amount == [0, 0]
        decision.intents*.direction as Set == ['inflow', 'outflow'] as Set
        decision.intents*.payloadJson*.contains('"_reconciliation_direction"') == [true, true]
        decision.intents*.payloadJson.any { it.contains('"_reconciliation_direction":"outflow"') }
    }

    def "amount edit plans side updates"() {
        given:
        def reconciler = reconciler(context('child', 'budget', 'account', 'From', 'To'))
        def initial = reconciler.reconcile(snapshot(movement(amount: 100))).first().desiredMirrors
        def existing = initial.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        when:
        def decision = reconciler.reconcile(snapshot(movement(amount: 250)), existing).first()

        then:
        decision.intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
        decision.intents*.payloadJson.every { it.contains('"memo":"From From to To"') }
    }

    def "category edit compares complete side set"() {
        given:
        def oldReconciler = reconciler(context('old', 'old-budget', 'old-account', 'From', 'To'))
        def initial = oldReconciler.reconcile(snapshot(movement())).first().desiredMirrors
        def existing = initial.withIndex().collect { desired, index -> mirror(desired, index + 1) }
        def changedReconciler = reconciler(
            context('old', 'old-budget', 'old-account', 'To'),
            context('new', 'new-budget', 'new-account', 'New From'))

        when:
        def intents = changedReconciler.reconcile(snapshot(movement(fromCategoryId: 'newFrom')), existing).first().intents

        then:
        intents*.action.count(PlannedAction.DELETE) == 1
        intents*.action.count(PlannedAction.UPDATE) == 1
        intents*.action.count(PlannedAction.CREATE) == 1
        intents.find { it.action == PlannedAction.CREATE }.dependsOnOperationKeys ==
            intents.findAll { it.action == PlannedAction.DELETE }*.operationKey
    }

    def "existing mirror bypasses new-movement lookback"() {
        given:
        def reconciler = reconciler(context('child', 'budget', 'account', 'From', 'To'))
        def initial = reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01'))).first().desiredMirrors
        def existing = initial.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        when:
        def decision = reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01', amount: 200)),
            existing, LocalDate.parse('2026-01-01')).first()

        then:
        decision.intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
    }

    def "new movement outside lookback creates no mirrors"() {
        given:
        def reconciler = reconciler(context('child', 'budget', 'account', 'From', 'To'))

        when:
        def decision = reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01')), [],
            LocalDate.parse('2026-01-01')).first()

        then:
        decision.desiredMirrors.empty
        decision.intents.empty
        decision.reason.contains('lookback')
    }

    def "absent ID becomes unconfirmed without delete"() {
        given:
        def source = MoneyMovementNormalizer.movementIdentity('parent', 'missing')
        def snapshot = normalizer.normalizeSnapshot('parent', [], categories, [source], 2)

        when:
        def decision = reconciler(context('child', 'budget', 'account', 'From', 'To'))
            .reconcile(snapshot, []).first()

        then:
        decision.status == MovementObservationStatus.UNCONFIRMED
        decision.intents.empty
    }

    def "similar replacement and shared group remain independent"() {
        given:
        def oldSource = MoneyMovementNormalizer.movementIdentity('parent', 'old')
        def current = snapshot(movement(id: 'replacement', groupId: 'same'))
        def withMissing = new MovementSnapshotObservation(current.observations, [oldSource] as Set, 1)

        when:
        def decisions = reconciler(context('child', 'budget', 'account', 'From', 'To')).reconcile(withMissing)

        then:
        decisions*.source*.moneyMovementId as Set == ['old', 'replacement'] as Set
        decisions.find { it.source.moneyMovementId == 'old' }.intents.empty
        decisions.find { it.source.moneyMovementId == 'replacement' }.intents*.action ==
            [PlannedAction.CREATE, PlannedAction.CREATE]
    }

    def "unchanged snapshot plans no operation beyond verification"() {
        given:
        def reconciler = reconciler(context('child', 'budget', 'account', 'From', 'To'))
        def first = reconciler.reconcile(snapshot(movement())).first()
        def existing = first.desiredMirrors.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        expect:
        reconciler.reconcile(snapshot(movement()), existing).first().intents*.action ==
            [PlannedAction.NO_OP, PlannedAction.NO_OP]
    }

    private MovementSnapshotObservation snapshot(MoneyMovementEvent event) {
        normalizer.normalizeSnapshot('parent', [event], categories, [], 1)
    }

    private MoneyMovementReconciler reconciler(ChildSyncContext... contexts) {
        new MoneyMovementReconciler(contexts as List, categories)
    }

    private static MoneyMovementEvent movement(Map overrides = [:]) {
        new MoneyMovementEvent([id: 'movement', groupId: 'group', eventDate: '2026-07-01',
            fromCategoryId: 'from', toCategoryId: 'to', amount: 100] + overrides)
    }

    private static ChildSyncContext context(String key, String budgetId, String accountId, String... names) {
        def mapping = new ChildAccountMapping('mapping', names.collect { new ParentCategoryNameMatcher(it, false) }, 'Checking')
        def target = new ChildBudgetSyncTarget(childKey: key, budgetName: key, tokenEnvVarName: 'TOKEN',
            accountMappings: [mapping], memoPrefix: '', memoSuffix: '')
        def context = new ChildSyncContext(target, null, budgetId, null)
        context.cacheAccountId('Checking', accountId)
        context
    }

    private static ActiveMirrorReference mirror(DesiredMirror desired, long id) {
        def state = new ChildMirrorState(id, id, desired.targetBudgetId, desired.direction,
            "child-${id}", desired.targetAccountId, desired.authoritativePayloadHash, 'active', 'now', null)
        new ActiveMirrorReference(desired.source, state, desired.targetChildKey, desired.direction, null)
    }
}

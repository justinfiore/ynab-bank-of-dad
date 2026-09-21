import spock.lang.Specification
import ynabbankofdad.config.ChildAccountMapping
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.config.ParentCategoryNameMatcher
import ynabbankofdad.model.AccountSnapshot
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
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-a', To: 'account-a'
        ], false))

        when:
        def decision = reconciler.reconcile(snapshot(movement())).first()

        then:
        decision.desiredMirrors*.direction as Set == ['inflow', 'outflow'] as Set
        decision.intents*.operationKey.toSet().size() == 2
        decision.desiredMirrors.every { !it.authoritativePayloadJson.contains('payee_id":"tp-') }
    }

    def "same-child different accounts collapse to one outflow transfer"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ]))

        when:
        def decision = reconciler.reconcile(snapshot(movement())).first()

        then:
        decision.desiredMirrors.size() == 1
        decision.desiredMirrors.first().direction == 'outflow'
        decision.desiredMirrors.first().targetAccountId == 'account-from'
        decision.desiredMirrors.first().amount == -100
        decision.desiredMirrors.first().payeeId == 'tp-account-to'
        decision.desiredMirrors.first().authoritativePayloadJson.contains('"payee_id":"tp-account-to"')
        decision.desiredMirrors.first().authoritativePayloadJson.contains(
            "\"${DesiredMirrorFactory.TRANSFER_DESTINATION_ACCOUNT_FIELD}\":\"account-to\"")
        decision.intents*.action == [PlannedAction.CREATE]
        decision.intents.first().payloadJson.contains('"_reconciliation_direction":"outflow"')
        decision.intents.first().payloadJson.contains('"payee_id":"tp-account-to"')
        !decision.intents.first().payloadJson.contains('"payee_name"') ||
            decision.intents.first().payloadJson.contains('"payee_name":null')
    }

    def "missing transfer_payee_id falls back to dual normal transactions"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ], false))

        when:
        def decision = reconciler.reconcile(snapshot(movement())).first()

        then:
        decision.desiredMirrors*.direction as Set == ['inflow', 'outflow'] as Set
        decision.intents*.action == [PlannedAction.CREATE, PlannedAction.CREATE]
    }

    def "cross-child movement stays dual independent transactions"() {
        given:
        def reconciler = reconciler(
            context('from-child', 'budget-from', [From: 'account-from']),
            context('to-child', 'budget-to', [To: 'account-to']))

        when:
        def decision = reconciler.reconcile(snapshot(movement())).first()

        then:
        decision.desiredMirrors*.direction as Set == ['inflow', 'outflow'] as Set
        decision.desiredMirrors*.targetBudgetId as Set == ['budget-from', 'budget-to'] as Set
        decision.intents*.action == [PlannedAction.CREATE, PlannedAction.CREATE]
    }

    def "grandfathered same-child mirrors stay dual and omit payee from authoritative state"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ]))
        def dual = dualMirrorsForTest(reconciler)
        def existing = dual.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        when:
        def decision = reconciler.reconcile(snapshot(movement(amount: 250)), existing).first()

        then:
        decision.desiredMirrors*.direction as Set == ['inflow', 'outflow'] as Set
        decision.desiredMirrors.every {
            !it.authoritativePayloadJson.contains('payee_name') &&
                !it.authoritativePayloadJson.contains('"payee_id"')
        }
        decision.intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
        decision.intents.every {
            !it.payloadJson.contains('payee_name') && !it.payloadJson.contains('"payee_id"')
        }
    }

    def "zero amount sides retain distinct logical directions in operation payloads"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-a', To: 'account-a'
        ], false))

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
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ]))
        def dual = dualMirrorsForTest(reconciler)
        def existing = dual.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        when:
        def decision = reconciler.reconcile(snapshot(movement(amount: 250)), existing).first()

        then:
        decision.intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
        decision.intents*.payloadJson.every { it.contains('"memo":"From From to To"') }
    }

    def "category edit compares complete side set"() {
        given:
        // Same-account dual so both sides stay on one child without transfer collapse.
        def oldReconciler = reconciler(context('old', 'old-budget', [From: 'old-account', To: 'old-account'], false))
        def initial = oldReconciler.reconcile(snapshot(movement())).first().desiredMirrors
        def existing = initial.withIndex().collect { desired, index -> mirror(desired, index + 1) }
        def changedReconciler = reconciler(
            context('old', 'old-budget', [To: 'old-account'], false),
            context('new', 'new-budget', ['New From': 'new-account'], false))

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
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ]))
        def dual = dualMirrorsForTest(reconciler)
        def existing = dual.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        when:
        def decision = reconciler.reconcile(snapshot(movement(eventDate: '2020-01-01', amount: 200)),
            existing, LocalDate.parse('2026-01-01')).first()

        then:
        decision.intents*.action == [PlannedAction.UPDATE, PlannedAction.UPDATE]
    }

    def "new movement outside lookback creates no mirrors"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ]))

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
        def decision = reconciler(context('child', 'budget', [From: 'a', To: 'b']))
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
        def decisions = reconciler(context('child', 'budget', [
            From: 'account-a', To: 'account-a'
        ], false)).reconcile(withMissing)

        then:
        decisions*.source*.moneyMovementId as Set == ['old', 'replacement'] as Set
        decisions.find { it.source.moneyMovementId == 'old' }.intents.empty
        decisions.find { it.source.moneyMovementId == 'replacement' }.intents*.action ==
            [PlannedAction.CREATE, PlannedAction.CREATE]
    }

    def "unchanged snapshot plans no operation beyond verification"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-a', To: 'account-a'
        ], false))
        def first = reconciler.reconcile(snapshot(movement())).first()
        def existing = first.desiredMirrors.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        expect:
        reconciler.reconcile(snapshot(movement()), existing).first().intents*.action ==
            [PlannedAction.NO_OP, PlannedAction.NO_OP]
    }

    def "posted transfer stays one outflow mirror on re-observe and amount edit"() {
        given:
        def reconciler = reconciler(context('child', 'budget', [
            From: 'account-from', To: 'account-to'
        ]))
        def first = reconciler.reconcile(snapshot(movement())).first()
        def existing = first.desiredMirrors.withIndex().collect { desired, index -> mirror(desired, index + 1) }

        when:
        def unchanged = reconciler.reconcile(snapshot(movement()), existing).first()
        def amountEdit = reconciler.reconcile(snapshot(movement(amount: 250)), existing).first()

        then:
        unchanged.desiredMirrors.size() == 1
        unchanged.desiredMirrors.first().direction == 'outflow'
        unchanged.desiredMirrors.first().payeeId == 'tp-account-to'
        unchanged.intents*.action == [PlannedAction.NO_OP]
        amountEdit.desiredMirrors.size() == 1
        amountEdit.desiredMirrors.first().amount == -250
        amountEdit.intents*.action == [PlannedAction.UPDATE]
        amountEdit.intents.first().payloadJson.contains('"payee_id":"tp-account-to"')
    }

    private MovementSnapshotObservation snapshot(MoneyMovementEvent event) {
        normalizer.normalizeSnapshot('parent', [event], categories, [], 1)
    }

    private MoneyMovementReconciler reconciler(ChildSyncContext... contexts) {
        new MoneyMovementReconciler(contexts as List, categories)
    }

    /** Dual inflow/outflow mirrors for a same-child different-account movement (pre-transfer shape). */
    private List<DesiredMirror> dualMirrorsForTest(MoneyMovementReconciler ignored) {
        def factory = new DesiredMirrorFactory(
            [context('child', 'budget', [From: 'account-from', To: 'account-to'])], categories)
        String memo = 'From From to To'
        def source = MoneyMovementNormalizer.movementIdentity('parent', 'movement')
        [
            factory.forSource(source, 'to', 'To', '2026-07-01', 100, null, 'From From', memo, 'inflow').first(),
            factory.forSource(source, 'from', 'From', '2026-07-01', -100, null, 'To To', memo, 'outflow').first()
        ]
    }

    private static MoneyMovementEvent movement(Map overrides = [:]) {
        new MoneyMovementEvent([id: 'movement', groupId: 'group', eventDate: '2026-07-01',
            fromCategoryId: 'from', toCategoryId: 'to', amount: 100] + overrides)
    }

    /**
     * @param accountByCategory parent category name → child account id
     */
    private static ChildSyncContext context(String key, String budgetId, Map<String, String> accountByCategory,
                                            boolean withTransferPayee = true) {
        List mappings = accountByCategory.collect { String catName, String accountId ->
            String accountName = "Acct-${accountId}"
            new ChildAccountMapping("map-${catName}", [new ParentCategoryNameMatcher(catName, false)], accountName)
        }
        def target = new ChildBudgetSyncTarget(childKey: key, budgetName: key, tokenEnvVarName: 'TOKEN',
            accountMappings: mappings, memoPrefix: '', memoSuffix: '')
        def context = new ChildSyncContext(target, null, budgetId, null)
        accountByCategory.each { String catName, String accountId ->
            String accountName = "Acct-${accountId}"
            context.cacheAccountSnapshot(new AccountSnapshot(
                accountId, accountName, 0,
                withTransferPayee ? "tp-${accountId}" : null,
                true))
        }
        context
    }

    private static ActiveMirrorReference mirror(DesiredMirror desired, long id) {
        def state = new ChildMirrorState(id, id, desired.targetBudgetId, desired.direction,
            "child-${id}", desired.targetAccountId, desired.authoritativePayloadHash, 'active', 'now', null)
        new ActiveMirrorReference(desired.source, state, desired.targetChildKey, desired.direction, null)
    }
}

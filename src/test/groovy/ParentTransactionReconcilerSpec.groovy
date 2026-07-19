import spock.lang.Specification
import ynabbankofdad.config.ChildAccountMapping
import ynabbankofdad.config.ChildBudgetSyncTarget
import ynabbankofdad.config.ParentCategoryNameMatcher
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.ChildTransaction
import ynabbankofdad.sync.model.ParentSubtransactionEvent
import ynabbankofdad.sync.model.ParentTransactionEvent
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.ChildMirrorState

class ParentTransactionReconcilerSpec extends Specification {
    def normalizer = new SourceRevisionNormalizer()

    def "authoritative changes plan one update with recreation payload"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def oldDesired = reconciler.reconcile(revision(amount: -100)).desiredMirrors.first()
        def existing = mirror(oldDesired)

        when:
        def result = reconciler.reconcile(revision(amount: -250, date: '2026-07-02', payeeName: 'New'), [existing])

        then:
        result.intents*.action == [PlannedAction.UPDATE]
        def payload = result.intents.first().payloadJson
        payload.contains('"amount":-250')
        payload.contains('"date":"2026-07-02"')
        payload.contains('"payee_name":"New"')
        payload.contains('"cleared":"cleared"')
        payload.contains('"approved":false')
        payload.contains('"memo":"YBOD: memo"')
        result.intents.first().requiresExistenceCheck
    }

    def "same-budget reroute plans update"() {
        given:
        def oldReconciler = reconciler(context('child', 'budget-1', 'acct-old', 'Spend'))
        def old = oldReconciler.reconcile(revision()).desiredMirrors.first()
        def newReconciler = reconciler(context('child', 'budget-1', 'acct-new', 'Spend'))

        when:
        def result = newReconciler.reconcile(revision(), [mirror(old)])

        then:
        result.intents*.action == [PlannedAction.UPDATE]
        result.intents.first().payloadJson.contains('"account_id":"acct-new"')
    }

    def "observed approval drift plans update"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def wanted = reconciler.reconcile(revision()).desiredMirrors.first()
        def child = new ChildTransaction('child-txn', 'acct-1', '2026-07-01', -100,
            'payee', 'Payee', null, 'child memo', 'uncleared', true, null, false)

        expect:
        reconciler.reconcile(revision(), [mirror(wanted, child)]).intents*.action == [PlannedAction.UPDATE]
    }

    def "memo-only edit plans no financial mutation and requires verification"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def wanted = reconciler.reconcile(revision(memo: 'old')).desiredMirrors.first()

        when:
        def result = reconciler.reconcile(revision(memo: 'new'), [mirror(wanted)])

        then:
        result.intents*.action == [PlannedAction.NO_OP]
        result.intents.first().requiresExistenceCheck
        result.intents.first().payloadJson.contains('"memo":"YBOD: new"')
    }

    def "tombstone unapproval and mapped-to-unmapped plan deletion"() {
        given:
        def mapped = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def wanted = mapped.reconcile(revision()).desiredMirrors.first()
        def existing = [mirror(wanted)]

        expect:
        mapped.reconcile(revision(deleted: true), existing).intents*.action == [PlannedAction.DELETE]
        mapped.reconcile(revision(approved: false), existing).intents*.action == [PlannedAction.DELETE]
        mapped.reconcile(revision(categoryName: 'Unmapped'), existing).intents*.action == [PlannedAction.DELETE]
    }

    def "cross-budget reroute creates dependency chain"() {
        given:
        def oldPlanner = reconciler(context('old', 'budget-old', 'acct-old', 'Spend'))
        def old = oldPlanner.reconcile(revision()).desiredMirrors.first()
        def newPlanner = reconciler(context('new', 'budget-new', 'acct-new', 'Spend'))

        when:
        def intents = newPlanner.reconcile(revision(), [mirror(old)]).intents

        then:
        intents*.action == [PlannedAction.DELETE, PlannedAction.CREATE]
        intents[1].dependsOnOperationKeys == [intents[0].operationKey]
        intents*.sequence == [1, 2]
    }

    def "ordinary-to-split orders delete then creates"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def ordinary = reconciler.reconcile(revision()).desiredMirrors.first()
        def splitRevision = revision(subtransactions: [sub(id: 'one', amount: -40), sub(id: 'two', amount: -60)])

        when:
        def intents = reconciler.reconcile(splitRevision, [mirror(ordinary)]).intents

        then:
        intents*.action == [PlannedAction.DELETE, PlannedAction.CREATE, PlannedAction.CREATE]
        intents[1].dependsOnOperationKeys == [intents[0].operationKey]
        intents[2].dependsOnOperationKeys == [intents[0].operationKey]
    }

    def "split-to-ordinary orders deletes then create"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def split = revision(subtransactions: [sub(id: 'one'), sub(id: 'two')])
        def mirrors = reconciler.reconcile(split).desiredMirrors.collect { mirror(it) }

        when:
        def intents = reconciler.reconcile(revision(), mirrors).intents

        then:
        intents*.action == [PlannedAction.DELETE, PlannedAction.DELETE, PlannedAction.CREATE]
        intents.last().dependsOnOperationKeys as Set == intents.take(2)*.operationKey as Set
    }

    def "split add edit and removal touch only matching components"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def oldRevision = revision(subtransactions: [sub(id: 'keep', amount: -30), sub(id: 'remove', amount: -70)])
        def existing = reconciler.reconcile(oldRevision).desiredMirrors.collect { mirror(it) }
        def changed = revision(subtransactions: [sub(id: 'keep', amount: -40), sub(id: 'add', amount: -60)])

        when:
        def intents = reconciler.reconcile(changed, existing).intents

        then:
        intents*.action.count(PlannedAction.DELETE) == 1
        intents*.action.count(PlannedAction.UPDATE) == 1
        intents*.action.count(PlannedAction.CREATE) == 1
    }

    def "split mirrors use each component payee instead of the parent payee"() {
        given:
        def split = revision(subtransactions: [
            sub(id: 'one', payeeId: 'payee-one', payeeName: 'First Store'),
            sub(id: 'two', payeeId: 'payee-two', payeeName: 'Second Store')])

        when:
        def desired = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
            .reconcile(split).desiredMirrors.toList().sort { it.source.parentSubtransactionId }

        then:
        desired*.payeeId == ['payee-one', 'payee-two']
        desired*.payeeName == ['First Store', 'Second Store']
        desired*.authoritativePayloadJson.every { !it.contains('"payee_name":"Parent Payee"') }
    }

    def "category rename with unchanged route and repeated delta are no-ops"() {
        given:
        def child = context('child', 'budget-1', 'acct-1', 'Spend', 'Renamed')
        def reconciler = reconciler(child)
        def original = reconciler.reconcile(revision(categoryName: 'Spend')).desiredMirrors.first()

        expect:
        reconciler.reconcile(revision(categoryName: 'Renamed'), [mirror(original)]).intents*.action == [PlannedAction.NO_OP]
        reconciler.reconcile(revision(categoryName: 'Spend'), [mirror(original)]).intents*.action == [PlannedAction.NO_OP]
    }

    def "partial split requires full fetch and cannot plan removals"() {
        given:
        def reconciler = reconciler(context('child', 'budget-1', 'acct-1', 'Spend'))
        def partial = normalizer.normalize('parent', transaction(subtransactions: [sub()]), 2, false)

        expect:
        reconciler.reconcile(partial, []).fetchRequired
        reconciler.reconcile(partial, []).intents.empty
    }

    def "config-only cycle plans no historical work and later delta uses current mapping"() {
        given:
        def current = reconciler(context('new', 'budget-new', 'acct-new', 'Spend'))

        expect:
        current.reconcileChanged([], []).empty
        current.reconcileChanged([revision()], []).first().desiredMirrors*.targetBudgetId == ['budget-new']
    }

    def "create uses configured memo decoration while update state excludes memo"() {
        given:
        def mapping = new ChildAccountMapping('mapping', [new ParentCategoryNameMatcher('Spend', false)], 'Checking')
        def target = new ChildBudgetSyncTarget('child', 'child', 'TOKEN', [mapping], '[Kid] ', ' (sync)')
        def child = new ChildSyncContext(target, null, 'budget-1', null)
        child.cacheAccountId('Checking', 'acct-1')

        when:
        def result = reconciler(child).reconcile(revision(memo: 'source memo'))

        then:
        result.intents.first().payloadJson.contains('"memo":"[Kid] source memo (sync)"')
        !result.desiredMirrors.first().authoritativePayloadJson.contains('memo')
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

    private static ParentTransactionReconciler reconciler(ChildSyncContext... contexts) {
        new ParentTransactionReconciler(contexts as List)
    }

    private static ChildSyncContext context(String key, String budgetId, String accountId, String... names) {
        def mapping = new ChildAccountMapping('mapping', names.collect { new ParentCategoryNameMatcher(it, false) }, 'Checking')
        def target = new ChildBudgetSyncTarget(key, key, 'TOKEN', [mapping], 'YBOD: ', '')
        def context = new ChildSyncContext(target, null, budgetId, null)
        context.cacheAccountId('Checking', accountId)
        context
    }

    private static ActiveMirrorReference mirror(DesiredMirror desired, ChildTransaction child = null) {
        def state = new ChildMirrorState(1L, 1L, desired.targetBudgetId, desired.direction,
            'child-txn', desired.targetAccountId, desired.authoritativePayloadHash,
            'active', 'now', null)
        new ActiveMirrorReference(desired.source, state, desired.targetChildKey, desired.direction, child)
    }
}

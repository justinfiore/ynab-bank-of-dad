import spock.lang.Specification
import ynabbankofdad.sync.model.ParentSubtransactionEvent
import ynabbankofdad.sync.model.ParentTransactionEvent
import ynabbankofdad.sync.reconcile.CompositionSafety
import ynabbankofdad.sync.reconcile.SourceRevisionNormalizer
import ynabbankofdad.sync.state.SourceEntityType

class SourceRevisionNormalizerSpec extends Specification {
    def normalizer = new SourceRevisionNormalizer()

    def "top-level identity excludes mutable fields"() {
        given:
        def first = normalizer.normalize('budget', transaction(amount: -100, categoryName: 'Old', memo: 'one'))
        def second = normalizer.normalize('budget', transaction(amount: -999, categoryName: 'New', memo: 'two',
            date: '2026-07-18', payeeName: 'Changed', approved: false, deleted: true))

        expect:
        first.parentSource == second.parentSource
        first.parentSource.type == SourceEntityType.TRANSACTION
        first.revisionHash != second.revisionHash
    }

    def "split identity excludes mutable fields"() {
        given:
        def first = normalizer.normalize('budget', transaction(subtransactions: [sub(amount: -100, categoryName: 'Old')]))
        def second = normalizer.normalize('budget', transaction(subtransactions: [sub(amount: -200, categoryName: 'New', memo: 'Changed')]))

        expect:
        first.components.first().source == second.components.first().source
        first.components.first().source.type == SourceEntityType.SUBTRANSACTION
        first.revisionHash != second.revisionHash
    }

    def "split component preserves its own payee identity"() {
        when:
        def revision = normalizer.normalize('budget', transaction(subtransactions: [
            sub(payeeId: 'split-payee', payeeName: 'Split Store')]))

        then:
        revision.components.first().payeeId == 'split-payee'
        revision.components.first().payeeName == 'Split Store'
        revision.normalizedJson.contains('"payeeName":"Split Store"')
    }

    def "ordinary and split identities do not collide"() {
        expect:
        SourceRevisionNormalizer.transactionIdentity('budget', 'txn') !=
            SourceRevisionNormalizer.subtransactionIdentity('budget', 'txn', 'txn')
    }

    def "deletion normalizes as tombstone with server knowledge"() {
        when:
        def revision = normalizer.normalize('budget', transaction(deleted: true, approved: false), 72)

        then:
        revision.deleted
        !revision.approved
        revision.serverKnowledge == 72
        revision.normalizedJson.contains('"deleted":true')
        revision.revisionHash.size() == 64
    }

    def "equivalent complete revisions have deterministic hashes"() {
        given:
        def first = transaction(subtransactions: [sub(id: 'b'), sub(id: 'a')])
        def reordered = transaction(subtransactions: [sub(id: 'a'), sub(id: 'b')])

        expect:
        normalizer.normalize('budget', first, 9).revisionHash == normalizer.normalize('budget', reordered, 9).revisionHash
    }

    def "server knowledge is revision metadata rather than semantic hash input"() {
        when:
        def first = normalizer.normalize('budget', transaction(), 9)
        def second = normalizer.normalize('budget', transaction(), 10)

        then:
        first.serverKnowledge == 9
        second.serverKnowledge == 10
        first.revisionHash == second.revisionHash
        !first.normalizedJson.contains('serverKnowledge')
    }

    def "partial split marks complete fetch required"() {
        when:
        def revision = normalizer.normalize('budget', transaction(subtransactions: [sub()]), 8, false)

        then:
        revision.compositionSafety == CompositionSafety.FETCH_REQUIRED
        revision.requiresCompleteFetch()
    }

    def "empty partial composition also requires fetch before assuming ordinary"() {
        expect:
        normalizer.normalize('budget', transaction(), 8, false).requiresCompleteFetch()
    }

    private static ParentTransactionEvent transaction(Map overrides = [:]) {
        new ParentTransactionEvent([
            id: 'txn', date: '2026-07-01', amount: -100, memo: 'memo', approved: true,
            serverKnowledge: 1, categoryId: 'cat', categoryName: 'Spend', subtransactions: [],
            payeeId: 'payee', payeeName: 'Payee', deleted: false
        ] + overrides)
    }

    private static ParentSubtransactionEvent sub(Map overrides = [:]) {
        new ParentSubtransactionEvent([
            id: 'sub', transactionId: 'txn', amount: -100, memo: 'sub memo',
            categoryId: 'cat', categoryName: 'Spend', deleted: false
        ] + overrides)
    }
}

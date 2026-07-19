import spock.lang.Specification
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.MoneyMovementEvent
import ynabbankofdad.sync.reconcile.MoneyMovementNormalizer
import ynabbankofdad.sync.state.SourceEntityType

class MoneyMovementNormalizerSpec extends Specification {
    def normalizer = new MoneyMovementNormalizer()

    def "movement identity excludes mutable fields"() {
        given:
        def first = snapshot(movement(amount: 100, groupId: 'one'))
        def second = snapshot(movement(amount: 999, groupId: 'two', eventDate: '2026-07-18',
            fromCategoryId: 'other'))

        expect:
        first.observations.first().source == second.observations.first().source
        first.observations.first().source.type == SourceEntityType.MONEY_MOVEMENT
        first.observations.first().revisionHash != second.observations.first().revisionHash
    }

    def "complete observations include names knowledge and deterministic hash"() {
        when:
        def first = snapshot(movement())
        def second = snapshot(movement())

        then:
        first.observations.first().fromCategoryName == 'From'
        first.observations.first().toCategoryName == 'To'
        first.observations.first().serverKnowledge == 44
        first.observations.first().revisionHash == second.observations.first().revisionHash
    }

    def "snapshot knowledge is metadata and does not grow semantic revisions"() {
        when:
        def first = normalizer.normalizeSnapshot('parent', [movement()], [
            from: new CategorySnapshot('from', 'From', 0), to: new CategorySnapshot('to', 'To', 0)], [], 44)
        def second = normalizer.normalizeSnapshot('parent', [movement()], [
            from: new CategorySnapshot('from', 'From', 0), to: new CategorySnapshot('to', 'To', 0)], [], 45)

        then:
        first.serverKnowledge == 44
        second.serverKnowledge == 45
        first.observations.first().serverKnowledge == 44
        second.observations.first().serverKnowledge == 45
        first.observations.first().revisionHash == second.observations.first().revisionHash
        !first.observations.first().normalizedJson.contains('serverKnowledge')
    }

    def "absent prior movement becomes unconfirmed"() {
        given:
        def prior = MoneyMovementNormalizer.movementIdentity('parent', 'missing')

        expect:
        normalizer.normalizeSnapshot('parent', [], [:], [prior], 45).unconfirmedSources == [prior] as Set
    }

    def "different movement ids and group ids never establish identity"() {
        expect:
        MoneyMovementNormalizer.movementIdentity('parent', 'old') !=
            MoneyMovementNormalizer.movementIdentity('parent', 'replacement')
    }

    private def snapshot(MoneyMovementEvent event) {
        normalizer.normalizeSnapshot('parent', [event], [from: new CategorySnapshot('from', 'From', 0),
            to: new CategorySnapshot('to', 'To', 0)], [], 44)
    }

    private static MoneyMovementEvent movement(Map overrides = [:]) {
        new MoneyMovementEvent([id: 'movement', groupId: 'group', eventDate: '2026-07-01',
            fromCategoryId: 'from', toCategoryId: 'to', amount: 100] + overrides)
    }
}

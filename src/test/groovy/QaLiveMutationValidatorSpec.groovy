import spock.lang.Specification
import ynabbankofdad.qa.QaBudgetIdentity
import ynabbankofdad.qa.QaExpectedMutation
import ynabbankofdad.qa.QaLiveMutationValidator

class QaLiveMutationValidatorSpec extends Specification {

    private static final QaBudgetIdentity JORSTEN =
        new QaBudgetIdentity("Jorsten's Plan", '10000000-0000-0000-0000-000000000001')
    private static final QaBudgetIdentity JORSTEN_JR =
        new QaBudgetIdentity("Jorsten Jr's Plan", '10000000-0000-0000-0000-000000000002')
    private static final List<QaBudgetIdentity> ALLOWLIST = [JORSTEN, JORSTEN_JR]
    private static final List<QaExpectedMutation> MANIFEST = [
        new QaExpectedMutation('update', JORSTEN_JR, 'dummy-transaction-id')
    ]

    def "requires the exact live mutation confirmation"() {
        when:
        QaLiveMutationValidator.validate(environment, MANIFEST, ALLOWLIST)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('QA_CONFIRM_LIVE_MUTATIONS must equal YES')

        where:
        environment << [[:], [QA_CONFIRM_LIVE_MUTATIONS: 'yes'], [QA_CONFIRM_LIVE_MUTATIONS: 'YES ']]
    }

    def "requires a non-empty expected mutation manifest"() {
        when:
        QaLiveMutationValidator.validate([QA_CONFIRM_LIVE_MUTATIONS: 'YES'], manifest, ALLOWLIST)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('Expected mutation manifest is required')

        where:
        manifest << [null, []]
    }

    def "rejects an unexpected write target outside the exact allowlist"() {
        given:
        def unexpected = new QaExpectedMutation(
            'create',
            new QaBudgetIdentity("Thorsten's Plan", '10000000-0000-0000-0000-000000000004'),
            'dummy-account-id'
        )

        when:
        QaLiveMutationValidator.validate([QA_CONFIRM_LIVE_MUTATIONS: 'YES'], [unexpected], ALLOWLIST)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('not an allowed QA budget')
    }

    def "accepts a confirmed non-empty manifest whose targets all match the allowlist"() {
        expect:
        QaLiveMutationValidator.validate(
            [QA_CONFIRM_LIVE_MUTATIONS: 'YES'],
            [
                new QaExpectedMutation('create', JORSTEN_JR, 'dummy-account-id'),
                new QaExpectedMutation('delete', JORSTEN, 'dummy-transaction-id')
            ],
            ALLOWLIST
        )*.operation == ['create', 'delete']
    }
}

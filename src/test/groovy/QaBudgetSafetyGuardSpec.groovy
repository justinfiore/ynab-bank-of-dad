import spock.lang.Specification
import ynabbankofdad.qa.QaBudgetIdentity
import ynabbankofdad.qa.QaBudgetSafetyGuard

class QaBudgetSafetyGuardSpec extends Specification {

    private static final List<QaBudgetIdentity> ALLOWLIST = [
        new QaBudgetIdentity("Jorsten's Plan", '00000000-0000-0000-0000-000000000001'),
        new QaBudgetIdentity("Jorsten Jr's Plan", '00000000-0000-0000-0000-000000000002'),
        new QaBudgetIdentity("Borsten's Plan", '00000000-0000-0000-0000-000000000003'),
        new QaBudgetIdentity("Thorsten's Plan", '00000000-0000-0000-0000-000000000004')
    ]

    def "accepts every configured QA budget only when display name and full ID both match exactly"() {
        expect:
        QaBudgetSafetyGuard.requireAllowed(new QaBudgetIdentity(displayName, fullId), ALLOWLIST) ==
            new QaBudgetIdentity(displayName, fullId)

        where:
        displayName         | fullId
        "Jorsten's Plan"    | '00000000-0000-0000-0000-000000000001'
        "Jorsten Jr's Plan" | '00000000-0000-0000-0000-000000000002'
        "Borsten's Plan"    | '00000000-0000-0000-0000-000000000003'
        "Thorsten's Plan"   | '00000000-0000-0000-0000-000000000004'
    }

    def "rejects an unknown name and ID pair"() {
        when:
        QaBudgetSafetyGuard.requireAllowed(
            new QaBudgetIdentity("Unknown's Plan", '00000000-0000-0000-0000-000000000099'),
            ALLOWLIST
        )

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('not an allowed QA budget')
    }

    def "rejects the right display name with the wrong ID"() {
        when:
        QaBudgetSafetyGuard.requireAllowed(
            new QaBudgetIdentity("Jorsten's Plan", '00000000-0000-0000-0000-000000000099'),
            ALLOWLIST
        )

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects the right ID with the wrong display name"() {
        when:
        QaBudgetSafetyGuard.requireAllowed(
            new QaBudgetIdentity("Jorsten Jr's Plan", '00000000-0000-0000-0000-000000000001'),
            ALLOWLIST
        )

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects a known plan suffix because it is not a full immutable ID"() {
        when:
        QaBudgetSafetyGuard.validateConfiguredAllowlist([
            new QaBudgetIdentity("Jorsten's Plan", '000000000001')
        ])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('full immutable UUID')
    }

    def "rejects name normalization instead of silently trimming or folding case"() {
        when:
        QaBudgetSafetyGuard.requireAllowed(
            new QaBudgetIdentity(" jorsten's plan ", '00000000-0000-0000-0000-000000000001'),
            ALLOWLIST
        )

        then:
        thrown(IllegalArgumentException)
    }
}

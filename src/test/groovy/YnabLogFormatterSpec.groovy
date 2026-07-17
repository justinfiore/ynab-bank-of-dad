import spock.lang.Specification
import ynabbankofdad.ynab.YnabLogFormatter

class YnabLogFormatterSpec extends Specification {

    def "formatAmount converts YNAB milliunits to dollar amounts"() {
        expect:
        YnabLogFormatter.formatAmount(milliunits) == expected

        where:
        milliunits || expected
        1200       || '$1.20'
        -1200      || '-$1.20'
        0          || '$0.00'
        1255       || '$1.26'
    }

    def "formatAmounts recursively formats transaction amounts without changing the payload"() {
        given:
        def payload = [
            transactions: [
                [amount: -1200, memo: 'Shoes'],
                [amount: 250, subtransactions: [[amount: 125]]]
            ],
            transaction_ids: ['txn-1']
        ]

        when:
        def formatted = YnabLogFormatter.formatAmounts(payload)

        then:
        formatted.transactions*.amount == ['-$1.20', '$0.25']
        formatted.transactions[1].subtransactions[0].amount == '$0.13'
        formatted.transaction_ids == ['txn-1']
        payload.transactions*.amount == [-1200, 250]
    }
}

import spock.lang.Specification

import java.text.SimpleDateFormat

class RecordAllowanceSpec extends Specification {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd")

    def "toMilliUnits converts dollars to YNAB milliunits"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)

        expect:
        recordAllowance.toMilliUnits(1.25) == 1250
        recordAllowance.toMilliUnits(0.5) == 500
    }

    def "toDollars converts YNAB milliunits to dollars"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)

        expect:
        recordAllowance.toDollars(1250) == 1.25
        recordAllowance.toDollars(-500) == -0.5
    }

    def "getCDOriginationDate subtracts months based on account type"() {
        given:
        def maturityDate = RecordAllowance.cdDateFormat.parse('08/15/25')

        expect:
        RecordAllowance.cdDateFormat.format(RecordAllowance.getCDOriginationDate('Jack Gold CD 2-Month 08/15/25', maturityDate)) == '06/15/25'
        RecordAllowance.cdDateFormat.format(RecordAllowance.getCDOriginationDate('Jack Gold CD 3-Month 08/15/25', maturityDate)) == '05/15/25'
        RecordAllowance.cdDateFormat.format(RecordAllowance.getCDOriginationDate('Jack Gold CD 6-Month 08/15/25', maturityDate)) == '02/15/25'
    }

    def "getCDOriginationDate throws for unrecognized non-CD category"() {
        given:
        def maturityDate = RecordAllowance.cdDateFormat.parse('08/15/25')

        when:
        RecordAllowance.getCDOriginationDate('Jack Savings Goal 08/15/25', maturityDate)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('Could not calculate CD Origination Date')
    }

    def "findInterestRatesForDate returns earliest known table for earlier origination dates"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)

        expect:
        recordAllowance.findInterestRatesForDate(dateFormat.parse('2025-05-01'))['Silver'] == 0.5
        recordAllowance.findInterestRatesForDate(dateFormat.parse('2025-04-14'))['Silver'] == 0.75
        recordAllowance.findInterestRatesForDate(dateFormat.parse('2024-01-01'))['Gold CD 2-Month'] == 2.25
    }

    def "generateNewAllowanceTransactionsForAdvancedAccounts produces configured deposits in stable order"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        def categoryInfo = [
            'Jack Silver Account': [id: 'jack-silver'],
            'Jack Give Bank': [id: 'jack-give'],
            'Evan Silver Account': [id: 'evan-silver'],
            'Evan Give Bank': [id: 'evan-give'],
            'Emily Silver Account': [id: 'emily-silver'],
            'Emily Give Bank': [id: 'emily-give'],
            'Colin Silver Account': [id: 'colin-silver'],
            'Colin Bronze Account': [id: 'colin-bronze'],
            'Colin Give Bank': [id: 'colin-give']
        ]

        when:
        def transactions = recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)

        then:
        transactions.size() == 9
        transactions*.payee_name == [
            'To Jack Silver Account',
            'To Jack Give Bank',
            'To Evan Silver Account',
            'To Evan Give Bank',
            'To Emily Silver Account',
            'To Emily Give Bank',
            'To Colin Silver Account',
            'To Colin Bronze Account',
            'To Colin Give Bank'
        ]
        transactions*.category_id == [
            'jack-silver',
            'jack-give',
            'evan-silver',
            'evan-give',
            'emily-silver',
            'emily-give',
            'colin-silver',
            'colin-bronze',
            'colin-give'
        ]
        transactions*.amount == [3000, 500, 3000, 500, 1000, 500, 1000, 500, 500]
        transactions.every { it.account_id == 'allowance-escrow' }
        transactions.every { it.memo == 'Allowance' }
    }

    def "generateInterestTransactionsForAdvancedAccounts creates interest transactions in stable order for matching categories"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        def categoryInfo = [
            'Jack Silver Account': [id: 'jack-silver', balance: 200000],
            'Evan Silver Account': [id: 'evan-silver', balance: 100000],
            'Emily Silver Account': [id: 'emily-silver', balance: 100000],
            'Colin Silver Account': [id: 'colin-silver', balance: 100000],
            'Colin Bronze Account': [id: 'colin-bronze', balance: 100000],
            'Colin Gold CD 2-Month 08/15/25': [id: 'colin-cd', balance: 100000],
            'Allowance': [id: 'allowance']
        ]

        when:
        def transactions = recordAllowance.generateInterestTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)
        def byCategory = transactions.collectEntries { [(it.category_id): it] }

        then:
        transactions.size() == 6
        transactions*.payee_name == [
            'Jack Silver Account Interest',
            'Evan Silver Account Interest',
            'Emily Silver Account Interest',
            'Colin Silver Account Interest',
            'Colin Bronze Account Interest',
            'Colin Gold CD 2-Month 08/15/25 Interest'
        ]
        transactions*.category_id == ['jack-silver', 'evan-silver', 'emily-silver', 'colin-silver', 'colin-bronze', 'colin-cd']
        transactions*.amount == [300, 150, 150, 150, 100, 250]
        byCategory.keySet() == ['jack-silver', 'evan-silver', 'emily-silver', 'colin-silver', 'colin-bronze', 'colin-cd'] as Set
        byCategory['jack-silver'].amount == 300
        byCategory['colin-bronze'].amount == 100
        byCategory['colin-cd'].amount == 250
        byCategory.values().every { it.account_id == 'allowance-escrow' }
        byCategory.values().every { it.memo == 'Interest' }
    }

    def "generateInterestTransactionsForAdvancedAccounts skips matured cds"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        def categoryInfo = [
            'Colin Gold CD 2-Month 06/01/25': [id: 'matured-cd', balance: 100000]
        ]

        when:
        def transactions = recordAllowance.generateInterestTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)

        then:
        transactions.empty
    }

    def "generateNewAllowanceTransactionsForAdvancedAccounts throws when configured category is missing"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        def categoryInfo = [
            'Jack Silver Account': [id: 'jack-silver']
        ]

        when:
        recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('Missing category info for advanced allowance category')
    }

    def "generateOffsettingTransaction offsets the total of allowance and interest transactions"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        def transactions = [[amount: 1250], [amount: 500], [amount: 250]]
        def categoryInfo = ['Allowance': [id: 'allowance-id']]

        when:
        def offset = recordAllowance.generateOffsettingTransaction('allowance-escrow', transactions, categoryInfo)

        then:
        offset.account_id == 'allowance-escrow'
        offset.category_id == 'allowance-id'
        offset.amount == -2000
        offset.memo == 'Allowance and Interest combined'
    }

    def "generateNonInterestBearingTransactions creates one combined allowance transaction per kid in stable order"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        recordAllowance.kidsWithoutInterest = ['Sam', 'Max']
        def categoryInfo = ['Allowance': [id: 'allowance-id']]

        when:
        def transactions = recordAllowance.generateNonInterestBearingTransactions('allowance-escrow', categoryInfo)

        then:
        transactions.size() == 2
        transactions.every { it.amount == -2500 }
        transactions*.payee_name == ['Allowance Sam', 'Allowance Max']
        transactions*.category_id == ['allowance-id', 'allowance-id']
        transactions*.memo == ['To Sam Piggy Banks', 'To Max Piggy Banks']
    }

    def "generateOffsettingTransaction uses all transaction groups in a stable combined total"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)
        recordAllowance.kidsWithoutInterest = ['Sam']
        def groupedTransactions = []
        groupedTransactions.addAll(recordAllowance.generateInterestTransactionsForAdvancedAccounts('allowance-escrow', [
            'Jack Silver Account': [id: 'jack-silver', balance: 200000],
            'Colin Bronze Account': [id: 'colin-bronze', balance: 100000],
            'Allowance': [id: 'allowance-id']
        ]))
        groupedTransactions.addAll(recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts('allowance-escrow', [
            'Jack Silver Account': [id: 'jack-silver'],
            'Jack Give Bank': [id: 'jack-give'],
            'Evan Silver Account': [id: 'evan-silver'],
            'Evan Give Bank': [id: 'evan-give'],
            'Emily Silver Account': [id: 'emily-silver'],
            'Emily Give Bank': [id: 'emily-give'],
            'Colin Silver Account': [id: 'colin-silver'],
            'Colin Bronze Account': [id: 'colin-bronze'],
            'Colin Give Bank': [id: 'colin-give']
        ]))
        groupedTransactions.addAll(recordAllowance.generateNonInterestBearingTransactions('allowance-escrow', ['Allowance': [id: 'allowance-id']]))

        when:
        def offset = recordAllowance.generateOffsettingTransaction('allowance-escrow', groupedTransactions, ['Allowance': [id: 'allowance-id']])

        then:
        groupedTransactions*.memo[0..1] == ['Interest', 'Interest']
        groupedTransactions*.memo[2..10] == ['Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance']
        groupedTransactions*.memo[11] == 'To Sam Piggy Banks'
        groupedTransactions*.payee_name[0..1] == ['Jack Silver Account Interest', 'Colin Bronze Account Interest']
        groupedTransactions*.payee_name[2..10] == [
            'To Jack Silver Account',
            'To Jack Give Bank',
            'To Evan Silver Account',
            'To Evan Give Bank',
            'To Emily Silver Account',
            'To Emily Give Bank',
            'To Colin Silver Account',
            'To Colin Bronze Account',
            'To Colin Give Bank'
        ]
        groupedTransactions*.payee_name[11] == 'Allowance Sam'
        offset.amount == -groupedTransactions.sum { it.amount as Integer }
        offset.payee_name == 'Allowance '
        offset.memo == 'Allowance and Interest combined'
    }

    def "generateOffsettingTransaction throws when Allowance category is missing"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), false)

        when:
        recordAllowance.generateOffsettingTransaction('allowance-escrow', [[amount: 1250]], [:])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('Missing category info for Allowance')
    }
}

import spock.lang.Specification

import java.text.SimpleDateFormat

class RecordAllowanceSpec extends Specification {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd")

    private RuntimeConfig demoConfig() {
        RuntimeConfig.fromMap([
            budgetName: 'Demo Family Budget',
            allowanceEscrowAccountName: 'Allowance Escrow',
            allowanceCategoryName: 'Family Allowance',
            interestMemo: 'Interest',
            allowanceMemo: 'Allowance',
            combinedMemo: 'Allowance and Interest combined',
            nonInterestMemoSuffix: 'Piggy Banks',
            bankSuffixes: [' Spend Bank', ' Save Bank', ' Give Bank'],
            allowanceRates: [' Spend Bank': 1.0, ' Save Bank': 0.5, ' Give Bank': 0.5],
            giveBankRate: 0.5,
            kidsWithoutInterest: [],
            kidsWithSimpleAccounts: [],
            kidsWithAdvancedAccounts: ['Child One', 'Child Two', 'Child Three', 'Child Four'],
            advancedAllowanceDeposits: [
                'Child One': ['Child One Silver Account': 3.0, 'Child One Give Bank': 0.5],
                'Child Two': ['Child Two Silver Account': 3.0, 'Child Two Give Bank': 0.5],
                'Child Three': ['Child Three Silver Account': 1.0, 'Child Three Give Bank': 0.5],
                'Child Four': ['Child Four Silver Account': 1.0, 'Child Four Bronze Account': 0.5, 'Child Four Give Bank': 0.5]
            ],
            accountTypes: ['Bronze', 'Silver', 'Gold CD 2-Month', 'Gold CD 3-Month', 'Gold CD 6-Month', 'First Car Fund'],
            interestRatesByAccountTypeAndDate: [
                'Current': [
                    'Bronze': 0.1,
                    'Silver': 0.15,
                    'Gold CD 2-Month': 0.25,
                    'Gold CD 3-Month': 0.35,
                    'Gold CD 6-Month': 0.65,
                    'First Car Fund': 0.65
                ],
                '2025-06-01': [
                    'Bronze': 0.1,
                    'Silver': 0.5,
                    'Gold CD 2-Month': 0.75,
                    'Gold CD 3-Month': 1.0,
                    'Gold CD 6-Month': 1.25,
                    'First Car Fund': 1.25
                ],
                '2025-04-14': [
                    'Bronze': 0.25,
                    'Silver': 0.75,
                    'Gold CD 2-Month': 1.5,
                    'Gold CD 3-Month': 1.75,
                    'Gold CD 6-Month': 2.0,
                    'First Car Fund': 2.0
                ],
                '2024-12-25': [
                    'Gold CD 2-Month': 1.75,
                    'Gold CD 3-Month': 2.0,
                    'Gold CD 6-Month': 2.25
                ],
                '2024-11-23': [
                    'Gold CD 2-Month': 2.25,
                    'Gold CD 3-Month': 2.5,
                    'Gold CD 6-Month': 2.75
                ]
            ]
        ])
    }

    def "toMilliUnits converts dollars to YNAB milliunits"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)

        expect:
        recordAllowance.toMilliUnits(1.25) == 1250
        recordAllowance.toMilliUnits(0.5) == 500
    }

    def "toDollars converts YNAB milliunits to dollars"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)

        expect:
        recordAllowance.toDollars(1250) == 1.25
        recordAllowance.toDollars(-500) == -0.5
    }

    def "getCDOriginationDate subtracts months based on account type"() {
        given:
        def maturityDate = RecordAllowance.cdDateFormat.parse('08/15/25')

        expect:
        RecordAllowance.cdDateFormat.format(RecordAllowance.getCDOriginationDate('Child One Gold CD 2-Month 08/15/25', maturityDate)) == '06/15/25'
        RecordAllowance.cdDateFormat.format(RecordAllowance.getCDOriginationDate('Child One Gold CD 3-Month 08/15/25', maturityDate)) == '05/15/25'
        RecordAllowance.cdDateFormat.format(RecordAllowance.getCDOriginationDate('Child One Gold CD 6-Month 08/15/25', maturityDate)) == '02/15/25'
    }

    def "getCDOriginationDate throws for unrecognized non-CD category"() {
        given:
        def maturityDate = RecordAllowance.cdDateFormat.parse('08/15/25')

        when:
        RecordAllowance.getCDOriginationDate('Child One Savings Goal 08/15/25', maturityDate)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('Could not calculate CD Origination Date')
    }

    def "findInterestRatesForDate returns earliest known table for earlier origination dates"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)

        expect:
        recordAllowance.findInterestRatesForDate(dateFormat.parse('2025-05-01'))['Silver'] == 0.5
        recordAllowance.findInterestRatesForDate(dateFormat.parse('2025-04-14'))['Silver'] == 0.75
        recordAllowance.findInterestRatesForDate(dateFormat.parse('2024-01-01'))['Gold CD 2-Month'] == 2.25
    }

    def "generateNewAllowanceTransactionsForAdvancedAccounts produces configured deposits in stable order"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        def categoryInfo = [
            'Child One Silver Account': [id: 'child-one-silver'],
            'Child One Give Bank': [id: 'child-one-give'],
            'Child Two Silver Account': [id: 'child-two-silver'],
            'Child Two Give Bank': [id: 'child-two-give'],
            'Child Three Silver Account': [id: 'child-three-silver'],
            'Child Three Give Bank': [id: 'child-three-give'],
            'Child Four Silver Account': [id: 'child-four-silver'],
            'Child Four Bronze Account': [id: 'child-four-bronze'],
            'Child Four Give Bank': [id: 'child-four-give']
        ]

        when:
        def transactions = recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)

        then:
        transactions.size() == 9
        transactions*.payee_name == [
            'To Child One Silver Account',
            'To Child One Give Bank',
            'To Child Two Silver Account',
            'To Child Two Give Bank',
            'To Child Three Silver Account',
            'To Child Three Give Bank',
            'To Child Four Silver Account',
            'To Child Four Bronze Account',
            'To Child Four Give Bank'
        ]
        transactions*.category_id == [
            'child-one-silver',
            'child-one-give',
            'child-two-silver',
            'child-two-give',
            'child-three-silver',
            'child-three-give',
            'child-four-silver',
            'child-four-bronze',
            'child-four-give'
        ]
        transactions*.amount == [3000, 500, 3000, 500, 1000, 500, 1000, 500, 500]
        transactions.every { it.account_id == 'allowance-escrow' }
        transactions.every { it.memo == 'Allowance' }
    }

    def "generateInterestTransactionsForAdvancedAccounts creates interest transactions in stable order for matching categories"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        def categoryInfo = [
            'Child One Silver Account': [id: 'child-one-silver', balance: 200000],
            'Child Two Silver Account': [id: 'child-two-silver', balance: 100000],
            'Child Three Silver Account': [id: 'child-three-silver', balance: 100000],
            'Child Four Silver Account': [id: 'child-four-silver', balance: 100000],
            'Child Four Bronze Account': [id: 'child-four-bronze', balance: 100000],
            'Child Four Gold CD 2-Month 08/15/25': [id: 'child-four-cd', balance: 100000]
        ]

        when:
        def transactions = recordAllowance.generateInterestTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)
        def byCategory = transactions.collectEntries { [(it.category_id): it] }

        then:
        transactions.size() == 6
        transactions*.payee_name == [
            'Child One Silver Account Interest',
            'Child Two Silver Account Interest',
            'Child Three Silver Account Interest',
            'Child Four Silver Account Interest',
            'Child Four Bronze Account Interest',
            'Child Four Gold CD 2-Month 08/15/25 Interest'
        ]
        transactions*.category_id == ['child-one-silver', 'child-two-silver', 'child-three-silver', 'child-four-silver', 'child-four-bronze', 'child-four-cd']
        transactions*.amount == [300, 150, 150, 150, 100, 250]
        byCategory.keySet() == ['child-one-silver', 'child-two-silver', 'child-three-silver', 'child-four-silver', 'child-four-bronze', 'child-four-cd'] as Set
        byCategory['child-one-silver'].amount == 300
        byCategory['child-four-bronze'].amount == 100
        byCategory['child-four-cd'].amount == 250
        byCategory.values().every { it.account_id == 'allowance-escrow' }
        byCategory.values().every { it.memo == 'Interest' }
    }

    def "generateInterestTransactionsForAdvancedAccounts skips matured cds"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        def categoryInfo = [
            'Child Four Gold CD 2-Month 06/01/25': [id: 'matured-cd', balance: 100000]
        ]

        when:
        def transactions = recordAllowance.generateInterestTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)

        then:
        transactions.empty
    }

    def "generateNewAllowanceTransactionsForAdvancedAccounts throws when configured category is missing"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        def categoryInfo = [
            'Child One Silver Account': [id: 'child-one-silver']
        ]

        when:
        recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts('allowance-escrow', categoryInfo)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('Missing category info for advanced allowance category')
    }

    def "generateOffsettingTransaction offsets the total of allowance and interest transactions"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        def transactions = [[amount: 1250], [amount: 500], [amount: 250]]
        def categoryInfo = ['Family Allowance': [id: 'allowance-id']]

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
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        recordAllowance.kidsWithoutInterest = ['Sam', 'Max']
        def categoryInfo = ['Family Allowance': [id: 'allowance-id']]

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
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)
        recordAllowance.kidsWithoutInterest = ['Sam']
        def groupedTransactions = []
        groupedTransactions.addAll(recordAllowance.generateInterestTransactionsForAdvancedAccounts('allowance-escrow', [
            'Child One Silver Account': [id: 'child-one-silver', balance: 200000],
            'Child Four Bronze Account': [id: 'child-four-bronze', balance: 100000]
        ]))
        groupedTransactions.addAll(recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts('allowance-escrow', [
            'Child One Silver Account': [id: 'child-one-silver'],
            'Child One Give Bank': [id: 'child-one-give'],
            'Child Two Silver Account': [id: 'child-two-silver'],
            'Child Two Give Bank': [id: 'child-two-give'],
            'Child Three Silver Account': [id: 'child-three-silver'],
            'Child Three Give Bank': [id: 'child-three-give'],
            'Child Four Silver Account': [id: 'child-four-silver'],
            'Child Four Bronze Account': [id: 'child-four-bronze'],
            'Child Four Give Bank': [id: 'child-four-give']
        ]))
        groupedTransactions.addAll(recordAllowance.generateNonInterestBearingTransactions('allowance-escrow', ['Family Allowance': [id: 'allowance-id']]))

        when:
        def offset = recordAllowance.generateOffsettingTransaction('allowance-escrow', groupedTransactions, ['Family Allowance': [id: 'allowance-id']])

        then:
        groupedTransactions*.memo[0..1] == ['Interest', 'Interest']
        groupedTransactions*.memo[2..10] == ['Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance', 'Allowance']
        groupedTransactions*.memo[11] == 'To Sam Piggy Banks'
        groupedTransactions*.payee_name[0..1] == ['Child One Silver Account Interest', 'Child Four Bronze Account Interest']
        groupedTransactions*.payee_name[2..10] == [
            'To Child One Silver Account',
            'To Child One Give Bank',
            'To Child Two Silver Account',
            'To Child Two Give Bank',
            'To Child Three Silver Account',
            'To Child Three Give Bank',
            'To Child Four Silver Account',
            'To Child Four Bronze Account',
            'To Child Four Give Bank'
        ]
        groupedTransactions*.payee_name[11] == 'Allowance Sam'
        offset.amount == -groupedTransactions.sum { it.amount as Integer }
        offset.payee_name == 'Allowance '
        offset.memo == 'Allowance and Interest combined'
    }

    def "generateOffsettingTransaction throws when Allowance category is missing"() {
        given:
        def recordAllowance = new RecordAllowance('token', dateFormat.parse('2025-07-06'), demoConfig(), false)

        when:
        recordAllowance.generateOffsettingTransaction('allowance-escrow', [[amount: 1250]], [:])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('Missing category info for Family Allowance')
    }
}

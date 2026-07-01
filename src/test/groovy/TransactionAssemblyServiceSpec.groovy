import spock.lang.Specification

import java.text.SimpleDateFormat

class TransactionAssemblyServiceSpec extends Specification {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd")

    def "generateInterestTransactionsForAdvancedAccounts skips zero-value rounded interest transactions"() {
        given:
        def service = buildAssemblyService('2025-07-06')
        def categories = [
            'Child One Silver Account': new CategorySnapshot('child-one-silver', 'Child One Silver Account', 100)
        ]

        when:
        def transactions = service.generateInterestTransactionsForAdvancedAccounts(
            'allowance-escrow',
            categories,
            ['Child One'],
            ['Silver'],
            'Interest'
        )

        then:
        transactions.empty
    }

    def "generateInterestTransactionsForAdvancedAccounts throws a clear error for unmatched account types"() {
        given:
        def service = buildAssemblyService('2025-07-06')
        def categories = [
            'Child One Mystery Account': new CategorySnapshot('child-one-mystery', 'Child One Mystery Account', 100000)
        ]

        when:
        service.generateInterestTransactionsForAdvancedAccounts(
            'allowance-escrow',
            categories,
            ['Child One'],
            ['Mystery'],
            'Interest'
        )

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Couldn't Find Account Type for category: Child One Mystery Account")
    }

    def "generateOffsettingTransaction throws when no component transactions are provided"() {
        given:
        def service = buildAssemblyService('2025-07-06')
        def categories = [
            'Family Allowance': new CategorySnapshot('allowance-id', 'Family Allowance', 0)
        ]

        when:
        service.generateOffsettingTransaction('allowance-escrow', [], categories, [])

        then:
        thrown(NullPointerException)
    }

    def "generateNonInterestBearingTransactions reuses the allowance category for each configured kid"() {
        given:
        def service = buildAssemblyService('2025-07-06')
        def categories = [
            'Family Allowance': new CategorySnapshot('allowance-id', 'Family Allowance', 0)
        ]

        when:
        def transactions = service.generateNonInterestBearingTransactions(
            'allowance-escrow',
            categories,
            ['Sam', 'Max'],
            [' Spend Bank': 1, ' Save Bank': 0.5],
            0.5
        )

        then:
        transactions*.amount == [-2000, -2000]
        transactions*.payeeName == ['Allowance Sam', 'Allowance Max']
        transactions*.memo == ['To Sam Piggy Banks', 'To Max Piggy Banks']
        transactions*.categoryId == ['allowance-id', 'allowance-id']
    }

    private TransactionAssemblyService buildAssemblyService(String transactionDate) {
        def calculationService = new AllowanceCalculationService(
            dateFormat.parse(transactionDate),
            [
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
                    'Gold CD 3-Month': 1.00,
                    'Gold CD 6-Month': 1.25,
                    'First Car Fund': 1.25
                ],
                '2025-04-14': [
                    'Bronze': 0.25,
                    'Silver': 0.75,
                    'Gold CD 2-Month': 1.50,
                    'Gold CD 3-Month': 1.75,
                    'Gold CD 6-Month': 2.00,
                    'First Car Fund': 2.00
                ],
                '2024-12-25': [
                    'Gold CD 2-Month': 1.75,
                    'Gold CD 3-Month': 2.00,
                    'Gold CD 6-Month': 2.25
                ],
                '2024-11-23': [
                    'Gold CD 2-Month': 2.25,
                    'Gold CD 3-Month': 2.5,
                    'Gold CD 6-Month': 2.75
                ]
            ],
            ['Bronze', 'Silver', 'Gold CD 2-Month', 'Gold CD 3-Month', 'Gold CD 6-Month', 'First Car Fund']
        )
        new TransactionAssemblyService(
            "${transactionDate}T00:00:00Z",
            calculationService,
            'Family Allowance',
            'Allowance',
            'Allowance and Interest combined',
            'Piggy Banks'
        )
    }
}

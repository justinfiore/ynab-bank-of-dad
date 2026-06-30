import spock.lang.Specification

import java.text.SimpleDateFormat

class AllowanceCalculationServiceSpec extends Specification {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd")

    def "resolveInterestRatePercent uses current rates for non-CD accounts"() {
        given:
        def service = buildService('2025-07-06')

        expect:
        service.resolveInterestRatePercent('Silver', 'Jack Silver Account') == 0.15
        service.resolveInterestRatePercent('Bronze', 'Colin Bronze Account') == 0.1
    }

    def "resolveInterestRatePercent uses origination-date historical rates for CDs"() {
        given:
        def service = buildService('2025-07-06')

        expect:
        service.resolveInterestRatePercent('Gold CD 2-Month', 'Colin Gold CD 2-Month 06/15/25') == 0.75
        service.resolveInterestRatePercent('Gold CD 6-Month', 'Colin Gold CD 6-Month 10/14/24') == 2.75
    }

    def "resolveAccountType returns null when category does not match a configured type"() {
        given:
        def service = buildService('2025-07-06')

        expect:
        service.resolveAccountType('Jack Vacation Bucket') == null
    }

    def "isMaturedCd only returns true after the maturity date"() {
        given:
        def beforeMaturity = buildService('2025-08-15')
        def afterMaturity = buildService('2025-08-16')

        expect:
        !beforeMaturity.isMaturedCd('Gold CD 2-Month', 'Colin Gold CD 2-Month 08/15/25')
        afterMaturity.isMaturedCd('Gold CD 2-Month', 'Colin Gold CD 2-Month 08/15/25')
        !afterMaturity.isMaturedCd('Silver', 'Jack Silver Account')
    }

    def "calculateInterest rounds using the configured math context"() {
        given:
        def service = buildService('2025-07-06')

        expect:
        service.calculateInterest(0.65, 1234.56) == 8.02G
        service.calculateInterest(0.15, 999.99) == 1.50G
    }

    private AllowanceCalculationService buildService(String transactionDate) {
        new AllowanceCalculationService(
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
    }
}

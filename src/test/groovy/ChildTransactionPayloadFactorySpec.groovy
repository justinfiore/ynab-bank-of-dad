import spock.lang.Specification
import ynabbankofdad.sync.ChildTransactionPayloadFactory
import ynabbankofdad.sync.state.SourceEntityKey
import ynabbankofdad.sync.state.SourceEntityType

class ChildTransactionPayloadFactorySpec extends Specification {
    def factory = new ChildTransactionPayloadFactory()

    def "stable reconciliation source and target identities determine import ids"() {
        given:
        def source = transactionSource('txn-1')

        expect:
        factory.buildImportId(source, 'child-one', 'outflow') ==
            factory.buildImportId(source, 'child-one', 'inflow')
        factory.buildImportId(source, 'child-one', 'outflow') !=
            factory.buildImportId(source, 'child-two', 'outflow')
        factory.buildImportId(source, 'child-one', 'outflow') !=
            factory.buildImportId(transactionSource('txn-2'), 'child-one', 'outflow')
        factory.buildImportId(source, 'child-one', 'outflow') !=
            factory.buildImportId(source, 'child-one', 'outflow', 'retired-child')
        factory.buildImportId(source, 'child-one', 'outflow', 'retired-child') ==
            factory.buildImportId(source, 'child-one', 'outflow', 'retired-child')
        factory.buildImportId(source, 'child-one', 'outflow') ==
            factory.buildImportId(source, 'child-one', 'outflow', null)
        factory.buildImportId(source, 'child-one', 'outflow') ==
            factory.buildImportId(source, 'child-one', 'outflow', '')
        factory.buildImportId(source, 'child-one', 'outflow') ==
            'PCBS:fafc540bc31c2fe27034bfe4d360a31'
    }

    def "money movement import ids distinguish mirror directions"() {
        given:
        def source = new SourceEntityKey(
            'parent-budget', SourceEntityType.MONEY_MOVEMENT, null, null, 'movement-1')

        expect:
        factory.buildImportId(source, 'child-budget', 'inflow') !=
            factory.buildImportId(source, 'child-budget', 'outflow')
    }

    def "optional namespace changes the hash while preserving legacy identity and format"() {
        given:
        def source = transactionSource('txn-1')

        expect:
        factory.buildImportId(source, 'child-one', 'outflow', null, null) ==
            'PCBS:fafc540bc31c2fe27034bfe4d360a31'
        factory.buildImportId(source, 'child-one', 'outflow', null, 'reseed-v2') ==
            factory.buildImportId(source, 'child-one', 'outflow', null, 'reseed-v2')
        factory.buildImportId(source, 'child-one', 'outflow', null, 'reseed-v2') !=
            factory.buildImportId(source, 'child-one', 'outflow', null, 'reseed-v3')
        factory.buildImportId(source, 'child-one', 'outflow', null, 'reseed-v2') ==~
            /PCBS:[a-f0-9]{31}/
        factory.buildImportId(source, 'child-one', 'outflow', null, 'reseed-v2').size() == 36
    }

    def "import ids are sanitized and bounded"() {
        given:
        def source = transactionSource('txn/with:punctuation and lots of extra characters !@#$%^&*()')

        when:
        def importId = factory.buildImportId(source, 'child budget!', 'outflow')

        then:
        importId ==~ /PCBS:[a-f0-9]{31}/
        importId.size() == 36
    }

    def "buildImportId rejects missing stable reconciliation identity"() {
        when:
        factory.buildImportId(source, targetBudgetId, direction)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('stable source and target identity')

        where:
        source                                                      | targetBudgetId | direction
        null                                                        | 'child'        | 'outflow'
        new SourceEntityKey(null, SourceEntityType.TRANSACTION,
            'txn', null, null)                                      | 'child'        | 'outflow'
        transactionSource('txn')                                    | null           | 'outflow'
        transactionSource('txn')                                    | 'child'        | null
    }

    def "extractCreatedTransactionId supports YNAB bulk and transaction response shapes"() {
        expect:
        factory.extractCreatedTransactionId([data: [bulk: [transaction_ids: ['bulk-id']]]]) == 'bulk-id'
        factory.extractCreatedTransactionId([data: [transactions: [[id: 'txn-id']]]]) == 'txn-id'
        factory.extractCreatedTransactionId([data: [:]]) == null
    }

    private static SourceEntityKey transactionSource(String transactionId) {
        new SourceEntityKey(
            'parent-budget', SourceEntityType.TRANSACTION, transactionId, null, null)
    }
}

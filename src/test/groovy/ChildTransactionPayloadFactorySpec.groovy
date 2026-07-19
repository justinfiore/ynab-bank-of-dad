import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import spock.lang.Specification

class ChildTransactionPayloadFactorySpec extends Specification {
    def factory = new ChildTransactionPayloadFactory()

    def "buildTransaction creates child YNAB payload with deterministic import id"() {
        given:
        def plan = plan('parent|child-one|transaction|txn-1||||cat-1|-1200')

        when:
        def first = factory.buildTransaction(plan, 'acct-1', 'YBOD: ', '')
        def second = factory.buildTransaction(plan, 'acct-1', 'YBOD: ', '')

        then:
        first.account_id == 'acct-1'
        first.date == '2026-07-01'
        first.amount == -1200
        first.payee_name == 'Payee'
        first.category_id == null
        first.memo == 'YBOD: Memo'
        first.cleared == 'cleared'
        !first.approved
        first.import_id == second.import_id
        first.import_id ==~ /PCBS:[a-f0-9]{31}/
        first.import_id.size() == 36
    }

    def "buildTransaction applies custom prefix and suffix"() {
        given:
        def plan = plan('parent|child-one|transaction|txn-1||||cat-1|-1200')

        when:
        def result = factory.buildTransaction(plan, 'acct-1', '[Kid] ', ' (auto)')

        then:
        result.memo == '[Kid] Memo (auto)'
        result.cleared == 'cleared'
    }

    def "buildTransaction respects empty prefix/suffix"() {
        given:
        def plan = plan('parent|child-one|transaction|txn-1||||cat-1|-1200')

        when:
        def result = factory.buildTransaction(plan, 'acct-1', '', '')

        then:
        result.memo == 'Memo'
        result.cleared == 'cleared'
    }

    def "buildTransaction trims the final decorated memo including an empty source memo"() {
        given:
        def source = plan('parent|child-one|transaction|txn-1||||cat-1|-1200')
        def emptyMemoPlan = new ChildTransactionPlan(
            source.sourceBudgetId, source.targetChildKey, source.targetBudgetName, source.mappingKey,
            source.parentCategoryName, source.eventType, source.parentTransactionId,
            source.parentSubtransactionId, source.moneyMovementId, source.moneyMovementGroupId,
            source.idempotencyKey, source.childAccountName, source.date, source.amount,
            null, source.payeeName, source.approved
        )

        expect:
        factory.buildTransaction(source, 'acct-1', '  [Kid] ', ' (auto)  ').memo == '[Kid] Memo (auto)'
        factory.buildTransaction(emptyMemoPlan, 'acct-1', '  [Kid] ', ' (auto)  ').memo == '[Kid]  (auto)'
        factory.buildTransaction(emptyMemoPlan, 'acct-1', '  ', '  ').memo == ''
    }

    def "stable source and target identities distinguish import ids"() {
        expect:
        factory.buildImportId(plan('parent|child-one|transaction|txn-1||||cat-1|-1200')) !=
            factory.buildImportId(plan('parent|child-two|transaction|txn-1||||cat-1|-1200', 'child-two'))
        factory.buildImportId(plan('parent|child-one|transaction|txn-1||||cat-1|-1200')) !=
            factory.buildImportId(plan('parent|child-one|transaction|txn-2||||cat-1|-1200', 'child-one', 'txn-2'))
    }

    def "import ids are sanitized and bounded"() {
        given:
        def importId = factory.buildImportId(plan('parent|child one|transaction|txn/with:punctuation and lots of extra characters !@#$%^&*()', 'child one!', 'txn/with:punctuation'))

        expect:
        importId ==~ /PCBS:[a-f0-9]{31}/
        importId.size() <= 36
    }

    def "extractCreatedTransactionId supports bulk and transaction list response shapes"() {
        expect:
        factory.extractCreatedTransactionId([data: [bulk: [transaction_ids: ['bulk-id']]]]) == 'bulk-id'
        factory.extractCreatedTransactionId([data: [transactions: [[id: 'txn-id']]]]) == 'txn-id'
        factory.extractCreatedTransactionId([data: [:]]) == null
    }

    def "buildImportId rejects missing stable identity with a useful error"() {
        when:
        def source = plan(null)
        factory.buildImportId(new ChildTransactionPlan(
            null, source.targetChildKey, source.targetBudgetName, source.mappingKey,
            source.parentCategoryName, source.eventType, source.parentTransactionId,
            source.parentSubtransactionId, source.moneyMovementId, source.moneyMovementGroupId,
            source.idempotencyKey, source.childAccountName, source.date, source.amount,
            source.memo, source.payeeName, source.approved
        ))

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('stable source and target identity')
    }

    def "buildImportId is unchanged by mutable date amount and idempotency details"() {
        given:
        def source = plan('parent|child-one|transaction|txn-1||||old-category|-1200')
        def changed = new ChildTransactionPlan(
            source.sourceBudgetId, source.targetChildKey, source.targetBudgetName, source.mappingKey,
            source.parentCategoryName, source.eventType, source.parentTransactionId,
            source.parentSubtransactionId, source.moneyMovementId, source.moneyMovementGroupId,
            'parent|child-one|transaction|txn-1||||new-category|-9999', source.childAccountName, '2026-08-02', -9999,
            source.memo, source.payeeName, source.approved
        )

        expect:
        factory.buildImportId(source) == factory.buildImportId(changed)
    }

    def "money movement import ids distinguish inflow and outflow sides"() {
        given:
        def source = plan('parent|child-one|mapping|account|money_movement|||mm-1|inflow|cat|name|500')
        def inflow = new ChildTransactionPlan(
            source.sourceBudgetId, source.targetChildKey, source.targetBudgetName, source.mappingKey,
            source.parentCategoryName, 'money_movement', null, null, 'mm-1', 'group-1',
            'parent|child-one|mapping|account|money_movement|||mm-1|inflow|cat|name|500',
            source.childAccountName, source.date, 500, source.memo, source.payeeName, source.approved
        )
        def outflow = new ChildTransactionPlan(
            source.sourceBudgetId, source.targetChildKey, source.targetBudgetName, source.mappingKey,
            source.parentCategoryName, 'money_movement', null, null, 'mm-1', 'group-1',
            'parent|child-one|mapping|account|money_movement|||mm-1|outflow|cat|name|500',
            source.childAccountName, source.date, -500, source.memo, source.payeeName, source.approved
        )

        expect:
        factory.buildImportId(inflow) != factory.buildImportId(outflow)
    }

    private static ChildTransactionPlan plan(String idempotencyKey, String childKey = 'child-one', String transactionId = 'txn-1') {
        new ChildTransactionPlan(
            'parent-budget', childKey, 'Child Budget', 'spend', 'Child One Spend Bank',
            'transaction', transactionId, null, null, null, idempotencyKey, 'Child Checking',
            '2026-07-01', -1200, 'Memo', 'Payee', false
        )
    }
}

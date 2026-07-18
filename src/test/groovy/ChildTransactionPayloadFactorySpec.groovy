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
        first.import_id.startsWith('PCBS:20260701:1200:')
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

    def "different idempotency keys produce different import ids"() {
        expect:
        factory.buildImportId(plan('parent|child-one|transaction|txn-1||||cat-1|-1200')) !=
            factory.buildImportId(plan('parent|child-two|transaction|txn-1||||cat-1|-1200'))
    }

    def "import ids are sanitized and bounded"() {
        given:
        def importId = factory.buildImportId(plan('parent|child one|transaction|txn/with:punctuation and lots of extra characters !@#$%^&*()'))

        expect:
        importId ==~ /PCBS:20260701:1200:[A-Za-z0-9]+/
        importId.split(':')[-1].size() <= 28
        importId.size() <= 64
    }

    def "extractCreatedTransactionId supports bulk and transaction list response shapes"() {
        expect:
        factory.extractCreatedTransactionId([data: [bulk: [transaction_ids: ['bulk-id']]]]) == 'bulk-id'
        factory.extractCreatedTransactionId([data: [transactions: [[id: 'txn-id']]]]) == 'txn-id'
        factory.extractCreatedTransactionId([data: [:]]) == null
    }

    private static ChildTransactionPlan plan(String idempotencyKey) {
        new ChildTransactionPlan(
            'parent-budget', 'child-one', 'Child Budget', 'spend', 'Child One Spend Bank',
            'transaction', 'txn-1', null, null, null, idempotencyKey, 'Child Checking',
            '2026-07-01', -1200, 'Memo', 'Payee', false
        )
    }
}

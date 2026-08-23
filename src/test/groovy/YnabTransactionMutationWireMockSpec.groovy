import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import spock.lang.Specification
import ynabbankofdad.ynab.YnabBudgetRepository
import ynabbankofdad.ynab.YnabHttpClient

import static com.github.tomakehurst.wiremock.client.WireMock.*

class YnabTransactionMutationWireMockSpec extends Specification {
    WireMockServer server
    YnabBudgetRepository repository

    def setup() {
        server = new WireMockServer(0)
        server.start()
        configureFor('localhost', server.port())
        repository = new YnabBudgetRepository(new YnabHttpClient("http://localhost:${server.port()}", 'child-secret'))
    }

    def cleanup() {
        server.stop()
    }

    def "verified update uses child token documented endpoint and memo-preserving payload"() {
        given:
        stubFor(put(urlEqualTo('/v1/plans/child-budget/transactions/child-1'))
            .willReturn(response([data: [server_knowledge: 2, transaction: transaction(false)]])))

        when:
        def result = repository.updateChildTransaction('child-budget', 'child-1', [
            account_id: 'new-account', date: '2026-07-18', amount: -250,
            payee_name: 'New Payee', cleared: 'cleared', approved: false
        ])

        then:
        result.transaction.id == 'child-1'
        verify(putRequestedFor(urlEqualTo('/v1/plans/child-budget/transactions/child-1'))
            .withHeader('Authorization', equalTo('Bearer child-secret'))
            .withRequestBody(equalToJson(JsonOutput.toJson([transaction: [
                account_id: 'new-account', date: '2026-07-18', amount: -250,
                payee_name: 'New Payee', cleared: 'cleared', approved: false
            ]]))))
    }

    def "verified delete endpoint treats documented absence as idempotent"() {
        given:
        stubFor(delete(urlEqualTo('/v1/plans/child-budget/transactions/child-1'))
            .inScenario('delete').whenScenarioStateIs('Started')
            .willReturn(response([data: [server_knowledge: 3, transaction: transaction(true)]]))
            .willSetStateTo('absent'))
        stubFor(delete(urlEqualTo('/v1/plans/child-budget/transactions/child-1'))
            .inScenario('delete').whenScenarioStateIs('absent')
            .willReturn(aResponse().withStatus(404).withHeader('Content-Type', 'application/json')
                .withBody(JsonOutput.toJson([error: [id: '404', detail: 'not found']]))))

        expect:
        !repository.deleteChildTransaction('child-budget', 'child-1').alreadyAbsent
        repository.deleteChildTransaction('child-budget', 'child-1').alreadyAbsent
        verify(2, deleteRequestedFor(urlEqualTo('/v1/plans/child-budget/transactions/child-1'))
            .withHeader('Authorization', equalTo('Bearer child-secret')))
    }

    def "duplicate create with no ID is safely recovered by documented import identity patch"() {
        given:
        stubFor(post(urlEqualTo('/v1/plans/child-budget/transactions/bulk'))
            .willReturn(response([data: [bulk: [transaction_ids: [],
                duplicate_import_ids: ['PCBS:stable-import-identity']]]])))
        stubFor(patch(urlEqualTo('/v1/plans/child-budget/transactions'))
            .willReturn(response([data: [server_knowledge: 7, transaction_ids: ['child-1'],
                transactions: [transaction(false)]]])))
        def desired = [account_id: 'new-account', date: '2026-07-18', amount: -250,
            payee_name: 'New Payee', memo: 'child memo', cleared: 'cleared', approved: false,
            import_id: 'PCBS:stable-import-identity']

        when:
        def createResponse = repository.postTransactions('child-budget', [desired])
        def recovered = repository.recoverChildTransactionByImportId(
            'child-budget', createResponse.data.bulk.duplicate_import_ids.first(), desired)

        then:
        createResponse.data.bulk.transaction_ids.empty
        recovered.transaction.id == 'child-1'
        verify(patchRequestedFor(urlEqualTo('/v1/plans/child-budget/transactions'))
            .withHeader('Authorization', equalTo('Bearer child-secret'))
            .withRequestBody(equalToJson(JsonOutput.toJson([transactions: [desired]]))))
    }

    private static Map transaction(boolean deleted) {
        [id: 'child-1', account_id: 'new-account', date: '2026-07-18', amount: -250,
         payee_name: 'New Payee', memo: 'child memo', cleared: 'cleared', approved: false, deleted: deleted]
    }

    private static def response(Object value) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
            .withBody(JsonOutput.toJson(value))
    }
}

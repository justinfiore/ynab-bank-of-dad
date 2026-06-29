import groovy.json.JsonSlurper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Specification

class YnabHttpClientSpec extends Specification {

    MockWebServer server

    def setup() {
        server = new MockWebServer()
        server.start()
    }

    def cleanup() {
        server.shutdown()
    }

    def "getJson sends auth header and parses response body"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{"budgets":[{"id":"budget-1"}]}}'))
        def client = buildClient()

        when:
        def response = client.getJson('/v1/budgets')
        def request = server.takeRequest()

        then:
        response.data.budgets[0].id == 'budget-1'
        request.method == 'GET'
        request.path == '/v1/budgets'
        request.getHeader('Authorization') == 'Bearer token'
        request.getHeader('Accept') == 'application/json'
    }

    def "postJson sends JSON body and parses response"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{"bulk":{"transaction_ids":["txn-1"]}}}'))
        def client = buildClient()
        def payload = [transactions: [[payee_name: 'Test Transaction', amount: 1000]]]

        when:
        def response = client.postJson('/v1/budgets/budget-1/transactions/bulk', payload)
        def request = server.takeRequest()
        def body = new JsonSlurper().parseText(request.body.readUtf8())

        then:
        response.data.bulk.transaction_ids == ['txn-1']
        request.method == 'POST'
        request.path == '/v1/budgets/budget-1/transactions/bulk'
        request.getHeader('Authorization') == 'Bearer token'
        request.getHeader('Content-Type').startsWith('application/json')
        body == payload
    }

    def "non-2xx responses surface a clear error"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(500)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"error":{"detail":"boom"}}'))
        def client = buildClient()

        when:
        client.postJson('/v1/budgets/budget-1/transactions/bulk', [transactions: []])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB POST /v1/budgets/budget-1/transactions/bulk failed with status 500')
        ex.message.contains('boom')
    }

    private YnabHttpClient buildClient() {
        new YnabHttpClient(server.url('/').toString(), 'token')
    }
}

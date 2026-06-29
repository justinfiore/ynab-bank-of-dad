import groovy.json.JsonSlurper
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

class RecordAllowanceWireMockSpec extends Specification {

    WireMockServer wireMockServer

    def setup() {
        wireMockServer = new WireMockServer(0)
        wireMockServer.start()
        configureFor('localhost', wireMockServer.port())
    }

    def cleanup() {
        wireMockServer.stop()
    }

    def "constructor selects newest Fiores budget using simulated YNAB budgets response"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-old", "name": "Fiores", "last_modified_on": "2025-07-01T12:00:00Z"},
      {"id": "budget-new", "name": "Fiores", "last_modified_on": "2025-07-08T12:00:00Z"},
      {"id": "budget-other", "name": "Other", "last_modified_on": "2025-07-09T12:00:00Z"}
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), true, buildClient())

        then:
        recordAllowance.budgetId == 'budget-new'
    }

    def "getAccountId and getCategoryInfoByCategoryName use simulated YNAB responses"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Fiores", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/budgets/budget-new/accounts'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "accounts": [
      {"id": "acct-1", "name": "Allowance Escrow"},
      {"id": "acct-2", "name": "Other"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/budgets/budget-new/categories'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "category_groups": [
      {
        "name": "Kids",
        "categories": [
          {"id": "cat-allowance", "name": "Allowance", "balance": 0},
          {"id": "cat-jack", "name": "Jack Silver Account", "balance": 1000}
        ]
      }
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), true, buildClient())
        def accountId = recordAllowance.getAccountId('Allowance Escrow')
        def categories = recordAllowance.getCategoryInfoByCategoryName()

        then:
        accountId == 'acct-1'
        categories['Allowance'].id == 'cat-allowance'
        categories['Jack Silver Account'].balance == 1000
    }

    def "constructor throws clear error when no Fiores budget exists"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-other", "name": "Other", "last_modified_on": "2025-07-09T12:00:00Z"}
    ]
  }
}
''')))

        when:
        new RecordAllowance('token', new Date(), true, buildClient())

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Could not find budget named 'Fiores'")
    }

    def "getAccountId throws clear error when required account is missing"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Fiores", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/budgets/budget-new/accounts'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "accounts": [
      {"id": "acct-2", "name": "Other"}
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), true, buildClient())
        recordAllowance.getAccountId('Allowance Escrow')

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Could not find account named 'Allowance Escrow'")
    }

    def "getCategoryInfoByCategoryName can reveal missing required categories from simulated YNAB responses"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Fiores", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/budgets/budget-new/categories'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "category_groups": [
      {
        "name": "Kids",
        "categories": [
          {"id": "cat-jack", "name": "Jack Silver Account", "balance": 1000}
        ]
      }
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), true, buildClient())
        def categories = recordAllowance.getCategoryInfoByCategoryName()

        then:
        !categories.containsKey('Allowance')
        categories['Jack Silver Account'].balance == 1000
    }

    def "postTransactions sends bulk transaction request to simulated YNAB endpoint"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Fiores", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(post(urlEqualTo('/v1/budgets/budget-new/transactions/bulk'))
            .withRequestBody(containing('Test Transaction'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "bulk": {"transaction_ids": ["txn-1"]}
  }
}
''')))

        and:
        def recordAllowance = new RecordAllowance('token', new Date(), true, buildClient())
        def transactions = [[
            account_id: 'acct-1',
            date: '2025-07-06T00:00:00Z',
            amount: 1000,
            payee_name: 'Test Transaction',
            category_id: 'cat-1',
            memo: 'memo',
            approved: true
        ]]

        when:
        def response = recordAllowance.postTransactions(transactions)
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/budgets/budget-new/transactions/bulk')))
        def body = new JsonSlurper().parseText(requests[0].bodyAsString)

        then:
        response.data.bulk.transaction_ids == ['txn-1']
        requests.size() == 1
        requests[0].getHeader('Authorization') == 'Bearer token'
        body.transactions == transactions
    }

    def "postTransactions surfaces simulated YNAB failure responses"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Fiores", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(post(urlEqualTo('/v1/budgets/budget-new/transactions/bulk'))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"error":{"name":"internal_server_error","detail":"boom"}}')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), true, buildClient())
        recordAllowance.postTransactions([[account_id: 'acct-1', amount: 1000]])

        then:
        thrown(Exception)
    }

    private YnabHttpClient buildClient() {
        new YnabHttpClient("http://localhost:${wireMockServer.port()}", 'token')
    }
}

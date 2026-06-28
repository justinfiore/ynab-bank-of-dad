import com.github.tomakehurst.wiremock.WireMockServer
import groovyx.net.http.HttpBuilder
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

        when:
        def response = recordAllowance.postTransactions([[
            account_id: 'acct-1',
            date: '2025-07-06T00:00:00Z',
            amount: 1000,
            payee_name: 'Test Transaction',
            category_id: 'cat-1',
            memo: 'memo',
            approved: true
        ]])

        then:
        response.data.bulk.transaction_ids == ['txn-1']
        verify(postRequestedFor(urlEqualTo('/v1/budgets/budget-new/transactions/bulk'))
            .withHeader('Authorization', equalTo('Bearer token')))
    }

    private HttpBuilder buildClient() {
        HttpBuilder.configure {
            request.uri = "http://localhost:${wireMockServer.port()}"
            request.headers['Authorization'] = 'Bearer token'
            request.headers['Accept'] = 'application/json'
        }
    }
}

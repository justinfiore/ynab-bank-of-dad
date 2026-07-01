import groovy.json.JsonSlurper
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

class YnabBudgetRepositorySpec extends Specification {

    WireMockServer wireMockServer

    def setup() {
        wireMockServer = new WireMockServer(0)
        wireMockServer.start()
        configureFor('localhost', wireMockServer.port())
    }

    def cleanup() {
        wireMockServer.stop()
    }

    def "getBudgets maps budget payloads into summaries"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-1", "name": "Configured Budget", "last_modified_on": "2025-07-01T12:00:00Z"},
      {"id": "budget-2", "name": "Other", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))

        when:
        def budgets = buildRepository().getBudgets()

        then:
        budgets*.id == ['budget-1', 'budget-2']
        budgets*.name == ['Configured Budget', 'Other']
        budgets*.lastModifiedOn*.time == [
            java.time.OffsetDateTime.parse('2025-07-01T12:00:00Z').toInstant().toEpochMilli(),
            java.time.OffsetDateTime.parse('2025-07-08T12:00:00Z').toInstant().toEpochMilli()
        ]
    }

    def "getLatestBudgetId returns the newest matching budget and ignores other names"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-old", "name": "Configured Budget", "last_modified_on": "2025-07-01T12:00:00Z"},
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"},
      {"id": "budget-other", "name": "Other", "last_modified_on": "2025-07-09T12:00:00Z"}
    ]
  }
}
''')))

        expect:
        buildRepository().getLatestBudgetId('Configured Budget') == 'budget-new'
    }

    def "getCategoryInfoByCategoryName preserves names and defaults missing balances to zero"() {
        given:
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
          {"id": "cat-jack", "name": "Child One Silver Account"}
        ]
      }
    ]
  }
}
''')))

        when:
        def categories = buildRepository().getCategoryInfoByCategoryName('budget-new')

        then:
        categories['Allowance'] == new CategorySnapshot('cat-allowance', 'Allowance', 0)
        categories['Child One Silver Account'] == new CategorySnapshot('cat-jack', 'Child One Silver Account', 0)
    }

    def "postTransactions sends the expected bulk payload to YNAB"() {
        given:
        stubFor(post(urlEqualTo('/v1/budgets/budget-new/transactions/bulk'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "bulk": {"transaction_ids": ["txn-1", "txn-2"]}
  }
}
''')))

        and:
        def repository = buildRepository()
        def transactions = [
            [account_id: 'acct-1', amount: 1000, payee_name: 'One'],
            [account_id: 'acct-1', amount: 2000, payee_name: 'Two']
        ]

        when:
        def response = repository.postTransactions('budget-new', transactions)
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/budgets/budget-new/transactions/bulk')))
        def body = new JsonSlurper().parseText(requests[0].bodyAsString)

        then:
        response.data.bulk.transaction_ids == ['txn-1', 'txn-2']
        requests.size() == 1
        requests[0].getHeader('Authorization') == 'Bearer token'
        body == [transactions: transactions]
    }

    private YnabBudgetRepository buildRepository() {
        new YnabBudgetRepository(new YnabHttpClient("http://localhost:${wireMockServer.port()}", 'token'))
    }
}

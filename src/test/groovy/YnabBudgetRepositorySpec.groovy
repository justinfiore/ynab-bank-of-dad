import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
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
        stubFor(get(urlEqualTo('/v1/plans'))
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
        stubFor(get(urlEqualTo('/v1/plans'))
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
        stubFor(get(urlEqualTo('/v1/plans/budget-new/categories'))
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

    def "getTransactions maps top-level and subtransaction payloads"() {
        given:
        stubFor(get(urlPathEqualTo('/v1/plans/budget-new/transactions'))
            .withQueryParam('since_date', matching('.*'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "server_knowledge": 77,
    "transactions": [
      {
        "id": "txn-1",
        "date": "2026-07-01",
        "amount": -1200,
        "memo": "Shoes",
        "approved": true,
        "category_id": "cat-shoes",
        "category_name": "Child One Spend Bank",
        "subtransactions": [
          {
            "id": "sub-1",
            "transaction_id": "txn-1",
            "amount": -700,
            "memo": "Split one",
            "category_id": "cat-split",
            "category_name": "Child One Save Bank"
          }
        ]
      }
    ]
  }
}
''')))

        when:
        def transactions = buildRepository().getTransactions('budget-new', 30)

        then:
        transactions*.id == ['txn-1']
        transactions[0].serverKnowledge == 77
        transactions[0].subtransactions*.id == ['sub-1']
        transactions[0].subtransactions[0].categoryName == 'Child One Save Bank'
    }

    def "getMoneyMovements filters to recent events and maps payloads"() {
        given:
        def recentDate = java.time.LocalDate.now().minusDays(5).toString()
        def staleDate = java.time.LocalDate.now().minusDays(80).toString()
        stubFor(get(urlEqualTo('/v1/plans/budget-new/money_movements'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody("""
{
  \"data\": {
    \"money_movements\": [
      {\"id\": \"mm-1\", \"money_movement_group_id\": \"group-1\", \"moved_at\": \"${recentDate}T12:00:00Z\", \"from_category_id\": \"cat-a\", \"to_category_id\": \"cat-b\", \"amount\": 500},
      {\"id\": \"mm-2\", \"money_movement_group_id\": \"group-2\", \"moved_at\": \"${staleDate}T12:00:00Z\", \"from_category_id\": \"cat-c\", \"to_category_id\": \"cat-d\", \"amount\": 900}
    ]
  }
}
""")))

        when:
        def movements = buildRepository().getMoneyMovements('budget-new', 30)

        then:
        movements*.id == ['mm-1']
        movements[0].groupId == 'group-1'
        movements[0].eventDate == recentDate
    }

    def "getMoneyMovements supports month and current-date fallbacks"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/budget-new/money_movements'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "money_movements": [
      {"id": "mm-month", "money_movement_group_id": "group-month", "month": "2026-07", "from_category_id": "cat-a", "to_category_id": "cat-b", "amount": 500},
      {"id": "mm-now", "money_movement_group_id": "group-now", "from_category_id": "cat-c", "to_category_id": "cat-d", "amount": 900}
    ]
  }
}
''')))

        when:
        def movements = buildRepository().getMoneyMovements('budget-new', 5000)

        then:
        movements*.id == ['mm-month', 'mm-now']
        movements[0].eventDate == '2026-07-01'
        movements[1].eventDate == java.time.LocalDate.now().toString()
    }

    def "postTransactions sends the expected bulk payload to YNAB"() {
        given:
        stubFor(post(urlEqualTo('/v1/plans/budget-new/transactions/bulk'))
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
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/budget-new/transactions/bulk')))
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

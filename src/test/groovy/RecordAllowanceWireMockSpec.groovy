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

    def "getCategoryInfoByCategoryName flattens multiple category groups and defaults missing balances to zero"() {
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
        "name": "Core",
        "categories": [
          {"id": "cat-allowance", "name": "Allowance", "balance": 5000}
        ]
      },
      {
        "name": "Kids",
        "categories": [
          {"id": "cat-jack", "name": "Jack Silver Account"},
          {"id": "cat-colin", "name": "Colin Bronze Account", "balance": -250}
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
        categories.keySet().containsAll(['Allowance', 'Jack Silver Account', 'Colin Bronze Account'])
        categories['Allowance'].balance == 5000
        categories['Jack Silver Account'].balance == 0
        categories['Colin Bronze Account'].balance == -250
    }

    def "constructor surfaces budget endpoint failures from YNAB"() {
        given:
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"error":{"name":"service_unavailable","detail":"try later"}}')))

        when:
        new RecordAllowance('token', new Date(), true, buildClient())

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB GET /v1/budgets failed with status 503')
        ex.message.contains('try later')
    }

    def "getUser uses simulated YNAB user response"() {
        given:
        stubFor(get(urlEqualTo('/v1/user'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "user": {
      "id": "user-1",
      "name": "Bank Parent"
    }
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), false, buildClient())
        def response = recordAllowance.getUser()
        def requests = wireMockServer.findAll(getRequestedFor(urlEqualTo('/v1/user')))

        then:
        response.data.user.id == 'user-1'
        response.data.user.name == 'Bank Parent'
        requests.size() == 1
        requests[0].getHeader('Authorization') == 'Bearer token'
    }

    def "full allowance flow assembles transactions and posts the expected bulk payload"() {
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
      {"id": "acct-allowance", "name": "Allowance Escrow"}
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
        "name": "Core",
        "categories": [
          {"id": "cat-allowance", "name": "Allowance", "balance": 0}
        ]
      },
      {
        "name": "Kids",
        "categories": [
          {"id": "cat-jack-silver", "name": "Jack Silver Account", "balance": 200000},
          {"id": "cat-jack-give", "name": "Jack Give Bank", "balance": 0},
          {"id": "cat-evan-silver", "name": "Evan Silver Account", "balance": 100000},
          {"id": "cat-evan-give", "name": "Evan Give Bank", "balance": 0},
          {"id": "cat-emily-silver", "name": "Emily Silver Account", "balance": 100000},
          {"id": "cat-emily-give", "name": "Emily Give Bank", "balance": 0},
          {"id": "cat-colin-silver", "name": "Colin Silver Account", "balance": 100000},
          {"id": "cat-colin-bronze", "name": "Colin Bronze Account", "balance": 100000},
          {"id": "cat-colin-give", "name": "Colin Give Bank", "balance": 0},
          {"id": "cat-colin-cd", "name": "Colin Gold CD 2-Month 08/15/25", "balance": 100000}
        ]
      }
    ]
  }
}
''')))
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
        def recordAllowance = new RecordAllowance('token', new GregorianCalendar(2025, Calendar.JULY, 6).time, true, buildClient())

        when:
        def categoryInfo = recordAllowance.getCategoryInfoByCategoryName()
        def allowanceEscrowAccountId = recordAllowance.getAccountId('Allowance Escrow')
        def transactionsThatNeedOffsetting = []
        transactionsThatNeedOffsetting.addAll(recordAllowance.generateInterestTransactionsForSimpleAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(recordAllowance.generateNewAllowanceTransactionsForSimpleAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(recordAllowance.generateInterestTransactionsForAdvancedAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(recordAllowance.generateNewAllowanceTransactionsForAdvancedAccounts(allowanceEscrowAccountId, categoryInfo))

        def transactions = []
        transactions.addAll(transactionsThatNeedOffsetting)
        transactions << recordAllowance.generateOffsettingTransaction(allowanceEscrowAccountId, transactionsThatNeedOffsetting, categoryInfo)
        transactions.addAll(recordAllowance.generateNonInterestBearingTransactions(allowanceEscrowAccountId, categoryInfo))

        def response = recordAllowance.postTransactions(transactions)
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/budgets/budget-new/transactions/bulk')))
        def body = new JsonSlurper().parseText(requests[0].bodyAsString)

        then:
        response.data.bulk.transaction_ids == ['txn-1', 'txn-2']
        requests.size() == 1
        body.transactions.size() == 16
        body.transactions*.payee_name == [
            'Jack Silver Account Interest',
            'Evan Silver Account Interest',
            'Emily Silver Account Interest',
            'Colin Silver Account Interest',
            'Colin Bronze Account Interest',
            'Colin Gold CD 2-Month 08/15/25 Interest',
            'To Jack Silver Account',
            'To Jack Give Bank',
            'To Evan Silver Account',
            'To Evan Give Bank',
            'To Emily Silver Account',
            'To Emily Give Bank',
            'To Colin Silver Account',
            'To Colin Bronze Account',
            'To Colin Give Bank',
            'Allowance '
        ]
        body.transactions*.amount == [300, 150, 150, 150, 100, 250, 3000, 500, 3000, 500, 1000, 500, 1000, 500, 500, -11600]
        body.transactions.every { it.account_id == 'acct-allowance' }
        body.transactions[0].memo == 'Interest'
        body.transactions[6].memo == 'Allowance'
        body.transactions[-1].category_id == 'cat-allowance'
        body.transactions[-1].memo == 'Allowance and Interest combined'
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

    def "postTransactions throws explicit YNAB failure details"() {
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
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB POST /v1/budgets/budget-new/transactions/bulk failed with status 500')
        ex.message.contains('boom')
    }

    private YnabHttpClient buildClient() {
        new YnabHttpClient("http://localhost:${wireMockServer.port()}", 'token')
    }
}

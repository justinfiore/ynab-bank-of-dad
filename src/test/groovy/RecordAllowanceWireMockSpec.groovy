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

class RecordAllowanceWireMockSpec extends Specification {

    WireMockServer wireMockServer

    private RuntimeConfig demoConfig() {
        RuntimeConfig.fromMap([
            budgetName: 'Configured Budget',
            allowanceEscrowAccountName: 'Allowance Escrow',
            allowanceCategoryName: 'Allowance',
            interestMemo: 'Interest',
            allowanceMemo: 'Allowance',
            combinedMemo: 'Allowance and Interest combined',
            nonInterestMemoSuffix: 'Piggy Banks',
            bankSuffixes: [' Spend Bank', ' Save Bank', ' Give Bank'],
            allowanceRates: [' Spend Bank': 1.0, ' Save Bank': 0.5, ' Give Bank': 0.5],
            giveBankRate: 0.5,
            kidsWithoutInterest: [],
            kidsWithSimpleAccounts: [],
            kidsWithAdvancedAccounts: ['Child One', 'Child Two', 'Child Three', 'Child Four'],
            advancedAllowanceDeposits: [
                'Child One': ['Child One Silver Account': 3.0, 'Child One Give Bank': 0.5],
                'Child Two': ['Child Two Silver Account': 3.0, 'Child Two Give Bank': 0.5],
                'Child Three': ['Child Three Silver Account': 1.0, 'Child Three Give Bank': 0.5],
                'Child Four': ['Child Four Silver Account': 1.0, 'Child Four Bronze Account': 0.5, 'Child Four Give Bank': 0.5]
            ],
            accountTypes: ['Bronze', 'Silver', 'Gold CD 2-Month', 'Gold CD 3-Month', 'Gold CD 6-Month', 'First Car Fund'],
            interestRatesByAccountTypeAndDate: [
                'Current': [
                    'Bronze': 0.1,
                    'Silver': 0.15,
                    'Gold CD 2-Month': 0.25,
                    'Gold CD 3-Month': 0.35,
                    'Gold CD 6-Month': 0.65,
                    'First Car Fund': 0.65
                ],
                '2025-06-01': [
                    'Bronze': 0.1,
                    'Silver': 0.5,
                    'Gold CD 2-Month': 0.75,
                    'Gold CD 3-Month': 1.0,
                    'Gold CD 6-Month': 1.25,
                    'First Car Fund': 1.25
                ],
                '2025-04-14': [
                    'Bronze': 0.25,
                    'Silver': 0.75,
                    'Gold CD 2-Month': 1.5,
                    'Gold CD 3-Month': 1.75,
                    'Gold CD 6-Month': 2.0,
                    'First Car Fund': 2.0
                ],
                '2024-12-25': [
                    'Gold CD 2-Month': 1.75,
                    'Gold CD 3-Month': 2.0,
                    'Gold CD 6-Month': 2.25
                ],
                '2024-11-23': [
                    'Gold CD 2-Month': 2.25,
                    'Gold CD 3-Month': 2.5,
                    'Gold CD 6-Month': 2.75
                ]
            ]
        ])
    }

    def setup() {
        wireMockServer = new WireMockServer(0)
        wireMockServer.start()
        configureFor('localhost', wireMockServer.port())
    }

    def cleanup() {
        wireMockServer.stop()
    }

    def "constructor selects newest Configured Budget budget using simulated YNAB budgets response"() {
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

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())

        then:
        recordAllowance.budgetId == 'budget-new'
    }

    def "getAccountId and getCategoryInfoByCategoryName use simulated YNAB responses"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/plans/budget-new/accounts'))
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
          {"id": "cat-child-one", "name": "Child One Silver Account", "balance": 1000}
        ]
      }
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())
        def accountId = recordAllowance.getAccountId('Allowance Escrow')
        def categories = recordAllowance.getCategoryInfoByCategoryName()

        then:
        accountId == 'acct-1'
        categories['Allowance'].id == 'cat-allowance'
        categories['Child One Silver Account'].balance == 1000
    }

    def "constructor throws clear error when no Configured Budget budget exists"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
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
        new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Could not find budget named 'Configured Budget'")
    }

    def "getAccountId throws clear error when required account is missing"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/plans/budget-new/accounts'))
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
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())
        recordAllowance.getAccountId('Allowance Escrow')

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Could not find account named 'Allowance Escrow'")
    }

    def "getCategoryInfoByCategoryName can reveal missing required categories from simulated YNAB responses"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
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
          {"id": "cat-child-one", "name": "Child One Silver Account", "balance": 1000}
        ]
      }
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())
        def categories = recordAllowance.getCategoryInfoByCategoryName()

        then:
        !categories.containsKey('Allowance')
        categories['Child One Silver Account'].balance == 1000
    }

    def "getCategoryInfoByCategoryName flattens multiple category groups and defaults missing balances to zero"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/plans/budget-new/categories'))
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
          {"id": "cat-child-one", "name": "Child One Silver Account"},
          {"id": "cat-child-four", "name": "Child Four Bronze Account", "balance": -250}
        ]
      }
    ]
  }
}
''')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())
        def categories = recordAllowance.getCategoryInfoByCategoryName()

        then:
        categories.keySet().containsAll(['Allowance', 'Child One Silver Account', 'Child Four Bronze Account'])
        categories['Allowance'].balance == 5000
        categories['Child One Silver Account'].balance == 0
        categories['Child Four Bronze Account'].balance == -250
    }

    def "constructor surfaces budget endpoint failures from YNAB"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"error":{"name":"service_unavailable","detail":"try later"}}')))

        when:
        new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB GET /v1/plans failed with status 503')
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
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), false, buildClient())
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
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(get(urlEqualTo('/v1/plans/budget-new/accounts'))
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
        stubFor(get(urlEqualTo('/v1/plans/budget-new/categories'))
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
          {"id": "cat-child-one-silver", "name": "Child One Silver Account", "balance": 200000},
          {"id": "cat-child-one-give", "name": "Child One Give Bank", "balance": 0},
          {"id": "cat-child-two-silver", "name": "Child Two Silver Account", "balance": 100000},
          {"id": "cat-child-two-give", "name": "Child Two Give Bank", "balance": 0},
          {"id": "cat-child-three-silver", "name": "Child Three Silver Account", "balance": 100000},
          {"id": "cat-child-three-give", "name": "Child Three Give Bank", "balance": 0},
          {"id": "cat-child-four-silver", "name": "Child Four Silver Account", "balance": 100000},
          {"id": "cat-child-four-bronze", "name": "Child Four Bronze Account", "balance": 100000},
          {"id": "cat-child-four-give", "name": "Child Four Give Bank", "balance": 0},
          {"id": "cat-child-four-cd", "name": "Child Four Gold CD 2-Month 08/15/25", "balance": 100000}
        ]
      }
    ]
  }
}
''')))
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
        def recordAllowance = new RecordAllowance('token', new GregorianCalendar(2025, Calendar.JULY, 6).time, demoConfig(), true, buildClient())

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
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/budget-new/transactions/bulk')))
        def body = new JsonSlurper().parseText(requests[0].bodyAsString)

        then:
        response.data.bulk.transaction_ids == ['txn-1', 'txn-2']
        requests.size() == 1
        body.transactions.size() == 16
        body.transactions*.payee_name == [
            'Child One Silver Account Interest',
            'Child Two Silver Account Interest',
            'Child Three Silver Account Interest',
            'Child Four Silver Account Interest',
            'Child Four Bronze Account Interest',
            'Child Four Gold CD 2-Month 08/15/25 Interest',
            'To Child One Silver Account',
            'To Child One Give Bank',
            'To Child Two Silver Account',
            'To Child Two Give Bank',
            'To Child Three Silver Account',
            'To Child Three Give Bank',
            'To Child Four Silver Account',
            'To Child Four Bronze Account',
            'To Child Four Give Bank',
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
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(post(urlEqualTo('/v1/plans/budget-new/transactions/bulk'))
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
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())
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
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/budget-new/transactions/bulk')))
        def body = new JsonSlurper().parseText(requests[0].bodyAsString)

        then:
        response.data.bulk.transaction_ids == ['txn-1']
        requests.size() == 1
        requests[0].getHeader('Authorization') == 'Bearer token'
        body.transactions == transactions
    }

    def "postTransactions throws explicit YNAB failure details"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "budgets": [
      {"id": "budget-new", "name": "Configured Budget", "last_modified_on": "2025-07-08T12:00:00Z"}
    ]
  }
}
''')))
        stubFor(post(urlEqualTo('/v1/plans/budget-new/transactions/bulk'))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"error":{"name":"internal_server_error","detail":"boom"}}')))

        when:
        def recordAllowance = new RecordAllowance('token', new Date(), demoConfig(), true, buildClient())
        recordAllowance.postTransactions([[account_id: 'acct-1', amount: 1000]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB POST /v1/plans/budget-new/transactions/bulk failed with status 500')
        ex.message.contains('boom')
    }

    private YnabHttpClient buildClient() {
        new YnabHttpClient("http://localhost:${wireMockServer.port()}", 'token')
    }
}

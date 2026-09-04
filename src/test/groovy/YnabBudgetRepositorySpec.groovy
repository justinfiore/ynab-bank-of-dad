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

import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant

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

    def "getBudgets maps plan payloads into summaries"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "plans": [
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
    "plans": [
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

    def "budget discovery supplies the budget name used in rate limit logs"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
                .withBody('{"data":{"plans":[{"id":"budget-1","name":"Fiores","last_modified_on":"2025-07-01T12:00:00Z"}]}}')))
        stubFor(get(urlPathEqualTo('/v1/plans/budget-1/transactions'))
            .inScenario('transaction rate limit')
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willSetStateTo('retried')
            .willReturn(aResponse().withStatus(429)))
        stubFor(get(urlPathEqualTo('/v1/plans/budget-1/transactions'))
            .inScenario('transaction rate limit')
            .whenScenarioStateIs('retried')
            .willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
                .withBody('{"data":{"transactions":[],"server_knowledge":1}}')))
        List<String> infos = []
        def client = new YnabHttpClient("http://localhost:${wireMockServer.port()}", 'token',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration ignored -> }, { Instant.EPOCH }, null, { String message -> infos << message })
        def repository = new YnabBudgetRepository(client)

        when:
        String budgetId = repository.getLatestBudgetId('Fiores')
        repository.getTransactions(budgetId, 30)

        then:
        infos.size() == 1
        infos[0].contains("for budget 'Fiores' rate limited on token 1 of 1")
        !infos[0].contains('budget-1')
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

    def "getCategoryInfoByCategoryName tolerates category groups without categories"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/budget-new/categories'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"data":{"category_groups":[{"name":"Empty"},{"name":"Null","categories":null}]}}')))

        expect:
        buildRepository().getCategoryInfoByCategoryName('budget-new') == [:]
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
            "payee_id": "payee-1",
            "payee_name": "Shoe Store",
        "deleted": false,
        "category_id": "cat-shoes",
        "category_name": "Child One Spend Bank",
        "subtransactions": [
          {
            "id": "sub-1",
            "transaction_id": "txn-1",
            "amount": -700,
            "memo": "Split one",
            "payee_id": "payee-split",
            "payee_name": "Split Store",
            "deleted": true,
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
        def delta = buildRepository().getTransactions('budget-new', 30)

        then:
        delta.serverKnowledge == 77
        delta.transactions*.id == ['txn-1']
        delta.transactions[0].serverKnowledge == 77
        delta.transactions[0].payeeId == 'payee-1'
        delta.transactions[0].payeeName == 'Shoe Store'
        !delta.transactions[0].deleted
        delta.transactions[0].subtransactions*.id == ['sub-1']
        delta.transactions[0].subtransactions[0].categoryName == 'Child One Save Bank'
        delta.transactions[0].subtransactions[0].payeeId == 'payee-split'
        delta.transactions[0].subtransactions[0].payeeName == 'Split Store'
        delta.transactions[0].subtransactions[0].deleted
    }

    def "empty incremental transaction response retains response server knowledge for cursor advancement"() {
        given:
        stubFor(get(urlPathEqualTo('/v1/plans/budget-new/transactions'))
            .withQueryParam('since_date', absent())
            .withQueryParam('last_knowledge_of_server', equalTo('77'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"data":{"server_knowledge":88,"transactions":[]}}')))
        when:
        def delta = buildRepository().getTransactions('budget-new', 30, 77)

        then:
        delta.transactions.empty
        delta.serverKnowledge == 88
    }

    def "getParentTransaction fetches complete split composition"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/budget-new/transactions/txn-split'))
            .willReturn(jsonResponse([data: [server_knowledge: 91, transaction: [
                id: 'txn-split', date: '2026-07-01', amount: -300, approved: true,
                deleted: false, subtransactions: [
                    [id: 'sub-1', transaction_id: 'txn-split', amount: -100, deleted: false],
                    [id: 'sub-2', transaction_id: 'txn-split', amount: -200, deleted: false]
                ]
            ]]])))

        when:
        def transaction = buildRepository().getParentTransaction('budget-new', 'txn-split')

        then:
        transaction.serverKnowledge == 91
        transaction.subtransactions*.id == ['sub-1', 'sub-2']
    }

    def "getMoneyMovements returns a typed complete unfiltered snapshot"() {
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
    \"server_knowledge\": 93,
    \"money_movements\": [
      {\"id\": \"mm-1\", \"money_movement_group_id\": \"group-1\", \"moved_at\": \"${recentDate}T12:00:00Z\", \"from_category_id\": \"cat-a\", \"to_category_id\": \"cat-b\", \"amount\": 500},
      {\"id\": \"mm-2\", \"money_movement_group_id\": \"group-2\", \"moved_at\": \"${staleDate}T12:00:00Z\", \"from_category_id\": \"cat-c\", \"to_category_id\": \"cat-d\", \"amount\": 900}
    ]
  }
}
""")))

        when:
        def snapshot = buildRepository().getMoneyMovements('budget-new')

        then:
        snapshot.serverKnowledge == 93
        snapshot.movements*.id == ['mm-1', 'mm-2']
        snapshot.movements[0].groupId == 'group-1'
        snapshot.movements[0].eventDate == recentDate
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
    "server_knowledge": 94,
    "money_movements": [
      {"id": "mm-month", "money_movement_group_id": "group-month", "month": "2026-07", "from_category_id": "cat-a", "to_category_id": "cat-b", "amount": 500},
      {"id": "mm-now", "money_movement_group_id": "group-now", "from_category_id": "cat-c", "to_category_id": "cat-d", "amount": 900}
    ]
  }
}
''')))

        when:
        def movements = buildRepository().getMoneyMovements('budget-new').movements

        then:
        movements*.id == ['mm-month', 'mm-now']
        movements[0].eventDate == '2026-07-01'
        movements[1].eventDate == java.time.LocalDate.now().toString()
    }

    def "getLatestServerKnowledge reads plan server knowledge from plan details response"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/budget-new'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "plan": {
      "id": "budget-new",
      "server_knowledge": 123
    }
  }
}
''')))

        expect:
        buildRepository().getLatestServerKnowledge('budget-new') == 123
    }

    def "parent event model keeps nullable payee and deletion fields"() {
        expect:
        new ParentTransactionEvent('txn-1', null, 0, null, null, null, null, null, [], null, null, null).payeeName == null
        new ParentSubtransactionEvent('sub-1', 'txn-1', 0, null, null, null, null, null, null).deleted == null
    }

    def "child transaction lookup maps the documented response"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/child-budget/transactions/txn-1'))
            .willReturn(jsonResponse(childTransactionResponse('txn-1', 101))))

        when:
        def result = buildRepository().getChildTransaction('child-budget', 'txn-1')

        then:
        result.found()
        result.serverKnowledge == 101
        result.transaction.id == 'txn-1'
        result.transaction.payeeId == 'payee-1'
        result.transaction.payeeName == 'Store'
        !result.transaction.deleted
    }

    def "child transaction lookup represents documented 404 as not found"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/child-budget/transactions/missing'))
            .willReturn(aResponse().withStatus(404).withHeader('Content-Type', 'application/json').withBody('{"error":{"detail":"not found"}}')))

        expect:
        !buildRepository().getChildTransaction('child-budget', 'missing').found()
    }

    def "child transaction update sends only supplied supported fields in wrapper"() {
        given:
        stubFor(put(urlEqualTo('/v1/plans/child-budget/transactions/txn-1'))
            .willReturn(jsonResponse(childTransactionResponse('txn-1', 102))))
        def fields = [account_id: 'acct-2', date: '2026-07-02', amount: -900, payee_name: 'New Store', cleared: 'cleared', approved: false]

        when:
        def result = buildRepository().updateChildTransaction('child-budget', 'txn-1', fields)
        def request = wireMockServer.findAll(putRequestedFor(urlEqualTo('/v1/plans/child-budget/transactions/txn-1')))[0]

        then:
        result.transaction.id == 'txn-1'
        result.serverKnowledge == 102
        new JsonSlurper().parseText(request.bodyAsString) == [transaction: fields]
        request.getHeader('Authorization') == 'Bearer token'
    }

    def "child transaction update rejects import id and unsupported fields before HTTP"() {
        when:
        buildRepository().updateChildTransaction('child-budget', 'txn-1', [amount: -900, import_id: 'immutable'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('import_id')
        wireMockServer.allServeEvents.empty
    }

    def "import identity recovery uses documented bulk patch lookup and returns matched transaction"() {
        given:
        stubFor(patch(urlEqualTo('/v1/plans/child-budget/transactions'))
            .willReturn(jsonResponse([data: [server_knowledge: 104, transaction_ids: ['txn-existing'],
                transactions: [[id: 'txn-existing', account_id: 'acct-1', date: '2026-07-01', amount: -800,
                    payee_name: 'Store', memo: 'Child memo', cleared: 'cleared', approved: false,
                    deleted: false]]]])))
        def fields = [account_id: 'acct-1', date: '2026-07-01', amount: -800,
            payee_name: 'Store', memo: 'Child memo', cleared: 'cleared', approved: false,
            import_id: 'must-not-overwrite', unsupported: 'drop-me']

        when:
        def result = buildRepository().recoverChildTransactionByImportId('child-budget', 'PCBS:stable', fields)
        def request = wireMockServer.findAll(patchRequestedFor(
            urlEqualTo('/v1/plans/child-budget/transactions'))).first()

        then:
        result.transaction.id == 'txn-existing'
        result.serverKnowledge == 104
        new JsonSlurper().parseText(request.bodyAsString) == [transactions: [[
            account_id: 'acct-1', date: '2026-07-01', amount: -800, payee_name: 'Store',
            memo: 'Child memo', cleared: 'cleared', approved: false, import_id: 'PCBS:stable'
        ]]]
    }

    def "child transaction delete maps success and treats 404 as already absent"() {
        given:
        stubFor(delete(urlEqualTo('/v1/plans/child-budget/transactions/txn-1'))
            .willReturn(jsonResponse(childTransactionResponse('txn-1', 103))))
        stubFor(delete(urlEqualTo('/v1/plans/child-budget/transactions/missing'))
            .willReturn(aResponse().withStatus(404).withHeader('Content-Type', 'application/json').withBody('{"error":{"detail":"not found"}}')))
        def repository = buildRepository()

        when:
        def deleted = repository.deleteChildTransaction('child-budget', 'txn-1')
        def absent = repository.deleteChildTransaction('child-budget', 'missing')

        then:
        !deleted.alreadyAbsent
        deleted.transaction.id == 'txn-1'
        deleted.serverKnowledge == 103
        absent.alreadyAbsent
        absent.transaction == null
    }

    def "child transaction operations reject malformed success responses"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/child-budget/transactions/txn-1')).willReturn(jsonResponse([data: [server_knowledge: 1]])))
        stubFor(put(urlEqualTo('/v1/plans/child-budget/transactions/txn-1')).willReturn(jsonResponse([data: [transaction: [id: 'txn-1']]])))
        stubFor(delete(urlEqualTo('/v1/plans/child-budget/transactions/txn-1')).willReturn(jsonResponse([data: [:]])))
        def repository = buildRepository()

        when:
        switch (operation) {
            case 'lookup': repository.getChildTransaction('child-budget', 'txn-1'); break
            case 'update': repository.updateChildTransaction('child-budget', 'txn-1', [amount: -1]); break
            case 'delete': repository.deleteChildTransaction('child-budget', 'txn-1'); break
        }

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("child transaction ${operation} response")

        where:
        operation << ['lookup', 'update', 'delete']
    }

    def "child transaction operations surface authentication validation and server errors"() {
        given:
        switch (operation) {
            case 'lookup': stubFor(get(urlEqualTo(path)).willReturn(errorResponse(status))); break
            case 'update': stubFor(put(urlEqualTo(path)).willReturn(errorResponse(status))); break
            case 'delete': stubFor(delete(urlEqualTo(path)).willReturn(errorResponse(status))); break
        }
        def repository = buildRepository()

        when:
        switch (operation) {
            case 'lookup': repository.getChildTransaction('child-budget', 'txn-1'); break
            case 'update': repository.updateChildTransaction('child-budget', 'txn-1', [amount: -1]); break
            case 'delete': repository.deleteChildTransaction('child-budget', 'txn-1'); break
        }

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "YNAB ${method} transactions failed with status ${status}"
        !ex.message.contains('child-budget')
        !ex.message.contains('txn-1')

        where:
        operation | method   | status
        'lookup'  | 'GET'    | 401
        'update'  | 'PUT'    | 400
        'delete'  | 'DELETE' | 500

        path = '/v1/plans/child-budget/transactions/txn-1'
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

    def "createAccount posts the requested SaveAccount type with zero balance and returns the new id"() {
        given:
        stubFor(post(urlEqualTo('/v1/plans/budget-new/accounts'))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader('Content-Type', 'application/json')
                .withBody("""
{
  "data": {
    "account": {"id": "acct-created", "name": "Child One Spend", "type": "${accountType}", "on_budget": ${onBudget}, "balance": 0}
  }
}
""")))

        when:
        def created = buildRepository().createAccount('budget-new', 'Child One Spend', accountType)
        def requests = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/budget-new/accounts')))
        def body = new JsonSlurper().parseText(requests[0].bodyAsString)

        then:
        created.id == 'acct-created'
        requests.size() == 1
        body == [account: [name: 'Child One Spend', type: accountType, balance: 0]]

        where:
        accountType  | onBudget
        'checking'   | true
        'otherAsset' | false
    }

    def "createAccount rejects types outside the live SaveAccountType enum"() {
        when:
        buildRepository().createAccount('budget-new', 'Child One Spend', 'asset')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("YNAB SaveAccount type 'asset' is not supported")
        wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/budget-new/accounts'))).isEmpty()
    }

    def "saveAccountTypeForOnBudget maps checking vs otherAsset"() {
        expect:
        YnabBudgetRepository.saveAccountTypeForOnBudget(true) == 'checking'
        YnabBudgetRepository.saveAccountTypeForOnBudget(false) == 'otherAsset'
    }

    def "accountsByName preserves balances and defaults missing balances to zero"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans/budget-new/accounts'))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody('''
{
  "data": {
    "accounts": [
      {"id": "acct-spend", "name": "Spend", "balance": 12500, "deleted": false},
      {"id": "acct-save", "name": "Save", "deleted": false},
      {"id": "acct-gone", "name": "Gone", "balance": 9, "deleted": true},
      {"id": "acct-dup", "name": "Spend", "balance": 1, "deleted": false}
    ]
  }
}
''')))

        when:
        def accounts = buildRepository().accountsByName('budget-new')
        def ids = buildRepository().accountIdByName('budget-new')

        then:
        accounts['Spend'] == new AccountSnapshot('acct-spend', 'Spend', 12500)
        accounts['Save'] == new AccountSnapshot('acct-save', 'Save', 0)
        !accounts.containsKey('Gone')
        ids == [Spend: 'acct-spend', Save: 'acct-save']
    }

    def "createAccount throws when the response is missing account.id"() {
        given:
        stubFor(post(urlEqualTo('/v1/plans/budget-new/accounts'))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader('Content-Type', 'application/json')
                .withBody('{"data":{"account":{"name":"Child One Spend"}}}')))

        when:
        buildRepository().createAccount('budget-new', 'Child One Spend', 'checking')

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('missing account.id')
    }

    private YnabBudgetRepository buildRepository() {
        new YnabBudgetRepository(new YnabHttpClient("http://localhost:${wireMockServer.port()}", 'token'))
    }

    private static Map childTransactionResponse(String id, int serverKnowledge) {
        [data: [server_knowledge: serverKnowledge, transaction: [
            id: id, account_id: 'acct-1', date: '2026-07-01', amount: -800,
            payee_id: 'payee-1', payee_name: 'Store', category_id: null, memo: 'Child memo',
            cleared: 'cleared', approved: false, flag_color: null, deleted: false
        ]]]
    }

    private static def jsonResponse(Object body) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json')
            .withBody(groovy.json.JsonOutput.toJson(body))
    }

    private static def errorResponse(int status) {
        aResponse().withStatus(status).withHeader('Content-Type', 'application/json')
            .withBody('{"error":{"detail":"failure"}}')
    }
}

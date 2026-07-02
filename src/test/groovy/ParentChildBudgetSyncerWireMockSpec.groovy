import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.sql.DriverManager

import static com.github.tomakehurst.wiremock.client.WireMock.*

class ParentChildBudgetSyncerWireMockSpec extends Specification {

    @TempDir
    Path tempDir

    WireMockServer wireMockServer

    def setup() {
        wireMockServer = new WireMockServer(0)
        wireMockServer.start()
        configureFor('localhost', wireMockServer.port())
    }

    def cleanup() {
        wireMockServer.stop()
    }

    def "one live cycle mirrors approved parent transactions split transactions and money movements into child budgets"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-unapproved', date: '2026-07-01', amount: -1300, memo: 'Ignore me', approved: false, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-unmapped', date: '2026-07-01', amount: -1400, memo: 'Parent only', approved: true, category_id: 'cat-parent-only', category_name: 'Parent Only', subtransactions: []],
            [id: 'txn-split', date: '2026-07-02', amount: -1500, memo: 'Split parent memo', approved: true, category_id: null, category_name: null, subtransactions: [
                [id: 'sub-child-one-save', transaction_id: 'txn-split', amount: -700, memo: 'Split save', category_id: 'cat-child-one-save', category_name: 'Child One Save Bank'],
                [id: 'sub-child-two-spend', transaction_id: 'txn-split', amount: -800, memo: null, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank'],
                [id: 'sub-unmapped', transaction_id: 'txn-split', amount: -900, memo: 'Ignore split', category_id: 'cat-parent-only', category_name: 'Parent Only']
            ]]
        ], 41)
        stubMoneyMovements([
            [id: 'mm-transfer', money_movement_group_id: 'group-1', moved_at: '2026-07-03T12:00:00Z', from_category_id: 'cat-child-one-spend', to_category_id: 'cat-child-two-spend', amount: 500],
            [id: 'mm-parent-only', money_movement_group_id: 'group-2', moved_at: '2026-07-03T12:00:00Z', from_category_id: 'cat-parent-only', to_category_id: 'cat-parent-only', amount: 600]
        ])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-one-budget-id', ['child-one-created-1', 'child-one-created-2', 'child-one-created-3'])
        stubChildPost('child-two-budget-id', ['child-two-created-1', 'child-two-created-2'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        List childOnePosts = postedTransactions('child-one-budget-id')
        childOnePosts.size() == 3
        childOnePosts*.account_id.unique() == ['child-one-account-id']
        childOnePosts.find { it.memo == 'Shoes' && it.amount == -1200 && it.payee_name == null && it.category_id == null }
        childOnePosts.find { it.memo == 'Split save' && it.amount == -700 && it.payee_name == null && it.category_id == null }
        childOnePosts.find { it.memo == 'From Child One Spend Bank to Child Two Spend Bank' && it.amount == -500 && it.payee_name == 'To Child Two Spend Bank' && it.category_id == null }

        and:
        List childTwoPosts = postedTransactions('child-two-budget-id')
        childTwoPosts.size() == 2
        childTwoPosts*.account_id.unique() == ['child-two-account-id']
        childTwoPosts.find { it.memo == 'Split parent memo' && it.amount == -800 && it.payee_name == null && it.category_id == null }
        childTwoPosts.find { it.memo == 'From Child One Spend Bank to Child Two Spend Bank' && it.amount == 500 && it.payee_name == 'From Child One Spend Bank' && it.category_id == null }

        and:
        allPostedImportIds().every { it.startsWith('PCBS:') }
        tableCount('sync_runs') == 1
        tableCount('source_events') == 5
        tableCount('sync_mappings') == 5
        tableCount('applied_transactions') == 5
        cursorValue('transactions.last_server_knowledge') == 41
    }

    def "second live cycle uses saved transaction cursor and duplicate idempotency state prevents reposting"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 41)
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 42, 41)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-one-created-1'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 1
        tableCount('sync_runs') == 2
        tableCount('source_events') == 1
        tableCount('sync_mappings') == 1
        tableCount('applied_transactions') == 1
        cursorValue('transactions.last_server_knowledge') == 42
        verify(getRequestedFor(urlPathEqualTo('/v1/budgets/parent-budget-id/transactions'))
            .withQueryParam('last_knowledge_of_server', equalTo('41')))
    }

    def "dry run exercises WireMock reads and child account resolution without posting or mutating SQLite state"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 41)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        def syncer = syncer(true)

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/budgets/child-one-budget-id/transactions/bulk')))
        tableCount('sync_runs') == 0
        tableCount('source_events') == 0
        tableCount('sync_mappings') == 0
        tableCount('applied_transactions') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "child API failure records failed applied transaction state and continues other child targets"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 51)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPostFailure('child-one-budget-id')
        stubChildPost('child-two-budget-id', ['child-two-created-1'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows()*.status.sort() == ['applied', 'failed']
        appliedRows().find { it.status == 'failed' }.failure_reason.contains('YNAB POST /v1/budgets/child-one-budget-id/transactions/bulk failed with status 500')
        cursorValue('transactions.last_server_knowledge') == 51
    }

    def "missing child account fails that child target with clear state while other targets still apply"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 52)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'wrong-account-id', 'Wrong Account')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-two-budget-id', ['child-two-created-1'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/budgets/child-one-budget-id/transactions/bulk')))
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows().find { it.status == 'failed' }.failure_reason.contains("Could not find account named 'Child One Checking'")
        cursorValue('transactions.last_server_knowledge') == 52
    }

    private ParentChildBudgetSyncer syncer(boolean dryRun) {
        String dbPath = tempDir.resolve('syncstate-wiremock.db').toString()
        SyncConfig syncConfig = new SyncConfig(
            new BudgetRef('Parent Budget', 'YNAB_PARENT_TOKEN'),
            [
                new ChildBudgetSyncTarget('child-one', 'Child One Budget', 'YNAB_CHILD_ONE_TOKEN', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking'),
                new ChildBudgetSyncTarget('child-two', 'Child Two Budget', 'YNAB_CHILD_TWO_TOKEN', ['Child Two Spend Bank'], 'Child Two Checking')
            ],
            300,
            new SyncLoggingConfig(tempDir.resolve('parent-child-sync.log').toString(), 'INFO', 7, 10),
            new SyncStateConfig(dbPath, 45, 45)
        )
        SyncStateStore stateStore = new SyncStateStore(dbPath)
        stateStore.initialize()
        new ParentChildBudgetSyncer(
            new RuntimeConfig(sync: syncConfig),
            syncConfig,
            dryRun,
            dbPath,
            1,
            new YnabBudgetRepository(buildClient('parent-token')),
            stateStore,
            [
                new ChildSyncContext(syncConfig.childBudgets[0], new YnabBudgetRepository(buildClient('child-one-token'))),
                new ChildSyncContext(syncConfig.childBudgets[1], new YnabBudgetRepository(buildClient('child-two-token')))
            ]
        )
    }

    private YnabHttpClient buildClient(String token) {
        new YnabHttpClient("http://localhost:${wireMockServer.port()}", token)
    }

    private void stubCommonBudgetDiscovery() {
        stubFor(get(urlEqualTo('/v1/budgets'))
            .willReturn(jsonResponse([
                data: [budgets: [
                    [id: 'parent-budget-id', name: 'Parent Budget', last_modified_on: '2026-07-01T12:00:00Z'],
                    [id: 'child-one-budget-id', name: 'Child One Budget', last_modified_on: '2026-07-01T12:00:00Z'],
                    [id: 'child-two-budget-id', name: 'Child Two Budget', last_modified_on: '2026-07-01T12:00:00Z']
                ]]
            ])))
    }

    private void stubParentCategories() {
        stubFor(get(urlEqualTo('/v1/budgets/parent-budget-id/categories'))
            .willReturn(jsonResponse([
                data: [category_groups: [[
                    name: 'Kids',
                    categories: [
                        [id: 'cat-child-one-spend', name: 'Child One Spend Bank', balance: 0],
                        [id: 'cat-child-one-save', name: 'Child One Save Bank', balance: 0],
                        [id: 'cat-child-two-spend', name: 'Child Two Spend Bank', balance: 0],
                        [id: 'cat-parent-only', name: 'Parent Only', balance: 0]
                    ]
                ]]]
            ])))
    }

    private void stubParentTransactions(List<Map> transactions, int serverKnowledge, Integer lastKnowledge = null) {
        def mapping = get(urlPathEqualTo('/v1/budgets/parent-budget-id/transactions'))
            .withQueryParam('since_date', matching('\\d{4}-\\d{2}-\\d{2}'))
        if (lastKnowledge == null) {
            mapping.withQueryParam('last_knowledge_of_server', absent())
        } else {
            mapping.withQueryParam('last_knowledge_of_server', equalTo(lastKnowledge.toString()))
        }
        stubFor(mapping.willReturn(jsonResponse([data: [server_knowledge: serverKnowledge, transactions: transactions]])))
    }

    private void stubMoneyMovements(List<Map> movements) {
        stubFor(get(urlEqualTo('/v1/budgets/parent-budget-id/money_movements'))
            .willReturn(jsonResponse([data: [money_movements: movements]])))
    }

    private void stubChildAccounts(String budgetId, String accountId, String accountName) {
        stubFor(get(urlEqualTo("/v1/budgets/${budgetId}/accounts"))
            .willReturn(jsonResponse([data: [accounts: [[id: accountId, name: accountName]]]])))
    }

    private void stubChildPost(String budgetId, List<String> transactionIds) {
        stubFor(post(urlEqualTo("/v1/budgets/${budgetId}/transactions/bulk"))
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: transactionIds]]])))
    }

    private void stubChildPostFailure(String budgetId) {
        stubFor(post(urlEqualTo("/v1/budgets/${budgetId}/transactions/bulk"))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader('Content-Type', 'application/json')
                .withBody(JsonOutput.toJson([error: [id: '500', detail: 'simulated child failure']]))))
    }

    private static def jsonResponse(Object body) {
        aResponse()
            .withStatus(200)
            .withHeader('Content-Type', 'application/json')
            .withBody(JsonOutput.toJson(body))
    }

    private List<Map> postedTransactions(String budgetId) {
        wireMockServer.findAll(postRequestedFor(urlEqualTo("/v1/budgets/${budgetId}/transactions/bulk"))).collectMany { event ->
            new JsonSlurper().parseText(event.bodyAsString).transactions as List<Map>
        }
    }

    private List<String> allPostedImportIds() {
        ['child-one-budget-id', 'child-two-budget-id'].collectMany { postedTransactions(it) }*.import_id
    }

    private int tableCount(String tableName) {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM ${tableName}")
            rs.next()
            rs.getInt(1)
        }
    }

    private Integer cursorValue(String key) {
        withDb { connection ->
            def statement = connection.prepareStatement('SELECT value_integer FROM sync_cursors WHERE key = ?')
            statement.setString(1, key)
            def rs = statement.executeQuery()
            rs.next() ? rs.getInt(1) : null
        }
    }

    private List<Map> appliedRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery('SELECT status, failure_reason FROM applied_transactions ORDER BY id')
            List rows = []
            while (rs.next()) {
                rows << [status: rs.getString('status'), failure_reason: rs.getString('failure_reason')]
            }
            rows
        }
    }

    private <T> T withDb(Closure<T> closure) {
        def connection = DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve('syncstate-wiremock.db')}")
        try {
            closure.call(connection)
        } finally {
            connection.close()
        }
    }
}

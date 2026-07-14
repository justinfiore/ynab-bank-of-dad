import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.sql.DriverManager

class ParentChildBudgetSyncerSpec extends Specification {

    @TempDir
    Path tempDir

    def "cli options parse sync-specific flags"() {
        when:
        def options = SyncCliOptions.parse(['--dry-run', '--config', 'custom.yaml', '--sync-state-db-path', 'state.db', '--max-cycles', '2'] as String[])

        then:
        options.dryRun
        options.configPath == 'custom.yaml'
        options.syncStateDbPath == 'state.db'
        options.maxCycles == 2
    }

    def "state store initializes sqlite schema and records applied transactions"() {
        given:
        def dbPath = tempDir.resolve('syncstate.db').toString()
        def store = new SyncStateStore(dbPath)
        store.initialize()
        def plan = new ChildTransactionPlan(
            'parent-budget', 'child-one', 'Child Budget', 'spend', 'Child One Spend Bank',
            'transaction', 'txn-1', null, null, null, 'idem-1', 'Child Checking',
            '2026-07-01', -1200, 'Shoes', 'Target Payee', true
        )

        when:
        long runId = store.startRun(false, 300, 'parent-budget')
        long sourceEventId = store.recordSourceEvent(plan)
        long mappingId = store.recordMapping(sourceEventId, plan, 'child-budget-id', 'acct-1')
        store.recordAppliedTransaction(mappingId, runId, 'child-budget-id', 'child-txn-1', 'applied', null, false)
        store.finishRun(runId, 'succeeded', null)

        then:
        store.hasAppliedIdempotencyKey('idem-1')

        and:
        def connection = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        def rs = connection.createStatement().executeQuery('SELECT COUNT(*) FROM applied_transactions')
        rs.next()
        rs.getInt(1) == 1
        connection.close()
    }

    def "buildPlans includes split transactions, ignores unapproved events, and fans out money movements"() {
        given:
        def syncConfig = sampleSyncConfig(tempDir.resolve('syncstate.db').toString())
        def syncer = new ParentChildBudgetSyncer(
            new RuntimeConfig(sync: syncConfig),
            syncConfig,
            true,
            tempDir.resolve('syncstate.db').toString(),
            1,
            null,
            new SyncStateStore(tempDir.resolve('syncstate.db').toString()),
            [
                new ChildSyncContext(syncConfig.childBudgets[0], new FakeYnabBudgetRepository(), null, null),
                new ChildSyncContext(syncConfig.childBudgets[1], new FakeYnabBudgetRepository(), null, null)
            ]
        )

        and:
        Map<String, CategorySnapshot> categoriesById = [
            'cat-1': new CategorySnapshot('cat-1', 'Child One Spend Bank', 0),
            'cat-2': new CategorySnapshot('cat-2', 'Child One Save Bank', 0),
            'cat-3': new CategorySnapshot('cat-3', 'Child Two Spend Bank', 0)
        ]
        def transactions = [
            new ParentTransactionEvent('txn-approved', '2026-07-01', -1200, 'Shoes', true, 12, 'cat-1', 'Child One Spend Bank', []),
            new ParentTransactionEvent('txn-unapproved', '2026-07-01', -1300, 'Ignore', false, 12, 'cat-1', 'Child One Spend Bank', []),
            new ParentTransactionEvent('txn-split', '2026-07-02', -1500, 'Split', true, 12, null, null, [
                new ParentSubtransactionEvent('sub-1', 'txn-split', -700, 'Split one', 'cat-2', 'Child One Save Bank'),
                new ParentSubtransactionEvent('sub-2', 'txn-split', -800, 'Split two', 'cat-3', 'Child Two Spend Bank')
            ])
        ]
        def movements = [
            new MoneyMovementEvent('mm-1', 'group-1', '2026-07-03', 'cat-1', 'cat-3', 500)
        ]

        when:
        def plans = syncer.buildPlans('parent-budget-id', categoriesById, transactions, movements)

        then:
        plans.findAll { it.eventType == 'transaction' }*.parentTransactionId == ['txn-approved']
        plans.findAll { it.eventType == 'subtransaction' }*.parentSubtransactionId.toSet() == ['sub-1', 'sub-2'] as Set
        plans.findAll { it.eventType == 'money_movement' }.size() == 2
        plans*.targetChildKey.toSet() == ['child-one', 'child-two'] as Set
    }

    def "dry-run applyPlans does not persist sqlite mappings"() {
        given:
        def dbPath = tempDir.resolve('dryrun.db').toString()
        def store = new SyncStateStore(dbPath)
        store.initialize()
        def childRepository = new FakeYnabBudgetRepository('child-budget-id', 'acct-1')
        def syncConfig = sampleSyncConfig(dbPath)
        def syncer = new ParentChildBudgetSyncer(
            new RuntimeConfig(sync: syncConfig),
            syncConfig,
            true,
            dbPath,
            1,
            null,
            store,
            [new ChildSyncContext(childTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN', [['spend', ['Child One Spend Bank'], 'Child One Checking']]), childRepository, null, null)]
        )
        def plans = [
            new ChildTransactionPlan('parent-budget', 'child-one', 'Child One Budget', 'spend', 'Child One Spend Bank', 'transaction', 'txn-1', null, null, null, 'idem-1', 'Child One Checking', '2026-07-01', -1200, 'Shoes', 'Payee', true)
        ]

        when:
        syncer.applyPlans(-1L, plans)

        then:
        !store.hasAppliedIdempotencyKey('idem-1')
        childRepository.postCallCount == 0
    }

    def "planner maps one child budget categories to distinct mapped child accounts with deterministic disambiguation"() {
        given:
        def target = new ChildBudgetSyncTarget(
            childKey: 'child-one',
            budgetName: 'Child One Budget',
            tokenEnvVarName: 'CHILD_ONE_TOKEN',
            accountMappings: [
                new ChildAccountMapping('broad-regex', [new ParentCategoryNameMatcher('Child One .* Bank', true)], 'Broad Account'),
                new ChildAccountMapping('spend', [new ParentCategoryNameMatcher('Child One Spend Bank', false)], 'Spend Account'),
                new ChildAccountMapping('give', [new ParentCategoryNameMatcher('Child One Give Bank', false)], 'Give Account'),
                new ChildAccountMapping('cd', [new ParentCategoryNameMatcher('Child One Gold CD.*', true)], 'CD Account')
            ]
        )
        def planner = new ChildSyncPlanner([childContextForPlanner(target)])
        Map<String, CategorySnapshot> categoriesById = [
            'cat-spend': new CategorySnapshot('cat-spend', 'Child One Spend Bank', 0),
            'cat-give' : new CategorySnapshot('cat-give', 'Child One Give Bank', 0),
            'cat-cd'   : new CategorySnapshot('cat-cd', 'Child One Gold CD 2-Month 07/31/26', 0)
        ]
        def transactions = [
            new ParentTransactionEvent('txn-spend', '2026-07-01', -1200, 'Spend', true, 12, 'cat-spend', 'Child One Spend Bank', []),
            new ParentTransactionEvent('txn-give', '2026-07-01', -1300, 'Give', true, 12, 'cat-give', 'Child One Give Bank', []),
            new ParentTransactionEvent('txn-cd', '2026-07-01', -1400, 'CD', true, 12, 'cat-cd', 'Child One Gold CD 2-Month 07/31/26', [])
        ]

        when:
        def plans = planner.planTransactions('parent-budget-id', categoriesById, transactions)

        then:
        plans.collectEntries { [(it.parentCategoryName): [it.mappingKey, it.childAccountName]] } == [
            'Child One Spend Bank': ['spend', 'Spend Account'],
            'Child One Give Bank': ['give', 'Give Account'],
            'Child One Gold CD 2-Month 07/31/26': ['cd', 'CD Account']
        ]
        plans.find { it.parentCategoryName == 'Child One Spend Bank' }.idempotencyKey.contains('|spend|acct-spend|')
    }

    def "planner treats regex metacharacters literally unless regex is true and uses first match for ties"() {
        given:
        def target = new ChildBudgetSyncTarget(
            childKey: 'child-one',
            budgetName: 'Child One Budget',
            tokenEnvVarName: 'CHILD_ONE_TOKEN',
            accountMappings: [
                new ChildAccountMapping('literal-first', [new ParentCategoryNameMatcher('Child One CD (2-Month) [07/31/26]', false)], 'Literal Account'),
                new ChildAccountMapping('regex-first', [new ParentCategoryNameMatcher('Child One Bonus.*', true)], 'First Regex Account'),
                new ChildAccountMapping('regex-second', [new ParentCategoryNameMatcher('Child One Bonus.*', true)], 'Second Regex Account')
            ]
        )
        def planner = new ChildSyncPlanner([childContextForPlanner(target)])
        Map<String, CategorySnapshot> categoriesById = [
            'cat-literal': new CategorySnapshot('cat-literal', 'Child One CD (2-Month) [07/31/26]', 0),
            'cat-regex'  : new CategorySnapshot('cat-regex', 'Child One Bonus Bank', 0)
        ]
        def transactions = [
            new ParentTransactionEvent('txn-literal', '2026-07-01', -1200, 'Literal', true, 12, 'cat-literal', 'Child One CD (2-Month) [07/31/26]', []),
            new ParentTransactionEvent('txn-regex', '2026-07-01', -1300, 'Regex', true, 12, 'cat-regex', 'Child One Bonus Bank', [])
        ]

        when:
        def plans = planner.planTransactions('parent-budget-id', categoriesById, transactions)

        then:
        plans.collectEntries { [(it.parentCategoryName): it.mappingKey] } == [
            'Child One CD (2-Month) [07/31/26]': 'literal-first',
            'Child One Bonus Bank': 'regex-first'
        ]
    }

    def "fromConfig validates required token environment and honors state override"() {
        given:
        def config = new RuntimeConfig(sync: sampleSyncConfig(tempDir.resolve('config-state.db').toString()))
        def options = SyncCliOptions.parse(['--dry-run', '--sync-state-db-path', tempDir.resolve('override-state.db').toString()] as String[])

        when:
        def syncer = ParentChildBudgetSyncer.fromConfig(config, options, [PARENT_TOKEN: 'parent-token', CHILD_ONE_TOKEN: 'one-token', CHILD_TWO_TOKEN: 'two-token'])

        then:
        syncer.stateDbPath.endsWith('override-state.db')
        syncer.childContexts.size() == 2
    }

    def "fromConfig throws when required token environment value is missing or blank"() {
        given:
        def config = new RuntimeConfig(sync: sampleSyncConfig(tempDir.resolve('config-state.db').toString()))
        def options = SyncCliOptions.parse(['--dry-run'] as String[])

        when:
        ParentChildBudgetSyncer.fromConfig(config, options, environment)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        environment                                                | expected
        [CHILD_ONE_TOKEN: 'one-token', CHILD_TWO_TOKEN: 'two-token'] | "Environment variable 'PARENT_TOKEN'"
        [PARENT_TOKEN: ' ', CHILD_ONE_TOKEN: 'one-token', CHILD_TWO_TOKEN: 'two-token'] | "Environment variable 'PARENT_TOKEN'"
        [PARENT_TOKEN: 'parent-token', CHILD_TWO_TOKEN: 'two-token'] | "Environment variable 'CHILD_ONE_TOKEN'"
    }
    def "apply resolves child account ids per mapped account under one child budget"() {
        given:
        def dbPath = tempDir.resolve('multi-account.db').toString()
        def store = new SyncStateStore(dbPath)
        store.initialize()
        def target = new ChildBudgetSyncTarget(
            childKey: 'child-one',
            budgetName: 'Child One Budget',
            tokenEnvVarName: 'CHILD_ONE_TOKEN',
            accountMappings: [
                new ChildAccountMapping('spend', [new ParentCategoryNameMatcher('Child One Spend Bank', false)], 'Spend Account'),
                new ChildAccountMapping('give', [new ParentCategoryNameMatcher('Child One Give Bank', false)], 'Give Account')
            ]
        )
        def childRepository = new FakeYnabBudgetRepository('child-budget-id', ['Spend Account': 'acct-spend', 'Give Account': 'acct-give'])
        def childContext = new ChildSyncContext(target, childRepository)
        def applier = new ChildSyncApplier(store, new ChildTransactionPayloadFactory(), false)
        def plans = [
            new ChildTransactionPlan('parent-budget', 'child-one', 'Child One Budget', 'spend', 'Child One Spend Bank', 'transaction', 'txn-1', null, null, null, 'parent-budget|child-one|spend||transaction|txn-1||||cat-spend|Child One Spend Bank|-1200', 'Spend Account', '2026-07-01', -1200, 'Shoes', 'Payee', true),
            new ChildTransactionPlan('parent-budget', 'child-one', 'Child One Budget', 'give', 'Child One Give Bank', 'transaction', 'txn-2', null, null, null, 'parent-budget|child-one|give||transaction|txn-2||||cat-give|Child One Give Bank|-500', 'Give Account', '2026-07-01', -500, 'Gift', 'Payee', true)
        ]

        when:
        long runId = store.startRun(false, 300, 'parent-budget')
        def result = applier.applyPlans(runId, plans, [childContext])

        then:
        result.appliedCount == 2
        childRepository.requestedAccountNames == ['Spend Account', 'Give Account']
        childContext.accountIdsByName == ['Spend Account': 'acct-spend', 'Give Account': 'acct-give']
        store.hasAppliedIdempotencyKey(ChildSyncIdempotency.buildKey(plans[0], 'acct-spend'))
        store.hasAppliedIdempotencyKey(ChildSyncIdempotency.buildKey(plans[1], 'acct-give'))
    }


    private static ChildSyncContext childContextForPlanner(ChildBudgetSyncTarget target, Map<String, String> accountIdsByName = null) {
        Map<String, String> ids = accountIdsByName ?: target.accountMappings.collectEntries { [(it.childAccountName): "acct-${it.mappingKey}"] }
        def repo = new FakeYnabBudgetRepository('child-budget-id', ids)
        def context = new ChildSyncContext(target, repo)
        context.budgetId = 'child-budget-id'
        ids.each { name, id -> context.cacheAccountId(name, id) }
        context
    }

    private static ChildBudgetSyncTarget childTarget(String childKey, String budgetName, String tokenEnvVarName, List mappingRows) {
        new ChildBudgetSyncTarget(
            childKey: childKey,
            budgetName: budgetName,
            tokenEnvVarName: tokenEnvVarName,
            accountMappings: mappingRows.collect { row ->
                new ChildAccountMapping(row[0] as String, (row[1] as List<String>).collect { new ParentCategoryNameMatcher(it, false) }, row[2] as String)
            }
        )
    }

    private static SyncConfig sampleSyncConfig(String dbPath) {
        new SyncConfig(
            new BudgetRef('Parent', 'PARENT_TOKEN'),
            [
                childTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN', [['spend-save', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking']]),
                childTarget('child-two', 'Child Two Budget', 'CHILD_TWO_TOKEN', [['spend', ['Child Two Spend Bank'], 'Child Two Checking']])
            ],
            300,
            new SyncLoggingConfig('logs/sync.log', 'INFO', 7, 10),
            new SyncStateConfig(dbPath, 30, 30)
        )
    }
}

class FakeYnabBudgetRepository extends YnabBudgetRepository {
    String budgetId
    String accountId
    Map<String, String> accountIdsByName = [:]
    int postCallCount = 0
    List<String> requestedAccountNames = []

    FakeYnabBudgetRepository(String budgetId = 'budget-id', String accountId = 'account-id') {
        super(null)
        this.budgetId = budgetId
        this.accountId = accountId
    }

    FakeYnabBudgetRepository(String budgetId, Map<String, String> accountIdsByName) {
        super(null)
        this.budgetId = budgetId
        this.accountIdsByName = accountIdsByName
    }

    @Override
    String getLatestBudgetId(String budgetName) {
        budgetId
    }

    @Override
    String getAccountId(String budgetId, String accountName) {
        requestedAccountNames << accountName
        accountIdsByName ? accountIdsByName[accountName] : accountId
    }

    @Override
    def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
        postCallCount++
        [data: [bulk: [transaction_ids: ['created-1']]]]
    }
}

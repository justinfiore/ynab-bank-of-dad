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
            'parent-budget', 'child-one', 'Child Budget', 'Child One Spend Bank',
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
                new ChildSyncContext(new ChildBudgetSyncTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking'), new FakeYnabBudgetRepository(), null, null),
                new ChildSyncContext(new ChildBudgetSyncTarget('child-two', 'Child Two Budget', 'CHILD_TWO_TOKEN', ['Child Two Spend Bank'], 'Child Two Checking'), new FakeYnabBudgetRepository(), null, null)
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
            [new ChildSyncContext(new ChildBudgetSyncTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN', ['Child One Spend Bank'], 'Child One Checking'), childRepository, null, null)]
        )
        def plans = [
            new ChildTransactionPlan('parent-budget', 'child-one', 'Child One Budget', 'Child One Spend Bank', 'transaction', 'txn-1', null, null, null, 'idem-1', 'Child One Checking', '2026-07-01', -1200, 'Shoes', 'Payee', true)
        ]

        when:
        syncer.applyPlans(-1L, plans)

        then:
        !store.hasAppliedIdempotencyKey('idem-1')
        childRepository.postCallCount == 0
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

    private static SyncConfig sampleSyncConfig(String dbPath) {
        new SyncConfig(
            new BudgetRef('Parent', 'PARENT_TOKEN'),
            [
                new ChildBudgetSyncTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking'),
                new ChildBudgetSyncTarget('child-two', 'Child Two Budget', 'CHILD_TWO_TOKEN', ['Child Two Spend Bank'], 'Child Two Checking')
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
    int postCallCount = 0

    FakeYnabBudgetRepository(String budgetId = 'budget-id', String accountId = 'account-id') {
        super(null)
        this.budgetId = budgetId
        this.accountId = accountId
    }

    @Override
    String getLatestBudgetId(String budgetName) {
        budgetId
    }

    @Override
    String getAccountId(String budgetId, String accountName) {
        accountId
    }

    @Override
    def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
        postCallCount++
        [data: [bulk: [transaction_ids: ['created-1']]]]
    }
}

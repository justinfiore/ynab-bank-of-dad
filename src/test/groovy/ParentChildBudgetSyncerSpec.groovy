import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.config.*
import ynabbankofdad.sync.ParentChildBudgetSyncer
import ynabbankofdad.sync.SyncCliOptions
import ynabbankofdad.sync.SyncRunCoordinator
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.ParentSubtransactionEvent
import ynabbankofdad.sync.model.ParentTransactionEvent
import ynabbankofdad.sync.reconcile.DesiredMirrorFactory
import ynabbankofdad.sync.reconcile.ParentReconciliationResult
import ynabbankofdad.sync.reconcile.SourceRevisionNormalizer
import ynabbankofdad.sync.state.SourceEntityKey
import ynabbankofdad.sync.state.SourceEntityType
import ynabbankofdad.sync.state.SyncStateStore

import java.nio.file.Path

class ParentChildBudgetSyncerSpec extends Specification {
    @TempDir
    Path tempDir

    def "cli options parse sync-specific flags"() {
        when:
        def options = SyncCliOptions.parse(['--dry-run', '--config', 'custom.yaml',
            '--sync-state-db-path', 'state.db', '--max-cycles', '2'] as String[])

        then:
        options.dryRun
        options.configPath == 'custom.yaml'
        options.syncStateDbPath == 'state.db'
        options.maxCycles == 2
    }

    def "cli options provide safe defaults and support short help flags"() {
        expect:
        SyncCliOptions.parse([] as String[]).with {
            !dryRun && !help && configPath == 'config.yaml' &&
                syncStateDbPath == null && maxCycles == Integer.MAX_VALUE
        }
        SyncCliOptions.parse(['-h'] as String[]).help
        SyncCliOptions.parse(['-c', 'other.yaml'] as String[]).configPath == 'other.yaml'
    }

    def "cli options reject unknown missing blank and invalid values"() {
        when:
        SyncCliOptions.parse(arguments as String[])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        arguments                       | expected
        ['--unknown']                    | 'Unknown argument'
        ['--config']                     | 'Missing value for --config'
        ['--sync-state-db-path', ' ']    | 'Missing value for --sync-state-db-path'
        ['--max-cycles', 'abc']          | '--max-cycles must be a positive integer'
        ['--max-cycles', '0']            | '--max-cycles must be positive'
        ['--max-cycles', '2147483648']   | '--max-cycles must be a positive integer'
    }

    def "reconciliation routing treats metacharacters literally and prefers literal mappings"() {
        given:
        def target = new ChildBudgetSyncTarget(
            childKey: 'child-one', budgetName: 'Child One Budget', tokenEnvVarName: 'CHILD_ONE_TOKEN',
            accountMappings: [
                new ChildAccountMapping('regex',
                    [new ParentCategoryNameMatcher('Child One .*', true)], 'Regex Account'),
                new ChildAccountMapping('literal',
                    [new ParentCategoryNameMatcher('Child One CD (2-Month) [07/31/26]', false)], 'Literal Account')
            ])
        def context = new ChildSyncContext(target, null, 'child-budget')
        context.cacheAccountId('Regex Account', 'regex-account')
        context.cacheAccountId('Literal Account', 'literal-account')

        when:
        def mirrors = new DesiredMirrorFactory([context]).forSource(
            new SourceEntityKey('parent', SourceEntityType.TRANSACTION, 'txn', null, null),
            'category', 'Child One CD (2-Month) [07/31/26]', '2026-07-01', -1200,
            null, 'Payee', 'Memo', 'outflow')

        then:
        mirrors*.mappingKey == ['literal']
        mirrors*.targetAccountId == ['literal-account']
    }

    def "fromConfig validates required token environment and honors state override"() {
        given:
        def config = new RuntimeConfig(sync: sampleSyncConfig(tempDir.resolve('config-state.db').toString()))
        def options = SyncCliOptions.parse(['--dry-run', '--sync-state-db-path',
            tempDir.resolve('override-state.db').toString()] as String[])

        when:
        def syncer = ParentChildBudgetSyncer.fromConfig(config, options,
            [PARENT_TOKEN: 'parent-token', CHILD_ONE_TOKEN: 'one-token', CHILD_TWO_TOKEN: 'two-token'])

        then:
        syncer.stateDbPath.endsWith('override-state.db')
        syncer.childContexts.size() == 2
    }

    def "fromConfig dry run does not create a SQLite file or parent directory"() {
        given:
        def dbPath = tempDir.resolve('missing-parent').resolve('dry-run.db')
        def config = new RuntimeConfig(sync: sampleSyncConfig(dbPath.toString()))
        def options = SyncCliOptions.parse(['--dry-run'] as String[])

        when:
        def syncer = ParentChildBudgetSyncer.fromConfig(config, options,
            [PARENT_TOKEN: 'parent-token', CHILD_ONE_TOKEN: 'one-token', CHILD_TWO_TOKEN: 'two-token'])
        syncer.stateStore.initialize()

        then:
        !dbPath.parent.toFile().exists()
        !dbPath.toFile().exists()
        !new File(dbPath.toString() + '.lock').exists()
    }

    def "fromConfig dry run reads an existing cursor without mutating state"() {
        given:
        def dbPath = tempDir.resolve('existing-dry-run.db')
        def persistentStore = new SyncStateStore(dbPath.toString())
        persistentStore.initialize()
        persistentStore.setCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY, 77)
        def config = new RuntimeConfig(sync: sampleSyncConfig(dbPath.toString()))
        def options = SyncCliOptions.parse(['--dry-run'] as String[])

        when:
        def syncer = ParentChildBudgetSyncer.fromConfig(config, options,
            [PARENT_TOKEN: 'parent-token', CHILD_ONE_TOKEN: 'one-token', CHILD_TWO_TOKEN: 'two-token'])
        Integer cursor = syncer.stateStore.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)
        syncer.stateStore.setCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY, 88)

        then:
        cursor == 77
        persistentStore.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY) == 77
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

    def "routing-blocked results do not plan deleted lifecycle from an empty desired set"() {
        given:
        def revision = new SourceRevisionNormalizer().normalize('parent', new ParentTransactionEvent(
            'txn-1', '2026-07-01', -1000, 'memo', true, 1, 'cat', 'Child One Spend Bank',
            [], null, 'Payee', false), 1, true)
        long sourceId = 42L
        Map sourceIds = [(revision.parentSource): sourceId]
        def blocked = new ParentReconciliationResult(revision, [], [], false, true, [] as Set)
        def unmapped = new ParentReconciliationResult(revision, [], [], false, false, [] as Set)

        expect:
        ParentChildBudgetSyncer.plannedSourceLifecycles(blocked, sourceIds).isEmpty()
        ParentChildBudgetSyncer.plannedSourceLifecycles(unmapped, sourceIds) == [(sourceId): 'deleted']
    }

    def "unrelated sources still receive lifecycle updates when another source is routing-blocked"() {
        given:
        def normalizer = new SourceRevisionNormalizer()
        def blockedRevision = normalizer.normalize('parent', new ParentTransactionEvent(
            'blocked', '2026-07-01', -1000, 'memo', true, 1, 'cat', 'Child Two Spend Bank',
            [], null, 'Payee', false), 1, true)
        def openRevision = normalizer.normalize('parent', new ParentTransactionEvent(
            'open', '2026-07-01', -2000, 'memo', true, 1, 'cat', 'Child One Spend Bank',
            [], null, 'Payee', false), 1, true)
        def child = new ChildSyncContext(childTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN',
            [['spend', ['Child One Spend Bank'], 'Checking']]), null, 'budget-one')
        child.cacheAccountId('Checking', 'account-one')
        def desired = new DesiredMirrorFactory([child]).forSource(
            openRevision.parentSource, 'cat', 'Child One Spend Bank', '2026-07-01',
            -2000, null, 'Payee', 'memo', 'outflow')
        def blocked = new ParentReconciliationResult(blockedRevision, [], [], false, true, [] as Set)
        def open = new ParentReconciliationResult(openRevision, desired, [], false, false, [] as Set)

        expect:
        ParentChildBudgetSyncer.plannedSourceLifecycles(blocked, [(blockedRevision.parentSource): 1L]).isEmpty()
        ParentChildBudgetSyncer.plannedSourceLifecycles(open, [(openRevision.parentSource): 2L]) ==
            [2L: 'active']
    }

    def "routing-blocked split component does not suppress healthy sibling lifecycle"() {
        given:
        def revision = new SourceRevisionNormalizer().normalize('parent', new ParentTransactionEvent(
            'split', '2026-07-01', -3000, 'memo', true, 1, null, null, [
                new ParentSubtransactionEvent('blocked', 'split', -1000, 'blocked', 'blocked-cat',
                    'Child One Save Bank', false, null, null),
                new ParentSubtransactionEvent('healthy', 'split', -2000, 'healthy', 'healthy-cat',
                    'Child Two Spend Bank', false, null, null)
            ], null, null, false), 1, true)
        def blockedSource = revision.components.find { it.source.parentSubtransactionId == 'blocked' }.source
        def healthyComponent = revision.components.find { it.source.parentSubtransactionId == 'healthy' }
        def child = new ChildSyncContext(childTarget('child-two', 'Child Two Budget', 'CHILD_TWO_TOKEN',
            [['spend', ['Child Two Spend Bank'], 'Checking']]), null, 'budget-two')
        child.cacheAccountId('Checking', 'account-two')
        def desired = new DesiredMirrorFactory([child]).forSource(
            healthyComponent.source, healthyComponent.categoryId, healthyComponent.categoryName,
            revision.date, healthyComponent.amount, null, null, healthyComponent.memo, 'outflow')
        Map sourceIds = [
            (revision.parentSource): 1L,
            (blockedSource): 2L,
            (healthyComponent.source): 3L
        ]
        def result = new ParentReconciliationResult(
            revision, desired, [], false, false, [blockedSource] as Set)

        expect:
        ParentChildBudgetSyncer.plannedSourceLifecycles(result, sourceIds) ==
            [1L: 'active', 3L: 'active']
    }

    private static ChildBudgetSyncTarget childTarget(String childKey, String budgetName,
                                                       String tokenEnvVarName, List mappingRows) {
        new ChildBudgetSyncTarget(
            childKey: childKey, budgetName: budgetName, tokenEnvVarName: tokenEnvVarName,
            accountMappings: mappingRows.collect { row ->
                new ChildAccountMapping(row[0] as String,
                    (row[1] as List<String>).collect { new ParentCategoryNameMatcher(it, false) },
                    row[2] as String)
            })
    }

    private static SyncConfig sampleSyncConfig(String dbPath) {
        new SyncConfig(
            new BudgetRef('Parent', 'PARENT_TOKEN'),
            [
                childTarget('child-one', 'Child One Budget', 'CHILD_ONE_TOKEN',
                    [['spend-save', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking']]),
                childTarget('child-two', 'Child Two Budget', 'CHILD_TWO_TOKEN',
                    [['spend', ['Child Two Spend Bank'], 'Child Two Checking']])
            ],
            300,
            new SyncLoggingConfig('logs/sync.log', 'INFO', 7, 10),
            new SyncStateConfig(dbPath, 30, 30))
    }
}

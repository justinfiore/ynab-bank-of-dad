package ynabbankofdad.sync

import groovy.util.logging.Slf4j
import ynabbankofdad.config.*
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.*

import java.time.format.DateTimeFormatter

@Slf4j
class ParentChildBudgetSyncer {
    static final String DEFAULT_CONFIG_PATH = 'config.yaml'
    static final int DEFAULT_MAX_CYCLES = Integer.MAX_VALUE
    static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

    static void main(String[] args) {
        SyncCliOptions options = SyncCliOptions.parse(args)
        if (options.help) {
            SyncCliOptions.printUsage()
            return
        }

        RuntimeConfig config = RuntimeConfig.load(options.configPath)
        if (config.sync == null) {
            throw new IllegalArgumentException("Config file '${options.configPath}' does not define the required 'sync' section")
        }

        SyncLoggingBootstrap.configure(config.sync.logging)
        log.info("Starting parent-child budget syncer with config {}", options.configPath)

        ParentChildBudgetSyncer syncer = fromConfig(config, options)
        syncer.runLoop()
    }

    final RuntimeConfig runtimeConfig
    final SyncConfig syncConfig
    final boolean dryRun
    final String stateDbPath
    final int maxCycles
    final YnabBudgetRepository parentRepository
    final SyncStateRepository stateStore
    final List<ChildSyncContext> childContexts
    final ChildSyncPlanner planner
    final ChildSyncApplier applier
    final SyncRunCoordinator coordinator

    ParentChildBudgetSyncer(
        RuntimeConfig runtimeConfig,
        SyncConfig syncConfig,
        boolean dryRun,
        String stateDbPath,
        int maxCycles,
        YnabBudgetRepository parentRepository,
        SyncStateRepository stateStore,
        List<ChildSyncContext> childContexts
    ) {
        this.runtimeConfig = runtimeConfig
        this.syncConfig = syncConfig
        this.dryRun = dryRun
        this.stateDbPath = stateDbPath
        this.maxCycles = maxCycles
        this.parentRepository = parentRepository
        this.stateStore = stateStore
        this.childContexts = childContexts
        this.planner = new ChildSyncPlanner(childContexts)
        this.applier = new ChildSyncApplier(stateStore, new ChildTransactionPayloadFactory(), dryRun)
        this.coordinator = new SyncRunCoordinator(stateStore, parentRepository, dryRun)
    }

    static ParentChildBudgetSyncer fromConfig(RuntimeConfig runtimeConfig, SyncCliOptions options) {
        fromConfig(runtimeConfig, options, System.getenv())
    }

    static ParentChildBudgetSyncer fromConfig(RuntimeConfig runtimeConfig, SyncCliOptions options, Map<String, String> environment) {
        String parentToken = resolveRequiredToken(runtimeConfig.sync.parentBudget.tokenEnvVarName, 'sync.parentBudget.tokenEnvVarName', environment)
        YnabBudgetRepository parentRepository = new YnabBudgetRepository(new YnabHttpClient('https://api.youneedabudget.com', parentToken))

        String stateDbPath = options.syncStateDbPath ?: runtimeConfig.sync.state.sqlitePath
        SyncStateStore stateStore = new SyncStateStore(stateDbPath)
        stateStore.initialize()

        List<ChildSyncContext> childContexts = runtimeConfig.sync.childBudgets.collect { ChildBudgetSyncTarget target ->
            String token = resolveRequiredToken(target.tokenEnvVarName, "sync.childBudgets[${target.childKey}].tokenEnvVarName", environment)
            YnabBudgetRepository childRepository = new YnabBudgetRepository(new YnabHttpClient('https://api.youneedabudget.com', token))
            new ChildSyncContext(target, childRepository)
        }

        new ParentChildBudgetSyncer(
            runtimeConfig,
            runtimeConfig.sync,
            options.dryRun,
            stateDbPath,
            options.maxCycles,
            parentRepository,
            stateStore,
            childContexts
        )
    }

    void runLoop() {
        int cycle = 0
        while (cycle < maxCycles) {
            cycle++
            runOnce(cycle)
            if (cycle >= maxCycles) {
                break
            }
            log.info('Sleeping {} seconds before next sync cycle', syncConfig.pollingIntervalSeconds)
            sleep(syncConfig.pollingIntervalSeconds * 1000L)
        }
    }

    void runOnce(int cycleNumber = 1) {
        stateStore.initialize()

        String parentBudgetId = parentRepository.getLatestBudgetId(syncConfig.parentBudget.budgetName)
        Map<String, CategorySnapshot> parentCategoriesByName = parentRepository.getCategoryInfoByCategoryName(parentBudgetId)
        Map<String, CategorySnapshot> parentCategoriesById = parentCategoriesByName.values().collectEntries { [(it.id): it] }
        List<ParentTransactionEvent> transactionEvents = parentRepository.getTransactions(
            parentBudgetId,
            syncConfig.state.transactionLookbackDays,
            transactionCursor()
        )
        List<MoneyMovementEvent> moneyMovementEvents = parentRepository.getMoneyMovements(
            parentBudgetId,
            syncConfig.state.moneyMovementLookbackDays
        )

        long runId = dryRun ? -1L : stateStore.startRun(dryRun, syncConfig.pollingIntervalSeconds, parentBudgetId)
        try {
            log.info('Cycle {} read {} parent transactions and {} money movements', cycleNumber, transactionEvents.size(), moneyMovementEvents.size())
            List<ChildTransactionPlan> plans = buildPlans(parentBudgetId, parentCategoriesById, transactionEvents, moneyMovementEvents)
            SyncRunResult result = applyPlans(runId, plans)
            coordinator.finishRun(runId, result, transactionEvents)
        } catch (Exception ex) {
            coordinator.failRun(runId, ex)
            throw ex
        }
    }

    private Integer transactionCursor() {
        stateStore.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)
    }

    List<ChildTransactionPlan> buildPlans(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<ParentTransactionEvent> transactions,
        List<MoneyMovementEvent> moneyMovements
    ) {
        planner.buildPlans(parentBudgetId, parentCategoriesById, transactions, moneyMovements)
    }

    SyncRunResult applyPlans(long runId, List<ChildTransactionPlan> plans) {
        applier.applyPlans(runId, plans, childContexts)
    }

    private static String resolveRequiredToken(String envVarName, String configKey, Map<String, String> environment) {
        if (!envVarName?.trim()) {
            throw new IllegalArgumentException("${configKey} must not be blank")
        }
        String token = environment[envVarName]
        if (!token?.trim()) {
            throw new IllegalArgumentException("Environment variable '${envVarName}' referenced by ${configKey} must be set")
        }
        token
    }
}

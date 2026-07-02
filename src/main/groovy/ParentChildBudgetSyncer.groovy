import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Slf4j
class ParentChildBudgetSyncer {
    static final String DEFAULT_CONFIG_PATH = 'config.yaml'
    static final int DEFAULT_MAX_CYCLES = Integer.MAX_VALUE
    static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
    static final String DRY_RUN_PREFIX = '[DRY RUN]'

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
    final SyncStateStore stateStore
    final List<ChildSyncContext> childContexts

    ParentChildBudgetSyncer(
        RuntimeConfig runtimeConfig,
        SyncConfig syncConfig,
        boolean dryRun,
        String stateDbPath,
        int maxCycles,
        YnabBudgetRepository parentRepository,
        SyncStateStore stateStore,
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
    }

    static ParentChildBudgetSyncer fromConfig(RuntimeConfig runtimeConfig, SyncCliOptions options) {
        String parentToken = resolveRequiredToken(runtimeConfig.sync.parentBudget.tokenEnvVarName, 'sync.parentBudget.tokenEnvVarName')
        YnabBudgetRepository parentRepository = new YnabBudgetRepository(new YnabHttpClient('https://api.youneedabudget.com', parentToken))

        String stateDbPath = options.syncStateDbPath ?: runtimeConfig.sync.state.sqlitePath
        SyncStateStore stateStore = new SyncStateStore(stateDbPath)
        stateStore.initialize()

        List<ChildSyncContext> childContexts = runtimeConfig.sync.childBudgets.collect { ChildBudgetSyncTarget target ->
            String token = resolveRequiredToken(target.tokenEnvVarName, "sync.childBudgets[${target.childKey}].tokenEnvVarName")
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
            applyPlans(runId, plans)
            if (!dryRun) {
                stateStore.finishRun(runId, 'succeeded', null)
                Integer latestServerKnowledge = parentRepository.latestServerKnowledge(transactionEvents)
                if (latestServerKnowledge != null) {
                    stateStore.setCursor('transactions.last_server_knowledge', latestServerKnowledge)
                }
            }
        } catch (Exception ex) {
            if (!dryRun && runId > 0) {
                stateStore.finishRun(runId, 'failed', ex.message)
            }
            throw ex
        }
    }

    private Integer transactionCursor() {
        stateStore.getCursor('transactions.last_server_knowledge')
    }

    List<ChildTransactionPlan> buildPlans(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<ParentTransactionEvent> transactions,
        List<MoneyMovementEvent> moneyMovements
    ) {
        List<ChildTransactionPlan> plans = []
        plans.addAll(planTransactions(parentBudgetId, parentCategoriesById, transactions))
        plans.addAll(planMoneyMovements(parentBudgetId, parentCategoriesById, moneyMovements))
        plans
    }

    private List<ChildTransactionPlan> planTransactions(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<ParentTransactionEvent> transactions
    ) {
        List<ChildTransactionPlan> plans = []
        transactions.each { ParentTransactionEvent event ->
            if (!event.approved) {
                return
            }

            if (event.subtransactions) {
                event.subtransactions.each { ParentSubtransactionEvent subtransaction ->
                    plans.addAll(planFromCategory(
                        parentBudgetId,
                        parentCategoriesById,
                        subtransaction.categoryId,
                        subtransaction.categoryName,
                        event.date,
                        subtransaction.memo ?: event.memo,
                        subtransaction.amount,
                        'subtransaction',
                        event.id,
                        subtransaction.id,
                        null,
                        null,
                        null,
                        event.id
                    ))
                }
                return
            }

            plans.addAll(planFromCategory(
                parentBudgetId,
                parentCategoriesById,
                event.categoryId,
                event.categoryName,
                event.date,
                event.memo,
                event.amount,
                'transaction',
                event.id,
                null,
                null,
                null,
                null,
                event.id
            ))
        }
        plans
    }

    private List<ChildTransactionPlan> planMoneyMovements(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        List<MoneyMovementEvent> moneyMovements
    ) {
        List<ChildTransactionPlan> plans = []
        moneyMovements.each { MoneyMovementEvent movement ->
            String sourceName = parentCategoriesById[movement.fromCategoryId]?.name
            String destinationName = parentCategoriesById[movement.toCategoryId]?.name
            String memo = "From ${sourceName ?: 'Unknown'} to ${destinationName ?: 'Unknown'}"

            plans.addAll(planFromCategory(
                parentBudgetId,
                parentCategoriesById,
                movement.toCategoryId,
                destinationName,
                movement.eventDate,
                memo,
                movement.amount,
                'money_movement',
                null,
                null,
                movement.id,
                movement.groupId,
                'inflow',
                movement.id,
                "From ${sourceName ?: 'Unknown'}"
            ))
            plans.addAll(planFromCategory(
                parentBudgetId,
                parentCategoriesById,
                movement.fromCategoryId,
                sourceName,
                movement.eventDate,
                memo,
                -movement.amount,
                'money_movement',
                null,
                null,
                movement.id,
                movement.groupId,
                'outflow',
                movement.id,
                "To ${destinationName ?: 'Unknown'}"
            ))
        }
        plans
    }

    private List<ChildTransactionPlan> planFromCategory(
        String parentBudgetId,
        Map<String, CategorySnapshot> parentCategoriesById,
        String categoryId,
        String categoryName,
        String date,
        String memo,
        Integer amount,
        String eventType,
        String transactionId,
        String subtransactionId,
        String moneyMovementId,
        String moneyMovementGroupId,
        String movementDirection,
        String eventAnchor,
        String explicitPayeeName = null
    ) {
        if (!categoryId) {
            return []
        }
        String resolvedCategoryName = categoryName ?: parentCategoriesById[categoryId]?.name
        if (!resolvedCategoryName) {
            return []
        }

        childContexts.findResults { ChildSyncContext child ->
            if (!child.target.parentCategoryNames.contains(resolvedCategoryName)) {
                return null
            }
            String idempotencyKey = [
                parentBudgetId,
                child.target.childKey,
                eventType,
                transactionId ?: '',
                subtransactionId ?: '',
                moneyMovementId ?: '',
                movementDirection ?: '',
                categoryId,
                amount
            ].join('|')
            new ChildTransactionPlan(
                sourceBudgetId: parentBudgetId,
                targetChildKey: child.target.childKey,
                targetBudgetName: child.target.budgetName,
                parentCategoryName: resolvedCategoryName,
                eventType: eventType,
                parentTransactionId: transactionId,
                parentSubtransactionId: subtransactionId,
                moneyMovementId: moneyMovementId,
                moneyMovementGroupId: moneyMovementGroupId,
                idempotencyKey: idempotencyKey,
                childAccountName: child.target.childAccountName,
                date: date,
                amount: amount,
                memo: memo,
                payeeName: explicitPayeeName,
                approved: true
            )
        }
    }

    private void applyPlans(long runId, List<ChildTransactionPlan> plans) {
        if (plans.isEmpty()) {
            log.info('No qualifying child sync work found')
            return
        }

        Map<String, List<ChildTransactionPlan>> plansByChild = plans.groupBy { it.targetChildKey }
        plansByChild.each { String childKey, List<ChildTransactionPlan> childPlans ->
            ChildSyncContext childContext = childContexts.find { it.target.childKey == childKey }
            try {
                applyChildPlans(runId, childContext, childPlans)
            } catch (Exception ex) {
                log.error('Child sync target {} failed: {}', childKey, ex.message, ex)
                if (!dryRun && runId > 0) {
                    childPlans.each { ChildTransactionPlan plan ->
                        long sourceEventId = stateStore.recordSourceEvent(plan)
                        long mappingId = stateStore.recordMapping(sourceEventId, plan, null)
                        stateStore.recordAppliedTransaction(mappingId, runId, plan.targetBudgetName, null, 'failed', ex.message, false)
                    }
                }
            }
        }
    }

    private void applyChildPlans(long runId, ChildSyncContext childContext, List<ChildTransactionPlan> childPlans) {
        String budgetId = childContext.budgetId ?: childContext.repository.getLatestBudgetId(childContext.target.budgetName)
        childContext.budgetId = budgetId
        String accountId = childContext.accountId ?: childContext.repository.getAccountId(budgetId, childContext.target.childAccountName)
        childContext.accountId = accountId

        childPlans.each { ChildTransactionPlan plan ->
            if (stateStore.hasAppliedIdempotencyKey(plan.idempotencyKey)) {
                log.info('Skipping duplicate child sync plan {}', plan.idempotencyKey)
                return
            }

            Map<String, Object> transaction = [
                account_id : accountId,
                date       : plan.date,
                amount     : plan.amount,
                payee_name : plan.payeeName,
                category_id: null,
                memo       : plan.memo,
                approved   : plan.approved,
                import_id  : buildImportId(plan)
            ]

            if (dryRun) {
                log.info('{} child transaction for {} -> {}', DRY_RUN_PREFIX, childContext.target.childKey, JsonOutput.toJson(transaction))
                log.info('{} sqlite state for {} -> {}', DRY_RUN_PREFIX, childContext.target.childKey, plan.idempotencyKey)
                return
            }

            long sourceEventId = stateStore.recordSourceEvent(plan)
            long mappingId = stateStore.recordMapping(sourceEventId, plan, accountId)
            def response = childContext.repository.postTransactions(budgetId, [transaction])
            String createdTransactionId = extractCreatedTransactionId(response)
            stateStore.recordAppliedTransaction(mappingId, runId, budgetId, createdTransactionId, 'applied', null, false)
            log.info('Posted child transaction for {}: {}', childContext.target.childKey, createdTransactionId)
        }
    }

    private static String buildImportId(ChildTransactionPlan plan) {
        String compact = plan.idempotencyKey.replaceAll(/[^A-Za-z0-9]/, '').takeRight(28)
        String datePart = (plan.date ?: '1970-01-01').replace('-', '')
        long amountAbs = Math.abs((plan.amount ?: 0) as long)
        "PCBS:${datePart}:${amountAbs}:${compact}"
    }

    private static String extractCreatedTransactionId(def response) {
        def ids = response?.data?.bulk?.transaction_ids
        if (ids instanceof List && !ids.isEmpty()) {
            return ids.first() as String
        }
        def transactions = response?.data?.transactions
        if (transactions instanceof List && !transactions.isEmpty()) {
            return transactions.first().id as String
        }
        null
    }

    private static String resolveRequiredToken(String envVarName, String configKey) {
        if (!envVarName?.trim()) {
            throw new IllegalArgumentException("${configKey} must not be blank")
        }
        String token = System.getenv(envVarName)
        if (!token?.trim()) {
            throw new IllegalArgumentException("Environment variable '${envVarName}' referenced by ${configKey} must be set")
        }
        token
    }
}


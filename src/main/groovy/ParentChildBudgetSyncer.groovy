import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Immutable
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
                parentRepository.updateTransactionCursor(parentBudgetId, transactionEvents)
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

class SyncCliOptions {
    boolean help = false
    boolean dryRun = false
    String configPath = ParentChildBudgetSyncer.DEFAULT_CONFIG_PATH
    String syncStateDbPath
    int maxCycles = ParentChildBudgetSyncer.DEFAULT_MAX_CYCLES

    static SyncCliOptions parse(String[] args) {
        SyncCliOptions options = new SyncCliOptions()
        int i = 0
        while (i < args.length) {
            String arg = args[i]
            switch (arg) {
                case '--help':
                case '-h':
                    options.help = true
                    i++
                    break
                case '--dry-run':
                    options.dryRun = true
                    i++
                    break
                case '--config':
                case '-c':
                    options.configPath = requireValue(args, i, arg)
                    i += 2
                    break
                case '--sync-state-db-path':
                    options.syncStateDbPath = requireValue(args, i, arg)
                    i += 2
                    break
                case '--max-cycles':
                    options.maxCycles = Integer.parseInt(requireValue(args, i, arg))
                    if (options.maxCycles <= 0) {
                        throw new IllegalArgumentException('--max-cycles must be positive')
                    }
                    i += 2
                    break
                default:
                    throw new IllegalArgumentException("Unknown argument: ${arg}")
            }
        }
        options
    }

    static void printUsage() {
        println '''Usage: ParentChildBudgetSyncer [options]
  -c, --config PATH             Path to config YAML file (default: config.yaml)
      --sync-state-db-path PATH Override SQLite sync state path
      --dry-run                 Read and plan only; do not post or persist state
      --max-cycles N            Run N polling cycles, then exit (default: continuous)
  -h, --help                    Show this help'''
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for ${flag}")
        }
        args[index + 1]
    }
}

class SyncLoggingBootstrap {
    static void configure(SyncLoggingConfig config) {
        Path path = Paths.get(config.filePath)
        if (path.parent != null) {
            Files.createDirectories(path.parent)
        }
        if (!Files.exists(path)) {
            Files.createFile(path)
        }
        append(path, "[bootstrap] level=${config.level.toUpperCase()} maxHistory=${config.maxHistory} maxFileSizeMb=${config.maxFileSizeMb}\n")
    }

    private static void append(Path path, String text) {
        Files.writeString(path, text, StandardOpenOption.APPEND)
    }
}

class SyncStateStore {
    final String databasePath

    SyncStateStore(String databasePath) {
        this.databasePath = databasePath
    }

    void initialize() {
        Path dbPath = Paths.get(databasePath)
        if (dbPath.parent != null) {
            Files.createDirectories(dbPath.parent)
        }
        withConnection { Connection connection ->
            connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at TEXT NOT NULL,
                    completed_at TEXT NULL,
                    dry_run INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    error_summary TEXT NULL,
                    polling_interval_seconds INTEGER NULL,
                    source_budget_id TEXT NOT NULL
                )
            ''')
            connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS source_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_budget_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    parent_transaction_id TEXT NULL,
                    parent_subtransaction_id TEXT NULL,
                    money_movement_id TEXT NULL,
                    money_movement_group_id TEXT NULL,
                    event_date TEXT NULL,
                    ynab_server_knowledge INTEGER NULL,
                    fingerprint TEXT NOT NULL UNIQUE,
                    raw_summary_json TEXT NULL,
                    created_at TEXT NOT NULL
                )
            ''')
            connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS sync_mappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_event_id INTEGER NOT NULL,
                    target_budget_id TEXT NOT NULL,
                    target_child_key TEXT NOT NULL,
                    target_account_id TEXT NULL,
                    direction TEXT NOT NULL,
                    planned_amount INTEGER NOT NULL,
                    planned_date TEXT NOT NULL,
                    planned_payee_name TEXT NULL,
                    planned_memo TEXT NULL,
                    planned_category_id TEXT NULL,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    last_planned_at TEXT NOT NULL,
                    FOREIGN KEY(source_event_id) REFERENCES source_events(id)
                )
            ''')
            connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS applied_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_mapping_id INTEGER NOT NULL,
                    sync_run_id INTEGER NOT NULL,
                    target_budget_id TEXT NOT NULL,
                    created_child_transaction_id TEXT NULL,
                    applied_at TEXT NULL,
                    status TEXT NOT NULL,
                    failure_reason TEXT NULL,
                    dry_run INTEGER NOT NULL,
                    FOREIGN KEY(sync_mapping_id) REFERENCES sync_mappings(id),
                    FOREIGN KEY(sync_run_id) REFERENCES sync_runs(id)
                )
            ''')
            connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS sync_cursors (
                    key TEXT PRIMARY KEY,
                    value_text TEXT NULL,
                    value_integer INTEGER NULL,
                    updated_at TEXT NOT NULL
                )
            ''')
        }
    }

    long startRun(boolean dryRun, int pollingIntervalSeconds, String sourceBudgetId) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_runs(started_at, dry_run, status, polling_interval_seconds, source_budget_id)
                VALUES (?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setString(1, now())
            statement.setInt(2, dryRun ? 1 : 0)
            statement.setString(3, 'running')
            statement.setInt(4, pollingIntervalSeconds)
            statement.setString(5, sourceBudgetId)
            statement.executeUpdate()
            def keys = statement.generatedKeys
            keys.next()
            keys.getLong(1)
        }
    }

    void finishRun(long runId, String status, String errorSummary) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE sync_runs
                SET completed_at = ?, status = ?, error_summary = ?
                WHERE id = ?
            ''')
            statement.setString(1, now())
            statement.setString(2, status)
            statement.setString(3, errorSummary)
            statement.setLong(4, runId)
            statement.executeUpdate()
        }
    }

    long recordSourceEvent(ChildTransactionPlan plan) {
        String fingerprint = plan.idempotencyKey
        Long existingId = findSourceEventIdByFingerprint(fingerprint)
        if (existingId != null) {
            return existingId
        }
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO source_events(source_budget_id, event_type, parent_transaction_id, parent_subtransaction_id, money_movement_id, money_movement_group_id, event_date, ynab_server_knowledge, fingerprint, raw_summary_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setString(1, plan.sourceBudgetId)
            statement.setString(2, plan.eventType)
            statement.setString(3, plan.parentTransactionId)
            statement.setString(4, plan.parentSubtransactionId)
            statement.setString(5, plan.moneyMovementId)
            statement.setString(6, plan.moneyMovementGroupId)
            statement.setString(7, plan.date)
            statement.setObject(8, null)
            statement.setString(9, fingerprint)
            statement.setString(10, JsonOutput.toJson(plan.toSummaryMap()))
            statement.setString(11, now())
            statement.executeUpdate()
            def keys = statement.generatedKeys
            keys.next()
            keys.getLong(1)
        }
    }

    long recordMapping(long sourceEventId, ChildTransactionPlan plan, String accountId) {
        Long existingId = findMappingIdByIdempotencyKey(plan.idempotencyKey)
        if (existingId != null) {
            return existingId
        }
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_mappings(source_event_id, target_budget_id, target_child_key, target_account_id, direction, planned_amount, planned_date, planned_payee_name, planned_memo, planned_category_id, idempotency_key, last_planned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setLong(1, sourceEventId)
            statement.setString(2, plan.targetBudgetName)
            statement.setString(3, plan.targetChildKey)
            statement.setString(4, accountId)
            statement.setString(5, plan.amount >= 0 ? 'inflow' : 'outflow')
            statement.setInt(6, plan.amount)
            statement.setString(7, plan.date)
            statement.setString(8, plan.payeeName)
            statement.setString(9, plan.memo)
            statement.setObject(10, null)
            statement.setString(11, plan.idempotencyKey)
            statement.setString(12, now())
            statement.executeUpdate()
            def keys = statement.generatedKeys
            keys.next()
            keys.getLong(1)
        }
    }

    void recordAppliedTransaction(long mappingId, long runId, String targetBudgetId, String createdChildTransactionId, String status, String failureReason, boolean dryRun) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO applied_transactions(sync_mapping_id, sync_run_id, target_budget_id, created_child_transaction_id, applied_at, status, failure_reason, dry_run)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ''')
            statement.setLong(1, mappingId)
            statement.setLong(2, runId)
            statement.setString(3, targetBudgetId)
            statement.setString(4, createdChildTransactionId)
            statement.setString(5, now())
            statement.setString(6, status)
            statement.setString(7, failureReason)
            statement.setInt(8, dryRun ? 1 : 0)
            statement.executeUpdate()
        }
    }

    boolean hasAppliedIdempotencyKey(String idempotencyKey) {
        findMappingIdByIdempotencyKey(idempotencyKey) != null
    }

    Integer getCursor(String key) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT value_integer FROM sync_cursors WHERE key = ?')
            statement.setString(1, key)
            def rs = statement.executeQuery()
            if (rs.next()) {
                return rs.getInt(1)
            }
            null
        }
    }

    void setCursor(String key, Integer value) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_cursors(key, value_integer, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value_integer = excluded.value_integer, updated_at = excluded.updated_at
            ''')
            statement.setString(1, key)
            statement.setInt(2, value)
            statement.setString(3, now())
            statement.executeUpdate()
        }
    }

    private Long findSourceEventIdByFingerprint(String fingerprint) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT id FROM source_events WHERE fingerprint = ?')
            statement.setString(1, fingerprint)
            def rs = statement.executeQuery()
            rs.next() ? rs.getLong(1) : null
        }
    }

    private Long findMappingIdByIdempotencyKey(String idempotencyKey) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT id FROM sync_mappings WHERE idempotency_key = ?')
            statement.setString(1, idempotencyKey)
            def rs = statement.executeQuery()
            rs.next() ? rs.getLong(1) : null
        }
    }

    private <T> T withConnection(Closure<T> closure) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${databasePath}")
        try {
            closure.call(connection)
        } finally {
            connection.close()
        }
    }

    private static String now() {
        OffsetDateTime.now(ZoneOffset.UTC).toString()
    }
}

class ChildSyncContext {
    final ChildBudgetSyncTarget target
    final YnabBudgetRepository repository
    String budgetId
    String accountId

    ChildSyncContext(ChildBudgetSyncTarget target, YnabBudgetRepository repository, String budgetId = null, String accountId = null) {
        this.target = target
        this.repository = repository
        this.budgetId = budgetId
        this.accountId = accountId
    }
}

@Immutable
class ChildTransactionPlan {
    String sourceBudgetId
    String targetChildKey
    String targetBudgetName
    String parentCategoryName
    String eventType
    String parentTransactionId
    String parentSubtransactionId
    String moneyMovementId
    String moneyMovementGroupId
    String idempotencyKey
    String childAccountName
    String date
    Integer amount
    String memo
    String payeeName
    Boolean approved

    Map<String, Object> toSummaryMap() {
        [
            sourceBudgetId      : sourceBudgetId,
            targetChildKey      : targetChildKey,
            targetBudgetName    : targetBudgetName,
            parentCategoryName  : parentCategoryName,
            eventType           : eventType,
            parentTransactionId : parentTransactionId,
            parentSubtransactionId: parentSubtransactionId,
            moneyMovementId     : moneyMovementId,
            moneyMovementGroupId: moneyMovementGroupId,
            childAccountName    : childAccountName,
            date                : date,
            amount              : amount,
            memo                : memo,
            payeeName           : payeeName,
            approved            : approved
        ]
    }
}

@Immutable
class ParentTransactionEvent {
    String id
    String date
    Integer amount
    String memo
    Boolean approved
    Integer serverKnowledge
    String categoryId
    String categoryName
    List<ParentSubtransactionEvent> subtransactions = []
}

@Immutable
class ParentSubtransactionEvent {
    String id
    String transactionId
    Integer amount
    String memo
    String categoryId
    String categoryName
}

@Immutable
class MoneyMovementEvent {
    String id
    String groupId
    String eventDate
    String fromCategoryId
    String toCategoryId
    Integer amount
}

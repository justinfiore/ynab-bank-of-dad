package ynabbankofdad.sync

import groovy.util.logging.Slf4j
import ynabbankofdad.config.*
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.*

import java.time.LocalDate
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
    final ReconciliationSyncStateRepository reconciliationState
    final ReconciliationOperationApplier reconciliationApplier

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
        this.coordinator = new SyncRunCoordinator(stateStore, dryRun)
        if (!(stateStore instanceof ReconciliationSyncStateRepository)) {
            throw new IllegalArgumentException('Parent-child reconciliation requires reconciliation-capable sync state')
        }
        this.reconciliationState = stateStore as ReconciliationSyncStateRepository
        this.reconciliationApplier = dryRun ? null :
            new ReconciliationOperationApplier(reconciliationState, childContexts)
    }

    static ParentChildBudgetSyncer fromConfig(RuntimeConfig runtimeConfig, SyncCliOptions options) {
        fromConfig(runtimeConfig, options, System.getenv())
    }

    static ParentChildBudgetSyncer fromConfig(RuntimeConfig runtimeConfig, SyncCliOptions options, Map<String, String> environment) {
        String parentToken = resolveRequiredToken(runtimeConfig.sync.parentBudget.tokenEnvVarName, 'sync.parentBudget.tokenEnvVarName', environment)
        YnabBudgetRepository parentRepository = new YnabBudgetRepository(new YnabHttpClient('https://api.ynab.com', parentToken))

        String stateDbPath = options.syncStateDbPath ?: runtimeConfig.sync.state.sqlitePath
        SyncStateStore persistentStateStore = new SyncStateStore(stateDbPath)
        SyncStateRepository stateStore = options.dryRun
            ? new DryRunSyncStateRepository(persistentStateStore, stateDbPath)
            : persistentStateStore
        stateStore.initialize()

        List<ChildSyncContext> childContexts = runtimeConfig.sync.childBudgets.collect { ChildBudgetSyncTarget target ->
            String token = resolveRequiredToken(target.tokenEnvVarName, "sync.childBudgets[${target.childKey}].tokenEnvVarName", environment)
            YnabBudgetRepository childRepository = new YnabBudgetRepository(new YnabHttpClient('https://api.ynab.com', token))
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
        Integer requestCursor = transactionCursor()
        TransactionDelta transactionDelta = parentRepository.getTransactions(
            parentBudgetId,
            syncConfig.state.transactionLookbackDays,
            requestCursor
        )
        MoneyMovementSnapshot movementSnapshot = null
        String movementReadFailure = null
        try {
            movementSnapshot = parentRepository.getMoneyMovements(parentBudgetId)
        } catch (Exception failure) {
            movementReadFailure = "money movements: ${failure.message ?: failure.class.simpleName}"
            log.error('Cycle {} could not read money movements: {}', cycleNumber, failure.message, failure)
        }
        TransactionDelta completeTransactionDelta = completeTransactionDelta(parentBudgetId, transactionDelta)
        Set<String> transactionCategoryNames = completeTransactionDelta.transactions.findAll {
            it.approved == true && it.deleted != true
        }.collectMany { ParentTransactionEvent event ->
            [event.categoryName] + event.subtransactions.findAll { it.deleted != true }*.categoryName
        }.findAll() as Set
        Set<String> movementCategoryNames = [] as Set
        if (movementSnapshot) {
            movementSnapshot.movements.each { MoneyMovementEvent movement ->
                movementCategoryNames << parentCategoriesById[movement.fromCategoryId]?.name
                movementCategoryNames << parentCategoriesById[movement.toCategoryId]?.name
            }
            movementCategoryNames.remove(null)
        }
        RoutingResolution transactionRouting = resolveChildRouting(transactionCategoryNames)
        RoutingResolution movementRouting = resolveChildRouting(movementCategoryNames)

        long runId = dryRun ? -1L : stateStore.startRun(dryRun, syncConfig.pollingIntervalSeconds, parentBudgetId)
        try {
            log.info('Cycle {} read {} parent transactions and {} money movements', cycleNumber,
                completeTransactionDelta.transactions.size(), movementSnapshot?.movements?.size() ?: 0)
            List<ParentReconciliationResult> transactionResults = planTransactions(
                parentBudgetId, parentCategoriesById, completeTransactionDelta, transactionRouting.contexts,
                transactionRouting.failedContexts)
            MovementPlanning movementPlanning = movementSnapshot == null ? null :
                planMovements(parentBudgetId, parentCategoriesById, movementSnapshot, movementRouting.contexts,
                    movementRouting.failedContexts)

            if (dryRun) {
                reportDryRun(transactionResults, movementPlanning)
                coordinator.finishRun(runId, SyncRunResult.empty(), transactionDelta.serverKnowledge, false)
                return
            }

            long transactionBatchId = persistTransactionBatch(parentBudgetId, completeTransactionDelta,
                transactionResults, !transactionRouting.failedContexts.isEmpty())
            Long movementBatchId = movementPlanning == null ? null :
                persistMovementBatch(parentBudgetId, movementSnapshot, movementPlanning)

            ReconciliationApplicationResult application = reconciliationApplier.applyReadyOperations()
            boolean transactionComplete = transactionRouting.failures.isEmpty() &&
                reconciliationState.completeIngestionBatchIfReady(transactionBatchId)
            boolean movementComplete = movementBatchId == null || (movementRouting.failures.isEmpty() &&
                reconciliationState.completeIngestionBatchIfReady(movementBatchId))
            List<String> failures = new ArrayList<>(application.failures ?: [])
            if (!transactionComplete && application.failed == 0) {
                failures << 'transaction reconciliation batch has unfinished operations'
            }
            if (!movementComplete && application.failed == 0) {
                failures << 'money movement reconciliation batch has unfinished operations'
            }
            if (movementReadFailure) {
                failures << movementReadFailure
            }
            failures.addAll(transactionRouting.failures)
            failures.addAll(movementRouting.failures)
            SyncRunResult result = new SyncRunResult(application.applied, 0,
                application.failed + (failures.size() - application.failures.size()), failures)
            coordinator.finishRun(runId, result, transactionDelta.serverKnowledge, transactionComplete)
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

    private RoutingResolution resolveChildRouting(Set<String> categoryNames) {
        List<ChildSyncContext> resolved = []
        List<ChildSyncContext> failed = []
        List<String> failures = []
        childContexts.each { ChildSyncContext child ->
            def neededMappings = child.target.accountMappings.findAll { mapping ->
                mapping.parentCategoryNames.any { matcher ->
                    categoryNames.any { String name -> matcher.regex ? name ==~ matcher.name : name == matcher.name }
                }
            }
            if (neededMappings.isEmpty()) {
                return
            }
            try {
                if (!child.budgetId) {
                    child.budgetId = child.repository.getLatestBudgetId(child.target.budgetName)
                }
                neededMappings*.childAccountName.unique().each { String accountName ->
                    if (!child.resolveAccountId(accountName)) {
                        child.cacheAccountId(accountName, child.repository.getAccountId(child.budgetId, accountName))
                    }
                }
                resolved << child
            } catch (Exception failure) {
                failed << child
                failures << "${child.target.childKey}: ${failure.message ?: failure.class.simpleName}"
                log.error('Could not resolve routing for child {}: {}', child.target.childKey,
                    failure.message, failure)
            }
        }
        new RoutingResolution(resolved, failed, failures)
    }

    private static boolean routingBlocked(ParentSourceRevision revision,
                                          List<ChildSyncContext> failedContexts) {
        Set<String> categoryNames = revision.components*.categoryName.findAll() as Set
        routingBlocked(categoryNames, failedContexts)
    }

    private static boolean routingBlocked(Set<String> categoryNames,
                                          List<ChildSyncContext> failedContexts) {
        failedContexts.any { ChildSyncContext child ->
            child.target.accountMappings.any { mapping ->
                mapping.parentCategoryNames.any { matcher ->
                    categoryNames.any { String name -> matcher.regex ? name ==~ matcher.name : name == matcher.name }
                }
            }
        }
    }

    private List<ParentReconciliationResult> planTransactions(
        String parentBudgetId,
        Map<String, CategorySnapshot> categoriesById,
        TransactionDelta delta,
        List<ChildSyncContext> planningContexts,
        List<ChildSyncContext> failedContexts = []
    ) {
        def normalizer = new SourceRevisionNormalizer()
        def reconciler = new ParentTransactionReconciler(planningContexts, categoriesById)
        delta.transactions.toList().sort { it.id }.collect { ParentTransactionEvent event ->
            List<ActiveMirrorReference> mirrors = activeMirrorsForParent(parentBudgetId, event.id)
            ParentSourceRevision revision = normalizer.normalize(
                parentBudgetId, event, delta.serverKnowledge, true)
            routingBlocked(revision, failedContexts) ? new ParentReconciliationResult(revision, [], [], false) :
                reconciler.reconcile(revision, mirrors)
        }
    }

    private TransactionDelta completeTransactionDelta(String parentBudgetId, TransactionDelta delta) {
        List<ParentTransactionEvent> complete = delta.transactions.collect { ParentTransactionEvent event ->
            event.deleted != true ? parentRepository.getParentTransaction(parentBudgetId, event.id) : event
        }
        new TransactionDelta(complete, delta.serverKnowledge)
    }

    private MovementPlanning planMovements(
        String parentBudgetId,
        Map<String, CategorySnapshot> categoriesById,
        MoneyMovementSnapshot snapshot,
        List<ChildSyncContext> planningContexts,
        List<ChildSyncContext> failedContexts = []
    ) {
        List<SourceEntityKey> prior = reconciliationState.findSourceEntities(
            parentBudgetId, SourceEntityType.MONEY_MOVEMENT)
        prior.addAll(legacyMirrors().findAll {
            it.source.sourceBudgetId == parentBudgetId && it.source.type == SourceEntityType.MONEY_MOVEMENT
        }*.source)
        prior = prior.unique()
        MovementSnapshotObservation observation = new MoneyMovementNormalizer().normalizeSnapshot(
            parentBudgetId, snapshot.movements, categoriesById, prior, snapshot.serverKnowledge)
        List<ActiveMirrorReference> mirrors = prior.collectMany { SourceEntityKey source ->
            activeMirrorsForSource(source)
        }
        LocalDate cutoff = LocalDate.now().minusDays(syncConfig.state.moneyMovementLookbackDays as long)
        List<MovementDecision> decisions = new MoneyMovementReconciler(planningContexts, categoriesById)
            .reconcile(observation, mirrors, cutoff).findAll { MovementDecision decision ->
                NormalizedMovementObservation current = observation.observations.find { it.source == decision.source }
                !current || !routingBlocked(
                    [current.fromCategoryName, current.toCategoryName].findAll() as Set, failedContexts)
            }
        new MovementPlanning(observation, decisions)
    }

    private List<ActiveMirrorReference> activeMirrorsForParent(String budgetId, String transactionId) {
        List<ChildMirrorState> mirrors = reconciliationState.findMirrorsForParent(budgetId, transactionId, false)
        legacyMirrors().findAll {
            it.source.sourceBudgetId == budgetId && it.source.parentTransactionId == transactionId && it.active
        }.eachWithIndex { LegacyMirrorProjection legacy, int index ->
            mirrors << new ChildMirrorState(-(index + 1L), 0L, legacy.targetBudgetId, legacy.direction,
                legacy.childTransactionId, null, null, 'active', null, null)
        }
        mirrors.collect { ChildMirrorState mirror ->
            SourceEntityKey source = mirror.sourceEntityId > 0 ?
                reconciliationState.findSourceEntityKey(mirror.sourceEntityId) :
                legacyMirrors().find { it.childTransactionId == mirror.childTransactionId }?.source
            new ActiveMirrorReference(source, mirror, childKey(mirror.targetBudgetId), null, null)
        }
    }

    private List<ActiveMirrorReference> activeMirrorsForSource(SourceEntityKey source) {
        List<ChildMirrorState> mirrors = reconciliationState.findMirrorsForSource(source, false)
        legacyMirrors().findAll { it.source == source && it.active }.eachWithIndex {
            LegacyMirrorProjection legacy, int index ->
                mirrors << new ChildMirrorState(-(index + 1L), 0L, legacy.targetBudgetId, legacy.direction,
                    legacy.childTransactionId, null, null, 'active', null, null)
        }
        mirrors.collect { ChildMirrorState mirror ->
            new ActiveMirrorReference(source, mirror, childKey(mirror.targetBudgetId), mirror.direction, null)
        }
    }

    private List<LegacyMirrorProjection> legacyMirrors() {
        dryRun && !reconciliationState.reconciliationSchemaAvailable() ?
            reconciliationState.projectLegacyMigration().mirrors : []
    }

    private String childKey(String budgetId) {
        childContexts.find { it.budgetId == budgetId }?.target?.childKey
    }

    private long persistTransactionBatch(String parentBudgetId, TransactionDelta delta,
                                          List<ParentReconciliationResult> results,
                                          boolean routingBlocked = false) {
        String batchKey = ReconciliationCanonicalizer.stableKey([
            'transaction_delta', parentBudgetId, delta.serverKnowledge,
            results.collect { it.revision.revisionHash }
        ])
        long batchId = reconciliationState.createIngestionBatch(batchKey, 'transaction_delta', delta.serverKnowledge)
        int sequence = 0
        results.each { ParentReconciliationResult result ->
            Map<SourceEntityKey, Long> sourceIds = persistParentRevision(result.revision, batchId)
            result.intents.each { PlannedReconciliationIntent planned ->
                if (!sourceIds.containsKey(planned.source)) {
                    sourceIds[planned.source] = reconciliationState.upsertSourceEntity(planned.source)
                }
            }
            if (!routingBlocked) {
                updateParentLifecycles(result, sourceIds)
            }
            Long priorDelete = null
            result.intents.each { PlannedReconciliationIntent planned ->
                Long dependency = planned.action == PlannedAction.DELETE ? priorDelete :
                    (planned.dependsOnOperationKeys ? priorDelete : null)
                long operationId = persistOperation(planned, batchId, sourceIds[planned.source], sequence++,
                    dependency, result.revision.revisionHash)
                if (planned.action == PlannedAction.DELETE) {
                    priorDelete = operationId
                }
            }
        }
        batchId
    }

    private void updateParentLifecycles(ParentReconciliationResult result,
                                        Map<SourceEntityKey, Long> sourceIds) {
        ParentSourceRevision revision = result.revision
        Set<SourceEntityKey> desiredSources = result.desiredMirrors*.source as Set
        boolean qualifyingParent = revision.deleted != true && revision.approved == true
        boolean split = revision.components.any { it.source != revision.parentSource }
        sourceIds.each { SourceEntityKey source, Long sourceId ->
            boolean active
            if (source == revision.parentSource) {
                active = qualifyingParent && (split || desiredSources.contains(source))
            } else {
                NormalizedSourceComponent component = revision.components.find { it.source == source }
                active = qualifyingParent && component?.deleted != true && desiredSources.contains(source)
            }
            reconciliationState.setSourceLifecycle(sourceId, active ? 'active' : 'deleted')
        }
    }

    private Map<SourceEntityKey, Long> persistParentRevision(ParentSourceRevision revision, long batchId) {
        Map<SourceEntityKey, Long> ids = [:]
        long parentId = reconciliationState.upsertSourceEntity(revision.parentSource)
        ids[revision.parentSource] = parentId
        reconciliationState.appendSourceRevision(parentId, revision.revisionHash, revision.normalizedJson,
            revision.serverKnowledge, batchId)
        revision.components.each { NormalizedSourceComponent component ->
            if (!ids.containsKey(component.source)) {
                long componentId = reconciliationState.upsertSourceEntity(component.source)
                ids[component.source] = componentId
                Map componentRevision = [
                    source: SourceRevisionNormalizer.sourceMap(component.source),
                    date: revision.date, approved: revision.approved, deleted: revision.deleted || component.deleted,
                    payeeId: component.payeeId, payeeName: component.payeeName,
                    categoryId: component.categoryId, categoryName: component.categoryName,
                    amount: component.amount, memo: component.memo
                ]
                String componentJson = ReconciliationCanonicalizer.json(componentRevision)
                reconciliationState.appendSourceRevision(componentId,
                    ReconciliationCanonicalizer.hashJson(componentJson), componentJson,
                    revision.serverKnowledge, batchId)
            }
        }
        ids
    }

    private long persistMovementBatch(String parentBudgetId, MoneyMovementSnapshot snapshot,
                                      MovementPlanning planning) {
        String batchKey = ReconciliationCanonicalizer.stableKey([
            'money_movement_snapshot', parentBudgetId, snapshot.serverKnowledge,
            planning.observation.observations.collect { it.revisionHash }
        ])
        long batchId = reconciliationState.createIngestionBatch(
            batchKey, 'money_movement_snapshot', snapshot.serverKnowledge)
        int sequence = 0
        planning.decisions.each { MovementDecision decision ->
            if (decision.status == MovementObservationStatus.UNCONFIRMED) {
                long entityId = reconciliationState.upsertSourceEntity(decision.source)
                reconciliationState.setSourceLifecycle(entityId, 'unconfirmed')
                log.warn('Money movement {} is unconfirmed: {}', decision.source.moneyMovementId, decision.reason)
                return
            }
            NormalizedMovementObservation observation = planning.observation.observations.find {
                it.source == decision.source
            }
            long entityId = reconciliationState.upsertSourceEntity(decision.source)
            reconciliationState.setSourceLifecycle(entityId, 'active')
            reconciliationState.appendSourceRevision(entityId, observation.revisionHash,
                observation.normalizedJson, observation.serverKnowledge, batchId)
            Long priorDelete = null
            decision.intents.each { PlannedReconciliationIntent planned ->
                Long dependency = planned.action == PlannedAction.DELETE ? priorDelete :
                    (planned.dependsOnOperationKeys ? priorDelete : null)
                long operationId = persistOperation(planned, batchId, entityId, sequence++, dependency,
                    observation.revisionHash)
                if (planned.action == PlannedAction.DELETE) {
                    priorDelete = operationId
                }
            }
        }
        batchId
    }

    private long persistOperation(PlannedReconciliationIntent planned, long batchId, long sourceEntityId,
                                  int sequence, Long dependencyId, String revisionHash) {
        ReconciliationOperationType type = planned.action == PlannedAction.CREATE ?
            ReconciliationOperationType.CREATE : planned.action == PlannedAction.DELETE ?
                ReconciliationOperationType.DELETE : ReconciliationOperationType.UPDATE
        String operationKey = ReconciliationCanonicalizer.stableKey([
            batchId, revisionHash, planned.operationKey, sourceEntityId, type.name(),
            planned.targetBudgetId, planned.childTransactionId, planned.payloadHash
        ])
        reconciliationState.createOperation(new ReconciliationOperationIntent(
            operationKey, batchId, sourceEntityId,
            planned.childMirrorId != null && planned.childMirrorId > 0 ? planned.childMirrorId : null,
            sequence, type, planned.targetBudgetId, planned.childTransactionId,
            planned.payloadJson, planned.payloadHash, dependencyId))
    }

    private void reportDryRun(List<ParentReconciliationResult> transactionResults,
                              MovementPlanning movementPlanning) {
        List<ReconciliationOperationIntent> cleanupOperations = reconciliationState.reconciliationSchemaAvailable() ?
            reconciliationState.findPendingMigrationCleanupOperations()*.intent :
            reconciliationState.projectLegacyMigration().cleanupOperations
        cleanupOperations.each { ReconciliationOperationIntent cleanup ->
            log.info('[DRY RUN] legacy cleanup delete child transaction {} from budget {}',
                cleanup.childTransactionId, cleanup.targetBudgetId)
        }
        List<PlannedReconciliationIntent> intents = transactionResults.collectMany { it.intents }
        if (movementPlanning) {
            intents.addAll(movementPlanning.decisions.collectMany { it.intents })
            movementPlanning.decisions.findAll { it.status == MovementObservationStatus.UNCONFIRMED }.each {
                log.warn('[DRY RUN] money movement {} is unconfirmed: {}', it.source.moneyMovementId, it.reason)
            }
        }
        intents.each { PlannedReconciliationIntent intent ->
            String action = intent.action.name().toLowerCase()
            if (intent.requiresExistenceCheck) {
                def lookup = childContexts.find { it.budgetId == intent.targetBudgetId }.repository
                    .getChildTransaction(intent.targetBudgetId, intent.childTransactionId)
                if (!lookup.found() || lookup.transaction.deleted == true) {
                    action = 'recreation'
                } else if (intent.action == PlannedAction.NO_OP) {
                    action = 'existence-check'
                }
            }
            log.info('[DRY RUN] {} child transaction for {} ({}) in budget {} for source {} payload={}', action,
                intent.targetChildKey, intent.childTransactionId ?: 'new', intent.targetBudgetId,
                intent.source.parentSubtransactionId ?: intent.source.parentTransactionId ?: intent.source.moneyMovementId,
                intent.payloadJson)
        }
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

@groovy.transform.Immutable
class MovementPlanning {
    MovementSnapshotObservation observation
    List<MovementDecision> decisions
}

@groovy.transform.Immutable
class RoutingResolution {
    List<ChildSyncContext> contexts
    List<ChildSyncContext> failedContexts
    List<String> failures
}

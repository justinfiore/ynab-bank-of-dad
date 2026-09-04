package ynabbankofdad.sync

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import ynabbankofdad.config.*
import ynabbankofdad.model.AccountSnapshot
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.reconcile.*
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.*

import java.time.Clock
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

        String stateDbPath = options.syncStateDbPath ?: config.sync.state.sqlitePath
        if (options.dryRun) {
            ParentChildBudgetSyncer syncer = fromConfig(config, options)
            syncer.runLoop()
            return
        }

        SyncStateLock lock = SyncStateLock.acquire(stateDbPath)
        try {
            log.info('Acquired live sync state lock at {}', lock.lockPath)
            ParentChildBudgetSyncer syncer = fromConfig(config, options)
            syncer.runLoop()
        } finally {
            lock.close()
            log.info('Released live sync state lock at {}', lock.lockPath)
        }
    }

    final RuntimeConfig runtimeConfig
    final SyncConfig syncConfig
    final boolean dryRun
    final String stateDbPath
    final int maxCycles
    final YnabBudgetRepository parentRepository
    final SyncStateRepository stateStore
    final List<ChildSyncContext> childContexts
    final Clock clock
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
        this(runtimeConfig, syncConfig, dryRun, stateDbPath, maxCycles, parentRepository, stateStore,
            childContexts, Clock.systemDefaultZone())
    }

    ParentChildBudgetSyncer(
        RuntimeConfig runtimeConfig,
        SyncConfig syncConfig,
        boolean dryRun,
        String stateDbPath,
        int maxCycles,
        YnabBudgetRepository parentRepository,
        SyncStateRepository stateStore,
        List<ChildSyncContext> childContexts,
        Clock clock
    ) {
        this.runtimeConfig = runtimeConfig
        this.syncConfig = syncConfig
        this.dryRun = dryRun
        this.stateDbPath = stateDbPath
        this.maxCycles = maxCycles
        this.parentRepository = parentRepository
        this.stateStore = stateStore
        this.childContexts = childContexts
        this.clock = clock
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
        Integer storedCursor = transactionCursor()
        Integer requestCursor = syncConfig.state.forceLookback ? null : storedCursor
        if (syncConfig.state.forceLookback) {
            log.info(
                'Force lookback enabled; fetching parent transactions with since_date lookback of {} days instead of last_knowledge_of_server={}',
                syncConfig.state.transactionLookbackDays, storedCursor)
        }
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
        TransactionDelta completeTransactionDelta = completeTransactionDelta(
            parentBudgetId, requestCursor, transactionDelta)
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

        ParentCategoryAccountCache mappingCache = new ParentCategoryAccountCache()
        long runId = dryRun ? -1L : stateStore.startRun(syncConfig.pollingIntervalSeconds, parentBudgetId)
        try {
            log.info('Cycle {} read {} parent transactions and {} money movements', cycleNumber,
                completeTransactionDelta.transactions.size(), movementSnapshot?.movements?.size() ?: 0)
            List<ParentReconciliationResult> transactionResults = planTransactions(
                parentBudgetId, parentCategoriesById, completeTransactionDelta, transactionRouting.contexts,
                transactionRouting.failedContexts, mappingCache)
            MovementPlanning movementPlanning = movementSnapshot == null ? null :
                planMovements(parentBudgetId, parentCategoriesById, movementSnapshot, movementRouting.contexts,
                    movementRouting.failedContexts, mappingCache)

            if (dryRun) {
                reportDryRun(transactionResults, movementPlanning)
                logCycleCompletion(cycleNumber, completeTransactionDelta, movementSnapshot,
                    transactionResults, movementPlanning, transactionRouting, movementRouting, [],
                    parentCategoriesById, mappingCache)
                coordinator.finishRun(runId, SyncRunResult.empty(), transactionDelta.serverKnowledge, false)
                return
            }

            long transactionBatchId = persistTransactionBatch(parentBudgetId, completeTransactionDelta,
                transactionResults)
            Long movementBatchId = movementPlanning == null ? null :
                persistMovementBatch(parentBudgetId, movementSnapshot, movementPlanning)

            ReconciliationApplicationResult application = reconciliationApplier.applyReadyOperations()
            boolean transactionComplete = transactionRouting.failures.isEmpty() &&
                reconciliationState.completeIngestionBatchesOfKindIfReady('transaction_delta')
            boolean movementComplete = movementBatchId == null || (movementRouting.failures.isEmpty() &&
                reconciliationState.completeIngestionBatchesOfKindIfReady('money_movement_snapshot'))
            List<String> failures = new ArrayList<>(application.failures ?: [])
            if (!transactionComplete && application.failed == 0) {
                failures << 'transaction reconciliation has unfinished ingestion batches or operations'
            }
            if (!movementComplete && application.failed == 0) {
                failures << 'money movement reconciliation has unfinished ingestion batches or operations'
            }
            if (movementReadFailure) {
                failures << movementReadFailure
            }
            failures.addAll(transactionRouting.failures)
            failures.addAll(movementRouting.failures)
            SyncRunResult result = new SyncRunResult(failures)
            coordinator.finishRun(runId, result, transactionDelta.serverKnowledge, transactionComplete)
            List<ChildSyncContext> resolvedChildren = []
            resolvedChildren.addAll(transactionRouting.contexts)
            resolvedChildren.addAll(movementRouting.contexts)
            refreshChildAccountSnapshots(resolvedChildren)
            logCycleCompletion(cycleNumber, completeTransactionDelta, movementSnapshot,
                transactionResults, movementPlanning, transactionRouting, movementRouting, failures,
                parentCategoriesById, mappingCache)
        } catch (Exception ex) {
            coordinator.failRun(runId, ex)
            throw ex
        }
    }

    private Integer transactionCursor() {
        stateStore.getCursor(SyncRunCoordinator.TRANSACTION_CURSOR_KEY)
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
                Map<String, AccountSnapshot> snapshots = child.repository.accountsByName(child.budgetId)
                snapshots.each { String name, AccountSnapshot snapshot -> child.cacheAccountSnapshot(snapshot) }
                Map<String, String> existingIds = snapshots.collectEntries { String name, AccountSnapshot snapshot ->
                    [(name): snapshot.id]
                }
                neededMappings.each { mapping ->
                    resolveOrCreateChildAccount(child, mapping, categoryNames, existingIds)
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

    private void resolveOrCreateChildAccount(ChildSyncContext child, ChildAccountMapping mapping,
                                             Set<String> categoryNames, Map<String, String> existingIds) {
        String mappedName = mapping.childAccountName
        if (existingIds[mappedName]) {
            child.cacheAccountId(mappedName, existingIds[mappedName])
            return
        }
        if (!child.target.autoCreateAccounts) {
            throw new IllegalStateException(
                "Could not find account named '${mappedName}' in budget '${child.budgetId}'")
        }
        String accountType = YnabBudgetRepository.saveAccountTypeForOnBudget(
            child.target.createdAccountOnBudget != false)
        categoryNames.findAll { String name -> mapping.matches(name) }.each { String parentName ->
            String derived = child.target.derivedAccountName(parentName)
            if (!derived) {
                throw new IllegalStateException(
                    "Child '${child.target.childKey}' derived an empty account name from parent category '${parentName}'")
            }
            String existingId = child.resolveAccountId(derived) ?: existingIds[derived]
            if (existingId) {
                child.cacheAccountId(derived, existingId)
                return
            }
            if (dryRun) {
                log.info(
                    "Would create account '{}' type={} (createdAccountOnBudget={}) in child {}",
                    derived, accountType, child.target.createdAccountOnBudget, child.target.childKey)
                return
            }
            log.info(
                'Creating New YNAB Account: {} in Budget: {} with type: {}',
                derived, child.target.budgetName, accountType)
            Map created = child.repository.createAccount(child.budgetId, derived, accountType)
            String createdId = created.id as String
            child.cacheAccountId(derived, createdId)
            existingIds[derived] = createdId
        }
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
        List<ChildSyncContext> failedContexts = [],
        ParentCategoryAccountCache mappingCache = null
    ) {
        def normalizer = new SourceRevisionNormalizer()
        def reconciler = new ParentTransactionReconciler(planningContexts, categoriesById, mappingCache)
        delta.transactions.toList().sort { it.id }.collect { ParentTransactionEvent event ->
            List<ActiveMirrorReference> mirrors = activeMirrorsForParent(parentBudgetId, event.id)
            ParentSourceRevision revision = normalizer.normalize(
                parentBudgetId, event, delta.serverKnowledge, true)
            ParentReconciliationResult planned = reconciler.reconcile(revision, mirrors)
            Set<SourceEntityKey> blockedSources = revision.components.findAll { component ->
                routingBlocked([component.categoryName].findAll() as Set, failedContexts)
            }*.source as Set
            Set<String> failedChildKeys = failedContexts*.target*.childKey.findAll() as Set
            List<PlannedReconciliationIntent> safeIntents = planned.intents.findAll { intent ->
                intent.action != PlannedAction.DELETE ||
                    (!blockedSources.contains(intent.source) &&
                        !failedChildKeys.contains(intent.targetChildKey))
            }
            new ParentReconciliationResult(revision, planned.desiredMirrors, safeIntents,
                planned.fetchRequired, false, blockedSources)
        }
    }

    private TransactionDelta completeTransactionDelta(String parentBudgetId, Integer requestCursor,
                                                      TransactionDelta delta) {
        boolean incremental = requestCursor != null
        List<ParentTransactionEvent> complete = delta.transactions.collect { ParentTransactionEvent event ->
            if (!incremental || event.deleted == true || !needsCompleteSplitLookup(parentBudgetId, event)) {
                return event
            }
            parentRepository.getParentTransaction(parentBudgetId, event.id)
        }
        new TransactionDelta(complete, delta.serverKnowledge)
    }

    private boolean needsCompleteSplitLookup(String parentBudgetId, ParentTransactionEvent event) {
        Set<String> listedSubIds = ((event.subtransactions ?: []) as List)*.id.findAll() as Set
        activeMirrorsForParent(parentBudgetId, event.id).any { ActiveMirrorReference ref ->
            ref.source?.type == SourceEntityType.SUBTRANSACTION &&
                !listedSubIds.contains(ref.source.parentSubtransactionId)
        }
    }

    private MovementPlanning planMovements(
        String parentBudgetId,
        Map<String, CategorySnapshot> categoriesById,
        MoneyMovementSnapshot snapshot,
        List<ChildSyncContext> planningContexts,
        List<ChildSyncContext> failedContexts = [],
        ParentCategoryAccountCache mappingCache = null
    ) {
        List<SourceEntityKey> prior = reconciliationState.findSourceEntities(
            parentBudgetId, SourceEntityType.MONEY_MOVEMENT)
        MovementSnapshotObservation observation = new MoneyMovementNormalizer().normalizeSnapshot(
            parentBudgetId, snapshot.movements, categoriesById, prior, snapshot.serverKnowledge)
        List<ActiveMirrorReference> mirrors = prior.collectMany { SourceEntityKey source ->
            activeMirrorsForSource(source)
        }
        LocalDate cutoff = LocalDate.now(clock).minusDays(syncConfig.state.moneyMovementLookbackDays as long)
        List<MovementDecision> decisions = new MoneyMovementReconciler(planningContexts, categoriesById, mappingCache)
            .reconcile(observation, mirrors, cutoff).collect { MovementDecision decision ->
                NormalizedMovementObservation current = observation.observations.find { it.source == decision.source }
                boolean blocked = current && routingBlocked(
                    [current.fromCategoryName, current.toCategoryName].findAll() as Set, failedContexts)
                if (!blocked) {
                    return decision
                }
                // Keep the observation for lifecycle/audit but drop destructive intents while routing is incomplete.
                new MovementDecision(decision.source, decision.status, [], [],
                    'child routing required for this movement failed this cycle', true)
            }
        new MovementPlanning(observation, decisions)
    }

    private List<ActiveMirrorReference> activeMirrorsForParent(String budgetId, String transactionId) {
        List<ChildMirrorState> mirrors = reconciliationState.findMirrorsForParent(budgetId, transactionId, false)
        mirrors.collect { ChildMirrorState mirror ->
            SourceEntityKey source = reconciliationState.findSourceEntityKey(mirror.sourceEntityId)
            new ActiveMirrorReference(source, mirror, childKey(mirror.targetBudgetId), null, null)
        }
    }

    private List<ActiveMirrorReference> activeMirrorsForSource(SourceEntityKey source) {
        List<ChildMirrorState> mirrors = reconciliationState.findMirrorsForSource(source, false)
        mirrors.collect { ChildMirrorState mirror ->
            new ActiveMirrorReference(source, mirror, childKey(mirror.targetBudgetId), mirror.direction, null)
        }
    }

    private String childKey(String budgetId) {
        childContexts.find { it.budgetId == budgetId }?.target?.childKey
    }

    private long persistTransactionBatch(String parentBudgetId, TransactionDelta delta,
                                          List<ParentReconciliationResult> results) {
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
            updateParentLifecycles(result, sourceIds)
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
        plannedSourceLifecycles(result, sourceIds).each { Long sourceId, String lifecycle ->
            reconciliationState.setSourceLifecycle(sourceId, lifecycle)
        }
    }

    /**
     * Returns lifecycle updates for a planned result. Routing-blocked sources are omitted so a
     * temporary child lookup failure cannot mark their empty desired state as deleted.
     */
    static Map<Long, String> plannedSourceLifecycles(ParentReconciliationResult result,
                                                     Map<SourceEntityKey, Long> sourceIds) {
        if (result?.routingBlocked || !result?.revision || sourceIds == null || sourceIds.isEmpty()) {
            return [:]
        }
        ParentSourceRevision revision = result.revision
        Set<SourceEntityKey> desiredSources = result.desiredMirrors*.source as Set
        boolean qualifyingParent = revision.deleted != true && revision.approved == true
        boolean split = revision.components.any { it.source != revision.parentSource }
        Map<Long, String> updates = [:]
        sourceIds.each { SourceEntityKey source, Long sourceId ->
            if (result.routingBlockedSources?.contains(source)) {
                return
            }
            boolean active
            if (source == revision.parentSource) {
                active = qualifyingParent && (split || desiredSources.contains(source))
            } else {
                NormalizedSourceComponent component = revision.components.find { it.source == source }
                active = qualifyingParent && component?.deleted != true && desiredSources.contains(source)
            }
            updates[sourceId] = active ? 'active' : 'deleted'
        }
        updates
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
                if (!decision.routingBlocked) {
                    reconciliationState.setSourceLifecycle(entityId, 'unconfirmed')
                }
                log.warn('Money movement {} is unconfirmed: {}', decision.source.moneyMovementId, decision.reason)
                return
            }
            NormalizedMovementObservation observation = planning.observation.observations.find {
                it.source == decision.source
            }
            long entityId = reconciliationState.upsertSourceEntity(decision.source)
            if (!decision.routingBlocked) {
                reconciliationState.setSourceLifecycle(entityId, 'active')
            }
            if (observation) {
                reconciliationState.appendSourceRevision(entityId, observation.revisionHash,
                    observation.normalizedJson, observation.serverKnowledge, batchId)
            }
            if (decision.routingBlocked) {
                return
            }
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
        long operationId = reconciliationState.createOperation(new ReconciliationOperationIntent(
            operationKey, batchId, sourceEntityId,
            planned.childMirrorId != null && planned.childMirrorId > 0 ? planned.childMirrorId : null,
            sequence, type, planned.targetBudgetId, planned.childTransactionId,
            planned.payloadJson, planned.payloadHash, dependencyId))
        log.info(
            'Reconciliation decision action={} operation={} key={} batch={} sequence={} source={} targetBudget={} childTransaction={} dependency={} payload={}',
            planned.action.name().toLowerCase(), operationId, operationKey, batchId, sequence,
            auditSource(planned.source), planned.targetBudgetId, planned.childTransactionId ?: 'new',
            dependencyId ?: 'none', auditPayload(planned.payloadJson))
        operationId
    }

    private static String auditSource(SourceEntityKey source) {
        [
            budget        : source.sourceBudgetId,
            type          : source.type.databaseValue,
            transaction   : source.parentTransactionId,
            subtransaction: source.parentSubtransactionId,
            movement      : source.moneyMovementId
        ].findAll { String ignored, Object value -> value != null }.collect { key, value -> "${key}=${value}" }.join(',')
    }

    private static String auditPayload(String payloadJson) {
        if (!payloadJson) {
            return 'none'
        }
        Object payload = new groovy.json.JsonSlurper().parseText(payloadJson)
        JsonOutput.toJson(YnabLogFormatter.formatAmounts(payload))
    }

    private void reportDryRun(List<ParentReconciliationResult> transactionResults,
                              MovementPlanning movementPlanning) {
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

    private void refreshChildAccountSnapshots(List<ChildSyncContext> children) {
        children.findAll { it?.budgetId }.unique { it.target.childKey }.each { ChildSyncContext child ->
            try {
                child.repository.accountsByName(child.budgetId).each { String name, AccountSnapshot snapshot ->
                    child.cacheAccountSnapshot(snapshot)
                }
            } catch (Exception ex) {
                log.warn('Could not refresh accounts for child {} for balance reporting: {}',
                    child.target.childKey, ex.message)
            }
        }
    }

    private void logCycleCompletion(int cycleNumber, TransactionDelta transactionDelta,
                                    MoneyMovementSnapshot movementSnapshot,
                                    List<ParentReconciliationResult> transactionResults,
                                    MovementPlanning movementPlanning,
                                    RoutingResolution transactionRouting,
                                    RoutingResolution movementRouting,
                                    List<String> failures,
                                    Map<String, CategorySnapshot> parentCategoriesById,
                                    ParentCategoryAccountCache mappingCache) {
        List<PlannedReconciliationIntent> intents = (transactionResults ?: []).collectMany { it.intents }
        if (movementPlanning) {
            intents.addAll(movementPlanning.decisions.collectMany { it.intents })
        }
        List<PlannedReconciliationIntent> mutating = intents.findAll {
            it.action == PlannedAction.CREATE || it.action == PlannedAction.UPDATE || it.action == PlannedAction.DELETE
        }
        String throughDate = parentSyncedThroughDate(transactionDelta, movementSnapshot)
        String mode = dryRun ? ' (dry-run)' : ''
        log.info('Cycle {} completed{}', cycleNumber, mode)
        log.info(
            'Cycle {} parent synced through {} ({} parent transactions, {} money movements)',
            cycleNumber, throughDate, transactionDelta?.transactions?.size() ?: 0,
            movementSnapshot?.movements?.size() ?: 0)
        log.info(
            'Cycle {} propagated {} parent sources to child budgets',
            cycleNumber, mutating.collect { identityOf(it.source) }.unique().size())
        childContexts.each { ChildSyncContext child ->
            List<PlannedReconciliationIntent> childIntents = mutating.findAll { it.targetChildKey == child.target.childKey }
            log.info(
                'Cycle {} child {}: created={} updated={} deleted={}',
                cycleNumber, child.target.childKey,
                childIntents.count { it.action == PlannedAction.CREATE },
                childIntents.count { it.action == PlannedAction.UPDATE },
                childIntents.count { it.action == PlannedAction.DELETE })
        }
        Map<String, CategorySnapshot> parentByName = (parentCategoriesById ?: [:]).values()
            .collectEntries { CategorySnapshot snapshot -> [(snapshot.name): snapshot] }
        Map<String, Map<String, AccountSnapshot>> childAccounts = [:]
        childContexts.each { ChildSyncContext child ->
            childAccounts[child.target.childKey] = new LinkedHashMap<>(child.accountSnapshotsByName ?: [:])
        }
        new CycleBalanceReporter().report(cycleNumber, dryRun, mappingCache ?: new ParentCategoryAccountCache(),
            parentByName, intents, childAccounts)
        List<String> unreplicated = unreplicatedReasons(transactionDelta, movementSnapshot, transactionRouting,
            movementRouting, movementPlanning, failures, parentCategoriesById)
        if (unreplicated) {
            log.warn('Cycle {} could not replicate: {}', cycleNumber, unreplicated.join('; '))
        } else {
            log.info('Cycle {} could not replicate: none', cycleNumber)
        }
        log.info(
            'Cycle {} API retries: rate-limit={} other={}',
            cycleNumber, totalRateLimitRetries(), totalOtherRetries())
    }

    private String parentSyncedThroughDate(TransactionDelta transactionDelta, MoneyMovementSnapshot movementSnapshot) {
        List<String> dates = []
        (transactionDelta?.transactions ?: []).each { ParentTransactionEvent event ->
            if (event.date) {
                dates << event.date
            }
        }
        (movementSnapshot?.movements ?: []).each { MoneyMovementEvent movement ->
            if (movement.eventDate) {
                dates << movement.eventDate
            }
        }
        dates.max() ?: LocalDate.now(clock).minusDays(syncConfig.state.transactionLookbackDays as long).toString()
    }

    private List<String> unreplicatedReasons(TransactionDelta transactionDelta, MoneyMovementSnapshot movementSnapshot,
                                             RoutingResolution transactionRouting, RoutingResolution movementRouting,
                                             MovementPlanning movementPlanning, List<String> failures,
                                             Map<String, CategorySnapshot> parentCategoriesById) {
        List<String> reasons = []
        List<String> unmapped = unmappedCategoryNames(transactionDelta, movementSnapshot, parentCategoriesById)
        if (unmapped) {
            reasons << "unmapped parent categories [${unmapped.join(', ')}]"
        }
        List<String> routing = []
        routing.addAll(transactionRouting?.failures ?: [])
        routing.addAll(movementRouting?.failures ?: [])
        if (routing) {
            reasons << "routing failures [${routing.unique().join(', ')}]"
        }
        List<String> unconfirmed = (movementPlanning?.decisions ?: []).findAll {
            it.status == MovementObservationStatus.UNCONFIRMED
        }.collect { it.source.moneyMovementId }
        if (unconfirmed) {
            reasons << "unconfirmed money movements [${unconfirmed.join(', ')}]"
        }
        List<String> applyFailures = (failures ?: []).findAll { it }
        if (applyFailures) {
            reasons << "failures [${applyFailures.join(', ')}]"
        }
        reasons
    }

    private List<String> unmappedCategoryNames(TransactionDelta transactionDelta, MoneyMovementSnapshot movementSnapshot,
                                               Map<String, CategorySnapshot> parentCategoriesById) {
        Set<String> names = [] as Set
        (transactionDelta?.transactions ?: []).findAll { it.approved == true && it.deleted != true }.each { event ->
            names << event.categoryName
            (event.subtransactions ?: []).findAll { it.deleted != true }.each { names << it.categoryName }
        }
        (movementSnapshot?.movements ?: []).each { MoneyMovementEvent movement ->
            names << (parentCategoriesById ?: [:])[movement.fromCategoryId]?.name
            names << (parentCategoriesById ?: [:])[movement.toCategoryId]?.name
        }
        names.remove(null)
        names.remove('')
        names.findAll { String name -> !categoryMapped(name) }.sort()
    }

    private boolean categoryMapped(String categoryName) {
        childContexts.any { ChildSyncContext child ->
            child.target.accountMappings.any { it.matches(categoryName) }
        }
    }

    private static String identityOf(SourceEntityKey source) {
        source.parentSubtransactionId ?: source.parentTransactionId ?: source.moneyMovementId
    }

    private int totalRateLimitRetries() {
        int total = parentRepository?.rateLimitRetryCount ?: 0
        childContexts.each { ChildSyncContext child ->
            total += child.repository?.rateLimitRetryCount ?: 0
        }
        total
    }

    private int totalOtherRetries() {
        int total = parentRepository?.otherRetryCount ?: 0
        childContexts.each { ChildSyncContext child ->
            total += child.repository?.otherRetryCount ?: 0
        }
        total
    }

    private static String resolveRequiredToken(String envVarName, String configKey, Map<String, String> environment) {
        if (!envVarName?.trim()) {
            throw new IllegalArgumentException("${configKey} must not be blank")
        }
        List<String> tokens = YnabHttpClient.parseAccessTokens(environment[envVarName])
        if (!tokens) {
            throw new IllegalArgumentException("Environment variable '${envVarName}' referenced by ${configKey} must be set")
        }
        tokens.join(',')
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

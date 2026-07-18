package ynabbankofdad.sync

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.SyncStateRepository
import ynabbankofdad.ynab.YnabLogFormatter

@Slf4j
class ChildSyncApplier {
    static final String DRY_RUN_PREFIX = '[DRY RUN]'

    private final SyncStateRepository stateStore
    private final ChildTransactionPayloadFactory payloadFactory
    private final boolean dryRun

    ChildSyncApplier(SyncStateRepository stateStore, ChildTransactionPayloadFactory payloadFactory, boolean dryRun) {
        this.stateStore = stateStore
        this.payloadFactory = payloadFactory
        this.dryRun = dryRun
    }

    SyncRunResult applyPlans(long runId, List<ChildTransactionPlan> plans, List<ChildSyncContext> childContexts) {
        if (plans.isEmpty()) {
            log.info('No qualifying child sync work found')
            return SyncRunResult.empty()
        }

        Map<String, List<ChildTransactionPlan>> plansByChild = plans.groupBy { it.targetChildKey }
        List<ChildApplyResult> childResults = plansByChild.collect { String childKey, List<ChildTransactionPlan> childPlans ->
            ChildSyncContext childContext = childContexts.find { it.target.childKey == childKey }
            applyChildPlans(runId, childContext, childPlans)
        }
        SyncRunResult.fromChildResults(childResults)
    }

    ChildApplyResult applyChildPlans(long runId, ChildSyncContext childContext, List<ChildTransactionPlan> childPlans) {
        String childKey = childContext?.target?.childKey
        String budgetId
        try {
            budgetId = childContext.budgetId ?: childContext.repository.getLatestBudgetId(childContext.target.budgetName)
            childContext.budgetId = budgetId
        } catch (Exception ex) {
            log.error('Child sync target {} failed during budget resolution: {}', childKey, ex.message, ex)
            childPlans.each { recordFailedPlan(runId, childContext, it, null, null, ex) }
            return new ChildApplyResult(childKey, 0, 0, childPlans.size(), ["${childKey}: ${ex.message}"])
        }

        int applied = 0
        int skipped = 0
        int failed = 0
        List<String> failures = []
        childPlans.each { ChildTransactionPlan provisionalPlan ->
            String accountId
            ChildTransactionPlan plan = provisionalPlan
            Long mappingId
            try {
                accountId = childContext.resolveAccountId(provisionalPlan.childAccountName)
                if (!accountId) {
                    accountId = childContext.repository.getAccountId(budgetId, provisionalPlan.childAccountName)
                    childContext.cacheAccountId(provisionalPlan.childAccountName, accountId)
                }
                plan = ChildSyncIdempotency.withKey(
                    provisionalPlan,
                    ChildSyncIdempotency.buildKey(provisionalPlan, accountId)
                )

                if (stateStore.hasAppliedIdempotencyKey(plan.idempotencyKey)) {
                    log.info('Skipping duplicate child sync plan {}', plan.idempotencyKey)
                    skipped++
                    return
                }

                String prefix = childContext.target.memoPrefix
                String suffix = childContext.target.memoSuffix
                Map<String, Object> transaction = payloadFactory.buildTransaction(plan, accountId, prefix, suffix)

                if (dryRun) {
                    log.info('{} child transaction for {} mapping {} account {} -> {}', DRY_RUN_PREFIX, childContext.target.childKey, plan.mappingKey, plan.childAccountName, JsonOutput.toJson(YnabLogFormatter.formatAmounts(transaction)))
                    log.info('{} sqlite state for {} mapping {} -> {}', DRY_RUN_PREFIX, childContext.target.childKey, plan.mappingKey, plan.idempotencyKey)
                    return
                }

                long sourceEventId = stateStore.recordSourceEvent(plan)
                mappingId = stateStore.recordMapping(sourceEventId, plan, budgetId, accountId)
                def response = childContext.repository.postTransactions(budgetId, [transaction])
                String createdTransactionId = payloadFactory.extractCreatedTransactionId(response)
                if (!createdTransactionId) {
                    throw new IllegalStateException('YNAB child transaction response did not include a created transaction ID')
                }
                stateStore.recordAppliedTransaction(mappingId, runId, budgetId, createdTransactionId, 'applied', null, false)
                log.info('Posted child transaction for {} mapping {} account {}: {}', childContext.target.childKey, plan.mappingKey, plan.childAccountName, createdTransactionId)
                applied++
            } catch (Exception ex) {
                failed++
                failures << "${childKey}: ${ex.message}"
                log.error('Child sync target {} plan {} failed: {}', childKey, provisionalPlan.mappingKey, ex.message, ex)
                recordFailedPlan(runId, childContext, plan, accountId, mappingId, ex)
            }
        }
        new ChildApplyResult(childKey, applied, skipped, failed, failures)
    }

    private void recordFailedPlan(
        long runId,
        ChildSyncContext childContext,
        ChildTransactionPlan plan,
        String accountId,
        Long existingMappingId,
        Exception failure
    ) {
        if (dryRun || runId <= 0) {
            return
        }
        try {
            long mappingId = existingMappingId ?: stateStore.recordMapping(
                stateStore.recordSourceEvent(plan),
                plan,
                childContext?.budgetId ?: plan.targetBudgetName,
                accountId
            )
            String targetBudgetId = childContext?.budgetId ?: plan.targetBudgetName
            stateStore.recordAppliedTransaction(mappingId, runId, targetBudgetId, null, 'failed', failure.message, false)
        } catch (Exception stateFailure) {
            log.error('Could not record failed child sync plan {}: {}', plan.mappingKey, stateFailure.message, stateFailure)
        }
    }
}

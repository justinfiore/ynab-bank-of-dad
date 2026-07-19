package ynabbankofdad.sync

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Immutable
import groovy.util.logging.Slf4j
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.ChildTransaction
import ynabbankofdad.sync.reconcile.DesiredMirrorFactory
import ynabbankofdad.sync.state.*
import ynabbankofdad.ynab.YnabBudgetRepository
import ynabbankofdad.ynab.YnabLogFormatter

@Slf4j
class ReconciliationOperationApplier {
    private static final Set<String> UPDATE_FIELDS = [
        'account_id', 'date', 'amount', 'payee_id', 'payee_name'
    ] as Set

    private final ReconciliationOperationStateRepository stateStore
    private final List<ChildSyncContext> childContexts
    private final ChildTransactionPayloadFactory payloadFactory
    private final JsonSlurper jsonSlurper = new JsonSlurper()

    ReconciliationOperationApplier(ReconciliationOperationStateRepository stateStore,
                                   List<ChildSyncContext> childContexts,
                                   ChildTransactionPayloadFactory payloadFactory = new ChildTransactionPayloadFactory()) {
        this.stateStore = stateStore
        this.childContexts = childContexts ?: []
        this.payloadFactory = payloadFactory
    }

    ReconciliationApplicationResult applyReadyOperations(int limit = 100) {
        int applied = 0
        int failed = 0
        List<String> failures = []
        Set<Long> attempted = [] as Set

        while (attempted.size() < limit) {
            List<ReconciliationOperation> ready = stateStore.findReadyOperations(limit - attempted.size())
                .findAll { !attempted.contains(it.id) }
            if (ready.isEmpty()) {
                break
            }
            ready.each { ReconciliationOperation operation ->
                attempted << operation.id
                try {
                    applyOperation(operation)
                    applied++
                } catch (Exception failure) {
                    failed++
                    String reason = failure.message ?: failure.class.simpleName
                    failures << "${operation.intent.targetBudgetId}: operation ${operation.id}: ${reason}"
                    recordFailure(operation, reason)
                    log.error('Reconciliation operation {} for child budget {} failed: {}',
                        operation.id, operation.intent.targetBudgetId, reason, failure)
                }
            }
        }
        new ReconciliationApplicationResult(applied, failed, failures)
    }

    private void applyOperation(ReconciliationOperation operation) {
        ChildSyncContext context = childContext(operation.intent.targetBudgetId)
        YnabBudgetRepository repository = context.repository
        Map<String, Object> desired = operation.intent.operationType == ReconciliationOperationType.DELETE
            ? [:] : payload(operation)
        ReconciliationOperationAttempt priorSuccess = stateStore.findSuccessfulOperationAttempt(operation.id)

        switch (operation.intent.operationType) {
            case ReconciliationOperationType.CREATE:
                applyCreate(operation, repository, desired, priorSuccess, false)
                break
            case ReconciliationOperationType.UPDATE:
                applyUpdate(operation, repository, desired)
                break
            case ReconciliationOperationType.DELETE:
                applyDelete(operation, repository, priorSuccess)
                break
        }
    }

    private void applyCreate(ReconciliationOperation operation, YnabBudgetRepository repository,
                             Map<String, Object> desired, ReconciliationOperationAttempt priorSuccess,
                             boolean recreation) {
        String direction = desired.remove(DesiredMirrorFactory.LOGICAL_DIRECTION_FIELD) as String
        if (!direction) {
            throw new IllegalStateException('Child transaction payload must contain a logical reconciliation direction')
        }
        desired.import_id = payloadFactory.buildImportId(
            stateStore.findSourceEntityKey(operation.intent.sourceEntityId),
            operation.intent.targetBudgetId, direction)

        String childTransactionId = priorSuccess?.returnedChildTransactionId
        String outcome = 'already_complete'
        if (!childTransactionId) {
            def response = repository.postTransactions(operation.intent.targetBudgetId, [desired])
            childTransactionId = payloadFactory.extractCreatedTransactionId(response)
            if (!childTransactionId && duplicateImportIds(response).contains(desired.import_id as String)) {
                childTransactionId = repository.recoverChildTransactionByImportId(
                    operation.intent.targetBudgetId, desired.import_id as String, desired).transaction.id
                outcome = 'already_complete'
            } else {
                outcome = 'applied'
            }
            if (!childTransactionId) {
                throw new IllegalStateException('YNAB child transaction response did not include a created transaction ID')
            }
        }
        stateStore.recordOperationAttempt(operation.id, outcome, null, childTransactionId)
        stateStore.completeCreateOperation(
            operation.id, operation.intent.sourceEntityId, operation.intent.childMirrorId,
            operation.intent.targetBudgetId, direction, childTransactionId,
            desired.account_id as String, operation.intent.payloadHash, recreation)
        log.info(
            'Reconciliation outcome action={} outcome={} operation={} source={} targetBudget={} childTransaction={} direction={} account={} payload={}',
            recreation ? 'recreate' : 'create', outcome, operation.id, auditSource(operation),
            operation.intent.targetBudgetId, childTransactionId, direction, desired.account_id,
            auditPayload(desired))
    }

    private void applyUpdate(ReconciliationOperation operation, YnabBudgetRepository repository,
                             Map<String, Object> desired) {
        String childId = operation.intent.childTransactionId
        def lookup = repository.getChildTransaction(operation.intent.targetBudgetId, childId)
        if (!lookup.found() || lookup.transaction.deleted) {
            ReconciliationOperationAttempt prior = stateStore.findSuccessfulOperationAttempt(operation.id)
            ReconciliationOperationAttempt priorRecreation = prior?.returnedChildTransactionId &&
                prior.returnedChildTransactionId != operation.intent.childTransactionId ? prior : null
            applyCreate(operation, repository, desired, priorRecreation, true)
            return
        }

        Map<String, Object> update = desired.findAll { String key, Object ignored -> UPDATE_FIELDS.contains(key) }
        update.cleared = 'cleared'
        update.approved = false
        String outcome = 'already_complete'
        if (!matches(lookup.transaction, update)) {
            def result = repository.updateChildTransaction(operation.intent.targetBudgetId, childId, update)
            outcome = 'applied'
            childId = result.transaction.id
        }
        stateStore.recordOperationAttempt(operation.id, outcome, null, childId)
        stateStore.completeUpdateOperation(operation.id, operation.intent.childMirrorId,
            desired.account_id as String, operation.intent.payloadHash)
        log.info(
            'Reconciliation outcome action=update outcome={} operation={} source={} targetBudget={} childTransaction={} account={} payload={}',
            outcome, operation.id, auditSource(operation), operation.intent.targetBudgetId, childId,
            desired.account_id, auditPayload(update))
    }

    private void applyDelete(ReconciliationOperation operation, YnabBudgetRepository repository,
                             ReconciliationOperationAttempt priorSuccess) {
        String outcome = 'already_complete'
        if (priorSuccess == null) {
            def result = repository.deleteChildTransaction(
                operation.intent.targetBudgetId, operation.intent.childTransactionId)
            outcome = result.alreadyAbsent ? 'already_complete' : 'applied'
        }
        stateStore.recordOperationAttempt(operation.id, outcome, null, null)
        stateStore.completeDeleteOperation(operation.id, operation.intent.childMirrorId)
        log.info(
            'Reconciliation outcome action=delete outcome={} operation={} source={} targetBudget={} childTransaction={}',
            outcome, operation.id, auditSource(operation), operation.intent.targetBudgetId,
            operation.intent.childTransactionId)
    }

    private ChildSyncContext childContext(String targetBudgetId) {
        ChildSyncContext context = childContexts.find { it.budgetId == targetBudgetId }
        if (context != null) {
            return context
        }
        List<String> resolutionFailures = []
        context = childContexts.find { ChildSyncContext candidate ->
            if (candidate.budgetId == null) {
                try {
                    candidate.budgetId = candidate.repository.getLatestBudgetId(candidate.target.budgetName)
                } catch (Exception failure) {
                    resolutionFailures << "${candidate.target.childKey}: ${failure.message}"
                }
            }
            candidate.budgetId == targetBudgetId
        }
        if (context == null) {
            String detail = resolutionFailures ? ": ${resolutionFailures.join('; ')}" : ''
            throw new IllegalStateException("No child context resolves to budget '${targetBudgetId}'${detail}")
        }
        context
    }

    private Map<String, Object> payload(ReconciliationOperation operation) {
        if (!operation.intent.payloadJson) {
            throw new IllegalStateException("Operation ${operation.id} has no child transaction payload")
        }
        def parsed = jsonSlurper.parseText(operation.intent.payloadJson)
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException("Operation ${operation.id} child transaction payload is not an object")
        }
        Map value = parsed as Map
        Map transaction = value.transaction instanceof Map ? value.transaction as Map : value
        new LinkedHashMap<String, Object>(transaction)
    }

    private static List<String> duplicateImportIds(def response) {
        def ids = response?.data?.duplicate_import_ids ?: response?.data?.bulk?.duplicate_import_ids
        ids instanceof List ? ids.collect { it as String } : []
    }

    private String auditSource(ReconciliationOperation operation) {
        SourceEntityKey source = stateStore.findSourceEntityKey(operation.intent.sourceEntityId)
        [
            budget        : source.sourceBudgetId,
            type          : source.type.databaseValue,
            transaction   : source.parentTransactionId,
            subtransaction: source.parentSubtransactionId,
            movement      : source.moneyMovementId
        ].findAll { String ignored, Object value -> value != null }.collect { key, value -> "${key}=${value}" }.join(',')
    }

    private static String auditPayload(Map<String, Object> payload) {
        JsonOutput.toJson(YnabLogFormatter.formatAmounts(payload))
    }

    private static boolean matches(ChildTransaction actual, Map<String, Object> desired) {
        if (desired.containsKey('account_id') && actual.accountId != desired.account_id as String) return false
        if (desired.containsKey('date') && actual.date != desired.date as String) return false
        if (desired.containsKey('amount') && actual.amount != (desired.amount as Number).intValue()) return false
        if (desired.containsKey('payee_id')) {
            if (desired.payee_id != null && actual.payeeId != desired.payee_id as String) return false
            if (desired.payee_id == null && desired.containsKey('payee_name') &&
                actual.payeeName != desired.payee_name as String) return false
            if (desired.payee_id == null && !desired.containsKey('payee_name') &&
                (actual.payeeId != null || actual.payeeName != null)) return false
        } else if (desired.containsKey('payee_name') && actual.payeeName != desired.payee_name as String) {
            return false
        }
        actual.cleared == desired.cleared && actual.approved == desired.approved
    }

    private void recordFailure(ReconciliationOperation operation, String reason) {
        try {
            stateStore.recordOperationAttempt(operation.id, 'failed', reason, null)
        } catch (Exception stateFailure) {
            log.error('Could not append failed attempt for reconciliation operation {}: {}',
                operation.id, stateFailure.message, stateFailure)
        }
        try {
            stateStore.markOperationRetryable(operation.id)
        } catch (Exception stateFailure) {
            log.error('Could not retain reconciliation operation {} for retry: {}',
                operation.id, stateFailure.message, stateFailure)
        }
    }
}

@Immutable
class ReconciliationApplicationResult {
    int applied
    int failed
    List<String> failures
}

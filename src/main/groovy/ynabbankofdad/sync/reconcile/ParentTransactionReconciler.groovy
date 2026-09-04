package ynabbankofdad.sync.reconcile

import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.ChildTransaction
import ynabbankofdad.sync.state.SourceEntityKey

class ParentTransactionReconciler {
    private final List<ChildSyncContext> childContexts
    private final Map<String, CategorySnapshot> categoriesById
    private final ParentCategoryAccountCache mappingCache

    ParentTransactionReconciler(List<ChildSyncContext> childContexts,
                                Map<String, CategorySnapshot> categoriesById = [:],
                                ParentCategoryAccountCache mappingCache = null) {
        this.childContexts = childContexts ?: []
        this.categoriesById = categoriesById ?: [:]
        this.mappingCache = mappingCache
    }

    ParentReconciliationResult reconcile(ParentSourceRevision revision,
                                         List<ActiveMirrorReference> activeMirrors = []) {
        List<ActiveMirrorReference> mirrors = (activeMirrors ?: []).findAll {
            it.source.sourceBudgetId == revision.parentSource.sourceBudgetId &&
                it.source.parentTransactionId == revision.parentSource.parentTransactionId &&
                it.mirror.status == 'active'
        }
        if (revision.requiresCompleteFetch()) {
            return new ParentReconciliationResult(revision, [], [], true, false, [] as Set)
        }

        List<DesiredMirror> desired = desiredMirrors(revision)
        List<PlannedReconciliationIntent> intents = compare(desired, mirrors)
        new ParentReconciliationResult(revision, desired, intents, false, false, [] as Set)
    }

    List<ParentReconciliationResult> reconcileChanged(List<ParentSourceRevision> changedRevisions,
                                                      List<ActiveMirrorReference> activeMirrors = []) {
        (changedRevisions ?: []).collect { revision -> reconcile(revision, activeMirrors) }
    }

    private List<DesiredMirror> desiredMirrors(ParentSourceRevision revision) {
        if (revision.deleted || revision.approved != true) {
            return []
        }
        DesiredMirrorFactory factory = new DesiredMirrorFactory(childContexts, categoriesById, mappingCache)
        revision.components.findAll { !it.deleted }.collectMany { component ->
            factory.forSource(component.source, component.categoryId, component.categoryName,
                revision.date, component.amount, component.payeeId, component.payeeName,
                component.memo ?: revision.memo)
        }
    }

    static List<PlannedReconciliationIntent> compare(List<DesiredMirror> desiredMirrors,
                                                      List<ActiveMirrorReference> activeMirrors) {
        List<DesiredMirror> desired = (desiredMirrors ?: []).sort { mirrorKey(it) }
        List<ActiveMirrorReference> existing = (activeMirrors ?: []).sort { existingKey(it) }
        List<PlannedReconciliationIntent> deletes = []
        List<PlannedReconciliationIntent> updatesAndNoOps = []
        List<DesiredMirror> unmatchedDesired = new ArrayList<>(desired)
        List<ActiveMirrorReference> unmatchedExisting = new ArrayList<>(existing)

        existing.each { current ->
            DesiredMirror match = unmatchedDesired.find { wanted -> exactMirror(current, wanted) }
            if (match) {
                unmatchedDesired.remove(match)
                unmatchedExisting.remove(current)
                updatesAndNoOps << matchedIntent(current, match)
            }
        }

        List<List<Object>> reroutes = []
        unmatchedExisting.toList().each { current ->
            DesiredMirror replacement = unmatchedDesired.find { wanted -> sameLogicalSource(current, wanted) }
            if (replacement) {
                reroutes << [current, replacement]
                unmatchedExisting.remove(current)
                unmatchedDesired.remove(replacement)
            }
        }

        unmatchedExisting.each { current -> deletes << deleteIntent(current) }
        reroutes.each { pair -> deletes << deleteIntent(pair[0] as ActiveMirrorReference) }
        deletes = deletes.sort { it.operationKey }
        List<String> deleteKeys = deletes*.operationKey

        List<PlannedReconciliationIntent> creates = []
        reroutes.each { pair -> creates << createIntent(pair[1] as DesiredMirror, deleteKeys) }
        unmatchedDesired.each { wanted -> creates << createIntent(wanted, deleteKeys) }
        creates = creates.sort { it.operationKey }
        updatesAndNoOps = updatesAndNoOps.sort { it.operationKey }

        List<PlannedReconciliationIntent> ordered = deletes + updatesAndNoOps + creates
        ordered.withIndex().collect { PlannedReconciliationIntent intent, int index ->
            new PlannedReconciliationIntent(intent.operationKey, index + 1, intent.action, intent.source,
                intent.targetChildKey, intent.targetBudgetId, intent.direction, intent.childMirrorId,
                intent.childTransactionId, intent.payloadJson, intent.payloadHash,
                intent.dependsOnOperationKeys, intent.requiresExistenceCheck,
                intent.targetAccountId, intent.priorAmount)
        }
    }

    private static PlannedReconciliationIntent matchedIntent(ActiveMirrorReference current, DesiredMirror wanted) {
        boolean changed = current.mirror.targetAccountId != wanted.targetAccountId ||
            current.mirror.authoritativePayloadHash != wanted.authoritativePayloadHash ||
            childDiffers(current.observedChild, wanted)
        PlannedAction action = changed ? PlannedAction.UPDATE : PlannedAction.NO_OP
        intent(action, wanted.source, wanted.targetChildKey, wanted.targetBudgetId, wanted.direction,
            current.mirror.id, current.mirror.childTransactionId,
            mutationPayload(wanted), wanted.authoritativePayloadHash, [], true,
            wanted.targetAccountId, current.observedChild?.amount)
    }

    private static boolean childDiffers(ChildTransaction child, DesiredMirror wanted) {
        if (!child) {
            return false
        }
        child.deleted == true || child.accountId != wanted.targetAccountId || child.date != wanted.date ||
            child.amount != wanted.amount || child.payeeId != wanted.payeeId ||
            child.payeeName != wanted.payeeName || child.cleared != 'cleared' || child.approved != false
    }

    private static PlannedReconciliationIntent deleteIntent(ActiveMirrorReference current) {
        intent(PlannedAction.DELETE, current.source, current.targetChildKey,
            current.mirror.targetBudgetId, current.direction, current.mirror.id,
            current.mirror.childTransactionId, null, null, [], false,
            current.mirror.targetAccountId, current.observedChild?.amount)
    }

    private static PlannedReconciliationIntent createIntent(DesiredMirror wanted, List<String> dependencies) {
        String json = mutationPayload(wanted)
        intent(PlannedAction.CREATE, wanted.source, wanted.targetChildKey, wanted.targetBudgetId,
            wanted.direction, null, null, json, wanted.authoritativePayloadHash, dependencies, false,
            wanted.targetAccountId, null)
    }

    private static String mutationPayload(DesiredMirror wanted) {
        Map payload = new groovy.json.JsonSlurper().parseText(wanted.authoritativePayloadJson) as Map
        payload.category_id = null
        payload.memo = wanted.memo
        payload[DesiredMirrorFactory.LOGICAL_DIRECTION_FIELD] = wanted.direction ?:
            ((wanted.amount ?: 0) >= 0 ? 'inflow' : 'outflow')
        ReconciliationCanonicalizer.json(payload)
    }

    private static PlannedReconciliationIntent intent(PlannedAction action, SourceEntityKey source,
                                                       String targetChildKey, String targetBudgetId,
                                                       String direction, Long mirrorId, String childId,
                                                       String payloadJson, String payloadHash,
                                                       List<String> dependencies, boolean verify,
                                                       String targetAccountId, Integer priorAmount) {
        String key = ReconciliationCanonicalizer.stableKey([
            action.name(), source.sourceBudgetId, source.type.databaseValue,
            source.parentTransactionId, source.parentSubtransactionId, source.moneyMovementId,
            targetBudgetId, direction, childId, payloadHash
        ])
        new PlannedReconciliationIntent(key, 0, action, source, targetChildKey, targetBudgetId,
            direction, mirrorId, childId, payloadJson, payloadHash, dependencies, verify,
            targetAccountId, priorAmount)
    }

    private static boolean exactMirror(ActiveMirrorReference current, DesiredMirror wanted) {
        sameLogicalSource(current, wanted) && current.mirror.targetBudgetId == wanted.targetBudgetId
    }

    private static boolean sameLogicalSource(ActiveMirrorReference current, DesiredMirror wanted) {
        current.source == wanted.source && (current.direction ?: '') == (wanted.direction ?: '')
    }

    private static String mirrorKey(DesiredMirror mirror) {
        [sourceKey(mirror.source), mirror.direction ?: '', mirror.targetBudgetId].join('|')
    }

    private static String existingKey(ActiveMirrorReference mirror) {
        [sourceKey(mirror.source), mirror.direction ?: '', mirror.mirror.targetBudgetId,
         mirror.mirror.id].join('|')
    }

    private static String sourceKey(SourceEntityKey source) {
        [source.sourceBudgetId, source.type.databaseValue, source.parentTransactionId ?: '',
         source.parentSubtransactionId ?: '', source.moneyMovementId ?: ''].join('|')
    }
}

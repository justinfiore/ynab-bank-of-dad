package ynabbankofdad.sync.reconcile

import groovy.util.logging.Slf4j
import ynabbankofdad.model.AccountSnapshot
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.model.ChildSyncContext
import ynabbankofdad.sync.model.MoneyMovementEvent
import ynabbankofdad.sync.state.SourceEntityKey
import ynabbankofdad.sync.state.SourceEntityType

import java.time.LocalDate

class MoneyMovementNormalizer {
    static SourceEntityKey movementIdentity(String sourceBudgetId, String movementId) {
        if (!sourceBudgetId || !movementId) {
            throw new IllegalArgumentException('Stable money movement identity requires source budget and movement ID')
        }
        new SourceEntityKey(sourceBudgetId, SourceEntityType.MONEY_MOVEMENT, null, null, movementId)
    }

    MovementSnapshotObservation normalizeSnapshot(String sourceBudgetId, List<MoneyMovementEvent> movements,
                                                  Map<String, CategorySnapshot> categoriesById = [:],
                                                  Collection<SourceEntityKey> previouslyObserved = [],
                                                  Integer serverKnowledge = null) {
        List<NormalizedMovementObservation> observations = (movements ?: []).collect { event ->
            SourceEntityKey source = movementIdentity(sourceBudgetId, event.id)
            Map normalized = [source: SourceRevisionNormalizer.sourceMap(source), groupId: event.groupId,
                eventDate: event.eventDate, fromCategoryId: event.fromCategoryId,
                fromCategoryName: categoriesById[event.fromCategoryId]?.name,
                toCategoryId: event.toCategoryId, toCategoryName: categoriesById[event.toCategoryId]?.name,
                amount: event.amount]
            String json = ReconciliationCanonicalizer.json(normalized)
            new NormalizedMovementObservation(source, event.groupId, event.eventDate,
                event.fromCategoryId, normalized.fromCategoryName as String,
                event.toCategoryId, normalized.toCategoryName as String, event.amount,
                serverKnowledge, json, ReconciliationCanonicalizer.hashJson(json))
        }.sort { it.source.moneyMovementId }
        Set<SourceEntityKey> observedKeys = observations*.source as Set
        Set<SourceEntityKey> unconfirmed = (previouslyObserved ?: []).findAll {
            it.sourceBudgetId == sourceBudgetId && !observedKeys.contains(it)
        } as Set
        new MovementSnapshotObservation(observations, unconfirmed, serverKnowledge)
    }
}

@Slf4j
class MoneyMovementReconciler {
    private final List<ChildSyncContext> childContexts
    private final Map<String, CategorySnapshot> categoriesById
    private final ParentCategoryAccountCache mappingCache

    MoneyMovementReconciler(List<ChildSyncContext> childContexts,
                            Map<String, CategorySnapshot> categoriesById = [:],
                            ParentCategoryAccountCache mappingCache = null) {
        this.childContexts = childContexts ?: []
        this.categoriesById = categoriesById ?: [:]
        this.mappingCache = mappingCache
    }

    List<MovementDecision> reconcile(MovementSnapshotObservation snapshot,
                                     List<ActiveMirrorReference> activeMirrors = [],
                                     LocalDate newMovementCutoff = null) {
        List<MovementDecision> decisions = snapshot.observations.collect { observation ->
            List<ActiveMirrorReference> existing = (activeMirrors ?: []).findAll {
                it.source == observation.source && it.mirror.status == 'active'
            }
            boolean newEligible = !newMovementCutoff ||
                !LocalDate.parse(observation.eventDate).isBefore(newMovementCutoff)
            List<DesiredMirror> desired = (newEligible || !existing.isEmpty()) ?
                desired(observation, existing) : []
            List<PlannedReconciliationIntent> intents = existing.isEmpty() && !newEligible ? [] :
                ParentTransactionReconciler.compare(desired, existing)
            String reason = newEligible || !existing.isEmpty() ? null : 'outside new-movement lookback'
            new MovementDecision(observation.source, MovementObservationStatus.OBSERVED,
                desired, intents, reason, false)
        }
        snapshot.unconfirmedSources.sort { it.moneyMovementId }.each { source ->
            decisions << new MovementDecision(source, MovementObservationStatus.UNCONFIRMED,
                [], [], 'absent from complete snapshot; absence is not deletion evidence', false)
        }
        decisions
    }

    private List<DesiredMirror> desired(NormalizedMovementObservation movement,
                                        List<ActiveMirrorReference> existing) {
        DesiredMirrorFactory factory = new DesiredMirrorFactory(childContexts, categoriesById, mappingCache)
        List<DesiredMirror> dual = dualMirrors(factory, movement)
        // New same-child pairs (and already-posted single outflow transfers) collapse to one transfer.
        // Pre-existing dual inflow+outflow mirrors are grandfathered so we do not delete/recreate them
        // (including rows the operator manually converted to transfers).
        if (existing && !maintainsPostedTransfer(existing, dual)) {
            return grandfatherDualMirrors(dual)
        }
        collapseSameChildTransfers(factory, dual)
    }

    /**
     * A single active outflow mirror for a same-child different-account pair is the durable shape
     * after posting a YNAB transfer. Keep planning transfers so amount/date updates stay one-sided.
     */
    private static boolean maintainsPostedTransfer(List<ActiveMirrorReference> existing,
                                                   List<DesiredMirror> dual) {
        existing?.size() == 1 &&
            existing[0].direction == 'outflow' &&
            !sameChildDifferentAccountPairs(dual).isEmpty()
    }

    private static List<DesiredMirror> dualMirrors(DesiredMirrorFactory factory,
                                                   NormalizedMovementObservation movement) {
        String fromName = movement.fromCategoryName
        String toName = movement.toCategoryName
        String memo = "From ${fromName ?: 'Unknown'} to ${toName ?: 'Unknown'}"
        List<DesiredMirror> mirrors = []
        mirrors.addAll(factory.forSource(movement.source, movement.toCategoryId, toName,
            movement.eventDate, movement.amount, null, "From ${fromName ?: 'Unknown'}", memo, 'inflow'))
        mirrors.addAll(factory.forSource(movement.source, movement.fromCategoryId, fromName,
            movement.eventDate, -movement.amount, null, "To ${toName ?: 'Unknown'}", memo, 'outflow'))
        mirrors.sort { DesiredMirror mirror -> "${mirror.direction}|${mirror.targetBudgetId}" }
    }

    /**
     * When both sides of a new movement map to different accounts in the same child budget, post
     * one YNAB transfer (outflow account → destination transfer payee) instead of two normals.
     * Cross-child and one-sided mappings stay as dual/normal mirrors.
     */
    private List<DesiredMirror> collapseSameChildTransfers(DesiredMirrorFactory factory,
                                                           List<DesiredMirror> dual) {
        List<List<DesiredMirror>> pairs = sameChildDifferentAccountPairs(dual)
        if (pairs.isEmpty()) {
            return dual
        }
        Set<DesiredMirror> collapsed = [] as Set
        List<DesiredMirror> result = []
        pairs.each { List<DesiredMirror> pair ->
            DesiredMirror outflow = pair[0]
            DesiredMirror inflow = pair[1]
            DesiredMirror transfer = buildTransfer(factory, outflow, inflow)
            if (transfer == null) {
                return
            }
            result << transfer
            collapsed << outflow
            collapsed << inflow
        }
        dual.each { DesiredMirror mirror ->
            if (!collapsed.contains(mirror)) {
                result << mirror
            }
        }
        result.sort { DesiredMirror mirror ->
            "${mirror.direction}|${mirror.targetBudgetId}|${mirror.targetAccountId}"
        }
    }

    /**
     * Keep dual mirror identity for grandfathered movements, but omit payee from authoritative
     * state for same-child different-account pairs so operator-converted transfers are not
     * rewritten back to normal payee names on amount/date updates.
     */
    private static List<DesiredMirror> grandfatherDualMirrors(List<DesiredMirror> dual) {
        Set<DesiredMirror> transferEligible = sameChildDifferentAccountPairs(dual)
            .collectMany { it } as Set
        dual.collect { DesiredMirror mirror ->
            transferEligible.contains(mirror) ? withoutPayee(mirror) : mirror
        }.sort { DesiredMirror mirror ->
            "${mirror.direction}|${mirror.targetBudgetId}|${mirror.targetAccountId}"
        }
    }

    private static List<List<DesiredMirror>> sameChildDifferentAccountPairs(List<DesiredMirror> dual) {
        List<DesiredMirror> inflows = dual.findAll { it.direction == 'inflow' }
        List<DesiredMirror> outflows = dual.findAll { it.direction == 'outflow' }
        Set<DesiredMirror> used = [] as Set
        List<List<DesiredMirror>> pairs = []
        outflows.each { DesiredMirror outflow ->
            DesiredMirror inflow = inflows.find { DesiredMirror candidate ->
                !used.contains(candidate) &&
                    candidate.targetBudgetId == outflow.targetBudgetId &&
                    candidate.targetAccountId && outflow.targetAccountId &&
                    candidate.targetAccountId != outflow.targetAccountId
            }
            if (inflow) {
                used << outflow
                used << inflow
                pairs << [outflow, inflow]
            }
        }
        pairs
    }

    private static DesiredMirror withoutPayee(DesiredMirror mirror) {
        Map payload = new groovy.json.JsonSlurper().parseText(mirror.authoritativePayloadJson) as Map
        payload.remove('payee_id')
        payload.remove('payee_name')
        String payloadJson = ReconciliationCanonicalizer.json(payload)
        new DesiredMirror(
            mirror.source, mirror.targetChildKey, mirror.targetBudgetId, mirror.direction,
            mirror.targetAccountId, mirror.targetAccountName, mirror.date, mirror.amount,
            null, null, mirror.memo, mirror.mappingKey, payloadJson,
            ReconciliationCanonicalizer.hashJson(payloadJson), mirror.importIdNamespace)
    }

    private DesiredMirror buildTransfer(DesiredMirrorFactory factory, DesiredMirror outflow,
                                        DesiredMirror inflow) {
        ChildSyncContext child = factory.childContextForBudget(outflow.targetBudgetId)
        if (child == null) {
            return null
        }
        AccountSnapshot destination = factory.accountSnapshot(child, inflow.targetAccountId)
        String transferPayeeId = destination?.transferPayeeId
        if (!transferPayeeId) {
            log.warn(
                'Money movement {} cannot be posted as a child transfer from {} to {} in budget {}: missing transfer_payee_id on destination account; using dual normal transactions',
                outflow.source.moneyMovementId, outflow.targetAccountName, inflow.targetAccountName,
                outflow.targetBudgetId)
            return null
        }
        // dual mirrors already decorate memo; rebuild bare "From X to Y" from dual payee labels.
        String toName = outflow.payeeName?.startsWith('To ') ? outflow.payeeName.substring(3) : 'Unknown'
        String fromName = inflow.payeeName?.startsWith('From ') ? inflow.payeeName.substring(5) : 'Unknown'
        factory.forAccountTransfer(
            outflow.source, child,
            outflow.targetAccountId, outflow.targetAccountName,
            inflow.targetAccountId, inflow.targetAccountName,
            transferPayeeId, outflow.date, outflow.amount,
            "From ${fromName} to ${toName}",
            outflow.mappingKey ?: inflow.mappingKey)
    }
}

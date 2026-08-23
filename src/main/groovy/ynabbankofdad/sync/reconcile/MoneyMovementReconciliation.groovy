package ynabbankofdad.sync.reconcile

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

class MoneyMovementReconciler {
    private final List<ChildSyncContext> childContexts
    private final Map<String, CategorySnapshot> categoriesById

    MoneyMovementReconciler(List<ChildSyncContext> childContexts,
                            Map<String, CategorySnapshot> categoriesById = [:]) {
        this.childContexts = childContexts ?: []
        this.categoriesById = categoriesById ?: [:]
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
            List<DesiredMirror> desired = (newEligible || !existing.isEmpty()) ? desired(observation) : []
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

    private List<DesiredMirror> desired(NormalizedMovementObservation movement) {
        DesiredMirrorFactory factory = new DesiredMirrorFactory(childContexts, categoriesById)
        String fromName = movement.fromCategoryName ?: categoriesById[movement.fromCategoryId]?.name
        String toName = movement.toCategoryName ?: categoriesById[movement.toCategoryId]?.name
        String memo = "From ${fromName ?: 'Unknown'} to ${toName ?: 'Unknown'}"
        List<DesiredMirror> mirrors = []
        mirrors.addAll(factory.forSource(movement.source, movement.toCategoryId, toName,
            movement.eventDate, movement.amount, null, "From ${fromName ?: 'Unknown'}", memo, 'inflow'))
        mirrors.addAll(factory.forSource(movement.source, movement.fromCategoryId, fromName,
            movement.eventDate, -movement.amount, null, "To ${toName ?: 'Unknown'}", memo, 'outflow'))
        mirrors.sort { DesiredMirror mirror -> "${mirror.direction}|${mirror.targetBudgetId}" }
    }
}

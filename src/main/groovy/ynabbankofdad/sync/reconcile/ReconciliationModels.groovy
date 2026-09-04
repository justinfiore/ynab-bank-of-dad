package ynabbankofdad.sync.reconcile

import groovy.json.JsonOutput
import groovy.transform.Immutable
import ynabbankofdad.sync.model.ChildTransaction
import ynabbankofdad.sync.state.ChildMirrorState
import ynabbankofdad.sync.state.SourceEntityKey

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum CompositionSafety {
    COMPLETE, FETCH_REQUIRED
}

enum PlannedAction {
    CREATE, UPDATE, DELETE, NO_OP
}

enum MovementObservationStatus {
    OBSERVED, UNCONFIRMED
}

@Immutable
class NormalizedSourceComponent {
    SourceEntityKey source
    String categoryId
    String categoryName
    Integer amount
    String memo
    Boolean deleted
    String payeeId
    String payeeName
}

@Immutable
class ParentSourceRevision {
    SourceEntityKey parentSource
    String date
    Integer amount
    String memo
    Boolean approved
    Boolean deleted
    String categoryId
    String categoryName
    String payeeId
    String payeeName
    Integer serverKnowledge
    List<NormalizedSourceComponent> components
    CompositionSafety compositionSafety
    String normalizedJson
    String revisionHash

    boolean requiresCompleteFetch() {
        compositionSafety == CompositionSafety.FETCH_REQUIRED
    }
}

@Immutable
class DesiredMirror {
    SourceEntityKey source
    String targetChildKey
    String targetBudgetId
    String direction
    String targetAccountId
    String targetAccountName
    String date
    Integer amount
    String payeeId
    String payeeName
    String memo
    String mappingKey
    String authoritativePayloadJson
    String authoritativePayloadHash
}

@Immutable
class ActiveMirrorReference {
    SourceEntityKey source
    ChildMirrorState mirror
    String targetChildKey
    String direction
    ChildTransaction observedChild
}

@Immutable
class PlannedReconciliationIntent {
    String operationKey
    int sequence
    PlannedAction action
    SourceEntityKey source
    String targetChildKey
    String targetBudgetId
    String direction
    Long childMirrorId
    String childTransactionId
    String payloadJson
    String payloadHash
    List<String> dependsOnOperationKeys
    boolean requiresExistenceCheck
    String targetAccountId
    Integer priorAmount
}

@Immutable
class ParentReconciliationResult {
    ParentSourceRevision revision
    List<DesiredMirror> desiredMirrors
    List<PlannedReconciliationIntent> intents
    boolean fetchRequired
    /**
     * True when routing blocks the complete parent revision. Retained for callers that cannot
     * identify a narrower source component.
     */
    boolean routingBlocked = false
    /**
     * Split component sources whose child routing failed this cycle. Empty desired mirrors for
     * these sources must not be treated as unmapped/deleted for lifecycle.
     */
    Set<SourceEntityKey> routingBlockedSources = [] as Set
}

@Immutable
class NormalizedMovementObservation {
    SourceEntityKey source
    String groupId
    String eventDate
    String fromCategoryId
    String fromCategoryName
    String toCategoryId
    String toCategoryName
    Integer amount
    Integer serverKnowledge
    String normalizedJson
    String revisionHash
}

@Immutable
class MovementSnapshotObservation {
    List<NormalizedMovementObservation> observations
    Set<SourceEntityKey> unconfirmedSources
    Integer serverKnowledge
}

@Immutable
class MovementDecision {
    SourceEntityKey source
    MovementObservationStatus status
    List<DesiredMirror> desiredMirrors
    List<PlannedReconciliationIntent> intents
    String reason
    boolean routingBlocked = false
}

final class ReconciliationCanonicalizer {
    private ReconciliationCanonicalizer() {}

    static String json(Object value) {
        JsonOutput.toJson(sort(value))
    }

    static String hash(Object value) {
        hashJson(json(value))
    }

    static String hashJson(String json) {
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(json.getBytes(StandardCharsets.UTF_8))
        digest.encodeHex().toString()
    }

    static String stableKey(List<?> parts) {
        hash(parts.collect { it == null ? '' : it.toString() })
    }

    private static Object sort(Object value) {
        if (value instanceof Map) {
            Map sorted = new TreeMap<String, Object>()
            value.each { key, item -> sorted[key.toString()] = sort(item) }
            return sorted
        }
        if (value instanceof Collection) {
            return value.collect { sort(it) }
        }
        value
    }
}

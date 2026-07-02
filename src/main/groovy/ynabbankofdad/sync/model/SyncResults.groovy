package ynabbankofdad.sync.model

import groovy.transform.Immutable

@Immutable
class ChildApplyResult {
    String childKey
    Integer appliedCount = 0
    Integer skippedCount = 0
    Integer failedCount = 0
    List<String> failureMessages = []

    boolean hasFailures() {
        failedCount > 0
    }
}

@Immutable
class SyncRunResult {
    Integer appliedCount = 0
    Integer skippedCount = 0
    Integer failedCount = 0
    List<String> failureMessages = []

    boolean hasFailures() {
        failedCount > 0
    }

    String status() {
        hasFailures() ? 'partial' : 'succeeded'
    }

    String errorSummary() {
        failureMessages ? failureMessages.join('; ') : null
    }

    static SyncRunResult empty() {
        new SyncRunResult(0, 0, 0, [])
    }

    static SyncRunResult fromChildResults(List<ChildApplyResult> childResults) {
        new SyncRunResult(
            childResults.sum { it.appliedCount } as Integer ?: 0,
            childResults.sum { it.skippedCount } as Integer ?: 0,
            childResults.sum { it.failedCount } as Integer ?: 0,
            childResults.collectMany { it.failureMessages ?: [] }
        )
    }
}

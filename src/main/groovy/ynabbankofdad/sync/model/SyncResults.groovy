package ynabbankofdad.sync.model

import groovy.transform.Immutable

@Immutable
class SyncRunResult {
    List<String> failureMessages = []

    boolean hasFailures() {
        !failureMessages.empty
    }

    String status() {
        hasFailures() ? 'partial' : 'succeeded'
    }

    String errorSummary() {
        failureMessages ? failureMessages.join('; ') : null
    }

    static SyncRunResult empty() {
        new SyncRunResult([])
    }
}

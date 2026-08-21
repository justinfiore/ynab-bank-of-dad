package ynabbankofdad.qa

import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.Path

class QaSnapshotProvisioningDiscovery implements QaProvisioningDiscovery {
    private final Map parentBudget
    private final List<Map> childBudgets

    QaSnapshotProvisioningDiscovery(Path snapshotPath) {
        if (snapshotPath == null || !Files.isRegularFile(snapshotPath)) {
            throw new IllegalArgumentException("QA discovery snapshot not found: ${snapshotPath}")
        }
        def raw = new JsonSlurper().parse(snapshotPath.toFile())
        if (!(raw instanceof Map) || !(raw.parentBudget instanceof Map) || !(raw.childBudgets instanceof List)) {
            throw new IllegalArgumentException(
                'QA discovery snapshot must define parentBudget and childBudgets'
            )
        }
        requireOnlyKeys(raw as Map, ['parentBudget', 'childBudgets'] as Set, 'top level')
        requireOnlyKeys(raw.parentBudget as Map,
            ['displayName', 'fullId', 'categoryNames'] as Set, 'parentBudget')
        (raw.childBudgets as List).eachWithIndex { def child, int index ->
            if (!(child instanceof Map)) {
                throw new IllegalArgumentException(
                    "QA discovery snapshot 'childBudgets[${index}]' must be an object"
                )
            }
            requireOnlyKeys(child as Map,
                ['displayName', 'fullId', 'accountNames'] as Set, "childBudgets[${index}]"
            )
        }
        parentBudget = raw.parentBudget as Map
        childBudgets = raw.childBudgets as List<Map>
    }

    @Override
    Set<String> getParentCategoryNames(QaBudgetIdentity requestedBudget) {
        QaBudgetIdentity discovered = identity(parentBudget, 'parentBudget')
        QaBudgetSafetyGuard.requireAllowed(discovered, [requestedBudget])
        names(parentBudget, 'categoryNames', 'parentBudget')
    }

    @Override
    Set<String> getChildAccountNames(QaBudgetIdentity requestedBudget) {
        Map discoveredBudget = childBudgets.find { Map candidate ->
            candidate.displayName == requestedBudget.displayName || candidate.fullId == requestedBudget.fullId
        }
        if (discoveredBudget == null) {
            throw new IllegalArgumentException(
                "QA discovery snapshot has no child budget matching '${requestedBudget.displayName}'"
            )
        }
        QaBudgetIdentity discovered = identity(discoveredBudget, 'childBudgets entry')
        QaBudgetSafetyGuard.requireAllowed(discovered, [requestedBudget])
        names(discoveredBudget, 'accountNames', 'childBudgets entry')
    }

    private static QaBudgetIdentity identity(Map raw, String path) {
        if (!(raw.displayName instanceof String) || !(raw.fullId instanceof String)) {
            throw new IllegalArgumentException("QA discovery snapshot '${path}' needs displayName and fullId")
        }
        new QaBudgetIdentity(raw.displayName as String, raw.fullId as String)
    }

    private static Set<String> names(Map raw, String key, String path) {
        if (!(raw[key] instanceof List) || (raw[key] as List).any { !(it instanceof String) }) {
            throw new IllegalArgumentException("QA discovery snapshot '${path}.${key}' must be a string list")
        }
        new LinkedHashSet<>((raw[key] as List).collect { it as String })
    }

    private static void requireOnlyKeys(Map raw, Set<String> allowedKeys, String path) {
        Set<String> suppliedKeys = raw.keySet().collect { it as String } as Set
        Set<String> unexpectedKeys = suppliedKeys - allowedKeys
        if (!unexpectedKeys.isEmpty()) {
            throw new IllegalArgumentException(
                "QA discovery snapshot '${path}' contains unsupported fields: ${unexpectedKeys.sort().join(', ')}"
            )
        }
    }
}

package ynabbankofdad.qa

import org.yaml.snakeyaml.Yaml

import java.nio.file.Files
import java.nio.file.Path

class QaEvidenceConfig {
    final QaProvisioningRequirements provisioning

    private QaEvidenceConfig(QaProvisioningRequirements provisioning) {
        this.provisioning = provisioning
    }

    static QaEvidenceConfig load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("QA config file not found: ${path}")
        }
        def rawValue = new Yaml().load(Files.readString(path))
        if (!(rawValue instanceof Map)) {
            throw new IllegalArgumentException('QA config must contain a YAML object')
        }
        Map raw = rawValue as Map
        Map budgets = requireMap(raw, 'budgets')
        QaBudgetIdentity parent = budgetIdentity(requireMap(budgets, 'parent'), 'budgets.parent')
        List childrenValue = requireList(budgets, 'children')
        List<QaBudgetIdentity> children = childrenValue.withIndex().collect { def child, int index ->
            if (!(child instanceof Map)) {
                throw new IllegalArgumentException("QA config 'budgets.children[${index}]' must be a map")
            }
            budgetIdentity(child as Map, "budgets.children[${index}]")
        }

        Map provisioning = requireMap(raw, 'provisioning')
        Set<String> parentCategories = stringList(provisioning, 'parentCategories') as Set
        Set<String> requiredAccounts = stringList(provisioning, 'requiredChildAccounts') as Set
        Map<QaBudgetIdentity, Set<String>> childAccounts = children.collectEntries { QaBudgetIdentity child ->
            [(child): new LinkedHashSet<>(requiredAccounts)]
        }

        QaBudgetSafetyGuard.validateConfiguredAllowlist([parent] + children)
        new QaEvidenceConfig(new QaProvisioningRequirements(parent, children, parentCategories, childAccounts))
    }

    private static QaBudgetIdentity budgetIdentity(Map raw, String path) {
        new QaBudgetIdentity(requireString(raw, 'displayName', path), requireString(raw, 'fullId', path))
    }

    private static Map requireMap(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("QA config '${key}' must be a map")
        }
        value as Map
    }

    private static List requireList(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof List) || value.isEmpty()) {
            throw new IllegalArgumentException("QA config '${key}' must be a non-empty list")
        }
        value as List
    }

    private static List<String> stringList(Map raw, String key) {
        List value = requireList(raw, key)
        if (value.any { !(it instanceof String) || it.isEmpty() }) {
            throw new IllegalArgumentException("QA config '${key}' must contain non-empty strings")
        }
        value.collect { it as String }
    }

    private static String requireString(Map raw, String key, String path) {
        def value = raw[key]
        if (!(value instanceof String) || value.isEmpty()) {
            throw new IllegalArgumentException("QA config '${path}.${key}' must be a non-empty string")
        }
        value as String
    }
}

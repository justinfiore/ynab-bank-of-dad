package ynabbankofdad.qa

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class QaProvisioningInspector {
    private final QaProvisioningDiscovery discovery

    QaProvisioningInspector(QaProvisioningDiscovery discovery) {
        if (discovery == null) {
            throw new IllegalArgumentException('QA provisioning discovery is required')
        }
        this.discovery = discovery
    }

    Map<String, Object> inspect(QaProvisioningRequirements requirements, Path artifactPath) {
        if (requirements == null) {
            throw new IllegalArgumentException('QA provisioning requirements are required')
        }
        if (artifactPath == null) {
            throw new IllegalArgumentException('QA provisioning artifact path is required')
        }

        List<QaBudgetIdentity> allowlist = [requirements.parentBudget] + requirements.childBudgets
        QaBudgetSafetyGuard.validateConfiguredAllowlist(allowlist)

        Set<String> discoveredCategories = discovery.getParentCategoryNames(requirements.parentBudget) ?: [] as Set
        List<String> missingCategories = sortedMissing(requirements.parentCategoryNames, discoveredCategories)
        List<Map<String, Object>> children = requirements.childBudgets.collect { QaBudgetIdentity child ->
            Set<String> requiredAccounts = requirements.childAccountNames[child] ?: [] as Set
            Set<String> discoveredAccounts = discovery.getChildAccountNames(child) ?: [] as Set
            [
                budget: [displayName: child.displayName, fullId: child.fullId],
                missingAccounts: sortedMissing(requiredAccounts, discoveredAccounts)
            ] as Map<String, Object>
        }

        Map<String, Object> evidence = [
            parentBudget: [
                displayName: requirements.parentBudget.displayName,
                fullId: requirements.parentBudget.fullId
            ],
            missingParentCategories: missingCategories,
            children: children,
            complete: missingCategories.isEmpty() && children.every { (it.missingAccounts as List).isEmpty() }
        ]

        if (artifactPath.parent != null) {
            Files.createDirectories(artifactPath.parent)
        }
        Files.writeString(
            artifactPath,
            JsonOutput.prettyPrint(JsonOutput.toJson(evidence)) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        evidence
    }

    private static List<String> sortedMissing(Collection<String> required, Collection<String> discovered) {
        ((required ?: []) as Set).findAll { !((discovered ?: []) as Set).contains(it) }.sort()
    }
}

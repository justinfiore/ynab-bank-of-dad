package ynabbankofdad.qa

/**
 * Minimal read-only discovery boundary. Implementations can only report names;
 * provisioning mutations are intentionally absent from this contract.
 */
interface QaProvisioningDiscovery {
    Set<String> getParentCategoryNames(QaBudgetIdentity parentBudget)

    Set<String> getChildAccountNames(QaBudgetIdentity childBudget)
}

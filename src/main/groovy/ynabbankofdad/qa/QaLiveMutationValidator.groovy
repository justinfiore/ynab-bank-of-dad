package ynabbankofdad.qa

class QaLiveMutationValidator {
    static final String CONFIRMATION_VARIABLE = 'QA_CONFIRM_LIVE_MUTATIONS'
    private static final Set<String> SUPPORTED_OPERATIONS = ['create', 'update', 'delete'] as Set

    static List<QaExpectedMutation> validate(Map<String, String> environment,
                                             Collection<QaExpectedMutation> expectedManifest,
                                             Collection<QaBudgetIdentity> configuredAllowlist) {
        if (environment?.get(CONFIRMATION_VARIABLE) != 'YES') {
            throw new IllegalArgumentException("${CONFIRMATION_VARIABLE} must equal YES")
        }
        if (expectedManifest == null || expectedManifest.isEmpty()) {
            throw new IllegalArgumentException('Expected mutation manifest is required and must not be empty')
        }

        List<QaBudgetIdentity> allowlist = QaBudgetSafetyGuard.validateConfiguredAllowlist(configuredAllowlist)
        List<QaExpectedMutation> validated = new ArrayList<>(expectedManifest)
        validated.eachWithIndex { QaExpectedMutation mutation, int index ->
            if (mutation == null || !SUPPORTED_OPERATIONS.contains(mutation.operation)) {
                throw new IllegalArgumentException(
                    "Expected mutation manifest entry ${index} must use create, update, or delete"
                )
            }
            if (mutation.targetResourceId == null || mutation.targetResourceId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "Expected mutation manifest entry ${index} must name its target resource"
                )
            }
            QaBudgetSafetyGuard.requireAllowed(mutation.budget, allowlist)
        }
        Collections.unmodifiableList(validated)
    }

    private QaLiveMutationValidator() {
    }
}

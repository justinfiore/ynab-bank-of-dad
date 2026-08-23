package ynabbankofdad.qa

class QaBudgetSafetyGuard {
    static final Set<String> KNOWN_QA_DISPLAY_NAMES = [
        "Jorsten's Plan",
        "Jorsten Jr's Plan",
        "Borsten's Plan",
        "Thorsten's Plan"
    ] as Set

    private static final String FULL_UUID_PATTERN =
        '(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

    static List<QaBudgetIdentity> validateConfiguredAllowlist(Collection<QaBudgetIdentity> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            throw new IllegalArgumentException('QA budget allowlist is required')
        }

        List<QaBudgetIdentity> configured = new ArrayList<>(allowlist)
        configured.each { QaBudgetIdentity identity ->
            validateIdentity(identity, 'Configured QA budget')
            if (!KNOWN_QA_DISPLAY_NAMES.contains(identity.displayName)) {
                throw new IllegalArgumentException(
                    "Configured QA budget display name '${identity.displayName}' is not a known QA display name"
                )
            }
        }

        def duplicateNames = configured.groupBy { it.displayName }.findAll { ignored, matches -> matches.size() > 1 }
        if (duplicateNames) {
            throw new IllegalArgumentException("QA budget display names must be unique: ${duplicateNames.keySet().sort()}")
        }
        def duplicateIds = configured.groupBy { it.fullId }.findAll { ignored, matches -> matches.size() > 1 }
        if (duplicateIds) {
            throw new IllegalArgumentException('QA budget full immutable IDs must be unique')
        }

        Collections.unmodifiableList(configured)
    }

    static QaBudgetIdentity requireAllowed(QaBudgetIdentity actual,
                                           Collection<QaBudgetIdentity> configuredAllowlist) {
        List<QaBudgetIdentity> allowlist = validateConfiguredAllowlist(configuredAllowlist)
        validateIdentity(actual, 'Discovered QA budget')

        QaBudgetIdentity match = allowlist.find { QaBudgetIdentity configured ->
            configured.displayName == actual.displayName && configured.fullId == actual.fullId
        }
        if (match == null) {
            throw new IllegalArgumentException(
                "Budget '${actual.displayName}' with the supplied full ID is not an allowed QA budget"
            )
        }
        match
    }

    private static void validateIdentity(QaBudgetIdentity identity, String label) {
        if (identity == null || identity.displayName == null || identity.displayName.isEmpty()) {
            throw new IllegalArgumentException("${label} must have an exact non-empty display name")
        }
        if (identity.displayName != identity.displayName.trim()) {
            throw new IllegalArgumentException("${label} display name must match exactly without normalization")
        }
        if (identity.fullId == null || !(identity.fullId ==~ FULL_UUID_PATTERN)) {
            throw new IllegalArgumentException("${label} must have a full immutable UUID; suffixes are unsafe")
        }
    }

    private QaBudgetSafetyGuard() {
    }
}

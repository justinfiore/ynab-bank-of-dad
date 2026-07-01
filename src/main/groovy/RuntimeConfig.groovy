import org.yaml.snakeyaml.Yaml

class RuntimeConfig {
    String budgetName
    String allowanceEscrowAccountName
    String allowanceCategoryName
    String interestMemo
    String allowanceMemo
    String combinedMemo
    String nonInterestMemoSuffix
    List<String> bankSuffixes
    Map<String, Number> allowanceRates
    Number giveBankRate
    List<String> kidsWithoutInterest
    List<String> kidsWithSimpleAccounts
    List<String> kidsWithAdvancedAccounts
    Map<String, Map<String, Number>> advancedAllowanceDeposits
    List<String> accountTypes
    Map<String, Map<String, Number>> interestRatesByAccountTypeAndDate

    static RuntimeConfig load(String path) {
        File file = new File(path)
        if (!file.exists()) {
            throw new IllegalArgumentException("Config file not found: ${file.path}")
        }

        def yaml = new Yaml()
        def raw = yaml.load(file.text)
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("Config file must contain a YAML object at the top level: ${file.path}")
        }

        return fromMap(raw as Map)
    }

    static RuntimeConfig fromMap(Map raw) {
        RuntimeConfig config = new RuntimeConfig(
            budgetName: requireString(raw, 'budgetName'),
            allowanceEscrowAccountName: requireString(raw, 'allowanceEscrowAccountName'),
            allowanceCategoryName: requireString(raw, 'allowanceCategoryName'),
            interestMemo: requireString(raw, 'interestMemo'),
            allowanceMemo: requireString(raw, 'allowanceMemo'),
            combinedMemo: requireString(raw, 'combinedMemo'),
            nonInterestMemoSuffix: requireString(raw, 'nonInterestMemoSuffix'),
            bankSuffixes: requireStringList(raw, 'bankSuffixes'),
            allowanceRates: requireNumberMap(raw, 'allowanceRates'),
            giveBankRate: requireNumber(raw, 'giveBankRate'),
            kidsWithoutInterest: requireStringList(raw, 'kidsWithoutInterest'),
            kidsWithSimpleAccounts: requireStringList(raw, 'kidsWithSimpleAccounts'),
            kidsWithAdvancedAccounts: requireStringList(raw, 'kidsWithAdvancedAccounts'),
            advancedAllowanceDeposits: requireNestedNumberMap(raw, 'advancedAllowanceDeposits'),
            accountTypes: requireStringList(raw, 'accountTypes'),
            interestRatesByAccountTypeAndDate: requireNestedNumberMap(raw, 'interestRatesByAccountTypeAndDate')
        )
        config.validate()
        config
    }

    void validate() {
        kidsWithAdvancedAccounts.each { String kid ->
            if (!advancedAllowanceDeposits.containsKey(kid)) {
                throw new IllegalArgumentException("Config is missing advancedAllowanceDeposits entry for '${kid}'")
            }
        }
        allowanceRates.keySet().each { String suffix ->
            if (!bankSuffixes.contains(suffix)) {
                throw new IllegalArgumentException("allowanceRates key '${suffix}' must also appear in bankSuffixes")
            }
        }
        if (!interestRatesByAccountTypeAndDate.containsKey('Current')) {
            throw new IllegalArgumentException("interestRatesByAccountTypeAndDate must include a 'Current' rate table")
        }
    }

    private static String requireString(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof String) || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Config key '${key}' must be a non-empty string")
        }
        value
    }

    private static Number requireNumber(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Config key '${key}' must be numeric")
        }
        value as Number
    }

    private static List<String> requireStringList(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof List) || value.any { !(it instanceof String) || it.trim().isEmpty() }) {
            throw new IllegalArgumentException("Config key '${key}' must be a list of non-empty strings")
        }
        (value as List).collect { it as String }
    }

    private static Map<String, Number> requireNumberMap(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Config key '${key}' must be a map")
        }
        def result = [:]
        value.each { k, v ->
            if (!(k instanceof String) || !(v instanceof Number)) {
                throw new IllegalArgumentException("Config key '${key}' must map strings to numeric values")
            }
            result[k as String] = v as Number
        }
        result
    }

    private static Map<String, Map<String, Number>> requireNestedNumberMap(Map raw, String key) {
        def value = raw[key]
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Config key '${key}' must be a map")
        }
        def result = [:]
        value.each { outerKey, outerValue ->
            if (!(outerKey instanceof String) || !(outerValue instanceof Map)) {
                throw new IllegalArgumentException("Config key '${key}' must map strings to nested maps")
            }
            def nested = [:]
            (outerValue as Map).each { innerKey, innerValue ->
                if (!(innerKey instanceof String) || !(innerValue instanceof Number)) {
                    throw new IllegalArgumentException("Config key '${key}' must map strings to nested numeric maps")
                }
                nested[innerKey as String] = innerValue as Number
            }
            result[outerKey as String] = nested
        }
        result
    }
}

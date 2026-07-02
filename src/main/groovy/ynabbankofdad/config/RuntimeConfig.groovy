package ynabbankofdad.config

import groovy.transform.Immutable
import org.yaml.snakeyaml.Yaml
import groovy.transform.Immutable
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
    SyncConfig sync

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
            interestRatesByAccountTypeAndDate: requireNestedNumberMap(raw, 'interestRatesByAccountTypeAndDate'),
            sync: raw.containsKey('sync') ? requireSyncConfig(raw.sync, 'sync') : null
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
        if (!interestRatesByAccountTypeAndDate.keySet().any { it?.equalsIgnoreCase('Current') }) {
            throw new IllegalArgumentException("interestRatesByAccountTypeAndDate must include a 'Current' rate table")
        }
        sync?.validate()
    }

    private static SyncConfig requireSyncConfig(def value, String key) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Config key '${key}' must be a map")
        }
        Map raw = value as Map
        new SyncConfig(
            parentBudget: requireBudgetRef(raw, 'parentBudget', key),
            childBudgets: requireChildBudgets(raw, 'childBudgets', key),
            pollingIntervalSeconds: requirePositiveInteger(raw, 'pollingIntervalSeconds', key),
            logging: requireSyncLoggingConfig(raw, 'logging', key),
            state: requireSyncStateConfig(raw, 'state', key)
        )
    }

    private static BudgetRef requireBudgetRef(Map raw, String childKey, String parentKey) {
        def value = raw[childKey]
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Config key '${parentKey}.${childKey}' must be a map")
        }
        Map budgetMap = value as Map
        new BudgetRef(
            budgetName: requireString(budgetMap, 'budgetName', "${parentKey}.${childKey}"),
            tokenEnvVarName: requireString(budgetMap, 'tokenEnvVarName', "${parentKey}.${childKey}")
        )
    }

    private static List<ChildBudgetSyncTarget> requireChildBudgets(Map raw, String childKey, String parentKey) {
        def value = raw[childKey]
        if (!(value instanceof List) || value.isEmpty()) {
            throw new IllegalArgumentException("Config key '${parentKey}.${childKey}' must be a non-empty list")
        }
        (value as List).withIndex().collect { def entry, int index ->
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Config key '${parentKey}.${childKey}[${index}]' must be a map")
            }
            Map item = entry as Map
            new ChildBudgetSyncTarget(
                childKey: requireString(item, 'childKey', "${parentKey}.${childKey}[${index}]"),
                budgetName: requireString(item, 'budgetName', "${parentKey}.${childKey}[${index}]"),
                tokenEnvVarName: requireString(item, 'tokenEnvVarName', "${parentKey}.${childKey}[${index}]"),
                parentCategoryNames: requireStringList(item, 'parentCategoryNames', "${parentKey}.${childKey}[${index}]"),
                childAccountName: requireString(item, 'childAccountName', "${parentKey}.${childKey}[${index}]")
            )
        }
    }

    private static SyncLoggingConfig requireSyncLoggingConfig(Map raw, String childKey, String parentKey) {
        def value = raw[childKey]
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Config key '${parentKey}.${childKey}' must be a map")
        }
        Map loggingMap = value as Map
        new SyncLoggingConfig(
            filePath: requireString(loggingMap, 'filePath', "${parentKey}.${childKey}"),
            level: requireString(loggingMap, 'level', "${parentKey}.${childKey}"),
            maxHistory: requirePositiveInteger(loggingMap, 'maxHistory', "${parentKey}.${childKey}"),
            maxFileSizeMb: requirePositiveInteger(loggingMap, 'maxFileSizeMb', "${parentKey}.${childKey}")
        )
    }

    private static SyncStateConfig requireSyncStateConfig(Map raw, String childKey, String parentKey) {
        def value = raw[childKey]
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Config key '${parentKey}.${childKey}' must be a map")
        }
        Map stateMap = value as Map
        new SyncStateConfig(
            sqlitePath: requireString(stateMap, 'sqlitePath', "${parentKey}.${childKey}"),
            transactionLookbackDays: requirePositiveInteger(stateMap, 'transactionLookbackDays', "${parentKey}.${childKey}"),
            moneyMovementLookbackDays: requirePositiveInteger(stateMap, 'moneyMovementLookbackDays', "${parentKey}.${childKey}")
        )
    }

    private static String requireString(Map raw, String key) {
        requireString(raw, key, null)
    }

    private static String requireString(Map raw, String key, String parentKey) {
        def value = raw[key]
        if (!(value instanceof String) || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Config key '${formatKey(parentKey, key)}' must be a non-empty string")
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

    private static Integer requirePositiveInteger(Map raw, String key, String parentKey) {
        def value = raw[key]
        if (!(value instanceof Number) || (value as Number).intValue() <= 0) {
            throw new IllegalArgumentException("Config key '${formatKey(parentKey, key)}' must be a positive integer")
        }
        (value as Number).intValue()
    }

    private static List<String> requireStringList(Map raw, String key) {
        requireStringList(raw, key, null)
    }

    private static List<String> requireStringList(Map raw, String key, String parentKey) {
        def value = raw[key]
        if (!(value instanceof List) || value.any { !(it instanceof String) || it.trim().isEmpty() }) {
            throw new IllegalArgumentException("Config key '${formatKey(parentKey, key)}' must be a list of non-empty strings")
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

    private static String formatKey(String parentKey, String childKey) {
        parentKey ? "${parentKey}.${childKey}" : childKey
    }
}

@Immutable
class SyncConfig {
    BudgetRef parentBudget
    List<ChildBudgetSyncTarget> childBudgets
    Integer pollingIntervalSeconds
    SyncLoggingConfig logging
    SyncStateConfig state

    void validate() {
        if (childBudgets == null || childBudgets.isEmpty()) {
            throw new IllegalArgumentException("Config key 'sync.childBudgets' must be a non-empty list")
        }
        def seenChildKeys = [] as Set
        def seenBudgetAndAccount = [] as Set
        childBudgets.each { ChildBudgetSyncTarget target ->
            if (!seenChildKeys.add(target.childKey)) {
                throw new IllegalArgumentException("sync.childBudgets childKey '${target.childKey}' must be unique")
            }
            if (!seenBudgetAndAccount.add("${target.budgetName}::${target.childAccountName}")) {
                throw new IllegalArgumentException("sync.childBudgets budget/account pairing '${target.budgetName} / ${target.childAccountName}' must be unique")
            }
        }
    }
}

@Immutable
class BudgetRef {
    String budgetName
    String tokenEnvVarName
}

@Immutable
class ChildBudgetSyncTarget {
    String childKey
    String budgetName
    String tokenEnvVarName
    List<String> parentCategoryNames
    String childAccountName
}

@Immutable
class SyncLoggingConfig {
    String filePath
    String level
    Integer maxHistory
    Integer maxFileSizeMb

    void validate() {
        def allowedLevels = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']
        if (!allowedLevels.contains(level?.toUpperCase())) {
            throw new IllegalArgumentException("Config key 'sync.logging.level' must be one of ${allowedLevels.join(', ')}")
        }
        if (maxHistory == null || maxHistory <= 0) {
            throw new IllegalArgumentException("Config key 'sync.logging.maxHistory' must be a positive integer")
        }
        if (maxFileSizeMb == null || maxFileSizeMb <= 0) {
            throw new IllegalArgumentException("Config key 'sync.logging.maxFileSizeMb' must be a positive integer")
        }
    }
}

@Immutable
class SyncStateConfig {
    String sqlitePath
    Integer transactionLookbackDays
    Integer moneyMovementLookbackDays
}

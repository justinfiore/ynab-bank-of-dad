package ynabbankofdad.config

import groovy.transform.Immutable
import org.yaml.snakeyaml.Yaml

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

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
        List<String> kidsWithoutInterest = optionalStringList(raw, 'kidsWithoutInterest')
        List<String> kidsWithSimpleAccounts = optionalStringList(raw, 'kidsWithSimpleAccounts')
        boolean requiresSimpleAccountConfig = !kidsWithoutInterest.isEmpty() || !kidsWithSimpleAccounts.isEmpty()

        RuntimeConfig config = new RuntimeConfig(
            budgetName: requireString(raw, 'budgetName'),
            allowanceEscrowAccountName: requireString(raw, 'allowanceEscrowAccountName'),
            allowanceCategoryName: requireString(raw, 'allowanceCategoryName'),
            interestMemo: requireString(raw, 'interestMemo'),
            allowanceMemo: requireString(raw, 'allowanceMemo'),
            combinedMemo: requireString(raw, 'combinedMemo'),
            nonInterestMemoSuffix: requireString(raw, 'nonInterestMemoSuffix'),
            bankSuffixes: requiresSimpleAccountConfig ? requireStringList(raw, 'bankSuffixes') : optionalStringList(raw, 'bankSuffixes'),
            allowanceRates: requiresSimpleAccountConfig ? requireNumberMap(raw, 'allowanceRates') : optionalNumberMap(raw, 'allowanceRates'),
            giveBankRate: requireNumber(raw, 'giveBankRate'),
            kidsWithoutInterest: kidsWithoutInterest,
            kidsWithSimpleAccounts: kidsWithSimpleAccounts,
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
            String itemKey = "${parentKey}.${childKey}[${index}]"
            new ChildBudgetSyncTarget(
                childKey: requireString(item, 'childKey', itemKey),
                budgetName: requireString(item, 'budgetName', itemKey),
                tokenEnvVarName: requireString(item, 'tokenEnvVarName', itemKey),
                accountMappings: requireAccountMappings(item, 'accountMappings', itemKey),
                memoPrefix: item.containsKey('memoPrefix') ? item.memoPrefix : "YBOD: ",
                memoSuffix: item.containsKey('memoSuffix') ? item.memoSuffix : ""
            )
        }
    }

    private static List<ChildAccountMapping> requireAccountMappings(Map raw, String key, String parentKey) {
        def value = raw[key]
        if (!(value instanceof List) || value.isEmpty()) {
            throw new IllegalArgumentException("Config key '${formatKey(parentKey, key)}' must be a non-empty list")
        }
        (value as List).withIndex().collect { def entry, int index ->
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Config key '${formatKey(parentKey, key)}[${index}]' must be a map")
            }
            Map item = entry as Map
            String itemKey = "${formatKey(parentKey, key)}[${index}]"
            new ChildAccountMapping(
                mappingKey: requireString(item, 'mappingKey', itemKey),
                parentCategoryNames: requireParentCategoryNameMatchers(item, 'parentCategoryNames', itemKey),
                childAccountName: requireString(item, 'childAccountName', itemKey)
            )
        }
    }

    private static List<ParentCategoryNameMatcher> requireParentCategoryNameMatchers(Map raw, String key, String parentKey) {
        def value = raw[key]
        if (!(value instanceof List) || value.isEmpty()) {
            throw new IllegalArgumentException("Config key '${formatKey(parentKey, key)}' must be a non-empty list")
        }
        (value as List).withIndex().collect { def entry, int index ->
            String itemKey = "${formatKey(parentKey, key)}[${index}]"
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Config key '${itemKey}' must be a map with a non-empty name and optional boolean regex")
            }
            Map item = entry as Map
            def regexValue = item.containsKey('regex') ? item.regex : false
            if (!(regexValue instanceof Boolean)) {
                throw new IllegalArgumentException("Config key '${itemKey}.regex' must be boolean when provided")
            }
            new ParentCategoryNameMatcher(
                name: requireString(item, 'name', itemKey),
                regex: regexValue as Boolean
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

    private static List<String> optionalStringList(Map raw, String key) {
        if (!raw.containsKey(key) || raw[key] == null) {
            return []
        }
        requireStringList(raw, key)
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

    private static Map<String, Number> optionalNumberMap(Map raw, String key) {
        if (!raw.containsKey(key) || raw[key] == null) {
            return [:]
        }
        requireNumberMap(raw, key)
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
        childBudgets.eachWithIndex { ChildBudgetSyncTarget target, int index ->
            if (!seenChildKeys.add(target.childKey)) {
                throw new IllegalArgumentException("sync.childBudgets[${index}].childKey '${target.childKey}' must be unique")
            }
            target.validate("sync.childBudgets[${index}]")
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
    List<ChildAccountMapping> accountMappings
    String memoPrefix = "YBOD: "
    String memoSuffix = ""

    void validate(String childConfigPath) {
        if (accountMappings == null || accountMappings.isEmpty()) {
            throw new IllegalArgumentException("Config key '${childConfigPath}.accountMappings' must be a non-empty list")
        }
        def seenMappingKeys = [] as Set
        def seenChildAccountNames = [] as Set
        accountMappings.eachWithIndex { ChildAccountMapping mapping, int index ->
            String mappingPath = "${childConfigPath}.accountMappings[${index}]"
            if (!seenMappingKeys.add(mapping.mappingKey)) {
                throw new IllegalArgumentException("Config key '${mappingPath}.mappingKey' duplicates mappingKey '${mapping.mappingKey}' within the same child budget")
            }
            if (!seenChildAccountNames.add(mapping.childAccountName)) {
                throw new IllegalArgumentException("Config key '${mappingPath}.childAccountName' duplicates child account '${mapping.childAccountName}' within the same child budget; list multiple parentCategoryNames on one mapping instead")
            }
            mapping.validate(mappingPath)
        }
    }
}

@Immutable
class ChildAccountMapping {
    String mappingKey
    List<ParentCategoryNameMatcher> parentCategoryNames
    String childAccountName

    void validate(String mappingConfigPath) {
        if (parentCategoryNames == null || parentCategoryNames.isEmpty()) {
            throw new IllegalArgumentException("Config key '${mappingConfigPath}.parentCategoryNames' must be a non-empty list")
        }
        parentCategoryNames.eachWithIndex { ParentCategoryNameMatcher matcher, int index ->
            if (matcher.regex) {
                try {
                    Pattern.compile(matcher.name)
                } catch (PatternSyntaxException ex) {
                    throw new IllegalArgumentException("Config key '${mappingConfigPath}.parentCategoryNames[${index}].name' has invalid regex pattern '${matcher.name}': ${ex.message}")
                }
            }
        }
    }
}

@Immutable
class ParentCategoryNameMatcher {
    String name
    Boolean regex = false
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

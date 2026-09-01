import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class RuntimeConfigSpec extends Specification {

    @TempDir
    Path tempDir

    def "load reads a valid yaml config file"() {
        given:
        def configFile = tempDir.resolve('config.yaml').toFile()
        configFile.text = '''
budgetName: Demo Family Budget
allowanceEscrowAccountName: Allowance Escrow
allowanceCategoryName: Family Allowance
interestMemo: Interest
allowanceMemo: Allowance
combinedMemo: Allowance and Interest combined
nonInterestMemoSuffix: Piggy Banks
bankSuffixes:
  - " Spend Bank"
  - " Save Bank"
  - " Give Bank"
allowanceRates:
  " Spend Bank": 1.0
  " Save Bank": 0.5
  " Give Bank": 0.5
giveBankRate: 0.5
kidsWithoutInterest:
  - Sam
kidsWithSimpleAccounts:
  - Taylor
kidsWithAdvancedAccounts:
  - Child One
advancedAllowanceDeposits:
  Child One:
    "Child One Silver Account": 3.0
    "Child One Give Bank": 0.5
accountTypes:
  - Bronze
  - Silver
  - Gold CD 2-Month
interestRatesByAccountTypeAndDate:
  Current:
    Bronze: 0.1
    Silver: 0.15
    Gold CD 2-Month: 0.25
  "2025-06-01":
    Bronze: 0.1
    Silver: 0.5
    Gold CD 2-Month: 0.75
sync:
  parentBudget:
    budgetName: Parent Budget
    tokenEnvVarName: YNAB_PARENT_TOKEN
  childBudgets:
    - childKey: child-one
      budgetName: Child One Budget
      tokenEnvVarName: YNAB_CHILD_ONE_TOKEN
      accountMappings:
        - mappingKey: spend
          parentCategoryNames:
            - name: "Child One Spend Bank"
            - name: "Child One Save Bank"
          childAccountName: Child One Checking
        - mappingKey: cd
          parentCategoryNames:
            - name: "Child One Gold CD.*"
              regex: true
          childAccountName: Child One CD
  pollingIntervalSeconds: 300
  logging:
    filePath: logs/sync.log
    level: INFO
    maxHistory: 7
    maxFileSizeMb: 10
  state:
    sqlitePath: syncstate.db
    transactionLookbackDays: 45
    moneyMovementLookbackDays: 45
'''

        when:
        def config = RuntimeConfig.load(configFile.path)

        then:
        config.budgetName == 'Demo Family Budget'
        config.allowanceEscrowAccountName == 'Allowance Escrow'
        config.allowanceCategoryName == 'Family Allowance'
        config.interestMemo == 'Interest'
        config.allowanceMemo == 'Allowance'
        config.combinedMemo == 'Allowance and Interest combined'
        config.nonInterestMemoSuffix == 'Piggy Banks'
        config.bankSuffixes == [' Spend Bank', ' Save Bank', ' Give Bank']
        config.allowanceRates == [' Spend Bank': 1.0, ' Save Bank': 0.5, ' Give Bank': 0.5]
        config.giveBankRate == 0.5
        config.kidsWithoutInterest == ['Sam']
        config.kidsWithSimpleAccounts == ['Taylor']
        config.kidsWithAdvancedAccounts == ['Child One']
        config.advancedAllowanceDeposits['Child One'] == ['Child One Silver Account': 3.0, 'Child One Give Bank': 0.5]
        config.accountTypes == ['Bronze', 'Silver', 'Gold CD 2-Month']
        config.interestRatesByAccountTypeAndDate['Current']['Silver'] == 0.15
        config.interestRatesByAccountTypeAndDate['2025-06-01']['Gold CD 2-Month'] == 0.75
        config.sync.parentBudget.budgetName == 'Parent Budget'
        config.sync.childBudgets*.childKey == ['child-one']
        config.sync.state.sqlitePath == 'syncstate.db'
    }

    def "sync childBudgets parse memoPrefix and memoSuffix correctly including explicit empty string"() {
        given:
        def raw = validConfigMap()
        raw.sync.childBudgets = [[
            childKey: 'child-one',
            budgetName: 'Child Budget',
            tokenEnvVarName: 'YNAB_CHILD_TOKEN',
            accountMappings: [[mappingKey: 'spend', parentCategoryNames: [[name: 'Child One Spend Bank']], childAccountName: 'Spend Account']],
            memoPrefix: 'YBOD: ',
            memoSuffix: ''
        ], [
            childKey: 'child-two',
            budgetName: 'Child Budget 2',
            tokenEnvVarName: 'YNAB_CHILD2_TOKEN',
            accountMappings: [[mappingKey: 'spend', parentCategoryNames: [[name: 'Child Two Spend Bank']], childAccountName: 'Spend Account']],
            memoPrefix: '',
            memoSuffix: ' (auto)'
        ]]

        when:
        def config = RuntimeConfig.fromMap(raw)

        then:
        config.sync.childBudgets[0].memoPrefix == 'YBOD: '
        config.sync.childBudgets[0].memoSuffix == ''
        config.sync.childBudgets[1].memoPrefix == ''
        config.sync.childBudgets[1].memoSuffix == ' (auto)'
    }

    def "sync childBudgets use memo decoration defaults when fields are omitted"() {
        when:
        def config = RuntimeConfig.fromMap(validConfigMap())

        then:
        config.sync.childBudgets[0].memoPrefix == 'YBOD: '
        config.sync.childBudgets[0].memoSuffix == ''
        config.sync.childBudgets[0].autoCreateAccounts == false
        config.sync.childBudgets[0].createdAccountOnBudget == true
        config.sync.childBudgets[0].accountCreationNameStripRegex == null
    }

    def "sync childBudgets parse auto-create account settings"() {
        given:
        def raw = validConfigMap()
        raw.sync.childBudgets[0].autoCreateAccounts = true
        raw.sync.childBudgets[0].createdAccountOnBudget = false
        raw.sync.childBudgets[0].accountCreationNameStripRegex = ' Bank$'

        when:
        def config = RuntimeConfig.fromMap(raw)

        then:
        config.sync.childBudgets[0].autoCreateAccounts
        config.sync.childBudgets[0].createdAccountOnBudget == false
        config.sync.childBudgets[0].accountCreationNameStripRegex == ' Bank$'
        config.sync.childBudgets[0].derivedAccountName('Child One Spend Bank') == 'Child One Spend'
    }

    def "sync childBudgets reject invalid auto-create settings with full config paths"() {
        given:
        def raw = validConfigMap()
        mutation(raw)

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        mutation << [
            { Map cfg -> cfg.sync.childBudgets[0].autoCreateAccounts = 'yes' },
            { Map cfg -> cfg.sync.childBudgets[0].createdAccountOnBudget = 'tracking' },
            { Map cfg -> cfg.sync.childBudgets[0].accountCreationNameStripRegex = '' },
            { Map cfg -> cfg.sync.childBudgets[0].accountCreationNameStripRegex = '[' }
        ]
        expected << [
            'sync.childBudgets[0].autoCreateAccounts',
            'sync.childBudgets[0].createdAccountOnBudget',
            'sync.childBudgets[0].accountCreationNameStripRegex',
            'sync.childBudgets[0].accountCreationNameStripRegex'
        ]
    }

    def "sync childBudgets reject non-string memo decoration with full config paths"() {
        given:
        def raw = validConfigMap()
        raw.sync.childBudgets[0][field] = invalidValue

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Config key 'sync.childBudgets[0].${field}' must be a string"

        where:
        field        | invalidValue
        'memoPrefix' | 123
        'memoPrefix' | null
        'memoSuffix' | false
    }

    def "load throws when config file does not exist"() {
        when:
        RuntimeConfig.load(tempDir.resolve('missing.yaml').toString())

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Config file not found')
    }

    def "load throws when yaml top level is not a map"() {
        given:
        def configFile = tempDir.resolve('invalid-top-level.yaml').toFile()
        configFile.text = '''
- not
- a
- map
'''

        when:
        RuntimeConfig.load(configFile.path)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('YAML object at the top level')
    }

    def "fromMap throws when required string key is missing or blank"() {
        given:
        def raw = validConfigMap()
        raw.remove('budgetName')

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Config key 'budgetName' must be a non-empty string")
    }

    def "fromMap throws when string list contains blank entries"() {
        given:
        def raw = validConfigMap()
        raw.kidsWithAdvancedAccounts = ['Child One', '']

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Config key 'kidsWithAdvancedAccounts' must be a list of non-empty strings")
    }

    def "fromMap throws when numeric value is not numeric"() {
        given:
        def raw = validConfigMap()
        raw.giveBankRate = '0.5'

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Config key 'giveBankRate' must be numeric")
    }

    def "fromMap throws when number map contains non-numeric values"() {
        given:
        def raw = validConfigMap()
        raw.allowanceRates = [' Spend Bank': 'one dollar']

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Config key 'allowanceRates' must map strings to numeric values")
    }

    def "fromMap defaults simple-account config fields when simple-account kids are omitted"() {
        given:
        def raw = validConfigMap()
        raw.remove('bankSuffixes')
        raw.remove('allowanceRates')
        raw.remove('kidsWithoutInterest')
        raw.remove('kidsWithSimpleAccounts')

        when:
        def config = RuntimeConfig.fromMap(raw)

        then:
        config.bankSuffixes == []
        config.allowanceRates == [:]
        config.kidsWithoutInterest == []
        config.kidsWithSimpleAccounts == []
    }

    def "fromMap still requires simple-account config when simple-account kids are configured"() {
        given:
        def raw = validConfigMap()
        raw.kidsWithoutInterest = ['Sam']
        raw.remove('bankSuffixes')
        raw.remove('allowanceRates')

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Config key 'bankSuffixes' must be a list of non-empty strings")
    }

    def "fromMap throws when nested map contains non-numeric values"() {
        given:
        def raw = validConfigMap()
        raw.advancedAllowanceDeposits = [
            'Child One': ['Child One Silver Account': 'three']
        ]

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Config key 'advancedAllowanceDeposits' must map strings to nested numeric maps")
    }

    def "validate throws when advanced kid is missing explicit deposit mapping"() {
        given:
        def raw = validConfigMap()
        raw.kidsWithAdvancedAccounts = ['Child One', 'Child Two']

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("missing advancedAllowanceDeposits entry for 'Child Two'")
    }

    def "validate throws when allowanceRates key is not listed in bankSuffixes"() {
        given:
        def raw = validConfigMap()
        raw.allowanceRates = [' Unknown Bank': 1.0]

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("allowanceRates key ' Unknown Bank' must also appear in bankSuffixes")
    }

    def "validate throws when Current rate table is missing"() {
        given:
        def raw = validConfigMap()
        raw.interestRatesByAccountTypeAndDate = [
            '2025-06-01': ['Bronze': 0.1]
        ]

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("must include a 'Current' rate table")
    }

    def "validate accepts Current rate table regardless of key casing"() {
        given:
        def raw = validConfigMap()
        raw.interestRatesByAccountTypeAndDate = [
            'current': ['Bronze': 0.1, 'Silver': 0.15, 'Gold CD 2-Month': 0.25],
            '2025-06-01': ['Bronze': 0.1, 'Silver': 0.5, 'Gold CD 2-Month': 0.75]
        ]

        when:
        def config = RuntimeConfig.fromMap(raw)

        then:
        config.interestRatesByAccountTypeAndDate['current']['Silver'] == 0.15
    }

    def "validate throws when child sync target is missing required mapping field"() {
        given:
        def raw = validConfigMap()
        raw.sync.childBudgets = [[
            childKey: 'child-one',
            budgetName: 'Child Budget',
            tokenEnvVarName: 'YNAB_CHILD_ONE_TOKEN'
        ]]

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("sync.childBudgets[0].accountMappings")
    }

    def "sync config accepts multiple account mappings with literal and regex matchers"() {
        given:
        def raw = validConfigMap()
        raw.sync.childBudgets[0].accountMappings = [
            [mappingKey: 'spend', parentCategoryNames: [[name: 'Child One Spend Bank']], childAccountName: 'Spend Account'],
            [mappingKey: 'give', parentCategoryNames: [[name: 'Child One Give Bank']], childAccountName: 'Give Account'],
            [mappingKey: 'cd', parentCategoryNames: [[name: 'Child One Gold CD.*', regex: true], [name: 'Child One CD (2-Month) [07/31/26]']], childAccountName: 'CD Account']
        ]

        when:
        def config = RuntimeConfig.fromMap(raw)

        then:
        config.sync.childBudgets[0].accountMappings*.mappingKey == ['spend', 'give', 'cd']
        config.sync.childBudgets[0].accountMappings[2].parentCategoryNames*.regex == [true, false]
        config.sync.childBudgets[0].accountMappings[2].childAccountName == 'CD Account'
    }

    def "sync config rejects duplicate mapping keys invalid regex and malformed matcher flags"() {
        given:
        def raw = validConfigMap()
        mutation(raw)

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        mutation << [
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings << [mappingKey: 'spend', parentCategoryNames: [[name: 'Other']], childAccountName: 'Other Account'] },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings << [mappingKey: 'give', parentCategoryNames: [[name: 'Child One Give Bank']], childAccountName: 'Child Checking'] },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].parentCategoryNames = [[name: 'Child One Gold CD[', regex: true]] },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].parentCategoryNames = [[name: 'Child One Spend Bank', regex: 'yes']] },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].parentCategoryNames = [[regex: false]] }
        ]
        expected << [
            'sync.childBudgets[0].accountMappings[1].mappingKey',
            'sync.childBudgets[0].accountMappings[1].childAccountName',
            'sync.childBudgets[0].accountMappings[0].parentCategoryNames[0].name',
            'sync.childBudgets[0].accountMappings[0].parentCategoryNames[0].regex',
            'sync.childBudgets[0].accountMappings[0].parentCategoryNames[0].name'
        ]
    }

    def 'sync config startup errors identify missing nested account mapping fields with full config paths'() {
        given:
        def raw = validConfigMap()
        mutation(raw)

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        mutation << [
            { Map cfg -> cfg.sync.childBudgets[0].remove('accountMappings') },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings = [] },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].remove('mappingKey') },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].remove('childAccountName') },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].remove('parentCategoryNames') },
            { Map cfg -> cfg.sync.childBudgets[0].accountMappings[0].parentCategoryNames = [] }
        ]
        expected << [
            'sync.childBudgets[0].accountMappings',
            'sync.childBudgets[0].accountMappings',
            'sync.childBudgets[0].accountMappings[0].mappingKey',
            'sync.childBudgets[0].accountMappings[0].childAccountName',
            'sync.childBudgets[0].accountMappings[0].parentCategoryNames',
            'sync.childBudgets[0].accountMappings[0].parentCategoryNames'
        ]
    }

    def "validate throws when sync logging level is invalid"() {
        given:
        def raw = validConfigMap()
        raw.sync.logging.level = 'VERBOSE'

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("sync.logging.level")
    }

    def "positive integer sync settings reject fractional and overflowing values"() {
        given:
        def raw = validConfigMap()
        mutation(raw)

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        mutation << [
            { Map cfg -> cfg.sync.pollingIntervalSeconds = 1.5 },
            { Map cfg -> cfg.sync.logging.maxHistory = 2147483648L },
            { Map cfg -> cfg.sync.state.transactionLookbackDays = 2.25 }
        ]
        expected << ['sync.pollingIntervalSeconds', 'sync.logging.maxHistory', 'sync.state.transactionLookbackDays']
    }

    def "validate throws when polling interval is non-positive"() {
        given:
        def raw = validConfigMap()
        raw.sync.pollingIntervalSeconds = 0

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("sync.pollingIntervalSeconds")
    }

    def "validate throws when replay protection setting is non-positive"() {
        given:
        def raw = validConfigMap()
        raw.sync.state.moneyMovementLookbackDays = -1

        when:
        RuntimeConfig.fromMap(raw)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("sync.state.moneyMovementLookbackDays")
    }

    private static Map validConfigMap() {
        [
            budgetName: 'Demo Family Budget',
            allowanceEscrowAccountName: 'Allowance Escrow',
            allowanceCategoryName: 'Family Allowance',
            interestMemo: 'Interest',
            allowanceMemo: 'Allowance',
            combinedMemo: 'Allowance and Interest combined',
            nonInterestMemoSuffix: 'Piggy Banks',
            bankSuffixes: [' Spend Bank', ' Save Bank', ' Give Bank'],
            allowanceRates: [' Spend Bank': 1.0, ' Save Bank': 0.5, ' Give Bank': 0.5],
            giveBankRate: 0.5,
            kidsWithoutInterest: [],
            kidsWithSimpleAccounts: [],
            kidsWithAdvancedAccounts: ['Child One'],
            advancedAllowanceDeposits: [
                'Child One': ['Child One Silver Account': 3.0, 'Child One Give Bank': 0.5]
            ],
            accountTypes: ['Bronze', 'Silver', 'Gold CD 2-Month'],
            interestRatesByAccountTypeAndDate: [
                'Current': ['Bronze': 0.1, 'Silver': 0.15, 'Gold CD 2-Month': 0.25],
                '2025-06-01': ['Bronze': 0.1, 'Silver': 0.5, 'Gold CD 2-Month': 0.75]
            ],
            sync: [
                parentBudget: [budgetName: 'Parent Budget', tokenEnvVarName: 'YNAB_PARENT_TOKEN'],
                childBudgets: [[
                    childKey: 'child-one',
                    budgetName: 'Child Budget',
                    tokenEnvVarName: 'YNAB_CHILD_ONE_TOKEN',
                    accountMappings: [[
                        mappingKey: 'spend',
                        parentCategoryNames: [[name: 'Child One Spend Bank']],
                        childAccountName: 'Child Checking'
                    ]]
                ]],
                pollingIntervalSeconds: 300,
                logging: [filePath: 'logs/sync.log', level: 'INFO', maxHistory: 7, maxFileSizeMb: 10],
                state: [sqlitePath: 'syncstate.db', transactionLookbackDays: 45, moneyMovementLookbackDays: 45]
            ]
        ]
    }
}

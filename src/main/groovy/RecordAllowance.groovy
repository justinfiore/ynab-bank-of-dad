import groovy.json.JsonOutput
import groovy.cli.picocli.CliBuilder
import groovy.util.logging.Slf4j
import org.apache.commons.lang3.StringUtils

import java.text.SimpleDateFormat

/**
 *
 */
@Slf4j
class RecordAllowance {

    static final String DEFAULT_CONFIG_PATH = 'config.yaml'
    static final String DRY_RUN_PREFIX = '[DRY RUN] Would have '
    static final SimpleDateFormat inputDateFormat = new SimpleDateFormat('yyyy-MM-dd')
    static final SimpleDateFormat cdDateFormat = new SimpleDateFormat('MM/dd/yy')
    static final SimpleDateFormat yyyymmddDateFormat = new SimpleDateFormat('yyyy-MM-dd')

    String allowanceEscrowAccountName
    String allowanceCategoryName
    String budgetName
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

    static def dryRun = false

    static final void main(String[] args) {
        String accessToken = System.getenv('YNAB_ACCESS_TOKEN')
        if (StringUtils.isBlank(accessToken)) {
            throw new IllegalArgumentException('environment variable YNAB_ACCESS_TOKEN must be set')
        }

        def cli = new CliBuilder(usage: 'RecordAllowance')
        cli.d(longOpt: 'date', args: 1, argName: 'Date to use', 'Date to use: YYYY-MM-DD. Default: Current Date')
        cli.c(longOpt: 'config', args: 1, argName: 'Config file', "Path to config YAML file. Default: ${DEFAULT_CONFIG_PATH}")
        cli._(longOpt: 'dry-run', "Dry Run. Don't actually execute")
        cli.h(longOpt: 'help', 'Help')
        def options = cli.parse(args)

        if (!options) {
            System.exit(1)
        }

        if (options.h) {
            cli.usage()
            System.exit(0)
        }

        def date = new Date()
        if (options.d) {
            date = inputDateFormat.parse(options.d)
        }
        if (options.'dry-run') {
            dryRun = true
            println('Dry Run Enabled.')
        }
        println("Using Date: $date")

        String configPath = options.c ?: DEFAULT_CONFIG_PATH
        println("Using Config: $configPath")
        RuntimeConfig config = RuntimeConfig.load(configPath)

        def ra = new RecordAllowance(accessToken, date, config)

        def categoryInfo = ra.getCategoryInfoByCategoryName()
        def allowanceEscrowAccountId = ra.getAccountId(ra.allowanceEscrowAccountName)

        log.info("Account Id for ${ra.allowanceEscrowAccountName}: $allowanceEscrowAccountId")

        List<TransactionDraft> transactionsThatNeedOffsetting = []
        transactionsThatNeedOffsetting.addAll(ra.generateInterestTransactionsForSimpleAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(ra.generateNewAllowanceTransactionsForSimpleAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(ra.generateInterestTransactionsForAdvancedAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(ra.generateNewAllowanceTransactionsForAdvancedAccounts(allowanceEscrowAccountId, categoryInfo))

        List<TransactionDraft> transactions = []
        transactions.addAll(transactionsThatNeedOffsetting)
        transactions << ra.generateOffsettingTransaction(allowanceEscrowAccountId, transactionsThatNeedOffsetting, categoryInfo)
        transactions.addAll(ra.generateNonInterestBearingTransactions(allowanceEscrowAccountId, categoryInfo))

        def ynabTransactions = toYnabTransactions(transactions)
        log.info("Transactions to add: ${JsonOutput.prettyPrint(JsonOutput.toJson(ynabTransactions))}")

        if (!dryRun) {
            def postedTransactions = ra.postTransactions(ynabTransactions)
            log.info("Successfully posted the following transactions: ${JsonOutput.prettyPrint(JsonOutput.toJson(postedTransactions))}")
        } else {
            log.info(DRY_RUN_PREFIX + " posted the following transactions: ${JsonOutput.prettyPrint(JsonOutput.toJson(ynabTransactions))}")
        }
    }

    def accessToken = null
    def ynabClient = null
    def ynabRepository = null
    def budgetId = null
    def transactionDate = null
    def calculationService = null
    def transactionAssemblyService = null

    RecordAllowance(String accessToken, Date transactionDate) {
        this(accessToken, transactionDate, RuntimeConfig.load(DEFAULT_CONFIG_PATH), true, null)
    }

    RecordAllowance(String accessToken, Date transactionDate, RuntimeConfig config) {
        this(accessToken, transactionDate, config, true, null)
    }

    RecordAllowance(String accessToken, Date transactionDate, boolean initializeBudget) {
        this(accessToken, transactionDate, RuntimeConfig.load(DEFAULT_CONFIG_PATH), initializeBudget, null)
    }

    RecordAllowance(String accessToken, Date transactionDate, boolean initializeBudget, ynabClient) {
        this(accessToken, transactionDate, RuntimeConfig.load(DEFAULT_CONFIG_PATH), initializeBudget, ynabClient)
    }

    RecordAllowance(String accessToken, Date transactionDate, RuntimeConfig config, boolean initializeBudget) {
        this(accessToken, transactionDate, config, initializeBudget, null)
    }

    RecordAllowance(String accessToken, Date transactionDate, RuntimeConfig config, boolean initializeBudget, ynabClient) {
        this.accessToken = accessToken
        this.transactionDate = transactionDate
        applyConfig(config)
        log.info('YNAB access token loaded from environment')
        this.ynabClient = ynabClient
        if (this.ynabClient == null && initializeBudget) {
            this.ynabClient = new YnabHttpClient('https://api.youneedabudget.com', this.accessToken)
        }
        if (this.ynabClient != null) {
            this.ynabRepository = new YnabBudgetRepository(this.ynabClient)
        }
        this.calculationService = new AllowanceCalculationService(transactionDate, interestRatesByAccountTypeAndDate, accountTypes)
        this.transactionAssemblyService = new TransactionAssemblyService(
            dateFormat.format(transactionDate),
            calculationService,
            allowanceCategoryName,
            allowanceMemo,
            combinedMemo,
            nonInterestMemoSuffix
        )

        if (initializeBudget) {
            budgetId = getLatestBudgetId(budgetName)
            log.info("Most Recent Budget ID: $budgetId")
        }
    }

    private void applyConfig(RuntimeConfig config) {
        this.budgetName = config.budgetName
        this.allowanceEscrowAccountName = config.allowanceEscrowAccountName
        this.allowanceCategoryName = config.allowanceCategoryName
        this.interestMemo = config.interestMemo
        this.allowanceMemo = config.allowanceMemo
        this.combinedMemo = config.combinedMemo
        this.nonInterestMemoSuffix = config.nonInterestMemoSuffix
        this.bankSuffixes = config.bankSuffixes
        this.allowanceRates = config.allowanceRates
        this.giveBankRate = config.giveBankRate
        this.kidsWithoutInterest = config.kidsWithoutInterest
        this.kidsWithSimpleAccounts = config.kidsWithSimpleAccounts
        this.kidsWithAdvancedAccounts = config.kidsWithAdvancedAccounts
        this.advancedAllowanceDeposits = config.advancedAllowanceDeposits
        this.accountTypes = config.accountTypes
        this.interestRatesByAccountTypeAndDate = config.interestRatesByAccountTypeAndDate
    }

    def postTransactions(transactions) {
        ynabRepository.postTransactions(budgetId, transactions)
    }

    def generateInterestTransactionsForSimpleAccounts(accountId, categoryInfoByCategoryName) {
        []
    }

    def generateNewAllowanceTransactionsForSimpleAccounts(accountId, categoryInfoByCategoryName) {
        []
    }

    def generateInterestTransactionsForAdvancedAccounts(accountId, categoryInfoByCategoryName) {
        def snapshots = categoryInfoByCategoryName.collectEntries { String categoryName, category ->
            [(categoryName): asCategorySnapshot(categoryName, category)]
        }
        transactionAssemblyService.generateInterestTransactionsForAdvancedAccounts(accountId, snapshots, kidsWithAdvancedAccounts, accountTypes, interestMemo)
            .collect { it.toYnabTransaction() }
    }

    static def getCDOriginationDate(categoryName, maturityDate) {
        new AllowanceCalculationService(new Date(), [:], []).getCDOriginationDate(categoryName as String, maturityDate as Date)
    }

    def findInterestRatesForDate(date) {
        calculationService.findInterestRatesForDate(date)
    }

    def generateNewAllowanceTransactionsForAdvancedAccounts(accountId, categoryInfoByCategoryName) {
        def snapshots = categoryInfoByCategoryName.collectEntries { String categoryName, category ->
            [(categoryName): asCategorySnapshot(categoryName, category)]
        }
        transactionAssemblyService.generateNewAllowanceTransactionsForAdvancedAccounts(accountId, snapshots, kidsWithAdvancedAccounts, advancedAllowanceDeposits)
            .collect { it.toYnabTransaction() }
    }

    def generateOffsettingTransaction(accountId, transactionsForAllowanceAndInterest, categoryInfoByCategoryName) {
        def drafts = transactionsForAllowanceAndInterest.collect { transaction ->
            if (transaction instanceof TransactionDraft) {
                return transaction
            }
            new TransactionDraft(
                transaction.account_id as String,
                transaction.date as String,
                transaction.amount as Integer,
                transaction.payee_name as String,
                transaction.category_id as String,
                transaction.memo as String,
                transaction.approved as Boolean
            )
        }
        transactionAssemblyService.generateOffsettingTransaction(accountId, drafts, categoryInfoByCategoryName, kidsWithSimpleAccounts).toYnabTransaction()
    }

    def generateGiveBankTransactions(accountId, categoryInfoByCategoryName) {
        []
    }

    def generateNonInterestBearingTransactions(accountId, categoryInfoByCategoryName) {
        def snapshots = categoryInfoByCategoryName.collectEntries { String categoryName, category ->
            [(categoryName): asCategorySnapshot(categoryName, category)]
        }
        transactionAssemblyService.generateNonInterestBearingTransactions(accountId, snapshots, kidsWithoutInterest, allowanceRates, giveBankRate)
    }

    def getAccountId(accountName) {
        ynabRepository.getAccountId(budgetId, accountName)
    }

    def getCategoryInfoByCategoryName() {
        ynabRepository.getCategoryInfoByCategoryName(budgetId)
    }

    def getLatestBudgetId(String budgetName) {
        ynabRepository.getLatestBudgetId(budgetName)
    }

    def getUser() {
        ynabClient.getJson('/v1/user')
    }

    def dateFormat = yyyymmddDateFormat

    static List<Map<String, Object>> toYnabTransactions(List<TransactionDraft> transactionDrafts) {
        transactionDrafts.collect { transaction ->
            if (transaction instanceof TransactionDraft) {
                return transaction.toYnabTransaction()
            }
            transaction as Map<String, Object>
        }
    }

    static Integer toMilliUnits(Number dollars) {
        BigDecimal.valueOf(dollars as double)
            .multiply(BigDecimal.valueOf(1000L))
            .setScale(0, BigDecimal.ROUND_HALF_UP)
            .intValueExact()
    }

    static BigDecimal toDollars(Number milliunits) {
        BigDecimal.valueOf(milliunits as long)
            .divide(BigDecimal.valueOf(1000L))
    }

    private static CategorySnapshot asCategorySnapshot(String categoryName, category) {
        if (category instanceof CategorySnapshot) {
            return category
        }
        new CategorySnapshot(
            category.id as String,
            categoryName,
            (category.balance ?: 0) as Integer
        )
    }
}

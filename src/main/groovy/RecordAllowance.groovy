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

    def spendBankSuffix = " Spend Bank"
    def saveBankSuffix = " Save Bank"
    def giveBankSuffix = " Give Bank"
    def allowanceRates = ["$spendBankSuffix": 1, "$saveBankSuffix": 0.5, "$giveBankSuffix": 0.5]
    def giveBankRate = 0.5
    def bankSuffixes = [spendBankSuffix, saveBankSuffix, giveBankSuffix]
    static def DRY_RUN_PREFIX = "[DRY RUN] Would have "

    def jack = "Jack"
    def evan = "Evan"
    def emily = "Emily"
    def colin = "Colin"
    def kidsWithoutInterest = []
    def kidsWithSimpleAccounts = []
    def kidsWithAdvancedAccounts = [jack, evan, emily, colin]

    def advancedAllowanceDeposits = [
        "Jack": [
            "Jack Silver Account": 3,
            "Jack Give Bank": 0.5
        ],
        "Evan": [
            "Evan Silver Account": 3,
            "Evan Give Bank": 0.5
        ],
        "Emily": [
            "Emily Silver Account": 1,
            "Emily Give Bank": 0.5
        ],
        "Colin": [
            "Colin Silver Account": 1,
            "Colin Bronze Account": 0.5,
            "Colin Give Bank": 0.5
        ]
    ]

    def accountTypes = [
        "Bronze",
        "Silver",
        "Gold CD 2-Month",
        "Gold CD 3-Month",
        "Gold CD 6-Month",
        "First Car Fund"
    ]

    def interestRatesByAccountTypeAndDate = [
        "Current": [
            "Bronze": 0.1,
            "Silver": 0.15,
            "Gold CD 2-Month": 0.25,
            "Gold CD 3-Month": 0.35,
            "Gold CD 6-Month": 0.65,
            "First Car Fund": 0.65
        ],
        "2025-06-01": [
            "Bronze": 0.1,
            "Silver": 0.5,
            "Gold CD 2-Month": 0.75,
            "Gold CD 3-Month": 1.00,
            "Gold CD 6-Month": 1.25,
            "First Car Fund": 1.25
        ],
        "2025-04-14": [
            "Bronze": 0.25,
            "Silver": 0.75,
            "Gold CD 2-Month": 1.50,
            "Gold CD 3-Month": 1.75,
            "Gold CD 6-Month": 2.00,
            "First Car Fund": 2.00
        ],
        "2024-12-25": [
            "Gold CD 2-Month": 1.75,
            "Gold CD 3-Month": 2.00,
            "Gold CD 6-Month": 2.25,
        ],
        "2024-11-23": [
            "Gold CD 2-Month": 2.25,
            "Gold CD 3-Month": 2.5,
            "Gold CD 6-Month": 2.75
        ]
    ]

    static def dryRun = false

    static def inputDateFormat = new SimpleDateFormat("yyyy-MM-dd")
    static def cdDateFormat = new SimpleDateFormat("MM/dd/yy")
    static def yyyymmddDateFormat = new SimpleDateFormat("yyyy-MM-dd")

    public static final void main(String[] args) {
        String accessToken = System.getenv("YNAB_ACCESS_TOKEN")
        if (StringUtils.isBlank(accessToken)) {
            throw new IllegalArgumentException("environment variable YNAB_ACCESS_TOKEN must be set")
        }

        def cli = new CliBuilder(usage: 'RecordAllowance')
        cli.d(longOpt: 'date', args: 1, argName: 'Date to use', "Date to use: YYYY-MM-DD. Default: Current Date")
        cli._(longOpt: 'dry-run', "Dry Run. Don't actually execute")
        cli.h(longOpt: 'help', "Help")
        def options = cli.parse(args)

        def date = new Date()
        if (options.d) {
            date = inputDateFormat.parse(options.d)
        }
        if (options.'dry-run') {
            dryRun = true
            println("Dry Run Enabled.")
        }
        if (options.h) {
            cli.usage()
            System.exit(0)
        }
        println("Using Date: $date")
        def ra = new RecordAllowance(accessToken, date)

        def categoryInfo = ra.getCategoryInfoByCategoryName()
        def allowanceEscrowAccountId = ra.getAccountId("Allowance Escrow")

        log.info("Account Id for Allowance Escrow: $allowanceEscrowAccountId")

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

    public RecordAllowance(String accessToken, Date transactionDate) {
        this(accessToken, transactionDate, true)
    }

    public RecordAllowance(String accessToken, Date transactionDate, boolean initializeBudget) {
        this(accessToken, transactionDate, initializeBudget, null)
    }

    public RecordAllowance(String accessToken, Date transactionDate, boolean initializeBudget, ynabClient) {
        this.accessToken = accessToken
        this.transactionDate = transactionDate
        log.info("YNAB access token loaded from environment")
        this.ynabClient = ynabClient
        if (this.ynabClient == null && initializeBudget) {
            this.ynabClient = new YnabHttpClient("https://api.youneedabudget.com", this.accessToken)
        }
        if (this.ynabClient != null) {
            this.ynabRepository = new YnabBudgetRepository(this.ynabClient)
        }
        this.calculationService = new AllowanceCalculationService(transactionDate, interestRatesByAccountTypeAndDate, accountTypes)
        this.transactionAssemblyService = new TransactionAssemblyService(dateFormat.format(transactionDate), calculationService)

        if (initializeBudget) {
            budgetId = getLatestBudgetId("Fiores")
            log.info("Most Recent Budget ID: $budgetId")
        }
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
        transactionAssemblyService.generateInterestTransactionsForAdvancedAccounts(accountId, snapshots, kidsWithAdvancedAccounts, accountTypes)
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
        def drafts = transactionsForAllowanceAndInterest.collect { Map transaction ->
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
            .collect { it.toYnabTransaction() }
    }

    def getAccountId(accountName) {
        ynabRepository.getAccountId(budgetId, accountName)
    }

    def getCategoryInfoByCategoryName() {
        ynabRepository.getCategoryInfoByCategoryName(budgetId)
    }

    def toDollars(milliunits) {
        calculationService.toDollars(milliunits)
    }

    def toMilliUnits(dollars) {
        calculationService.toMilliUnits(dollars)
    }

    def getUser() {
        ynabRepository.getUser()
    }

    def getBudgets() {
        ynabRepository.getBudgets().collect { [id: it.id, name: it.name, last_modified_on: dateFormat.format(it.lastModifiedOn)] }
    }

    def dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    def getLatestBudgetId(budgetName) {
        ynabRepository.getLatestBudgetId(budgetName)
    }

    private static List<Map<String, Object>> toYnabTransactions(List<TransactionDraft> drafts) {
        drafts.collect { it.toYnabTransaction() }
    }

    private static CategorySnapshot asCategorySnapshot(String categoryName, Object category) {
        if (category instanceof CategorySnapshot) {
            return category as CategorySnapshot
        }
        new CategorySnapshot(
            category.id as String,
            categoryName,
            (category.balance ?: 0) as Integer
        )
    }
}

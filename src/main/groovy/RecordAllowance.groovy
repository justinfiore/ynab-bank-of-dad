import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import groovyx.net.http.HttpBuilder
import org.apache.commons.lang3.StringUtils

import java.math.MathContext
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
    def kidsWithoutInterest = []
    def kidsWithSimpleAccounts = [evan, emily]
    def kidsWithAdvancedAccounts = [jack]

    def advancedAllowanceDeposits = [
            "Jack": [
                    "Jack: Savings Account": 1,
                    "Jack: Checking Account": 0.5,
                    "Jack Give Bank": 0.5
            ]
    ]

    def interestRatesByAccountType = [
            "Checking": 0.25,
            "Savings": 0.75,
            "CD 2-Month": 2.25,
            "CD 3-Month": 2.5,
            "CD 6-Month": 2.75
    ]

    static def dryRun = false

    static def inputDateFormat = new SimpleDateFormat("yyyy-MM-dd")

    static def cdDateFormat = new SimpleDateFormat("MM/dd/yy")


    public static final void main(String[] args) {
        String accessToken = System.getenv("YNAB_ACCESS_TOKEN")
        if(StringUtils.isBlank(accessToken)) {
            throw new IllegalArgumentException("environment variable YNAB_ACCESS_TOKEN must be set")
        }

        def cli = new CliBuilder(usage:'RecordAllowance')
        cli.d(longOpt: 'date', args: 1, argName: 'Date to use', "Date to use: YYYY-MM-DD. Default: Current Date")
        cli._(longOpt: 'dry-run', "Dry Run. Don't actually execute")
        cli.h(longOpt: 'help', "Help")
        def options = cli.parse(args)


        def date = new Date()
        if(options.d) {
            def dateStr = options.d
            date = inputDateFormat.parse(dateStr)
        }
        if(options.'dry-run') {
            dryRun = true;
            println("Dry Run Enabled.")
        }
        if(options.h) {
            cli.usage()
            System.exit(0)
        }
        println("Using Date: $date")
        def ra = new RecordAllowance(accessToken, date)

        def categoryInfo = ra.getCategoryInfoByCategoryName()

        def allowanceEscrowAccountId = ra.getAccountId("Allowance Escrow")

        log.info("Account Id for Allowance Escrow: $allowanceEscrowAccountId")

        def transactions = []

        def transactionsThatNeedOffsetting = []

        transactionsThatNeedOffsetting.addAll(ra.generateInterestTransactionsForSimpleAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(ra.generateNewAllowanceTransactionsForSimpleAccounts(allowanceEscrowAccountId, categoryInfo))

        transactionsThatNeedOffsetting.addAll(ra.generateInterestTransactionsForAdvancedAccounts(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(ra.generateNewAllowanceTransactionsForAdvancedAccounts(allowanceEscrowAccountId, categoryInfo))


        transactions.addAll(transactionsThatNeedOffsetting)
        //transactions.addAll(ra.generateGiveBankTransactions(allowanceEscrowAccountId, categoryInfo))

        transactions.addAll(ra.generateOffsettingTransaction(allowanceEscrowAccountId, transactionsThatNeedOffsetting, categoryInfo))

        transactions.addAll(ra.generateNonInterestBearingTransactions(allowanceEscrowAccountId, categoryInfo))


        log.info("Transactions to add: ${JsonOutput.prettyPrint(JsonOutput.toJson(transactions))}")

        if(!dryRun) {
            def postedTransactions = ra.postTransactions(transactions)
            log.info("Successfully posted the following transactions: ${JsonOutput.prettyPrint(JsonOutput.toJson(postedTransactions))}")
        } else {
            log.info(DRY_RUN_PREFIX + " posted the following transactions: ${JsonOutput.prettyPrint(JsonOutput.toJson(transactions))}")
        }
    }

    def accessToken = null
    def ynabClient = null
    def budgetId = null
    def transactionDate = null

    public RecordAllowance(String accessToken, Date transactionDate) {

        this.accessToken = accessToken
        this.transactionDate = transactionDate
        log.info("Using accessToken: ${accessToken}")
        ynabClient = HttpBuilder.configure {
            request.uri = "https://api.youneedabudget.com"
            request.headers['Authorization'] = "Bearer ${this.accessToken}"
            request.headers['Accept'] = "application/json"
        }

        budgetId = getLatestBudgetId("Fiores")

        log.info("Most Recent Budget ID: $budgetId")

    }


    def postTransactions(transactions) {
        def r = ynabClient.post {
            request.uri.path = "/v1/budgets/$budgetId/transactions/bulk"
            request.contentType = "application/json"
            request.body = [
                transactions: transactions
            ]
        }
        return r
    }

    def generateInterestTransactionsForSimpleAccounts(accountId, categoryInfoByCategoryName) {
        def transactions = []
        def interestRatesByKidAndBankSuffix = [
                "$jack": ["$spendBankSuffix": 2.0, "$saveBankSuffix": 1.0, "$giveBankSuffix": 0.0],
                "$evan": ["$spendBankSuffix": 2.0, "$saveBankSuffix": 1.0, "$giveBankSuffix": 0.0],
                "$emily": ["$spendBankSuffix": 2.0, "$saveBankSuffix": 1.0, "$giveBankSuffix": 0.0]
        ]
        log.info("Interest Rate Configuration: ${interestRatesByKidAndBankSuffix}")
        kidsWithSimpleAccounts.each { kid ->
            bankSuffixes.each { bankSuffix ->
                def catName = kid + bankSuffix
                def categoryInfo = categoryInfoByCategoryName[catName]
                def catId = categoryInfo.id
                def currentBalance = toDollars(categoryInfo.balance)
                log.info("className = ${interestRatesByKidAndBankSuffix.getClass().getSimpleName()}")
                log.info("size = ${interestRatesByKidAndBankSuffix.size()}")
				log.info("interestRatesByKidAndBankSuffix = $interestRatesByKidAndBankSuffix")
				log.info("Determining Interest Rate for kid: $kid and bankSuffix: $bankSuffix using: ${interestRatesByKidAndBankSuffix["$kid"]}")
				log.info("${interestRatesByKidAndBankSuffix.get("Evan")}")
				log.info("keys = ${interestRatesByKidAndBankSuffix.keySet()}")
                def interestRatesForKid = interestRatesByKidAndBankSuffix.find { k, v -> k == kid }.getValue()
                log.info("interestRatesForKid = $interestRatesForKid")
				def interestRatePercent = interestRatesForKid.find{ k, v -> k == bankSuffix }.getValue()
				log.info("Found interest rate: $interestRatePercent")
                def interest = ((interestRatePercent / 100.0) * currentBalance)
                log.info("Calculated Interest: ${interest}")
                def roundedInterest = interest.round(new MathContext(2))
                log.info("Rounded Interest: ${roundedInterest}")
                log.info("${roundedInterest * 1000}")
                def interestInMilliUnits = toMilliUnits(roundedInterest)
				if(interest > 0) {
					def transaction = [
						account_id: accountId,
						date: dateFormat.format(transactionDate),
						amount: interestInMilliUnits,
						payee_name: "$catName Interest",
						category_id: catId,
						memo: "Interest",
						approved: true
					]
					transactions.add(transaction)
				}
            }
        }
        return transactions
    }

    def generateNewAllowanceTransactionsForSimpleAccounts(accountId, categoryInfoByCategoryName) {
        def transactions = []
        kidsWithSimpleAccounts.each { kid ->
            bankSuffixes.each { bankSuffix ->
                def catName = kid + bankSuffix
                def categoryInfo = categoryInfoByCategoryName[catName]
                def catId = categoryInfo.id
                def allowance = allowanceRates.find { k, v -> k == bankSuffix }.getValue()
                def allowanceInMilliUnits = toMilliUnits(allowance)
                def transaction = [
                        account_id: accountId,
                        date: dateFormat.format(transactionDate),
                        amount: allowanceInMilliUnits,
                        payee_name: "To $catName",
                        category_id: catId,
                        memo: "Allowance",
                        approved: true
                ]
                transactions.add(transaction)
            }
        }
        return transactions

    }

    def generateInterestTransactionsForAdvancedAccounts(accountId, categoryInfoByCategoryName) {
        def transactions = []

        log.info("Interest Rate Configuration: ${interestRatesByAccountType}")
        kidsWithAdvancedAccounts.each { kid ->
            def categoriesToProcess = categoryInfoByCategoryName.findAll { String catName, category ->
                //log.info("catName: $catName")
                def catNameIncludesKid = catName.contains(kid)
                if(!catNameIncludesKid) {
                    return false
                }
                boolean categoryIsAccount = false
                for(String accountType : interestRatesByAccountType.keySet()) {
                    if(catName.contains(accountType)) {
                        categoryIsAccount = true;
                    }
                }
                return categoryIsAccount;
            }

            log.info("Categories to Process: ${categoriesToProcess.size()}: ${categoriesToProcess.keySet().join(", ")}");

            categoriesToProcess.each { String catName, categoryInfo ->
                String accountTypeName = null
                for(String accountType : interestRatesByAccountType.keySet()) {
                    if(catName.contains(accountType)) {
                        accountTypeName = accountType
                    }
                }
                if(accountTypeName == null) {
                    throw new IllegalArgumentException("Couldn't Find Account Type for category: $catName")
                }

                log.info("Processing Account: ${catName}")
                def skip = false
                def interestRatePercent = interestRatesByAccountType[accountTypeName]
                if(accountTypeName.startsWith("CD")) {
                    def maturityDateStr = catName.split(" ")[-1]
                    log.info("Processing CD With Maturity Date: $maturityDateStr")
                    def maturityDate = cdDateFormat.parse(maturityDateStr)
                    if(transactionDate.after(maturityDate)) {
                        skip = true
                        log.warn("Skipping Account: ${catName} because transactionDate: ${cdDateFormat.format(transactionDate)} is after the maturity date: ${cdDateFormat.format(maturityDate)}")
                    }
                }
                if(!skip) {
                    def currentBalance = toDollars(categoryInfo.balance)
                    def interest = ((interestRatePercent / 100.0) * currentBalance)
                    log.info("Calculated Interest: ${interest}")
                    def roundedInterest = interest.round(new MathContext(3))
                    log.info("Rounded Interest: ${roundedInterest}")
                    log.info("${roundedInterest * 1000}")
                    def interestInMilliUnits = toMilliUnits(roundedInterest)
                    def catId = categoryInfo.id
                    if(interest > 0) {
                        log.info("Account: $catName produced: ${roundedInterest} on ${currentBalance} at rate: ${interestRatePercent}")
                        def transaction = [
                                account_id: accountId,
                                date: dateFormat.format(transactionDate),
                                amount: interestInMilliUnits,
                                payee_name: "$catName Interest",
                                category_id: catId,
                                memo: "Interest",
                                approved: true
                        ]
                        transactions.add(transaction)
                    }

                }
            }
        };
        return transactions
    }


    def generateNewAllowanceTransactionsForAdvancedAccounts(accountId, categoryInfoByCategoryName) {
        def transactions = []
        kidsWithAdvancedAccounts.each { kid ->
            def categoryNameToDepositAmount = advancedAllowanceDeposits[kid]
            categoryNameToDepositAmount.each { catName, allowance ->
                def categoryInfo = categoryInfoByCategoryName[catName]
                def catId = categoryInfo.id
                def allowanceInMilliUnits = toMilliUnits(allowance)
                def transaction = [
                        account_id : accountId,
                        date       : dateFormat.format(transactionDate),
                        amount     : allowanceInMilliUnits,
                        payee_name : "To $catName",
                        category_id: catId,
                        memo       : "Allowance",
                        approved   : true
                ]
                transactions.add(transaction)

            }

        }

        return transactions

    }


    def generateOffsettingTransaction(accountId, transactionsForAllowanceAndInterest, categoryInfoByCategoryName) {
        def totalMilliUnits = transactionsForAllowanceAndInterest.collect {t -> t.amount}.sum()
        def allowanceCategoryId = categoryInfoByCategoryName["Allowance"].id
        return [
                account_id: accountId,
                date: dateFormat.format(transactionDate),
                amount: -totalMilliUnits,
                payee_name: "Allowance ${kidsWithSimpleAccounts.join(", ")}",
                category_id: allowanceCategoryId,
                memo: "Allowance and Interest combined",
                approved: true
        ]
    }

    def generateGiveBankTransactions(accountId, categoryInfoByCategoryName) {
        def allowanceCategoryId = categoryInfoByCategoryName["Allowance"].id
        return kidsWithSimpleAccounts.collect { kid ->
            [
                account_id: accountId,
                date: dateFormat.format(transactionDate),
                amount: -1 * toMilliUnits(giveBankRate),
                payee_name: "Allowance $kid (Give)",
                category_id: allowanceCategoryId,
                memo: "To $kid Give Bank",
                approved: true
            ]
        }
    }

    def generateNonInterestBearingTransactions(accountId, categoryInfoByCategoryName) {
        def allowanceCategoryId = categoryInfoByCategoryName["Allowance"].id
        def allowanceRateValues = allowanceRates.values()
        def amount = giveBankRate + allowanceRateValues.sum()
        return kidsWithoutInterest.collect { kid ->
            [
                    account_id: accountId,
                    date: dateFormat.format(transactionDate),
                    amount: -1 * toMilliUnits(amount),
                    payee_name: "Allowance $kid",
                    category_id: allowanceCategoryId,
                    memo: "To $kid Piggy Banks",
                    approved: true
            ]
        }
    }



    def getAccountId(accountName) {
        def r = ynabClient.get {
            request.uri.path = "/v1/budgets/${budgetId}/accounts"
        }
        def account = r.data.accounts.find { a -> a.name == accountName}
        return account?.id
    }

    def getCategoryInfoByCategoryName() {
        def path = "/v1/budgets/${budgetId}/categories"
        def r = ynabClient.get {
            request.uri.path = path
        }
        def categoryInfoByCategoryName = [:]
        r.data.category_groups.each { cg ->
            cg.categories.each { c ->
                categoryInfoByCategoryName[c.name] = c
            }
        }
        return categoryInfoByCategoryName
    }

    def toDollars(milliunits) {
        return milliunits / 1000.0
    }

    def toMilliUnits(dollars) {
        return (int) (dollars * 1000)
    }

    def getUser() {
        return ynabClient.get {
            request.uri.path = "/v1/user"
        }
    }

    def getBudgets() {
        return ynabClient.get {
            request.uri.path = "/v1/budgets"
        }
    }

    def dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    def getLatestBudgetId(budgetName) {
        def response = getBudgets()

        def budgets = []
        budgets.addAll(response.data.budgets)
        def sortedBudgets = budgets.findAll{ b -> b.name == budgetName }.toSorted { a, b ->
            def bDate = dateFormat.parse(b.last_modified_on)
            def aDate = dateFormat.parse(a.last_modified_on)
            bDate.getTime() <=> aDate.getTime()
        }
        log.info("Sorted Budgets Found: ${sortedBudgets.collect { b -> b.last_modified_on + " " + b.name + " "  + b.id }}")
        return sortedBudgets[0].id
    }
}

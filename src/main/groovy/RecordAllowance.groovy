import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import groovyx.net.http.HttpBuilder
import org.apache.commons.lang3.StringUtils

import java.text.SimpleDateFormat

/**
 *
 */
@Slf4j
class RecordAllowance {

    def spendBankSuffix = " Spend Bank"
    def saveBankSuffix = " Save Bank"
    def allowanceRates = [spendBankSuffix: 1, saveBankSuffix: 0.5]
    def giveBankRate = 0.5
    def bankSuffixes = [spendBankSuffix, saveBankSuffix]
    def jack = "Jack"
    def evan = "Evan"
    def emily = "Emily"
    def kidsWithoutInterest = [emily]
    def kids = [jack, evan]

    public static final void main(String[] args) {
        String accessToken = System.getenv("YNAB_ACCESS_TOKEN")
        if(StringUtils.isBlank(accessToken)) {
            throw new IllegalArgumentException("environment variable YNAB_ACCESS_TOKEN must be set")
        }
        def ra = new RecordAllowance(accessToken)

        def categoryInfo = ra.getCategoryInfoByCategoryName()

        def allowanceEscrowAccountId = ra.getAccountId("Allowance Escrow")

        log.info("Account Id for Allowance Escrow: $allowanceEscrowAccountId")

        def transactions = []

        def transactionsThatNeedOffsetting = []

        transactionsThatNeedOffsetting.addAll(ra.generateInterestTransactions(allowanceEscrowAccountId, categoryInfo))
        transactionsThatNeedOffsetting.addAll(ra.generateNewAllowanceTransactions(allowanceEscrowAccountId, categoryInfo))

        transactions.addAll(transactionsThatNeedOffsetting)
        transactions.addAll(ra.generateGiveBankTransactions(allowanceEscrowAccountId, categoryInfo))

        transactions.addAll(ra.generateOffsettingTransaction(allowanceEscrowAccountId, transactionsThatNeedOffsetting, categoryInfo))

        transactions.addAll(ra.generateNonInterestBearingTransactions(allowanceEscrowAccountId, categoryInfo))


        log.info("Transactions to add: ${JsonOutput.prettyPrint(JsonOutput.toJson(transactions))}")

        def postedTransactions = ra.postTransactions(transactions)
        log.info("Successfully posted the following transactions: ${JsonOutput.prettyPrint(JsonOutput.toJson(postedTransactions))}")

    }

    def accessToken = null
    def ynabClient = null
    def budgetId = null

    public RecordAllowance(String accessToken) {
        this.accessToken = accessToken
        log.info("Using accessToken: ${accessToken}")
        ynabClient = HttpBuilder.configure {
            request.uri = "https://api.youneedabudget.com"
            request.headers['Authorization'] = "Bearer ${this.accessToken}"
            request.headers['Accept'] = "application/json"
        }

        budgetId = getLatestBudgetId()

        log.info("Most Recent Budget ID: $budgetId")

    }

    public static final float interestRatePercent = 2.0

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

    def generateInterestTransactions(accountId, categoryInfoByCategoryName) {
        def transactions = []
        kids.each { kid ->
            bankSuffixes.each { bankSuffix ->
                def catName = kid + bankSuffix
                def categoryInfo = categoryInfoByCategoryName[catName]
                def catId = categoryInfo.id
                def currentBalance = toDollars(categoryInfo.balance)
                def interest = ((interestRatePercent / 100.0) * currentBalance).round(2)
                def interestInMilliUnits = toMilliUnits(interest)

                def transaction = [
                    account_id: accountId,
                    date: dateFormat.format(new Date()),
                    amount: interestInMilliUnits,
                    payee_name: "$catName Interest",
                    category_id: catId,
                    memo: "Interest",
                    approved: true
                ]
                transactions.add(transaction)
            }
        }
        return transactions
    }

    def generateNewAllowanceTransactions(accountId, categoryInfoByCategoryName) {
        def transactions = []
        kids.each { kid ->
            bankSuffixes.each { bankSuffix ->
                def catName = kid + bankSuffix
                def categoryInfo = categoryInfoByCategoryName[catName]
                def catId = categoryInfo.id
                def allowance = bankSuffix.contains("Spend") ? 1 : 0.50
                def allowanceInMilliUnits = toMilliUnits(allowance)
                def transaction = [
                        account_id: accountId,
                        date: dateFormat.format(new Date()),
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

    def generateOffsettingTransaction(accountId, transactionsForAllowanceAndInterest, categoryInfoByCategoryName) {
        def totalMilliUnits = transactionsForAllowanceAndInterest.collect {t -> t.amount}.sum()
        def allowanceCategoryId = categoryInfoByCategoryName["Allowance"].id
        return [
                account_id: accountId,
                date: dateFormat.format(new Date()),
                amount: -totalMilliUnits,
                payee_name: "Allowance Jack and Evan",
                category_id: allowanceCategoryId,
                memo: "Allowance and Interest combined",
                approved: true
        ]
    }

    def generateGiveBankTransactions(accountId, categoryInfoByCategoryName) {
        def allowanceCategoryId = categoryInfoByCategoryName["Allowance"].id
        return kids.collect { kid ->
            [
                account_id: accountId,
                date: dateFormat.format(new Date()),
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
                    date: dateFormat.format(new Date()),
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

    def getLatestBudgetId() {
        def response = getBudgets()

        def budgets = []
        budgets.addAll(response.data.budgets)
        def sortedBudgets = budgets.toSorted { a, b ->
            def bDate = dateFormat.parse(b.last_modified_on)
            def aDate = dateFormat.parse(a.last_modified_on)
            bDate.getTime() <=> aDate.getTime()
        }
        log.info("Sorted Budgets Found: ${sortedBudgets.collect { b -> b.last_modified_on + " " + b.name + " "  + b.id }}")
        return sortedBudgets[0].id
    }
}

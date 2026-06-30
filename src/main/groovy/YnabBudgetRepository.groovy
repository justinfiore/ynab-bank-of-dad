import java.text.SimpleDateFormat

class YnabBudgetRepository {
    private final YnabHttpClient ynabClient
    private final SimpleDateFormat budgetTimestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    YnabBudgetRepository(YnabHttpClient ynabClient) {
        this.ynabClient = ynabClient
    }

    List<BudgetSummary> getBudgets() {
        def response = ynabClient.getJson('/v1/budgets')
        response.data.budgets.collect { budget ->
            new BudgetSummary(
                budget.id as String,
                budget.name as String,
                budgetTimestampFormat.parse(budget.last_modified_on as String)
            )
        }
    }

    String getLatestBudgetId(String budgetName) {
        def matchingBudgets = getBudgets().findAll { it.name == budgetName }
            .sort { a, b -> b.lastModifiedOn.time <=> a.lastModifiedOn.time }
        if (matchingBudgets.isEmpty()) {
            throw new IllegalStateException("Could not find budget named '${budgetName}'")
        }
        matchingBudgets.first().id
    }

    String getAccountId(String budgetId, String accountName) {
        def response = ynabClient.getJson("/v1/budgets/${budgetId}/accounts")
        def account = response.data.accounts.find { it.name == accountName }
        if (account == null) {
            throw new IllegalStateException("Could not find account named '${accountName}' in budget '${budgetId}'")
        }
        account.id
    }

    Map<String, CategorySnapshot> getCategoryInfoByCategoryName(String budgetId) {
        def response = ynabClient.getJson("/v1/budgets/${budgetId}/categories")
        Map<String, CategorySnapshot> categoriesByName = [:]
        response.data.category_groups.each { group ->
            group.categories.each { category ->
                categoriesByName[category.name] = new CategorySnapshot(
                    category.id as String,
                    category.name as String,
                    (category.balance ?: 0) as Integer
                )
            }
        }
        categoriesByName
    }

    def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
        ynabClient.postJson("/v1/budgets/${budgetId}/transactions/bulk", [transactions: transactions])
    }

    def getUser() {
        ynabClient.getJson('/v1/user')
    }
}

import java.text.SimpleDateFormat
import java.time.LocalDate

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

    List<ParentTransactionEvent> getTransactions(String budgetId, int lookbackDays, Integer lastServerKnowledge = null) {
        String sinceDate = LocalDate.now().minusDays(lookbackDays as long).toString()
        String path = "/v1/budgets/${budgetId}/transactions?since_date=${sinceDate}"
        if (lastServerKnowledge != null) {
            path += "&last_knowledge_of_server=${lastServerKnowledge}"
        }
        def response = ynabClient.getJson(path)
        List transactions = (response?.data?.transactions ?: []) as List
        transactions.collect { transaction ->
            new ParentTransactionEvent(
                transaction.id as String,
                transaction.date as String,
                (transaction.amount ?: 0) as Integer,
                transaction.memo as String,
                transaction.approved as Boolean,
                (response?.data?.server_knowledge ?: transaction.server_knowledge ?: 0) as Integer,
                transaction.category_id as String,
                transaction.category_name as String,
                ((transaction.subtransactions ?: []) as List).collect { subtransaction ->
                    new ParentSubtransactionEvent(
                        subtransaction.id as String,
                        subtransaction.transaction_id as String,
                        (subtransaction.amount ?: 0) as Integer,
                        subtransaction.memo as String,
                        subtransaction.category_id as String,
                        subtransaction.category_name as String
                    )
                }
            )
        }
    }

    List<MoneyMovementEvent> getMoneyMovements(String budgetId, int lookbackDays) {
        def response = ynabClient.getJson("/v1/budgets/${budgetId}/money_movements")
        LocalDate threshold = LocalDate.now().minusDays(lookbackDays as long)
        List movements = (response?.data?.money_movements ?: []) as List
        movements.collect { movement ->
            new MoneyMovementEvent(
                movement.id as String,
                movement.money_movement_group_id as String,
                deriveMovementDate(movement),
                movement.from_category_id as String,
                movement.to_category_id as String,
                (movement.amount ?: 0) as Integer
            )
        }.findAll { MoneyMovementEvent movement ->
            LocalDate.parse(movement.eventDate) >= threshold
        }
    }

    Integer getLatestServerKnowledge(String budgetId) {
        def response = ynabClient.getJson("/v1/budgets/${budgetId}")
        (response?.data?.budget?.server_knowledge ?: response?.data?.server_knowledge) as Integer
    }

    void updateTransactionCursor(String budgetId, List<ParentTransactionEvent> transactions) {
        // Cursor persistence is handled by the sync state store; repository exposure kept for API symmetry.
    }

    def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
        ynabClient.postJson("/v1/budgets/${budgetId}/transactions/bulk", [transactions: transactions])
    }

    def getUser() {
        ynabClient.getJson('/v1/user')
    }

    private static String deriveMovementDate(def movement) {
        String movedAt = movement.moved_at as String
        if (movedAt) {
            return movedAt.substring(0, 10)
        }
        String month = movement.month as String
        if (month) {
            return "${month}-01"
        }
        LocalDate.now().toString()
    }
}

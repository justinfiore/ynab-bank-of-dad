package ynabbankofdad.ynab

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import ynabbankofdad.model.*
import ynabbankofdad.sync.model.*

import java.text.SimpleDateFormat
import java.time.LocalDate

@Slf4j
class YnabBudgetRepository {
    private final YnabHttpClient ynabClient
    private final SimpleDateFormat budgetTimestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    YnabBudgetRepository(YnabHttpClient ynabClient) {
        this.ynabClient = ynabClient
    }

    List<BudgetSummary> getBudgets() {
        def response = ynabClient.getJson('/v1/plans')
        List budgets = (response?.data?.plans ?: []) as List
        log.debug('Fetched {} budgets from YNAB /v1/plans', budgets.size())
        budgets.collect { budget ->
            new BudgetSummary(
                budget.id as String,
                budget.name as String,
                budgetTimestampFormat.parse(budget.last_modified_on as String)
            )
        }
    }

    String getLatestBudgetId(String budgetName) {
        def allBudgets = getBudgets()
        def allBudgetNames = allBudgets.collect { it.name }
        def matchingBudgets = allBudgets.findAll { it.name == budgetName }
            .sort { a, b -> b.lastModifiedOn.time <=> a.lastModifiedOn.time }
        log.debug(
            "Budget lookup for '{}' saw {} total budgets and {} matching budgets: {}",
            budgetName,
            allBudgets.size(),
            matchingBudgets.size(),
            allBudgetNames
        )
        if (matchingBudgets.isEmpty()) {
            throw new IllegalStateException("Could not find budget named '${budgetName}'. Available budget names: ${allBudgetNames}")
        }
        matchingBudgets.first().id
    }

    String getAccountId(String budgetId, String accountName) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}/accounts")
        List accounts = (response?.data?.accounts ?: []) as List
        log.debug("Fetched {} accounts from YNAB for budget '{}'", accounts.size(), budgetId)
        def account = accounts.find { it.name == accountName }
        if (account == null) {
            throw new IllegalStateException("Could not find account named '${accountName}' in budget '${budgetId}'")
        }
        account.id
    }

    Map<String, CategorySnapshot> getCategoryInfoByCategoryName(String budgetId) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}/categories")
        List categoryGroups = (response?.data?.category_groups ?: []) as List
        int categoryCount = categoryGroups.sum { ((it.categories ?: []) as List).size() } ?: 0
        log.debug("Fetched {} category groups and {} categories from YNAB for budget '{}'", categoryGroups.size(), categoryCount, budgetId)
        Map<String, CategorySnapshot> categoriesByName = [:]
        categoryGroups.each { group ->
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
        String path = "/v1/plans/${budgetId}/transactions?since_date=${sinceDate}"
        if (lastServerKnowledge != null) {
            path += "&last_knowledge_of_server=${lastServerKnowledge}"
        }
        def response = ynabClient.getJson(path)
        List transactions = (response?.data?.transactions ?: []) as List
        Integer responseServerKnowledge = (response?.data?.server_knowledge ?: 0) as Integer
        log.debug(
            "Fetched {} transactions from YNAB for budget '{}' since {} with last_knowledge_of_server={} and response server_knowledge={}",
            transactions.size(),
            budgetId,
            sinceDate,
            lastServerKnowledge,
            responseServerKnowledge
        )
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
        def response = ynabClient.getJson("/v1/plans/${budgetId}/money_movements")
        LocalDate threshold = LocalDate.now().minusDays(lookbackDays as long)
        List movements = (response?.data?.money_movements ?: []) as List
        List<MoneyMovementEvent> mappedMovements = movements.collect { movement ->
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
        log.debug(
            "Fetched {} money movements from YNAB for budget '{}' and retained {} within {} days",
            movements.size(),
            budgetId,
            mappedMovements.size(),
            lookbackDays
        )
        mappedMovements
    }

    Integer getLatestServerKnowledge(String budgetId) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}")
        Integer serverKnowledge = (response?.data?.plan?.server_knowledge ?: response?.data?.server_knowledge) as Integer
        log.debug("Fetched latest server_knowledge={} for budget '{}'", serverKnowledge, budgetId)
        serverKnowledge
    }

    Integer latestServerKnowledge(List<ParentTransactionEvent> transactions) {
        List<Integer> knowledgeValues = transactions.collect { it.serverKnowledge }.findAll { it != null }
        Integer latestKnowledge = knowledgeValues ? knowledgeValues.max() : null
        log.debug('Derived latest server_knowledge={} from {} transactions', latestKnowledge, transactions.size())
        latestKnowledge
    }

    def postTransactions(String budgetId, List<Map<String, Object>> transactions) {
        YnabHttpResponse response = ynabClient.postJsonWithMetadata("/v1/plans/${budgetId}/transactions/bulk", [transactions: transactions])
        log.debug(
            "Posted {} transactions to budget '{}' and received status {} with body {}",
            transactions.size(),
            budgetId,
            response.statusCode,
            response.bodyText == null ? 'null' : JsonOutput.prettyPrint(JsonOutput.toJson(YnabLogFormatter.formatAmounts(response.body)))
        )
        response.body
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

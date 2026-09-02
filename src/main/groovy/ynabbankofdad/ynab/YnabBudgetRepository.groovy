package ynabbankofdad.ynab

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import ynabbankofdad.model.*
import ynabbankofdad.sync.model.*

import java.text.SimpleDateFormat
import java.time.LocalDate

@Slf4j
class YnabBudgetRepository {
    private static final Set<String> SUPPORTED_TRANSACTION_UPDATE_FIELDS = [
        'account_id', 'date', 'amount', 'payee_id', 'payee_name', 'category_id', 'memo',
        'cleared', 'approved', 'flag_color', 'subtransactions'
    ] as Set

    // Live YNAB SaveAccountType enum (OpenAPI v1.86.0). Checking is on-budget;
    // otherAsset is the tracking / off-budget "Asset (e.g. Investment)" type.
    static final String ON_BUDGET_ACCOUNT_TYPE = 'checking'
    static final String OFF_BUDGET_ACCOUNT_TYPE = 'otherAsset'
    private static final Set<String> SAVE_ACCOUNT_TYPES = [
        'checking', 'savings', 'cash', 'creditCard', 'otherAsset', 'otherLiability'
    ] as Set

    static String saveAccountTypeForOnBudget(boolean onBudget) {
        onBudget ? ON_BUDGET_ACCOUNT_TYPE : OFF_BUDGET_ACCOUNT_TYPE
    }

    private final YnabHttpClient ynabClient
    private final SimpleDateFormat budgetTimestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    YnabBudgetRepository(YnabHttpClient ynabClient) {
        this.ynabClient = ynabClient
    }

    List<BudgetSummary> getBudgets() {
        def response = ynabClient.getJson('/v1/plans')
        List budgets = (response?.data?.plans ?: []) as List
        log.debug('Fetched {} budgets from YNAB /v1/plans', budgets.size())
        List<BudgetSummary> summaries = budgets.collect { budget ->
            new BudgetSummary(
                budget.id as String,
                budget.name as String,
                budgetTimestampFormat.parse(budget.last_modified_on as String)
            )
        }
        summaries.each { BudgetSummary budget ->
            ynabClient.registerBudgetName(budget.id, budget.name)
        }
        summaries
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
        String accountId = findAccountId(budgetId, accountName)
        if (accountId == null) {
            throw new IllegalStateException("Could not find account named '${accountName}' in budget '${budgetId}'")
        }
        accountId
    }

    String findAccountId(String budgetId, String accountName) {
        accountIdByName(budgetId)[accountName]
    }

    Map<String, String> accountIdByName(String budgetId) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}/accounts")
        List accounts = (response?.data?.accounts ?: []) as List
        log.debug("Fetched {} accounts from YNAB for budget '{}'", accounts.size(), budgetId)
        Map<String, String> idsByName = [:]
        accounts.each { account ->
            if (account?.name && account.deleted != true && !idsByName.containsKey(account.name as String)) {
                idsByName[account.name as String] = account.id as String
            }
        }
        idsByName
    }

    Map createAccount(String budgetId, String name, String type) {
        if (!SAVE_ACCOUNT_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                "YNAB SaveAccount type '${type}' is not supported. Allowed: ${SAVE_ACCOUNT_TYPES.sort().join(', ')}")
        }
        YnabHttpResponse response = ynabClient.postJsonWithMetadata("/v1/plans/${budgetId}/accounts", [
            account: [name: name, type: type, balance: 0]
        ])
        log.debug(
            "Created account '{}' in budget '{}' and received status {} with body {}",
            name,
            budgetId,
            response.statusCode,
            response.bodyText == null ? 'null' : JsonOutput.prettyPrint(JsonOutput.toJson(YnabLogFormatter.formatAmounts(response.body)))
        )
        Map data = requireData(response.body, 'create account')
        if (!(data.account instanceof Map) || !data.account.id) {
            throw new IllegalStateException(
                "YNAB create account response for budget '${budgetId}', name '${name}' is missing account.id")
        }
        data.account as Map
    }

    Map<String, CategorySnapshot> getCategoryInfoByCategoryName(String budgetId) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}/categories")
        List categoryGroups = (response?.data?.category_groups ?: []) as List
        int categoryCount = categoryGroups.sum { ((it.categories ?: []) as List).size() } ?: 0
        log.debug("Fetched {} category groups and {} categories from YNAB for budget '{}'", categoryGroups.size(), categoryCount, budgetId)
        Map<String, CategorySnapshot> categoriesByName = [:]
        categoryGroups.each { group ->
            ((group.categories ?: []) as List).each { category ->
                categoriesByName[category.name] = new CategorySnapshot(
                    category.id as String,
                    category.name as String,
                    (category.balance ?: 0) as Integer
                )
            }
        }
        categoriesByName
    }

    TransactionDelta getTransactions(String budgetId, int lookbackDays, Integer lastServerKnowledge = null) {
        String sinceDate = LocalDate.now().minusDays(lookbackDays as long).toString()
        String path = lastServerKnowledge == null
            ? "/v1/plans/${budgetId}/transactions?since_date=${sinceDate}"
            : "/v1/plans/${budgetId}/transactions?last_knowledge_of_server=${lastServerKnowledge}"
        def response = ynabClient.getJson(path)
        Map data = requireData(response, 'transaction delta')
        if (!(data.transactions instanceof List) || data.server_knowledge == null) {
            throw new IllegalStateException("YNAB transaction delta for budget '${budgetId}' is missing transactions or server_knowledge")
        }
        List transactions = data.transactions as List
        Integer responseServerKnowledge = data.server_knowledge as Integer
        log.debug(
            "Fetched {} transactions from YNAB for budget '{}' with since_date={} last_knowledge_of_server={} and response server_knowledge={}",
            transactions.size(),
            budgetId,
            lastServerKnowledge == null ? sinceDate : null,
            lastServerKnowledge,
            responseServerKnowledge
        )
        List<ParentTransactionEvent> mappedTransactions = transactions.collect { transaction ->
            mapParentTransaction(transaction as Map, responseServerKnowledge)
        }
        new TransactionDelta(mappedTransactions, responseServerKnowledge)
    }

    ParentTransactionEvent getParentTransaction(String budgetId, String transactionId) {
        def response = ynabClient.getJson(transactionPath(budgetId, transactionId))
        Map data = requireData(response, 'parent transaction detail')
        if (!(data.transaction instanceof Map) || data.server_knowledge == null) {
            throw new IllegalStateException(
                "YNAB parent transaction detail for budget '${budgetId}', transaction '${transactionId}' " +
                    'is missing transaction or server_knowledge')
        }
        mapParentTransaction(data.transaction as Map, data.server_knowledge as Integer)
    }

    MoneyMovementSnapshot getMoneyMovements(String budgetId) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}/money_movements")
        Map data = requireData(response, 'money movement snapshot')
        if (!(data.money_movements instanceof List) || data.server_knowledge == null) {
            throw new IllegalStateException("YNAB money movement snapshot for budget '${budgetId}' is missing money_movements or server_knowledge")
        }
        List movements = data.money_movements as List
        List<MoneyMovementEvent> mappedMovements = movements.collect { movement ->
            new MoneyMovementEvent(
                movement.id as String,
                movement.money_movement_group_id as String,
                deriveMovementDate(movement),
                movement.from_category_id as String,
                movement.to_category_id as String,
                (movement.amount ?: 0) as Integer
            )
        }
        log.debug(
            "Fetched complete snapshot of {} money movements from YNAB for budget '{}' with server_knowledge={}",
            movements.size(),
            budgetId,
            data.server_knowledge
        )
        new MoneyMovementSnapshot(mappedMovements, data.server_knowledge as Integer)
    }

    Integer getLatestServerKnowledge(String budgetId) {
        def response = ynabClient.getJson("/v1/plans/${budgetId}")
        Integer serverKnowledge = (response?.data?.plan?.server_knowledge ?: response?.data?.server_knowledge) as Integer
        log.debug("Fetched latest server_knowledge={} for budget '{}'", serverKnowledge, budgetId)
        serverKnowledge
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

    ChildTransactionLookupResult getChildTransaction(String budgetId, String transactionId) {
        String path = transactionPath(budgetId, transactionId)
        YnabHttpResponse response = ynabClient.getJsonWithMetadata(path, [404] as Set)
        if (response.statusCode == 404) {
            return new ChildTransactionLookupResult(null, null)
        }
        ChildTransactionResult result = mapChildTransactionResponse(response.body, budgetId, transactionId, 'lookup')
        new ChildTransactionLookupResult(result.transaction, result.serverKnowledge)
    }

    ChildTransactionResult updateChildTransaction(String budgetId, String transactionId, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException('Child transaction update fields must not be empty')
        }
        Set<String> unsupported = fields.keySet().collect { it as String }.findAll {
            !SUPPORTED_TRANSACTION_UPDATE_FIELDS.contains(it)
        } as Set
        if (unsupported) {
            throw new IllegalArgumentException("Unsupported child transaction update fields: ${unsupported.sort().join(', ')}")
        }
        Map<String, Object> suppliedFields = fields.collectEntries { key, value -> [(key as String): value] }
        String path = transactionPath(budgetId, transactionId)
        YnabHttpResponse response = ynabClient.putJsonWithMetadata(path, [transaction: suppliedFields])
        mapChildTransactionResponse(response.body, budgetId, transactionId, 'update')
    }

    ChildTransactionResult recoverChildTransactionByImportId(String budgetId, String importId,
                                                               Map<String, Object> fields) {
        if (!importId) {
            throw new IllegalArgumentException('Child transaction recovery import ID is required')
        }
        Map<String, Object> suppliedFields = fields.findAll { key, ignored ->
            SUPPORTED_TRANSACTION_UPDATE_FIELDS.contains(key as String)
        }.collectEntries { key, value -> [(key as String): value] }
        suppliedFields.import_id = importId
        String path = "/v1/plans/${budgetId}/transactions"
        YnabHttpResponse response = ynabClient.patchJsonWithMetadata(path, [transactions: [suppliedFields]])
        Map data = requireData(response.body, 'child transaction import identity recovery')
        List transactions = data.transactions instanceof List ? data.transactions as List : []
        if (data.server_knowledge == null || transactions.size() != 1 || !transactions.first()?.id) {
            throw new IllegalStateException(
                "YNAB child transaction import identity recovery for budget '${budgetId}', import_id '${importId}' " +
                    'did not return exactly one transaction with an ID')
        }
        mapChildTransaction(data.transactions.first() as Map, data.server_knowledge as Integer,
            budgetId, importId, 'import identity recovery')
    }

    ChildTransactionDeleteResult deleteChildTransaction(String budgetId, String transactionId) {
        String path = transactionPath(budgetId, transactionId)
        YnabHttpResponse response = ynabClient.deleteJsonWithMetadata(path, [404] as Set)
        if (response.statusCode == 404) {
            return new ChildTransactionDeleteResult(null, null, true)
        }
        ChildTransactionResult result = mapChildTransactionResponse(response.body, budgetId, transactionId, 'delete')
        new ChildTransactionDeleteResult(result.transaction, result.serverKnowledge, false)
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

    private static String transactionPath(String budgetId, String transactionId) {
        "/v1/plans/${budgetId}/transactions/${transactionId}"
    }

    private static ParentTransactionEvent mapParentTransaction(Map transaction, Integer serverKnowledge) {
        new ParentTransactionEvent(
            transaction.id as String,
            transaction.date as String,
            (transaction.amount ?: 0) as Integer,
            transaction.memo as String,
            transaction.approved as Boolean,
            (serverKnowledge ?: transaction.server_knowledge) as Integer,
            transaction.category_id as String,
            transaction.category_name as String,
            ((transaction.subtransactions ?: []) as List).collect { subtransaction ->
                new ParentSubtransactionEvent(
                    subtransaction.id as String,
                    subtransaction.transaction_id as String,
                    (subtransaction.amount ?: 0) as Integer,
                    subtransaction.memo as String,
                    subtransaction.category_id as String,
                    subtransaction.category_name as String,
                    subtransaction.deleted as Boolean,
                    subtransaction.payee_id as String,
                    subtransaction.payee_name as String
                )
            },
            transaction.payee_id as String,
            transaction.payee_name as String,
            transaction.deleted as Boolean
        )
    }

    private static Map requireData(def response, String responseName) {
        if (!(response?.data instanceof Map)) {
            throw new IllegalStateException("YNAB ${responseName} response is missing data")
        }
        response.data as Map
    }

    private static ChildTransactionResult mapChildTransactionResponse(
        def response,
        String budgetId,
        String transactionId,
        String operation
    ) {
        Map data = requireData(response, "child transaction ${operation}")
        if (!(data.transaction instanceof Map) || data.server_knowledge == null) {
            throw new IllegalStateException(
                "YNAB child transaction ${operation} response for budget '${budgetId}', transaction '${transactionId}' " +
                    'is missing transaction or server_knowledge'
            )
        }
        Map transaction = data.transaction as Map
        if (!transaction.id) {
            throw new IllegalStateException(
                "YNAB child transaction ${operation} response for budget '${budgetId}', transaction '${transactionId}' is missing transaction.id"
            )
        }
        mapChildTransaction(transaction, data.server_knowledge as Integer, budgetId, transactionId, operation)
    }

    private static ChildTransactionResult mapChildTransaction(Map transaction, Integer serverKnowledge,
                                                               String budgetId, String transactionId,
                                                               String operation) {
        if (!transaction.id) {
            throw new IllegalStateException(
                "YNAB child transaction ${operation} response for budget '${budgetId}', transaction '${transactionId}' is missing transaction.id"
            )
        }
        new ChildTransactionResult(
            new ChildTransaction(
                transaction.id as String,
                transaction.account_id as String,
                transaction.date as String,
                transaction.amount as Integer,
                transaction.payee_id as String,
                transaction.payee_name as String,
                transaction.category_id as String,
                transaction.memo as String,
                transaction.cleared as String,
                transaction.approved as Boolean,
                transaction.flag_color as String,
                transaction.deleted as Boolean
            ),
            serverKnowledge
        )
    }
}

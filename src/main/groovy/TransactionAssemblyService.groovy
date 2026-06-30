class TransactionAssemblyService {
    private final String transactionDate
    private final AllowanceCalculationService calculationService

    TransactionAssemblyService(String transactionDate, AllowanceCalculationService calculationService) {
        this.transactionDate = transactionDate
        this.calculationService = calculationService
    }

    List<TransactionDraft> generateInterestTransactionsForAdvancedAccounts(String accountId,
                                                                           Map<String, CategorySnapshot> categoryInfoByCategoryName,
                                                                           List<String> kidsWithAdvancedAccounts,
                                                                           List<String> accountTypes) {
        List<TransactionDraft> transactions = []
        kidsWithAdvancedAccounts.each { String kid ->
            def categoriesToProcess = categoryInfoByCategoryName.findAll { String categoryName, CategorySnapshot snapshot ->
                categoryName.contains(kid) && accountTypes.any { categoryName.contains(it) }
            }
            categoriesToProcess.each { String categoryName, CategorySnapshot category ->
                String accountTypeName = calculationService.resolveAccountType(categoryName)
                if (accountTypeName == null) {
                    throw new IllegalArgumentException("Couldn't Find Account Type for category: $categoryName")
                }
                if (calculationService.isMaturedCd(accountTypeName, categoryName)) {
                    return
                }
                Number interestRatePercent = calculationService.resolveInterestRatePercent(accountTypeName, categoryName)
                Number currentBalance = calculationService.toDollars(category.balance)
                BigDecimal roundedInterest = calculationService.calculateInterest(interestRatePercent, currentBalance)
                Integer interestInMilliUnits = calculationService.toMilliUnits(roundedInterest)
                if (interestInMilliUnits > 0) {
                    transactions << new TransactionDraft(
                        accountId,
                        transactionDate,
                        interestInMilliUnits,
                        "$categoryName Interest",
                        category.id,
                        'Interest',
                        true
                    )
                }
            }
        }
        transactions
    }

    List<TransactionDraft> generateNewAllowanceTransactionsForAdvancedAccounts(String accountId,
                                                                               Map<String, CategorySnapshot> categoryInfoByCategoryName,
                                                                               List<String> kidsWithAdvancedAccounts,
                                                                               Map<String, Map<String, Number>> advancedAllowanceDeposits) {
        List<TransactionDraft> transactions = []
        kidsWithAdvancedAccounts.each { String kid ->
            advancedAllowanceDeposits[kid].each { String categoryName, Number allowance ->
                CategorySnapshot category = categoryInfoByCategoryName[categoryName]
                if (category == null) {
                    throw new IllegalStateException("Missing category info for advanced allowance category: ${categoryName}")
                }
                transactions << new TransactionDraft(
                    accountId,
                    transactionDate,
                    calculationService.toMilliUnits(allowance),
                    "To $categoryName",
                    category.id,
                    'Allowance',
                    true
                )
            }
        }
        transactions
    }

    TransactionDraft generateOffsettingTransaction(String accountId,
                                                   List<TransactionDraft> transactionsForAllowanceAndInterest,
                                                   Map<String, CategorySnapshot> categoryInfoByCategoryName,
                                                   List<String> kidsWithSimpleAccounts) {
        Integer totalMilliUnits = transactionsForAllowanceAndInterest.sum { it.amount } as Integer
        CategorySnapshot allowanceCategory = categoryInfoByCategoryName['Allowance']
        if (allowanceCategory == null) {
            throw new IllegalStateException('Missing category info for Allowance')
        }
        new TransactionDraft(
            accountId,
            transactionDate,
            -totalMilliUnits,
            "Allowance ${kidsWithSimpleAccounts.join(', ')}",
            allowanceCategory.id,
            'Allowance and Interest combined',
            true
        )
    }

    List<TransactionDraft> generateNonInterestBearingTransactions(String accountId,
                                                                  Map<String, CategorySnapshot> categoryInfoByCategoryName,
                                                                  List<String> kidsWithoutInterest,
                                                                  Map<String, Number> allowanceRates,
                                                                  Number giveBankRate) {
        CategorySnapshot allowanceCategory = categoryInfoByCategoryName['Allowance']
        Number amount = giveBankRate + allowanceRates.values().sum()
        kidsWithoutInterest.collect { String kid ->
            new TransactionDraft(
                accountId,
                transactionDate,
                -calculationService.toMilliUnits(amount),
                "Allowance $kid",
                allowanceCategory.id,
                "To $kid Piggy Banks",
                true
            )
        }
    }
}

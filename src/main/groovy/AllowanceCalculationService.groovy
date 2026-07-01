import java.math.MathContext
import java.text.SimpleDateFormat

class AllowanceCalculationService {
    static final SimpleDateFormat CD_DATE_FORMAT = new SimpleDateFormat("MM/dd/yy")
    static final SimpleDateFormat YYYYMMDD_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd")

    private final Date transactionDate
    private final Map<String, Map<String, Number>> interestRatesByAccountTypeAndDate
    private final List<String> accountTypes

    AllowanceCalculationService(Date transactionDate,
                                Map<String, Map<String, Number>> interestRatesByAccountTypeAndDate,
                                List<String> accountTypes) {
        this.transactionDate = transactionDate
        this.interestRatesByAccountTypeAndDate = interestRatesByAccountTypeAndDate
        this.accountTypes = accountTypes
    }

    int toMilliUnits(Number dollars) {
        (int) (dollars * 1000)
    }

    double toDollars(Number milliunits) {
        milliunits / 1000.0
    }

    Map<String, Number> findInterestRatesForDate(Date date) {
        for (Map.Entry<Date, Map<String, Number>> entry : getInterestRatesByDate().entrySet()) {
            if (date.before(entry.key) || date.equals(entry.key)) {
                return entry.value
            }
        }
        Map<String, Number> currentRates = getCurrentInterestRates()
        if (currentRates != null) {
            return currentRates
        }
        throw new IllegalStateException("Could not find interest rates for date: ${date}")
    }

    Date getCDOriginationDate(String categoryName, Date maturityDate) {
        Calendar cal = Calendar.getInstance()
        cal.setTime(maturityDate)
        if (categoryName.contains("6-Month")) {
            cal.add(Calendar.MONTH, -6)
        } else if (categoryName.contains("3-Month")) {
            cal.add(Calendar.MONTH, -3)
        } else if (categoryName.contains("2-Month")) {
            cal.add(Calendar.MONTH, -2)
        } else {
            throw new IllegalStateException("Could not calculate CD Origination Date from category: ${categoryName}")
        }
        cal.getTime()
    }

    BigDecimal calculateInterest(Number interestRatePercent, Number currentBalanceDollars) {
        (((interestRatePercent / 100.0) * currentBalanceDollars) as BigDecimal).round(new MathContext(3))
    }

    String resolveAccountType(String categoryName) {
        accountTypes.find { categoryName.contains(it) }
    }

    boolean isMaturedCd(String accountTypeName, String categoryName) {
        if (!accountTypeName?.startsWith("Gold CD")) {
            return false
        }
        Date maturityDate = CD_DATE_FORMAT.parse(categoryName.split(" ")[-1])
        transactionDate.after(maturityDate)
    }

    Number resolveInterestRatePercent(String accountTypeName, String categoryName) {
        Number interestRatePercent = getCurrentInterestRates()[accountTypeName]
        if (accountTypeName.startsWith("Gold CD")) {
            Date maturityDate = CD_DATE_FORMAT.parse(categoryName.split(" ")[-1])
            Date originationDate = getCDOriginationDate(categoryName, maturityDate)
            interestRatePercent = findInterestRatesForDate(originationDate)[accountTypeName]
        }
        interestRatePercent
    }

    private Map<Date, Map<String, Number>> getInterestRatesByDate() {
        TreeMap<Date, Map<String, Number>> sorted = new TreeMap<>()
        interestRatesByAccountTypeAndDate.each { String dateKey, Map<String, Number> rates ->
            Date effectiveDate = isCurrentRateKey(dateKey) ? transactionDate : YYYYMMDD_DATE_FORMAT.parse(dateKey)
            sorted[effectiveDate] = rates
        }
        sorted
    }

    private Map<String, Number> getCurrentInterestRates() {
        interestRatesByAccountTypeAndDate.find { String dateKey, Map<String, Number> rates ->
            isCurrentRateKey(dateKey)
        }?.value
    }

    private static boolean isCurrentRateKey(String dateKey) {
        dateKey?.equalsIgnoreCase('Current')
    }
}

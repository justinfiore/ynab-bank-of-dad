package ynabbankofdad.ynab

import java.math.RoundingMode

class YnabLogFormatter {

    static Object formatAmounts(Object value) {
        if (value instanceof Map) {
            return value.collectEntries { key, nestedValue ->
                [(key): key == 'amount' && nestedValue instanceof Number
                    ? formatAmount(nestedValue)
                    : formatAmounts(nestedValue)]
            }
        }
        if (value instanceof Collection) {
            return value.collect { formatAmounts(it) }
        }
        value
    }

    static String formatAmount(Number milliunits) {
        BigDecimal dollars = BigDecimal.valueOf(milliunits.longValue())
            .divide(BigDecimal.valueOf(1000L))
        String sign = dollars.signum() < 0 ? '-$' : '$'
        "${sign}${dollars.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()}"
    }
}

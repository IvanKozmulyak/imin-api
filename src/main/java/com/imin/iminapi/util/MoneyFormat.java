package com.imin.iminapi.util;

import java.util.Locale;
import java.util.Set;

public final class MoneyFormat {

    private MoneyFormat() {}

    // Stripe's zero-decimal currencies — amounts are stored in major units
    // directly (1 JPY = 1, not 100). Three-decimal currencies (BHD, JOD, KWD,
    // OMR, TND) charge in thousandths but are still stored as integers; we
    // don't sell into those today, so they're not in the table.
    private static final Set<String> ZERO_DECIMAL = Set.of(
        "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
        "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"
    );

    public static boolean isZeroDecimal(String currency) {
        return currency != null && ZERO_DECIMAL.contains(currency.toUpperCase(Locale.ROOT));
    }

    /** "12.34 EUR" / "1500 JPY" — minor units to display string. */
    public static String format(long minor, String currency) {
        String code = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        if (isZeroDecimal(code)) {
            return String.format(Locale.ROOT, "%d %s", minor, code).trim();
        }
        return String.format(Locale.ROOT, "%.2f %s", minor / 100.0, code).trim();
    }
}

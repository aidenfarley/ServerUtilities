package serverutils.lib.util;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class NumberFormatUtils {

    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL = ThreadLocal
            .withInitial(() -> createFormatter("#0.0"));
    private static final ThreadLocal<DecimalFormat> TWO_DECIMALS = ThreadLocal
            .withInitial(() -> createFormatter("#0.00"));
    private static final ThreadLocal<DecimalFormat> ROUNDED_ONE_DECIMAL = ThreadLocal
            .withInitial(() -> createFormatter("#0.0", RoundingMode.HALF_EVEN));

    private NumberFormatUtils() {}

    private static DecimalFormat createFormatter(String pattern) {
        return createFormatter(pattern, RoundingMode.DOWN);
    }

    private static DecimalFormat createFormatter(String pattern, RoundingMode roundingMode) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(roundingMode);
        return format;
    }

    public static String formatOneDecimal(double value) {
        String formatted = ONE_DECIMAL.get().format(value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }

    public static String formatFixedOneDecimal(double value) {
        return ONE_DECIMAL.get().format(value);
    }

    public static String formatRoundedOneDecimal(double value) {
        return ROUNDED_ONE_DECIMAL.get().format(value);
    }

    public static String formatTwoDecimals(double value) {
        String formatted = TWO_DECIMALS.get().format(value);
        return formatted.endsWith(".00") ? formatted.substring(0, formatted.length() - 3) : formatted;
    }
}

package serverutils.lib.util;

import java.util.UUID;

import javax.annotation.Nullable;

public final class UuidUtils {

    private UuidUtils() {}

    public static String toCompactString(@Nullable UUID id) {
        if (id == null) {
            return "";
        }

        long mostSignificant = id.getMostSignificantBits();
        long leastSignificant = id.getLeastSignificantBits();
        StringBuilder builder = new StringBuilder(32);
        appendDigits(builder, mostSignificant >> 32, 8);
        appendDigits(builder, mostSignificant >> 16, 4);
        appendDigits(builder, mostSignificant, 4);
        appendDigits(builder, leastSignificant >> 48, 4);
        appendDigits(builder, leastSignificant, 12);
        return builder.toString();
    }

    @Nullable
    public static UUID parse(@Nullable String value) {
        if (value == null || !(value.length() == 32 || value.length() == 36)) {
            return null;
        }

        try {
            if (value.indexOf('-') != -1) {
                return UUID.fromString(value);
            }

            StringBuilder builder = new StringBuilder(36);
            for (int i = 0; i < value.length(); i++) {
                builder.append(value.charAt(i));
                if (i == 7 || i == 11 || i == 15 || i == 19) {
                    builder.append('-');
                }
            }
            return UUID.fromString(builder.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void appendDigits(StringBuilder builder, long value, int digits) {
        long highBit = 1L << (digits * 4);
        String encoded = Long.toHexString(highBit | (value & (highBit - 1)));
        builder.append(encoded, 1, encoded.length());
    }
}

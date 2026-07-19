package serverutils.lib.util;

import java.util.Locale;
import java.util.regex.Pattern;

import serverutils.lib.io.Bits;

public final class IdentifierUtils {

    private static final Pattern NOT_SNAKE_CASE_PATTERN = Pattern.compile("[^a-z0-9_]");
    private static final Pattern REPEATING_UNDERSCORE_PATTERN = Pattern.compile("_{2,}");

    private IdentifierUtils() {}

    public static String toSnakeCase(String value) {
        return value.isEmpty() ? value
                : REPEATING_UNDERSCORE_PATTERN.matcher(
                        NOT_SNAKE_CASE_PATTERN.matcher(StringUtils.unformatted(value).toLowerCase(Locale.ROOT))
                                .replaceAll("_"))
                        .replaceAll("_");
    }

    public static String normalize(Object value, int flags) {
        String id = StringUtils.getRawID(value);
        if (flags == 0) {
            return id;
        }

        boolean fix = Bits.getFlag(flags, StringUtils.FLAG_ID_FIX);
        if (!fix && id.isEmpty() && !Bits.getFlag(flags, StringUtils.FLAG_ID_ALLOW_EMPTY)) {
            throw new NullPointerException("ID can't be empty!");
        }

        if (Bits.getFlag(flags, StringUtils.FLAG_ID_ONLY_LOWERCASE)
                || Bits.getFlag(flags, StringUtils.FLAG_ID_ONLY_UNDERLINE)) {
            String lowercase = id.toLowerCase(Locale.ROOT);
            if (fix) {
                id = lowercase;
            } else if (!id.equals(lowercase)) {
                throw new IllegalArgumentException("ID can't contain uppercase characters!");
            }
        }

        if (Bits.getFlag(flags, StringUtils.FLAG_ID_ONLY_UNDERLINE)) {
            boolean allowPeriod = Bits.getFlag(flags, 16);
            char[] chars = id.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (!(chars[i] == '.' && allowPeriod || StringUtils.isTextChar(chars[i], true))) {
                    if (fix) {
                        chars[i] = '_';
                    } else {
                        throw new IllegalArgumentException("ID contains invalid character: '" + chars[i] + "'!");
                    }
                }
            }
            id = new String(chars);
        }

        return id;
    }
}

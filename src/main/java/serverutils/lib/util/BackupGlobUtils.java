package serverutils.lib.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Applies a world folder name to a configured backup glob without treating the name itself as glob syntax. */
public final class BackupGlobUtils {

    private BackupGlobUtils() {}

    public static String substituteLiteralPath(String pattern, String worldName) {
        return pattern.replace("$WORLDNAME", worldName);
    }

    public static String substituteGlob(String pattern, String worldName) {
        return pattern.replace("$WORLDNAME", escapeGlobLiteral(worldName));
    }

    public static Path searchRoot(String pattern, String worldName) {
        int firstWildcardIndex = pattern.indexOf('*');
        if (firstWildcardIndex < 0) {
            return Paths.get(substituteLiteralPath(pattern, worldName));
        }

        String literalPrefix = substituteLiteralPath(pattern.substring(0, firstWildcardIndex), worldName);
        Path root = Paths.get(literalPrefix);
        if (firstWildcardIndex != 0 && pattern.charAt(firstWildcardIndex - 1) != '/') {
            root = root.getParent();
        }
        return root == null ? Paths.get("") : root;
    }

    private static String escapeGlobLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' || character == '*'
                    || character == '?'
                    || character == '['
                    || character == ']'
                    || character == '{'
                    || character == '}') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}

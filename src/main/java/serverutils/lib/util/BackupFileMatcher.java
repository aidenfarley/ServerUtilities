package serverutils.lib.util;

import static serverutils.ServerUtilitiesConfig.backups;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class BackupFileMatcher {

    private final List<Rule> excludedBackupFiles;
    private final List<Rule> additionalGlobalBackupFiles;

    public BackupFileMatcher(@Nullable String worldName) {
        excludedBackupFiles = compileRules(backups.excluded_backup_files, worldName, true);
        additionalGlobalBackupFiles = compileRules(backups.additional_backup_files, worldName, false);
    }

    public boolean isExcluded(File file) {
        return isExcluded(FileUtils.getRelativePath(file));
    }

    public boolean isExcluded(String path) {
        return matches(excludedBackupFiles, path);
    }

    public boolean shouldExtract(File file, boolean includeGlobal) {
        String path = FileUtils.normalizePath(file.getPath());
        if (isExcluded(path)) {
            return false;
        }

        return includeGlobal || !matches(additionalGlobalBackupFiles, path);
    }

    private static List<Rule> compileRules(String[] patterns, @Nullable String worldName,
            boolean includeWorldSpecific) {
        List<Rule> rules = new ArrayList<>();
        if (patterns == null) {
            return rules;
        }

        for (String pattern : patterns) {
            if (pattern == null || pattern.trim().isEmpty()) {
                continue;
            }

            if (!includeWorldSpecific && pattern.contains("$WORLDNAME")) {
                continue;
            }

            rules.add(new Rule(pattern, worldName));
        }

        return rules;
    }

    private static boolean matches(List<Rule> rules, String path) {
        String normalizedPath = FileUtils.normalizePath(path);
        for (Rule rule : rules) {
            if (rule.matches(normalizedPath)) {
                return true;
            }
        }

        return false;
    }

    private static class Rule {

        private final String path;
        private final Pattern regex;

        private Rule(String pattern, @Nullable String worldName) {
            String normalizedPattern = FileUtils.normalizePath(pattern.trim());
            normalizedPattern = normalizedPattern.replace(
                    "$WORLDNAME",
                    worldName == null || worldName.isEmpty() ? "*" : FileUtils.normalizePath(worldName));

            if (hasWildcard(normalizedPattern)) {
                path = null;
                regex = Pattern.compile(globToRegex(normalizedPattern));
            } else {
                path = stripTrailingSlashes(normalizedPattern);
                regex = null;
            }
        }

        private boolean matches(String normalizedPath) {
            if (regex != null) {
                return regex.matcher(normalizedPath).matches();
            }

            return normalizedPath.equals(path) || normalizedPath.startsWith(path + "/");
        }

        private static boolean hasWildcard(String pattern) {
            return pattern.indexOf('*') != -1 || pattern.indexOf('?') != -1;
        }

        private static String stripTrailingSlashes(String path) {
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }

            return path;
        }

        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");

            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*') {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (c == '?') {
                    regex.append("[^/]");
                } else if ("\\.[]{}()+-^$|".indexOf(c) != -1) {
                    regex.append('\\').append(c);
                } else {
                    regex.append(c);
                }
            }

            return regex.append('$').toString();
        }
    }
}

package serverutils.lib.util.compression;

import static serverutils.ServerUtilitiesConfig.backups;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;

import serverutils.lib.util.BackupGlobUtils;

final class BackupArchivePathPolicy {

    static final class Target {

        final Path relative;
        final Path destination;

        private Target(Path relative, Path destination) {
            this.relative = relative;
            this.destination = destination;
        }
    }

    private BackupArchivePathPolicy() {}

    static Target resolve(Path extractionRoot, String entryName, boolean oldBackup) throws IOException {
        if (entryName == null || entryName.indexOf('\0') >= 0) {
            throw new IOException("Archive entry has an invalid name");
        }

        String normalizedName = entryName.replace('\\', '/');
        if (normalizedName.startsWith("/") || normalizedName.startsWith("//")
                || normalizedName.matches("^[A-Za-z]:.*")) {
            throw new IOException("Archive entry is absolute: " + entryName);
        }

        try {
            Path archiveRelative = Paths.get(normalizedName).normalize();
            if (archiveRelative.toString().isEmpty() || archiveRelative.isAbsolute()
                    || archiveRelative.startsWith("..")) {
                throw new IOException("Archive entry escapes the restore directory: " + entryName);
            }

            Path relative = oldBackup ? Paths.get("saves").resolve(archiveRelative) : archiveRelative;

            Path root = extractionRoot.toAbsolutePath().normalize();
            Path destination = root.resolve(relative).normalize();
            if (!destination.startsWith(root)) {
                throw new IOException("Archive entry escapes the restore directory: " + entryName);
            }

            return new Target(relative, destination);
        } catch (InvalidPathException ex) {
            throw new IOException("Archive entry has an invalid path: " + entryName, ex);
        }
    }

    static boolean shouldExtract(Path relative, boolean includeGlobal) {
        return shouldExtract(relative, includeGlobal, null);
    }

    static boolean shouldExtract(Path relative, boolean includeGlobal, String worldName) {
        if (includeGlobal) {
            return true;
        }

        if (worldName != null) {
            if (!isSingleWorldName(worldName)) {
                return false;
            }

            Path worldRoot = Paths.get("saves").resolve(worldName);
            if (relative.equals(worldRoot) || relative.startsWith(worldRoot)) {
                return true;
            }

            for (String pattern : backups.additional_backup_files) {
                if (!pattern.contains("$WORLDNAME")) {
                    continue;
                }

                PathMatcher matcher = FileSystems.getDefault()
                        .getPathMatcher("glob:" + BackupGlobUtils.substituteGlob(pattern, worldName));
                if (matcher.matches(relative)) {
                    return true;
                }
            }

            return false;
        }

        for (String pattern : backups.additional_backup_files) {
            if (pattern.contains("$WORLDNAME")) {
                continue;
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            if (matcher.matches(relative)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSingleWorldName(String worldName) {
        if (worldName.isEmpty() || worldName.equals(".")
                || worldName.equals("..")
                || worldName.indexOf('/') >= 0
                || worldName.indexOf('\\') >= 0
                || worldName.indexOf(':') >= 0
                || worldName.indexOf('\0') >= 0) {
            return false;
        }

        try {
            Path path = Paths.get(worldName);
            return !path.isAbsolute() && path.getNameCount() == 1;
        } catch (InvalidPathException ex) {
            return false;
        }
    }

}

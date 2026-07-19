package serverutils.client.gui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class RestoreTransaction {

    private static final String JOURNAL_NAME = ".restore-journal";
    private static final String JOURNAL_HEADER = "SERVERUTILITIES_RESTORE_V1";

    @FunctionalInterface
    interface InstallObserver {

        void afterInstall(Path destination) throws IOException;
    }

    private static final class Move {

        private final Path original;
        private final Path backup;

        private Move(Path original, Path backup) {
            this.original = original;
            this.backup = backup;
        }
    }

    private final Path root;
    private final Path backupRoot;
    private final Path journal;
    private final List<Path> protectedPaths = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<Path> installedFiles = new ArrayList<>();
    private final List<Path> createdDirectories = new ArrayList<>();

    RestoreTransaction(Path root, Path backupRoot) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.backupRoot = requireInsideRoot(backupRoot);
        Files.createDirectories(this.backupRoot);
        journal = this.backupRoot.resolve(JOURNAL_NAME);
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeAndForce(channel, JOURNAL_HEADER + '\n');
        }
        protect(this.backupRoot);
    }

    void protect(Path path) throws IOException {
        protectedPaths.add(requireInsideRoot(path));
    }

    void moveAside(Path original) throws IOException {
        Path normalizedOriginal = requireInsideRoot(original);
        Path relative = root.relativize(normalizedOriginal);
        moveAside(normalizedOriginal, backupRoot.resolve(relative));
    }

    void moveAside(Path original, Path backup) throws IOException {
        Path normalizedOriginal = requireInsideRoot(original);
        Path normalizedBackup = requireInsideRoot(backup);
        if (!Files.exists(normalizedOriginal, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectLinkedPath(normalizedOriginal);
        rejectLinkedPath(normalizedBackup);
        if (isProtected(normalizedOriginal)) {
            throw new IOException("Refusing to move protected restore path " + normalizedOriginal);
        }
        if (Files.exists(normalizedBackup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Restore rollback path already exists: " + normalizedBackup);
        }

        Path parent = normalizedBackup.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Move move = new Move(normalizedOriginal, normalizedBackup);
        appendJournal("M " + encode(normalizedOriginal) + ' ' + encode(normalizedBackup));
        moves.add(move);
        Files.move(normalizedOriginal, normalizedBackup);
        protect(normalizedBackup);
    }

    void install(Path stagingRoot) throws IOException {
        install(stagingRoot, destination -> {});
    }

    void install(Path stagingRoot, InstallObserver observer) throws IOException {
        Path staging = stagingRoot.toAbsolutePath().normalize();
        List<Path> stagedPaths;
        try (Stream<Path> paths = Files.walk(staging)) {
            stagedPaths = paths.filter(path -> !path.equals(staging))
                    .sorted(Comparator.comparingInt(Path::getNameCount)).collect(Collectors.toList());
        }

        validateInstall(staging, stagedPaths);
        for (Path source : stagedPaths) {
            Path destination = destinationFor(staging, source);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    appendJournal("D " + encode(destination));
                    createdDirectories.add(destination);
                    Files.createDirectory(destination);
                }
                continue;
            }

            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                moveAside(destination);
            }
            appendJournal("F " + encode(destination));
            installedFiles.add(destination);
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            observer.afterInstall(destination);
        }
    }

    private void validateInstall(Path staging, List<Path> stagedPaths) throws IOException {
        for (Path source : stagedPaths) {
            if (Files.isSymbolicLink(source)) {
                throw new IOException("Restore staging contains a linked path: " + source);
            }

            Path destination = destinationFor(staging, source);
            rejectLinkedPath(destination);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && isProtected(destination)) {
                throw new IOException("Backup attempts to overwrite restore transaction data: " + destination);
            }

            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(destination)) {
                    throw new IOException("Restore target is a linked path: " + destination);
                }

                boolean sourceDirectory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
                boolean destinationDirectory = Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS);
                if (sourceDirectory != destinationDirectory) {
                    throw new IOException("Restore target has a conflicting file type: " + destination);
                }
            }
        }
    }

    private Path destinationFor(Path staging, Path source) throws IOException {
        Path destination = root.resolve(staging.relativize(source)).normalize();
        if (!destination.startsWith(root)) {
            throw new IOException("Restore target escapes the server directory: " + destination);
        }
        return destination;
    }

    void rollback() throws IOException {
        IOException failure = null;

        for (int i = installedFiles.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(installedFiles.get(i));
            } catch (IOException ex) {
                failure = append(failure, ex);
            }
        }

        for (int i = createdDirectories.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(createdDirectories.get(i));
            } catch (IOException ex) {
                failure = append(failure, ex);
            }
        }

        for (int i = moves.size() - 1; i >= 0; i--) {
            Move move = moves.get(i);
            try {
                if (!Files.exists(move.backup, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path parent = move.original.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.move(move.backup, move.original, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                failure = append(failure, ex);
            }
        }

        if (failure != null) {
            throw failure;
        }

        deactivateJournal();
    }

    void commit() throws IOException {
        deactivateJournal();
    }

    static void recoverPending(Path serverRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        Path backupBase = root.resolve("backups_before_restore");
        if (!Files.isDirectory(backupBase, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        List<Path> journals;
        try (Stream<Path> paths = Files.walk(backupBase)) {
            journals = paths.filter(path -> path.getFileName().toString().equals(JOURNAL_NAME))
                    .collect(Collectors.toList());
        }
        for (Path journal : journals) {
            recoverJournal(root, journal);
        }
    }

    private boolean isProtected(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path protectedPath : protectedPaths) {
            if (normalized.startsWith(protectedPath) || protectedPath.startsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    boolean isProtectedPath(Path path) {
        return isProtected(path);
    }

    private Path requireInsideRoot(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Restore path escapes the server directory: " + path);
        }
        return normalized;
    }

    private String encode(Path path) throws IOException {
        Path relative = root.relativize(requireInsideRoot(path));
        String value = relative.toString().replace('\\', '/');
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void appendJournal(String line) throws IOException {
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writeAndForce(channel, line + '\n');
        }
    }

    private void deactivateJournal() throws IOException {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path inactive = journal.resolveSibling(JOURNAL_NAME + ".inactive");
        Files.deleteIfExists(inactive);
        moveReplacing(journal, inactive);
        Files.deleteIfExists(inactive);
    }

    private static void recoverJournal(Path root, Path journal) throws IOException {
        if (Files.isSymbolicLink(journal)) {
            throw new IOException("Restore journal is a symbolic link: " + journal);
        }
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !JOURNAL_HEADER.equals(lines.get(0))) {
            throw new IOException("Unrecognized restore journal: " + journal);
        }

        List<Move> moves = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(" ");
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            if (parts[0].equals("M") && parts.length == 3) {
                moves.add(new Move(decode(root, parts[1]), decode(root, parts[2])));
            } else if (parts[0].equals("F") && parts.length == 2) {
                files.add(decode(root, parts[1]));
            } else if (parts[0].equals("D") && parts.length == 2) {
                directories.add(decode(root, parts[1]));
            } else {
                throw new IOException("Invalid restore journal entry in " + journal);
            }
        }

        for (int i = files.size() - 1; i >= 0; i--) {
            Path path = files.get(i);
            if (shouldRemoveInstalledPath(path, moves)) {
                Files.deleteIfExists(path);
            }
        }
        for (int i = directories.size() - 1; i >= 0; i--) {
            Path path = directories.get(i);
            if (shouldRemoveInstalledPath(path, moves)) {
                Files.deleteIfExists(path);
            }
        }
        for (int i = moves.size() - 1; i >= 0; i--) {
            Move move = moves.get(i);
            if (!Files.exists(move.backup, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path parent = move.original.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(move.backup, move.original, StandardCopyOption.REPLACE_EXISTING);
        }

        Path inactive = journal.resolveSibling(JOURNAL_NAME + ".inactive");
        Files.deleteIfExists(inactive);
        moveReplacing(journal, inactive);
        Files.deleteIfExists(inactive);
    }

    private static boolean shouldRemoveInstalledPath(Path path, List<Move> moves) {
        for (Move move : moves) {
            if (path.startsWith(move.original)) {
                return Files.exists(move.backup, LinkOption.NOFOLLOW_LINKS);
            }
        }
        return true;
    }

    private static Path decode(Path root, String encoded) throws IOException {
        final String value;
        try {
            value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Restore journal contains invalid path data", ex);
        }
        Path relative = java.nio.file.Paths.get(value).normalize();
        Path resolved = root.resolve(relative).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || !resolved.startsWith(root)) {
            throw new IOException("Restore journal path escapes the server directory");
        }
        return resolved;
    }

    private static void writeAndForce(FileChannel channel, String value) throws IOException {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(value);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rejectLinkedPath(Path path) throws IOException {
        Path current = root;
        for (Path segment : root.relativize(path)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            BasicFileAttributes attributes = Files
                    .readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(current) || attributes.isOther()) {
                throw new IOException("Restore path traverses a link: " + current);
            }
        }
    }

    private static IOException append(IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}

package serverutils.lib.util.compression;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;

import javax.annotation.Nullable;

abstract class AbstractZipCompressor<A extends Closeable, E> implements ICompress {

    private static final int MAX_ARCHIVE_ENTRIES = 1_000_000;
    private static final long MAX_EXTRACTED_BYTES = 1L << 40;
    private static final long MIN_FREE_SPACE_RESERVE = 64L * 1024L * 1024L;

    protected abstract A openArchive(File archive) throws IOException;

    protected abstract Enumeration<? extends E> getEntries(A archive);

    protected abstract String getEntryName(E entry);

    protected abstract boolean isDirectory(E entry);

    protected abstract InputStream openEntry(A archive, E entry) throws IOException;

    protected static void copyArchiveInput(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Archive operation cancelled");
            }
            output.write(buffer, 0, read);
        }
    }

    @Override
    public boolean isOldBackup(File archive) throws IOException {
        try (A zip = openArchive(archive)) {
            Enumeration<? extends E> entries = getEntries(zip);
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                if (getEntryName(entries.nextElement()).replace('\\', '/').startsWith("saves/")) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void extractArchive(File archive, boolean includeGlobal, boolean isOldBackup) throws IOException {
        extractArchiveTo(Paths.get(""), archive, includeGlobal, isOldBackup);
    }

    @Override
    public void extractArchiveTo(File extractionRoot, File archive, boolean includeGlobal, boolean isOldBackup)
            throws IOException {
        extractArchiveTo(extractionRoot.toPath(), archive, includeGlobal, isOldBackup, null);
    }

    @Override
    public void extractArchiveTo(File extractionRoot, File archive, boolean includeGlobal, boolean isOldBackup,
            @Nullable String worldName) throws IOException {
        extractArchiveTo(extractionRoot.toPath(), archive, includeGlobal, isOldBackup, worldName);
    }

    void extractArchiveTo(Path extractionRoot, File archive, boolean includeGlobal, boolean isOldBackup)
            throws IOException {
        extractArchiveTo(extractionRoot, archive, includeGlobal, isOldBackup, null);
    }

    void extractArchiveTo(Path extractionRoot, File archive, boolean includeGlobal, boolean isOldBackup,
            @Nullable String worldName) throws IOException {
        validateArchiveTo(extractionRoot, archive, includeGlobal, isOldBackup, worldName);

        Path root = extractionRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        verifyNoLinkedPath(root, root);
        long usableSpace = Files.getFileStore(root).getUsableSpace();
        long reserve = Math.min(MIN_FREE_SPACE_RESERVE, usableSpace / 10L);
        ExtractionBudget budget = new ExtractionBudget(Math.min(MAX_EXTRACTED_BYTES, usableSpace - reserve));

        try (A zip = openArchive(archive)) {
            Enumeration<? extends E> entries = getEntries(zip);
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                E entry = entries.nextElement();
                BackupArchivePathPolicy.Target target = BackupArchivePathPolicy
                        .resolve(extractionRoot, getEntryName(entry), isOldBackup);
                if (!BackupArchivePathPolicy.shouldExtract(target.relative, includeGlobal, worldName)) {
                    continue;
                }

                verifyNoLinkedPath(extractionRoot, target.destination);
                if (isDirectory(entry)) {
                    Files.createDirectories(target.destination);
                    continue;
                }

                Path parent = target.destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    verifyNoLinkedPath(root, parent);
                }

                Path temporary = Files.createTempFile(parent, ".restore-", ".tmp");
                try {
                    try (InputStream input = openEntry(zip, entry);
                            OutputStream output = Files.newOutputStream(temporary)) {
                        copyWithBudget(input, output, budget);
                    }
                    moveReplacing(temporary, target.destination);
                    temporary = null;
                } finally {
                    if (temporary != null) {
                        Files.deleteIfExists(temporary);
                    }
                }
            }
        }
    }

    void validateArchiveTo(Path extractionRoot, File archive, boolean includeGlobal, boolean isOldBackup)
            throws IOException {
        validateArchiveTo(extractionRoot, archive, includeGlobal, isOldBackup, null);
    }

    void validateArchiveTo(Path extractionRoot, File archive, boolean includeGlobal, boolean isOldBackup,
            @Nullable String worldName) throws IOException {
        try (A zip = openArchive(archive)) {
            Enumeration<? extends E> entries = getEntries(zip);
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                E entry = entries.nextElement();
                BackupArchivePathPolicy.Target target = BackupArchivePathPolicy
                        .resolve(extractionRoot, getEntryName(entry), isOldBackup);
                if (BackupArchivePathPolicy.shouldExtract(target.relative, includeGlobal, worldName)) {
                    verifyNoLinkedPath(extractionRoot, target.destination);
                }
            }
        }
    }

    private static void copyWithBudget(InputStream input, OutputStream output, ExtractionBudget budget)
            throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            budget.consume(read);
            output.write(buffer, 0, read);
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class ExtractionBudget {

        private final long maximum;
        private long consumed;

        private ExtractionBudget(long maximum) {
            this.maximum = Math.max(0L, maximum);
        }

        private void consume(int bytes) throws IOException {
            if (bytes > maximum - consumed) {
                throw new IOException("Archive exceeds the safe extraction-size limit");
            }
            consumed += bytes;
        }
    }

    private static void verifyNoLinkedPath(Path extractionRoot, Path destination) throws IOException {
        Path root = extractionRoot.toAbsolutePath().normalize();
        Path current = root;
        rejectLink(current);

        for (Path segment : root.relativize(destination)) {
            current = current.resolve(segment);
            rejectLink(current);
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        BasicFileAttributes attributes = Files
                .readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || attributes.isOther()) {
            throw new IOException("Archive entry traverses a linked path: " + path);
        }
    }

    @Override
    public @Nullable String getWorldName(File file) throws IOException {
        if (file.isDirectory() || !file.getName().endsWith(".zip")) return null;

        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(file)) {
            return zipFile.getComment();
        }
    }
}

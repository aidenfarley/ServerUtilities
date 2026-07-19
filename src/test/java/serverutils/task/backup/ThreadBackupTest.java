package serverutils.task.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import serverutils.lib.math.ChunkDimPos;
import serverutils.lib.util.BackupGlobUtils;
import serverutils.lib.util.compression.ICompress;

class ThreadBackupTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void customBackupNamesStayDirectlyInsideTheBackupFolder() throws Exception {
        File destination = ThreadBackup.resolveBackupFile(temporaryDirectory.toFile(), "nightly");

        assertEquals(temporaryDirectory.resolve("nightly.zip").toFile().getCanonicalFile(), destination);

        for (String unsafeName : new String[] { "../escape", "..\\escape", "nested/name", "C:escape", ".", "name " }) {
            assertThrows(
                    IOException.class,
                    () -> ThreadBackup.resolveBackupFile(temporaryDirectory.toFile(), unsafeName),
                    unsafeName);
        }
    }

    @Test
    void wildcardWithoutAParentFallsBackToTheWorkingDirectory() {
        String rootLevelPattern = "server*.properties";

        assertEquals(Paths.get(""), BackupGlobUtils.searchRoot(rootLevelPattern, ""));
    }

    @Test
    void worldFolderIsEscapedWhenInsertedIntoBackupGlob() {
        PathMatcher matcher = java.nio.file.FileSystems.getDefault()
                .getPathMatcher("glob:" + BackupGlobUtils.substituteGlob("config/$WORLDNAME/**", "world[1]"));

        assertTrue(matcher.matches(Paths.get("config/world[1]/settings.cfg")));
        assertFalse(matcher.matches(Paths.get("config/world1/settings.cfg")));
        assertEquals(Paths.get("config/world[1]"), BackupGlobUtils.searchRoot("config/$WORLDNAME/**", "world[1]"));
    }

    @Test
    void claimedModeWithNoClaimsExcludesEveryRegionInsteadOfFallingBackToAFullBackup() throws Exception {
        Path levelData = Files.write(temporaryDirectory.resolve("level.dat"), new byte[] { 1 });
        Path region = Files.write(temporaryDirectory.resolve("r.0.0.mca"), new byte[] { 2 });
        List<File> files = new ArrayList<>();
        files.add(levelData.toFile());
        files.add(region.toFile());
        RecordingCompressor compressor = new RecordingCompressor(false);

        ThreadBackup.compressSelectedFiles(files, Collections.<ChunkDimPos>emptySet(), compressor, true);

        assertEquals(Collections.singletonList("level.dat"), compressor.archivedFileNames);
        assertFalse(files.contains(region.toFile()));
    }

    @Test
    void interruptionStopsCompressionBeforeTheNextFile() throws Exception {
        Path first = Files.write(temporaryDirectory.resolve("first.dat"), new byte[] { 1 });
        Path second = Files.write(temporaryDirectory.resolve("second.dat"), new byte[] { 2 });
        RecordingCompressor compressor = new RecordingCompressor(true);

        try {
            assertThrows(
                    InterruptedIOException.class,
                    () -> ThreadBackup.compressSelectedFiles(
                            new ArrayList<>(java.util.Arrays.asList(first.toFile(), second.toFile())),
                            Collections.<ChunkDimPos>emptySet(),
                            compressor,
                            false));
            assertEquals(1, compressor.archivedFileNames.size());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class RecordingCompressor implements ICompress {

        private final List<String> archivedFileNames = new ArrayList<>();
        private final boolean interruptAfterFirstFile;

        private RecordingCompressor(boolean interruptAfterFirstFile) {
            this.interruptAfterFirstFile = interruptAfterFirstFile;
        }

        @Override
        public void createOutputStream(File file) {}

        @Override
        public void addFileToArchive(File file, String name) {
            archivedFileNames.add(file.getName());
            if (interruptAfterFirstFile && archivedFileNames.size() == 1) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void extractArchive(File archive, boolean includeGlobal, boolean isOldBackup) {}

        @Override
        public boolean isOldBackup(File archive) {
            return false;
        }

        @Override
        public String getWorldName(File file) {
            return null;
        }

        @Override
        public void close() {}
    }
}

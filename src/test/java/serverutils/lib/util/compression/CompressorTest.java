package serverutils.lib.util.compression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static serverutils.ServerUtilitiesConfig.backups;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompressorTest {

    @TempDir
    Path temporaryDirectory;

    private String[] previousAdditionalFiles;

    @BeforeEach
    void setUp() {
        previousAdditionalFiles = backups.additional_backup_files;
        backups.additional_backup_files = new String[0];
    }

    @AfterEach
    void tearDown() {
        backups.additional_backup_files = previousAdditionalFiles;
    }

    @Test
    void bothBackendsRestoreOldAndNewLayouts() throws Exception {
        for (Supplier<AbstractZipCompressor<?, ?>> factory : compressors()) {
            AbstractZipCompressor<?, ?> compressor = factory.get();
            Path oldArchive = createArchive("old.zip", "Legacy World", "world/level.dat", "old");
            Path oldDestination = Files
                    .createDirectory(temporaryDirectory.resolve(factory.get().getClass().getSimpleName()));

            assertTrue(compressor.isOldBackup(oldArchive.toFile()));
            compressor.extractArchiveTo(oldDestination, oldArchive.toFile(), true, true);
            assertEquals("old", read(oldDestination.resolve("saves/world/level.dat")));

            Path newArchive = createArchive(
                    factory.get().getClass().getSimpleName() + "-new.zip",
                    "Current World",
                    "saves/world/level.dat",
                    "new");
            Path newDestination = Files
                    .createDirectory(temporaryDirectory.resolve(factory.get().getClass().getSimpleName() + "-new"));

            assertFalse(compressor.isOldBackup(newArchive.toFile()));
            compressor.extractArchiveTo(newDestination, newArchive.toFile(), true, false);
            assertEquals("new", read(newDestination.resolve("saves/world/level.dat")));
            assertEquals("Current World", compressor.getWorldName(newArchive.toFile()));
        }
    }

    @Test
    void bothBackendsRejectTraversalAndAbsoluteEntries() throws Exception {
        List<String> unsafeNames = Arrays.asList("../escape.txt", "..\\escape.txt", "/absolute.txt", "C:/drive.txt");

        for (Supplier<AbstractZipCompressor<?, ?>> factory : compressors()) {
            for (String unsafeName : unsafeNames) {
                for (boolean oldLayout : Arrays.asList(false, true)) {
                    AbstractZipCompressor<?, ?> compressor = factory.get();
                    List<String> entries = oldLayout ? Arrays.asList(unsafeName)
                            : Arrays.asList("saves/world/level.dat", unsafeName);
                    Path archive = createArchive(
                            factory.get().getClass().getSimpleName() + '-'
                                    + oldLayout
                                    + '-'
                                    + Math.abs(unsafeName.hashCode())
                                    + ".zip",
                            null,
                            entries,
                            "unsafe");
                    Path destination = Files.createDirectories(
                            temporaryDirectory.resolve(
                                    factory.get().getClass().getSimpleName() + "-unsafe-"
                                            + oldLayout
                                            + '-'
                                            + Math.abs(unsafeName.hashCode())));

                    boolean detectedOldLayout = compressor.isOldBackup(archive.toFile());
                    assertEquals(oldLayout, detectedOldLayout);
                    assertThrows(
                            IOException.class,
                            () -> compressor.extractArchiveTo(destination, archive.toFile(), true, detectedOldLayout));
                    assertFalse(Files.exists(destination.resolve("saves/world/level.dat")));
                    assertFalse(Files.exists(temporaryDirectory.resolve("escape.txt")));
                }
            }
        }
    }

    @Test
    void globalBackupPatternsAreExcludedWhenRequested() throws Exception {
        backups.additional_backup_files = new String[] { "config/**" };

        for (Supplier<AbstractZipCompressor<?, ?>> factory : compressors()) {
            AbstractZipCompressor<?, ?> compressor = factory.get();
            Path archive = createArchive(
                    factory.get().getClass().getSimpleName() + "-global.zip",
                    null,
                    "config/server.cfg",
                    "config");
            Path destination = Files.createDirectory(
                    temporaryDirectory.resolve(factory.get().getClass().getSimpleName() + "-filtered"));

            compressor.extractArchiveTo(destination, archive.toFile(), false, false);
            assertFalse(Files.exists(destination.resolve("config/server.cfg")));
        }
    }

    @Test
    void worldOnlyRestoreCannotWriteAnotherWorldOrUnrelatedGlobalFiles() throws Exception {
        backups.additional_backup_files = new String[] { "config/$WORLDNAME/**", "mods/**" };

        for (Supplier<AbstractZipCompressor<?, ?>> factory : compressors()) {
            AbstractZipCompressor<?, ?> compressor = factory.get();
            Path archive = createArchive(
                    factory.get().getClass().getSimpleName() + "-scoped.zip",
                    "world",
                    Arrays.asList(
                            "saves/world/level.dat",
                            "saves/other/level.dat",
                            "config/world/settings.cfg",
                            "config/other/settings.cfg",
                            "mods/unrelated.jar"),
                    "content");
            Path destination = Files
                    .createDirectory(temporaryDirectory.resolve(factory.get().getClass().getSimpleName() + "-scoped"));

            compressor.extractArchiveTo(destination.toFile(), archive.toFile(), false, false, "world");

            assertTrue(Files.exists(destination.resolve("saves/world/level.dat")));
            assertTrue(Files.exists(destination.resolve("config/world/settings.cfg")));
            assertFalse(Files.exists(destination.resolve("saves/other/level.dat")));
            assertFalse(Files.exists(destination.resolve("config/other/settings.cfg")));
            assertFalse(Files.exists(destination.resolve("mods/unrelated.jar")));
        }
    }

    @Test
    void worldNameIsLiteralWhenAppliedToConfiguredGlob() throws Exception {
        backups.additional_backup_files = new String[] { "config/$WORLDNAME/**" };

        for (Supplier<AbstractZipCompressor<?, ?>> factory : compressors()) {
            AbstractZipCompressor<?, ?> compressor = factory.get();
            Path archive = createArchive(
                    factory.get().getClass().getSimpleName() + "-literal-world.zip",
                    "world[1]",
                    Arrays.asList(
                            "saves/world[1]/level.dat",
                            "config/world[1]/settings.cfg",
                            "config/world1/settings.cfg"),
                    "content");
            Path destination = Files.createDirectory(
                    temporaryDirectory.resolve(factory.get().getClass().getSimpleName() + "-literal-world"));

            compressor.extractArchiveTo(destination.toFile(), archive.toFile(), false, false, "world[1]");

            assertTrue(Files.exists(destination.resolve("saves/world[1]/level.dat")));
            assertTrue(Files.exists(destination.resolve("config/world[1]/settings.cfg")));
            assertFalse(Files.exists(destination.resolve("config/world1/settings.cfg")));
        }
    }

    @Test
    void archiveCopyStopsPromptlyWhenItsThreadIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedIOException.class,
                    () -> AbstractZipCompressor.copyArchiveInput(
                            new ByteArrayInputStream(new byte[128 * 1024]),
                            new ByteArrayOutputStream()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private List<Supplier<AbstractZipCompressor<?, ?>>> compressors() {
        return Arrays.asList(LegacyCompressor::new, CommonsCompressor::new);
    }

    private Path createArchive(String name, String comment, String entryName, String content) throws IOException {
        return createArchive(name, comment, Arrays.asList(entryName), content);
    }

    private Path createArchive(String name, String comment, List<String> entryNames, String content)
            throws IOException {
        Path archive = temporaryDirectory.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            if (comment != null) {
                output.setComment(comment);
            }
            for (String entryName : entryNames) {
                output.putNextEntry(new ZipEntry(entryName));
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

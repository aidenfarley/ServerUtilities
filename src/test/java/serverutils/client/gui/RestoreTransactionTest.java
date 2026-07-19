package serverutils.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreTransactionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void partialInstallCanRestoreWorldAndGlobalFiles() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("server"));
        Path world = write(root.resolve("saves/world/level.dat"), "old-world").getParent();
        Path config = write(root.resolve("config/server.cfg"), "old-config");
        Path staging = Files.createDirectories(root.resolve("serverutilities/restore-staging/restore-test"));
        write(staging.resolve("saves/world/level.dat"), "new-world");
        write(staging.resolve("config/server.cfg"), "new-config");

        Path rollbackRoot = root.resolve("backups_before_restore/test");
        RestoreTransaction transaction = new RestoreTransaction(root, rollbackRoot);
        transaction.protect(root.resolve("serverutilities/restore-staging"));
        transaction.protect(root.resolve("backups_before_restore"));
        transaction.moveAside(world, root.resolve("saves/world_old"));
        transaction.moveAside(config);
        transaction.install(staging);

        assertEquals("new-world", read(root.resolve("saves/world/level.dat")));
        assertEquals("new-config", read(root.resolve("config/server.cfg")));

        transaction.rollback();

        assertEquals("old-world", read(root.resolve("saves/world/level.dat")));
        assertEquals("old-config", read(root.resolve("config/server.cfg")));
        assertFalse(Files.exists(root.resolve("saves/world_old")));
    }

    @Test
    void transactionDataCannotBeInstalledOver() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("server"));
        Path staging = Files.createDirectories(root.resolve("serverutilities/restore-staging/restore-test"));
        write(staging.resolve("backups_before_restore/evil.txt"), "evil");

        RestoreTransaction transaction = new RestoreTransaction(root, root.resolve("backups_before_restore/test"));
        transaction.protect(root.resolve("backups_before_restore"));

        assertThrows(IOException.class, () -> transaction.install(staging));
        assertFalse(Files.exists(root.resolve("backups_before_restore/evil.txt")));
    }

    @Test
    void ancestorOfProtectedTransactionDataCannotBeMoved() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("server-protected-ancestor"));
        Path stagingBase = Files.createDirectories(root.resolve("serverutilities/restore-staging"));
        write(stagingBase.resolve("active/file.dat"), "staged");

        RestoreTransaction transaction = new RestoreTransaction(root, root.resolve("backups_before_restore/test"));
        transaction.protect(stagingBase);

        assertThrows(IOException.class, () -> transaction.moveAside(root.resolve("serverutilities")));
        assertEquals("staged", read(stagingBase.resolve("active/file.dat")));
        transaction.rollback();
    }

    @Test
    void interruptedProcessCanRecoverFromPersistentJournal() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("server-recovery"));
        Path world = write(root.resolve("saves/world/level.dat"), "old-world").getParent();
        Path staging = Files.createDirectories(root.resolve("serverutilities/restore-staging/recovery"));
        write(staging.resolve("saves/world/level.dat"), "new-world");

        RestoreTransaction transaction = new RestoreTransaction(root, root.resolve("backups_before_restore/recovery"));
        transaction.moveAside(world, root.resolve("saves/world_old"));
        transaction.install(staging);

        assertEquals("new-world", read(root.resolve("saves/world/level.dat")));
        RestoreRecovery.recoverPending(root);

        assertEquals("old-world", read(root.resolve("saves/world/level.dat")));
        assertFalse(Files.exists(root.resolve("saves/world_old")));
    }

    @Test
    void midInstallFailureRollsBackEveryCompletedMove() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("server-partial"));
        write(root.resolve("config/one.cfg"), "old-one");
        write(root.resolve("config/two.cfg"), "old-two");
        Path staging = Files.createDirectories(root.resolve("serverutilities/restore-staging/partial"));
        write(staging.resolve("config/one.cfg"), "new-one");
        write(staging.resolve("config/two.cfg"), "new-two");

        RestoreTransaction transaction = new RestoreTransaction(root, root.resolve("backups_before_restore/partial"));
        assertThrows(
                IOException.class,
                () -> transaction.install(staging, destination -> { throw new IOException("injected failure"); }));
        transaction.rollback();

        assertEquals("old-one", read(root.resolve("config/one.cfg")));
        assertEquals("old-two", read(root.resolve("config/two.cfg")));
    }

    private Path write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

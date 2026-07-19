package serverutils.client.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Recovers an interrupted backup restore before Minecraft can load a partially installed world. */
public final class RestoreRecovery {

    private RestoreRecovery() {}

    public static void recoverPendingFromWorkingDirectory() throws IOException {
        recoverPending(Paths.get(""));
    }

    static void recoverPending(Path serverRoot) throws IOException {
        RestoreTransaction.recoverPending(serverRoot);
    }
}

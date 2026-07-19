package serverutils.aurora;

import net.minecraft.server.MinecraftServer;

public class Aurora {

    private static AuroraServer server;

    public static void start(MinecraftServer s) {
        if (AuroraConfig.general.enable) {
            if (server == null) {
                AuroraServer candidate = new AuroraServer(s, AuroraConfig.general.port);
                if (candidate.start()) {
                    server = candidate;
                }
            }
        }
    }

    public static void stop() {
        if (AuroraConfig.general.enable) {
            if (server != null) {
                server.shutdown();
                server = null;
            }
        }
    }
}

package serverutils.aurora;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

class AuroraServerTest {

    @Test
    void bindFailureShutsDownBothEventLoopGroups() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            AuroraServer server = new AuroraServer(null, occupied.getLocalPort());

            assertFalse(server.start());
            assertTrue(server.eventLoopsAreShuttingDown());
        }
    }
}

package serverutils.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import serverutils.events.IReloadHandler;

class ServerUtilitiesRegistryTest {

    private static final ResourceLocation TEST_ID = new ResourceLocation("serverutils", "registry_test");

    @AfterEach
    void cleanUp() {
        serverutils.ServerUtilitiesRegistry.RELOAD_IDS.remove(TEST_ID);
    }

    @Test
    void checkedRegistrationRejectsDuplicatesAndExposesReadOnlyLiveView() {
        IReloadHandler first = event -> true;
        IReloadHandler second = event -> false;

        ServerUtilitiesRegistry.registerServerReloadHandler(TEST_ID, first);

        assertSame(first, ServerUtilitiesRegistry.findReloadHandler(TEST_ID));
        assertSame(first, ServerUtilitiesRegistry.reloadHandlersView().get(TEST_ID));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerUtilitiesRegistry.registerServerReloadHandler(TEST_ID, second));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ServerUtilitiesRegistry.reloadHandlersView().put(TEST_ID, second));
    }

    @Test
    void legacyMapMutationsRemainVisibleForCompatibility() {
        IReloadHandler handler = event -> true;

        serverutils.ServerUtilitiesRegistry.RELOAD_IDS.put(TEST_ID, handler);

        assertSame(handler, ServerUtilitiesRegistry.findReloadHandler(TEST_ID));
        assertSame(handler, ServerUtilitiesRegistry.reloadHandlersView().get(TEST_ID));
    }

    @Test
    void checkedRegistrationRejectsNulls() {
        assertThrows(
                NullPointerException.class,
                () -> ServerUtilitiesRegistry.registerServerReloadHandler(null, event -> true));
        assertThrows(
                NullPointerException.class,
                () -> ServerUtilitiesRegistry.registerServerReloadHandler(TEST_ID, null));
    }
}

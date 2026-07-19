package serverutils.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import serverutils.events.IReloadHandler;
import serverutils.invsee.inventories.IModdedInventory;
import serverutils.invsee.inventories.InvSeeRegistry;
import serverutils.lib.config.ConfigValueProvider;
import serverutils.lib.data.AdminPanelAction;
import serverutils.lib.data.ISyncData;
import serverutils.lib.data.TeamAction;

/**
 * Checked access to ServerUtilities extension registries.
 *
 * <p>
 * Registration, duplicate-ID handling, lookup, and read-only-view behavior are supported facade contracts. Types in the
 * method signatures retain their existing compatibility guarantees; exposing one here does not make every member of
 * that type part of this facade's stability contract.
 */
public final class ServerUtilitiesRegistry {

    private static final Map<ResourceLocation, IReloadHandler> RELOAD_HANDLERS = Collections
            .unmodifiableMap(serverutils.ServerUtilitiesRegistry.RELOAD_IDS);
    private static final Map<ResourceLocation, TeamAction> TEAM_ACTIONS = Collections
            .unmodifiableMap(serverutils.ServerUtilitiesRegistry.TEAM_GUI_ACTIONS);
    private static final Map<ResourceLocation, AdminPanelAction> ADMIN_ACTIONS = Collections
            .unmodifiableMap(serverutils.ServerUtilitiesRegistry.ADMIN_PANEL_ACTIONS);
    private static final Map<String, ConfigValueProvider> CONFIG_PROVIDERS = Collections
            .unmodifiableMap(serverutils.ServerUtilitiesRegistry.CONFIG_VALUE_PROVIDERS);
    private static final Map<String, ISyncData> SYNC_DATA = Collections
            .unmodifiableMap(serverutils.ServerUtilitiesRegistry.SYNCED_DATA);

    private ServerUtilitiesRegistry() {}

    public static void registerConfigValueProvider(String id, ConfigValueProvider provider) {
        registerUnique(serverutils.ServerUtilitiesRegistry.CONFIG_VALUE_PROVIDERS, id, provider, "config provider");
    }

    public static void registerSyncData(String modId, ISyncData data) {
        registerUnique(serverutils.ServerUtilitiesRegistry.SYNCED_DATA, modId, data, "sync data");
    }

    public static void registerServerReloadHandler(ResourceLocation id, IReloadHandler handler) {
        registerUnique(serverutils.ServerUtilitiesRegistry.RELOAD_IDS, id, handler, "reload handler");
    }

    public static void registerAdminPanelAction(AdminPanelAction action) {
        Objects.requireNonNull(action, "action");
        registerUnique(
                serverutils.ServerUtilitiesRegistry.ADMIN_PANEL_ACTIONS,
                action.getId(),
                action,
                "admin panel action");
    }

    public static void registerTeamAction(TeamAction action) {
        Objects.requireNonNull(action, "action");
        registerUnique(serverutils.ServerUtilitiesRegistry.TEAM_GUI_ACTIONS, action.getId(), action, "team action");
    }

    public static void registerInvseeInventory(IModdedInventory inventory) {
        InvSeeRegistry.registerInventory(Objects.requireNonNull(inventory, "inventory"));
    }

    public static Map<ResourceLocation, IReloadHandler> reloadHandlersView() {
        return RELOAD_HANDLERS;
    }

    public static Map<ResourceLocation, TeamAction> teamActionsView() {
        return TEAM_ACTIONS;
    }

    public static Map<ResourceLocation, AdminPanelAction> adminPanelActionsView() {
        return ADMIN_ACTIONS;
    }

    public static Map<String, ConfigValueProvider> configValueProvidersView() {
        return CONFIG_PROVIDERS;
    }

    public static Map<String, ISyncData> syncDataView() {
        return SYNC_DATA;
    }

    @Nullable
    public static IReloadHandler findReloadHandler(ResourceLocation id) {
        return serverutils.ServerUtilitiesRegistry.RELOAD_IDS.get(id);
    }

    @Nullable
    public static TeamAction findTeamAction(ResourceLocation id) {
        return serverutils.ServerUtilitiesRegistry.TEAM_GUI_ACTIONS.get(id);
    }

    @Nullable
    public static AdminPanelAction findAdminPanelAction(ResourceLocation id) {
        return serverutils.ServerUtilitiesRegistry.ADMIN_PANEL_ACTIONS.get(id);
    }

    @Nullable
    public static ConfigValueProvider findConfigValueProvider(String id) {
        return serverutils.ServerUtilitiesRegistry.CONFIG_VALUE_PROVIDERS.get(id);
    }

    @Nullable
    public static ISyncData findSyncData(String id) {
        return serverutils.ServerUtilitiesRegistry.SYNCED_DATA.get(id);
    }

    private static <K, V> void registerUnique(Map<K, V> registry, K key, V value, String registryName) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (registry.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("Duplicate " + registryName + " ID: " + key);
        }
    }
}

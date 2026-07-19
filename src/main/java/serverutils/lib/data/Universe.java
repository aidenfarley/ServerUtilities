package serverutils.lib.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.ServerUtilitiesConfig;
import serverutils.events.team.ForgeTeamDeletedEvent;
import serverutils.events.universe.UniverseClearCacheEvent;
import serverutils.events.universe.UniverseClosedEvent;
import serverutils.lib.math.MathUtils;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.FileUtils;
import serverutils.lib.util.ServerUtils;
import serverutils.lib.util.StringUtils;
import serverutils.ranks.Ranks;
import serverutils.task.Task;

public class Universe {

    private static final HashSet<UUID> LOGGED_IN_PLAYERS = new HashSet<>(); // Required because of a Forge bug
    // https://github.com/MinecraftForge/MinecraftForge/issues/5696
    private static Universe INSTANCE = null;

    public static boolean loaded() {
        return INSTANCE != null;
    }

    public static Universe get() {
        if (INSTANCE == null) {
            throw new NullPointerException("ServerUtilities Universe == null!");
        }

        return INSTANCE;
    }

    public static Universe requireLoaded() {
        if (INSTANCE == null) {
            throw new IllegalStateException("ServerUtilities Universe is not loaded");
        }

        return INSTANCE;
    }

    public static @Nullable Universe getNullable() {
        return INSTANCE;
    }

    public static @Nullable Universe getIfLoaded() {
        return INSTANCE;
    }

    // Event handlers start //

    public static void onServerAboutToStart(FMLServerAboutToStartEvent event) {
        INSTANCE = new Universe(event.getServer());
    }

    public static void onServerStarted(FMLServerStartedEvent event) {
        INSTANCE.world = INSTANCE.server.worldServers[0];
        INSTANCE.ticks = Ticks.get(INSTANCE.world.getTotalWorldTime());
        INSTANCE.load();
    }

    public static void onServerStopping(FMLServerStoppingEvent event) {
        if (loaded()) {
            for (ForgePlayer player : INSTANCE.getPlayers()) {
                if (player.isOnline()) {
                    player.onLoggedOut(player.getPlayer());
                }
            }

            LOGGED_IN_PLAYERS.clear();
            INSTANCE.save();
            new UniverseClosedEvent(INSTANCE).post();
            INSTANCE = null;
        }
    }

    @SubscribeEvent
    public void onWorldSaved(WorldEvent.Save event) {
        if (loaded()) {
            INSTANCE.save();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (loaded() && event.player instanceof EntityPlayerMP playerMP && !ServerUtils.isFake(playerMP)) {
            LOGGED_IN_PLAYERS.add(playerMP.getUniqueID());
            INSTANCE.onPlayerLoggedIn(playerMP);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (loaded() && event.player instanceof EntityPlayerMP playerMP
                && LOGGED_IN_PLAYERS.remove(playerMP.getUniqueID())) {
            ForgePlayer p = INSTANCE.getPlayer(playerMP.getGameProfile());

            if (p != null) {
                vanishedPlayers.remove(p);
                p.onLoggedOut(playerMP);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (event.entity instanceof EntityPlayerMP playerMP) {
            ForgePlayer p = INSTANCE.getPlayer(playerMP.getGameProfile());

            if (p != null) {
                p.tempPlayer = playerMP;
            }

            INSTANCE.clearCache();

            if (p != null) {
                p.tempPlayer = null;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onTickEvent(TickEvent.WorldTickEvent event) {
        if (!loaded()) {
            return;
        }

        Universe universe = get();

        if (event.phase == TickEvent.Phase.START) {
            universe.ticks = Ticks.get(event.world.getTotalWorldTime());
        } else if (!event.world.isRemote && event.world.provider.dimensionId == 0) {
            universe.taskScheduler.tick(universe);

            if (universe.server.isSinglePlayer()) {
                boolean cheats = universe.server.getConfigurationManager().commandsAllowedForAll;

                if (universe.prevCheats != cheats) {
                    universe.prevCheats = cheats;
                    universe.clearCache();
                }
            }
        }
    }

    // Event handler end //

    public final MinecraftServer server;
    public WorldServer world;
    private final UniverseRepository repository;
    /** @deprecated Use player registration and lookup methods instead. */
    @Deprecated
    public final Map<UUID, ForgePlayer> players;
    /** @deprecated Use {@link #setVanished(ForgePlayer, boolean)} and {@link #getVanishedPlayersView()}. */
    @Deprecated
    public final Set<ForgePlayer> vanishedPlayers;
    public ForgeTeam fakePlayerTeam;
    public FakeForgePlayer fakePlayer;
    private final UniversePersistence persistence;
    private final UniverseTaskScheduler taskScheduler;
    public Ticks ticks;
    private boolean prevCheats = false;
    public File dataFolder;
    public File latModFolder;
    public boolean gameRulesFlipped;
    public final Map<String, String> flippedRulesSaveState;

    public Universe(MinecraftServer s) {
        server = s;
        ticks = Ticks.NO_TICKS;
        repository = new UniverseRepository();
        persistence = new UniversePersistence(this);
        taskScheduler = new UniverseTaskScheduler();
        players = repository.mutablePlayers();
        vanishedPlayers = repository.mutableVanishedPlayers();
        gameRulesFlipped = false;
        flippedRulesSaveState = new HashMap<>();
        repository.initialize(this);
    }

    public void markDirty() {
        persistence.markDirty();
    }

    void markChildDirty() {
        persistence.markChildDirty();
    }

    public UUID getUUID() {
        return persistence.getUuid();
    }

    public void scheduleTask(Task task) {
        scheduleTask(task, true);
    }

    public void scheduleTask(Task task, boolean condition) {
        taskScheduler.schedule(this, task, condition);
    }

    private void load() {
        persistence.load();
    }

    private void save() {
        persistence.save();
    }

    public File getWorldDirectory() {
        return server.worldServers[0].getSaveHandler().getWorldDirectory();
    }

    private void onPlayerLoggedIn(EntityPlayerMP player) {
        if (!player.mcServer.getConfigurationManager().func_152607_e(player.getGameProfile())) { // canjoin
            return;
        }

        ForgePlayer p = getPlayer(player.getGameProfile());

        if (p == null) {
            p = new ForgePlayer(this, player.getUniqueID(), player.getCommandSenderName());
            repository.putPlayer(p.getId(), p);
            p.onLoggedIn(player, this, true);
        } else {
            if (!p.getId().equals(player.getUniqueID()) || !p.getName().equals(player.getCommandSenderName())) {
                File old = p.getDataFile();
                repository.removePlayer(p.getId());
                p.profile = new GameProfile(player.getUniqueID(), player.getCommandSenderName());
                repository.putPlayer(p.getId(), p);
                old.renameTo(p.getDataFile());
                p.markDirty();
                p.getTeam().markDirty();
                markDirty();
            }

            if (ServerUtils.isVanished(player)) {
                vanishedPlayers.add(p);
                player.capabilities.disableDamage = true;
            }

            p.onLoggedIn(player, this, false);
        }
    }

    public Collection<ForgePlayer> getPlayers() {
        return repository.players();
    }

    public Collection<ForgePlayer> getPlayersView() {
        return Collections.unmodifiableCollection(repository.players());
    }

    public void registerPlayer(ForgePlayer player) {
        registerPlayer(player.getId(), player);
    }

    public void registerPlayer(UUID id, ForgePlayer player) {
        repository.putPlayer(id, player);
    }

    public Collection<ForgePlayer> getVanishedPlayers() {
        return vanishedPlayers;
    }

    public Set<ForgePlayer> getVanishedPlayersView() {
        return Collections.unmodifiableSet(vanishedPlayers);
    }

    public boolean setVanished(ForgePlayer player, boolean vanished) {
        return vanished ? vanishedPlayers.add(player) : vanishedPlayers.remove(player);
    }

    @Nullable
    public ForgePlayer getPlayer(@Nullable UUID id) {
        if (id == null) {
            return null;
        } else if (id.equals(ServerUtils.FAKE_PLAYER_PROFILE.getId())) {
            return fakePlayer;
        }

        return repository.getPlayer(id);
    }

    @Nullable
    public ForgePlayer getPlayer(CharSequence nameOrId) {
        if (fakePlayer != null && ServerUtils.FAKE_PLAYER_PROFILE.getName().equalsIgnoreCase(nameOrId.toString())) {
            return fakePlayer;
        }

        List<ForgePlayer> matches = searchPlayers(nameOrId);
        return matches.isEmpty() ? null : matches.get(0);
    }

    @Nullable
    public ForgePlayer findPlayerExact(CharSequence nameOrId) {
        String query = nameOrId.toString();
        UUID id = StringUtils.fromString(query);
        if (id != null) {
            return getPlayer(id);
        }

        List<ForgePlayer> exactMatches = findPlayersByName(query, true);
        return exactMatches.size() == 1 ? exactMatches.get(0) : null;
    }

    public List<ForgePlayer> searchPlayers(CharSequence nameOrId) {
        String query = nameOrId.toString();
        if (query.isEmpty()) {
            return Collections.emptyList();
        }

        UUID id = StringUtils.fromString(query);
        if (id != null) {
            ForgePlayer player = getPlayer(id);
            return player == null ? Collections.emptyList() : Collections.singletonList(player);
        }

        List<ForgePlayer> exactMatches = findPlayersByName(query, true);
        if (!exactMatches.isEmpty()) {
            return Collections.unmodifiableList(exactMatches);
        }

        return Collections.unmodifiableList(findPlayersByName(query, false));
    }

    private List<ForgePlayer> findPlayersByName(String query, boolean exact) {
        String normalizedQuery = query.toLowerCase(java.util.Locale.ROOT);
        List<ForgePlayer> matches = new ArrayList<>();
        Set<UUID> matchedIds = new HashSet<>();
        if (exact && fakePlayer != null
                && ServerUtils.FAKE_PLAYER_PROFILE.getName().equalsIgnoreCase(normalizedQuery)) {
            matches.add(fakePlayer);
            matchedIds.add(fakePlayer.getId());
        }

        for (ForgePlayer player : repository.players()) {
            String normalizedName = player.getName().toLowerCase(java.util.Locale.ROOT);
            if ((exact ? normalizedName.equals(normalizedQuery) : normalizedName.contains(normalizedQuery))
                    && matchedIds.add(player.getId())) {
                matches.add(player);
            }
        }

        matches.sort(
                Comparator.comparing((ForgePlayer player) -> player.getName().toLowerCase(java.util.Locale.ROOT))
                        .thenComparing(ForgePlayer::getId));
        return matches;
    }

    public ForgePlayer getPlayer(@Nullable ICommandSender sender) {
        if (sender instanceof EntityPlayerMP player) {

            if (ServerUtils.isFake(player)) {
                fakePlayer.tempPlayer = player;
                fakePlayer.clearCache();
                return fakePlayer;
            }

            ForgePlayer p = getPlayer(player.getGameProfile());

            if (p == null) {
                throw new NullPointerException(
                        "Player can't be found for " + player.getCommandSenderName()
                                + ":"
                                + StringUtils.fromUUID(player.getUniqueID())
                                + ":"
                                + player.getClass().getName());
            }

            return p;
        }

        throw new IllegalArgumentException("Sender is not a player!");
    }

    public ForgePlayer getPlayer(ForgePlayer player) {
        ForgePlayer p = getPlayer(player.getId());
        return p == null ? player : p;
    }

    @Nullable
    public ForgePlayer getPlayer(GameProfile profile) {
        ForgePlayer player = getPlayer(profile.getId());

        if (player == null
                && ServerUtilitiesConfig.general.merge_offline_mode_players.get(!server.isDedicatedServer())) {
            String profileName = profile.getName();
            for (ForgePlayer p : repository.players()) {
                if (p.getName().equalsIgnoreCase(profileName)) {
                    player = p;
                    break;
                }
            }

            if (player != null) {
                repository.putPlayer(profile.getId(), player);
                player.markDirty();
            }
        }

        return player;
    }

    public Collection<ForgeTeam> getTeams() {
        return repository.teamsView();
    }

    public ForgeTeam getTeam(String id) {
        if (id.isEmpty()) {
            return repository.noneTeam();
        } else if (id.length() == 4) {
            try {
                ForgeTeam team = getTeam(Integer.valueOf(id, 16).shortValue());

                if (team.isValid()) {
                    return team;
                }
            } catch (NumberFormatException ignored) {
                // Not a hexadecimal team UID; continue with regular ID lookup.
            }
        }

        if (id.equals("fakeplayer")) {
            return fakePlayerTeam;
        }

        ForgeTeam team = repository.getTeam(id);

        if (team != null) {
            return team;
        }

        ForgePlayer player = getPlayer(id);

        if (player != null) {
            return player.getTeam();
        }

        return repository.noneTeam();
    }

    public ForgeTeam getTeam(short uid) {
        if (uid == 0) {
            return repository.noneTeam();
        } else if (uid == 1) {
            return fakePlayerTeam;
        }

        ForgeTeam team = repository.getTeam(uid);
        return team == null ? repository.noneTeam() : team;
    }

    public Collection<ForgePlayer> getOnlinePlayers() {
        Collection<ForgePlayer> set = Collections.emptySet();

        for (ForgePlayer player : getPlayers()) {
            if (player.isOnline()) {
                if (set.isEmpty()) {
                    set = new HashSet<>();
                }

                set.add(player);
            }
        }

        return set;
    }

    public void clearCache() {
        new UniverseClearCacheEvent(this).post();
        getTeams().forEach(ForgeTeam::clearCache);
        getPlayers().forEach(ForgePlayer::clearCache);
        fakePlayer.clearCache();
        if (Ranks.INSTANCE != null) {
            Ranks.INSTANCE.clearCache();
        }
    }

    public void addTeam(ForgeTeam team) {
        if (team.universe != this) {
            throw new IllegalArgumentException("Team belongs to a different universe");
        }
        repository.addTeam(team);
    }

    public void removeTeam(ForgeTeam team) {
        File folder = new File(dataFolder, "teams/");
        new ForgeTeamDeletedEvent(team, folder).post();
        repository.removeTeam(team);
        FileUtils.deleteSafe(new File(folder, team.getId() + ".dat"));
        markDirty();
        clearCache();
    }

    public short generateTeamUID(short id) {
        while (id == 0 || id == 1 || id == 2 || repository.containsTeamUid(id)) {
            id = (short) MathUtils.RAND.nextInt();
        }

        return id;
    }

    public boolean shouldLoadLatmod() {
        return persistence.shouldLoadLatmod();
    }
}

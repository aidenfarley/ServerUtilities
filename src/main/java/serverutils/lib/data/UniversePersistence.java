package serverutils.lib.data;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import serverutils.ServerUtilities;
import serverutils.ServerUtilitiesConfig;
import serverutils.data.BackwardsCompat;
import serverutils.events.ServerReloadEvent;
import serverutils.events.player.ForgePlayerLoadedEvent;
import serverutils.events.player.ForgePlayerSavedEvent;
import serverutils.events.team.ForgeTeamLoadedEvent;
import serverutils.events.team.ForgeTeamSavedEvent;
import serverutils.events.universe.UniverseLoadedEvent;
import serverutils.events.universe.UniverseSavedEvent;
import serverutils.lib.EnumReloadType;
import serverutils.lib.EnumTeamColor;
import serverutils.lib.io.DataReader;
import serverutils.lib.util.FileUtils;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.StringUtils;

final class UniversePersistence {

    private final Universe universe;
    private UUID uuid;
    private boolean dirty;
    private boolean checkSaving;
    private long dirtyVersion;

    UniversePersistence(Universe universe) {
        this.universe = universe;
        uuid = null;
        dirty = false;
        checkSaving = true;
        dirtyVersion = 0L;
    }

    void markDirty() {
        dirty = true;
        checkSaving = true;
        dirtyVersion++;
    }

    void markChildDirty() {
        checkSaving = true;
    }

    UUID getUuid() {
        if (uuid == null) {
            uuid = UUID.randomUUID();
            markDirty();
        }

        return uuid;
    }

    void load() {
        universe.dataFolder = new File(universe.getWorldDirectory(), "serverutilities/");
        universe.latModFolder = new File(universe.getWorldDirectory(), "LatMod");
        NBTTagCompound universeData = NBTUtils.readNBT(new File(universe.dataFolder, "universe.dat"));

        if (universeData == null) {
            universeData = new NBTTagCompound();
        }

        migrateWorldDataJson(universeData);
        uuid = StringUtils.fromString(universeData.getString("UUID"));

        if (uuid != null && uuid.getLeastSignificantBits() == 0L && uuid.getMostSignificantBits() == 0L) {
            uuid = null;
        }

        NBTTagCompound data = universeData.getCompoundTag("Data");
        new UniverseLoadedEvent.Pre(universe, data).post();

        Map<UUID, NBTTagCompound> playerNbt = new HashMap<>();
        Map<String, NBTTagCompound> teamNbt = new HashMap<>();
        loadPlayers(playerNbt);
        loadTeams(teamNbt);
        createFakePlayerTeam();

        new UniverseLoadedEvent.CreateServerTeams(universe).post();
        hydratePlayers(playerNbt);
        hydrateTeams(teamNbt);
        hydrateUniverseData(universeData);

        new UniverseLoadedEvent.Post(universe, data).post();

        if (shouldLoadLatmod()) {
            BackwardsCompat.load();
        }

        new UniverseLoadedEvent.Finished(universe).post();
        ServerUtilitiesAPI.reloadServer(universe, universe.server, EnumReloadType.CREATED, ServerReloadEvent.ALL);
    }

    private void migrateWorldDataJson(NBTTagCompound universeData) {
        File worldDataJsonFile = new File(universe.getWorldDirectory(), "world_data.json");
        JsonElement worldData = DataReader.get(worldDataJsonFile).safeJson();

        if (!worldData.isJsonObject()) {
            return;
        }

        JsonObject jsonWorldData = worldData.getAsJsonObject();
        if (jsonWorldData.has("world_id")) {
            universeData.setString("UUID", jsonWorldData.get("world_id").getAsString());
        }

        if (worldDataJsonFile.exists() && !worldDataJsonFile.delete()) {
            ServerUtilities.LOGGER.warn("Failed to delete migrated world data at {}", worldDataJsonFile);
        }
    }

    private void loadPlayers(Map<UUID, NBTTagCompound> playerNbt) {
        File[] files = new File(universe.dataFolder, "players").listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!isDataFile(file)) {
                continue;
            }

            try {
                loadPlayer(file, playerNbt);
            } catch (RuntimeException ex) {
                ServerUtilities.LOGGER.error("Failed to load player data from {}", file.getAbsolutePath(), ex);
            }
        }
    }

    private void loadPlayer(File file, Map<UUID, NBTTagCompound> playerNbt) {
        NBTTagCompound nbt = NBTUtils.readNBT(file);
        if (nbt == null) {
            return;
        }

        String uuidString = nbt.getString("UUID");
        if (uuidString.isEmpty()) {
            uuidString = FileUtils.getBaseName(file);
            FileUtils.deleteSafe(file);
        }

        UUID playerId = StringUtils.fromString(uuidString);
        if (playerId != null) {
            playerNbt.put(playerId, nbt);
            universe.registerPlayer(new ForgePlayer(universe, playerId, nbt.getString("Name")));
        } else {
            ServerUtilities.LOGGER.warn("Ignoring player data with invalid UUID in {}", file.getAbsolutePath());
        }
    }

    private void loadTeams(Map<String, NBTTagCompound> teamNbt) {
        File[] files = new File(universe.dataFolder, "teams").listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (!isDataFile(file)) {
                continue;
            }

            try {
                loadTeam(file, teamNbt);
            } catch (RuntimeException ex) {
                ServerUtilities.LOGGER.error("Failed to load team data from {}", file.getAbsolutePath(), ex);
            }
        }
    }

    private void loadTeam(File file, Map<String, NBTTagCompound> teamNbt) {
        NBTTagCompound nbt = NBTUtils.readNBT(file);
        if (nbt == null) {
            return;
        }

        String id = nbt.getString("ID");
        if (id.isEmpty()) {
            id = FileUtils.getBaseName(file);
        }

        teamNbt.put(id, nbt);
        short storedUid = nbt.getShort("UID");
        ForgeTeam team = new ForgeTeam(
                universe,
                universe.generateTeamUID(storedUid),
                id,
                TeamType.NAME_MAP.get(nbt.getString("Type")));
        universe.addTeam(team);

        if (storedUid == 0) {
            team.markDirty();
        }
    }

    private static boolean isDataFile(File file) {
        return file.isFile() && file.getName().endsWith(".dat")
                && file.getName().indexOf('.') == file.getName().lastIndexOf('.');
    }

    private void createFakePlayerTeam() {
        universe.fakePlayerTeam = new ForgeTeam(universe, (short) 1, "fakeplayer", TeamType.SERVER_NO_SAVE) {

            @Override
            public void markDirty() {
                universe.markDirty();
            }
        };

        universe.fakePlayer = new FakeForgePlayer(universe);
        universe.fakePlayer.setTeamFromLoad(universe.fakePlayerTeam);
        universe.fakePlayerTeam.setColor(EnumTeamColor.GRAY);
    }

    private void hydratePlayers(Map<UUID, NBTTagCompound> playerNbt) {
        for (ForgePlayer player : universe.getPlayers()) {
            NBTTagCompound nbt = playerNbt.get(player.getId());
            if (nbt != null && !nbt.hasNoTags()) {
                player.setTeamFromLoad(universe.getTeam(nbt.getString("TeamID")));
                player.deserializeNBT(nbt);
            }

            new ForgePlayerLoadedEvent(player).post();
        }
    }

    private void hydrateTeams(Map<String, NBTTagCompound> teamNbt) {
        for (ForgeTeam team : universe.getTeams()) {
            if (!team.type.save) {
                continue;
            }

            NBTTagCompound nbt = teamNbt.get(team.getId());
            if (nbt != null && !nbt.hasNoTags()) {
                team.deserializeNBT(nbt);
            }

            new ForgeTeamLoadedEvent(team).post();
        }
    }

    private void hydrateUniverseData(NBTTagCompound universeData) {
        if (universeData.hasKey("FakePlayer")) {
            universe.fakePlayer.deserializeNBT(universeData.getCompoundTag("FakePlayer"));
        }

        if (universeData.hasKey("FakeTeam")) {
            universe.fakePlayerTeam.deserializeNBT(universeData.getCompoundTag("FakeTeam"));
        }

        universe.fakePlayerTeam.setStoredOwner(universe.fakePlayer);

        if (!universeData.hasKey("GameRulesState")) {
            return;
        }

        NBTTagCompound gameRulesState = universeData.getCompoundTag("GameRulesState");
        universe.gameRulesFlipped = gameRulesState.getBoolean("Flipped");
        NBTTagCompound savedRules = gameRulesState.getCompoundTag("SavedRules");
        for (String key : NBTUtils.getKeySet(savedRules)) {
            universe.flippedRulesSaveState.put(key, savedRules.getString(key));
        }
    }

    void save() {
        if (!checkSaving) {
            return;
        }

        boolean allSaved = saveUniverse();
        allSaved &= savePlayers();
        allSaved &= saveTeams();
        checkSaving = !allSaved || hasDirtyData();
    }

    private boolean saveUniverse() {
        if (!dirty) {
            return true;
        }

        if (ServerUtilitiesConfig.debugging.print_more_info) {
            ServerUtilities.LOGGER.info("Saving universe data");
        }

        UUID universeUuid = getUuid();
        long savingVersion = dirtyVersion;
        NBTTagCompound universeData = new NBTTagCompound();
        NBTTagCompound data = new NBTTagCompound();
        new UniverseSavedEvent(universe, data).post();
        universeData.setTag("Data", data);
        universeData.setString("UUID", StringUtils.fromUUID(universeUuid));
        universeData.setTag("FakePlayer", universe.fakePlayer.serializeNBT());
        universeData.setTag("FakeTeam", universe.fakePlayerTeam.serializeNBT());

        NBTTagCompound gameRulesState = new NBTTagCompound();
        gameRulesState.setBoolean("Flipped", universe.gameRulesFlipped);
        NBTTagCompound savedRules = new NBTTagCompound();
        for (Map.Entry<String, String> entry : universe.flippedRulesSaveState.entrySet()) {
            savedRules.setString(entry.getKey(), entry.getValue());
        }

        gameRulesState.setTag("SavedRules", savedRules);
        universeData.setTag("GameRulesState", gameRulesState);
        if (!NBTUtils.writeNBTChecked(new File(universe.dataFolder, "universe.dat"), universeData)) {
            return false;
        }

        if (dirtyVersion == savingVersion) {
            dirty = false;
        }
        return true;
    }

    private boolean savePlayers() {
        boolean allSaved = true;
        for (ForgePlayer player : universe.getPlayers()) {
            if (!player.isDirty()) {
                continue;
            }

            if (ServerUtilitiesConfig.debugging.print_more_info) {
                ServerUtilities.LOGGER.info("Saved player data for {}", player.getName());
            }

            NBTTagCompound nbt = player.serializeNBT();
            nbt.setString("Name", player.getName());
            nbt.setString("UUID", StringUtils.fromUUID(player.getId()));
            nbt.setString("TeamID", player.getTeam().getId());
            if (NBTUtils.writeNBTChecked(player.getDataFile(), nbt)) {
                player.markSaved();
                new ForgePlayerSavedEvent(player).post();
            } else {
                allSaved = false;
            }
        }
        return allSaved;
    }

    private boolean hasDirtyData() {
        if (dirty) {
            return true;
        }
        for (ForgePlayer player : universe.getPlayers()) {
            if (player.isDirty()) {
                return true;
            }
        }
        for (ForgeTeam team : universe.getTeams()) {
            if (team.isDirty()) {
                return true;
            }
        }
        return false;
    }

    private boolean saveTeams() {
        boolean allSaved = true;
        for (ForgeTeam team : universe.getTeams()) {
            if (!team.isDirty()) {
                continue;
            }

            if (ServerUtilitiesConfig.debugging.print_more_info) {
                ServerUtilities.LOGGER.info("Saved team data for {}", team.getId());
            }

            File file = team.getDataFile("");
            if (team.type.save && team.isValid()) {
                NBTTagCompound nbt = team.serializeNBT();
                nbt.setString("ID", team.getId());
                nbt.setShort("UID", team.getUID());
                nbt.setString("Type", team.type.getName());
                if (NBTUtils.writeNBTChecked(file, nbt)) {
                    team.markSaved();
                    new ForgeTeamSavedEvent(team).post();
                } else {
                    allSaved = false;
                }
            } else if (file.exists()) {
                try {
                    if (FileUtils.delete(file)) {
                        team.markSaved();
                    } else {
                        ServerUtilities.LOGGER.warn("Failed to delete invalid team data at {}", file);
                        allSaved = false;
                    }
                } catch (RuntimeException ex) {
                    ServerUtilities.LOGGER.error("Failed to delete invalid team data at " + file, ex);
                    allSaved = false;
                }
            } else {
                team.markSaved();
            }
        }
        return allSaved;
    }

    boolean shouldLoadLatmod() {
        return universe.latModFolder.exists() && !universe.dataFolder.exists();
    }
}

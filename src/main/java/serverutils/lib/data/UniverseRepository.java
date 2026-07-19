package serverutils.lib.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

final class UniverseRepository {

    private final Map<UUID, ForgePlayer> players = new HashMap<>();
    private final Set<ForgePlayer> vanishedPlayers = new ObjectOpenHashSet<>();
    private final Map<String, ForgeTeam> teams = new HashMap<>();
    private final Map<Short, ForgeTeam> teamsByUid = new HashMap<>();
    private final Collection<ForgeTeam> teamsView = Collections.unmodifiableCollection(teams.values());
    private ForgeTeam noneTeam;

    UniverseRepository() {}

    void initialize(Universe universe) {
        if (noneTeam != null) {
            throw new IllegalStateException("Universe repository has already been initialized");
        }
        ForgeTeam team = new ForgeTeam(universe, (short) 0, "", TeamType.NONE, false);
        noneTeam = team;
        team.initializePersistence();
    }

    Map<UUID, ForgePlayer> mutablePlayers() {
        return players;
    }

    Set<ForgePlayer> mutableVanishedPlayers() {
        return vanishedPlayers;
    }

    Collection<ForgePlayer> players() {
        return players.values();
    }

    ForgePlayer getPlayer(UUID id) {
        return players.get(id);
    }

    void putPlayer(UUID id, ForgePlayer player) {
        players.put(id, player);
    }

    void removePlayer(UUID id) {
        players.remove(id);
    }

    Collection<ForgeTeam> teams() {
        return teams.values();
    }

    Collection<ForgeTeam> teamsView() {
        return teamsView;
    }

    ForgeTeam getTeam(String id) {
        return teams.get(id);
    }

    ForgeTeam getTeam(short uid) {
        return teamsByUid.get(uid);
    }

    ForgeTeam noneTeam() {
        if (noneTeam == null) {
            throw new IllegalStateException("Universe repository is not initialized");
        }
        return noneTeam;
    }

    void addTeam(ForgeTeam team) {
        ForgeTeam teamWithId = teams.get(team.getId());
        ForgeTeam teamWithUid = teamsByUid.get(team.getUID());
        if (teamWithId != null && teamWithId != team) {
            throw new IllegalArgumentException("Duplicate team ID: " + team.getId());
        }
        if (teamWithUid != null && teamWithUid != team) {
            throw new IllegalArgumentException("Duplicate team UID: " + team.getUIDCode());
        }
        teamsByUid.put(team.getUID(), team);
        teams.put(team.getId(), team);
    }

    void removeTeam(ForgeTeam team) {
        teamsByUid.remove(team.getUID(), team);
        teams.remove(team.getId(), team);
    }

    boolean containsTeamUid(short uid) {
        return teamsByUid.containsKey(uid);
    }
}

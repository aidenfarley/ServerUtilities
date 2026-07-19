package serverutils.lib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

class UniverseTest {

    @Test
    void explicitSingletonAccessReportsUnloadedLifecycle() {
        assertNull(Universe.getIfLoaded());
        assertThrows(IllegalStateException.class, Universe::requireLoaded);
    }

    @Test
    void exactAndFuzzyPlayerSearchesAreDeterministic() {
        Universe universe = new Universe(null);
        universe.fakePlayer = new FakeForgePlayer(universe);
        ForgePlayer zed = player(universe, "ZedOne", "00000000-0000-0000-0000-000000000002");
        ForgePlayer alpha = player(universe, "AlphaOne", "00000000-0000-0000-0000-000000000001");

        assertSame(alpha, universe.findPlayerExact("AlphaOne"));
        assertSame(zed, universe.findPlayerExact(zed.getId().toString()));
        assertEquals(
                Arrays.asList("AlphaOne", "ZedOne"),
                universe.searchPlayers("one").stream().map(ForgePlayer::getName).collect(Collectors.toList()));
        assertSame(alpha, universe.getPlayer("one"));
    }

    @Test
    void ambiguousExactNamesAreReported() {
        Universe universe = new Universe(null);
        universe.fakePlayer = new FakeForgePlayer(universe);
        player(universe, "Duplicate", "00000000-0000-0000-0000-000000000001");
        player(universe, "duplicate", "00000000-0000-0000-0000-000000000002");

        assertNull(universe.findPlayerExact("DUPLICATE"));
        assertEquals(2, universe.searchPlayers("DUPLICATE").size());
    }

    @Test
    void legacyLookupKeepsFakePlayerPriority() {
        Universe universe = new Universe(null);
        universe.fakePlayer = new FakeForgePlayer(universe);
        player(universe, universe.fakePlayer.getName(), "00000000-0000-0000-0000-000000000001");

        assertSame(universe.fakePlayer, universe.getPlayer(universe.fakePlayer.getName()));
        assertNull(universe.findPlayerExact(universe.fakePlayer.getName()));
    }

    @Test
    void teamActionPayloadAcceptsUuidAndLegacyName() {
        Universe universe = new Universe(null);
        universe.fakePlayer = new FakeForgePlayer(universe);
        ForgePlayer actor = player(universe, "Actor", "00000000-0000-0000-0000-000000000001");
        ForgePlayer target = player(universe, "Target", "00000000-0000-0000-0000-000000000002");
        NBTTagCompound payload = new NBTTagCompound();

        payload.setString("player", target.getId().toString());
        assertSame(target, ServerUtilitiesTeamGuiActions.getPayloadPlayer(actor, payload));

        payload.setString("player", target.getName());
        assertSame(target, ServerUtilitiesTeamGuiActions.getPayloadPlayer(actor, payload));
    }

    @Test
    void duplicateTeamIndexesAreRejectedAtomically() {
        Universe universe = new Universe(null);
        ForgeTeam original = new ForgeTeam(universe, (short) 2, "original", TeamType.SERVER);
        universe.addTeam(original);

        ForgeTeam duplicateId = new ForgeTeam(universe, (short) 3, "original", TeamType.SERVER);
        assertThrows(IllegalArgumentException.class, () -> universe.addTeam(duplicateId));
        assertSame(original, universe.getTeam("original"));
        assertSame(original, universe.getTeam((short) 2));

        ForgeTeam duplicateUid = new ForgeTeam(universe, (short) 2, "duplicate_uid", TeamType.SERVER);
        assertThrows(IllegalArgumentException.class, () -> universe.addTeam(duplicateUid));
        assertSame(original, universe.getTeam("original"));
        assertSame(original, universe.getTeam((short) 2));
    }

    @Test
    void teamsFromOtherUniversesCannotBeRegistered() {
        Universe universe = new Universe(null);
        Universe otherUniverse = new Universe(null);
        ForgeTeam foreignTeam = new ForgeTeam(otherUniverse, (short) 2, "foreign", TeamType.SERVER);

        assertThrows(IllegalArgumentException.class, () -> universe.addTeam(foreignTeam));
    }

    private ForgePlayer player(Universe universe, String name, String id) {
        ForgePlayer player = new ForgePlayer(universe, UUID.fromString(id), name);
        universe.registerPlayer(player);
        return player;
    }
}

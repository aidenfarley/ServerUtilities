package serverutils.lib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import serverutils.lib.EnumTeamColor;
import serverutils.lib.EnumTeamStatus;

class ForgeTeamTest {

    private Universe universe;
    private ForgePlayer player;
    private TestForgeTeam team;

    @BeforeEach
    void setUp() {
        universe = new Universe(null);
        universe.fakePlayer = new FakeForgePlayer(universe);

        player = new ForgePlayer(universe, UUID.randomUUID(), "TestPlayer");
        universe.registerPlayer(player);

        team = new TestForgeTeam(universe, (short) 2, "test_team", TeamType.SERVER);
        universe.addTeam(team);
        player.setTeam(team);
    }

    @Test
    void removingLastMemberPostsOneEventAndClearsMembership() {
        assertEquals(1, team.getMembers().size());

        assertTrue(team.removeMember(player));

        assertEquals(1, team.playerLeftEventCount);
        assertSame(universe.getTeam(""), player.getTeam());
        assertFalse(team.isMember(player));
        assertTrue(player.isDirty());
        assertTrue(team.isDirty());
    }

    @Test
    void domainCollectionsExposeReadOnlyViews() {
        assertThrows(UnsupportedOperationException.class, () -> universe.getPlayersView().clear());
        assertThrows(UnsupportedOperationException.class, () -> universe.getVanishedPlayersView().clear());
        assertThrows(UnsupportedOperationException.class, () -> universe.getTeams().clear());
        assertThrows(UnsupportedOperationException.class, () -> team.getPlayerStatusesView().clear());
        assertThrows(UnsupportedOperationException.class, () -> team.getClaimedChunksView().clear());
        assertSame(team, universe.getTeam(team.getId()));
        assertSame(team, universe.getTeam(team.getUID()));
    }

    @Test
    void teamEqualityUsesUidWithinOneUniverseOnly() {
        ForgeTeam sameIdentity = new ForgeTeam(universe, team.getUID(), "same_uid", TeamType.SERVER);
        Universe otherUniverse = new Universe(null);
        ForgeTeam otherUniverseTeam = new ForgeTeam(otherUniverse, team.getUID(), "test_team", TeamType.SERVER);

        assertEquals(team, sameIdentity);
        assertEquals(team.hashCode(), sameIdentity.hashCode());
        assertNotEquals(team, otherUniverseTeam);
        assertNotEquals(team, Integer.valueOf(team.getUID()));
        assertNotEquals(Integer.valueOf(team.getUID()), team);
    }

    @Test
    void changingTeamValidatesOwnershipAndMaintainsMutationState() {
        ForgeTeam nextTeam = new ForgeTeam(universe, (short) 3, "next_team", TeamType.SERVER);
        universe.addTeam(nextTeam);
        player.markSaved();
        team.markSaved();
        nextTeam.markSaved();
        player.cachedPlayerNBT = new NBTTagCompound();

        player.setTeam(nextTeam);

        assertSame(nextTeam, player.getTeam());
        assertNull(player.cachedPlayerNBT);
        assertTrue(player.isDirty());
        assertTrue(team.isDirty());
        assertTrue(nextTeam.isDirty());
        assertThrows(NullPointerException.class, () -> player.setTeam(null));

        Universe otherUniverse = new Universe(null);
        ForgeTeam foreignTeam = new ForgeTeam(otherUniverse, (short) 4, "foreign", TeamType.SERVER);
        assertThrows(IllegalArgumentException.class, () -> player.setTeam(foreignTeam));
        assertSame(nextTeam, player.getTeam());
    }

    @Test
    void loadTimeTeamHydrationAvoidsFalseDirtyState() {
        ForgeTeam loadedTeam = new ForgeTeam(universe, (short) 3, "loaded_team", TeamType.SERVER);
        universe.addTeam(loadedTeam);
        player.markSaved();
        loadedTeam.markSaved();

        player.setTeamFromLoad(loadedTeam);

        assertSame(loadedTeam, player.getTeam());
        assertFalse(player.isDirty());
        assertFalse(loadedTeam.isDirty());
    }

    @Test
    void initializingOwnerKeepsOwnerAndMembershipConsistent() {
        ForgeTeam playerTeam = new ForgeTeam(universe, (short) 3, "owned_team", TeamType.PLAYER);
        universe.addTeam(playerTeam);
        player.markSaved();
        team.markSaved();
        playerTeam.markSaved();

        playerTeam.initializeOwner(player);

        assertSame(player, playerTeam.getOwner());
        assertSame(playerTeam, player.getTeam());
        assertTrue(player.isDirty());
        assertTrue(playerTeam.isDirty());
        assertThrows(IllegalStateException.class, () -> team.initializeOwner(player));

        Universe otherUniverse = new Universe(null);
        ForgePlayer foreignPlayer = new ForgePlayer(otherUniverse, UUID.randomUUID(), "Foreign");
        assertThrows(IllegalArgumentException.class, () -> playerTeam.setStoredOwner(foreignPlayer));
    }

    @Test
    void vanishedStateIsChangedThroughUniverse() {
        assertTrue(universe.setVanished(player, true));
        assertTrue(universe.getVanishedPlayersView().contains(player));
        assertTrue(universe.setVanished(player, false));
        assertFalse(universe.getVanishedPlayersView().contains(player));
    }

    @Test
    void persistedTeamStateAndMembershipRoundTripWithoutChangingNbtKeys() {
        ForgePlayer enemy = registerPlayer("Enemy");
        ForgePlayer requester = registerPlayer("Requester");
        team.setTitle("Test title");
        team.setDesc("Test description");
        team.setColor(EnumTeamColor.RED);
        team.setIcon("serverutils:settings");
        team.setFreeToJoin(true);
        team.setStatus(enemy, EnumTeamStatus.ENEMY);
        team.setRequestingInvite(requester, true);

        NBTTagCompound serialized = team.serializeNBT();
        assertEquals("Test title", serialized.getString("Title"));
        assertEquals("Test description", serialized.getString("Desc"));
        assertEquals("red", serialized.getString("Color"));
        assertEquals("serverutils:settings", serialized.getString("Icon"));
        assertTrue(serialized.hasKey("Players"));
        assertTrue(serialized.hasKey("RequestingInvite"));
        assertTrue(serialized.hasKey("Data"));

        ForgeTeam restored = new ForgeTeam(universe, (short) 3, "restored", TeamType.SERVER);
        universe.addTeam(restored);
        restored.deserializeNBT(serialized);

        assertEquals("Test description", restored.getDesc());
        assertEquals(EnumTeamColor.RED, restored.getColor());
        assertTrue(restored.isFreeToJoin());
        assertTrue(restored.isEnemy(enemy));
        assertTrue(restored.isRequestingInvite(requester));
        assertEquals(EnumTeamStatus.ENEMY, restored.getPlayerStatusesView().get(enemy));
    }

    private ForgePlayer registerPlayer(String name) {
        ForgePlayer registered = new ForgePlayer(universe, UUID.randomUUID(), name);
        universe.registerPlayer(registered);
        return registered;
    }

    private static final class TestForgeTeam extends ForgeTeam {

        private int playerLeftEventCount;

        private TestForgeTeam(Universe universe, short id, String name, TeamType type) {
            super(universe, id, name, type);
        }

        @Override
        void postPlayerLeftEvent(ForgePlayer player) {
            playerLeftEventCount++;
        }
    }
}

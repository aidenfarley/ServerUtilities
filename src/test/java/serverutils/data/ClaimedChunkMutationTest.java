package serverutils.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.TeamType;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;

class ClaimedChunkMutationTest {

    @Test
    void claimedChunkMutationsValidateOwnershipAndMarkTeamDirty() {
        Universe universe = new Universe(null);
        ForgeTeam team = new ForgeTeam(universe, (short) 2, "team", TeamType.SERVER);
        ForgeTeam otherTeam = new ForgeTeam(universe, (short) 3, "other", TeamType.SERVER);
        ClaimedChunk ownedChunk = new ClaimedChunk(new ChunkDimPos(1, 2, 0), new ServerUtilitiesTeamData(team));
        ClaimedChunk foreignChunk = new ClaimedChunk(new ChunkDimPos(3, 4, 0), new ServerUtilitiesTeamData(otherTeam));

        team.markSaved();
        assertTrue(team.addClaimedChunk(ownedChunk));
        assertTrue(team.isDirty());
        assertFalse(team.addClaimedChunk(ownedChunk));
        assertThrows(IllegalArgumentException.class, () -> team.addClaimedChunk(foreignChunk));

        team.markSaved();
        assertTrue(team.removeClaimedChunk(ownedChunk));
        assertTrue(team.isDirty());
        assertFalse(team.removeClaimedChunk(ownedChunk));
        assertThrows(IllegalArgumentException.class, () -> team.removeClaimedChunk(foreignChunk));
    }
}

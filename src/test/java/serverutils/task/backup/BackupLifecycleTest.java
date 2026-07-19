package serverutils.task.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BackupLifecycleTest {

    @Test
    void backToBackRunsCannotOverlapOrReleaseEachOthersSlot() {
        BackupLifecycle lifecycle = new BackupLifecycle();
        BackupLifecycle.Run first = lifecycle.tryBegin();

        assertNotNull(first);
        assertTrue(lifecycle.isInProgress());
        assertNull(lifecycle.tryBegin());

        assertTrue(lifecycle.complete(first));
        BackupLifecycle.Run second = lifecycle.tryBegin();
        assertNotNull(second);

        assertFalse(lifecycle.complete(first));
        assertTrue(lifecycle.isCurrent(second));
        assertTrue(lifecycle.complete(second));
        assertFalse(lifecycle.isInProgress());
    }

    @Test
    void saveStateSnapshotDefensivelyCopiesEachExecution() {
        List<BackupSaveStateSnapshot.WorldState> source = new ArrayList<>();
        source.add(new BackupSaveStateSnapshot.WorldState(2, true));

        BackupSaveStateSnapshot snapshot = new BackupSaveStateSnapshot(source);
        source.clear();

        assertEquals(1, snapshot.worldStates().size());
        assertEquals(2, snapshot.worldStates().get(0).worldIndex);
        assertTrue(snapshot.worldStates().get(0).levelSaving);
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.worldStates().add(new BackupSaveStateSnapshot.WorldState(3, false)));
    }

    @Test
    void claimedScopeHonorsForcedRunsAndKeepsZeroClaimRunsFiltered() {
        assertEquals(BackupScope.CLAIMED_CHUNKS, BackupScope.select(true, false, true));
        assertEquals(BackupScope.CLAIMED_CHUNKS, BackupScope.select(false, true, true));
        assertTrue(BackupScope.CLAIMED_CHUNKS.isClaimedChunksOnly());

        assertEquals(BackupScope.FULL_WORLD, BackupScope.select(false, false, true));
        assertEquals(BackupScope.FULL_WORLD, BackupScope.select(true, false, false));
    }
}

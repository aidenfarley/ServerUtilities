package serverutils.task.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable copy of the world auto-save states changed for one backup execution. */
final class BackupSaveStateSnapshot {

    static final class WorldState {

        final int worldIndex;
        final boolean levelSaving;

        WorldState(int worldIndex, boolean levelSaving) {
            this.worldIndex = worldIndex;
            this.levelSaving = levelSaving;
        }
    }

    private final List<WorldState> worldStates;

    BackupSaveStateSnapshot(List<WorldState> worldStates) {
        this.worldStates = Collections.unmodifiableList(new ArrayList<>(worldStates));
    }

    List<WorldState> worldStates() {
        return worldStates;
    }
}

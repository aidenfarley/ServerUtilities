package serverutils.task.backup;

enum BackupScope {

    FULL_WORLD,
    CLAIMED_CHUNKS;

    static BackupScope select(boolean forceOnlyClaimed, boolean configuredOnlyClaimed, boolean claimedChunksActive) {
        return claimedChunksActive && (forceOnlyClaimed || configuredOnlyClaimed) ? CLAIMED_CHUNKS : FULL_WORLD;
    }

    boolean isClaimedChunksOnly() {
        return this == CLAIMED_CHUNKS;
    }
}

package serverutils.task.backup;

import static serverutils.ServerUtilitiesConfig.backups;
import static serverutils.ServerUtilitiesNotifications.BACKUP;
import static serverutils.lib.util.FileUtils.SizeUnit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;

import serverutils.ServerUtilities;
import serverutils.ServerUtilitiesConfig;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.FileUtils;
import serverutils.lib.util.ServerUtils;
import serverutils.lib.util.StringUtils;
import serverutils.lib.util.compression.ICompress;
import serverutils.task.Task;

public class BackupTask extends Task {

    public static final Pattern BACKUP_NAME_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}(.*)");
    public static final File BACKUP_TEMP_FOLDER = new File("serverutilities/temp/");
    public static final File BACKUP_FOLDER;
    private static final BackupLifecycle LIFECYCLE = new BackupLifecycle();
    private static volatile BackupExecution activeExecution;
    public static volatile ThreadBackup thread;
    public static boolean hadPlayer = false;
    private ICommandSender sender;
    private String customName = "";
    private boolean post = false;
    private boolean forceOnlyClaimed = false;
    private BackupExecution cleanupExecution;

    private static final class BackupExecution {

        private final BackupLifecycle.Run run;
        private final BackupSaveStateSnapshot saveStates;
        @Nullable
        private final ThreadBackup worker;

        private BackupExecution(BackupLifecycle.Run run, BackupSaveStateSnapshot saveStates,
                @Nullable ThreadBackup worker) {
            this.run = run;
            this.saveStates = saveStates;
            this.worker = worker;
        }
    }

    static {
        BACKUP_FOLDER = backups.backup_folder_path.isEmpty() ? new File("/backups/")
                : new File(backups.backup_folder_path);
        if (!BACKUP_FOLDER.exists()) BACKUP_FOLDER.mkdirs();
        clearOldBackups();
        ServerUtilities.LOGGER.info("Backups folder - {}", BACKUP_FOLDER.getAbsolutePath());
    }

    public BackupTask() {
        super(Ticks.HOUR.x(backups.backup_timer));
    }

    public BackupTask(@Nullable ICommandSender ics, String customName, final boolean forceOnlyClaimed) {
        this(ics, customName);
        this.forceOnlyClaimed = forceOnlyClaimed;
    }

    public BackupTask(@Nullable ICommandSender ics, String customName) {
        this.customName = customName;
        this.sender = ics;
    }

    public BackupTask(boolean postCleanup) {
        super(0);
        this.post = postCleanup;
        if (postCleanup) {
            this.cleanupExecution = activeExecution;
        }
    }

    private BackupTask(BackupExecution execution) {
        super(0);
        post = true;
        cleanupExecution = execution;
    }

    @Override
    public boolean isRepeatable() {
        return !post;
    }

    @Override
    public void execute(Universe universe) {
        if (post) {
            if (cleanupExecution != null) {
                postBackup(universe, cleanupExecution);
            }
            return;
        }
        boolean auto = sender == null;

        if (auto && !backups.enable_backups) return;

        MinecraftServer server = universe.server;
        if (auto && backups.need_online_players) {
            if (!hasOnlinePlayers(server) && !hadPlayer) return;
            hadPlayer = false;
        }

        BackupLifecycle.Run run = LIFECYCLE.tryBegin();
        if (run == null) return;

        List<BackupSaveStateSnapshot.WorldState> saveStates = new ArrayList<>();

        try {
            // Must run before saveAllChunks so level.dat is written with the current host inventory, otherwise
            // the single-player host's inventory in the backup is stale and items can dupe/vanish on restore.
            server.getConfigurationManager().saveAllPlayerData();

            for (int i = 0; i < server.worldServers.length; ++i) {
                WorldServer world = server.worldServers[i];
                if (world != null) {
                    saveStates.add(new BackupSaveStateSnapshot.WorldState(i, world.levelSaving));
                    world.saveAllChunks(true, null);
                    world.levelSaving = true;
                }
            }
        } catch (Exception ex) {
            ServerUtils.notifyChat(
                    server,
                    null,
                    new ChatComponentText(
                            EnumChatFormatting.RED + "An error occurred while preparing backup. " + ex.getMessage()));
            ServerUtilities.LOGGER.error("An error occurred while preparing backup, aborting", ex);
            restoreSaveStates(server, new BackupSaveStateSnapshot(saveStates));
            abandonRun(run);
            return;
        }

        // saveAllPlayerData and saveAllChunks queue writes on another thread, so wait for them to finish
        try {
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        } catch (InterruptedException ex) {
            ServerUtilities.LOGGER.warn("Interrupted while flushing pending world writes before backup", ex);
            Thread.currentThread().interrupt();
            restoreSaveStates(server, new BackupSaveStateSnapshot(saveStates));
            abandonRun(run);
            return;
        }

        BackupSaveStateSnapshot saveStateSnapshot = new BackupSaveStateSnapshot(saveStates);
        ICompress compressor = null;
        boolean backupStarted = false;
        BackupExecution execution = null;
        try {
            if (!backups.silent_backup) {
                BACKUP.sendAll(StringUtils.color("cmd.backup_start", EnumChatFormatting.LIGHT_PURPLE));
            }

            Set<ChunkDimPos> backupChunks = new HashSet<>();
            boolean onlyClaimedChunks = BackupScope
                    .select(forceOnlyClaimed, backups.only_backup_claimed_chunks, ClaimedChunks.isActive())
                    .isClaimedChunksOnly();
            if (onlyClaimedChunks) {
                backupChunks.addAll(ClaimedChunks.instance.getAllClaimedPositions());
                // noinspection ResultOfMethodCallIgnored
                BACKUP_TEMP_FOLDER.mkdirs();
            }

            File worldDir = DimensionManager.getCurrentSaveRootDirectory();
            compressor = ICompress.createCompressor();
            if (backups.use_separate_thread) {
                ThreadBackup backupThread = new ThreadBackup(
                        compressor,
                        worldDir,
                        customName,
                        backupChunks,
                        onlyClaimedChunks);
                execution = new BackupExecution(run, saveStateSnapshot, backupThread);
                publishExecution(execution);
                universe.scheduleTask(new BackupTask(execution));
                backupThread.start();
                compressor = null; // The worker owns and closes it.
            } else {
                execution = new BackupExecution(run, saveStateSnapshot, null);
                publishExecution(execution);
                ThreadBackup.doBackup(compressor, worldDir, customName, backupChunks, onlyClaimedChunks);
                compressor = null; // doBackup closes it.
                universe.scheduleTask(new BackupTask(execution));
            }
            backupStarted = true;
        } catch (Exception ex) {
            ServerUtilities.LOGGER.error("An error occurred while starting the backup", ex);
            ServerUtils.notifyChat(
                    server,
                    null,
                    new ChatComponentText(
                            EnumChatFormatting.RED + "An error occurred while starting backup. " + ex.getMessage()));
        } finally {
            if (compressor != null) {
                try {
                    compressor.close();
                } catch (Exception closeError) {
                    ServerUtilities.LOGGER.warn("Failed to close an unused backup compressor", closeError);
                }
            }

            if (!backupStarted) {
                restoreSaveStates(server, saveStateSnapshot);
                abandonRun(run);
            }
        }
    }

    public static boolean isBackupInProgress() {
        return LIFECYCLE.isInProgress();
    }

    public static boolean cancelRunningBackup() {
        ThreadBackup currentWorker;
        synchronized (LIFECYCLE) {
            currentWorker = thread;
            if (currentWorker == null || currentWorker.isDone) {
                return false;
            }
        }

        currentWorker.interrupt();
        return true;
    }

    private static void publishExecution(BackupExecution execution) throws IOException {
        synchronized (LIFECYCLE) {
            if (!LIFECYCLE.isCurrent(execution.run)) {
                throw new IOException("Backup execution lost ownership before it started");
            }
            activeExecution = execution;
            thread = execution.worker;
        }
    }

    private static void abandonRun(BackupLifecycle.Run run) {
        synchronized (LIFECYCLE) {
            if (!LIFECYCLE.isCurrent(run)) {
                return;
            }
            thread = null;
            activeExecution = null;
            LIFECYCLE.complete(run);
        }
    }

    public static void clearOldBackups() {
        File[] files = BACKUP_FOLDER.listFiles();
        if (files == null || files.length == 0) return;

        List<File> backupFiles = Arrays.stream(files).filter(
                file -> backups.delete_custom_name_backups || BACKUP_NAME_PATTERN.matcher(file.getName()).matches())
                .sorted(Comparator.comparingLong(File::lastModified)).collect(Collectors.toList());

        long maxSize = backups.max_folder_size * SizeUnit.GB.getSize();
        if (maxSize > 0) {
            long currentSize = backupFiles.stream().mapToLong(FileUtils::getSize).sum();
            if (currentSize <= maxSize) return;
            deleteOldBackups(backupFiles, currentSize, maxSize);

        } else if (backupFiles.size() > backups.backups_to_keep) {
            deleteExcessBackups(backupFiles);
        }
    }

    private static void deleteOldBackups(List<File> backupFiles, long currentSize, long maxSize) {
        int deleted = 0;
        for (File file : backupFiles) {
            if (currentSize <= maxSize) break;
            currentSize -= FileUtils.getSize(file);
            ServerUtilities.LOGGER.info("Deleting old backup: {}", file.getPath());
            FileUtils.delete(file);
            deleted++;
        }
        ServerUtilities.LOGGER.info("Deleted {} old backups", deleted);
    }

    private static void deleteExcessBackups(List<File> backupFiles) {
        int toDelete = backupFiles.size() - ServerUtilitiesConfig.backups.backups_to_keep;
        ServerUtilities.LOGGER.info("Deleting {} old backups", toDelete);
        for (int i = 0; i < toDelete; i++) {
            File file = backupFiles.get(i);
            ServerUtilities.LOGGER.info("Deleted old backup: {}", file.getPath());
            FileUtils.delete(file);
        }
    }

    private boolean hasOnlinePlayers(MinecraftServer server) {
        return !server.getConfigurationManager().playerEntityList.isEmpty();
    }

    private void postBackup(Universe universe, BackupExecution execution) {
        if (!LIFECYCLE.isCurrent(execution.run)) {
            return;
        }

        if (execution.worker != null && !execution.worker.isDone) {
            setNextTime(System.currentTimeMillis() + Ticks.SECOND.millis());
            universe.scheduleTask(this);
            return;
        }

        try {
            clearOldBackups();
            FileUtils.delete(BACKUP_TEMP_FOLDER);
        } finally {
            restoreSaveStates(universe.server, execution.saveStates);
            abandonRun(execution.run);
        }
    }

    private static void restoreSaveStates(MinecraftServer server, BackupSaveStateSnapshot saveStates) {
        try {
            for (BackupSaveStateSnapshot.WorldState saveState : saveStates.worldStates()) {
                if (saveState.worldIndex >= 0 && saveState.worldIndex < server.worldServers.length) {
                    WorldServer world = server.worldServers[saveState.worldIndex];
                    if (world != null) {
                        world.levelSaving = saveState.levelSaving;
                    }
                }
            }
        } catch (Exception ex) {
            ServerUtilities.LOGGER.error("An error occurred while restoring world auto-save state", ex);
        }
    }
}

package serverutils.task.backup;

import static serverutils.ServerUtilitiesConfig.backups;
import static serverutils.ServerUtilitiesNotifications.BACKUP;
import static serverutils.task.backup.BackupTask.BACKUP_TEMP_FOLDER;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import serverutils.ServerUtilities;
import serverutils.ServerUtilitiesConfig;
import serverutils.lib.math.ChunkDimPos;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.BackupGlobUtils;
import serverutils.lib.util.FileUtils;
import serverutils.lib.util.ServerUtils;
import serverutils.lib.util.StringUtils;
import serverutils.lib.util.compression.ICompress;

public class ThreadBackup extends Thread {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.ROOT);
    private static long logMillis;
    private final File src0;
    private final String customName;
    private final Set<ChunkDimPos> chunksToBackup;
    private final boolean onlyClaimedChunks;
    public volatile boolean isDone = false;
    private final ICompress compressor;

    public ThreadBackup(ICompress compress, File sourceFile, String backupName, Set<ChunkDimPos> backupChunks) {
        this(compress, sourceFile, backupName, backupChunks, !backupChunks.isEmpty());
    }

    public ThreadBackup(ICompress compress, File sourceFile, String backupName, Set<ChunkDimPos> backupChunks,
            boolean backupOnlyClaimedChunks) {
        src0 = sourceFile;
        customName = backupName;
        chunksToBackup = backupChunks;
        onlyClaimedChunks = backupOnlyClaimedChunks;
        compressor = compress;
        setName("ServerUtilities Backup");
        setPriority(7);
    }

    public void run() {
        isDone = false;
        try {
            doBackup(compressor, src0, customName, chunksToBackup, onlyClaimedChunks);
        } finally {
            isDone = true;
        }
    }

    private static void addBaseFolderFiles(List<File> files, File saveFile) throws IOException {
        String saveName = saveFile.getName();

        for (String pattern : backups.additional_backup_files) {
            checkCancelled();
            String resolvedPattern = BackupGlobUtils.substituteLiteralPath(pattern, saveName);

            int firstWildcardIndex = pattern.indexOf('*');
            if (firstWildcardIndex == -1) {
                files.addAll(FileUtils.listTree(new File(resolvedPattern)));
                checkCancelled();
                continue;
            }

            Path rootFolder = BackupGlobUtils.searchRoot(pattern, saveName);

            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + BackupGlobUtils.substituteGlob(pattern, saveName));
            List<File> fileCandidates = FileUtils.listTree(rootFolder.toFile());
            for (File file : fileCandidates) {
                checkCancelled();
                if (matcher.matches(file.toPath())) {
                    files.add(file);
                }
            }
        }
    }

    public static void doBackup(ICompress compressor, File src, String customName, Set<ChunkDimPos> chunks) {
        doBackup(compressor, src, customName, chunks, !chunks.isEmpty());
    }

    public static void doBackup(ICompress compressor, File src, String customName, Set<ChunkDimPos> chunks,
            boolean backupOnlyClaimedChunks) {
        File dstFile = null;
        try (ICompress ignored = compressor) {
            checkCancelled();
            dstFile = resolveBackupFile(BackupTask.BACKUP_FOLDER, customName);
            List<File> files = FileUtils.listTree(src);
            checkCancelled();
            addBaseFolderFiles(files, src);
            long start = System.currentTimeMillis();
            logMillis = start + Ticks.SECOND.x(5).millis();

            Path destinationParent = dstFile.toPath().getParent();
            if (destinationParent != null) {
                Files.createDirectories(destinationParent);
            }
            checkCancelled();
            compressor.createOutputStream(dstFile);
            compressSelectedFiles(files, chunks, compressor, backupOnlyClaimedChunks);
            checkCancelled();

            String backupSize = FileUtils.getSizeString(dstFile);
            ServerUtilities.LOGGER.info("Backup done in {} seconds ({})!", getDoneTime(start), backupSize);
            ServerUtilities.LOGGER.info("Created {} from {}", dstFile.getAbsolutePath(), src.getAbsolutePath());

            if (!backups.silent_backup) {
                if (backups.display_file_size) {
                    String sizeT = FileUtils.getSizeString(BackupTask.BACKUP_FOLDER);
                    BACKUP.sendAll(
                            StringUtils.color(
                                    "cmd.backup_end_2",
                                    EnumChatFormatting.LIGHT_PURPLE,
                                    getDoneTime(start),
                                    (backupSize.equals(sizeT) ? backupSize : (backupSize + " | " + sizeT))));
                } else {
                    BACKUP.sendAll(
                            StringUtils.color("cmd.backup_end_1", EnumChatFormatting.LIGHT_PURPLE, getDoneTime(start)));
                }
            }
        } catch (InterruptedIOException e) {
            ServerUtilities.LOGGER.info("Backup cancelled");
            if (dstFile != null) FileUtils.delete(dstFile);
        } catch (Exception e) {
            ServerUtils.notifyChat(
                    ServerUtils.getServer(),
                    null,
                    StringUtils.color("cmd.backup_fail", EnumChatFormatting.RED, e.getMessage()));
            ServerUtilities.LOGGER.error("Error while backing up", e);
            if (dstFile != null) FileUtils.delete(dstFile);
        }
    }

    static File resolveBackupFile(File backupFolder, String customName) throws IOException {
        if (customName == null) {
            throw new IOException("Backup name cannot be null");
        }

        String baseName = customName.isEmpty() ? DATE_FORMAT.format(LocalDateTime.now()) : customName;
        if (!customName.isEmpty()) {
            validateCustomName(customName);
        }

        File canonicalRoot = backupFolder.getCanonicalFile();
        File destination = new File(canonicalRoot, baseName + ".zip").getCanonicalFile();
        if (!canonicalRoot.equals(destination.getParentFile())) {
            throw new IOException("Backup name must resolve directly inside the backup folder");
        }
        return destination;
    }

    private static void validateCustomName(String customName) throws IOException {
        if (customName.equals(".") || customName.equals("..") || customName.endsWith(" ") || customName.endsWith(".")) {
            throw new IOException("Invalid backup name: " + customName);
        }

        for (int i = 0; i < customName.length(); i++) {
            char character = customName.charAt(i);
            if (character < 32 || character == '/'
                    || character == '\\'
                    || character == ':'
                    || character == '*'
                    || character == '?'
                    || character == '"'
                    || character == '<'
                    || character == '>'
                    || character == '|') {
                throw new IOException("Invalid backup name: " + customName);
            }
        }
    }

    private static void logProgress(int i, int allFiles, String name) {
        long millis = System.currentTimeMillis();
        boolean first = i == 0;
        if (first) {
            ServerUtilities.LOGGER.info("Backing up {} files...", allFiles);
        }

        if (first || millis > logMillis || i == allFiles - 1) {
            logMillis = millis + Ticks.SECOND.x(5).millis();
            ServerUtilities.LOGGER
                    .info("[{} | {}%]: {}", i, StringUtils.formatDouble00((i / (double) allFiles) * 100D), name);
        }
    }

    static void compressSelectedFiles(List<File> files, Set<ChunkDimPos> chunks, ICompress compressor,
            boolean backupOnlyClaimedChunks) throws IOException {
        if (backupOnlyClaimedChunks) {
            backupRegions(files, chunks, compressor);
        } else {
            compressFiles(files, compressor);
        }
    }

    private static void compressFiles(List<File> files, ICompress compressor) throws IOException {
        int allFiles = files.size();
        for (int i = 0; i < allFiles; i++) {
            checkCancelled();
            File file = files.get(i);
            compressFile(FileUtils.getRelativePath(file), file, compressor, i, allFiles);
        }
    }

    private static void compressFile(String entryName, File file, ICompress compressor, int index, int totalFiles)
            throws IOException {
        checkCancelled();
        logProgress(index, totalFiles, file.getAbsolutePath());
        compressor.addFileToArchive(file, entryName);
        checkCancelled();
    }

    private static void backupRegions(List<File> files, Set<ChunkDimPos> chunksToBackup, ICompress compressor)
            throws IOException {
        checkCancelled();
        Object2ObjectMap<File, ObjectSet<ChunkDimPos>> dimRegionClaims = mapClaimsToRegionFile(chunksToBackup);
        files.removeIf(f -> f.getName().endsWith(".mca"));

        int index = 0;
        int savedChunks = 0;
        int regionFiles = dimRegionClaims.size();
        int totalFiles = files.size() + regionFiles;

        if (backups.backup_entire_regions_with_claims) {
            // Backup entire region files that contain claimed chunks
            for (Object2ObjectMap.Entry<File, ObjectSet<ChunkDimPos>> entry : dimRegionClaims.object2ObjectEntrySet()) {
                checkCancelled();
                File regionFile = entry.getKey();
                ObjectSet<ChunkDimPos> claimedChunks = entry.getValue();
                savedChunks += claimedChunks.size();

                // Backup the entire region file as-is
                compressFile(FileUtils.getRelativePath(regionFile), regionFile, compressor, index++, totalFiles);
            }
            ServerUtilities.LOGGER
                    .info("Backed up {} entire regions containing {} claimed chunks", regionFiles, savedChunks);
        } else {
            // Standard behavior: reconstruct temporary region files with only claimed chunks
            for (Object2ObjectMap.Entry<File, ObjectSet<ChunkDimPos>> entry : dimRegionClaims.object2ObjectEntrySet()) {
                checkCancelled();
                File file = entry.getKey();
                File dimensionRoot = file.getParentFile().getParentFile();
                File tempFile = FileUtils.newFile(new File(BACKUP_TEMP_FOLDER, file.getName()));
                boolean hasData = false;
                try {
                    RegionFile tempRegion = new RegionFile(tempFile);
                    try {
                        for (ChunkDimPos pos : entry.getValue()) {
                            checkCancelled();
                            try (DataInputStream in = RegionFileCache
                                    .getChunkInputStream(dimensionRoot, pos.posX, pos.posZ)) {
                                if (in == null) continue;
                                savedChunks++;
                                hasData = true;
                                NBTTagCompound tag = CompressedStreamTools.read(in);
                                try (DataOutputStream tempOut = tempRegion
                                        .getChunkDataOutputStream(pos.posX & 31, pos.posZ & 31)) {
                                    CompressedStreamTools.write(tag, tempOut);
                                }
                            }
                        }
                    } finally {
                        tempRegion.close();
                    }

                    if (hasData) {
                        compressFile(FileUtils.getRelativePath(file), tempFile, compressor, index++, totalFiles);
                    }
                } finally {
                    FileUtils.delete(tempFile);
                }
            }
            ServerUtilities.LOGGER.info("Backed up {} regions containing {} claimed chunks", regionFiles, savedChunks);
        }

        for (File file : files) {
            checkCancelled();
            compressFile(FileUtils.getRelativePath(file), file, compressor, index++, totalFiles);
        }
    }

    private static Object2ObjectMap<File, ObjectSet<ChunkDimPos>> mapClaimsToRegionFile(Set<ChunkDimPos> chunksToBackup)
            throws IOException {
        if (chunksToBackup.isEmpty()) {
            return new Object2ObjectOpenHashMap<>();
        }

        checkCancelled();
        Int2ObjectMap<Long2ObjectMap<ObjectSet<ChunkDimPos>>> regionClaimsByDim = new Int2ObjectOpenHashMap<>();
        chunksToBackup.forEach(
                pos -> regionClaimsByDim.computeIfAbsent(pos.dim, k -> new Long2ObjectOpenHashMap<>())
                        .computeIfAbsent(getRegionFromChunk(pos.posX, pos.posZ), k -> new ObjectOpenHashSet<>())
                        .add(pos));

        Object2ObjectMap<File, ObjectSet<ChunkDimPos>> regionFilesToBackup = new Object2ObjectOpenHashMap<>();
        for (WorldServer worldserver : ServerUtils.getServer().worldServers) {
            checkCancelled();
            if (worldserver == null) continue;

            int dim = worldserver.provider.dimensionId;
            File regionFolder = new File(worldserver.getChunkSaveLocation(), "region");
            Long2ObjectMap<ObjectSet<ChunkDimPos>> regionClaims = regionClaimsByDim.get(dim);
            if (!regionFolder.exists() || regionClaims == null) continue;

            File[] regions = regionFolder.listFiles();
            if (regions == null) continue;

            for (File file : regions) {
                checkCancelled();
                int[] coords = getRegionCoords(file);
                if (coords == null) continue;
                long key = CoordinatePacker.pack(coords[0], 0, coords[1]);
                ObjectSet<ChunkDimPos> claims = regionClaims.get(key);
                if (claims == null) {
                    if (ServerUtilitiesConfig.debugging.print_more_info) {
                        ServerUtilities.LOGGER.info("Skipping region file {} from dimension {}", file.getName(), dim);
                    }
                    continue;
                }
                regionFilesToBackup.put(file, claims);
            }
        }
        return regionFilesToBackup;
    }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Backup cancelled");
        }
    }

    private static int[] getRegionCoords(File file) {
        if (!file.getName().endsWith(".mca")) return null;

        String[] parts = file.getName().split("\\.");
        try {
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new int[] { x, z };
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static String getDoneTime(long l) {
        return StringUtils.getTimeString(System.currentTimeMillis() - l);
    }

    private static long getRegionFromChunk(int chunkX, int chunkZ) {
        return CoordinatePacker.pack(chunkX >> 5, 0, chunkZ >> 5);
    }
}

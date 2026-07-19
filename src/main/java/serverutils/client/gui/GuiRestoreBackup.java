package serverutils.client.gui;

import static serverutils.ServerUtilitiesConfig.backups;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraftforge.client.event.GuiScreenEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import serverutils.ServerUtilities;
import serverutils.ServerUtilitiesConfig;
import serverutils.lib.gui.Button;
import serverutils.lib.gui.ButtonContainer;
import serverutils.lib.gui.GuiIcons;
import serverutils.lib.gui.Panel;
import serverutils.lib.gui.SimpleTextButton;
import serverutils.lib.gui.Theme;
import serverutils.lib.gui.Widget;
import serverutils.lib.gui.WidgetLayout;
import serverutils.lib.gui.misc.GuiButtonListBase;
import serverutils.lib.icon.Icon;
import serverutils.lib.util.BackupGlobUtils;
import serverutils.lib.util.FileUtils;
import serverutils.lib.util.compression.ICompress;
import serverutils.lib.util.misc.MouseButton;
import serverutils.task.backup.BackupTask;

@EventBusSubscriber(side = Side.CLIENT)
public class GuiRestoreBackup extends GuiButtonListBase {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd-HH-mm-ss", Locale.ROOT);
    private static final Set<File> allBackupFiles = new ObjectOpenHashSet<>();
    private static Object2ObjectMap<String, List<File>> worldBackups;
    private final List<File> backupFiles;
    private final String title;
    private final Button backButton, recreateWorldButton;
    private final String worldName;

    public GuiRestoreBackup(String worldName, GuiSelectWorld selectWorld) {
        this.worldName = worldName;
        this.backupFiles = worldBackups.get(worldName);
        this.title = StatCollector.translateToLocalFormatted("serverutilities.gui.backup.title", worldName);
        backupFiles.sort(Comparator.comparing(File::lastModified).reversed());
        backButton = new SimpleTextButton(this, StatCollector.translateToLocal("gui.cancel"), GuiIcons.CANCEL) {

            @Override
            public void onClicked(MouseButton button) {
                closeGui();
            }
        };

        recreateWorldButton = new SimpleTextButton(
                this,
                StatCollector.translateToLocal("selectWorld.recreate"),
                GuiIcons.REFRESH) {

            @Override
            public void onClicked(MouseButton button) {
                try {
                    // Button doesn't matter, it just needs to have an id of 7
                    ReflectionHelper.findMethod(
                            GuiSelectWorld.class,
                            null,
                            new String[] { "func_146284_a", "actionPerformed" },
                            GuiButton.class).invoke(selectWorld, new GuiButton(7, 0, 0, 0, 0, ""));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @EventBusSubscriber.Condition
    public static boolean shouldEventBusSubscribe() {
        return ServerUtilitiesConfig.backups.enable_backups;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    @SuppressWarnings("unchecked")
    public static void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (worldBackups == null) {
            worldBackups = new Object2ObjectOpenHashMap<>();
            preProcess();
        }

        if (event.gui instanceof GuiSelectWorld gui) {
            if (needsRefresh()) {
                worldBackups.clear();
                allBackupFiles.clear();
                preProcess();
            }

            // Don't add the button if it's too big to fit on the screen
            if (event.gui.width / 2 + 248 > event.gui.width) return;

            event.buttonList.add(
                    new GuiRestoreButton(
                            event.gui.width - 90,
                            event.gui.height - 52,
                            82,
                            20,
                            StatCollector.translateToLocal("serverutilities.gui.backup.button"),
                            gui));

            // Removes Aroma's "Backup" button
            gui.buttonList.removeIf(button -> button.id == 50);
        }
    }

    private static boolean needsRefresh() {
        File[] files = BackupTask.BACKUP_FOLDER.listFiles();
        if (files == null) return false;
        return !allBackupFiles.containsAll(Arrays.asList(files));
    }

    private static void preProcess() {
        File[] files = BackupTask.BACKUP_FOLDER.listFiles();
        if (files == null) return;

        try (ICompress compressor = ICompress.createCompressor()) {
            for (File file : files) {
                allBackupFiles.add(file);
                try {
                    String worldName = compressor.getWorldName(file);
                    if (worldName == null) continue;
                    worldBackups.computeIfAbsent(worldName, k -> new ObjectArrayList<>()).add(file);
                } catch (IOException ex) {
                    serverutils.ServerUtilities.LOGGER.warn("Failed to inspect backup " + file.getAbsolutePath(), ex);
                }
            }
        } catch (Exception ex) {
            serverutils.ServerUtilities.LOGGER.warn("Failed to close the backup reader", ex);
        }
    }

    @Override
    public void onPostInit() {
        setFullscreen();
        alignWidgets();
    }

    @Override
    public void alignWidgets() {
        backButton.setPos(9, 2);
        backButton.setHeight(15);
        recreateWorldButton.setPos(9 + backButton.width, 2);
        recreateWorldButton.setHeight(15);
        panelButtons.setPosAndSize(9, 20, width - 20 - scrollBar.width, height - 20);
        super.alignWidgets();
    }

    @Override
    public boolean onClosedByKey(int key) {
        if (super.onClosedByKey(key)) {
            closeGui();
            return true;
        }

        return false;
    }

    @Override
    public void addWidgets() {
        super.addWidgets();
        add(backButton);
        add(recreateWorldButton);
    }

    @Override
    public void addButtons(Panel panel) {
        for (File file : backupFiles) {
            ButtonContainer container = new ButtonContainer(panel, file.getName(), Icon.EMPTY);
            container.addSubButton(
                    new BackupEntryButton(
                            panel,
                            StatCollector.translateToLocal("serverutilities.gui.backup.restore"),
                            GuiIcons.ACCEPT,
                            file,
                            this::loadBackupWorld));
            container.addSubButton(
                    new BackupEntryButton(
                            panel,
                            StatCollector.translateToLocal("serverutilities.gui.backup.restore_global"),
                            GuiIcons.ACCEPT,
                            file,
                            this::loadBackupGlobal));
            container.addSubButton(
                    new BackupEntryButton(
                            panel,
                            StatCollector.translateToLocal("selectWorld.delete"),
                            GuiIcons.REMOVE,
                            file,
                            this::deleteBackup));
            container.setXOffset(9);
            panel.add(container);
        }
    }

    private void moveAdditionalFiles(RestoreTransaction transaction, boolean includeGlobal) throws IOException {
        for (String pattern : backups.additional_backup_files) {
            if (!pattern.contains("$WORLDNAME") && !includeGlobal) {
                continue;
            }
            String resolvedPattern = BackupGlobUtils.substituteLiteralPath(pattern, worldName);

            // Gather list of all old files
            List<File> previousFiles;
            int firstWildcardIndex = pattern.indexOf('*');
            if (firstWildcardIndex == -1) {
                previousFiles = FileUtils.listTree(new File(resolvedPattern));
            } else {
                Path rootFolder = BackupGlobUtils.searchRoot(pattern, worldName);
                PathMatcher matcher = FileSystems.getDefault()
                        .getPathMatcher("glob:" + BackupGlobUtils.substituteGlob(pattern, worldName));
                List<File> fileCandidates = FileUtils.listTree(rootFolder.toFile());
                previousFiles = new ArrayList<>();
                for (File file : fileCandidates) {
                    if (matcher.matches(file.toPath())) {
                        previousFiles.add(file);
                    }
                }
            }

            // Move all old files into the rollback journal.
            for (File file : previousFiles) {
                if (!transaction.isProtectedPath(file.toPath())) {
                    transaction.moveAside(file.toPath());
                }
            }
        }
    }

    private void loadBackupWorld(File file) {
        openYesNo(
                StatCollector.translateToLocal("serverutilities.gui.backup.restore_confirm"),
                StatCollector.translateToLocal("serverutilities.gui.backup.restore_confirm_desc"),
                () -> { loadBackup(file, false); });
    }

    private void loadBackupGlobal(File file) {
        openYesNo(
                StatCollector.translateToLocal("serverutilities.gui.backup.restore_global_confirm"),
                StatCollector.translateToLocal("serverutilities.gui.backup.restore_global_confirm_desc"),
                () -> { loadBackup(file, true); });
    }

    private void loadBackup(File file, boolean includeGlobal) {
        Path serverRoot = Paths.get("").toAbsolutePath().normalize();
        Path stagingRoot = null;
        RestoreTransaction transaction = null;

        try {
            RestoreTransaction.recoverPending(serverRoot);
            Path worldRelative = Paths.get(worldName);
            if (worldName.isEmpty() || worldName.indexOf('/') >= 0
                    || worldName.indexOf('\\') >= 0
                    || worldName.indexOf(':') >= 0
                    || worldRelative.isAbsolute()
                    || worldRelative.getNameCount() != 1
                    || worldName.equals(".")
                    || worldName.equals("..")) {
                throw new IOException("Backup has an invalid world name: " + worldName);
            }

            Path savesDir = serverRoot.resolve("saves");
            Path worldDir = savesDir.resolve(worldRelative).normalize();
            Path saveCopy = savesDir.resolve(worldName + "_old");
            while (Files.exists(saveCopy)) {
                saveCopy = savesDir.resolve(saveCopy.getFileName() + "_old");
            }

            Path stagingBase = serverRoot.resolve("serverutilities/restore-staging");
            Files.createDirectories(stagingBase);
            stagingRoot = Files.createTempDirectory(stagingBase, "restore-");

            Path previousRoot = serverRoot.resolve("backups_before_restore")
                    .resolve(DATE_FORMAT.format(LocalDateTime.now()));
            while (Files.exists(previousRoot)) {
                previousRoot = previousRoot.resolveSibling(previousRoot.getFileName() + "_old");
            }

            transaction = new RestoreTransaction(serverRoot, previousRoot);
            transaction.protect(stagingBase);
            transaction.protect(serverRoot.resolve("backups_before_restore"));
            Path archivePath = file.toPath().toAbsolutePath().normalize();
            if (archivePath.startsWith(serverRoot)) {
                transaction.protect(archivePath);
            }

            boolean isOldBackup;
            try (ICompress compressor = ICompress.createCompressor()) {
                isOldBackup = compressor.isOldBackup(file);
                compressor.extractArchiveTo(stagingRoot.toFile(), file, includeGlobal, isOldBackup, worldName);
            }

            transaction.moveAside(worldDir, saveCopy);
            if (!isOldBackup) {
                moveAdditionalFiles(transaction, includeGlobal);
            }

            transaction.install(stagingRoot);
            transaction.commit();
            closeGui();
        } catch (Exception e) {
            ServerUtilities.LOGGER.error("Failed to restore backup", e);
            if (transaction != null) {
                try {
                    transaction.rollback();
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                    ServerUtilities.LOGGER.error("Failed to roll back the backup restore", rollbackError);
                }
            }
            Minecraft.getMinecraft().displayGuiScreen(
                    new GuiErrorScreen(
                            StatCollector.translateToLocal("serverutilities.gui.backup.error"),
                            EnumChatFormatting.RED + e.getMessage()));
        } finally {
            if (stagingRoot != null) {
                FileUtils.delete(stagingRoot.toFile());
            }
        }
    }

    private void deleteBackup(File file) {
        openYesNo(StatCollector.translateToLocal("serverutilities.gui.backup.delete_confirm"), "", () -> {
            FileUtils.delete(file);
            backupFiles.remove(file);
        });
    }

    @Override
    public void drawBackground(Theme theme, int x, int y, int w, int h) {
        super.drawBackground(theme, x, y, w, h);
        theme.drawString(
                EnumChatFormatting.BOLD + title,
                x + (width - theme.getStringWidth(title)) / 2,
                2 + theme.getFontHeight(),
                Theme.SHADOW);
    }

    @Override
    protected Panel createButtonPanel() {
        return new Panel(this) {

            @Override
            public void addWidgets() {
                addButtons(this);
            }

            @Override
            public void alignWidgets() {
                setY(21);

                for (Widget w : widgets) {
                    w.setX(0);
                    w.setWidth(width);
                }

                scrollBar.setPosAndSize(posX + width + 6, posY - 1, 16, height + 2);
                scrollBar.setMaxValue(align(new WidgetLayout.Vertical(0, 0, 0)));

                getGui().setWidth(scrollBar.posX + scrollBar.width + 8);
                getGui().setHeight(height + 18);
            }

            @Override
            public void drawBackground(Theme theme, int x, int y, int w, int h) {
                theme.drawPanelBackground(x, y, w, h);
            }
        };
    }

    private static class BackupEntryButton extends SimpleTextButton {

        private final File file;
        private final Consumer<File> callback;

        public BackupEntryButton(Panel panel, String text, Icon icon, File file, Consumer<File> callback) {
            super(panel, text, icon);
            this.file = file;
            this.callback = callback;
        }

        @Override
        public void onClicked(MouseButton button) {
            callback.accept(file);
        }
    }

    private static class GuiRestoreButton extends GuiButton {

        private final GuiSelectWorld gui;
        private String currentWorld;

        public GuiRestoreButton(int x, int y, int widthIn, int heightIn, String buttonText, GuiSelectWorld gui) {
            super(111, x, y, widthIn, heightIn, buttonText);
            this.gui = gui;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            int worldIndex = gui.field_146640_r;

            if (worldIndex == -1) {
                enabled = false;
            } else {
                currentWorld = ((SaveFormatComparator) gui.field_146639_s.get(worldIndex)).getFileName();
                enabled = worldBackups.containsKey(currentWorld);
            }

            super.drawButton(mc, mouseX, mouseY);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!super.mousePressed(mc, mouseX, mouseY)) {
                return false;
            }

            new GuiRestoreBackup(currentWorld, gui).openGui();

            return true;
        }
    }

}

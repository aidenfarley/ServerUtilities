package serverutils.client.gui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.minecraft.client.AnvilConverterException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraft.world.storage.SaveFormatOld;
import net.minecraftforge.client.event.GuiScreenEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import serverutils.ServerUtilitiesConfig;

@EventBusSubscriber(side = Side.CLIENT)
public class GuiToggleCheatsButton extends GuiButton {

    private final GuiSelectWorld gui;
    private String currentWorld;

    public GuiToggleCheatsButton(int x, int y, int widthIn, int heightIn, String buttonText, GuiSelectWorld gui) {
        super(112, x, y, widthIn, heightIn, buttonText);
        this.gui = gui;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        int worldIndex = gui.field_146640_r;

        if (worldIndex == -1) {
            enabled = false;
            this.displayString = StatCollector.translateToLocal("serverutilities.general.toggle_cheats");
        } else {
            SaveFormatComparator saveformatcomparator = (SaveFormatComparator) gui.field_146639_s.get(worldIndex);
            currentWorld = saveformatcomparator.getFileName();
            enabled = true;
            if (saveformatcomparator.getCheatsEnabled()) {
                this.displayString = StatCollector.translateToLocal("serverutilities.general.disable_cheats");
            } else {
                this.displayString = StatCollector.translateToLocal("serverutilities.general.enable_cheats");
            }
        }

        super.drawButton(mc, mouseX, mouseY);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) {
            return false;
        }

        try {
            writeCheatsSetting(currentWorld);
            SaveFormatComparator saveformatcomparator = (SaveFormatComparator) gui.field_146639_s
                    .get(gui.field_146640_r);
            ((ISaveFormatComparatorWithCheatSetter) saveformatcomparator)
                    .serverutilities$setCheatsEnabled(!saveformatcomparator.getCheatsEnabled());
        } catch (AnvilConverterException | IOException e) {
            serverutils.ServerUtilities.LOGGER.error("Failed to toggle cheats for the selected world", e);
        }

        return true;
    }

    /**
     * Toggles cheats by flipping the allowCommands byte between 0 and 1
     */
    @SideOnly(Side.CLIENT)
    public void toggleCheats(String worldName) throws AnvilConverterException {
        try {
            writeCheatsSetting(worldName);
        } catch (IOException ex) {
            serverutils.ServerUtilities.LOGGER.error("Failed to toggle cheats for world " + worldName, ex);
        }
    }

    private void writeCheatsSetting(String worldName) throws AnvilConverterException, IOException {
        File saveFolder = new File(
                ((SaveFormatOld) Minecraft.getMinecraft().getSaveLoader()).savesDirectory,
                worldName);

        if (!saveFolder.exists()) return;

        File levelDataFile = new File(saveFolder, "level.dat");

        if (!levelDataFile.exists()) return;

        NBTTagCompound parentTag;
        try (FileInputStream input = new FileInputStream(levelDataFile)) {
            parentTag = CompressedStreamTools.readCompressed(input);
        }

        NBTTagCompound dataTag = parentTag.getCompoundTag("Data");
        byte allowCommands = dataTag.getByte("allowCommands");
        dataTag.setByte("allowCommands", allowCommands == 0 ? (byte) 1 : (byte) 0);
        replaceLevelData(levelDataFile, parentTag);
    }

    static void replaceLevelData(File levelDataFile, NBTTagCompound parentTag) throws IOException {
        Path target = levelDataFile.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("World metadata has no parent directory: " + target);
        }

        Path replacement = Files.createTempFile(parent, "level", ".dat_new");
        Path oldReplacement = null;
        try {
            try (FileOutputStream output = new FileOutputStream(replacement.toFile())) {
                OutputStream nonClosing = new FilterOutputStream(output) {

                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                };
                CompressedStreamTools.writeCompressed(parentTag, nonClosing);
                output.getChannel().force(true);
            }

            Path old = parent.resolve("level.dat_old");
            oldReplacement = Files.createTempFile(parent, "level", ".dat_old");
            Files.copy(target, oldReplacement, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel oldOutput = FileChannel.open(oldReplacement, java.nio.file.StandardOpenOption.WRITE)) {
                oldOutput.force(true);
            }
            moveReplacing(oldReplacement, old);
            oldReplacement = null;

            moveReplacing(replacement, target);
            replacement = null;
        } finally {
            if (replacement != null) {
                Files.deleteIfExists(replacement);
            }
            if (oldReplacement != null) {
                Files.deleteIfExists(oldReplacement);
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @EventBusSubscriber.Condition
    public static boolean shouldEventBusSubscribe() {
        return ServerUtilitiesConfig.general.enable_toggle_cheats_button;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    @SuppressWarnings("unchecked")
    public static void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiSelectWorld gui) {
            // Don't add the button if it's too big to fit on the screen
            if (gui.width / 2 + 248 > gui.width) return;

            event.buttonList.add(
                    new GuiToggleCheatsButton(
                            gui.width - 90,
                            gui.height - 28,
                            82,
                            20,
                            StatCollector.translateToLocal("serverutilities.general.toggle_cheats"),
                            gui));
        }
    }
}

package serverutils.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiToggleCheatsButtonTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacementKeepsPreviousMetadataAsOldCopy() throws Exception {
        Path level = temporaryDirectory.resolve("level.dat");
        write(level, metadata((byte) 0));

        GuiToggleCheatsButton.replaceLevelData(level.toFile(), metadata((byte) 1));

        assertEquals(1, readAllowCommands(level));
        assertEquals(0, readAllowCommands(temporaryDirectory.resolve("level.dat_old")));
    }

    @Test
    void failedOldCopyRotationLeavesLiveMetadataUntouched() throws Exception {
        Path level = temporaryDirectory.resolve("level.dat");
        write(level, metadata((byte) 0));
        Files.createDirectory(temporaryDirectory.resolve("level.dat_old"));

        assertThrows(
                IOException.class,
                () -> GuiToggleCheatsButton.replaceLevelData(level.toFile(), metadata((byte) 1)));
        assertEquals(0, readAllowCommands(level));
    }

    private static NBTTagCompound metadata(byte allowCommands) {
        NBTTagCompound parent = new NBTTagCompound();
        NBTTagCompound data = new NBTTagCompound();
        data.setByte("allowCommands", allowCommands);
        parent.setTag("Data", data);
        return parent;
    }

    private static void write(Path path, NBTTagCompound nbt) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            CompressedStreamTools.writeCompressed(nbt, output);
        }
    }

    private static int readAllowCommands(Path path) throws IOException {
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            return CompressedStreamTools.readCompressed(input).getCompoundTag("Data").getByte("allowCommands");
        }
    }
}

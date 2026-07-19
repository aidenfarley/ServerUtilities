package serverutils.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NBTUtilsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void checkedWriteReportsSuccessOnlyAfterReadableReplacement() {
        Path target = temporaryDirectory.resolve("data.dat");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("value", "saved");

        assertTrue(NBTUtils.writeNBTChecked(target.toFile(), tag));
        assertEquals("saved", NBTUtils.readNBT(target.toFile()).getString("value"));
    }

    @Test
    void checkedWriteReportsReplacementFailureAndCleansTemporaryFile() throws Exception {
        Path targetDirectory = Files.createDirectory(temporaryDirectory.resolve("data.dat"));
        NBTTagCompound tag = new NBTTagCompound();

        assertFalse(NBTUtils.writeNBTChecked(targetDirectory.toFile(), tag));
        assertTrue(Files.isDirectory(targetDirectory));
        try (java.util.stream.Stream<Path> children = Files.list(temporaryDirectory)) {
            assertEquals(1L, children.count());
        }
    }
}

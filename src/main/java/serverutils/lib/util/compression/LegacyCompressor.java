package serverutils.lib.util.compression;

import static serverutils.ServerUtilitiesConfig.backups;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.common.DimensionManager;

public class LegacyCompressor extends AbstractZipCompressor<ZipFile, ZipEntry> {

    private ZipOutputStream output;

    @Override
    public void createOutputStream(File file) throws IOException {
        output = new ZipOutputStream(new FileOutputStream(file));
        if (backups.compression_level == 0) {
            output.setMethod(ZipOutputStream.STORED);
        } else {
            output.setLevel(backups.compression_level);
        }

        File worldDir = DimensionManager.getCurrentSaveRootDirectory();
        if (worldDir != null) {
            output.setComment(worldDir.getName());
        }
    }

    @Override
    public void addFileToArchive(File file, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        output.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
            copyArchiveInput(fis, output);
        }
        output.closeEntry();
    }

    @Override
    protected ZipFile openArchive(File archive) throws IOException {
        return new ZipFile(archive);
    }

    @Override
    protected Enumeration<? extends ZipEntry> getEntries(ZipFile archive) {
        return archive.entries();
    }

    @Override
    protected String getEntryName(ZipEntry entry) {
        return entry.getName();
    }

    @Override
    protected boolean isDirectory(ZipEntry entry) {
        return entry.isDirectory();
    }

    @Override
    protected InputStream openEntry(ZipFile archive, ZipEntry entry) throws IOException {
        return archive.getInputStream(entry);
    }

    @Override
    public void close() throws Exception {
        if (output != null) {
            output.close();
        }
    }
}

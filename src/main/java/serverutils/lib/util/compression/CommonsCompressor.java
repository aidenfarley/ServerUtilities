package serverutils.lib.util.compression;

import static serverutils.ServerUtilitiesConfig.backups;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;

import net.minecraftforge.common.DimensionManager;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

public class CommonsCompressor extends AbstractZipCompressor<ZipFile, ZipArchiveEntry> {

    private ArchiveOutputStream output;

    @Override
    public void createOutputStream(File file) throws IOException {
        ZipArchiveOutputStream zaos = new ZipArchiveOutputStream(file);
        if (backups.compression_level == 0) {
            zaos.setMethod(ZipEntry.STORED);
        } else {
            zaos.setLevel(backups.compression_level);
        }

        File worldDir = DimensionManager.getCurrentSaveRootDirectory();
        if (worldDir != null) {
            zaos.setComment(worldDir.getName());
        }

        output = zaos;
    }

    @Override
    public void addFileToArchive(File file, String name) throws IOException {
        ArchiveEntry entry = output.createArchiveEntry(file, name);
        output.putArchiveEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
            copyArchiveInput(fis, output);
        }
        output.closeArchiveEntry();
    }

    @Override
    protected ZipFile openArchive(File archive) throws IOException {
        return new ZipFile(archive);
    }

    @Override
    protected Enumeration<? extends ZipArchiveEntry> getEntries(ZipFile archive) {
        return archive.getEntries();
    }

    @Override
    protected String getEntryName(ZipArchiveEntry entry) {
        return entry.getName();
    }

    @Override
    protected boolean isDirectory(ZipArchiveEntry entry) {
        return entry.isDirectory();
    }

    @Override
    protected InputStream openEntry(ZipFile archive, ZipArchiveEntry entry) throws IOException {
        return archive.getInputStream(entry);
    }

    @Override
    public void close() throws Exception {
        if (output != null) {
            output.close();
        }
    }
}

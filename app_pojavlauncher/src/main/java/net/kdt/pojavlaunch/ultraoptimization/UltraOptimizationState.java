package net.kdt.pojavlaunch.ultraoptimization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-installation Ultra-Optimization state, stored inside the selected installation's game
 * directory. It is intentionally NOT a launcher SharedPreference: two installations never share
 * a state file, and the existing global Ultra-Lite preference keeps its own storage and keys.
 */
public final class UltraOptimizationState {
    public static final String DIRECTORY_NAME = ".ultra_optimization";
    public static final String STATE_FILE_NAME = "state.json";
    public static final String BACKUP_DIRECTORY_NAME = "backup";
    public static final String TEMP_DIRECTORY_NAME = "tmp";
    public static final int SCHEMA_VERSION = 1;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int schemaVersion = SCHEMA_VERSION;
    public long createdAt;
    public long updatedAt;
    public String minecraftVersion;
    public String loader;
    public String renderer;
    public List<Entry> entries = new ArrayList<>();

    /** One file we placed into the installation, plus the backup of anything we displaced. */
    public static final class Entry {
        public String projectId;
        public String slug;
        public String title;
        public String versionId;
        public String versionNumber;
        public String fileName;
        public String hashAlgorithm;
        public String hash;
        public long size;
        public String modsDirectory;
        public long installedAt;
        /** File name inside the backup directory, when an existing file had to be displaced. */
        public String backupFileName;
        public String replacedHashAlgorithm;
        public String replacedHash;
        public long replacedSize;
    }

    public static File directory(File gameDirectory) {
        return new File(gameDirectory, DIRECTORY_NAME);
    }

    public static File stateFile(File gameDirectory) {
        return new File(directory(gameDirectory), STATE_FILE_NAME);
    }

    public static File backupDirectory(File gameDirectory) {
        return new File(directory(gameDirectory), BACKUP_DIRECTORY_NAME);
    }

    public static File tempDirectory(File gameDirectory) {
        return new File(directory(gameDirectory), TEMP_DIRECTORY_NAME);
    }

    public static boolean hasState(File gameDirectory) {
        return stateFile(gameDirectory).isFile();
    }

    /** @return the persisted state, or null when this installation was never optimized. */
    public static UltraOptimizationState load(File gameDirectory) {
        File file = stateFile(gameDirectory);
        if (!file.isFile()) return null;
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), UTF_8);
            UltraOptimizationState state = GSON.fromJson(reader, UltraOptimizationState.class);
            if (state == null) return null;
            if (state.entries == null) state.entries = new ArrayList<>();
            return state;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    public void save(File gameDirectory) throws IOException {
        File directory = directory(gameDirectory);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory.getAbsolutePath());
        }
        updatedAt = System.currentTimeMillis();
        if (createdAt <= 0) createdAt = updatedAt;
        schemaVersion = SCHEMA_VERSION;
        File target = stateFile(gameDirectory);
        File temporary = new File(directory, STATE_FILE_NAME + ".writing");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(temporary), UTF_8);
            GSON.toJson(this, writer);
            writer.flush();
        } finally {
            closeQuietly(writer);
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
        if (!temporary.renameTo(target)) {
            throw new IOException("Cannot write " + target.getAbsolutePath());
        }
    }

    public Entry findEntryByFileName(String fileName) {
        if (fileName == null) return null;
        for (Entry entry : entries) {
            if (fileName.equals(entry.fileName)) return entry;
        }
        return null;
    }

    public boolean containsProject(String projectIdOrSlug) {
        if (projectIdOrSlug == null) return false;
        for (Entry entry : entries) {
            if (projectIdOrSlug.equalsIgnoreCase(entry.projectId)
                    || projectIdOrSlug.equalsIgnoreCase(entry.slug)) return true;
        }
        return false;
    }

    public int installedCount() {
        return entries.size();
    }

    /** Removes the state file and our staging/backup directories. Never touches the mods folder. */
    public void delete(File gameDirectory) {
        deleteRecursively(stateFile(gameDirectory));
        deleteRecursively(new File(directory(gameDirectory), STATE_FILE_NAME + ".writing"));
        deleteRecursively(backupDirectory(gameDirectory));
        deleteRecursively(tempDirectory(gameDirectory));
        File directory = directory(gameDirectory);
        // Only remove the directory when it is empty, so nothing unexpected is deleted.
        String[] remaining = directory.list();
        if (remaining != null && remaining.length == 0) {
            // noinspection ResultOfMethodCallIgnored
            directory.delete();
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        // noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}

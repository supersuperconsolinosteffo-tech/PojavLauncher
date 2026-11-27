package net.kdt.pojavlaunch.ultraoptimization;

import android.util.Log;

import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.PlanItem;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Resolution;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies an {@link Resolution} to one installation.
 *
 * Every file is first downloaded into {@code .ultra_optimization/tmp}, verified against the
 * Modrinth hash and size, and only then moved into the mods directory. Anything that already
 * exists is left alone unless the user explicitly confirmed an overwrite, in which case the old
 * file is copied into {@code .ultra_optimization/backup} first, so restore is always possible.
 */
public final class UltraOptimizationInstaller {
    private static final String TAG = "UltraOptimization";
    private static final String USER_AGENT = "PojavLauncher-Renewed/Ultra-Optimization (self-hosted fork)";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 40000;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private UltraOptimizationInstaller() {}

    public interface Callback {
        void onProgress(String message, int completed, int total);

        void onLog(String message);
    }

    public static final class Result {
        public int installed;
        public int removed;
        public int skipped;
        public int failed;
        public boolean cancelled;
        public boolean restored;
        public final List<String> messages = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public boolean anythingHappened() {
            return installed > 0 || removed > 0 || restored || skipped > 0;
        }
    }

    /**
     * Download and install every missing, verified item of the resolution.
     *
     * @param allowOverwrite true only after the user explicitly confirmed replacing same-named files
     */
    public static Result install(File gameDirectory, Resolution resolution, boolean allowOverwrite,
                                Callback callback, AtomicBoolean cancelFlag) {
        Result result = new Result();
        if (gameDirectory == null || resolution == null) {
            result.errors.add("No installation or plan is selected.");
            return result;
        }
        List<PlanItem> plan = resolution.installableItems();
        if (plan.isEmpty()) {
            result.skipped++;
            result.messages.add("Nothing to install: no missing compatible mod was found.");
            return result;
        }

        UltraOptimizationState state = UltraOptimizationState.load(gameDirectory);
        if (state == null) state = new UltraOptimizationState();
        state.minecraftVersion = resolution.environment == null ? null : resolution.environment.minecraftVersion;
        state.loader = resolution.environment == null || resolution.environment.loader == null
                ? null : resolution.environment.loader.displayName();
        state.renderer = resolution.environment == null ? null : resolution.environment.renderer;

        File tempDirectory = UltraOptimizationState.tempDirectory(gameDirectory);
        if (!tempDirectory.isDirectory() && !tempDirectory.mkdirs()) {
            result.errors.add("Cannot create the staging directory " + tempDirectory.getName());
            return result;
        }

        int total = plan.size();
        for (int index = 0; index < total; index++) {
            PlanItem item = plan.get(index);
            if (isCancelled(cancelFlag)) {
                result.cancelled = true;
                report(callback, "Cancelled", index, total);
                break;
            }
            report(callback, "Preparing " + item.title, index, total);
            File modsDirectory = new File(gameDirectory, "mods");
            File target = new File(modsDirectory, item.fileName);
            if (state.findEntryByFileName(item.fileName) != null && target.isFile()) {
                result.skipped++;
                log(callback, item.title + ": already installed by Ultra-Optimization, skipped.");
                continue;
            }
            if (target.isFile()) {
                if (!allowOverwrite) {
                    result.skipped++;
                    log(callback, item.title + ": " + item.fileName
                            + " already exists in this installation; nothing was overwritten.");
                    continue;
                }
                try {
                    backupExistingFile(gameDirectory, item, target, state);
                } catch (IOException e) {
                    result.failed++;
                    result.errors.add(item.title + ": cannot back up the existing file (" + e.getMessage() + ")");
                    continue;
                }
            }

            File staged = null;
            try {
                if (!modsDirectory.isDirectory() && !modsDirectory.mkdirs()) {
                    throw new IOException("Cannot create " + modsDirectory.getAbsolutePath());
                }
                staged = new File(tempDirectory, "uo_" + System.currentTimeMillis() + "_" + item.fileName + ".part");
                long downloaded = download(item, staged, callback, cancelFlag);
                if (downloaded < 0) {
                    result.cancelled = true;
                    UltraOptimizationState.deleteRecursively(staged);
                    report(callback, "Cancelled", index + 1, total);
                    break;
                }
                verify(staged, downloaded, item);
                commit(staged, target);
                staged = null;

                UltraOptimizationState.Entry entry = new UltraOptimizationState.Entry();
                entry.projectId = item.projectId;
                entry.slug = item.slug;
                entry.title = item.title;
                entry.versionId = item.versionId;
                entry.versionNumber = item.versionNumber;
                entry.fileName = item.fileName;
                entry.hashAlgorithm = item.hashAlgorithm;
                entry.hash = item.hash;
                entry.size = item.size;
                entry.modsDirectory = "mods";
                entry.installedAt = System.currentTimeMillis();
                state.entries.add(entry);
                state.save(gameDirectory);

                result.installed++;
                log(callback, item.title + " " + item.versionNumber + " installed ("
                        + humanReadableSize(item.size) + ", verified with " + item.hashAlgorithm.toUpperCase(Locale.US) + ").");
                report(callback, "Installed " + item.title, index + 1, total);
            } catch (Exception e) {
                result.failed++;
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                result.errors.add(item.title + ": " + message);
                log(callback, item.title + " failed: " + message);
                UltraOptimizationState.deleteRecursively(staged);
            }
        }

        try {
            if (result.installed > 0) state.save(gameDirectory);
        } catch (IOException e) {
            result.errors.add("The install log could not be saved: " + e.getMessage());
        }
        UltraOptimizationState.deleteRecursively(tempDirectory);
        return result;
    }

    /**
     * Undo everything Ultra-Optimization did to this installation.
     *
     * Files are removed only when they still match the hash we installed, and displaced files are
     * put back from the backup. Anything else in the mods directory is never touched.
     */
    public static Result restore(File gameDirectory, Callback callback) {
        Result result = new Result();
        if (gameDirectory == null) {
            result.errors.add("No installation is selected.");
            return result;
        }
        UltraOptimizationState state = UltraOptimizationState.load(gameDirectory);
        if (state == null) {
            result.errors.add("This installation has no Ultra-Optimization state to restore.");
            return result;
        }
        List<UltraOptimizationState.Entry> entries = new ArrayList<>(state.entries);
        int total = entries.size();
        for (int index = total - 1; index >= 0; index--) {
            UltraOptimizationState.Entry entry = entries.get(index);
            report(callback, "Restoring " + (entry.title == null ? entry.fileName : entry.title),
                    total - index, total);
            File modsDirectory = new File(gameDirectory,
                    entry.modsDirectory == null ? "mods" : entry.modsDirectory);
            File installed = new File(modsDirectory, entry.fileName);
            File backup = entry.backupFileName == null ? null
                    : new File(UltraOptimizationState.backupDirectory(gameDirectory), entry.backupFileName);

            if (installed.isFile()) {
                boolean ours = matchesHash(installed, entry.hashAlgorithm, entry.hash);
                if (!ours) {
                    result.skipped++;
                    log(callback, entry.fileName + " was changed since it was installed, so it was left in place.");
                    continue;
                }
                if (!installed.delete()) {
                    result.failed++;
                    result.errors.add("Cannot delete " + entry.fileName);
                    continue;
                }
                result.removed++;
                log(callback, "Removed " + entry.fileName + ".");
            }

            if (backup != null && backup.isFile()) {
                File restored = new File(modsDirectory, entry.fileName);
                if (restored.exists()) {
                    log(callback, entry.fileName + " already exists again, its backup was kept.");
                } else {
                    try {
                        if (!modsDirectory.isDirectory() && !modsDirectory.mkdirs()) {
                            throw new IOException("Cannot create " + modsDirectory.getAbsolutePath());
                        }
                        move(backup, restored);
                        log(callback, "Restored the previous " + entry.fileName + ".");
                        result.restored = true;
                    } catch (IOException e) {
                        result.failed++;
                        result.errors.add("Cannot restore " + entry.fileName + ": " + e.getMessage());
                    }
                }
            }
            state.entries.remove(entry);
        }

        try {
            if (state.entries.isEmpty()) {
                state.delete(gameDirectory);
            } else {
                state.save(gameDirectory);
            }
        } catch (IOException e) {
            result.errors.add("The install log could not be updated: " + e.getMessage());
        }
        result.restored = result.removed > 0 || result.restored;
        report(callback, "Restore complete", total, total);
        return result;
    }

    /** Discards interrupted downloads without touching the mods directory. */
    public static void discardStaging(File gameDirectory) {
        UltraOptimizationState.deleteRecursively(UltraOptimizationState.tempDirectory(gameDirectory));
    }

    private static void backupExistingFile(File gameDirectory, PlanItem item, File existing,
                                           UltraOptimizationState state) throws IOException {
        File backupDirectory = UltraOptimizationState.backupDirectory(gameDirectory);
        if (!backupDirectory.isDirectory() && !backupDirectory.mkdirs()) {
            throw new IOException("Cannot create the backup directory");
        }
        String backupName = System.currentTimeMillis() + "_" + item.fileName;
        File backup = new File(backupDirectory, backupName);
        copy(existing, backup);
        UltraOptimizationState.Entry entry = state.findEntryByFileName(item.fileName);
        if (entry == null) {
            entry = new UltraOptimizationState.Entry();
            entry.fileName = item.fileName;
            entry.modsDirectory = "mods";
            entry.title = item.title;
            entry.projectId = item.projectId;
            entry.slug = item.slug;
            state.entries.add(entry);
        }
        entry.backupFileName = backupName;
        entry.replacedSize = existing.length();
        entry.replacedHashAlgorithm = "sha-1";
        entry.replacedHash = digestHex(existing, "SHA-1");
    }

    private static long download(PlanItem item, File destination, Callback callback,
                                 AtomicBoolean cancelFlag) throws IOException {
        HttpURLConnection connection = null;
        OutputStream outputStream = null;
        try {
            connection = (HttpURLConnection) new URL(item.downloadUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Download server returned HTTP " + status);
            }
            long expectedLength = connection.getContentLengthLong();
            if (expectedLength > MAX_FILE_BYTES) throw new IOException("File is larger than the 32 MB safety cap");
            InputStream inputStream = connection.getInputStream();
            MessageDigest digest = newDigest(item.hashAlgorithm);
            DigestInputStream digestInputStream = digest == null
                    ? null : new DigestInputStream(inputStream, digest);
            long written = 0;
            int percent = -1;
            try {
                outputStream = new FileOutputStream(destination);
                byte[] buffer = new byte[16384];
                int read;
                while ((read = (digestInputStream == null ? inputStream : digestInputStream).read(buffer)) != -1) {
                    if (isCancelled(cancelFlag)) return -1;
                    written += read;
                    if (written > MAX_FILE_BYTES) throw new IOException("File exceeds the 32 MB safety cap");
                    outputStream.write(buffer, 0, read);
                    if (expectedLength > 0) {
                        int next = (int) (written * 100L / expectedLength);
                        if (next / 5 != percent / 5) {
                            percent = next;
                            log(callback, "Downloading " + item.title + " - " + percent + "%");
                        }
                    }
                }
            } finally {
                if (outputStream != null) {
                    outputStream.flush();
                    outputStream.close();
                    outputStream = null;
                }
                (digestInputStream == null ? inputStream : digestInputStream).close();
            }
            if (digest != null) {
                String actual = toHex(digest.digest());
                if (!actual.equalsIgnoreCase(item.hash)) {
                    throw new IOException("Integrity check failed: computed " + item.hashAlgorithm
                            + " does not match the Modrinth hash");
                }
            }
            if (item.size > 0 && written != item.size) {
                throw new IOException("Size mismatch: expected " + item.size + " bytes, received " + written);
            }
            return written;
        } finally {
            if (outputStream != null) closeQuietly(outputStream);
            if (connection != null) connection.disconnect();
        }
    }

    private static void verify(File file, long length, PlanItem item) throws IOException {
        if (!file.isFile() || length <= 0) throw new IOException("The downloaded file is missing or empty");
        if (item.size > 0 && length != item.size) throw new IOException("Size mismatch after staging");
        if (item.hash == null || item.hashAlgorithm == null) throw new IOException("No hash to verify");
        String algorithm = normalizeAlgorithm(item.hashAlgorithm);
        String actual = digestHex(file, algorithm);
        if (!actual.equalsIgnoreCase(item.hash)) {
            throw new IOException("Integrity check failed for the staged file (" + algorithm + ")");
        }
        if (!isZipJar(file)) throw new IOException("The downloaded file is not a readable JAR");
    }

    private static void commit(File staged, File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
        if (!staged.renameTo(target)) move(staged, target);
    }

    private static void move(File source, File target) throws IOException {
        copy(source, target);
        // noinspection ResultOfMethodCallIgnored
        source.delete();
    }

    private static void copy(File source, File target) throws IOException {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(source);
            outputStream = new FileOutputStream(target);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = inputStream.read(buffer)) != -1) outputStream.write(buffer, 0, read);
            outputStream.flush();
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
        }
    }

    private static boolean matchesHash(File file, String algorithm, String expectedHash) {
        if (algorithm == null || expectedHash == null) return false;
        try {
            return digestHex(file, normalizeAlgorithm(algorithm)).equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static String digestHex(File file, String algorithm) throws IOException {
        InputStream inputStream = null;
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            inputStream = new DigestInputStream(new FileInputStream(file), digest);
            byte[] buffer = new byte[16384];
            // noinspection StatementWithEmptyBody
            while (inputStream.read(buffer) != -1) { /* digest.update happens in DigestInputStream */ }
            return toHex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot compute " + algorithm + ": " + e.getMessage());
        } finally {
            closeQuietly(inputStream);
        }
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(normalizeAlgorithm(algorithm));
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) return "SHA-1";
        String lower = algorithm.toLowerCase(Locale.US).replace("-", "");
        if (lower.startsWith("sha512") || lower.startsWith("sha384") || lower.startsWith("sha256")) {
            return "SHA-" + lower.substring(3);
        }
        if (lower.startsWith("sha1")) return "SHA-1";
        return "SHA-1";
    }

    /** A Minecraft mod is a ZIP; checking the magic bytes rejects truncated or HTML error pages. */
    private static boolean isZipJar(File file) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            int first = inputStream.read();
            int second = inputStream.read();
            return first == 'P' && second == 'K';
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(inputStream);
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelFlag) {
        return (cancelFlag != null && cancelFlag.get()) || Thread.currentThread().isInterrupted();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private static String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static void report(Callback callback, String message, int completed, int total) {
        Log.i(TAG, message + " (" + completed + "/" + total + ")");
        if (callback != null) callback.onProgress(message, completed, total);
    }

    private static void log(Callback callback, String message) {
        Log.i(TAG, message);
        if (callback != null) callback.onLog(message);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}

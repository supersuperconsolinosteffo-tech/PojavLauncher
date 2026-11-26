package net.kdt.pojavlaunch.modloaders.modpacks.imagecache;

import android.util.Log;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.prefs.PerformanceProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Keeps the mod icon cache under the limit chosen by the performance profile.
 */
public class IconCacheJanitor implements Runnable {
    private static Future<?> sJanitorFuture;
    private static boolean sJanitorRan = false;

    private IconCacheJanitor() {
        // don't allow others to create this
    }

    @Override
    public void run() {
        boolean cleaned = false;
        try {
            File modIconCachePath = ModIconCache.getImageCachePath();
            if(!modIconCachePath.isDirectory() || !modIconCachePath.canRead()) return;
            File[] modIconFiles = modIconCachePath.listFiles();
            if(modIconFiles == null) return;
            ArrayList<File> writableModIconFiles = new ArrayList<>(modIconFiles.length);
            long directoryFileSize = 0;
            for(File modIconFile : modIconFiles) {
                if(!modIconFile.isFile() || !modIconFile.canRead()) continue;
                directoryFileSize += modIconFile.length();
                if(modIconFile.canWrite()) writableModIconFiles.add(modIconFile);
            }
            long sizeLimit = PerformanceProfile.getIconCacheLimitBytes();
            long bringdownSize = PerformanceProfile.getIconCacheBringdownBytes();
            if(directoryFileSize < sizeLimit || writableModIconFiles.isEmpty()) return;

            // Delete the least recently used icons first.
            Collections.sort(writableModIconFiles, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            int filesCleanedUp = 0;
            for(File modFile : writableModIconFiles) {
                if(directoryFileSize < bringdownSize) break;
                long modFileSize = modFile.length();
                if(modFile.delete()) {
                    directoryFileSize -= modFileSize;
                    filesCleanedUp++;
                }
            }
            Log.i("IconCacheJanitor", "Cleaned up " + filesCleanedUp + " files");
            cleaned = filesCleanedUp > 0;
        } finally {
            synchronized (IconCacheJanitor.class) {
                sJanitorFuture = null;
                sJanitorRan = cleaned;
            }
        }
    }

    /** Runs the janitor task, unless there was one running already or one has ran already */
    public static void runJanitor() {
        synchronized (IconCacheJanitor.class) {
            if (sJanitorFuture != null || sJanitorRan) return;
            sJanitorFuture = PojavApplication.sExecutorService.submit(new IconCacheJanitor());
        }
    }

    /**
     * Waits for the janitor task to finish, if there is one running already
     * Note that the thread waiting must not be interrupted.
     */
    public static void waitForJanitorToFinish() {
        synchronized (IconCacheJanitor.class) {
            if (sJanitorFuture == null) return;
            try {
                sJanitorFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException("Should not happen!", e);
            }
        }
    }
}

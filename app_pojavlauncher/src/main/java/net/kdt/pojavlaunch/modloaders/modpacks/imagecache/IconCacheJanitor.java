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
 * The limit is captured when the task is submitted so a profile change cannot make a running
 * cleanup delete more files than the user selected.
 */
public class IconCacheJanitor implements Runnable {
    private static final String TAG = "IconCacheJanitor";

    private static Future<?> sJanitorFuture;
    private static boolean sJanitorRan;
    private static long sLastSizeLimit = -1L;

    private final long mSizeLimit;
    private final long mBringdownSize;

    private IconCacheJanitor(long sizeLimit, long bringdownSize) {
        mSizeLimit = sizeLimit;
        mBringdownSize = bringdownSize;
    }

    @Override
    public void run() {
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
            if(directoryFileSize < mSizeLimit || writableModIconFiles.isEmpty()) return;

            // Delete the least recently used icons first.
            Collections.sort(writableModIconFiles,
                    (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            int filesCleanedUp = 0;
            for(File modFile : writableModIconFiles) {
                if(directoryFileSize < mBringdownSize) break;
                long modFileSize = modFile.length();
                if(modFile.delete()) {
                    directoryFileSize -= modFileSize;
                    filesCleanedUp++;
                }
            }
            Log.i(TAG, "Cleaned up " + filesCleanedUp + " files");
        } catch (Throwable throwable) {
            // Never let a failed cache scan break icon loading or leave the Future stuck.
            Log.e(TAG, "Failed to clean the mod icon cache", throwable);
        } finally {
            synchronized (IconCacheJanitor.class) {
                sJanitorFuture = null;
                sJanitorRan = true;
            }
        }
    }

    /**
     * Runs the janitor unless it is already running or has already run with the same limit.
     * Re-running after a profile change allows Ultra-Lite to enforce its smaller cache.
     */
    public static void runJanitor() {
        long limit = PerformanceProfile.getIconCacheLimitBytes();
        long bringdown = limit / 2;
        synchronized (IconCacheJanitor.class) {
            if (sJanitorFuture != null) return;
            if (sJanitorRan && sLastSizeLimit == limit) return;
            // The worker's finally block needs this same monitor, so it cannot clear the
            // Future before the assignment below has completed.
            sJanitorFuture = PojavApplication.sExecutorService.submit(
                    new IconCacheJanitor(limit, bringdown));
            sLastSizeLimit = limit;
        }
    }

    /**
     * Waits for a running janitor task to finish. The Future is copied under the monitor and
     * awaited outside it: holding the monitor while calling get() would deadlock against the
     * worker's finally block.
     */
    public static void waitForJanitorToFinish() {
        Future<?> future;
        synchronized (IconCacheJanitor.class) {
            future = sJanitorFuture;
            if (future == null) return;
        }
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Log.e(TAG, "Janitor task failed", e.getCause());
        }
    }
}

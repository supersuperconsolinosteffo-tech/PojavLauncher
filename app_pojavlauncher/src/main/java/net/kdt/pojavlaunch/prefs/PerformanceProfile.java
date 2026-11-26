package net.kdt.pojavlaunch.prefs;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;

import java.io.File;

/**
 * Optional, reversible resource profiles. The default is Balanced, which behaves exactly like
 * the launcher did before profiles existed. Nothing here writes to user preferences unless the
 * user explicitly applies a suggestion or a heap recommendation.
 */
public final class PerformanceProfile {
    public static final String KEY_PROFILE = "performanceProfile";
    public static final String PROFILE_BALANCED = "balanced";
    public static final String PROFILE_ULTRA_LITE = "ultra_lite";
    public static final String PROFILE_CUSTOM = "custom";

    public static final String KEY_MINIMAL_BACKGROUND = "perfMinimalBackground";
    public static final String KEY_SMALL_ICON_CACHE = "perfSmallIconCache";
    public static final String KEY_LOW_PROGRESS_RATE = "perfLowProgressRate";
    public static final String KEY_DISABLE_LOGCAT = "perfDisableLogcatCapture";

    private static final long MEGABYTE = 1024L * 1024L;
    private static final long BALANCED_ICON_CACHE_LIMIT = 100L * MEGABYTE;
    private static final long LITE_ICON_CACHE_LIMIT = 16L * MEGABYTE;

    private PerformanceProfile() {}

    private static SharedPreferences prefs() {
        return LauncherPreferences.DEFAULT_PREF;
    }

    public static String getProfile() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return PROFILE_BALANCED;
        String value = prefs.getString(KEY_PROFILE, PROFILE_BALANCED);
        if (PROFILE_ULTRA_LITE.equals(value) || PROFILE_CUSTOM.equals(value)) return value;
        return PROFILE_BALANCED;
    }

    /**
     * Reads an optional switch. Ultra-Lite only enables the three resource switches; logcat
     * capture is deliberately left alone there and is controlled by Custom only.
     */
    private static boolean isEnabled(String key, boolean ultraLiteDefault) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return false;
        String profile = getProfile();
        if (PROFILE_ULTRA_LITE.equals(profile)) return ultraLiteDefault;
        if (PROFILE_CUSTOM.equals(profile)) return prefs.getBoolean(key, false);
        return false;
    }

    /** Skips the icon cache scan at launcher start; the scan still runs when mods are browsed. */
    public static boolean useMinimalBackgroundWork() {
        return isEnabled(KEY_MINIMAL_BACKGROUND, true);
    }

    public static boolean useSmallIconCache() {
        return isEnabled(KEY_SMALL_ICON_CACHE, true);
    }

    public static boolean useLowProgressRate() {
        return isEnabled(KEY_LOW_PROGRESS_RATE, true);
    }

    /** Custom only. Disabling logcat capture leaves just the launcher lines in latestlog.txt. */
    public static boolean isLogcatCaptureDisabled() {
        SharedPreferences prefs = prefs();
        if (prefs == null || !PROFILE_CUSTOM.equals(getProfile())) return false;
        return prefs.getBoolean(KEY_DISABLE_LOGCAT, false);
    }

    public static long getIconCacheLimitBytes() {
        return useSmallIconCache() ? LITE_ICON_CACHE_LIMIT : BALANCED_ICON_CACHE_LIMIT;
    }

    public static long getIconCacheBringdownBytes() {
        return getIconCacheLimitBytes() / 2;
    }

    public static int getIconLoaderThreads() {
        return useSmallIconCache() ? 1 : 2;
    }

    public static long getProgressIntervalMs() {
        return useLowProgressRate() ? 250L : 100L;
    }

    /**
     * A conservative heap suggestion for the existing "allocation" preference. It leaves room
     * for Android, native drivers and the launcher itself, and is never applied automatically.
     * The result is clamped to the same range as the Java settings seek bar.
     */
    public static int getRecommendedHeapMb(Context context) {
        DeviceInfo info = DeviceInfo.detect(context);
        int total = info.totalRamMb;
        if (total <= 0) return 1024;
        int byTotal = (int) (total * 0.30f);
        int recommended = Math.max(512, Math.min(1536, byTotal));
        int byAvailable = Math.max(256, info.availableRamMb - 512);
        recommended = Math.min(recommended, byAvailable);
        if (info.is32Bit) recommended = Math.min(recommended, 696);

        // Same bound as LauncherPreferenceJavaFragment, so setValue() cannot go out of range.
        int seekMax;
        if (info.is32Bit || total < 2048) seekMax = Math.min(1024, total);
        else seekMax = total - (total < 3064 ? 800 : 1024);
        seekMax = Math.max(256, seekMax);
        recommended = Math.min(recommended, seekMax);
        return Math.max(256, recommended);
    }

    /** Explicit user action: writes the suggested value to the existing allocation key. */
    public static int applyRecommendedHeap(Context context) {
        int value = getRecommendedHeapMb(context);
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putInt("allocation", value).apply();
            LauncherPreferences.PREF_RAM_ALLOCATION = value;
        }
        return value;
    }

    /** Device capability reading used to suggest a profile. Never applied on its own. */
    public static final class DeviceInfo {
        public final int totalRamMb;
        public final int availableRamMb;
        public final int androidApi;
        public final String androidRelease;
        public final String cpuAbi;
        public final int cpuCores;
        public final int installedRuntimes;
        public final boolean is32Bit;

        private DeviceInfo(int totalRamMb, int availableRamMb, int androidApi,
                           String androidRelease, String cpuAbi, int cpuCores,
                           int installedRuntimes, boolean is32Bit) {
            this.totalRamMb = totalRamMb;
            this.availableRamMb = availableRamMb;
            this.androidApi = androidApi;
            this.androidRelease = androidRelease;
            this.cpuAbi = cpuAbi;
            this.cpuCores = cpuCores;
            this.installedRuntimes = installedRuntimes;
            this.is32Bit = is32Bit;
        }

        public static DeviceInfo detect(Context context) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            ActivityManager activityManager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) activityManager.getMemoryInfo(memory);
            int totalMb = (int) (memory.totalMem / MEGABYTE);
            int availableMb = (int) (memory.availMem / MEGABYTE);
            String[] abis = Build.SUPPORTED_ABIS;
            String abi = abis != null && abis.length > 0 ? abis[0] : "unknown";
            int runtimes = 0;
            if (Tools.MULTIRT_HOME != null) {
                String[] entries = new File(Tools.MULTIRT_HOME).list();
                if (entries != null) runtimes = entries.length;
            }
            return new DeviceInfo(totalMb, availableMb, Build.VERSION.SDK_INT,
                    Build.VERSION.RELEASE, abi, Runtime.getRuntime().availableProcessors(),
                    runtimes, Architecture.is32BitsDevice());
        }

        /**
         * Devices with the same total RAM can behave differently, so available memory, address
         * space and core count are taken into account too. A failed memory read (zero values)
         * must not be mistaken for a low-RAM device. This is a suggestion only.
         */
        public String suggestProfile() {
            if (totalRamMb > 0 && totalRamMb <= 4096) return PROFILE_ULTRA_LITE;
            if (totalRamMb > 0 && availableRamMb < 1200) return PROFILE_ULTRA_LITE;
            if (is32Bit) return PROFILE_ULTRA_LITE;
            if (totalRamMb > 0 && totalRamMb <= 6144 && cpuCores > 0 && cpuCores <= 4) {
                return PROFILE_ULTRA_LITE;
            }
            return PROFILE_BALANCED;
        }

        public String describe(Context context, int suggestedProfileNameRes) {
            return context.getString(net.kdt.pojavlaunch.R.string.perf_device_info,
                    Tools.formatFileSize((long) totalRamMb * MEGABYTE),
                    Tools.formatFileSize((long) availableRamMb * MEGABYTE),
                    androidRelease, androidApi, cpuAbi, cpuCores, installedRuntimes,
                    context.getString(suggestedProfileNameRes));
        }
    }
}

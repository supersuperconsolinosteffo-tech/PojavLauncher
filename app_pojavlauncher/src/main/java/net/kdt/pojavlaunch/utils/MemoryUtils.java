package net.kdt.pojavlaunch.utils;

import android.app.ActivityManager;
import android.content.Context;

/**This class is modified from Fold Craft Launcher.*/
public class MemoryUtils {
    private static ActivityManager activityManager;

    private static void init(Context context) {
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /** One binder call for callers that need total, used and available memory together. */
    public static ActivityManager.MemoryInfo getMemoryInfo(Context context) {
        if (activityManager == null) init(context);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);
        return memInfo;
    }

    public static long getTotalDeviceMemory(Context context) {
        return getMemoryInfo(context).totalMem;
    }

    public static long getUsedDeviceMemory(Context context) {
        ActivityManager.MemoryInfo memInfo = getMemoryInfo(context);
        return memInfo.totalMem - memInfo.availMem;
    }

    public static long getFreeDeviceMemory(Context context) {
        return getMemoryInfo(context).availMem;
    }
}

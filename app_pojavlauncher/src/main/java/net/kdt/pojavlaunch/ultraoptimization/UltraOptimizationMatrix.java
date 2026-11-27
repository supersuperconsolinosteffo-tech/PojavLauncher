package net.kdt.pojavlaunch.ultraoptimization;

/**
 * Documents the two independent state domains. Ultra-Lite remains a global launcher
 * SharedPreference; Ultra-Optimization state is a file inside one installation's game directory.
 */
public final class UltraOptimizationMatrix {
    private UltraOptimizationMatrix() {}

    /** Existing global Ultra-Lite key. Ultra-Optimization only reads it for display. */
    public static final String ULTRA_LITE_KEY = "performanceProfile";
    /** New per-installation state path, relative to the selected game directory. */
    public static final String ULTRA_OPTIMIZATION_STATE = ".ultra_optimization/state.json";

    public static boolean independentStateDomains() {
        return !ULTRA_LITE_KEY.equals(ULTRA_OPTIMIZATION_STATE)
                && ULTRA_OPTIMIZATION_STATE.startsWith(".ultra_optimization/")
                && !ULTRA_OPTIMIZATION_STATE.startsWith("shared_prefs/");
    }

    public static String describe(boolean ultraLiteOn, boolean ultraOptimizationOn) {
        if (!ultraLiteOn && !ultraOptimizationOn) return "Normal launcher";
        if (ultraLiteOn && !ultraOptimizationOn) return "Lightweight launcher";
        if (!ultraLiteOn) return "Minecraft optimized through mods";
        return "Both optimizations simultaneously";
    }

    public static String[] allCombinations() {
        return new String[]{
                describe(false, false),
                describe(true, false),
                describe(false, true),
                describe(true, true)
        };
    }
}

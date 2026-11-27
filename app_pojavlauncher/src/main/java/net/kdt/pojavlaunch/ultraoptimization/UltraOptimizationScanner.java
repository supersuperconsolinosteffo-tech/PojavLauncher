package net.kdt.pojavlaunch.ultraoptimization;

import android.content.Context;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.JVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.MoJsonExtras;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Environment;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Loader;
import net.kdt.pojavlaunch.value.DependentLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the currently selected Minecraft installation once, on demand, and turns it into the pure
 * {@link Environment} the resolver works on.
 *
 * This class performs no caching, no listener registration, and no scheduled work: the scan runs
 * when the Ultra-Optimization screen is opened (or explicitly re-scanned) and again when the user
 * presses Optimize. It only reads; nothing here writes the renderer, the runtime, or any profile.
 */
public final class UltraOptimizationScanner {
    private UltraOptimizationScanner() {}

    public static final class Target {
        public String instanceName;
        public File gameDirectory;
        public Environment environment;
        public String error;
        public int installedByUs;
        public boolean stateExists;

        public boolean isUsable() {
            return error == null && environment != null;
        }
    }

    /** Load the selected installation and collect everything the resolver needs. Never throws. */
    public static Target scanSelectedInstallation(Context context) {
        Target target = new Target();
        try {
            Instance instance = Instances.loadSelectedInstance();
            if (instance == null) {
                target.error = "No Minecraft installation is selected. Create or select one first.";
                return target;
            }
            target.instanceName = instance.name;
            File gameDirectory = instance.getGameDirectory();
            target.gameDirectory = gameDirectory;

            Environment environment = new Environment(null, Loader.UNKNOWN,
                    instance.getLaunchRenderer(), 0, instance.selectedRuntime != null,
                    0, Architecture.is32BitsDevice(),
                    gameDirectory == null ? null : gameDirectory.getAbsolutePath(),
                    instance.sharedData, System.currentTimeMillis());

            readVersionMetadata(instance, environment, target);

            environment.runtimeJavaMajor = readRuntimeJavaVersion(instance);
            environment.totalRamMb = readAllocatedRam(context);
            readInstalledMods(gameDirectory, environment.minecraftVersion, environment);

            UltraOptimizationState state = UltraOptimizationState.load(gameDirectory);
            if (state != null) {
                target.stateExists = true;
                target.installedByUs = state.installedCount();
                for (UltraOptimizationState.Entry entry : state.entries) {
                    if (entry == null) continue;
                    if (entry.projectId != null) environment.installedProjectIds.add(entry.projectId);
                    if (entry.slug != null) environment.installedProjectIds.add(entry.slug);
                    if (entry.fileName != null) environment.installedFileNames.add(entry.fileName);
                }
            }

            target.environment = environment;
            return target;
        } catch (Throwable thrown) {
            String message = thrown.getMessage();
            target.error = message == null ? thrown.getClass().getSimpleName() : message;
            return target;
        }
    }

    /**
     * The launcher's {@code Tools.getVersionInfo} merges only a fixed set of fields from the
     * profile into the parent version, which drops the loader libraries. Ultra-Optimization needs
     * those to tell Fabric, Quilt, Forge and NeoForge apart, so the profile JSON and its parent
     * are read directly, and the parent stays the authority for the Minecraft version and Java.
     */
    private static void readVersionMetadata(Instance instance, Environment environment, Target target) {
        String versionId = instance.versionId;
        if (versionId == null || versionId.trim().isEmpty()) {
            target.error = "The selected installation has no version selected yet.";
            return;
        }
        String normalized = MoJsonExtras.normalizeVersionId(versionId);
        if (UltraOptimizationDetector.isVersionAlias(normalized)) {
            target.error = "This installation points at \"" + versionId + "\" instead of an exact"
                    + " Minecraft version. Select a concrete version before optimizing; nothing was changed.";
            return;
        }
        versionId = normalized;

        JVersionList.Version profile = readVersionJson(versionId);
        if (profile == null) {
            target.error = "The version metadata of \"" + versionId + "\" is unavailable. Launch this"
                    + " installation once so the launcher prepares it, then re-scan.";
            return;
        }
        JVersionList.Version parent = profile;
        if (profile.inheritsFrom != null && !profile.inheritsFrom.equals(profile.id)) {
            JVersionList.Version inherited = readVersionJson(profile.inheritsFrom);
            if (inherited != null) parent = inherited;
        }

        List<String> libraryNames = new ArrayList<>();
        collectLibraryNames(profile, libraryNames);
        if (parent != profile) collectLibraryNames(parent, libraryNames);

        String mainClass = profile.mainClass != null ? profile.mainClass : parent.mainClass;
        environment.minecraftVersion = UltraOptimizationDetector
                .detectMinecraftVersion(profile.id, profile.inheritsFrom, parent.id);
        environment.loader = UltraOptimizationDetector.detectLoader(profile.id, mainClass, libraryNames);
        environment.requiredJavaMajor = readRequiredJava(parent, profile);
    }

    private static JVersionList.Version readVersionJson(String versionId) {
        if (versionId == null || versionId.trim().isEmpty()) return null;
        if (versionId.indexOf('/') >= 0 || versionId.indexOf('\\') >= 0 || versionId.contains("..")
                || versionId.indexOf(':') >= 0) {
            return null;
        }
        try {
            File json = new File(Tools.DIR_HOME_VERSION + "/" + versionId + "/" + versionId + ".json");
            if (!json.isFile()) return null;
            return Tools.GLOBAL_GSON.fromJson(Tools.read(json), JVersionList.Version.class);
        } catch (Throwable thrown) {
            return null;
        }
    }

    private static void collectLibraryNames(JVersionList.Version version, List<String> out) {
        if (version == null || version.libraries == null) return;
        for (DependentLibrary library : version.libraries) {
            if (library == null) continue;
            if (library.name != null) out.add(library.name.toString());
        }
    }

    private static int readRequiredJava(JVersionList.Version parent, JVersionList.Version profile) {
        if (profile != null && profile.javaVersion != null && profile.javaVersion.majorVersion > 0) {
            return profile.javaVersion.majorVersion;
        }
        if (parent != null && parent.javaVersion != null && parent.javaVersion.majorVersion > 0) {
            return parent.javaVersion.majorVersion;
        }
        return 0; // resolver falls back to its documented version heuristic
    }

    private static int readRuntimeJavaVersion(Instance instance) {
        try {
            String runtimeName = Tools.getSelectedRuntime(instance);
            if (runtimeName == null || runtimeName.trim().isEmpty()) return 0;
            Runtime runtime = MultiRTUtils.forceReread(runtimeName);
            if (runtime == null) return 0;
            return runtime.javaVersion; // 0 when unknown: never guess a runtime
        } catch (Throwable thrown) {
            return 0;
        }
    }

    private static int readAllocatedRam(Context context) {
        int allocated = LauncherPreferences.PREF_RAM_ALLOCATION;
        if (allocated > 0) return allocated;
        if (context == null) return 0;
        try {
            return Tools.getTotalDeviceMemory(context);
        } catch (Throwable thrown) {
            return 0;
        }
    }

    /**
     * Shallow listing of the installation's mods. Both the common {@code mods} directory and the
     * Forge-style {@code mods/<mcversion>} directory are read, because a loader may have been
     * installed with either layout. No archive is opened, so a scan stays cheap.
     */
    private static void readInstalledMods(File gameDirectory, String minecraftVersion,
                                          Environment environment) {
        if (gameDirectory == null) return;
        File mods = new File(gameDirectory, "mods");
        collectJarNames(mods, environment);
        if (minecraftVersion != null && minecraftVersion.length() > 0) {
            collectJarNames(new File(mods, minecraftVersion), environment);
        }
    }

    private static void collectJarNames(File directory, Environment environment) {
        if (directory == null || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".jar")) continue;
            environment.installedFileNames.add(name);
        }
    }
}

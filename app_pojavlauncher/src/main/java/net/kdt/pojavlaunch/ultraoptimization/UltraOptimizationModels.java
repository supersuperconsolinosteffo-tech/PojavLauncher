package net.kdt.pojavlaunch.ultraoptimization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Data structures shared by the Ultra-Optimization resolver, Android client and offline tests.
 *
 * This class intentionally has no Android imports. It can be compiled with a plain JDK so the
 * compatibility policy can be verified without an emulator or a live Modrinth connection.
 */
public final class UltraOptimizationModels {
    private UltraOptimizationModels() {}

    public enum Loader {
        VANILLA("Vanilla", new String[0]),
        FABRIC("Fabric", new String[]{"fabric"}),
        FORGE("Forge", new String[]{"forge"}),
        NEOFORGE("NeoForge", new String[]{"neoforge"}),
        QUILT("Quilt", new String[]{"quilt", "fabric"}),
        UNKNOWN("Unknown", new String[0]);

        private final String mDisplayName;
        private final String[] mModrinthTags;

        Loader(String displayName, String[] modrinthTags) {
            mDisplayName = displayName;
            mModrinthTags = modrinthTags;
        }

        public String displayName() { return mDisplayName; }

        public List<String> modrinthTags() {
            return Collections.unmodifiableList(Arrays.asList(mModrinthTags));
        }
    }

    public enum ItemState {
        RECOMMENDED, INSTALLED, SKIPPED, FAILED
    }

    public static final class Environment {
        public String minecraftVersion;
        public Loader loader = Loader.UNKNOWN;
        public String renderer;
        /** Java major version required by Minecraft, read from the version JSON. 0 means unknown. */
        public int requiredJavaMajor;
        public int runtimeJavaMajor;
        public boolean runtimeExplicit;
        public int totalRamMb;
        public boolean is32Bit;
        public String gameDirectory;
        public boolean sharedData;
        public long nowEpochMs;
        public final Set<String> installedProjectIds = new HashSet<>();
        public final Set<String> installedModIds = new HashSet<>();
        public final Set<String> installedFileNames = new HashSet<>();

        public Environment(String minecraftVersion, Loader loader, String renderer,
                           int runtimeJavaMajor, boolean runtimeExplicit, int totalRamMb,
                           boolean is32Bit, String gameDirectory, boolean sharedData,
                           long nowEpochMs) {
            this.minecraftVersion = minecraftVersion;
            this.loader = loader == null ? Loader.UNKNOWN : loader;
            this.renderer = renderer;
            this.runtimeJavaMajor = runtimeJavaMajor;
            this.runtimeExplicit = runtimeExplicit;
            this.totalRamMb = totalRamMb;
            this.is32Bit = is32Bit;
            this.gameDirectory = gameDirectory;
            this.sharedData = sharedData;
            this.nowEpochMs = nowEpochMs > 0 ? nowEpochMs : System.currentTimeMillis();
        }
    }

    /** Static candidate metadata. No version is fixed; all usable versions come from Modrinth. */
    public static final class Candidate {
        public final String slug;
        public final String title;
        public final String description;
        /** Heuristic benefit, 1 (small) to 5 (large). This is not a measured result. */
        public final int benefit;
        /** Heuristic runtime cost, 1 (small) to 5 (large). This is not a measured result. */
        public final int cost;
        /** Heuristic resident/loaded-cost allowance in MB. This is not a measured result. */
        public final int estimatedCostMb;
        public final boolean sodium;
        public final boolean memoryLeakFix;
        public final String exclusiveGroup;
        public final Set<Loader> supportedLoaders;
        public final List<String> filenameTokens;

        public Candidate(String slug, String title, String description, int benefit, int cost,
                         int estimatedCostMb, boolean sodium, boolean memoryLeakFix,
                         String exclusiveGroup, List<String> filenameTokens) {
            this.slug = slug;
            this.title = title;
            this.description = description;
            this.benefit = benefit;
            this.cost = cost;
            this.estimatedCostMb = estimatedCostMb;
            this.sodium = sodium;
            this.memoryLeakFix = memoryLeakFix;
            this.exclusiveGroup = exclusiveGroup;
            this.supportedLoaders = Collections.unmodifiableSet(EnumSet.of(
                    Loader.FABRIC, Loader.FORGE, Loader.NEOFORGE, Loader.QUILT));
            this.filenameTokens = Collections.unmodifiableList(new ArrayList<>(filenameTokens));
        }

        public int netScore() { return benefit * 2 - cost; }
    }

    public static List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        list.add(new Candidate("ferrite-core", "FerriteCore",
                "Reduces Minecraft's memory footprint by deduplicating/block-compressing common runtime data.",
                5, 1, 2, false, false, null,
                Arrays.asList("ferritecore", "ferrite-core", "ferrite_core")));
        list.add(new Candidate("lithium", "Lithium",
                "Optimizes game logic such as entities, block ticking and physics without changing gameplay.",
                5, 1, 1, false, false, null,
                Arrays.asList("lithium")));
        list.add(new Candidate("sodium", "Sodium",
                "Rewrites chunk rendering for much lower CPU/GPU work; renderer compatibility is validated separately.",
                5, 3, 4, true, false, "sodium-family",
                Arrays.asList("sodium")));
        list.add(new Candidate("modernfix", "ModernFix",
                "Reduces launch time and memory use, and fixes several modern-Minecraft performance bottlenecks.",
                4, 2, 3, false, false, null,
                Arrays.asList("modernfix", "modern-fix")));
        list.add(new Candidate("immediatelyfast", "ImmediatelyFast",
                "Speeds up immediate-mode rendering and entity/UI batching on supported loaders.",
                3, 2, 2, false, false, null,
                Arrays.asList("immediatelyfast", "immediately-fast")));
        list.add(new Candidate("entityculling", "Entity Culling",
                "Skips rendering entities and block entities that are not visible to the player.",
                3, 2, 1, false, false, "culling",
                Arrays.asList("entityculling", "entity-culling")));
        list.add(new Candidate("moreculling", "More Culling",
                "Adds additional block/entity culling; overlaps with Entity Culling, so only one is selected.",
                3, 2, 2, false, false, "culling",
                Arrays.asList("moreculling", "more-culling")));
        list.add(new Candidate("memoryleakfix", "Memory Leak Fix",
                "Fixes specific memory leaks in older Minecraft branches; used only when relevant and maintained.",
                2, 1, 1, false, true, null,
                Arrays.asList("memoryleakfix", "memory-leak-fix", "memory_leak_fix")));
        return Collections.unmodifiableList(list);
    }

    public static Candidate candidateBySlug(String slug) {
        for (Candidate candidate : candidates()) {
            if (candidate.slug.equalsIgnoreCase(slug)) return candidate;
        }
        return null;
    }

    /** Minimal Modrinth project fields used by the resolver. Field names match the API JSON. */
    public static final class ProjectInfo {
        public String id;
        public String slug;
        public String title;
        public String description;
        public String status;
        public String client_side;
        public String server_side;
        public String date_modified;
        public List<String> loaders;
        public List<String> game_versions;
    }

    public static final class FileInfo {
        public String url;
        public String filename;
        public boolean primary;
        public long size;
        public Map<String, String> hashes;
    }

    public static final class DependencyInfo {
        public String project_id;
        public String version_id;
        public String dependency_type;
        public String file_name;
    }

    /** Minimal Modrinth version fields used by the resolver. Field names match the API JSON. */
    public static final class VersionInfo {
        public String id;
        public String project_id;
        public String name;
        public String version_number;
        public String version_type;
        public String status;
        public String date_published;
        public boolean featured;
        public List<String> game_versions;
        public List<String> loaders;
        public List<FileInfo> files;
        public List<DependencyInfo> dependencies;
    }

    public static final class PlanItem {
        public Candidate candidate;
        public String projectId;
        public String slug;
        public String title;
        public String description;
        public String versionId;
        public String versionNumber;
        public String fileName;
        public String downloadUrl;
        public String hashAlgorithm;
        public String hash;
        public long size;
        public ItemState state = ItemState.RECOMMENDED;
        public String reason;
        public boolean dependency;
        public int benefit;
        public int cost;
        public int estimatedCostMb;
        public String installedFileName;

        public boolean isInstallable() {
            return state == ItemState.RECOMMENDED && downloadUrl != null && fileName != null
                    && hash != null && hashAlgorithm != null;
        }
    }

    public static final class Resolution {
        public Environment environment;
        public final List<PlanItem> items = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean canInstall;
        public int selectedCount;
        public int estimatedCostMb;
        public int budgetCount;
        public int requiredJavaMajor;
        public String sodiumPolicyReason;
        /** What the detected renderer means for selection; shown by the screen, never a switch. */
        public String rendererNote;
        public String summary;

        public List<PlanItem> installableItems() {
            List<PlanItem> result = new ArrayList<>();
            for (PlanItem item : items) if (item.isInstallable()) result.add(item);
            return result;
        }

        public List<PlanItem> stateItems(ItemState state) {
            List<PlanItem> result = new ArrayList<>();
            for (PlanItem item : items) if (item.state == state) result.add(item);
            return result;
        }
    }

    public interface DataSource {
        ProjectInfo project(String slugOrId) throws Exception;
        List<ProjectInfo> projects(List<String> idsOrSlugs) throws Exception;
        List<VersionInfo> versions(String projectIdOrSlug, String minecraftVersion,
                                   List<String> loaders) throws Exception;
        VersionInfo version(String versionId) throws Exception;
    }

    public interface ProgressCallback {
        void onProgress(String message, int completed, int total);
    }

    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    public static Map<String, String> hashEntry(String algorithm, String hash) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(algorithm, hash);
        return map;
    }
}

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationDetector;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationMatrix;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Candidate;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.DataSource;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.DependencyInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Environment;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.FileInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ItemState;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Loader;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.PlanItem;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ProjectInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Resolution;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.VersionInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationResolver;

/**
 * Offline compatibility-resolution tests. These fixtures are not live Modrinth responses and do
 * not claim that any mod was downloaded or executed on an Android device.
 */
public final class UltraOptimizationResolverTest {
    private static final String NOW = "2026-11-01T00:00:00Z";
    private static long NOW_MS;

    private UltraOptimizationResolverTest() {}

    public static void main(String[] args) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        NOW_MS = format.parse(NOW).getTime();

        scenario("detector and matrix", new Runnable() { @Override public void run() {
            testDetectorAndMatrix();
        }});
        scenario("Fabric 1.20.1 / LTW / dependencies", new Runnable() { @Override public void run() {
            testFabricLtwWithDependencies();
        }});
        scenario("Forge 1.20.1 / GL4ES / Sodium skip", new Runnable() { @Override public void run() {
            testForgeGl4esSodiumSkip();
        }});
        scenario("NeoForge 1.21.1 / Zink", new Runnable() { @Override public void run() {
            testNeoForgeZink();
        }});
        scenario("Quilt 1.19.2 / Fabric fallback", new Runnable() { @Override public void run() {
            testQuiltFabricFallback();
        }});
        scenario("Vanilla / shared-data / old Java safety", new Runnable() { @Override public void run() {
            testSafetyRefusals();
        }});
        scenario("installed duplicate / Sodium forks", new Runnable() { @Override public void run() {
            testInstalledDetection();
        }});
        scenario("required dependency failure", new Runnable() { @Override public void run() {
            testRequiredDependencyFailure();
        }});
        scenario("incompatible dependency conflict", new Runnable() { @Override public void run() {
            testIncompatibleConflict();
        }});
        scenario("exclusive culling group", new Runnable() { @Override public void run() {
            testCullingGroup();
        }});
        System.out.println("Ultra-Optimization offline tests: ALL PASSED");
    }

    private static void scenario(String name, Runnable body) {
        body.run();
        System.out.println("PASS: " + name);
    }

    private static void testDetectorAndMatrix() {
        check(UltraOptimizationDetector.detectLoader("fabric-loader-0.15.11-1.20.1",
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                Arrays.asList("net.fabricmc:fabric-loader:0.15.11")) == Loader.FABRIC,
                "Fabric loader detection");
        check(UltraOptimizationDetector.detectLoader("quilt-loader-0.26.0-1.19.2",
                "org.quiltmc.loader.impl.launch.knot.KnotClient",
                Arrays.asList("org.quiltmc:quilt-loader:0.26.0")) == Loader.QUILT,
                "Quilt loader detection");
        check(UltraOptimizationDetector.detectLoader("1.20.1-forge-47.2.0", "cpw.mods.modlauncher.Launcher",
                Arrays.asList("net.minecraftforge:fmlloader:1.20.1-47.2.0")) == Loader.FORGE,
                "Forge loader detection");
        check(UltraOptimizationDetector.detectLoader("1.21.1-neoforge-21.1.1", "cpw.mods.modlauncher.Launcher",
                Arrays.asList("net.neoforged:neoforge:21.1.1")) == Loader.NEOFORGE,
                "NeoForge loader detection");
        check(UltraOptimizationDetector.detectLoader("1.20.1", "net.minecraft.client.main.Main",
                Collections.<String>emptyList()) == Loader.VANILLA,
                "Vanilla loader detection");
        check("1.20.1".equals(UltraOptimizationDetector.detectMinecraftVersion(
                "fabric-loader-0.15.11-1.20.1", "1.20.1", "fabric-loader-0.15.11-1.20.1")),
                "Minecraft version extraction");
        check(UltraOptimizationDetector.minimumJavaMajor("1.16.5") == 8, "Java 8 for 1.16.5");
        check(UltraOptimizationDetector.minimumJavaMajor("1.20.1") == 17, "Java 17 for 1.20.1");
        check(UltraOptimizationDetector.minimumJavaMajor("1.21.1") == 21, "Java 21 for 1.21.1");
        check(UltraOptimizationDetector.sodiumPolicy("opengles3_ltw", "1.20.1").allowed,
                "Sodium allowed on LTW 1.20.1");
        check(!UltraOptimizationDetector.sodiumPolicy("opengles2", "1.20.1").allowed,
                "Sodium blocked on GL4ES 1.20.1");
        check(!UltraOptimizationDetector.sodiumPolicy("vulkan_zink", "1.21.1").allowed,
                "Sodium conservatively skipped on unvalidated Zink");

        String[] combinations = UltraOptimizationMatrix.allCombinations();
        check(combinations.length == 4, "Four optimization combinations");
        check("Normal launcher".equals(combinations[0]), "OFF/OFF");
        check("Lightweight launcher".equals(combinations[1]), "ON/OFF");
        check("Minecraft optimized through mods".equals(combinations[2]), "OFF/ON");
        check("Both optimizations simultaneously".equals(combinations[3]), "ON/ON");
        check(UltraOptimizationMatrix.independentStateDomains(), "Independent state domains");
        check(!"performanceProfile".equals(UltraOptimizationMatrix.ULTRA_OPTIMIZATION_STATE),
                "Ultra-Optimization state does not use Ultra-Lite preference key");
    }

    private static void testFabricLtwWithDependencies() {
        FakeSource source = fullSource("1.20.1", "fabric", Arrays.asList("fabric"));
        addDependencyProject(source, "required-api", "required-api-1.20.1", "1.20.1", "fabric");
        VersionInfo ferrite = source.versionById.get("ver-ferrite-core-1.20.1");
        ferrite.dependencies = Collections.singletonList(dependency("id-required-api",
                "required-api-1.20.1", "required"));
        Environment environment = environment("1.20.1", Loader.FABRIC, "opengles3_ltw",
                17, 8192, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);

        check(resolution.canInstall, "Fabric resolution has installable mods");
        check(!resolution.errors.isEmpty() == false, "No fatal errors");
        check(hasInstallable(resolution, "sodium"), "Sodium installable on LTW");
        check(hasInstallable(resolution, "ferrite-core"), "FerriteCore installable");
        check(hasInstallable(resolution, "required-api"), "Required dependency installable");
        check(resolution.selectedCount <= resolution.budgetCount,
                "Selection stayed within cost-aware budget");
        check(resolution.budgetCount == 5, "8 GB fixture uses five-mod budget");
        check(!hasInstallable(resolution, "memoryleakfix"),
                "Memory Leak Fix skipped on 1.20.1");
        check(containsText(resolution.summary, "estimated static cost"),
                "Resolution reports static cost");
    }

    private static void testForgeGl4esSodiumSkip() {
        FakeSource source = fullSource("1.20.1", "forge", Arrays.asList("forge"));
        Environment environment = environment("1.20.1", Loader.FORGE, "opengles2",
                17, 6144, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
        check(resolution.canInstall, "Forge resolution has installable mods");
        check(!hasInstallable(resolution, "sodium"), "Sodium not installable on GL4ES");
        PlanItem sodium = findItem(resolution, "sodium");
        check(sodium != null && sodium.state == ItemState.SKIPPED, "Sodium is skipped");
        check(sodium != null && containsText(sodium.reason, "GL4ES"), "Sodium skip explains GL4ES");
        check(containsAny(resolution.warnings, "Sodium policy"), "Sodium policy is reported");
        check(resolution.selectedCount <= resolution.budgetCount, "Forge selection respects budget");
    }

    private static void testNeoForgeZink() {
        FakeSource source = fullSource("1.21.1", "neoforge", Arrays.asList("neoforge"));
        Environment environment = environment("1.21.1", Loader.NEOFORGE, "vulkan_zink",
                21, 8192, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
        check(resolution.canInstall, "NeoForge resolution has installable mods");
        check(!hasInstallable(resolution, "sodium"),
                "Sodium skipped on renderer without established compatibility");
        PlanItem sodium = findItem(resolution, "sodium");
        check(sodium != null && containsText(sodium.reason, "no validated Sodium compatibility"),
                "Sodium skip explains missing renderer validation");
        check(!hasInstallable(resolution, "memoryleakfix"), "Memory Leak Fix skipped on 1.21.1");
        check(hasInstallable(resolution, "lithium"), "A non-rendering optimization remains available");
    }

    private static void testQuiltFabricFallback() {
        FakeSource source = fullSource("1.19.2", "fabric",
                Arrays.asList("quilt", "fabric"));
        // ModernFix has a Fabric build but its project does not declare Quilt support: reject it.
        ProjectInfo modernFix = source.projects.get("id-modernfix");
        modernFix.loaders = Arrays.asList("fabric");
        Environment environment = environment("1.19.2", Loader.QUILT, "opengles3_ltw",
                17, 8192, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
        check(resolution.canInstall, "Quilt resolution has installable mods");
        check(hasInstallable(resolution, "lithium"), "Fabric build accepted for Quilt-capable project");
        check(!hasInstallable(resolution, "modernfix"),
                "Fabric-only project without Quilt support is rejected");
        PlanItem skipped = findItem(resolution, "modernfix");
        check(skipped != null && containsText(skipped.reason, "compatible Quilt loader"),
                "Quilt rejection is explained");
    }

    private static void testSafetyRefusals() {
        FakeSource source = fullSource("1.20.1", "fabric", Arrays.asList("fabric"));
        Resolution vanilla = UltraOptimizationResolver.resolve(environment("1.20.1",
                Loader.VANILLA, "opengles2", 17, 8192, false), source, null);
        check(!vanilla.canInstall, "Vanilla cannot be optimized");
        check(containsAny(vanilla.errors, "Vanilla detected"), "Vanilla refusal is explicit");

        Resolution shared = UltraOptimizationResolver.resolve(environment("1.20.1",
                Loader.FABRIC, "opengles2", 17, 8192, true), source, null);
        check(!shared.canInstall, "Shared-data installation cannot be optimized");
        check(containsAny(shared.errors, "shared game directory"), "Shared-data refusal is explicit");

        Resolution oldJava = UltraOptimizationResolver.resolve(environment("1.20.1",
                Loader.FABRIC, "opengles2", 8, 8192, false), source, null);
        check(!oldJava.canInstall, "Old Java runtime cannot be optimized");
        check(containsAny(oldJava.errors, "older than Java 17"), "Java mismatch is explicit");
        check(containsAny(oldJava.errors, "no runtime was changed"), "Runtime is not changed");
    }

    private static void testInstalledDetection() {
        FakeSource source = fullSource("1.20.1", "fabric", Arrays.asList("fabric"));
        Environment environment = environment("1.20.1", Loader.FABRIC, "opengles3_ltw",
                17, 8192, false);
        environment.installedProjectIds.add("lithium");
        environment.installedFileNames.add("embeddium-0.3.0.jar");
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
        check(!hasInstallable(resolution, "lithium"), "Already-installed Lithium is not duplicated");
        PlanItem lithium = findItem(resolution, "lithium");
        check(lithium != null && lithium.state == ItemState.INSTALLED, "Lithium shown as installed");
        check(!hasInstallable(resolution, "sodium"), "Sodium is not installed beside Embeddium");
        PlanItem sodium = findItem(resolution, "sodium");
        check(sodium != null && containsText(sodium.reason, "Sodium-family"),
                "Sodium-family conflict is explained");
    }

    private static void testRequiredDependencyFailure() {
        FakeSource source = fullSource("1.20.1", "fabric", Arrays.asList("fabric"));
        VersionInfo ferrite = source.versionById.get("ver-ferrite-core-1.20.1");
        ferrite.dependencies = Collections.singletonList(dependency("missing-project", null, "required"));
        Resolution resolution = UltraOptimizationResolver.resolve(
                environment("1.20.1", Loader.FABRIC, "opengles3_ltw", 17, 8192, false),
                source, null);
        check(!hasInstallable(resolution, "ferrite-core"),
                "Parent is not installed when a required dependency fails");
        check(containsAny(resolution.warnings, "Required dependency"),
                "Required dependency failure is reported");
        check(resolution.canInstall, "Other compatible mods remain installable");
    }

    private static void testIncompatibleConflict() {
        FakeSource source = fullSource("1.20.1", "fabric", Arrays.asList("fabric"));
        VersionInfo ferrite = source.versionById.get("ver-ferrite-core-1.20.1");
        ferrite.dependencies = Collections.singletonList(dependency("id-conflict-mod", null, "incompatible"));
        Environment environment = environment("1.20.1", Loader.FABRIC, "opengles3_ltw",
                17, 8192, false);
        environment.installedProjectIds.add("id-conflict-mod");
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
        check(!hasInstallable(resolution, "ferrite-core"),
                "Incompatible-dependency rule removes the candidate");
        PlanItem skipped = findItem(resolution, "ferrite-core");
        check(skipped != null && skipped.state == ItemState.SKIPPED,
                "Incompatible candidate is shown as skipped");
        check(skipped != null && containsText(skipped.reason, "incompatible-dependency"),
                "Incompatible skip is explained");
    }

    private static void testCullingGroup() {
        FakeSource source = new FakeSource();
        addCandidate(source, UltraOptimizationModels.candidateBySlug("entityculling"),
                "1.20.1", "fabric", Arrays.asList("fabric"), null);
        addCandidate(source, UltraOptimizationModels.candidateBySlug("moreculling"),
                "1.20.1", "fabric", Arrays.asList("fabric"), null);
        Resolution resolution = UltraOptimizationResolver.resolve(
                environment("1.20.1", Loader.FABRIC, "opengles2", 17, 8192, false),
                source, null);
        int cullingInstallable = 0;
        if (hasInstallable(resolution, "entityculling")) cullingInstallable++;
        if (hasInstallable(resolution, "moreculling")) cullingInstallable++;
        check(cullingInstallable == 1, "Exactly one overlapping culling mod is selected");
        PlanItem more = findItem(resolution, "moreculling");
        check(more != null && containsText(more.reason, "exclusive group"),
                "Exclusive-group skip is explained");
    }

    private static Environment environment(String minecraftVersion, Loader loader, String renderer,
                                           int javaMajor, int ramMb, boolean sharedData) {
        return new Environment(minecraftVersion, loader, renderer, javaMajor, true,
                ramMb, false, "/tmp/uo-test/instance", sharedData, NOW_MS);
    }

    private static FakeSource fullSource(String mc, String loaderTag, List<String> projectLoaders) {
        FakeSource source = new FakeSource();
        for (Candidate candidate : UltraOptimizationModels.candidates()) {
            addCandidate(source, candidate, mc, loaderTag, projectLoaders, null);
        }
        return source;
    }

    private static void addCandidate(FakeSource source, Candidate candidate, String mc,
                                     String loaderTag, List<String> projectLoaders,
                                     List<DependencyInfo> dependencies) {
        String id = "id-" + candidate.slug;
        String versionId = "ver-" + candidate.slug + "-" + mc;
        ProjectInfo project = project(candidate.slug, id, candidate.title,
                "Offline fixture for " + candidate.title, projectLoaders, NOW);
        VersionInfo version = version(versionId, id, mc, loaderTag, dependencies);
        source.add(project, version);
    }

    private static void addDependencyProject(FakeSource source, String slug, String versionId,
                                             String mc, String loaderTag) {
        String id = "id-" + slug;
        ProjectInfo project = project(slug, id, "Required API", "Offline dependency fixture",
                Arrays.asList(loaderTag), NOW);
        VersionInfo version = version(versionId, id, mc, loaderTag, null);
        source.add(project, version);
    }

    private static ProjectInfo project(String slug, String id, String title, String description,
                                       List<String> loaders, String modified) {
        ProjectInfo project = new ProjectInfo();
        project.id = id;
        project.slug = slug;
        project.title = title;
        project.description = description;
        project.status = "approved";
        project.client_side = "required";
        project.server_side = "optional";
        project.date_modified = modified;
        project.loaders = loaders;
        project.game_versions = Arrays.asList("1.16.5", "1.19.2", "1.20.1", "1.21.1");
        return project;
    }

    private static VersionInfo version(String versionId, String projectId, String mc,
                                       String loaderTag, List<DependencyInfo> dependencies) {
        VersionInfo version = new VersionInfo();
        version.id = versionId;
        version.project_id = projectId;
        version.name = "1.0.0";
        version.version_number = "1.0.0";
        version.version_type = "release";
        version.status = "listed";
        version.date_published = NOW;
        version.featured = true;
        version.game_versions = Collections.singletonList(mc);
        version.loaders = Collections.singletonList(loaderTag);
        version.dependencies = dependencies == null ? new ArrayList<DependencyInfo>() : dependencies;
        FileInfo file = new FileInfo();
        file.url = "https://cdn.modrinth.com/data/" + projectId + "/" + versionId + ".jar";
        file.filename = versionId + ".jar";
        file.primary = true;
        file.size = 1024L;
        file.hashes = UltraOptimizationModels.hashEntry("sha512", "fixture-hash-" + versionId);
        version.files = Collections.singletonList(file);
        return version;
    }

    private static DependencyInfo dependency(String projectId, String versionId, String type) {
        DependencyInfo item = new DependencyInfo();
        item.project_id = projectId;
        item.version_id = versionId;
        item.dependency_type = type;
        return item;
    }

    private static final class FakeSource implements DataSource {
        final Map<String, ProjectInfo> projects = new HashMap<>();
        final Map<String, VersionInfo> versionById = new HashMap<>();
        final Map<String, List<VersionInfo>> versions = new LinkedHashMap<>();

        void add(ProjectInfo project, VersionInfo version) {
            String slugKey = key(project.slug);
            String idKey = key(project.id);
            projects.put(slugKey, project);
            projects.put(idKey, project);
            versionById.put(version.id, version);
            versions.put(slugKey + "|" + first(version.game_versions), Collections.singletonList(version));
            versions.put(idKey + "|" + first(version.game_versions), Collections.singletonList(version));
        }

        @Override public ProjectInfo project(String slugOrId) {
            return projects.get(key(slugOrId));
        }

        @Override public List<ProjectInfo> projects(List<String> idsOrSlugs) {
            List<ProjectInfo> result = new ArrayList<>();
            for (String value : idsOrSlugs) {
                ProjectInfo project = projects.get(key(value));
                if (project != null) result.add(project);
            }
            return result;
        }

        @Override public List<VersionInfo> versions(String projectIdOrSlug, String minecraftVersion,
                                                     List<String> loaders) {
            List<VersionInfo> result = versions.get(key(projectIdOrSlug) + "|" + minecraftVersion);
            return result == null ? Collections.<VersionInfo>emptyList() : result;
        }

        @Override public VersionInfo version(String versionId) {
            return versionById.get(versionId);
        }

        private static String first(List<String> values) {
            return values == null || values.isEmpty() ? "" : values.get(0);
        }
    }

    private static boolean hasInstallable(Resolution resolution, String slug) {
        for (PlanItem item : resolution.installableItems()) {
            if (slug.equalsIgnoreCase(item.slug) || slug.equalsIgnoreCase(item.projectId)) return true;
        }
        return false;
    }

    private static PlanItem findItem(Resolution resolution, String slug) {
        for (PlanItem item : resolution.items) {
            if (slug.equalsIgnoreCase(item.slug) || slug.equalsIgnoreCase(item.projectId)) return item;
        }
        return null;
    }

    private static boolean containsAny(List<String> values, String needle) {
        for (String value : values) if (containsText(value, needle)) return true;
        return false;
    }

    private static boolean containsText(String value, String needle) {
        return value != null && value.toLowerCase(Locale.US).contains(needle.toLowerCase(Locale.US));
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

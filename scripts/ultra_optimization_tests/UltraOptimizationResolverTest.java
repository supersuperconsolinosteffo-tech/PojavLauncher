import java.io.File;
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

import net.kdt.pojavlaunch.game.renderer.def.Renderers;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationDetector;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationDetector.RendererFamily;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationMatrix;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationDetector.RendererCapability;
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
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationState;


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
        scenario("renderer families: GL4ES, Krypton, LTW, Zink, unknown", new Runnable() { @Override public void run() {
            testRendererFamilyClassification();
        }});
        scenario("Krypton 1.21.11 / Fabric / 2048 MB device profile", new Runnable() { @Override public void run() {
            testKryptonTestedDeviceProfile();
        }});
        scenario("Krypton offers the non-rendering mods", new Runnable() { @Override public void run() {
            testKryptonOffersNonRendererMods();
        }});
        scenario("Krypton resolves required dependencies like any other renderer", new Runnable() { @Override public void run() {
            testKryptonResolvesRequiredDependency();
        }});
        scenario("Krypton with the user's installed set: no-op, not a refusal", new Runnable() { @Override public void run() {
            testKryptonInstalledSetIsNoOp();
        }});
        scenario("per-installation state ownership survives a Krypton run", new Runnable() { @Override public void run() {
            testStateOwnershipAfterKryptonPlan();
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
        check(sodium != null && containsText(sodium.reason, "no validated Sodium path"),
                "Sodium skip explains missing renderer validation");
        check(sodium != null && containsText(sodium.reason, "Zink")
                && containsText(sodium.reason, "4.6"),
                "Sodium skip quotes the renderer and its advertised desktop GL version");
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

    // ------------------------------------------------------------ renderer capability model

    /**
     * Krypton Wrapper (NG-GL4ES) has its own renderer tag in this launcher, so it must be
     * classified as itself: never LTW, never Zink, never plain GL4ES - and an unrecognised tag
     * must never be reported as Krypton either.
     */
    private static void testRendererFamilyClassification() {
        // Tags are compared against the launcher's own constants, not copies.
        check("opengles_nggl4es".equals(Renderers.NGGL4ES_RENDERER),
                "Krypton keeps its distinct launcher tag");
        check(!Renderers.NGGL4ES_RENDERER.equals(Renderers.GL4ES_RENDERER),
                "Krypton does not share the GL4ES tag");

        RendererCapability krypton = UltraOptimizationDetector.describe(Renderers.NGGL4ES_RENDERER);
        check(krypton.identified, "Krypton is identified");
        check(krypton.family == RendererFamily.KRYPTON, "Krypton family");
        check(krypton.isKrypton() && !krypton.isLtw() && !krypton.isGl4es(),
                "Krypton is not classified as LTW or GL4ES");
        check(krypton.family != RendererFamily.ZINK, "Krypton is not classified as Zink");
        check(containsText(krypton.displayName, "Krypton") && containsText(krypton.displayName, "NG-GL4ES"),
                "Krypton display names both spellings");
        check(krypton.esMajor == 3, "Krypton sets up OpenGL ES 3 (glesVersion() == 3)");
        check("3.1".equals(krypton.desktopGlVersion()),
                "Krypton advertises desktop OpenGL 3.1 (LIBGL_GL=31)");
        check(!krypton.launcherGuardsSodium,
                "GameRunner's GL4ES Sodium guard does not cover the Krypton spec");
        check(!krypton.sodiumValidated, "Krypton has no validated Sodium path");
        check(containsText(krypton.note, "libng_gl4es.so"), "Krypton note cites its native library");

        check(UltraOptimizationDetector.describe(Renderers.GL4ES_RENDERER).family == RendererFamily.GL4ES,
                "Standard GL4ES stays GL4ES");
        check(UltraOptimizationDetector.describe("opengles2_4").family == RendererFamily.GL4ES,
                "Legacy alias opengles2_4 is GL4ES");
        check(UltraOptimizationDetector.describe("opengles2_5").family == RendererFamily.GL4ES,
                "Legacy alias opengles2_5 is GL4ES");
        check(UltraOptimizationDetector.describe("opengles2_4").launcherGuardsSodium,
                "Legacy GL4ES aliases keep the launcher guard");
        check(UltraOptimizationDetector.describe(Renderers.LTW_RENDERER).isLtw(),
                "LTW stays LTW");
        check(UltraOptimizationDetector.describe(Renderers.LTW_RENDERER).sodiumValidated,
                "LTW remains the only validated Sodium path");
        check(UltraOptimizationDetector.describe(Renderers.ZINK_RENDERER).family == RendererFamily.ZINK,
                "Zink stays Zink");
        check("4.6".equals(UltraOptimizationDetector.describe(Renderers.ZINK_RENDERER).desktopGlVersion()),
                "Zink advertises MESA_GL_VERSION_OVERRIDE=4.6");
        check("4.3".equals(UltraOptimizationDetector.describe(Renderers.LEGACYZINK_RENDERER).desktopGlVersion()),
                "Legacy Zink advertises 4.3");

        // No renderer the launcher can persist may fall through as unidentified.
        String[] knownTags = {
                Renderers.LTW_RENDERER, Renderers.GL4ES_RENDERER, Renderers.NGGL4ES_RENDERER,
                Renderers.MOBILEGLUES_RENDERER, Renderers.SFPEW_RENDERER, Renderers.ZINK_RENDERER,
                Renderers.LEGACYZINK_RENDERER, Renderers.FREEDRENO_RENDERER, Renderers.MESA_RENDERER,
                Renderers.MESA_RENDERER_EXT
        };
        for (String tag : knownTags) {
            check(UltraOptimizationDetector.describe(tag).identified, "Identified: " + tag);
        }

        RendererCapability unknown = UltraOptimizationDetector.describe("opengles_future_wrapper");
        check(!unknown.identified, "Unknown tag is not identified");
        check(!unknown.isKrypton(), "Unknown tag is never reported as Krypton");
        check(!unknown.isGl4es(), "Unknown tag is never reported as GL4ES");
        check(!UltraOptimizationDetector.describe("").identified, "Empty renderer is unknown");
        check(!UltraOptimizationDetector.describe(null).identified, "Null renderer is unknown");
        check("Unknown".equals(UltraOptimizationDetector.describe(null).displayName),
                "Null renderer has a neutral display name");

        // Sodium policy per renderer.
        check(!UltraOptimizationDetector.sodiumPolicy(Renderers.NGGL4ES_RENDERER, "1.21.11").allowed,
                "Sodium withheld on Krypton");
        check(containsText(UltraOptimizationDetector.sodiumPolicy(Renderers.NGGL4ES_RENDERER, "1.21.11").reason,
                "Krypton"), "Krypton skip explains itself");
        check(containsText(UltraOptimizationDetector.sodiumPolicy(Renderers.NGGL4ES_RENDERER, "1.21.11").reason,
                "3.1"), "Krypton skip quotes the advertised desktop GL version");
        check(containsText(UltraOptimizationDetector.sodiumPolicy(Renderers.NGGL4ES_RENDERER, "1.21.11").reason,
                "no renderer change is made"), "Krypton skip promises no renderer switch");
        check(UltraOptimizationDetector.sodiumPolicy(Renderers.NGGL4ES_RENDERER, "1.21.11")
                .capability.family == RendererFamily.KRYPTON,
                "The policy carries the Krypton capability to its callers");
        check(containsText(UltraOptimizationDetector.sodiumPolicy("opengles2", "1.20.1").reason,
                "blocked by PojavLauncher"), "GL4ES keeps its launcher-block reason");
        check(containsText(UltraOptimizationDetector.sodiumPolicy("opengles2_4", "1.20.1").reason,
                "GL4ES"), "Legacy GL4ES alias gets the GL4ES reason, not a vague one");
        check(UltraOptimizationDetector.sodiumPolicy(Renderers.LTW_RENDERER, "1.20.1").allowed,
                "Sodium remains allowed on LTW");
        check(!UltraOptimizationDetector.sodiumPolicy(Renderers.LTW_RENDERER, "1.16.5").allowed,
                "Sodium stays withheld on LTW below 1.17");
        check(!UltraOptimizationDetector.sodiumPolicy(Renderers.ZINK_RENDERER, "1.21.1").allowed,
                "Sodium still withheld on Zink despite its 4.6 claim");
        for (String tag : knownTags) {
            if (Renderers.LTW_RENDERER.equals(tag)) continue;
            check(!UltraOptimizationDetector.sodiumPolicy(tag, "1.21.1").allowed,
                    "Only LTW may receive Sodium: " + tag);
        }
        // The conservative fallback for an unidentifiable renderer must also withhold it.
        check(!UltraOptimizationDetector.sodiumPolicy("opengles_future_wrapper", "1.21.1").allowed,
                "Unknown renderer cannot earn Sodium");
        check(containsText(UltraOptimizationDetector.sodiumPolicy("opengles_future_wrapper", "1.21.1").reason,
                "not a renderer identifier this"), "Unknown renderer reason says it is unrecognised");

        // The capability line is what the screen shows; it must be renderer-specific and honest.
        String kryptonNote = UltraOptimizationDetector.rendererNote(Renderers.NGGL4ES_RENDERER);
        check(containsText(kryptonNote, "Krypton"), "Krypton capability line names the wrapper");
        check(containsText(kryptonNote, "never as LTW or Zink"), "Krypton line records the non-conflation");
        check(containsText(kryptonNote, "never changed"), "Krypton line promises no renderer change");
        check(!containsText(kryptonNote, "GL4ES is blocked"),
                "Krypton is not described with the standard GL4ES rule");
    }

    // ----------------------------------------------------- Krypton end-to-end resolution

    /** The user's reported test configuration: 1.21.11, Fabric, Java 21, Krypton, 2048 MB. */
    private static void testKryptonTestedDeviceProfile() {
        FakeSource source = fullSource("1.21.11", "fabric", Arrays.asList("fabric"));
        Environment environment = environment("1.21.11", Loader.FABRIC, Renderers.NGGL4ES_RENDERER,
                21, 2048, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);

        check(resolution.errors.isEmpty(), "Krypton never causes a refusal");
        check(resolution.canInstall, "Krypton resolution still offers mods at 2048 MB");
        check(!hasInstallable(resolution, "sodium"), "Sodium withheld on Krypton");
        PlanItem sodium = findItem(resolution, "sodium");
        check(sodium != null && sodium.state == ItemState.SKIPPED, "Sodium is a skipped row, not hidden");
        check(sodium != null && containsText(sodium.reason, "Krypton"),
                "The skipped row explains the Krypton limitation");
        for (PlanItem item : resolution.installableItems()) {
            check(!"sodium".equalsIgnoreCase(item.slug), "No renderer-dependent mod is offered");
        }
        check(resolution.budgetCount == 2, "2048 MB keeps the two-mod cost budget");
        check(resolution.selectedCount <= resolution.budgetCount, "Budget is not exceeded on Krypton");
        check(containsText(resolution.rendererNote, "Krypton"), "Renderer capability is reported");
        check(containsAny(resolution.warnings, "Krypton family"), "Krypton gets its own warning line");
        check(containsText(resolution.sodiumPolicyReason, "Krypton"), "Sodium policy names Krypton");
        check(!containsText(resolution.summary, "renderer"),
                "The summary does not blame the renderer for the plan");
    }

    /** Same renderer, enough RAM for the whole non-rendering set. */
    private static void testKryptonOffersNonRendererMods() {
        FakeSource source = fullSource("1.21.11", "fabric", Arrays.asList("fabric"));
        Environment environment = environment("1.21.11", Loader.FABRIC, Renderers.NGGL4ES_RENDERER,
                21, 8192, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);

        check(resolution.errors.isEmpty(), "No fatal error with a capable renderer budget");
        check(resolution.budgetCount == 5, "8 GB fixture uses the five-mod budget");
        check(hasInstallable(resolution, "ferrite-core"), "FerriteCore offered on Krypton");
        check(hasInstallable(resolution, "lithium"), "Lithium offered on Krypton");
        check(hasInstallable(resolution, "modernfix"), "ModernFix offered on Krypton");
        check(hasInstallable(resolution, "immediatelyfast"), "ImmediatelyFast offered on Krypton");
        check(!hasInstallable(resolution, "sodium"), "Sodium still withheld on Krypton");
        int cullingSelected = 0;
        for (PlanItem item : resolution.installableItems()) {
            if ("entityculling".equalsIgnoreCase(item.slug) || "moreculling".equalsIgnoreCase(item.slug)) {
                cullingSelected++;
            }
        }
        check(cullingSelected == 1, "Exactly one culling mod is selected (exclusive group)");
        check(!hasInstallable(resolution, "memoryleakfix"), "Memory Leak Fix skipped on 1.21.11");

        // Only one candidate in the whole catalogue is renderer-gated. If that ever grows, the
        // Krypton policy has to be re-examined for each new renderer-dependent mod, not assumed.
        int rendererGated = 0;
        for (Candidate candidate : UltraOptimizationModels.candidates()) {
            if (candidate.sodium) rendererGated++;
        }
        check(rendererGated == 1, "Sodium is the only renderer-gated candidate");

        for (PlanItem item : resolution.items) {
            if (item.state != ItemState.SKIPPED) continue;
            if ("sodium".equalsIgnoreCase(item.slug)) continue;
            check(!containsText(item.reason, "krypton"),
                    "Nothing is refused merely because the renderer is Krypton: " + item.slug);
            check(!containsText(item.reason, "renderer"),
                    "No renderer blame is attached to a non-rendering mod: " + item.slug);
        }
    }

    /** Required dependencies still resolve on Krypton, exactly as on any other renderer. */
    private static void testKryptonResolvesRequiredDependency() {
        FakeSource source = fullSource("1.21.11", "fabric", Arrays.asList("fabric"));
        addDependencyProject(source, "required-api", "required-api-1.21.11", "1.21.11", "fabric");
        VersionInfo lithium = source.versionById.get("ver-lithium-1.21.11");
        lithium.dependencies = Collections.singletonList(dependency("id-required-api",
                "required-api-1.21.11", "required"));
        Environment environment = environment("1.21.11", Loader.FABRIC, Renderers.NGGL4ES_RENDERER,
                21, 8192, false);
        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);

        check(resolution.errors.isEmpty(), "Krypton dependency scenario is not refused");
        check(hasInstallable(resolution, "lithium"), "Lithium offered on Krypton");
        check(hasInstallable(resolution, "required-api"),
                "Required dependency resolves on Krypton");
        PlanItem dependencyItem = findItem(resolution, "required-api");
        check(dependencyItem != null && dependencyItem.dependency,
                "The resolved project is marked as a dependency, not a candidate");
        check(!hasInstallable(resolution, "sodium"), "Sodium withheld in the dependency scenario");
    }

    /** A Krypton installation that already has the reported set must be a no-op, never a refusal. */
    private static void testKryptonInstalledSetIsNoOp() {
        FakeSource source = fullSource("1.21.11", "fabric", Arrays.asList("fabric"));
        Environment environment = environment("1.21.11", Loader.FABRIC, Renderers.NGGL4ES_RENDERER,
                21, 8192, false);
        String[] installed = {
                "fabric-api-0.115.0.jar", "cloth-config-16.0.jar",
                "ferritecore-7.0.0.jar", "lithium-0.15.4.jar",
                "entityculling-1.7.5.jar", "moreculling-1.24.10.jar",
                "immediatelyfast-1.5.7.jar"
        };
        for (String fileName : installed) environment.installedFileNames.add(fileName);
        environment.installedProjectIds.addAll(Arrays.asList("ferrite-core", "lithium",
                "entityculling", "moreculling", "immediatelyfast", "modernfix"));

        Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
        check(resolution.errors.isEmpty(), "Already-optimized Krypton install is not refused");
        check(!resolution.canInstall, "Nothing new is offered when everything is present");
        check(!hasInstallable(resolution, "sodium"), "Sodium is not pushed onto a working install");
        for (String slug : new String[]{"ferrite-core", "lithium", "entityculling",
                "moreculling", "immediatelyfast", "modernfix"}) {
            PlanItem item = findItem(resolution, slug);
            check(item != null && item.state == ItemState.INSTALLED, "Recognised as installed: " + slug);
        }
        check(!containsAny(resolution.warnings, "shared game directory"),
                "No unrelated warning is invented");
    }

    /**
     * The per-installation state that drives Restore must record the Krypton installation exactly
     * like any other, own only its own files, and leave Ultra-Lite's preference untouched.
     */
    private static void testStateOwnershipAfterKryptonPlan() {
        try {
            stateOwnershipBody();
        } catch (Exception e) {
            // The scenarios run inside a Runnable, so checked failures surface as assertions.
            throw new AssertionError("State fixture failed: " + e, e);
        }
    }

    private static void stateOwnershipBody() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "uo-krypton-" + System.nanoTime());
        File gameDirectory = new File(root, "instance");
        // noinspection ResultOfMethodCallIgnored
        gameDirectory.mkdirs();
        try {
            FakeSource source = fullSource("1.21.11", "fabric", Arrays.asList("fabric"));
            Environment environment = environment("1.21.11", Loader.FABRIC,
                    Renderers.NGGL4ES_RENDERER, 21, 8192, false);
            environment.gameDirectory = gameDirectory.getAbsolutePath();
            Resolution resolution = UltraOptimizationResolver.resolve(environment, source, null);
            check(!resolution.installableItems().isEmpty(), "Plan has something to install");

            UltraOptimizationState state = new UltraOptimizationState();
            state.minecraftVersion = environment.minecraftVersion;
            state.loader = environment.loader.displayName();
            state.renderer = environment.renderer;
            for (PlanItem item : resolution.installableItems()) {
                UltraOptimizationState.Entry entry = new UltraOptimizationState.Entry();
                entry.projectId = item.projectId;
                entry.slug = item.slug;
                entry.fileName = item.fileName;
                entry.modsDirectory = "mods";
                state.entries.add(entry);
            }
            check(state.entries.size() == resolution.installableItems().size(),
                    "State records exactly the planned files");
            for (UltraOptimizationState.Entry entry : state.entries) {
                check(!"sodium".equalsIgnoreCase(entry.slug), "State never owns a renderer mod on Krypton");
            }
            state.save(gameDirectory);
            check(UltraOptimizationState.hasState(gameDirectory), "State lives inside the installation");

            UltraOptimizationState reloaded = UltraOptimizationState.load(gameDirectory);
            check(reloaded != null, "State reloads");
            check(reloaded.installedCount() == state.entries.size(), "Ownership survives reload");
            check(Renderers.NGGL4ES_RENDERER.equals(reloaded.renderer),
                    "The Krypton tag is stored verbatim, not normalised to GL4ES");
            check(reloaded.findEntryByFileName(state.entries.get(0).fileName) != null,
                    "Restore can find a file it owns");
            check(!reloaded.containsProject("sodium"),
                    "Restore owns nothing it did not install");

            File stray = new File(UltraOptimizationState.directory(gameDirectory), "keep.txt");
            java.io.Writer strayWriter = new java.io.FileWriter(stray);
            strayWriter.close();
            reloaded.delete(gameDirectory);
            check(!UltraOptimizationState.hasState(gameDirectory), "State file removed on delete");
            check(stray.isFile(), "An unrelated file in the state directory is kept");
            check(UltraOptimizationState.directory(gameDirectory).isDirectory(),
                    "A non-empty state directory is kept");
            check(!new File(gameDirectory, "performanceProfile").exists(),
                    "Ultra-Lite preference is not part of the installation state");
        } finally {
            UltraOptimizationState.deleteRecursively(root);
        }
        check(!root.exists(), "Test cleanup removed its temporary directory");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package net.kdt.pojavlaunch.ultraoptimization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Loader;

/**
 * Pure detection helpers for the selected installation.
 *
 * Renderer rules are deliberately conservative and derived from this repository's launcher code:
 * GameRunner blocks Sodium on GL4ES for modern Minecraft and would otherwise auto-switch to LTW;
 * LTW is labelled 1.17+ only. Ultra-Optimization never performs or relies on such a switch.
 */
public final class UltraOptimizationDetector {
    private UltraOptimizationDetector() {}

    public static final String GL4ES_RENDERER = "opengles2";
    public static final String LTW_RENDERER = "opengles3_ltw";

    private static final Pattern MC_VERSION = Pattern.compile(
            "(?:^|[^0-9])(1\\.\\d{1,2}(?:\\.\\d{1,2})?|\\d{2}w\\d{2}[a-z]?)(?:$|[^0-9])");
    private static final Pattern SNAPSHOT = Pattern.compile("(\\d{2})w(\\d{2})([a-z]?)");

    public static final class RendererPolicy {
        public final boolean allowed;
        public final String reason;
        public final String renderer;

        public RendererPolicy(boolean allowed, String renderer, String reason) {
            this.allowed = allowed;
            this.renderer = renderer;
            this.reason = reason;
        }
    }

    public static String detectMinecraftVersion(String versionId, String inheritsFrom, String jsonId) {
        String fromParent = extractMinecraftVersion(inheritsFrom);
        if (fromParent != null) return fromParent;
        String fromJson = extractMinecraftVersion(jsonId);
        if (fromJson != null) return fromJson;
        String fromInstance = extractMinecraftVersion(versionId);
        if (fromInstance != null) return fromInstance;
        return versionId == null ? "" : versionId;
    }

    public static String extractMinecraftVersion(String value) {
        if (value == null) return null;
        Matcher matcher = MC_VERSION.matcher(value);
        if (matcher.find()) return matcher.group(1);
        return null;
    }

    public static boolean isVersionAlias(String value) {
        if (value == null || value.trim().isEmpty()) return true;
        String lower = value.toLowerCase(Locale.US);
        if ("latest_release".equals(lower) || "latest_snapshot".equals(lower)) return true;
        return extractMinecraftVersion(value) == null;
    }

    public static Loader detectLoader(String versionId, String mainClass, List<String> libraryCoordinates) {
        String id = safeLower(versionId);
        String main = safeLower(mainClass);
        List<String> libs = new ArrayList<>();
        if (libraryCoordinates != null) {
            for (String library : libraryCoordinates) libs.add(safeLower(library));
        }

        boolean quilt = id.contains("quilt") || main.contains("quiltmc")
                || containsAny(libs, "org.quiltmc", "quilt-loader");
        boolean fabric = id.contains("fabric") || main.contains("fabricmc")
                || containsAny(libs, "net.fabricmc", "fabric-loader");
        boolean neoforge = id.contains("neoforge") || id.contains("neo-forge")
                || containsAny(libs, "net.neoforged", "neoforge")
                || main.contains("neoforged");
        boolean forge = id.contains("forge") || main.contains("net.minecraftforge")
                || containsAny(libs, "net.minecraftforge", "minecraftforge", "fmlloader");

        if (quilt) return Loader.QUILT;
        if (fabric) return Loader.FABRIC;
        if (neoforge) return Loader.NEOFORGE;
        if (forge) return Loader.FORGE;
        return Loader.VANILLA;
    }

    public static boolean isModernMinecraft(String minecraftVersion) {
        if (isVersionAlias(minecraftVersion)) return false;
        Matcher snapshot = SNAPSHOT.matcher(minecraftVersion.toLowerCase(Locale.US));
        if (snapshot.matches()) {
            int year = Integer.parseInt(snapshot.group(1));
            return year >= 21;
        }
        return compareMinecraft(minecraftVersion, "1.17") >= 0;
    }

    public static boolean isAtLeast(String version, String minimum) {
        Matcher snapshot = SNAPSHOT.matcher(safeLower(version));
        if (snapshot.matches()) {
            int year = Integer.parseInt(snapshot.group(1));
            if (minimum.startsWith("1.17")) return year >= 21;
            if (minimum.startsWith("1.19")) return year >= 22;
            if (minimum.startsWith("1.20.5")) return year >= 24;
            return true;
        }
        return compareMinecraft(version, minimum) >= 0;
    }

    public static int minimumJavaMajor(String minecraftVersion) {
        Matcher snapshot = SNAPSHOT.matcher(safeLower(minecraftVersion));
        if (snapshot.matches()) {
            int year = Integer.parseInt(snapshot.group(1));
            if (year <= 20) return 8;
            if (year == 21) return 16;
            if (year <= 23) return 17;
            return 21;
        }
        if (isVersionAlias(minecraftVersion)) return 0;
        if (compareMinecraft(minecraftVersion, "1.17") < 0) return 8;
        if (compareMinecraft(minecraftVersion, "1.18") < 0) return 16;
        if (compareMinecraft(minecraftVersion, "1.20.5") < 0) return 17;
        return 21;
    }

    public static int compareMinecraft(String left, String right) {
        int[] a = parseRelease(left);
        int[] b = parseRelease(right);
        if (a == null || b == null) return 0;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
        }
        return 0;
    }

    /**
     * Sodium is installed only on LTW with Minecraft 1.17+. Every other renderer is skipped
     * with an explicit reason. This avoids triggering GameRunner's automatic renderer switch and
     * avoids assuming unvalidated renderer/Sodium-version combinations.
     */
    public static RendererPolicy sodiumPolicy(String renderer, String minecraftVersion) {
        String tag = safeLower(renderer);
        if (tag.isEmpty()) {
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: active renderer is unknown.");
        }
        if (!isModernMinecraft(minecraftVersion)) {
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: this Minecraft version predates the validated 1.17+ renderer path.");
        }
        if (GL4ES_RENDERER.equals(tag)) {
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: GL4ES is blocked by PojavLauncher for Sodium on 1.17+ and would trigger an automatic LTW renderer switch.");
        }
        if (LTW_RENDERER.equals(tag)) {
            return new RendererPolicy(true, renderer,
                    "Sodium allowed: LTW is PojavLauncher's validated 1.17+ renderer path; no renderer change is made.");
        }
        return new RendererPolicy(false, renderer,
                "Sodium skipped: renderer " + renderer + " has no validated Sodium compatibility in this implementation.");
    }

    public static String rendererDisplayName(String renderer) {
        String tag = safeLower(renderer);
        if (GL4ES_RENDERER.equals(tag)) return "GL4ES (OpenGL ES 2)";
        if (LTW_RENDERER.equals(tag)) return "LTW (OpenGL ES 3, 1.17+)";
        if ("vulkan_zink".equals(tag)) return "Zink (Vulkan)";
        if ("vulkan_legacyzink".equals(tag)) return "Legacy Zink (Vulkan)";
        if ("opengles_mobileglues".equals(tag)) return "MobileGlues (OpenGL ES 3)";
        if ("opengles_sfpew".equals(tag)) return "SFPEW / MobileGlues";
        if ("opengles_nggl4es".equals(tag)) return "Krypton Wrapper (OpenGL ES 3.2)";
        if ("mesa_desktop".equals(tag)) return "Mesa (DRM)";
        if ("mesa_desktop_ext".equals(tag)) return "Mesa (DRM, external)";
        if ("freedreno_kgsl".equals(tag)) return "Freedreno (KGSL)";
        return renderer == null || renderer.isEmpty() ? "Unknown" : renderer;
    }

    public static boolean isSodiumForkToken(String token) {
        String lower = safeLower(token);
        return lower.contains("embeddium") || lower.contains("rubidium") || lower.contains("angelica");
    }

    private static int[] parseRelease(String version) {
        if (version == null) return null;
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) return null;
        int[] parsed = new int[]{1, 0, 0};
        try {
            parsed[1] = Integer.parseInt(parts[1]);
            if (parts.length > 2) parsed[2] = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return parsed;
    }

    private static boolean containsAny(List<String> values, String... needles) {
        for (String value : values) {
            for (String needle : needles) {
                if (value.contains(needle)) return true;
            }
        }
        return false;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    public static List<String> emptyIfNull(List<String> values) {
        return values == null ? Collections.<String>emptyList() : values;
    }
}

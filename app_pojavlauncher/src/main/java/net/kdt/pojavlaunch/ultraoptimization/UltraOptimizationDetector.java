package net.kdt.pojavlaunch.ultraoptimization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kdt.pojavlaunch.game.renderer.def.Renderers;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Loader;

/**
 * Pure detection helpers for the selected installation.
 *
 * Renderer rules are deliberately conservative and derived from this repository's launcher code:
 * {@code GameRunner} blocks Sodium on GL4ES for modern Minecraft and would otherwise auto-switch
 * to LTW; LTW is labelled 1.17+ only; Krypton Wrapper ({@code NGGL4ESRenderSpec}) is a modified
 * GL4ES backend that pins desktop OpenGL 3.1 over OpenGL ES 3 and is not covered by either of
 * those launcher rules. Ultra-Optimization never performs or relies on such a switch.
 *
 * Renderer identifiers are taken from {@link Renderers}, so the classification cannot drift away
 * from the tags the launcher actually persists in its preferences.
 */
public final class UltraOptimizationDetector {
    private UltraOptimizationDetector() {}

    /** Renderer tags, aliased from the launcher so both sides always agree. */
    public static final String GL4ES_RENDERER = Renderers.GL4ES_RENDERER;
    public static final String LTW_RENDERER = Renderers.LTW_RENDERER;
    /** Krypton Wrapper (NG-GL4ES): a modified GL4ES backend with its own tag and native library. */
    public static final String NGGL4ES_RENDERER = Renderers.NGGL4ES_RENDERER;
    /**
     * Legacy values accepted by {@code GameRenderer.getKnownRenderer} "For compatibility", which
     * maps both of them onto the standard GL4ES spec. They are therefore GL4ES, not Krypton.
     */
    public static final String GL4ES_RENDERER_LEGACY_4 = "opengles2_4";
    public static final String GL4ES_RENDERER_LEGACY_5 = "opengles2_5";

    private static final Pattern MC_VERSION = Pattern.compile(
            "(?:^|[^0-9])(1\\.\\d{1,2}(?:\\.\\d{1,2})?|\\d{2}w\\d{2}[a-z]?)(?:$|[^0-9])");
    private static final Pattern SNAPSHOT = Pattern.compile("(\\d{2})w(\\d{2})([a-z]?)");

    /**
     * Renderer families as this launcher actually implements them. A family groups specs that
     * share the same native library and the same environment setup, never the marketing name.
     */
    public enum RendererFamily {
        /** OpenLTW: the only renderer the launcher documents as the modern (1.17+) path. */
        LTW,
        /** Plain gl4es ({@code libgl4es_114.so}); the launcher refuses Sodium on 1.17+. */
        GL4ES,
        /**
         * Krypton Wrapper / NG-GL4ES ({@code libng_gl4es.so}): a modified GL4ES backend. It is a
         * sibling spec, not a subclass of the GL4ES spec, so the launcher's GL4ES rules do not
         * apply to it - neither the Sodium refusal nor the version cutoff.
         */
        KRYPTON,
        /** MobileGlues ({@code libmobileglues.so}). */
        MOBILEGLUES,
        /** SimpleFPEWrapper over MobileGlues ({@code libSimpleFPEWrapper.so}). */
        SFPEW,
        /** Mesa Zink over Vulkan, in either the bundled or the external/legacy form. */
        ZINK,
        /** Mesa desktop GL through DRM. */
        MESA,
        /** Mesa with the kgsl (Adreno) driver. */
        FREEDRENO,
        /** Anything this launcher does not know: no capability is assumed, in either direction. */
        UNKNOWN
    }

    /**
     * What a renderer tag is allowed to imply. The numbers are not measured here: they are the
     * values the corresponding {@code RenderSpec} writes into the child environment, which is the
     * strongest capability statement available without launching a game.
     */
    public static final class RendererCapability {
        public final String tag;
        public final RendererFamily family;
        public final String displayName;
        /** Major OpenGL ES version the spec asks MojoExec to set up; 0 when not applicable. */
        public final int esMajor;
        /** Desktop OpenGL version the spec pins for the game; 0 when it pins none. */
        public final int desktopGlMajor;
        public final int desktopGlMinor;
        /** False for an empty or unrecognised tag, so callers can tell "unknown" from "GL4ES". */
        public final boolean identified;
        /** True when {@code GameRunner} special-cases this renderer for Sodium and may switch it. */
        public final boolean launcherGuardsSodium;
        /** True when this build has a validated Sodium path for the renderer. */
        public final boolean sodiumValidated;
        public final String note;

        public RendererCapability(String tag, RendererFamily family, String displayName, int esMajor,
                                  int desktopGlMajor, int desktopGlMinor, boolean launcherGuardsSodium,
                                  boolean sodiumValidated, String note) {
            this.tag = tag;
            this.family = family == null ? RendererFamily.UNKNOWN : family;
            this.displayName = displayName;
            this.esMajor = esMajor;
            this.desktopGlMajor = desktopGlMajor;
            this.desktopGlMinor = desktopGlMinor;
            this.identified = this.family != RendererFamily.UNKNOWN;
            this.launcherGuardsSodium = launcherGuardsSodium;
            this.sodiumValidated = sodiumValidated;
            this.note = note;
        }

        /** "3.1", "2.1" ... or "not advertised" - never a guessed desktop OpenGL version. */
        public String desktopGlVersion() {
            return desktopGlMajor <= 0 ? "not advertised" : desktopGlMajor + "." + desktopGlMinor;
        }

        public boolean isKrypton() {
            return family == RendererFamily.KRYPTON;
        }

        public boolean isGl4es() {
            return family == RendererFamily.GL4ES;
        }

        public boolean isLtw() {
            return family == RendererFamily.LTW;
        }
    }

    public static final class RendererPolicy {
        public final boolean allowed;
        public final String reason;
        public final String renderer;
        public final RendererCapability capability;

        public RendererPolicy(boolean allowed, String renderer, String reason) {
            this(allowed, renderer, reason, describe(renderer));
        }

        RendererPolicy(boolean allowed, String renderer, String reason,
                       RendererCapability capability) {
            this.allowed = allowed;
            this.renderer = renderer;
            this.reason = reason;
            this.capability = capability;
        }
    }

    /**
     * Map a persisted renderer tag onto the family that this launcher build implements. Unknown
     * tags stay unknown: an unrecognised renderer is never reported as Krypton, and never treated
     * as plain GL4ES either.
     */
    public static RendererCapability describe(String renderer) {
        String tag = safeLower(renderer);
        String raw = renderer == null || renderer.isEmpty() ? "Unknown" : renderer;
        if (tag.isEmpty()) {
            return new RendererCapability(renderer, RendererFamily.UNKNOWN, "Unknown", 0, 0, 0,
                    false, false, "No renderer is selected for this installation, so no rendering "
                    + "capability is assumed and no renderer is changed.");
        }
        if (LTW_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.LTW,
                    "LTW (OpenGL ES 3) - 1.17+ only", 3, 0, 0, false, true,
                    "OpenLTW (libltw.so) is the renderer this launcher documents for modern"
                    + " Minecraft; it is the only path with a validated Sodium policy here.");
        }
        if (GL4ES_RENDERER.equals(tag) || GL4ES_RENDERER_LEGACY_4.equals(tag)
                || GL4ES_RENDERER_LEGACY_5.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.GL4ES,
                    "GL4ES (OpenGL ES 2) - 1.21.4 and lower", 2, 2, 1, true, false,
                    "gl4es (libgl4es_114.so) pins no LIBGL_GL, so its default desktop profile (2.1)"
                    + " applies; GameRunner blocks Sodium with this spec from 1.17 on and switches"
                    + " the renderer instead.");
        }
        if (NGGL4ES_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.KRYPTON,
                    "Krypton Wrapper (NG-GL4ES, labelled OpenGL ES 3.2) - 26.2 and lower",
                    3, 3, 1, false, false,
                    "Krypton Wrapper is a modified GL4ES backend (libng_gl4es.so): GLESRenderSpec"
                    + ".NGGL4ESRenderSpec sets LIBGL_GL=31, LIBGL_ES=3 and LIBGL_NORMALIZE=1, so the"
                    + " game sees emulated desktop OpenGL 3.1 on OpenGL ES 3. It is not a subclass of"
                    + " the GL4ES spec, so GameRunner applies neither its Sodium guard nor its"
                    + " 1.21.4-and-lower cutoff to it - and it does not endorse a desktop-GL 4.x"
                    + " path either.");
        }
        if (Renderers.MOBILEGLUES_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.MOBILEGLUES,
                    "MobileGlues (OpenGL ES) - 1.17+ only", 3, 0, 0, false, false,
                    "MobileGlues (libmobileglues.so) exposes OpenGL ES 3; no desktop OpenGL version is"
                    + " pinned by its RenderSpec.");
        }
        if (Renderers.SFPEW_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.SFPEW,
                    "SFPEW/MobileGlues (OpenGL ES) - all versions", 3, 0, 0, false, false,
                    "libSimpleFPEWrapper.so wraps MobileGlues unless the Angelica mod provides its own"
                    + " function pointer table; no desktop OpenGL version is pinned.");
        }
        if (Renderers.ZINK_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.ZINK,
                    "Zink (Vulkan) - all versions", 0, 4, 6, false, false,
                    "Mesa zink pins MESA_GL_VERSION_OVERRIDE=4.6 (GLSL 460) over Vulkan, which is a"
                    + " different capability profile from the GLES wrappers.");
        }
        if (Renderers.LEGACYZINK_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.ZINK,
                    "Legacy Zink (Vulkan) - all versions", 0, 4, 3, false, false,
                    "The legacy zink plugin pins MESA_GL_VERSION_OVERRIDE=4.3 over Vulkan.");
        }
        if (Renderers.FREEDRENO_RENDERER.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.FREEDRENO,
                    "Freedreno (KGSL) - all versions", 0, 0, 0, false, false,
                    "Mesa with the kgsl driver; the RenderSpec only forces desktop OpenGL 3.3 on Adreno"
                    + " 5XX and lower, otherwise the driver default applies.");
        }
        if (Renderers.MESA_RENDERER.equals(tag) || Renderers.MESA_RENDERER_EXT.equals(tag)) {
            return new RendererCapability(renderer, RendererFamily.MESA,
                    Renderers.MESA_RENDERER_EXT.equals(tag)
                            ? "Mesa (DRM, external) - all versions" : "Mesa (DRM) - all versions",
                    0, 0, 0, false, false,
                    "Mesa desktop GL through DRM; the version depends on the driver in use, so none is"
                    + " assumed here.");
        }
        return new RendererCapability(renderer, RendererFamily.UNKNOWN, raw, 0, 0, 0, false, false,
                "Renderer \"" + renderer + "\" is not one of this launcher's known renderer"
                + " identifiers; no capability is assumed and no renderer is changed.");
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
     * Sodium rewrites the renderer, so it is installed only on LTW with Minecraft 1.17+ - the one
     * path this launcher documents for modern Minecraft. Every other renderer is skipped with an
     * explicit reason, including Krypton Wrapper: the wrapper is recognised and classified (see
     * {@link #describe(String)}), but pinning desktop OpenGL 3.1 is not the OpenGL 3.2+ core
     * feature set Sodium's backend expects, and neither this repository nor the launcher's own
     * checks establish that combination. Skipping is a capability decision, never a refusal to
     * optimise: the non-rendering mods stay eligible.
     */
    public static RendererPolicy sodiumPolicy(String renderer, String minecraftVersion) {
        RendererCapability capability = describe(renderer);
        if (!capability.identified) {
            if (safeLower(renderer).isEmpty()) {
                return new RendererPolicy(false, renderer,
                        "Sodium skipped: active renderer is unknown.", capability);
            }
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: renderer " + renderer + " is not a renderer identifier this"
                    + " launcher build defines, so no desktop-GL capability is assumed and no"
                    + " renderer change is made.", capability);
        }
        if (!isModernMinecraft(minecraftVersion)) {
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: this Minecraft version predates the validated 1.17+ renderer path.",
                    capability);
        }
        if (capability.isLtw()) {
            return new RendererPolicy(true, renderer,
                    "Sodium allowed: LTW is PojavLauncher's validated 1.17+ renderer path; no renderer change is made.",
                    capability);
        }
        if (capability.isGl4es()) {
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: GL4ES is blocked by PojavLauncher for Sodium on 1.17+ and would trigger an automatic LTW renderer switch.",
                    capability);
        }
        if (capability.isKrypton()) {
            return new RendererPolicy(false, renderer,
                    "Sodium skipped: Krypton Wrapper (NG-GL4ES) advertises emulated desktop OpenGL 3.1"
                    + " over OpenGL ES 3, which is below the desktop-GL core path Sodium needs, and"
                    + " this launcher neither guards nor validates that combination; no renderer"
                    + " change is made. Non-rendering optimization mods are still offered.",
                    capability);
        }
        return new RendererPolicy(false, renderer,
                "Sodium skipped: " + capability.displayName + (capability.desktopGlMajor > 0
                        ? " advertises desktop OpenGL " + capability.desktopGlVersion()
                        : " pins no desktop OpenGL version at all")
                + " and has no validated Sodium path in this implementation; no renderer change"
                + " is made.", capability);
    }

    /**
     * One-line, non-fatal statement about what the selected renderer means for mod selection. The
     * screen shows it next to the plan so a user can see why a renderer mod was not offered.
     */
    public static String rendererNote(String renderer) {
        RendererCapability capability = describe(renderer);
        StringBuilder builder = new StringBuilder();
        builder.append(capability.displayName);
        if (capability.identified) {
            builder.append(" - OpenGL ES ");
            builder.append(capability.esMajor > 0 ? String.valueOf(capability.esMajor) : "n/a");
            builder.append(", desktop OpenGL ");
            builder.append(capability.desktopGlVersion());
        }
        builder.append(". ").append(capability.note);
        if (capability.family == RendererFamily.KRYPTON) {
            builder.append(" Krypton is reported to the resolver as its own family, never as LTW or Zink,")
                    .append(" and the launcher's GL4ES version cutoff is deliberately not applied to it.")
                    .append(" The selected renderer is never changed by Ultra-Optimization.");
        }
        return builder.toString();
    }

    public static String rendererDisplayName(String renderer) {
        return describe(renderer).displayName;
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

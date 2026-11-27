# Ultra-Optimization implementation plan

**Branch:** `arena/7535fc36-pojavlauncher`  
**Reviewed HEAD:** `446d4a62f886c52b4321c2cb4198128fbc5ce33f`  
**Date:** 2026-11-26

## 1. Scope and hard boundary

Ultra-Optimization is a new, independent, per-Minecraft-installation feature. It must be
combinable with the existing global launcher optimization, Ultra-Lite, in all four states:

| Ultra-Lite | Ultra-Optimization | Result |
| --- | --- | --- |
| OFF | OFF | Normal launcher |
| ON | OFF | Lightweight launcher |
| OFF | ON | Minecraft optimized through compatible mods |
| ON | ON | Both systems simultaneously |

The existing Ultra-Lite implementation is preserved byte-for-byte:

- `prefs/PerformanceProfile.java`
- `res/values/perf_profiles.xml`
- `res/xml/pref_misc.xml`
- `prefs/screens/LauncherPreferenceMiscellaneousFragment.java`
- `LauncherActivity.java`, `Downloader.java`, `IconCacheJanitor.java`,
  `ModIconCache.java`, `JREUtils.java`, `LauncherPreferenceJavaFragment.java`,
  and `pref_java.xml`

No existing key, default, cache limit, thread count, progress interval, background-work
behavior, label, or Balanced / Ultra-Lite / Custom choice is changed.

## 2. Repository findings

- Main settings navigation is `LauncherActivity` → top-right sliders button →
  `LauncherPreferenceFragment` → `pref_main.xml`. Sub-screens use AndroidX preference
  `android:fragment` navigation.
- `Instances.loadSelectedInstance()` returns the active `Instance`. `versionId`,
  `renderer`, `selectedRuntime`, `sharedData`, and `getGameDirectory()` are available.
  A selected instance with `sharedData=true` uses one shared game directory and must be
  refused by Ultra-Optimization so another installation is not modified.
- Version metadata is stored in `Tools.DIR_HOME_VERSION/<versionId>/<versionId>.json`.
  Fabric, Quilt, Forge, and NeoForge can be identified from `id`, `inheritsFrom`,
  `mainClass`, and library coordinates. The base Minecraft version is obtained from
  `inheritsFrom` or by extracting `1.x[.x]` / snapshot identifiers from `versionId`.
- Mods live in `<gameDirectory>/mods`. Existing launcher code already treats that path
  as the per-installation mod directory.
- Active renderer comes from `Instance.getLaunchRenderer()`; it falls back to
  `LauncherPreferences.PREF_RENDERER`. Known tags are in `Renderers.java`.
- `GameRunner` explicitly blocks Sodium with GL4ES on Minecraft 1.17+ and would try to
  switch to LTW. It also switches LTW to GL4ES for compatibility contexts before 1.17.
  Ultra-Optimization must not trigger those automatic renderer changes.
- Java runtime can be read with `Tools.getSelectedRuntime(instance)` and
  `MultiRTUtils.forceReread(...).javaVersion`. The launcher may auto-select Java later;
  Ultra-Optimization only reports/skips on an explicitly incompatible selected runtime and
  never changes it.
- `api.modrinth.com/v2` is already used by the existing modpack API. It provides project
  metadata and version arrays with `game_versions`, `loaders`, `version_type`,
  `status`, `dependencies`, `files[].url`, `files[].filename`, `files[].size`, and
  `files[].hashes`. Its file downloads use HTTPS CDN URLs with SHA-512 and/or SHA-1.
- The app already has `INTERNET`, Gson, HTTPS networking, and a shared one-shot
  `PojavApplication.sExecutorService`. No new background service, polling loop, database,
  or dependency is needed.

The sandbox has no outbound access to `api.modrinth.com`, so live Modrinth API calls are
implemented for the Android app but cannot be exercised here. Compatibility resolution is
therefore verified with deterministic offline fixtures. No runtime result is claimed from
those fixtures.

## 3. Architecture

New package: `net.kdt.pojavlaunch.ultraoptimization`.

1. **Pure Java core** (no Android imports, usable by `javac` tests):
   - `UltraOptimizationModels`: environment, loader, candidate, Modrinth project/version,
     dependency, plan item, result, and progress data.
   - `UltraOptimizationDetector`: loader detection, Minecraft version extraction,
     minimum Java version, and conservative Sodium renderer rules.
   - `UltraOptimizationResolver`: candidate scoring, exact version/loader matching,
     abandoned/alpha/incompatible filtering, required dependency resolution, conflict
     resolution, installed-mod deduplication, and a cost-aware selection budget.
   - `ModrinthDataSource`: small interface allowing offline fixtures and the real client.

2. **Android integration**:
   - `ModrinthClient`: bounded HTTPS requests to the official API, identifiable
     User-Agent, HTTPS/CDN host allowlist, redirect limits, response-size caps, and
     streaming file downloads with digest verification.
   - `UltraOptimizationScanner`: reads only small mod metadata entries from local JARs
     (plus filename tokens as a conservative fallback); never loads a large mod database.
   - `UltraOptimizationState`: per-installation `state.json`, backup manifests, installed
     file records, and atomic writes.
   - `UltraOptimizationInstaller`: one-shot transaction; stages to `.part`, verifies hash
     and size, atomically moves unique filenames into `mods/`, rolls back on failure, and
     restores only recorded files with matching hashes.
   - `UltraOptimizationFragment`: dedicated AndroidX preference screen and UI.

3. **Resources / navigation**:
   - New `res/xml/pref_ultra_optimization.xml`.
   - New `res/values/ultra_optimization_strings.xml`.
   - One new top-level preference item in `pref_main.xml`, in its own
     **Optimization** category, with its own icon/summary and fragment class.
   - No changes to existing Ultra-Lite resources or defaults.

## 4. Compatibility policy

- Supported loaders: Fabric, Forge, NeoForge, Quilt. Vanilla is detected but optimized
  only through an explicit new-installation workflow; the existing Vanilla installation
  is never converted.
- A Modrinth version is accepted only when its `game_versions` contains the exact detected
  Minecraft version and its `loaders` satisfies the selected loader policy. For Quilt, a
  Fabric build is accepted only when the project itself declares Quilt support.
- Prefer `release`, then `beta`; skip `alpha`, non-listed/unknown status, archived or
  abandoned projects, versions without a primary JAR, versions without any hash, files
  with unsafe names, and files above the resource cap.
- Required dependencies are resolved recursively to compatible versions. Optional and
  embedded dependencies are not installed separately. `incompatible` dependencies and
  known overlapping mod families remove the affected candidate.
- Candidate cost/benefit scores are static, documented heuristics, not measured device
  performance. A memory-dependent cap prevents installing every available mod merely
  because it exists.
- Sodium is skipped unless the selected renderer and Minecraft version satisfy a
  source-derived allowlist. GL4ES/1.17+, LTW/pre-1.17, unknown renderers, Sodium forks,
  and unresolved renderer compatibility produce an explicit skip reason. Renderer and
  Java runtime are never changed.

## 5. Installation safety

- Only `Instances.loadSelectedInstance().getGameDirectory()` is used.
- Shared-data installations are rejected.
- No overwrite of an existing filename; unique `uo_...` filenames are used.
- Existing configuration files are never written.
- A recoverable backup/transaction manifest is written before any `mods/` change.
- Explicit confirmation is required before Install and before Restore.
- Downloaded files are staged, hash-verified, size-checked, then committed.
- Interrupted transactions are detected only when the feature screen is opened and can be
  rolled back; there is no startup scan or background service.
- Restore deletes only files recorded by the transaction and only when their current hash
  still matches. Unrelated mods and generated configs are preserved.

## 6. Verification and release

- Extend `scripts/verify_static.py` with protected Ultra-Lite hash checks, independence
  checks, and four-combination checks.
- Add a pure Java fixture test run with `javac --release 8` in CI. Scenarios cover
  multiple Minecraft versions and Fabric/Forge/NeoForge/Quilt/Vanilla, dependency
  failure, conflict handling, duplicate installation, Sodium/renderer gates, and all four
  Ultra-Lite combinations.
- Build `assembleNoruntimeDebug` and `assembleFullDebug` in GitHub Actions with the
  proven JDK 17 / Gradle 8.14.5 / official SDK setup.
- Verify ZIP integrity, `aapt dump badging`, `apksigner verify`, `zipalign -c -v 4`,
  sizes, SHA-256, ABIs, and package metadata.
- Publish only verified assets in a new unique tag and **PojavLauncher Renewed** release
  (a `push` run only verifies and builds; publication is an explicit `workflow_dispatch`
  with the `publish` input, so no branch push can create a release by accident):
  `PojavLauncher-Renewed-Full.apk`, `PojavLauncher-Renewed-Noruntime.apk`, a verification
  text asset, and release notes with limitations, checksums, build information, supported
  scenarios, and upgrade instructions.
- Do not overwrite or delete `pojavlauncher-debug-apks-20261126`.

Physical-device installation, live Modrinth API behavior, and in-game performance remain
user-side validation and will be explicitly reported as untested by the agent.


## 7. Krypton Wrapper (NG-GL4ES)

**Audit question:** can Ultra-Optimization tell Krypton apart from plain GL4ES, and what may it
offer on Krypton?

**Renderer identification, from the launcher source (not from naming):**

| Fact | Evidence |
| --- | --- |
| Krypton has its own tag, `opengles_nggl4es`, distinct from GL4ES `opengles2` | `game/renderer/def/Renderers.java` (`NGGL4ES_RENDERER` / `GL4ES_RENDERER`) |
| It has its own spec class and native library | `game/renderer/impl/GLESRenderSpec.java` -> `NGGL4ESRenderSpec`, `library()` = `libng_gl4es.so` |
| It is a *sibling* of the GL4ES spec, not a subclass | `NGGL4ESRenderSpec extends GLESRenderSpec`, like `GL4ESRenderSpec` |
| It pins desktop OpenGL **3.1** over OpenGL ES 3 | its `setupEnvironment` sets `LIBGL_GL=31`, `LIBGL_ES=3`, `LIBGL_NORMALIZE=1`, `LIBGL_USE_MC_COLOR=1`, `LIBGL_NOERROR=1` |
| It does not inherit the base GLES environment setup | its `setupEnvironment` does not call `super`, so no `GLESProvider`/ANGLE selection, no `allow_higher_compat_version`, no shader-dump flag |
| The launcher's Sodium guard does **not** cover it | `utils/jre/GameRunner.java` tests `renderer instanceof GLESRenderSpec.GL4ESRenderSpec` before blocking Sodium / switching to LTW; the same `instanceof` gates the "1.21.5+ is not GL4ES-compatible" switch |
| The launcher's settings list persists exactly this tag | `RendererCache.getCompatibleRenderers` feeds `ListPreference.setEntryValues(list.rendererIds)`; `Instance.getLaunchRenderer()` returns that value |
| Two legacy aliases mean plain GL4ES | `GameRenderer.getKnownRenderer` maps `opengles2_4` and `opengles2_5` onto `GL4ESRenderSpec` |

So Krypton **is** reliably distinguishable, and no invented detection mechanism is needed: the
per-installation renderer tag is the authority.

**Capability classification** (`UltraOptimizationDetector.describe`) is expressed over the
launcher's own `Renderers` constants and records, per family: the OpenGL ES major the spec asks
MojoExec to set up, the desktop OpenGL version the spec pins (if any), whether `GameRunner`
guards Sodium for it, and whether this build has a validated Sodium path. LTW is `esMajor=3`
with a validated Sodium path; GL4ES is `esMajor=2`, desktop GL 2.1, launcher-guarded; Krypton is
`esMajor=3`, desktop GL **3.1**, **not** launcher-guarded and **not** validated; Zink pins
`MESA_GL_VERSION_OVERRIDE=4.6` (legacy Zink 4.3) and is also not validated here. An unrecognised
tag stays unidentifiable and is never reported as Krypton, never as GL4ES either.

**Selection policy on Krypton:**

- Sodium is withheld. The wrapper advertises desktop OpenGL 3.1, which is below the core-profile
  path Sodium's rendering backend uses, and neither this repository nor the launcher's own checks
  establish the combination. "Neither guarded nor validated" is treated as unvalidated, not as
  permitted. Skipping is reported with the reason and the advertised version, on the mod row and
  in the notes.
- No other mod is affected: Sodium is the only renderer-gated candidate, asserted by the offline
  suite, so FerriteCore, Lithium, ModernFix, ImmediatelyFast, Entity Culling, More Culling and
  Memory Leak Fix stay subject only to the usual Modrinth/version/loader/dependency/budget rules.
- Krypton never causes a refusal. Renderer-dependent capability is a per-mod decision; the
  installation is still resolved, and an installation that already contains the mods resolves to
  a no-op rather than an error.
- The renderer is read, never written. Krypton is not "promoted" to LTW/Zink, and the GL4ES
  version cutoff is deliberately not applied to it, because `GameRunner` does not apply it either.

**Reported device evidence (user-provided, not measured here):** an Android phone running
Minecraft 1.21.11 on Fabric with Java 21, Krypton Wrapper and 2048 MB allocated, reaching the
main menu with FerriteCore, Lithium, Entity Culling, ImmediatelyFast, More Culling, Fabric API
and Cloth Config installed. This is consistent with the policy above - all of those are
non-renderer mods, and Fabric API / Cloth Config arrive as required dependencies rather than as
candidates. It does not establish anything about Sodium, and it is not a benchmark.

**Known nuances, stated rather than engineered around:**

- The scan reads the *configured* renderer (`Instance.getLaunchRenderer()`), which is what
  `GameRenderer` is constructed from. If the wrapper's native setup fails at launch,
  `GameRenderer.maybeSetupRenderer()` falls back to plain GL4ES; the resolver then still describes
  the installation as Krypton. That mismatch is benign here: both renderers get the same treatment
  for renderer-dependent mods (withheld) and the same treatment for everything else (eligible),
  so no mod decision changes. No launch-time probing or GL context creation is performed to
  resolve it - that would mean starting the game.
- A version JSON may set `disableRendererChecks`, which turns off `GameRunner`'s renderer blocks
  for that installation. Ultra-Optimization deliberately does not follow that flag: it never
  installs based on "the launcher would not complain", so the LTW-only Sodium rule stands regardless.
- The wrapper is labelled "26.2 and lower" in the launcher's own settings string. That ceiling
  belongs to the renderer, not to mod selection, so Ultra-Optimization neither enforces it nor
  ignores it silently: it is surfaced in the renderer display name.

Note that the 2048 MB allocation yields the two-mod budget tier, so the *first* run on that device
offers the two highest-scoring mods rather than all seven; raising the count is a deliberate,
separate policy decision and is not part of renderer support.

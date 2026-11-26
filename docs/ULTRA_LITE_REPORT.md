# PojavLauncher Ultra-Lite — final report

Date: 2026-11-26
Base commit: `5431df433002c460ca322867d1954f3e62d16287` (`v3_openjdk`)
Working branch: `arena/7535fc36-pojavlauncher`
Baseline audit: `docs/ULTRA_LITE_BASELINE.md`
Static verification: `python3 scripts/verify_static.py`

> **Branch and push.** This environment fixes the session to the branch above; it does not
> allow creating or switching branches, so the requested `ultra-lite-optimization` branch
> could not be created here. All nine commits are on the current branch and were pushed to
> `origin/arena/7535fc36-pojavlauncher`. `v3_openjdk` was not modified. The `origin` remote
> (`https://github.com/supersuperconsolinosteffo-tech/PojavLauncher.git`) is unchanged, so
> upstream synchronization still works. To get the requested name, create it from this branch
> locally and push it yourself (see build instructions below).

---

> **Finalization note (2026-11-26).** A later second-pass review, additional runtime fixes,
> extended static verification, build attempts and delivery status are documented in
> `docs/ULTRA_LITE_FINALIZATION_REPORT.md`. Where build/publishing results or commit counts in
> sections A–G differ from that report, the finalization report is authoritative.

## A. Optimized source code

The application is still PojavLauncher. Authentication, Microsoft/ElyBy/local login,
version management, vanilla/Fabric/Forge/NeoForge/Quilt/OptiFine/BTA installers, Java runtime
selection, library and dependency management, launch and shutdown, control customization,
touch controls, virtual mouse, keyboard, gamepad, all renderers, resolution settings, user
preferences, game directories and save files are unchanged in behaviour.

The main code changes live in:

| Area | Files |
| --- | --- |
| Optional profiles | `prefs/PerformanceProfile.java` (new), `res/values/perf_profiles.xml` (new), `res/xml/pref_misc.xml`, `res/xml/pref_java.xml`, two preference fragments |
| Idle threads / polling | `PojavApplication.java`, `ModIconCache.java`, `IconCacheJanitor.java`, `Downloader.java`, `LauncherPreferenceJavaFragment.java`, `MemoryUtils.java` |
| Retained references | `LauncherActivity.java`, `ProgressKeeper.java`, `ProgressLayout.java` |
| Preference loading cost | `prefs/LauncherPreferences.java` |
| Optional logging | `utils/JREUtils.java` |
| Compile blockers | `utils/jre/GameRunner.java` |
| Dependencies | `app_pojavlauncher/build.gradle` |

---

## B. Change report

### B.1 Removed

* **3 unused Gradle dependencies**: `androidx.documentfile:documentfile:1.1.0`,
  `androidx.viewpager2:viewpager2:1.1.0`, `javax.annotation:javax.annotation-api:1.3.2`.
  No source, layout, manifest or AIDL reference exists for any of them. Also removed five
  commented-out dependency lines that were dead configuration.
* **2 dead source files**: `customcontrols/mouse/AbstractTouchpad.java` (the interface has no
  implementors; `InGUIEventProcessor.setAbstractTouchpad(View)` takes a plain `View` now) and
  `customcontrols/mouse/CursorContainer.java` (no references at all). Both were checked
  against Java, Kotlin, XML, JNI C/C++ sources, the manifest and ProGuard rules first.
  `CriticalNativeTest.java` was kept despite having no Java references because it is a
  `@Keep` JNI test hook; `MultidrawBench.kt` was kept because its four declared types are used
  by `MgController.kt` and `MgDialogs.kt`.
* **77 unused resources**: 71 drawables (35 `avatar_*.webp`, 34 stale `ic_*` icons,
  `control_side_action_window.9.png`, `mineknob_off_unfiltered.xml`), 2 layouts, 2 menus,
  `xml/provider.xml` and `font/minecraft_ten.ttf`. The two `mine_button_*.9.png` files were
  **kept** after finding they are referenced by the `mine_button_background` selector.
* **3 unused assets**: `pojavlauncher.png` (528 KB), `about_en.txt`, `language_list.txt`.
  License files (`assets/licenses/*`, `assets/font/LICENSE_OFL.txt`) were deliberately kept.
* **2 stale imports** in `GameRunner.java` that pointed at non-existent packages
  (`utils.lifecycle.LifecycleAwareAlertDialog`, `utils.plugins.LibraryPlugin`) and one
  duplicate `android.content.Context` import. These two imports were a compile blocker at
  baseline; they now point at `net.kdt.pojavlaunch.lifecycle` and
  `net.kdt.pojavlaunch.plugins`.
* **1 redundant lifecycle override** (`LauncherPreferenceMiscellaneousFragment.onResume`).

### B.2 Optimized

| Change | Effect |
| --- | --- |
| `PojavApplication.sExecutorService` | `allowCoreThreadTimeOut(true)` with a 30 s keep-alive. The 4 worker threads no longer keep their stacks reserved while the launcher process is idle during gameplay. Task throughput is unchanged. |
| `ModIconCache.cacheLoaderPool` | 10 permanent threads -> 2 (1 in Ultra-Lite), core threads time out after 30 s. |
| `LauncherPreferenceJavaFragment` memory loop | 1 Hz -> 0.5 Hz, runs only while the fragment is resumed (`onResume`/`onPause`), 3 `ActivityManager.getMemoryInfo` binder calls per tick -> 1, no extra `runOnUiThread` post from the UI thread. |
| `Downloader` | progress reporting ~30 Hz -> 10 Hz (Balanced) or 4 Hz (Ultra-Lite); metadata loop 33 ms -> 100/250 ms. Fewer runnable, vararg and string allocations, fewer `TextView` invalidations and notification updates. Final progress is still cleared by the existing callers. |
| `ProgressLayout` | skips `setProgress`/`setText` when the reported values have not changed; `cleanUpObservers()` clears its own list so re-observing cannot register duplicates. |
| `LauncherPreferences.loadPreferences` | device-dependent defaults are computed only when the preference key is absent (Java evaluates `getInt(key, default)` arguments eagerly). `isDevicePowerful()` and `hasVulkanDrivers()` results are cached per process, avoiding repeated `/sys` CPU-freq reads, display-metric reads and EGL context creation on every settings toggle. Stored values are identical to before. |
| `IconCacheJanitor` | always clears its `Future` in `finally` (previously leaked when the cache was under the limit), deletes least-recently-used icons first (previously sorted one array and deleted from a different, unsorted list), drops the per-start "skipping cleanup" log line, and takes the cache limit from the profile. |
| `LauncherActivity` | `mRequestPermissionLauncher` is no longer `static` (a static ActivityResultLauncher retained a destroyed Activity); `mDoubleLaunchPreventionListener` is now removed from the static `ProgressKeeper` in `onDestroy`; fragment lifecycle callbacks are registered once in `onCreate` instead of being added again on every `onStart`. |
| `ProgressKeeper.removeListener` | removes empty listener lists from the static map. |
| `BaseActivity` / layouts | reviewed and left alone: the launcher hierarchy is already a flat `ConstraintLayout` with a `<merge>` include; maximum layout nesting depth is 4. No animation or transition was removed because none runs while the game is active. |

### B.3 Dependencies eliminated

`androidx.documentfile:documentfile`, `androidx.viewpager2:viewpager2`,
`javax.annotation:javax.annotation-api`. **No dependency was added.** Kept on purpose:
`org.tukaani:xz` (runtime requirement of `XZCompressorInputStream` in `MultiRTUtils`),
`commons-codec`, `htmlcleaner`, `checkerboarddrawable`, `ExtendedView`,
`virtual-joystick-android`, `android_gamepad_remapper`, `portrait-sdp/-ssp`,
`alsoft-android-aar`, Kotlin/coroutines/lifecycle, all AndroidX UI libraries, both JNI
binding projects and the entire `libs/*.aar`/`*.jar` set.

### B.4 Background tasks modified

| Task | Before | After |
| --- | --- | --- |
| General executor threads | 4 permanent per process | 4 max, released when idle |
| Mod icon loader threads | 10 permanent per adapter | 1–2, released when idle |
| Mod icon cache scan | every launcher start | launcher start in Balanced/Custom, deferred to mod browsing in Ultra-Lite; still performed there |
| Memory display polling | 1 Hz while the settings fragment existed, including while stopped | 0.5 Hz, only while resumed |
| Download progress | ~30 Hz | 10 Hz / 4 Hz |
| Version list fetch | every `LauncherActivity.onCreate`, 24 h disk cache | unchanged (already cached) |
| `ProgressService` | foreground only while tasks exist | unchanged |
| `GameService` | foreground in `:game` | unchanged |
| `options.txt` `FileObserver` | in `:game` | unchanged |
| `logcat` capture thread | always in `:game` | always by default; can be switched off only by the Custom profile |

### B.5 Memory-management improvements

* No destroyed `LauncherActivity` (with its full view hierarchy) is retained by
  `ProgressKeeper` or by a static `ActivityResultLauncher`.
* No duplicate `FragmentLifecycleCallbacks` accumulate on each return to the launcher.
* Idle worker-thread stacks are released in every process.
* `ProgressKeeper` no longer keeps empty listener lists.
* Icon cache can be capped at 16 MB instead of 100 MB, and the janitor now actually removes
  the oldest icons.
* A large static value (`ExtraCore` release table) was reviewed and deliberately **not**
  cleared on `onStop`: clearing it could make the "Play" button read a null version list while
  the async refresh is in flight. See remaining limitations.

### B.6 Functionality preserved

No renderer, ABI, native library, JNI source, `jniLibs` directory, `libs/*.aar`, manifest
declaration, process declaration, authentication class, mod loader, input handler, control
editor, runtime manager, `FolderProvider` or localization was removed. The only deleted Java
files were two verified-unused classes. `AndroidManifest.xml` was not modified: the existing
`:launcher`, `:game`, `:gui_installer` and `:mgquery` process split is intact. All four ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) remain available. `getJavaArgs` and the user's
`javaArgs` key were not touched.

### B.7 New optional features

**Performance profiles** (`Settings -> Miscellaneous -> Performance profile`), default
**Balanced**, so an existing installation behaves exactly as before:

* **Balanced**: normal behaviour, conservative management, best compatibility.
* **Ultra-Lite**: skips the startup icon cache scan, caps the mod icon cache at 16 MB (8 MB
  bring-down), uses one icon loader thread, refreshes download progress at 4 Hz, and
  recommends a conservative heap. Intended for 3–4 GB devices.
* **Custom**: four independent switches (minimal background work, small icon cache, low
  progress refresh rate, disable logcat capture). All default to off; the logcat switch is
  Custom-only and stays off in Ultra-Lite so game logs keep working by default.

**Automatic detection** (`PerformanceProfile.DeviceInfo`): total RAM, available RAM, Android
release/API, CPU ABI, CPU core count, 32-bit address space and installed Java runtimes. The
Misc screen shows these values and the suggested profile; tapping the entry applies the
suggestion. Nothing is applied automatically. Devices with the same total RAM are not treated
as identical: available memory, 32-bit address space and core count also influence the
suggestion.

**Java memory**: `Settings -> Java tweaks -> Apply recommended heap size` computes a
conservative value (about 30% of total RAM, capped at 1536 MB, further limited by currently
available RAM minus 512 MB and by 696 MB on 32-bit devices) and writes it to the existing
`allocation` preference only when the user taps it. No JVM flags were added, no G1GC flag was
imposed, and `javaArgs` is never rewritten. The existing `-Xms`/`-Xmx` handling is unchanged.

Every profile is optional and reversible: selecting Balanced restores the original behaviour.
Switching profiles never writes to `allocation`, `javaArgs`, renderer, runtime or instance
settings.

---

## C. Performance comparison

### C.1 Measured here (static, reproducible)

| Metric | Baseline `5431df4` | Final `HEAD` | Delta |
| --- | --- | --- | --- |
| Tracked files | 849 | 772 | -77 |
| Tracked bytes (no `.git`) | 73,567,646 | about 72.86 MB | about -0.71 MB |
| Java sources | 335 | 334 | -1 |
| Kotlin sources | 24 | 24 | 0 |
| Java + Kotlin lines | 38,196 | 38,409 | +213 |
| Resource files | 345 | 269 | -76 |
| `res/` bytes | 2,606,133 | 2,377,311 | -228,822 |
| Asset files | 25 | 22 | -3 |
| `assets/` bytes | 1,106,351 | 565,410 | -540,941 |
| `implementation` dependencies | 22 | 19 | -3 |
| `Log.*` call sites | 158 | 157 | -1 |
| Unresolved internal imports | 2 | 0 | -2 |
| Unused local resources | 77 | 0 | -77 |
| Idle executor threads (worst case) | 4 + 10 per mod adapter | 0 after 30 s idle | -14 |
| Download progress reports | ~30/s | 10/s (4/s in Ultra-Lite) | -67% / -87% |
| Settings memory polls | 1/s, 3 binder calls | 0.5/s, 1 binder call, paused when hidden | -83% binder calls |

`jniLibs` (15,047,528 bytes), `libs` (53,081,604 bytes), JNI sources and all four ABIs are
unchanged. The +213 lines come from the new `PerformanceProfile` class and its UI wiring; the
net effect is still 77 fewer files and ~0.7 MB less tracked content.

### C.2 Runtime measurements: not available

**No APK size, launcher RAM, JVM heap, native heap, graphics memory or CPU numbers were
measured, and none are claimed.** This sandbox has no JDK, Android SDK/NDK, Gradle
distribution, `adb`, emulator or device. Both build attempts
(`./gradlew :app_pojavlauncher:assembleDebug --no-daemon`) failed immediately with
`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.` A smaller
source tree is not evidence of lower runtime RAM.

The improvements above are theoretically beneficial (fewer idle thread stacks, fewer binder
calls and allocations per second, fewer retained Activity/View graphs, smaller disk cache),
but their magnitude is **unmeasured**. To obtain real numbers, build the baseline commit and
this commit separately and record both:

```
adb shell dumpsys meminfo net.kdt.pojavlaunch.debug          # :launcher process
adb shell dumpsys meminfo net.kdt.pojavlaunch.debug:game     # :game process
adb shell top -n 1 | grep pojavlaunch                        # idle and in-game CPU
adb shell cat /proc/<pid>/status                             # VmRSS, native heap
ls -l app_pojavlauncher/build/outputs/apk/*/*/*.apk         # APK size
```

Measure: (1) launcher RAM before launching, (2) launcher RAM while Minecraft runs, (3) game
process RAM, (4) JVM heap, (5) native heap, (6) CPU while the launcher is idle, (7) CPU while
Minecraft runs, (8) APK size. Compare Java heap, native heap, total PSS and graphics memory
separately. Also compare Balanced against Ultra-Lite on the same device and Minecraft version.

---

## D. Compatibility report

### D.1 What was tested and passed (static)

| Check | Result |
| --- | --- |
| `python3 scripts/verify_static.py` | 0 strict errors, 0 unused local resources |
| Java syntax of all 334 `.java` files (`javalang`) | parsed successfully |
| XML well-formedness (236 files, including manifest, layouts, preferences, values) | passed |
| Internal `net.kdt.pojavlaunch.*` imports resolve | 2 errors at baseline -> 0 |
| Local resource references (`@drawable/`, `@layout/`, `@menu/`, `@xml/`, `@font/`, `@mipmap/`, `R.<same>.name`) | all resolve |
| `@string/`, `@array/`, `@integer/` references in the two edited preference XMLs | all resolve |
| Preference keys referenced by `requirePreference`/`findPreference` | all present in XML |
| Duplicate resource names among new values | none |
| 41 `R.*` references not defined locally | all accounted for: AndroidX/appcompat attrs and layouts, `portrait-sdp/-ssp` dims, gamepad-remapper drawables, `resValue`-generated strings |
| Protected paths | no `jni/`, `jniLibs/`, `libs/`, renderer, platform, auth or manifest change |

### D.2 What failed

* The Gradle build, twice, because the sandbox has no JDK. Additionally no Android SDK/NDK is
  installed, so a build would not complete even with a JDK. This is an environment failure,
  not a code failure; the two baseline compile-blocking imports were fixed.

### D.3 What remains unverified (no device/emulator)

Application startup; Microsoft account authentication; existing account sessions; Minecraft
version selection; vanilla, Fabric and Forge launch; Java runtime selection; game directory
access; settings preservation; touch controls; keyboard and controller input; renderer
selection; Minecraft shutdown; returning to the launcher; performance profile switching at
runtime; custom JVM arguments; optional mod installation; behaviour on Android 5–16; APK
installation; and the runtime effect of the changes on RAM/CPU. **None of these is claimed to
work because the code parses.** They need an Android device or emulator.

---

## E. Build instructions

Requirements: JDK 17 (Android Gradle Plugin 8.11.1), Android SDK with platform 36, NDK
`29.0.14206865`, CMake, git submodules initialized (`git submodule update --init --recursive`),
and network access for the Gradle wrapper, Maven/Google/JitPack dependencies and the remote
asset tasks. The `full` flavor downloads a JRE archive at build time; use `noruntime` to skip
that download if you supply your own runtime.

```
git submodule update --init --recursive
./gradlew :app_pojavlauncher:assembleDebug            # Linux/macOS
.\gradlew.bat :app_pojavlauncher:assembleDebug       # Windows
```

The debug APK is written under
`app_pojavlauncher/build/outputs/apk/full/debug/` (or `noruntime/debug/`). Other build types
from `app_pojavlauncher/build.gradle`: `proguard`, `proguardNoDebug`, `release`, `gplay`,
`pubRelease`. Release signing needs the environment variables referenced in that file.

Static verification (no JDK needed):

```
python3 scripts/verify_static.py           # strict checks + unused resource report
python3 scripts/verify_static.py --summary
```

Optional: `pip install javalang` enables Java syntax parsing inside the script.

To create the requested branch name from this work (run locally, with your own push):

```
git checkout -b ultra-lite-optimization arena/7535fc36-pojavlauncher
git push -u origin ultra-lite-optimization
```

To inspect or revert a single phase: `git show <commit>` / `git revert <commit>` using the
hashes in section G.

---

## F. Remaining limitations

1. **No build or device validation.** See D.2 and D.3. Runtime performance numbers do not
   exist and were not invented.
2. **Optional Fabric performance-mod installer (phase 5 of the request) was not
   implemented.** Reasons: PojavLauncher exposes several renderers (LTW, GL4ES, Mesa/Zink,
   ANGLE, MobileGlues); Sodium-family mods interact with those renderers in ways that cannot
   be verified here; the project already has a Modrinth/CurseForge modpack pipeline whose
   model classes would need extending for version matching, dependency resolution, duplicate
   detection, per-instance install paths, progress and failure handling; and there is no way
   to test any of it in this environment. Shipping an untested auto-installer would conflict
   with "never silently install incompatible mods" and with the request's priority of
   reducing work. It is left as future work, not silently attempted.
3. **No JVM/G1GC flags were added.** `-XX:+UseG1GC` and related flags depend on the selected
   Java runtime (8/17/21) and Minecraft version. The existing `-Xms`/`-Xmx` handling, all
   generated arguments and all user `javaArgs` remain untouched. Profiles only offer an
   explicit heap *suggestion* written to the existing `allocation` preference.
4. **No process architecture changes.** The launcher/game process split already exists in
   `AndroidManifest.xml`. Nothing was moved, no `android:process` was added, no
   `Process.killProcess` shortcut was introduced and no service was terminated.
5. **The `ExtraCore` release table is still retained while the game runs.** Clearing it when
   the launcher stops would free its few hundred KB, but a "Play" tap immediately after
   returning could then see a null version list while `AsyncVersionList` refreshes. The
   safer choice was to leave it. This is the main remaining known static retention in the
   launcher process.
6. **`JREUtils.redirectAndPrintJRELog` still runs by default.** It keeps a `logcat` process
   and a reader thread alive in `:game`. Only the Custom profile can disable it, because the
   log is important for crash reports. Other minor background work (`AsyncVersionList` with a
   24 h disk cache, the `options.txt` `FileObserver`, `ProgressService` during downloads) was
   kept because it is needed.
7. **Third-party AARs and native libraries dominate APK size.** ~53 MB of `libs/*.aar` and
   ~15 MB of `jniLibs` were left untouched by design; they are protected rendering/JNI
   components. Removing an ABI to shrink the APK was explicitly avoided. An ARM64-only build
   was not added as a flavor because no build could be produced or tested here.
8. **Localization.** New profile strings exist only in `res/values/perf_profiles.xml`; other
   locales fall back to English. All existing translations are untouched.
9. **Profile changes take effect at different times.** Thread counts and cache limits are read
   when the relevant component is created; the heap value applies on the next game launch.
   This is documented in the UI (the heap toast). No restart of the whole app is required for
   the profile itself.

---

## G. Git history

Nine commits on `arena/7535fc36-pojavlauncher`, logically grouped by phase, following the
project's `Upd/Fix/Feat[what_changed]: description` convention:

| Commit | Phase |
| --- | --- |
| `463528d` | `Upd[docs]: add Ultra-Lite baseline audit and static verification script` |
| `c6f274f` | `Fix[jre]: correct stale GameRunner imports and drop duplicate Context import` |
| `d3cd692` | `Upd[cleanup]: remove unused dependencies, dead code and 77 unused resources/assets` |
| `1890ffd` | `Upd[runtime]: release idle threads, stop UI polling and fix retained listeners` |
| `5166da5` | `Feat[prefs]: add optional Balanced/Ultra-Lite/Custom performance profiles` |
| `885aef0` | `Upd[ui]: avoid duplicate fragment callbacks and redundant progress view updates` |
| `21c8b99` | `Upd[docs]: add final Ultra-Lite change, compatibility and measurement report` |
| `1eea90f` | `Upd[runtime,docs]: guard progress varargs and finalize Ultra-Lite report` |
| HEAD | `Upd[docs]: record GitHub push status in report` |

`git status` is clean. The working tree was clean before work started, so no user changes were
overwritten. `git diff --stat 5431df4..HEAD` lists the full changeset (about 104 files,
excluding binary deletions); run it for exact line counts.

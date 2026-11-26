# PojavLauncher Ultra-Lite — baseline audit

Date: 2026-11-26
Repository: `supersuperconsolinosteffo-tech/PojavLauncher`
Base commit: `5431df433002c460ca322867d1954f3e62d16287` (`v3_openjdk`)
Working branch: `arena/7535fc36-pojavlauncher` (clean before work started)
Remotes: `origin` -> `https://github.com/supersuperconsolinosteffo-tech/PojavLauncher.git`

> Branch note: this environment fixes the session to the branch above and does not allow
> creating or switching branches. The requested `ultra-lite-optimization` branch cannot be
> created here. All work is committed in logical phases on the current branch, and no push to
> the default branch is performed. The fork's `origin` remote is left untouched, so pulling
> future upstream changes remains possible.

## 1. What this codebase is

This fork is a MojoLauncher-lineage PojavLauncher build (see `AGENTS.md`, `.gitmodules` and
the manifest). The code lives in `app_pojavlauncher`, not in `app_mojo` as some project docs
say. Native windowing/integration libraries (`glfw`, `sdl`, `mojoexec`, `NoBlocklist`,
`MioLibPatcher`) are git submodules; the checkout contains their directories but not their
contents. The JNI sources are under `app_pojavlauncher/src/main/jni`.

Important architectural fact for the runtime objective: the launcher and the game already
run in **separate Android processes**. `AndroidManifest.xml` declares
`android:process=":launcher"` on `<application>`, `android:process=":game"` on
`GameActivity` and `GameService`, `:gui_installer` on `AWTActivity` and `:mgquery` on
`MgQueryService`. This is why no process split was added: the split is already there and
changing it would risk surface, input and JNI communication. Work therefore concentrates on
what each process keeps doing while the game runs.

## 2. Baseline, measured

These are static, reproducible measurements of the checkout. They are not APK or runtime
measurements; see section 6 for why.

| Metric | Value at `5431df4` |
| --- | --- |
| Tracked files | 849 |
| Tracked bytes (no `.git`) | 73,567,646 |
| Java sources | 335 |
| Kotlin sources | 24 |
| Java + Kotlin lines | 38,196 |
| Resource files | 345 (2,606,133 bytes) |
| Asset files | 25 (1,106,351 bytes) |
| `jniLibs` (4 ABIs) | 15,047,528 bytes |
| `libs` (bundled jars/aars) | 53,081,604 bytes |
| JNI source files | 32 (113,585 bytes) |
| `implementation` dependencies in `app_pojavlauncher/build.gradle` | 22 |
| `Log.*` call sites | 158 |
| Explicit `Thread`/executor use sites | 100+ |

Commands used (Linux):

```
find app_pojavlauncher/src/main/java -name '*.java' | wc -l
cat $(find app_pojavlauncher/src/main/java -name '*.java' -o -name '*.kt') | wc -l
du -sb app_pojavlauncher/src/main/{res,assets,jniLibs,jni} app_pojavlauncher/libs
git ls-files | wc -l
```

The baseline APK size, launcher RAM, JVM heap, native heap and CPU usage could not be
measured: this sandbox has no JDK, no Android SDK/NDK, no Gradle distribution, no `adb` and
no emulator or device. `./gradlew :app_pojavlauncher:assembleDebug` fails immediately with
`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.` No invented
numbers are reported anywhere in this project. Section 6 describes the measurement protocol
to run on a real device.

## 3. Build blockers found at baseline

`net/kdt/pojavlaunch/utils/jre/GameRunner.java` imports two classes from packages that do
not exist in the source tree:

* `net.kdt.pojavlaunch.utils.lifecycle.LifecycleAwareAlertDialog` (actual:
  `net.kdt.pojavlaunch.lifecycle.LifecycleAwareAlertDialog`)
* `net.kdt.pojavlaunch.utils.plugins.LibraryPlugin` (actual:
  `net.kdt.pojavlaunch.plugins.LibraryPlugin`)

These are the only two unresolved internal imports in the project. They are fixed in phase 2.

## 4. Audit findings

"Kept" means a component was checked and deliberately left alone, usually because it belongs
to the protected native/renderer/input/auth/launch path or because its references are
dynamic.

### 4.1 Dependencies (`app_pojavlauncher/build.gradle`)

| Dependency | Used by | Decision |
| --- | --- | --- |
| `androidx.documentfile:documentfile` | no source or layout reference | remove |
| `androidx.viewpager2:viewpager2` | no source or layout reference | remove |
| `javax.annotation:javax.annotation-api` | no source reference; androidx annotations are used instead | remove |
| `org.tukaani:xz` | needed at runtime by `XZCompressorInputStream` in `MultiRTUtils` | keep |
| `commons-codec:commons-codec` | `HashUtils` (SHA1 hex) | keep |
| `net.sourceforge.htmlcleaner:htmlcleaner` | `OptiFineScraper`, `OFDownloadPageScraper` | keep |
| `com.github.duanhong169:checkerboarddrawable` | `AlphaView`, `ColorSideBySideView`, `CropperView` | keep |
| `com.github.Mathias-Boulay:ExtendedView` | `LauncherMenuButton extends ExtendedButton` | keep |
| `com.github.Mathias-Boulay:virtual-joystick-android` | `ControlJoystick extends JoystickView` | keep |
| `com.github.Mathias-Boulay:android_gamepad_remapper` | gamepad mapper fragments | keep |
| `com.github.PojavLauncherTeam:portrait-sdp/-ssp` | `@dimen/_Nsdp`, `@dimen/_Nssp` used in many layouts | keep |
| `com.github.MojoLauncher:alsoft-android-aar`, `libs/*.aar` | native audio/renderer libs loaded through JNI | keep, protected |
| `kotlinx-coroutines-android`, `lifecycle-runtime-ktx` | MobileGlues Kotlin settings screens | keep |
| `androidx.preference`, `drawerlayout`, `constraintlayout`, `annotation` | settings UI, game drawer, layouts | keep |
| `project(":glfw:jni_bindings")`, `project(":sdl:jni_bindings")` | `Platform` backends | keep, protected |

The commented-out dependency lines (`android-bsf-api`, `androidx.core`, `sdp-android`,
`ssp-android`, `streamsupport-cfuture`) are dead configuration and are removed as cleanup,
not as a dependency change.

### 4.2 Dead source code

| File | References | Decision |
| --- | --- | --- |
| `customcontrols/mouse/AbstractTouchpad.java` | none; `InGUIEventProcessor.setAbstractTouchpad(View)` now takes a plain `View` | remove |
| `customcontrols/mouse/CursorContainer.java` | none | remove |
| `CriticalNativeTest.java` | none in Java, but `@Keep` + native `testCriticalNative`; JNI test hook | keep (protected JNI) |
| `mobileglues/settings/MultidrawBench.kt` | filename is not referenced, but its four declared types are used by `MgController.kt` and `MgDialogs.kt` | keep |

`MultidrawBench.kt` is a good example of why a filename-based dead-code check is not enough:
the file declares `MultidrawBenchReport`, `MultidrawBenchQuality`, `RankedItem` and
`MultidrawBenchAnalyzer`, all used elsewhere.

### 4.3 Unused resources and assets

Verified with `scripts/verify_static.py`, which checks `R.<type>.<name>`, `@<type>/<name>`,
resource-name string usage, manifest references and dynamic `getIdentifier` use. There are no
`getIdentifier` calls in the project, so name-based references are complete.

* 2 unused layouts: `dialog_live_mouse_speed_editor.xml`, `dialog_per_version_control.xml`
* 2 unused menus: `menu_customctrl.xml`, `menu_versionopt.xml`
* 1 unused xml: `provider.xml` (the manifest uses `@xml/netconfig`)
* 1 unused font: `minecraft_ten.ttf` (127 KB); `noto_sans_bold` is the one actually used
* 38 unused drawables: older `ic_setting_*`, `ic_menu_*`, `ic_px_*` icon set, three
  9-patches and `mineknob_off_unfiltered.xml`
* 35 unused `drawable-nodpi/avatar_*.webp` contributor pictures (no code or text reference)
* 3 unused assets: `pojavlauncher.png` (528 KB), `about_en.txt`, `language_list.txt`

Kept on purpose: `assets/licenses/*` (legal notices for GL4ES, Mesa, bytehook, mojoexec,
pffft), `assets/font/LICENSE_OFL.txt`, all `mipmap*` launcher icons, `drawable-uk`, all
`values-*` translations, all `assets/components/*` (authlib-injector, caciocavallo, security,
sandbox policies), `assets/{cert.pem,default.json,launcher_profiles.json,options.txt,
resolv.conf,substitutions.json}`, all four `jniLibs` ABIs and all `libs/*.aar`.

### 4.4 Background work, CPU and polling

| Component | What it does | Decision |
| --- | --- | --- |
| `PojavApplication.sExecutorService` | 4 permanent core threads in every process (`ThreadPoolExecutor(4,4,500ms, unbounded queue)`), core threads never time out | allow core threads to time out; keeps behaviour, removes idle thread stacks |
| `ModIconCache.cacheLoaderPool` | `ThreadPoolExecutor(10,10,1000ms,...)`, 10 permanent threads per mod-browser adapter | smaller pool + core timeout |
| `LauncherPreferenceJavaFragment.memoryUpdater` | 1 Hz loop on the UI thread while the settings fragment exists; each tick does 3 `ActivityManager.getMemoryInfo` binder calls, rebuilds strings and posts another runnable | stop on pause, tick less often, one memory read per tick |
| `Downloader.downloadAll` | `Thread.sleep(33)` loop reporting progress ~30 times/sec through `ProgressKeeper` -> `ProgressLayout` -> `post()` -> notification | lower rate, skip unchanged progress |
| `IconCacheJanitor.runJanitor()` | scans the whole mod icon cache dir at every launcher start (once per process) | keep for now, make cache limit and startup scan profile-controlled |
| `AsyncVersionList` | version list fetch on each `LauncherActivity.onCreate`, already backed by a 24 h disk cache | keep; no unbounded work |
| `ProgressService` | foreground service only while downloads are active, stops itself at task count 0 | keep, required for downloads |
| `GameService` | foreground service in `:game` process | keep, required for game persistence |
| `MCOptionUtils` `FileObserver` | watches `options.txt` for GUI scale changes in the game process | keep, required in-game |
| `JREUtils.redirectAndPrintJRELog` | `logcat` subprocess + reader thread in `:game` for `latestlog.txt` | keep by default; a new optional setting can disable it. No universal change. |
| `LauncherPreferences.loadPreferences` | called on *every* preference change; eagerly computes `findBestRAMAllocation`, `findBestResolution`, `isDevicePowerful` (reads `/sys` CPU freq files) and `hasVulkanDrivers` (creates an EGL context) even when the values come from stored preferences | make defaults lazy and cache device capabilities |
| `MCOptionUtils.load` | logs every malformed `options.txt` line, reads the full file | keep, runs once per launch |

### 4.5 Memory leaks and retained references

| Finding | Impact | Decision |
| --- | --- | --- |
| `LauncherActivity` registers `mDoubleLaunchPreventionListener` with the static `ProgressKeeper` and never removes it in `onDestroy` | a destroyed Activity (and its whole view hierarchy) stays reachable for the process lifetime | fix in phase 4 |
| `ProgressKeeper.removeListener` leaves empty `ArrayList` entries in a static map, `ProgressLayout.cleanUpObservers()` does not clear its own list | small, bounded; repeated `observe()` could register duplicate listeners | clean up in phase 4 |
| `ModIconCache.mCancelledReceivers` holds weak references, pruned on use | negligible | keep pruning, reduce pool |
| `ExtraCore` listener map | weak references already, pruned on notify | keep |
| `GameActivity.touchCharInput`, `weakCursor`, `mForcedPanningHeight` | static fields; `touchCharInput` keeps a View statically in `:game` | documented, kept: input code and JNI callbacks depend on them |

### 4.6 Protected components left untouched

GL4ES, MobileGlues, Zink/Mesa, ANGLE, LWJGL, GLFW, SDL, all `jni/` C/C++ code, all
`jniLibs` ABIs, `libs/*.aar`, `GameRenderer`/`RenderSpec`/`Platform` backends,
`CallbackBridge`, `JavaRunner`/`GameRunner` launch logic, authentication (Microsoft, ElyBy,
local), mod loaders (Fabric/Forge/NeoForge/Quilt/OptiFine/BTA), `MultiRTUtils` runtime
management, `FolderProvider`, control customization, gyro/gamepad/touch input and all
localizations. No renderer or ABI was removed, no rendering architecture was rewritten, and
no new dependency was introduced.

## 5. Features that were investigated but not implemented

**Optional Fabric performance-mod installer (phase 5).** Deferred, with reasons in
`docs/ULTRA_LITE_REPORT.md`: the launcher supports several renderers (LTW, GL4ES, Mesa/Zink,
ANGLE, MobileGlues) and Sodium-family mods interact with those renderers in ways this
environment cannot verify; the project already has a Modrinth/CurseForge modpack pipeline
whose metadata models would have to be extended; and there is no build or device here to
validate version matching, dependency resolution, duplicate detection or download failures.
Shipping an untested auto-installer would conflict with the "never silently install
incompatible mods" requirement and with the priority of reducing work. It is documented as a
remaining limitation rather than implemented.

**G1GC / extra JVM flags.** Not added. Whether `-XX:+UseG1GC` and related flags are available
depends on the selected runtime (Java 8/17/21) and Minecraft version; a universal flag would
break some combinations. The existing `-Xms`/`-Xmx` handling and the user's `javaArgs` are
left exactly as they are. The new profiles only offer an explicit, one-tap heap *suggestion*
written to the existing `allocation` preference.

**Moving the game to another process, `Process.killProcess` shortcuts, killing services.**
Not done. The process split already exists and the game/service communication must keep
working.

## 6. Verification limits and measurement protocol

Unavailable here: JDK, Android SDK/NDK, Gradle distribution, `adb`, emulator, device.
Therefore the following remain unverified and are never claimed to work: application startup,
Microsoft login, version selection, vanilla/Fabric/Forge launch, Java runtime selection,
game directory access, settings preservation, touch controls, keyboard/controller input,
renderer selection, Minecraft shutdown, returning to the launcher, profile switching, custom
JVM arguments and optional mod installation.

What *is* verified here is static: Java/Kotlin parsing (`javalang` where available), XML
well-formedness, internal import resolution, resource reference resolution, unused-resource
detection, dependency usage and listener/executor review, via `scripts/verify_static.py`.

To measure the real result on a device, build the baseline commit and the optimized commit
separately and record, for each build:

```
adb shell dumpsys meminfo net.kdt.pojavlaunch.debug          # launcher process (:launcher)
adb shell dumpsys meminfo net.kdt.pojavlaunch.debug:game     # game process
adb shell top -n 1 | grep pojavlaunch                        # CPU, launcher idle and in game
adb shell cat /proc/<pid>/status                             # VmRSS / native heap
ls -l app_pojavlauncher/build/outputs/apk/*/*/*.apk         # APK size
```

Measure (1) launcher RAM before launching, (2) launcher RAM while the game runs, (3) game
process RAM, (4) JVM heap from the game log, (5) native heap from `dumpsys meminfo`, (6) CPU
while the launcher is idle, (7) CPU while Minecraft runs, (8) APK size. Compare Java heap,
native heap, total PSS and graphics memory separately. Do not treat a smaller APK as proof
of lower runtime RAM.

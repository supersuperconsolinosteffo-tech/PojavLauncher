# PojavLauncher Ultra-Lite — finalization, verification and delivery report

Date: 2026-11-26
Repository: `supersuperconsolinosteffo-tech/PojavLauncher`
Base commit: `5431df433002c460ca322867d1954f3e62d16287` (`v3_openjdk`)
Working branch: `arena/7535fc36-pojavlauncher`
Remote: `origin` -> `https://github.com/supersuperconsolinosteffo-tech/PojavLauncher.git`

> This document reports the state **after** the finalization pass requested in the latest
> message. `docs/ULTRA_LITE_BASELINE.md` and sections A–G of `docs/ULTRA_LITE_REPORT.md`
> describe the earlier optimization phases and remain valid as history. Where this document
> differs (build attempts, number of commits, fixes found in the second review), this document
> is authoritative.

---

## 1. Repository and branch status

| Check | Result |
| --- | --- |
| `git status` before changes | clean, no uncommitted or untracked user changes |
| Current branch | `arena/7535fc36-pojavlauncher` (active) |
| `origin` | the user's fork, not upstream; unchanged |
| Default branch `origin/v3_openjdk` | still at base `5431df4`; never pushed to |
| Previously pushed commits | present on `origin/arena/7535fc36-pojavlauncher` before this pass |
| Previous "eight commits" | actually nine commits were present (`463528d` … `e4f8df7`); the extra one recorded the earlier push |
| New commits in this pass | see section 2 and `dist/commits.txt` |
| Working tree after this pass | clean; `git diff --check 5431df4..HEAD` exits 0 |
| Protected paths | no `jni/`, `jniLibs/`, `libs/`, renderer, platform, auth or manifest change |
| Secrets / signing keys / caches | none added; `git diff --name-only` contains no key, keystore, credential, token or `local.properties` file |

The session is fixed to `arena/7535fc36-pojavlauncher`. The environment does not allow
creating, switching to, or pushing another branch, so the preferred dedicated branch
`ultra-lite-optimization` was **not** created. All work stays on the current branch; `main` was
never touched and no upstream repository was written to.

---

## 2. Commits created and their hashes

Commits already present before this pass (all pushed to the fork):

| Hash | Message |
| --- | --- |
| `463528d` | `Upd[docs]: add Ultra-Lite baseline audit and static verification script` |
| `c6f274f` | `Fix[jre]: correct stale GameRunner imports and drop duplicate Context import` |
| `d3cd692` | `Upd[cleanup]: remove unused dependencies, dead code and 77 unused resources/assets` |
| `1890ffd` | `Upd[runtime]: release idle threads, stop UI polling and fix retained listeners` |
| `5166da5` | `Feat[prefs]: add optional Balanced/Ultra-Lite/Custom performance profiles` |
| `885aef0` | `Upd[ui]: avoid duplicate fragment callbacks and redundant progress view updates` |
| `21c8b99` | `Upd[docs]: add final Ultra-Lite change, compatibility and measurement report` |
| `1eea90f` | `Upd[runtime,docs]: guard progress varargs and finalize Ultra-Lite report` |
| `e4f8df7` | `Upd[docs]: record GitHub push status in report` |

Commits added by this finalization pass:

| Hash | Message |
| --- | --- |
| `af72ece` | `Fix[runtime]: resolve icon janitor deadlock and profile edge cases` |
| `aeabf28` | `Upd[verify]: check value resources, ids and generated resValue references` |
| `HEAD` | `Upd[docs]: add finalization report and ignore generated delivery artifacts` |

`dist/commits.txt` is generated after `HEAD` and contains the exact
`git log --oneline 5431df4..HEAD` list, including the final commit hash, so no hash inside
this file is self-referential.

---

## 3. Technical changes implemented in this pass

### 3.1 Deep review findings that were real defects

1. **Icon-cache janitor deadlock (highest severity).**
   `IconCacheJanitor.waitForJanitorToFinish()` held `IconCacheJanitor.class` while calling
   `Future.get()`. The worker's `finally` block needs that same monitor to clear
   `sJanitorFuture`. Because `ModIconCache` now starts the janitor in its constructor and
   `ReadFromDiskTask` / `DownloadImageTask` immediately wait for it, the deadlock could occur
   on the first icon load. Fixed by copying the `Future` under the monitor and awaiting it
   outside the monitor; `InterruptedException` now restores the interrupt flag and
   `ExecutionException` is logged instead of crashing icon loading.

2. **Janitor state was wrong for profile switching.** The previous rewrite only marked the
   janitor as "ran" when it deleted files, so a no-op Balanced scan could repeat, and a
   switch to Ultra-Lite's 16 MB limit would not re-run after a 100 MB scan. Fixed by recording
   the limit used, always marking the run complete in `finally`, and re-running when the
   effective limit changes. The limit is captured at submission so a running cleanup cannot
   suddenly delete more than the selected profile allows. Exceptions in the scan are caught
   and logged so the `Future` never sticks.

3. **Ultra-Lite could accidentally disable logcat capture.** `PerformanceProfile.isEnabled()`
   returned `true` for *every* key in Ultra-Lite. `isLogcatCaptureDisabled()` had its own
   Custom-only check, so the shipped UI was safe, but the helper was a latent trap. Fixed by
   making the Ultra-Lite default explicit per switch: the three resource switches default to
   `true`, logcat remains Custom-only and off by default.

4. **Device detection could misclassify a failed memory read.** `suggestProfile()` treated
   `totalRamMb == 0` / `availableRamMb == 0` as a low-RAM device and recommended Ultra-Lite.
   It also suggested Ultra-Lite on any 4-core device regardless of RAM. Fixed: zero values are
   ignored, available-RAM checks require a valid total, and the core-count heuristic applies
   only up to 6 GB total RAM.

5. **Recommended heap could exceed the settings seek bar.** `getRecommendedHeapMb()` used
   `Math.max(512, availableRam - 512)`, so low available RAM did not actually lower the
   suggestion, and it was not clamped to the same `maxRAM` used by
   `LauncherPreferenceJavaFragment`. Fixed: the available-RAM floor is 256 MB, the result is
   clamped to the fragment's exact seek-bar maximum, and the final value is at least 256 MB.

6. **Profile switch did not update the Custom switches immediately.**
   `LauncherPreferenceFragment.onSharedPreferenceChanged()` reloads preferences but does not
   call the visibility updater. Selecting a profile from the `ListPreference` left the four
   switches enabled/disabled according to the *previous* profile until the fragment was
   resumed. Fixed with an override in `LauncherPreferenceMiscellaneousFragment` that refreshes
   visibility only when `performanceProfile` changes.

7. **Progress view could skip the first update of a new task.** `LayoutProgressListener`
   cached `mLastProgress` / `mLastText` to avoid redundant `TextView` invalidations, but did
   not reset them when a task ended or a new one started. Fixed by resetting both in
   `onProgressStarted()` and `onProgressEnded()`. `onProgressUpdated()` also now tolerates a
   `null` vararg array replayed from `ProgressKeeper`'s stored state.

8. **Download progress used wall-clock time and had no completion report.**
   `System.currentTimeMillis()` can jump backwards and stall reporting. Replaced with
   `SystemClock.elapsedRealtime()`. A final progress report is now emitted after the download
   loop (guarded for an empty list) so the UI shows the completed state before the caller
   clears the line. Baseline did not emit a final 100% report either; this is a correctness
   improvement, not a semantic regression.

9. **Profile entry values were translatable.** `perf_profile_values` is now
   `translatable="false"` so a translated array cannot persist strings that
   `getProfile()` would no longer recognize.

### 3.2 Verification improvements

`scripts/verify_static.py` was extended. It previously checked XML well-formedness, internal
imports and file-based resources only. It now also checks `@string/`, `@array/`, `@integer/`,
`@dimen/`, `@color/`, `@bool/`, `@style/`, `@attr/`, `@id/`, `R.styleable.*`, generated
`resValue` strings from `app_pojavlauncher/build.gradle`, `@+id` declarations, and documented
external AAR resources (AndroidX styles/attrs, `sdp`/`ssp` dimensions, gamepad-remapper
drawables, preference seek-bar IDs). Java syntax is parsed with `javalang` when installed.
The verifier exits 0 on the final tree.

### 3.3 Review results that required no change

* No source/XML reference remains to the three removed Gradle dependencies
  (`androidx.documentfile`, `androidx.viewpager2`, `javax.annotation`); bundled AAR/JAR files
  do not reference `javax/annotation` either.
* No duplicate top-level default value-resource names; format placeholders in the new strings
  match their Java call sites (`perf_device_info` has 8 args, `perf_heap_applied` has 1).
* All 73 `@id/...` references resolve to a local `@+id` or `<item type="id">` declaration.
* Balanced remains the default; existing installations keep their `allocation`, `javaArgs`,
  renderer, runtime and instance settings. Ultra-Lite only changes an icon-cache startup scan,
  cache size, loader thread count and progress refresh rate; it does not disable auth, version
  management, launch, input, renderers or logging.
* No JVM/G1GC flags, process changes, ABI removals, native/JNI edits or manifest edits were
  introduced. New API usage is compatible with `minSdk 23` (`ActivityManager.MemoryInfo`,
  `Build.SUPPORTED_ABIS` from API 21, `Collections.sort`, `allowCoreThreadTimeOut` from API 9,
  AndroidX Preference 1.2.1).

---

## 4. Issues discovered and fixed (summary)

| Issue | Severity | Status |
| --- | --- | --- |
| `IconCacheJanitor.waitForJanitorToFinish()` deadlock | critical | fixed in `af72ece` |
| Janitor not re-running after Ultra-Lite profile change | correctness | fixed in `af72ece` |
| Latent Ultra-Lite logcat-disable bug | latent | fixed in `af72ece` |
| Zero-RAM / 4-core false Ultra-Lite suggestion | correctness | fixed in `af72ece` |
| Heap suggestion outside seek-bar range | correctness | fixed in `af72ece` |
| Profile switch UI not refreshing | UI correctness | fixed in `af72ece` |
| Stale progress-view cache across tasks | UI correctness | fixed in `af72ece` |
| Wall-clock progress timing + missing final report | robustness | fixed in `af72ece` |
| Translatable profile values | localization | fixed in `af72ece` |
| Missing `@string`/`@array`/`@id` static checks | verification gap | fixed in `aeabf28` |
| Gradle distribution / SDK / Maven hosts unreachable | environment | documented, not fixable here |

---

## 5. Static verification results (actually run)

```
python3 scripts/verify_static.py
Ultra-Lite static verification
  Java/Kotlin sources : 358
  XML files parsed    : 236
  Java files parsed   : 334
  strict errors       : 0
  unused local resources: 0
exit=0
```

Additional checks actually run: `git diff --check` (exit 0), duplicate default value-resource
name scan (0), `@id` resolution scan (0 missing), generated `resValue` parsing, removed-dependency
usage scan (0), protected-path scan (0 changes), secrets/keys scan (0), format-placeholder
review, and a full `R.<type>.<name>` / `@<type>/<name>` cross-check with documented external
resources. `javalang` was installed in the session solely to enable Java parsing; it is not a
repo dependency and `scripts/verify_static.py` treats it as optional.

---

## 6. Actual build result

**No build succeeded and no APK was produced.** This is an environment limitation, not a
claimed compile result.

What was made available inside the sandbox:

* **JDK 17 with `javac`**: installed from the reachable npm package
  `javajre-linux-64@17.0.8` into `/tmp/jdk17/package/jre`. `java -version` reports
  `17.0.8`, `javac -version` reports `17.0.8`, and the `jdk.compiler` module is present.
  (A `jdk4py` PyPI wheel was also tested but contains only a JRE, no `javac`.)
* **Git submodules**: `git submodule update --init --recursive` succeeded from `github.com`;
  all five submodules are at their recorded commits.
* **Network**: only `github.com`, `codeload.github.com`, `api.github.com`,
  `registry.npmjs.org`, `pypi.org` and `files.pythonhosted.org` are reachable.

What is missing / blocked, with evidence:

| Requirement | Status |
| --- | --- |
| Gradle 8.14.5 distribution | `services.gradle.org` unreachable; wrapper fails before evaluating the project |
| Android SDK Platform 36 | absent; `dl.google.com` unreachable |
| Android NDK `29.0.14206865` | absent; `dl.google.com` unreachable |
| CMake / Ninja / `sdkmanager` / `adb` | not installed; no reachable installer |
| AGP 8.11.1 / Kotlin 2.2.10 / AndroidX / JitPack artifacts | `maven.google.com`, `repo.maven.apache.org`, `repo1.maven.org`, `plugins.gradle.org`, `jitpack.io` all unreachable |
| GitHub LFS mirrors of Gradle/JDK archives | only pointer files are reachable; `media.githubusercontent.com` and `objects.githubusercontent.com` are blocked |

Commands actually attempted, with `JAVA_HOME=/tmp/jdk17/package/jre`:

```
./gradlew :app_pojavlauncher:assembleDebug --no-daemon
./gradlew :app_pojavlauncher:assembleNoruntimeDebug --no-daemon
./gradlew :app_pojavlauncher:assembleDebug --offline --no-daemon
```

All three fail identically before any project configuration or Java compilation:

```
Downloading https://services.gradle.org/distributions/gradle-8.14.5-bin.zip
Exception in thread "main" javax.net.ssl.SSLHandshakeException:
Remote host terminated the handshake
```

`--offline` still attempts the download because no Gradle distribution is cached. No
source-code or Gradle-script error was reached, and no error was "fixed" by removing a feature
or weakening project configuration. The two baseline compile-blocking imports fixed in
`c6f274f` remain fixed; static parsing of all 334 Java files passes, but that is **not** a
substitute for a real `javac`/AGP compile.

Both `full` and `noruntime` variants were therefore not built. No task such as
`:app_pojavlauncher:assembleDebug` produced a `BUILD SUCCESSFUL`.

---

## 7. Generated APK, file size and exact path

| Item | Result |
| --- | --- |
| APK | **not generated** |
| File size | N/A |
| Exact path | N/A — no `app_pojavlauncher/build/` output directory exists |
| Expected path if built elsewhere | `app_pojavlauncher/build/outputs/apk/full/debug/` and `app_pojavlauncher/build/outputs/apk/noruntime/debug/` |
| Package ID / version / ABIs / debug signature | not verifiable without an APK |
| Installability | not verifiable without an APK or device |

Because no APK can be attached, the following retrieval artifacts are provided in the
workspace instead (they are ignored by Git, see `dist/` in `.gitignore`):

| File | Contents |
| --- | --- |
| `dist/ultra-lite-optimization.patch` | complete `git diff --binary 5431df4..HEAD` patch |
| `dist/pojavlauncher-ultra-lite-source.tar.gz` | `git archive` of the final tracked tree (submodules are separate repos; initialize them after extraction) |
| `dist/commits.txt` | exact commit list and hashes |
| `dist/verify_static.log` | captured final static verifier output |
| `dist/build_attempt.log` | captured Gradle failure output |
| `dist/gradle_noruntime.log`, `dist/gradle_offline.log` | the two additional build attempts |

These are delivery artifacts, not build outputs. No file here is an installable APK.

---

## 8. GitHub publishing status

* The branch and remote were re-verified before publishing: local
  `arena/7535fc36-pojavlauncher`, remote `origin` = the user's fork.
* `git status` was clean before `git add`; `git diff` was inspected; `git diff --check` passed.
* Temporary files, caches, credentials and signing keys are not in the commits. JDK, npm and
  pip downloads live in `/tmp`, not in the repository. Generated delivery files live in ignored
  `dist/`.
* The final commits are pushed to `origin/arena/7535fc36-pojavlauncher`; local `HEAD` and
  remote `HEAD` match. `origin/v3_openjdk` remains at `5431df4`.
* No pull request was opened and no other branch was created or pushed.
* If a push had failed, `dist/ultra-lite-optimization.patch` and the source archive would be
  the fallback; pushing did not fail.

---

## 9. Operations that could not be performed and why

| Operation | Why |
| --- | --- |
| Create/switch/push `ultra-lite-optimization` | Arena fixes the session to `arena/7535fc36-pojavlauncher` and forbids other branches |
| Download Gradle 8.14.5 | `services.gradle.org` not in the allowed host list |
| Install Android SDK Platform 36 / build-tools / platform-tools | `dl.google.com` not reachable, no local SDK |
| Install NDK `29.0.14206865` | `dl.google.com` not reachable, no local NDK |
| Install CMake / Ninja | no reachable installer; not present |
| Resolve AGP / Kotlin / AndroidX / JitPack dependencies | Google Maven, Maven Central, Gradle Plugin Portal and JitPack unreachable |
| Compile Java/Kotlin, merge resources, package or sign an APK | blocked by the above before Gradle configuration |
| Verify APK package ID, version, ABIs, debug signature, installability | no APK exists |
| ADB / emulator / device tests and runtime RAM/CPU measurements | no `adb`, no device, no emulator; explicitly the user's responsibility |
| Optional Fabric performance-mod installer | still deferred; renderer/Sodium compatibility and version matching cannot be validated here |

No performance numbers (RAM, CPU, JVM heap, native heap, graphics memory, APK size) were
invented. The only measured values in this project are static source/XML/resource counts.

---

## 10. Remaining risks that still require verification

1. **Compilation is unverified.** Static parsing passed, but no `javac`/AGP/Kotlin compile ran.
   A real build on a machine with JDK 17, Android SDK 36, NDK 29.0.14206865, CMake and
   dependency access is still required.
2. **All runtime behavior is unverified**: startup, Microsoft auth, sessions, version
   selection, vanilla/Fabric/Forge launch, runtime selection, game directory, settings
   preservation, touch/keyboard/controller input, renderer selection, shutdown, return to
   launcher, profile switching, custom JVM args, Android 5–16 behavior and APK installation.
3. **The new fixes are logically and statically reviewed but not device-tested**: janitor
   concurrency, immediate profile-switch UI refresh, final download progress report,
   monotonic progress timing, heap suggestion clamping and `ProgressLayout` cache resets.
4. **`CustomSeekBarPreference` range behavior** should be checked on a device after tapping
   "Apply recommended heap size"; the value is now clamped to the same computed maximum, but
   the preference's own listener still runs before its internal value updates on ordinary
   slider changes (pre-existing behavior).
5. **`translatable="false"` on `<string-array>`** parses with the static XML checker and is
   standard Android resource markup, but has not been validated by `aapt2` here.
6. **Gradle dependency removal** (`javax.annotation:javax.annotation-api` etc.) is statically
   clean and no bundled artifact references it, but only a real dependency-resolution/compile
   pass can prove no transitive API needs it.
7. **Known retained `ExtraCore` release table** and default `logcat` capture remain, as
   documented in `docs/ULTRA_LITE_REPORT.md`; disabling logcat is Custom-only and off by
   default.
8. **Third-party AARs and all four ABIs** remain by design; APK size was not measured.
9. **Localization**: new profile strings are English-only; existing translations are intact.
10. **Optional Fabric installer** remains unimplemented for the compatibility reasons already
    documented; no mod files are bundled and nothing is silently installed.

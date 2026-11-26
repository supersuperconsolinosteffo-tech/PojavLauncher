# PojavLauncher debug APK build and verification

Both debug variants were built directly in the working environment from the final committed source state (`7017185`). The official toolchain was obtained through the verified GitHub/git transfer channel because the usual Gradle, Google Maven, Maven Central and JitPack hosts were unreachable from this environment. Every transferred archive and part was SHA-256 checked before use; no third-party prebuilt APK was used as the deliverable.

Toolchain actually used for the final builds:

- Gradle `8.14.5` (revision `62345becae08b13e793521816d585102fea66398`)
- JDK `17.0.8`
- Android SDK Platform 36 revision 2, build-tools `35.0.0` and `36.0.0`
- NDK `29.0.14206865`, CMake `3.22.1-g37088a8`, Ninja `1.10.2`
- initialized submodules at the commits recorded by the repository
- verified Gradle dependency cache and remote assets
- local-only Gradle init script compiling the Java 8 submodule with JDK 17 in `--release 8` mode; project/submodule configuration was not weakened

Commands completed successfully from the final branch state:

- `gradle :app_pojavlauncher:assembleNoruntimeDebug --offline --no-daemon --console=plain` — `BUILD SUCCESSFUL in 37s`
- `gradle :app_pojavlauncher:assembleFullDebug --offline --no-daemon --console=plain` — `BUILD SUCCESSFUL in 36s`
- `python3 scripts/verify_static.py` — 0 strict errors, 0 unused local resources

Locally produced APKs are in `dist/` and in the standard Gradle output directories. Verification performed with Android SDK build tools:

- ZIP integrity: passed for both APKs
- `apksigner verify`: exit 0 for both APKs
- `zipalign -c -v 4`: exit 0 for both APKs
- package: `net.kdt.pojavlaunch.debug`
- minSdk `23`, targetSdk `36`, compileSdk `36`
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- debug signer: `CN=Android Debug, O=Android, C=US`
- signer SHA-256: `d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`

Final APKs:

| Variant | Size | SHA-256 |
|---|---:|---|
| `dist/app_pojavlauncher-full-debug.apk` | 115,702,122 bytes | `20a1a943b99f44b3915cd52fc712b5fc9127f762f8c4daf726fa2aafdc738668` |
| `dist/app_pojavlauncher-noruntime-debug.apk` | 86,591,332 bytes | `9b5e416626fc30e636d2df63a88d4eb064fe8d0560798c83305b005196b48c10` |

Exact per-APK check results and build logs are in `dist/APK_SUMMARY.txt`, `dist/APK_VERIFICATION.txt`, and `dist/local-*-build.log`. Physical-device installation and runtime testing were not performed in this environment; that remains the only user-side validation.

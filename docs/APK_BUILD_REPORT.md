# PojavLauncher debug APK build and verification

Both debug variants were built directly in the working environment with the official toolchain transferred through the verified GitHub/git channel and checked locally:

- Gradle `8.14.5` (revision `62345becae08b13e793521816d585102fea66398`)
- Temurin/OpenJDK-style JDK 17 locally available as `17.0.8`
- Android SDK Platform 36 revision 2, build-tools `35.0.0` and `36.0.0`
- NDK `29.0.14206865`, CMake `3.22.1-g37088a8`, Ninja `1.10.2`
- submodules initialized at the commits recorded by the repository
- Gradle dependency cache and remote assets verified with SHA-256 manifests before use

Commands completed successfully:

- `gradle :app_pojavlauncher:assembleNoruntimeDebug --offline --no-daemon --console=plain` — `BUILD SUCCESSFUL in 3m 6s`
- `gradle :app_pojavlauncher:assembleFullDebug --offline --no-daemon --console=plain` — `BUILD SUCCESSFUL in 1m 35s`
- `python3 scripts/verify_static.py` — 0 strict errors, 0 unused local resources

Locally produced APKs are placed in `dist/` and in the standard Gradle output directories. Verification performed with Android SDK build tools:

- ZIP integrity: passed for both APKs
- `apksigner verify`: exit 0 for both APKs
- `zipalign -c -v 4`: exit 0 for both APKs
- package: `net.kdt.pojavlaunch.debug`
- minSdk `23`, targetSdk `36`, compileSdk `36`
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- debug signer: `CN=Android Debug, O=Android, C=US`
- signer SHA-256: `d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`

Exact sizes, SHA-256 hashes, version names and per-APK check results are in `dist/APK_SUMMARY.txt` and `dist/APK_VERIFICATION.txt`. Physical-device installation and runtime testing were not performed in this environment; that remains the only user-side validation.

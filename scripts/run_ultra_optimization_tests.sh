#!/usr/bin/env bash
# Compile and run the offline Ultra-Optimization tests with a plain JDK.
#
# Only the Android-free part of the feature is compiled here (models, detector, matrix,
# resolver, per-installation state), plus the offline Modrinth fixtures. The Android classes
# are compiled by the Gradle build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/ultra-optimization-tests"
PKG="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ultraoptimization"
GSON="$ROOT/app_pojavlauncher/libs/gson-2.8.6.jar"
TEST="$ROOT/scripts/ultra_optimization_tests/UltraOptimizationResolverTest.java"

rm -rf "$OUT"
mkdir -p "$OUT"

CORE=(
  "$PKG/UltraOptimizationModels.java"
  "$PKG/UltraOptimizationDetector.java"
  "$PKG/UltraOptimizationMatrix.java"
  "$PKG/UltraOptimizationResolver.java"
  "$PKG/UltraOptimizationState.java"
)

echo "javac --release 8: ${#CORE[@]} core files + 1 test file"
javac -Xlint:all \
  --release 8 \
  -cp "$GSON" \
  -d "$OUT" \
  "${CORE[@]}" "$TEST"

echo "running UltraOptimizationResolverTest"
java -cp "$OUT:$GSON" UltraOptimizationResolverTest

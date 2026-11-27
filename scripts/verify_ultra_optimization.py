#!/usr/bin/env python3
"""Static safety checks for Ultra-Optimization and for Ultra-Lite preservation.

Runs without a JDK or an Android SDK. It verifies:
  * every protected Ultra-Lite / build file is byte-identical to the reviewed baseline
    (git blob SHA-1 of 446d4a62f886c52b4321c2cb4198128fbc5ce33f);
  * the Ultra-Optimization feature is present, self-contained and on-demand only;
  * the feature never writes launcher preferences, so the four Ultra-Lite /
    Ultra-Optimization combinations stay independent;
  * the new resources and layouts are well-formed and reference only resources that exist;
  * no mod version is hardcoded in the candidate metadata.
"""

import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app_pojavlauncher", "src", "main")
JAVA = os.path.join(MAIN, "java")
RES = os.path.join(MAIN, "res")
FEATURE = os.path.join(JAVA, "net", "kdt", "pojavlaunch", "ultraoptimization")
FEATURE_PKG = "net/kdt/pojavlaunch/ultraoptimization"

# Git blob SHA-1 values at the reviewed baseline (446d4a6). These files are the existing
# Ultra-Lite implementation, its call sites, or build/config safety boundaries.
PROTECTED = {
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/PerformanceProfile.java': 'c1e07f1f0c2b5b15de0f94dd2f9b85081c0a04ac',
    'app_pojavlauncher/src/main/res/values/perf_profiles.xml': '106aae163d5e14577fe320228532731c7d2a60ea',
    'app_pojavlauncher/src/main/res/xml/pref_misc.xml': '78e738e924860d5373e21071c35b1115c3e5c0ff',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/screens/LauncherPreferenceMiscellaneousFragment.java': 'd31f24ae07a56a4af5cafacd5303712c7f176df9',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/LauncherActivity.java': '69bdf40d8f4bd11103cbecdf259a6cfdfb272afc',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/downloader/Downloader.java': '1a062639f370802217ec8f73427c4bb95309ebad',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/modloaders/modpacks/imagecache/IconCacheJanitor.java': '09bf2d8a0cb281b3ba3c1f8d6a115517499c7552',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/modloaders/modpacks/imagecache/ModIconCache.java': 'bbdc9a758d1ad6739dccc555c6d7ce2b04d55fc8',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java': '99d06db75a255d4f9d4f9ee91053b3da4cec562a',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/screens/LauncherPreferenceJavaFragment.java': '4c58ef18fcab6d3a2266e52448c94f0c1b0ebd56',
    'app_pojavlauncher/src/main/res/xml/pref_java.xml': '33593289b85c2819c8cb7846942c334c5d143679',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/LauncherPreferences.java': 'd4413c76170d2907344733492910f7fbe8d0931b',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/MemoryUtils.java': 'f00bde0287064e07a3ab3d2bfaee6473e71bcd19',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/jre/GameRunner.java': '0172a5751eb25c4d328ac88b920fa3279d9902f5',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java': '4322c9c6a86c8f0d8fc5b75cff63314de45dafbe',
    'app_pojavlauncher/src/main/java/com/kdt/mcgui/ProgressLayout.java': '10b08230b927012a31c8f73d1fa28fc04d9af84b',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/RTRecyclerViewAdapter.java': '1aca9344fc569f5b5fba09fd136c0dcc9c7eb1d2',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/screens/MobileGluesMultidrawOrderFragment.kt': '6051ccdb8a8a0e8b9fc7a6ab80065a37046a43e2',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/vulkan/ui/DriverViewAdapter.java': '229b4f49810b29d608fa2ecdac550408b01ad145',
    'app_pojavlauncher/build.gradle': '956a73936466361e656aaa356bfd98e177661af5',
    'app_pojavlauncher/src/main/AndroidManifest.xml': '4df56b5ed2cb016342ae065cf5e6b903144cde97',
    '.gitignore': 'ed47f84fec20d67e8eb4ade541c6d12b675f2b07',
    'gradle.properties': 'd3bacf9dcfb490b17000e53722e65942e1ab275b',
    'settings.gradle': '5c91f0815ffafb5033a602cc1591ee790100e7c4',
    'build.gradle': 'cdf72e531b9dfa4a89ca3aabfabace5c86ba235c',
}

FEATURE_FILES = [
    'UltraOptimizationModels.java',
    'UltraOptimizationDetector.java',
    'UltraOptimizationMatrix.java',
    'UltraOptimizationResolver.java',
    'ModrinthClient.java',
    'UltraOptimizationScanner.java',
    'UltraOptimizationState.java',
    'UltraOptimizationInstaller.java',
    'UltraOptimizationFragment.java',
    'UltraOptimizationPreference.java',
]

REQUIRED_PATHS = [
    'app_pojavlauncher/src/main/res/layout/fragment_ultra_optimization.xml',
    'app_pojavlauncher/src/main/res/values/ultra_optimization_strings.xml',
    'app_pojavlauncher/src/main/res/xml/pref_main.xml',
    'scripts/ultra_optimization_tests/UltraOptimizationResolverTest.java',
    'scripts/run_ultra_optimization_tests.sh',
    'docs/ULTRA_OPTIMIZATION_PLAN.md',
]

FORBIDDEN_FEATURE_PATTERNS = [
    (r'\bPerformanceProfile\b', 'couples to the existing Ultra-Lite implementation'),
    (r'LauncherPreferenceMiscellaneousFragment', 'couples to the Ultra-Lite settings screen'),
    (r'put(String|Boolean|Int|Long|Float)\s*\(\s*["\']performanceProfile["\']', 'writes the Ultra-Lite preference'),
    (r'\.edit\s*\(\s*\)', 'writes launcher SharedPreferences at all'),
    (r'postDelayed\s*\(', 'uses a delayed polling loop'),
    (r'\bFileObserver\b', 'uses permanent filesystem observation'),
    (r'\bAlarmManager\b', 'uses scheduled background work'),
    (r'\bWorkManager\b', 'uses scheduled background work'),
    (r'\bJobScheduler\b', 'uses scheduled background work'),
    (r'newScheduledThreadPool|scheduleAtFixedRate|scheduleWithFixedDelay', 'schedules recurring work'),
    (r'\bnew Thread\b|\bTimer\s*\(', 'spawns its own background thread or timer'),
    (r'extends\s+(android\.app\.)?Service\b', 'declares a background service'),
    (r'registerReceiver', 'registers a background receiver'),
    (r'https?://api\.curseforge', 'queries a non-Modrinth API'),
    (r'\.mrpack|ModpackApi|installModpack|searchMod\(', 'handles modpacks instead of single mods'),
    (r'http://', 'uses cleartext HTTP'),
]

# Rules that must be visible in the source, each mapped to where they are checked.
REQUIRED_SNIPPETS = {
    'UltraOptimizationResolver.java': [
        ('exact Minecraft version', 'containsExact(environment.minecraftVersion, version.game_versions)'),
        ('release/beta only', '"release", "beta"'),
        ('reject unlisted status', 'ACCEPTED_VERSION_STATUS'),
        ('required dependencies', '"required"'),
        ('optional deps not installed', '"optional"'),
        ('incompatible deps', '"incompatible"'),
        ('hash required', 'No SHA-512 or SHA-1 hash'),
        ('jar required', 'No primary JAR was published'),
        ('Modrinth host pinned', 'cdn.modrinth.com'),
        ('cost budget', 'budgetCount'),
    ],
    'UltraOptimizationDetector.java': [
        ('GL4ES skip', 'GL4ES'),
        ('LTW allow', 'LTW_RENDERER'),
        ('never switch renderer', 'no renderer change is made'),
    ],
    'UltraOptimizationInstaller.java': [
        ('staged download', '.part'),
        ('hash verification', 'Integrity check failed'),
        ('backup before overwrite', 'backupDirectory'),
        ('no overwrite without confirmation', 'nothing was overwritten'),
        ('restore checks ownership', 'matchesHash'),
    ],
    'UltraOptimizationScanner.java': [
        ('refuse shared data', 'instance.sharedData'),
        ('renderer read only', 'getLaunchRenderer'),
        ('runtime read only', 'getSelectedRuntime'),
    ],
    'UltraOptimizationState.java': [
        ('per-installation path', '.ultra_optimization'),
        ('state file name', 'state.json'),
    ],
    'UltraOptimizationMatrix.java': [
        ('OFF/OFF', 'Normal launcher'),
        ('ON/OFF', 'Lightweight launcher'),
        ('OFF/ON', 'Minecraft optimized through mods'),
        ('ON/ON', 'Both optimizations simultaneously'),
        ('independent domains', 'independentStateDomains'),
    ],
}


def git_blob_sha1(path):
    with open(path, 'rb') as handle:
        data = handle.read()
    header = b'blob ' + str(len(data)).encode('ascii') + b'\0'
    return hashlib.sha1(header + data).hexdigest()


def read(path):
    with open(path, encoding='utf-8', errors='replace') as handle:
        return handle.read()


def collect_resource_names(errors):
    """Every @string and @drawable the new files reference must exist in this module."""
    names = {'string': set(), 'drawable': set(), 'color': set(), 'dimen': set()}
    kinds = {'string': ('string', 'string-array', 'plurals'),
             'drawable': ('item',),
             'color': ('color',),
             'dimen': ('dimen',)}
    for directory in sorted(os.listdir(RES)):
        values_dir = os.path.join(RES, directory)
        if not os.path.isdir(values_dir) or not directory.startswith('values'):
            continue
        for name in sorted(os.listdir(values_dir)):
            if not name.endswith('.xml'):
                continue
            path = os.path.join(values_dir, name)
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as e:
                errors.append('unparsable resource %s: %s' % (os.path.relpath(path, ROOT), e))
                continue
            for child in root:
                resource_name = child.get('name')
                if not resource_name:
                    continue
                if child.tag in kinds['string'] and child.tag != 'item':
                    names['string'].add(resource_name)
                if child.tag == 'color':
                    names['color'].add(resource_name)
                if child.tag == 'dimen':
                    names['dimen'].add(resource_name)
                if child.tag == 'item' and child.get('type') in ('drawable', 'color'):
                    names[child.get('type')].add(resource_name)
    for directory in ('drawable', 'drawable-v24', 'mipmap-anydpi-v26'):
        path = os.path.join(RES, directory)
        if os.path.isdir(path):
            for name in os.listdir(path):
                base, _ = os.path.splitext(name)
                if os.path.isdir(os.path.join(path, name)):
                    names['drawable'].add(name)
                elif name.endswith('.xml') or name.endswith('.png') or name.endswith('.webp'):
                    names['drawable'].add(base)
    return names


def check_new_file_references(errors, resources):
    new_sources = {
        'layout': os.path.join(RES, 'layout', 'fragment_ultra_optimization.xml'),
        'strings': os.path.join(RES, 'values', 'ultra_optimization_strings.xml'),
        'pref_main': os.path.join(RES, 'xml', 'pref_main.xml'),
    }
    java_files = []
    if os.path.isdir(FEATURE):
        java_files = [os.path.join(FEATURE, n) for n in sorted(os.listdir(FEATURE))
                      if n.endswith('.java')]

    layout_path = new_sources['layout']
    layout_ids = set()
    if os.path.isfile(layout_path):
        try:
            ET.parse(layout_path).getroot()
        except ET.ParseError as e:
            errors.append('unparsable layout fragment_ultra_optimization.xml: %s' % e)
        text = read(layout_path)
        layout_ids = set(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))
        for kind, referenced in re.findall(r'@(string|color|dimen|drawable)/([A-Za-z0-9_]+)', text):
            if referenced in names_for(resources, kind):
                continue
            # _<n>ssp / _<n>sdp are generated by the portrait-sdp/portrait-ssp library
            # (see app_pojavlauncher/build.gradle) and are not declared in this module.
            if kind == 'dimen' and re.fullmatch(r'_\d+s[sd]p', referenced):
                continue
            errors.append('layout references missing @%s/%s' % (kind, referenced))

    # R.* references from the new Java sources (android.R.* is a framework resource)
    for path in java_files:
        text = read(path)
        app_r = re.sub(r'android\.R\.', 'frameworkR.', text)
        for kind, member in re.findall(r'(?<![\w.])R\.(string|drawable|color|dimen|layout|id)\.([A-Za-z0-9_]+)', app_r):
            if kind == 'layout':
                if not os.path.isfile(os.path.join(RES, 'layout', member + '.xml')):
                    errors.append('%s references missing layout R.layout.%s'
                                  % (os.path.basename(path), member))
            elif kind == 'id':
                if member not in layout_ids:
                    errors.append('%s references missing view id R.id.%s'
                                  % (os.path.basename(path), member))
            elif member not in names_for(resources, kind):
                errors.append('%s references missing R.%s.%s'
                              % (os.path.basename(path), kind, member))

    # getString("...") / preference keys used from XML
    if os.path.isfile(new_sources['pref_main']):
        pref_main = read(new_sources['pref_main'])
        if 'ultra_optimization_screen' not in pref_main:
            errors.append('pref_main.xml has no ultra_optimization_screen entry')
        if 'UltraOptimizationPreference' not in pref_main:
            errors.append('pref_main.xml does not link the Ultra-Optimization screen')
        try:
            ET.parse(new_sources['pref_main']).getroot()
        except ET.ParseError as e:
            errors.append('unparsable pref_main.xml: %s' % e)


def names_for(resources, kind):
    return resources.get(kind, set())


def main():
    errors = []
    warnings = []

    for relative, expected in sorted(PROTECTED.items()):
        path = os.path.join(ROOT, relative)
        if not os.path.isfile(path):
            errors.append('protected file missing: %s' % relative)
            continue
        actual = git_blob_sha1(path)
        if actual != expected:
            errors.append('protected file changed: %s (%s != %s)' % (relative, actual, expected))

    for name in FEATURE_FILES:
        if not os.path.isfile(os.path.join(FEATURE, name)):
            errors.append('required feature file missing: %s/%s' % (FEATURE_PKG, name))
    for relative in REQUIRED_PATHS:
        if not os.path.isfile(os.path.join(ROOT, relative)):
            errors.append('required file missing: %s' % relative)

    feature_sources = []
    if os.path.isdir(FEATURE):
        for name in sorted(os.listdir(FEATURE)):
            if name.endswith(('.java', '.kt')):
                feature_sources.append(os.path.join(FEATURE, name))
        if not feature_sources:
            errors.append('feature package empty: %s' % FEATURE)

    joined = '\n'.join(read(path) for path in feature_sources)
    for pattern, description in FORBIDDEN_FEATURE_PATTERNS:
        if re.search(pattern, joined):
            match = re.search(pattern, joined)
            line = joined[:match.start()].count('\n') + 1 if match else 0
            errors.append('feature source %s (near line %d of the joined package)'
                          % (description, line))

    for filename, snippets in REQUIRED_SNIPPETS.items():
        path = os.path.join(FEATURE, filename)
        if not os.path.isfile(path):
            continue
        text = read(path)
        for label, snippet in snippets:
            if snippet not in text:
                errors.append('%s is missing the %s rule (%s)' % (filename, label, snippet))

    # No pinned mod versions anywhere in the candidate metadata or the resolver.
    for filename in ('UltraOptimizationModels.java', 'UltraOptimizationResolver.java'):
        path = os.path.join(FEATURE, filename)
        if not os.path.isfile(path):
            continue
        pinned = re.findall(r'"(\d+\.\d+\.\d+)"', read(path))
        if pinned:
            errors.append('%s pins a mod version literally: %s' % (filename, ', '.join(sorted(set(pinned)))))

    # Candidate list must be exactly the investigated set, and must be a candidate list only.
    if os.path.isfile(os.path.join(FEATURE, 'UltraOptimizationModels.java')):
        models = read(os.path.join(FEATURE, 'UltraOptimizationModels.java'))
        for slug in ('ferrite-core', 'lithium', 'sodium', 'modernfix', 'immediatelyfast',
                     'entityculling', 'moreculling', 'memoryleakfix'):
            if '"%s"' % slug not in models:
                errors.append('candidate %s is missing from the investigated list' % slug)

    # Modrinth client contract
    client_path = os.path.join(FEATURE, 'ModrinthClient.java')
    if os.path.isfile(client_path):
        client = read(client_path)
        for label, snippet in (('official API base', 'https://api.modrinth.com/v2'),
                               ('User-Agent header', 'User-Agent'),
                               ('bounded cache', 'removeEldestEntry'),
                               ('timeout', 'setConnectTimeout')):
            if snippet not in client:
                errors.append('ModrinthClient.java is missing the %s (%s)' % (label, snippet))

    # Pure core must stay free of Android imports so it can be compiled and tested by a JDK alone.
    pure_core = ('UltraOptimizationModels.java', 'UltraOptimizationDetector.java',
                 'UltraOptimizationMatrix.java', 'UltraOptimizationResolver.java',
                 'UltraOptimizationState.java')
    for filename in pure_core:
        path = os.path.join(FEATURE, filename)
        if not os.path.isfile(path):
            continue
        text = read(path)
        if re.search(r'^import android\.', text, re.M) or 'com.google' in text and filename != 'UltraOptimizationState.java':
            errors.append('%s is not Android-free' % filename)
        if filename != 'UltraOptimizationState.java' and re.search(r'^import androidx\.', text, re.M):
            errors.append('%s imports androidx, breaking the JDK-only test harness' % filename)

    resources = collect_resource_names(errors)
    check_new_file_references(errors, resources)

    # The settings entry must exist, and Ultra-Lite's own screen must not have gained a link.
    misc = read(os.path.join(RES, 'xml', 'pref_misc.xml'))
    if 'ultra_optimization' in misc:
        errors.append('pref_misc.xml (Ultra-Lite screen) references Ultra-Optimization')

    print('Ultra-Optimization static safety verification')
    print('  protected baseline files : %d' % len(PROTECTED))
    print('  feature sources          : %d' % len(feature_sources))
    print('  errors                   : %d' % len(errors))
    for error in errors:
        print('  ERROR ' + error)
    for warning in warnings:
        print('  WARN  ' + warning)
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())

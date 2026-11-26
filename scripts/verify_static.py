#!/usr/bin/env python3
"""Static regression checks for the Ultra-Lite cleanup.

Runs without a JDK or Android SDK. Usage:

    python3 scripts/verify_static.py            # all checks
    python3 scripts/verify_static.py --summary # counts only

Strict checks (non-zero exit code):
  * XML files under res/ and AndroidManifest.xml are well formed
  * internal net.kdt.pojavlaunch.* imports resolve to a source file
  * local resource references (@drawable/, @layout/, @menu/, @xml/, @font/,
    @mipmap/, R.<same>.<name>) resolve to a file in res/

Informational:
  * unused drawables/layouts/menus/xml/fonts
  * Java syntax, parsed with javalang when it is installed
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app_pojavlauncher", "src", "main")
SRC = os.path.join(MAIN, "java")
RES = os.path.join(MAIN, "res")
MANIFEST = os.path.join(MAIN, "AndroidManifest.xml")

TEXT_EXT = (".java", ".kt", ".xml", ".gradle", ".properties", ".pro", ".json",
            ".txt", ".aidl", ".md")
STRICT_RES_TYPES = ("drawable", "layout", "menu", "xml", "font", "mipmap")
GENERATED = ("R", "BuildConfig", "databinding")

# Resources supplied by the Android framework or by bundled AARs. They are
# referenced by name in this module but do not exist in this module's res/.
EXTERNAL_RESOURCES = {
    "simple_list_item_1", "simple_list_item_single_choice", "simple_spinner_item",
    "simple_spinner_dropdown_item", "support_simple_spinner_dropdown_item",
    "simple_expandable_list_item_1", "ic_menu_close_clear_cancel",
    "button_a", "button_b", "button_x", "button_y", "button_start", "button_select",
    "trigger_left", "trigger_right", "shoulder_left", "shoulder_right",
    "stick_left", "stick_right", "stick_left_click", "stick_right_click",
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
}


def walk_text(base, exts=TEXT_EXT):
    for directory, _, names in os.walk(base):
        if "/build" in directory or "/.git" in directory:
            continue
        for name in names:
            if name.endswith(exts):
                path = os.path.join(directory, name)
                try:
                    with open(path, encoding="utf-8", errors="replace") as handle:
                        yield path, handle.read()
                except OSError:
                    pass


def source_files():
    return {path: text for path, text in walk_text(SRC, (".java", ".kt"))}


def corpus():
    files = list(walk_text(SRC))
    files += list(walk_text(RES))
    files += list(walk_text(os.path.join(ROOT, "app_pojavlauncher"), (".gradle", ".pro")))
    files += list(walk_text(MAIN, (".aidl", ".xml")))
    seen, out = set(), []
    for path, text in files:
        if path not in seen:
            seen.add(path)
            out.append((path, text))
    return out


def rel(path):
    return os.path.relpath(path, ROOT)


def check_xml():
    errors = []
    xml_files = [MANIFEST]
    for directory, _, names in os.walk(RES):
        for name in names:
            if name.endswith(".xml"):
                xml_files.append(os.path.join(directory, name))
    for path in xml_files:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append("%s: %s" % (rel(path), exc))
    return errors, len(xml_files)


def known_source_classes(sources):
    known = set()
    for path in sources:
        name = os.path.relpath(path, SRC)
        known.add(name.rsplit(".", 1)[0].replace(os.sep, "."))
    return known


def check_imports(sources):
    errors = []
    known = known_source_classes(sources)
    pattern = re.compile(r"^import\s+(?:static\s+)?(net\.kdt\.pojavlaunch\.[\w.]+);", re.M)
    for path, text in sources.items():
        for match in pattern.finditer(text):
            imported = match.group(1)
            parts = imported.split(".")
            if parts[-1] in GENERATED or "R." in imported:
                continue
            if imported in known:
                continue
            # nested class or static member import
            if any(".".join(parts[:i]) in known for i in range(4, len(parts))):
                continue
            errors.append("%s: unresolved import %s" % (rel(path), imported))
    return errors


def local_resources():
    found = {kind: set() for kind in STRICT_RES_TYPES}
    for directory, _, names in os.walk(RES):
        folder = os.path.basename(directory).split("-")[0]
        if folder not in found:
            continue
        for name in names:
            base = name.split(".", 1)[0]
            # 9-patch and other qualified file names
            found[folder].add(base)
    return found


def check_resource_references(texts, resources):
    errors = []
    for path, text in texts:
        for kind in STRICT_RES_TYPES:
            for regex in (r"@" + kind + r"/([\w.]+)",
                          r"(?<![\w.])R\." + kind + r"\.([\w]+)"):
                for match in re.finditer(regex, text):
                    name = match.group(1)
                    if name in EXTERNAL_RESOURCES or name == "null":
                        continue
                    if name not in resources[kind]:
                        errors.append("%s: missing @%s/%s" % (rel(path), kind, name))
    return errors


def find_unused(texts, resources):
    unused = {kind: [] for kind in STRICT_RES_TYPES}
    joined = "\n".join(text for _, text in texts)
    for directory, _, names in os.walk(RES):
        folder = os.path.basename(directory).split("-")[0]
        if folder not in unused:
            continue
        for name in names:
            base = name.split(".", 1)[0]
            patterns = [r"R\." + folder + r"\." + re.escape(base) + r"\b",
                        r"@" + folder + r"/" + re.escape(base) + r"\b",
                        r"\"" + re.escape(base) + r"\""]
            if not any(re.search(pattern, joined) for pattern in patterns):
                unused[folder].append(rel(os.path.join(directory, name)))
    for kind in unused:
        unused[kind].sort()
    return unused


def check_java_syntax(sources):
    try:
        import javalang
    except ImportError:
        return None, 0
    errors, parsed = [], 0
    for path, text in sources.items():
        if not path.endswith(".java"):
            continue
        try:
            javalang.parse.parse(text)
            parsed += 1
        except Exception as exc:
            errors.append("%s: %s" % (rel(path), exc))
    return errors, parsed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", action="store_true", help="print counts only")
    args = parser.parse_args()

    sources = source_files()
    texts = corpus()
    resources = local_resources()

    xml_errors, xml_count = check_xml()
    import_errors = check_imports(sources)
    resource_errors = check_resource_references(texts, resources)
    java_errors, parsed = check_java_syntax(sources)
    unused = find_unused(texts, resources)

    print("Ultra-Lite static verification")
    print("  Java/Kotlin sources : %d" % len(sources))
    print("  XML files parsed    : %d" % xml_count)
    print("  Java files parsed   : %s" % (parsed if java_errors is not None else "skipped (no javalang)"))
    print("  strict errors       : %d" % (len(xml_errors) + len(import_errors)
                                           + len(resource_errors) + len(java_errors or [])))
    if not args.summary:
        for label, errors in (("XML", xml_errors), ("imports", import_errors),
                              ("resources", resource_errors), ("Java syntax", java_errors or [])):
            for error in errors:
                print("  ERROR [%s] %s" % (label, error))
        total_unused = sum(len(items) for items in unused.values())
        print("  unused local resources: %d" % total_unused)
        for kind, items in unused.items():
            if items:
                print("    %s: %d" % (kind, len(items)))
                if len(items) <= 10:
                    for item in items:
                        print("      " + item)
    failed = xml_errors or import_errors or resource_errors or (java_errors or [])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

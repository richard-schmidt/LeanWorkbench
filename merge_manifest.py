#!/usr/bin/env python3
"""Minimal manifest merger for the hand-rolled build.

Merges each dependency AAR's <application> children and top-level
<uses-permission>/<uses-feature> into the app manifest, substituting
${applicationId}. <application> children with the same (tag, android:name)
are merged into one element with their <meta-data> unioned: several AARs
declare androidx.startup.InitializationProvider with one authority.
No tools:node semantics beyond match-by-name.
"""
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_ATTR = "{%s}" % ANDROID_NS


def strip_tools_attribs(elem):
    """Drop tools:* attributes -- they're manifest-merger-time instructions
    (e.g. tools:node="merge") that mean nothing to aapt2 and aren't
    implemented by this simplified merge anyway."""
    tools_ns = "{http://schemas.android.com/tools}"
    for key in [k for k in elem.attrib if k.startswith(tools_ns)]:
        del elem.attrib[key]
    for child in elem:
        strip_tools_attribs(child)


def substitute_application_id(elem, application_id):
    for key, value in list(elem.attrib.items()):
        if "${applicationId}" in value:
            elem.attrib[key] = value.replace("${applicationId}", application_id)
    for child in elem:
        substitute_application_id(child, application_id)


def identity_key(elem):
    """(tag, android:name) for provider/service/receiver/activity(-alias), as AGP
    matches them; None for anything else (always appended, never deduped)."""
    if elem.tag not in ("provider", "service", "receiver", "activity", "activity-alias"):
        return None
    name = elem.get(ANDROID_ATTR + "name")
    return (elem.tag, name) if name else None


def merge_application_child(app_application, existing_by_key, child):
    """Append `child`, or, if an element with its identity_key() exists, add
    child's children it lacks to that element instead (idempotent)."""
    key = identity_key(child)
    if key is not None and key in existing_by_key:
        existing = existing_by_key[key]
        existing_signatures = {ET.tostring(c) for c in existing}
        for grandchild in list(child):
            if ET.tostring(grandchild) not in existing_signatures:
                existing.append(grandchild)
        return False
    app_application.append(child)
    if key is not None:
        existing_by_key[key] = child
    return True


def main():
    app_manifest_path, deps_extracted_list_path, output_path = sys.argv[1:4]

    ET.register_namespace("android", ANDROID_NS)
    app_tree = ET.parse(app_manifest_path)
    app_root = app_tree.getroot()
    application_id = app_root.get("package")
    if not application_id:
        print("merge_manifest.py: app manifest has no package attribute", file=sys.stderr)
        sys.exit(1)

    app_application = app_root.find("application")
    if app_application is None:
        print("merge_manifest.py: app manifest has no <application> element", file=sys.stderr)
        sys.exit(1)

    existing_permissions = {
        el.get(ANDROID_ATTR + "name")
        for el in app_root.findall("uses-permission")
    }

    existing_by_key = {}
    for child in app_application:
        key = identity_key(child)
        if key is not None:
            existing_by_key[key] = child

    with open(deps_extracted_list_path) as f:
        dep_manifest_paths = [line.strip() for line in f if line.strip()]

    merged_count = 0
    for dep_manifest_path in dep_manifest_paths:
        try:
            dep_root = ET.parse(dep_manifest_path).getroot()
        except ET.ParseError as e:
            print(f"merge_manifest.py: skipping unparseable {dep_manifest_path}: {e}", file=sys.stderr)
            continue

        dep_application = dep_root.find("application")
        if dep_application is not None:
            for child in list(dep_application):
                strip_tools_attribs(child)
                substitute_application_id(child, application_id)
                merge_application_child(app_application, existing_by_key, child)
                merged_count += 1

        for perm in dep_root.findall("uses-permission"):
            name = perm.get(ANDROID_ATTR + "name")
            if name and name not in existing_permissions:
                strip_tools_attribs(perm)
                substitute_application_id(perm, application_id)
                app_root.insert(0, perm)
                existing_permissions.add(name)
                merged_count += 1

    app_tree.write(output_path, encoding="utf-8", xml_declaration=True)
    print(f"merge_manifest.py: merged {merged_count} element(s) from {len(dep_manifest_paths)} dependency manifest(s)")


if __name__ == "__main__":
    main()

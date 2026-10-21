#!/usr/bin/env bash
#
# Validate that every contract-tested AgentRuntime is actually *scaffoldable*
# and *resolvable*, not just present as a module.
#
# Drift #59 (2026-05-24): atmosphere-anthropic and atmosphere-cohere shipped
# (module + snapshot + contract test + README all green) but
# `atmosphere new my-app --runtime cohere` died with "Unknown runtime: cohere"
# because cli/runtime-overlays.json was never updated, and a hand-edited pom
# would have failed Maven resolution because bom/pom.xml had no entry either.
# The capability snapshot gate did not catch it — a runtime can be fully
# contract-tested and still be unscaffoldable.
#
# This gate closes that class structurally. For every runtime pinned in the
# capability snapshot it asserts:
#   1. an overlay entry exists in cli/runtime-overlays.json, and
#   2. the runtime's atmosphere-<x> artifact is declared in bom/pom.xml
#      (so a scaffolded pom resolves the version from the BOM).
# It also reverse-checks that no overlay names a runtime the snapshot does
# not know about (catches stale/typo overlay keys).
#
# The Built-in runtime is the documented exception: it is the default,
# ships inside atmosphere-ai (already on every AI sample), and its overlay
# carries no deps — so it is checked for overlay presence only, not BOM.
#
# Run from repo root. Exits 0 on success, 1 on any gap.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

SNAPSHOT=".harness/capabilities.snapshot.json"
OVERLAYS="cli/runtime-overlays.json"
BOM="bom/pom.xml"

for f in "$SNAPSHOT" "$OVERLAYS" "$BOM"; do
    if [ ! -f "$f" ]; then
        echo "validate-runtime-overlay-coverage.sh: $f not found" >&2
        exit 1
    fi
done

python3 - "$SNAPSHOT" "$OVERLAYS" "$BOM" <<'PY'
import json
import re
import sys
from pathlib import Path

snapshot = json.loads(Path(sys.argv[1]).read_text())
overlays = json.loads(Path(sys.argv[2]).read_text())
bom_text = Path(sys.argv[3]).read_text()

overlay_keys = set(overlays.get("overlays", {}).keys())
bom_artifacts = set(re.findall(r"<artifactId>(atmosphere-[a-z0-9-]+)</artifactId>", bom_text))

# Map a runtime's snapshot module path to its (overlay key, required BOM
# artifact). The default derivation — strip "modules/" → overlay key, and
# "atmosphere-<key>" → BOM artifact — holds for every framework adapter.
# The Built-in runtime is the only special case: its contract test lives in
# modules/ai-test, but it is the default runtime bundled in atmosphere-ai,
# scaffolded via the dependency-free "builtin" overlay.
SPECIAL = {
    "BuiltInAgentRuntime": {"overlay": "builtin", "bom_artifact": None},
}

failed = False
expected_overlay_keys = set()

for runtime in snapshot["runtimes"]["items"]:
    name = runtime["name"]
    basename = runtime["module"].split("/", 1)[1] if "/" in runtime["module"] else runtime["module"]
    special = SPECIAL.get(name)
    overlay_key = special["overlay"] if special else basename
    bom_artifact = special["bom_artifact"] if special else f"atmosphere-{basename}"
    expected_overlay_keys.add(overlay_key)

    if overlay_key not in overlay_keys:
        print(
            f"validate-runtime-overlay-coverage.sh: {name} ({runtime['module']}) "
            f"has no overlay '{overlay_key}' in cli/runtime-overlays.json — "
            f"`atmosphere new ... --runtime {overlay_key}` would fail with 'Unknown runtime'",
            file=sys.stderr,
        )
        failed = True

    if bom_artifact is not None and bom_artifact not in bom_artifacts:
        print(
            f"validate-runtime-overlay-coverage.sh: {name} ({runtime['module']}) "
            f"artifact '{bom_artifact}' is not declared in bom/pom.xml — "
            f"a scaffolded pom would fail Maven version resolution",
            file=sys.stderr,
        )
        failed = True

# Reverse check: an overlay that names no known runtime is stale or a typo.
for key in sorted(overlay_keys - expected_overlay_keys):
    print(
        f"validate-runtime-overlay-coverage.sh: cli/runtime-overlays.json "
        f"declares overlay '{key}' but no contract-tested runtime maps to it "
        f"(stale entry, or the snapshot is missing a runtime)",
        file=sys.stderr,
    )
    failed = True

# Version check: a third-party library an overlay adds must come at the
# version the adapter is built on. The Semantic Kernel overlay pinned 1.4.0
# while atmosphere-semantic-kernel was compiled against 1.5.0, and every tool
# turn of a scaffolded project died with an AbstractMethodError. So every
# third-party dep of an overlay must name its version as ${property}, the
# property must be defined in the root pom (the parent of a scaffolded
# project, which resolves it), the adapter module must not redefine it, and
# the adapter module must build that groupId on that same property.
def properties_of(pom_text):
    # The project's own block sits at four spaces; deeper ones belong to profiles.
    block = re.search(r"^    <properties>(.*?)^    </properties>", pom_text, re.S | re.M)
    if not block:
        return {}
    body = re.sub(r"<!--.*?-->", "", block.group(1), flags=re.S)
    return dict(re.findall(r"<([A-Za-z0-9_.\-]+)>([^<]*)</\1>", body))

def dependency_versions(pom_text):
    """(groupId, artifactId, version) of every <dependency> declaring a version."""
    found = []
    for dep in re.findall(r"<dependency>(.*?)</dependency>", re.sub(r"<!--.*?-->", "", pom_text, flags=re.S), re.S):
        group = re.search(r"<groupId>\s*([^<\s]+)\s*</groupId>", dep)
        artifact = re.search(r"<artifactId>\s*([^<\s]+)\s*</artifactId>", dep)
        version = re.search(r"<version>\s*([^<\s]+)\s*</version>", dep)
        if group and artifact and version:
            found.append((group.group(1), artifact.group(1), version.group(1)))
    return found

root_pom = Path(sys.argv[3]).resolve().parent.parent / "pom.xml"
root_properties = properties_of(root_pom.read_text())

for key, overlay in sorted(overlays.get("overlays", {}).items()):
    third_party = [d for d in overlay.get("deps", []) if d.get("groupId") != "org.atmosphere"]
    if not third_party:
        continue
    module_pom = root_pom.parent / "modules" / key / "pom.xml"
    if not module_pom.is_file():
        print(
            f"validate-runtime-overlay-coverage.sh: overlay '{key}' adds third-party deps "
            f"but modules/{key}/pom.xml does not exist to check their versions against",
            file=sys.stderr,
        )
        failed = True
        continue
    module_text = module_pom.read_text()
    module_properties = properties_of(module_text)
    module_deps = dependency_versions(module_text)
    for dep in third_party:
        coordinate = f"{dep['groupId']}:{dep['artifactId']}"
        version = dep.get("version")
        placeholder = re.fullmatch(r"\$\{([A-Za-z0-9_.\-]+)\}", version or "")
        if not placeholder:
            print(
                f"validate-runtime-overlay-coverage.sh: overlay '{key}' pins {coordinate} "
                f"to {version!r}; name the version as the ${{property}} the adapter module "
                f"builds on so the two cannot drift",
                file=sys.stderr,
            )
            failed = True
            continue
        prop = placeholder.group(1)
        if prop not in root_properties:
            print(
                f"validate-runtime-overlay-coverage.sh: overlay '{key}' versions {coordinate} "
                f"with ${{{prop}}}, which the root pom.xml does not define — a scaffolded "
                f"project could not resolve it",
                file=sys.stderr,
            )
            failed = True
            continue
        if prop in module_properties and module_properties[prop] != root_properties[prop]:
            print(
                f"validate-runtime-overlay-coverage.sh: modules/{key}/pom.xml redefines "
                f"{prop}={module_properties[prop]} while the root pom (what a scaffolded "
                f"project gets) says {root_properties[prop]}",
                file=sys.stderr,
            )
            failed = True
        built_on = {v for g, _, v in module_deps if g == dep["groupId"]}
        if version not in built_on:
            print(
                f"validate-runtime-overlay-coverage.sh: overlay '{key}' adds {coordinate} at "
                f"{version} (= {root_properties[prop]}), but modules/{key}/pom.xml builds "
                f"{dep['groupId']} on {sorted(built_on) or 'nothing'}",
                file=sys.stderr,
            )
            failed = True

if failed:
    sys.exit(1)

count = snapshot["runtimes"]["count"]
print(
    f"validate-runtime-overlay-coverage.sh: OK ({count} runtimes — "
    f"each has a CLI overlay and (where applicable) a BOM artifact)"
)
PY

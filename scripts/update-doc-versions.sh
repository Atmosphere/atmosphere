#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# update-doc-versions.sh — Update all documentation version references
#
# Usage: ./scripts/update-doc-versions.sh <release-version>
#   e.g.: ./scripts/update-doc-versions.sh 4.0.12
#
# Called automatically by the release workflow (release-4x.yml)
# and can be run manually before a release.
# ──────────────────────────────────────────────────────────────
set -euo pipefail

VERSION="${1:?Usage: $0 <release-version>}"
ROOT="$(git rev-parse --show-toplevel)"

# Validate version format
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$'; then
    echo "Error: Invalid version format: $VERSION"
    echo "Expected: X.Y.Z or X.Y.Z-qualifier"
    exit 1
fi

echo ""
echo "Updating documentation to version $VERSION"
echo "============================================"
echo ""

UPDATED=0

# Helper: portable sed -i (macOS vs Linux)
sedi() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "$@"
    else
        sed -i "$@"
    fi
}

# ── 1-3. Maven <version> tags for org.atmosphere deps in docs/*.md, module
#         README.md, and the root README.md ──
#
# Only bump a <version> that lives inside an Atmosphere <dependency>/<parent>
# block (groupId org.atmosphere, or — for BOM-style snippets that omit the
# groupId — an atmosphere-* artifactId). Third-party dependency examples
# (com.google.adk:google-adk, dev.langchain4j:*, io.grpc:*, org.sosy-lab:*,
# org.springframework.ai:*, ...) MUST keep their real upstream versions.
#
# A blanket `s|<version>X.Y.Z</version>|<version>$VERSION</version>|g` used to
# clobber every <version> tag here, stamping the Atmosphere release version onto
# third-party deps so readers copied coordinates that do not exist. The rule
# below mirrors scripts/validate-doc-thirdparty-versions.sh (the gate that
# catches the same drift), so generator and validator stay consistent.
echo "── Atmosphere <version> tags (docs/*.md, module README.md, root README.md)"
python3 - "$VERSION" "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

version = sys.argv[1]
root = Path(sys.argv[2])

files = []
docs = root / "docs"
if docs.is_dir():
    files += docs.rglob("*.md")
files += (root / "modules").rglob("README.md")
readme = root / "README.md"
if readme.exists():
    files.append(readme)

# A <dependency>/<parent> block (xml-fenced or inline), non-greedy.
BLOCK = re.compile(r"<(dependency|parent)>(.*?)</\1>", re.DOTALL | re.IGNORECASE)
GROUP = re.compile(r"<groupId>\s*([^<]+?)\s*</groupId>", re.IGNORECASE)
ARTIFACT = re.compile(r"<artifactId>\s*([^<]+?)\s*</artifactId>", re.IGNORECASE)
# A literal release version only — never clobber ${...} property placeholders.
VER = re.compile(
    r"(<version>\s*)\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?(\s*</version>)", re.IGNORECASE
)


def is_atmosphere(block):
    g = GROUP.search(block)
    if g:
        return g.group(1).strip().startswith("org.atmosphere")
    a = ARTIFACT.search(block)
    return bool(a and a.group(1).strip().startswith("atmosphere-"))


def bump_block(m):
    block = m.group(2)
    if not is_atmosphere(block):
        return m.group(0)
    new_block = VER.sub(rf"\g<1>{version}\g<2>", block)
    return m.group(0).replace(block, new_block, 1)


for f in sorted(set(files)):
    try:
        text = f.read_text()
    except (OSError, UnicodeDecodeError):
        continue
    new = BLOCK.sub(bump_block, text)
    if new != text:
        f.write_text(new)
        print(f"   {f.relative_to(root)}")
PY
# "Current release: `X.Y.Z[-qualifier]`" pattern — markdown inline code,
# not an XML tag. Release 4.0.36 shipped with 4.0.36-SNAPSHOT here because
# this line slipped the tag-only regex above.
if grep -q 'Current release:' "$ROOT/README.md" 2>/dev/null; then
    sedi -E "s|(Current release: \`)[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?(\`)|\1${VERSION}\3|" "$ROOT/README.md"
    echo "   README.md (Current release: inline code)"
fi

# ── 4. JAR artifact names in sample README.md ──
echo "── Sample README.md JAR references"
{ find "$ROOT/samples" -name 'README.md' -exec grep -lE 'atmosphere-[a-z-]+-[0-9]+\.[0-9]+\.[0-9]+' {} + 2>/dev/null || true; } | while read -r f; do
    sedi -E "s/(atmosphere-[a-z-]+)-[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT|-RC[0-9]+)?/\1-$VERSION/g" "$f"
    echo "   $f"
    UPDATED=$((UPDATED + 1))
done

# ── 5. CLI version strings ──
echo "── CLI version strings"
CLI_SCRIPT="$ROOT/cli/atmosphere"
if [ -f "$CLI_SCRIPT" ]; then
    sedi "s|^VERSION=\"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^\"]*\"|VERSION=\"$VERSION\"|" "$CLI_SCRIPT"
    echo "   cli/atmosphere"
fi

CLI_SAMPLES="$ROOT/cli/samples.json"
if [ -f "$CLI_SAMPLES" ]; then
    sedi "s|\"version\": \"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^\"]*\"|\"version\": \"$VERSION\"|" "$CLI_SAMPLES"
    echo "   cli/samples.json"
fi

CLI_NPX="$ROOT/cli/npx/package.json"
if [ -f "$CLI_NPX" ]; then
    sedi "s|\"version\": \"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^\"]*\"|\"version\": \"$VERSION\"|" "$CLI_NPX"
    echo "   cli/npx/package.json"
fi

# ── 6. cli/sdkman/*.md example commands ──
# SDKMAN submission docs embed the publish.sh example command with a
# pinned version argument. These are markdown prose files that the
# Maven versions:set and xml-tag regexes never touch, so they silently
# rot across releases. Release 4.0.36 shipped with "publish.sh 4.0.35"
# here — caught by audit, fixed by adding this section.
echo "── cli/sdkman/*.md example commands"
{ find "$ROOT/cli/sdkman" -name '*.md' 2>/dev/null || true; } | while read -r f; do
    if grep -qE 'publish\.sh [0-9]+\.[0-9]+\.[0-9]+' "$f" 2>/dev/null; then
        sedi -E "s|(publish\.sh )[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?|\1${VERSION}|g" "$f"
        echo "   $f"
        UPDATED=$((UPDATED + 1))
    fi
done

CLI_HOMEBREW="$ROOT/cli/homebrew/atmosphere.rb"
if [ -f "$CLI_HOMEBREW" ]; then
    sedi "s|atmosphere-[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^\"]*\.tar\.gz|atmosphere-$VERSION.tar.gz|" "$CLI_HOMEBREW"
    sedi "s|version \"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^\"]*\"|version \"$VERSION\"|" "$CLI_HOMEBREW"
    echo "   cli/homebrew/atmosphere.rb (SHA256 must be updated manually after tagging)"
fi

echo ""
echo "Done. Summary of changes:"
git diff --stat

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

# ── 1. Maven <version> tags in docs/*.md ──
echo "── docs/*.md Maven snippets"
if [ -d "$ROOT/docs" ]; then
{ find "$ROOT/docs" -name '*.md' -exec grep -l '<version>[0-9]' {} + 2>/dev/null || true; } | while read -r f; do
    sedi "s|<version>[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^<]*</version>|<version>$VERSION</version>|g" "$f"
    echo "   $f"
    UPDATED=$((UPDATED + 1))
done
fi

# ── 2. Maven <version> tags in module README.md ──
echo "── Module README.md files"
{ find "$ROOT/modules" -name 'README.md' -exec grep -l '<version>[0-9]' {} + 2>/dev/null || true; } | while read -r f; do
    sedi "s|<version>[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^<]*</version>|<version>$VERSION</version>|g" "$f"
    echo "   $f"
    UPDATED=$((UPDATED + 1))
done

# ── 3. Root README.md ──
echo "── Root README.md"
if grep -q '<version>[0-9]' "$ROOT/README.md" 2>/dev/null; then
    sedi "s|<version>[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*[^<]*</version>|<version>$VERSION</version>|g" "$ROOT/README.md"
    echo "   README.md (xml version tag)"
fi
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

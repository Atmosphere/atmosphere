#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# release-preflight.sh — Verify all version references are aligned
#
# Usage: ./scripts/release-preflight.sh 4.0.3 [5.0.2]
#   arg1: expected Java release version
#   arg2: expected atmosphere.js version (optional)
# ──────────────────────────────────────────────────────────────
set -euo pipefail

JAVA_VERSION="${1:?Usage: $0 <java-version> [js-version]}"
JS_VERSION="${2:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'
ERRORS=0

pass() { echo -e "${GREEN}✅ $1${NC}"; }
fail() { echo -e "${RED}❌ $1${NC}"; ERRORS=$((ERRORS + 1)); }

echo "═══════════════════════════════════════════"
echo " Release Preflight: Java ${JAVA_VERSION}"
[ -n "$JS_VERSION" ] && echo "                     JS    ${JS_VERSION}"
echo "═══════════════════════════════════════════"
echo ""

# ── 1. Check Java version in all READMEs ──
echo "── Java version references ──"
READMES=(
  README.md
  modules/ai/README.md
  modules/mcp/README.md
  modules/cpr/README.md
  modules/spring-boot-starter/README.md
  modules/quarkus-extension/README.md
)

for f in "${READMES[@]}"; do
  if grep -q "<version>${JAVA_VERSION}</version>" "$f" 2>/dev/null || \
     grep -q ":${JAVA_VERSION}'" "$f" 2>/dev/null; then
    pass "$f"
  else
    fail "$f — does not reference version ${JAVA_VERSION}"
    grep -n '<version>[0-9]' "$f" 2>/dev/null | head -3 || true
  fi
done

echo ""

# ── 2. Check atmosphere.js versions ──
if [ -n "$JS_VERSION" ]; then
  echo "── atmosphere.js version references ──"

  PKG_VER=$(grep '"version"' atmosphere.js/package.json | grep -o '[0-9][0-9.]*')
  SRC_VER=$(grep 'VERSION' atmosphere.js/src/version.ts | grep -o '[0-9][0-9.]*')

  if [ "$PKG_VER" = "$JS_VERSION" ]; then
    pass "package.json: ${PKG_VER}"
  else
    fail "package.json: ${PKG_VER} (expected ${JS_VERSION})"
  fi

  if [ "$SRC_VER" = "$JS_VERSION" ]; then
    pass "version.ts: ${SRC_VER}"
  else
    fail "version.ts: ${SRC_VER} (expected ${JS_VERSION})"
  fi

  if [ "$PKG_VER" = "$SRC_VER" ]; then
    pass "package.json and version.ts match"
  else
    fail "MISMATCH: package.json=${PKG_VER} version.ts=${SRC_VER}"
  fi

  echo ""
fi

# ── 3. Check for stale file references in sample READMEs ──
echo "── Sample README file references ──"
STALE_JS=$(grep -rn 'javascript/' samples/*/README.md 2>/dev/null || true)
if [ -z "$STALE_JS" ]; then
  pass "No stale javascript/ references in sample READMEs"
else
  fail "Stale javascript/ references found:"
  echo "$STALE_JS"
fi

echo ""

# ── 4. Check npm script names in atmosphere.js README ──
echo "── atmosphere.js npm scripts ──"
if [ -f atmosphere.js/README.md ] && [ -f atmosphere.js/package.json ]; then
  for cmd in $(grep -oE 'npm run [a-zA-Z_-]+' atmosphere.js/README.md | sed 's/npm run //' | sort -u); do
    if grep -q "\"${cmd}\"" atmosphere.js/package.json; then
      pass "npm run ${cmd}"
    else
      fail "npm run ${cmd} — not found in package.json"
    fi
  done
fi

echo ""

# ── 5. Check previous version isn't still lurking ──
echo "── Stale version check ──"
# Find what the previous version might be by decrementing patch
MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f1)
MINOR=$(echo "$JAVA_VERSION" | cut -d. -f2)
PATCH=$(echo "$JAVA_VERSION" | cut -d. -f3)
if [ "$PATCH" -gt 0 ]; then
  PREV_VERSION="${MAJOR}.${MINOR}.$((PATCH - 1))"
  STALE=$(grep -rn "${PREV_VERSION}" --include='*.md' . 2>/dev/null | grep -v CHANGELOG | grep -v MIGRATION | grep -v node_modules || true)
  if [ -z "$STALE" ]; then
    pass "No references to previous version ${PREV_VERSION} in docs"
  else
    fail "Found references to old version ${PREV_VERSION}:"
    echo "$STALE"
  fi
fi

echo ""
echo "═══════════════════════════════════════════"
if [ "$ERRORS" -eq 0 ]; then
  echo -e "${GREEN}All checks passed! Ready to release.${NC}"
  exit 0
else
  echo -e "${RED}${ERRORS} issue(s) found. Fix before releasing.${NC}"
  exit 1
fi

#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# release-preflight.sh — Verify all version references are aligned
#
# Usage: ./scripts/release-preflight.sh [java-version [js-version]]
#   java-version: Atmosphere release being cut. Default: the root pom.xml
#                 atmosphere-project <version> with -SNAPSHOT stripped (the
#                 version about to be released).
#   js-version:   atmosphere.js version. Default: atmosphere.js/package.json
#                 "version" (the next atmosphere.js to publish).
#
# The doc checks expect scripts/update-doc-versions.sh to have run for
# java-version already; release-4x.yml calls this right after that step.
# Never hardcode a version here: scripts/validate-no-hardcoded-atmosphere-version.sh
# fails the build on the current one.
# ──────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

JAVA_VERSION="${1:-}"
if [ -z "$JAVA_VERSION" ]; then
  JAVA_VERSION=$(python3 -c '
import re, sys
m = re.search(r"atmosphere-project</artifactId>.*?<version>([^<]+)</version>", open("pom.xml").read(), re.S)
sys.exit(1) if not m else print(m.group(1).strip())
') || { echo "release-preflight.sh: cannot read the atmosphere-project <version> from pom.xml; pass the release version explicitly." >&2; exit 2; }
  JAVA_VERSION="${JAVA_VERSION%-SNAPSHOT}"
  JAVA_SOURCE="pom.xml"
else
  JAVA_SOURCE="argument"
fi

JS_VERSION="${2:-}"
if [ -z "$JS_VERSION" ]; then
  JS_VERSION=$(grep -m1 '"version"' atmosphere.js/package.json | grep -o '[0-9][0-9.]*' || true)
  if [ -z "$JS_VERSION" ]; then
    echo "release-preflight.sh: cannot read the version from atmosphere.js/package.json; pass it explicitly." >&2
    exit 2
  fi
  JS_SOURCE="atmosphere.js/package.json"
else
  JS_SOURCE="argument"
fi

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'
ERRORS=0

pass() { echo -e "${GREEN}✅ $1${NC}"; }
fail() { echo -e "${RED}❌ $1${NC}"; ERRORS=$((ERRORS + 1)); }

echo "═══════════════════════════════════════════"
echo " Release Preflight: Java ${JAVA_VERSION}  (from ${JAVA_SOURCE})"
echo "                    JS    ${JS_VERSION}  (from ${JS_SOURCE})"
echo "═══════════════════════════════════════════"
echo ""

# ── 1. Check Java version in all READMEs and docs ──
echo "── Java version references ──"

# Delegated to the doc-version gate: it reads only org.atmosphere
# <dependency>/<parent> blocks, in every tracked *.md. The old per-file test
# ("does this README contain <version>X</version> anywhere?") failed every
# README whose only <version> tags are third-party ones (wasync, rag, adk,
# verifier-smt) and passed a file carrying both the new and a stale version.
if ATMO_RELEASED_VERSION="$JAVA_VERSION" ./scripts/validate-atmosphere-doc-version.sh; then
  pass "org.atmosphere <version> in tracked docs is ${JAVA_VERSION}"
else
  fail "org.atmosphere <version> drift against ${JAVA_VERSION} (scripts/validate-atmosphere-doc-version.sh, above)"
fi

echo ""

# ── 2. Check atmosphere.js versions ──
echo "── atmosphere.js version references ──"

PKG_VER=$(grep -m1 '"version"' atmosphere.js/package.json | grep -o '[0-9][0-9.]*')

if [ "$PKG_VER" = "$JS_VERSION" ]; then
  pass "package.json: ${PKG_VER}"
else
  fail "package.json: ${PKG_VER} (expected ${JS_VERSION})"
fi

# VERSION (sent as X-Atmosphere-Framework) is injected from package.json by
# tsup's `define`; a literal in version.ts is the drift this replaced.
if grep -q '^export const VERSION[^=]*= *__ATMOSPHERE_VERSION__ *;' atmosphere.js/src/version.ts; then
  pass "version.ts derives VERSION from package.json at build time"
else
  fail "version.ts no longer reads __ATMOSPHERE_VERSION__ — VERSION can drift from package.json"
fi

if [ -f atmosphere.js/dist/atmosphere.global.js ]; then
  if grep -q "\"${JS_VERSION}\"" atmosphere.js/dist/atmosphere.global.js; then
    pass "dist/atmosphere.global.js carries VERSION ${JS_VERSION}"
  else
    fail "dist/atmosphere.global.js does not carry VERSION ${JS_VERSION} — rebuild atmosphere.js"
  fi
fi

echo ""

# ── 3. Check for stale file references in sample READMEs ──
echo "── Sample README file references ──"
# Targets static asset paths (static/javascript/atmosphere.js moved to assets/);
# the atmosphere.js client docs URL (.../docs/clients/javascript/) is current.
STALE_JS=$(grep -rn 'javascript/' samples/*/README.md 2>/dev/null | grep -v 'atmosphere.github.io/docs/clients/javascript/' || true)
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
CORE_VERSION="${JAVA_VERSION%%-*}"
MAJOR=$(echo "$CORE_VERSION" | cut -d. -f1)
MINOR=$(echo "$CORE_VERSION" | cut -d. -f2)
PATCH=$(echo "$CORE_VERSION" | cut -d. -f3)
if [ "$PATCH" -gt 0 ]; then
  PREV_VERSION="${MAJOR}.${MINOR}.$((PATCH - 1))"
  PREV_RE="(^|[^0-9.])${PREV_VERSION//./\\.}([^0-9]|\$)"
  # Tracked docs only (git ls-files), so a nested worktree, node_modules or a
  # scratch file cannot fail a release. Release history (CHANGELOG, MIGRATION)
  # and the agent workspaces (.harness/, .claude/) name past versions by design.
  # The boundary regex keeps 4.0.7 from matching 4.0.70 or 14.0.7.
  STALE=$(git ls-files -z -- '*.md' ':!:*CHANGELOG*' ':!:*MIGRATION*' ':!:.harness/**' ':!:.claude/**' \
            | xargs -0 grep -nE -- "$PREV_RE" 2>/dev/null || true)
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

#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# Console bundle sync — single source of truth for the Atmosphere Console SPA.
#
# The Console is BUILT from source in modules/spring-boot-starter (frontend-
# maven-plugin, node pinned, never committed). spring-boot3-starter and
# quarkus-admin-extension SERVE the same SPA but ship it as committed
# resources — which historically drifted silently (the Tape tab shipped to
# the SB4 console only; the committed copies stayed at an older build).
#
# This script closes that class:
#   sync  (default)  — build the SPA via Maven (pinned node), copy the bundle
#                      into both committed resource dirs, write the source
#                      fingerprint marker.
#   --check          — recompute the fingerprint over the SPA inputs and fail
#                      if it doesn't match the marker, if the committed copies
#                      differ from each other, or if a copy does not send the
#                      atmosphere.js version of atmosphere.js/package.json (the
#                      one the source-built SB4 console sends). No Maven/npm —
#                      cheap enough for pre-commit (called from
#                      architectural-validation.sh).
#   --stamp-version  — write the package.json version into the committed
#                      copies in place of the one they send. No build: the
#                      version is the only byte a version bump changes (see
#                      mask_version). The release workflow runs it with each
#                      atmosphere.js version bump it commits.
#   --fingerprint    — print the fingerprint of the SPA inputs.
#
# The fingerprint hashes the SPA *inputs* (console frontend sources +
# atmosphere.js sources/locks), not the emitted bytes, so the gate never
# depends on bundler byte-determinism across environments.
# ----------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FRONTEND_DIR="modules/spring-boot-starter/frontend"
BUILT_BUNDLE="modules/spring-boot-starter/target/classes/META-INF/resources/atmosphere/console"
DEST_DIRS=(
  "modules/spring-boot3-starter/src/main/resources/META-INF/resources/atmosphere/console"
  "modules/quarkus-admin-extension/runtime/src/main/resources/META-INF/resources/atmosphere/console"
  # Bare-Jetty samples — no starter jar to carry the bundle, so their webapps
  # ship committed copies under the same gate (served by the DefaultServlet
  # at /atmosphere/console/, protected-targets already set).
  "samples/grpc-chat/src/main/webapp/atmosphere/console"
  "samples/chat/src/main/webapp/atmosphere/console"
  "samples/embedded-jetty-websocket-chat/src/main/webapp/atmosphere/console"
)
MARKER="scripts/console-bundle.fingerprint"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

sha_cmd() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256; else sha256sum; fi
}

# Fingerprint over everything that influences the emitted bundle: the console
# frontend (sources, lockfile, build config) and atmosphere.js (bundled into
# the SPA chunk via the file: dependency). node_modules excluded — the
# lockfiles pin them.
#
# The package's own "version" line is masked out of the manifest/lockfile hash.
# The release workflow bumps atmosphere.js to the next -SNAPSHOT as its last
# step. That bump moved the fingerprint, so the first commit after every
# release was blocked on a re-sync. Masking keeps the gate firing on real
# input changes (dependencies, sources, build config) and stops it firing on
# the version string alone. The one byte-level effect of the bump is the
# atmosphere.js VERSION string (injected from package.json at build time and
# sent as X-Atmosphere-Framework), which --check verifies on its own
# (check_versions) and --stamp-version rewrites without a build: copies last
# synced at d98fe2f051, before the 1c5410bd17 bump, shipped in a release
# sending the previous version while the source-built SB4 console sent the
# bumped one. Only the top-level and root-package "version" keys are masked;
# a dependency's pinned version sits deeper in the lockfile and still counts.
mask_version() {
  sed -E 's/^([[:space:]]*)"version"[[:space:]]*:[[:space:]]*"[^"]*"/\1"version": "MASKED"/'
}

compute_fingerprint() {
  {
    find "$FRONTEND_DIR" -type f -not -path "*/node_modules/*" 2>/dev/null
    find "atmosphere.js/src" -type f 2>/dev/null
  } | LC_ALL=C sort | while IFS= read -r f; do
    [ -f "$f" ] && sha_cmd < "$f" | awk -v f="$f" '{print $1, f}'
  done > /tmp/.atmo-console-fp.$$ 2>/dev/null || true

  for f in "atmosphere.js/package.json" "atmosphere.js/package-lock.json"; do
    if [ -f "$f" ]; then
      mask_version < "$f" | sha_cmd | awk -v f="$f" '{print $1, f}' >> /tmp/.atmo-console-fp.$$
    fi
  done

  LC_ALL=C sort /tmp/.atmo-console-fp.$$ | sha_cmd | awk '{print $1}'
  rm -f /tmp/.atmo-console-fp.$$
}

package_version() {
  sed -nE 's/^  "version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' atmosphere.js/package.json | head -n1
}

# The atmosphere.js versions a committed copy sends, one per line: the value of
# each identifier its JS assets send as X-Atmosphere-Framework (the minified
# VERSION constant, e.g. ss=`<version>`).
embedded_versions() {
  local dir="$1" js ident
  for js in "$dir"/assets/*.js; do
    [ -f "$js" ] || continue
    { grep -oE 'X-Atmosphere-Framework=[$][{]encodeURIComponent[(][A-Za-z_$][A-Za-z0-9_$]*[)][}]' "$js" || true; } \
      | sed -E 's/.*encodeURIComponent[(]([^)]*)[)].*/\1/' | LC_ALL=C sort -u \
      | while IFS= read -r ident; do
          { grep -oE "(^|[^A-Za-z0-9_\$])${ident//\$/[\$]}=[\"'\`][^\"'\`]+[\"'\`]" "$js" || true; } \
            | sed -E 's/.*=.(.*).$/\1/'
        done
  done | LC_ALL=C sort -u
}

# Every committed copy must send the atmosphere.js version of package.json, as
# the spring-boot-starter console, built from source, does.
check_versions() {
  local want dest found rc=0
  want="$(package_version)"
  if [ -z "$want" ]; then
    echo -e "${RED}No version in atmosphere.js/package.json.${NC}"
    return 1
  fi
  for dest in "${DEST_DIRS[@]}"; do
    found="$(embedded_versions "$dest" | paste -sd, -)"
    if [ "$found" != "$want" ]; then
      echo -e "${RED}Committed console bundle at $dest sends atmosphere.js ${found:-<no version found>}, package.json is $want.${NC}"
      rc=1
    fi
  done
  return $rc
}

check() {
  local fingerprint marker_value rc=0
  fingerprint="$(compute_fingerprint)"

  if [ ! -f "$MARKER" ]; then
    echo -e "${RED}Console bundle marker missing: $MARKER${NC}"
    rc=1
  else
    marker_value="$(head -n1 "$MARKER")"
    if [ "$fingerprint" != "$marker_value" ]; then
      echo -e "${RED}Console SPA sources changed but the committed bundles were not re-synced.${NC}"
      echo "  expected fingerprint: $fingerprint"
      echo "  marker holds:         $marker_value"
      rc=1
    fi
  fi

  # Every committed copy must be byte-identical to the first — a manual edit
  # to any one of them is drift even when the marker still matches.
  for dest in "${DEST_DIRS[@]:1}"; do
    if ! diff -r "${DEST_DIRS[0]}" "$dest" >/dev/null 2>&1; then
      echo -e "${RED}Committed console bundle at $dest differs from ${DEST_DIRS[0]}.${NC}"
      rc=1
    fi
  done

  check_versions || rc=1

  if [ $rc -ne 0 ]; then
    echo "  Fix: ./scripts/sync-console-bundle.sh   (then commit the refreshed bundles + marker)"
    return 1
  fi
  echo -e "${GREEN}Console bundle in sync (fingerprint ${fingerprint:0:12}…)${NC}"
}

sync() {
  echo "Building the Console SPA via Maven (pinned node toolchain)…"
  ./mvnw -q generate-resources -pl modules/spring-boot-starter

  if [ ! -f "$BUILT_BUNDLE/index.html" ]; then
    echo -e "${RED}Build produced no bundle at $BUILT_BUNDLE${NC}"
    exit 1
  fi

  for dest in "${DEST_DIRS[@]}"; do
    rm -rf "$dest"
    mkdir -p "$dest"
    cp -R "$BUILT_BUNDLE/." "$dest/"
    echo "Synced → $dest"
  done

  compute_fingerprint > "$MARKER"
  echo "Marker updated → $MARKER ($(head -c 12 "$MARKER")…)"
  echo "Commit the refreshed bundles together with the marker."
}

stamp_version() {
  local want dest js old
  want="$(package_version)"
  if [ -z "$want" ]; then
    echo -e "${RED}No version in atmosphere.js/package.json.${NC}"
    exit 1
  fi
  for dest in "${DEST_DIRS[@]}"; do
    for old in $(embedded_versions "$dest"); do
      [ "$old" = "$want" ] && continue
      for js in "$dest"/assets/*.js; do
        [ -f "$js" ] || continue
        # Only the VERSION constant's literal: =`old` (whichever quote).
        OLD="$old" NEW="$want" perl -pi -e 's/(=[`"\x27])\Q$ENV{OLD}\E([`"\x27])/$1$ENV{NEW}$2/g' "$js"
      done
    done
  done
  check_versions
  echo "Committed console bundles now send atmosphere.js $want."
}

case "${1:-sync}" in
  --check)         check ;;
  --stamp-version) stamp_version ;;
  --fingerprint)   compute_fingerprint ;;
  sync)            sync ;;
  *) echo "usage: $0 [--check|--stamp-version|--fingerprint]"; exit 2 ;;
esac

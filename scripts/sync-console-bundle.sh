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
#                      if it doesn't match the marker, or if the two committed
#                      copies differ from each other. No Maven/npm — cheap
#                      enough for pre-commit (called from
#                      architectural-validation.sh).
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
  # Bare-Jetty sample — no starter jar to carry the bundle, so its webapp
  # ships a committed copy under the same gate (served by the DefaultServlet
  # at /atmosphere/console/, protected-targets already set).
  "samples/grpc-chat/src/main/webapp/atmosphere/console"
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
compute_fingerprint() {
  {
    find "$FRONTEND_DIR" -type f -not -path "*/node_modules/*" 2>/dev/null
    find "atmosphere.js/src" -type f 2>/dev/null
    printf '%s\n' "atmosphere.js/package.json" "atmosphere.js/package-lock.json"
  } | LC_ALL=C sort | while IFS= read -r f; do
    [ -f "$f" ] && sha_cmd < "$f" | awk -v f="$f" '{print $1, f}'
  done | sha_cmd | awk '{print $1}'
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

case "${1:-sync}" in
  --check) check ;;
  sync)    sync ;;
  *) echo "usage: $0 [--check]"; exit 2 ;;
esac

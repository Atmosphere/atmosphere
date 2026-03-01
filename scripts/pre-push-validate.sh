#!/usr/bin/env bash
# Pre-push validation script — runs Maven build and stamps a marker.
# Run this before `git push`. The pre-push hook checks for the marker.
#
# Usage:
#   ./scripts/pre-push-validate.sh          # full build + tests
#   ./scripts/pre-push-validate.sh --fast   # compile only (no tests)

set -e

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
GIT_DIR="$(git rev-parse --git-dir)"
MARKER_FILE="$GIT_DIR/validation-passed"
VALIDATION_TTL_MINUTES=30

echo ""
echo "🔍 Atmosphere — Pre-Push Validation"
echo "===================================="
echo ""

START_TIME=$(date +%s)

# Remove any stale marker
rm -f "$MARKER_FILE"

CURRENT_COMMIT=$(git rev-parse HEAD)
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "📋 Branch: $CURRENT_BRANCH"
echo "   Commit: ${CURRENT_COMMIT:0:8}"
echo ""

# Determine build mode
if [ "$1" = "--fast" ]; then
    echo "⚡ Fast mode: install without tests"
    echo ""
    BUILD_CMD="./mvnw install -DskipTests -q"
else
    echo "🔨 Full mode: compile + tests"
    echo ""
    BUILD_CMD="./mvnw install -q"
fi

# Run the build
cd "$PROJECT_ROOT"
echo "Running: $BUILD_CMD"
echo ""

if $BUILD_CMD; then
    END_TIME=$(date +%s)
    ELAPSED=$(( END_TIME - START_TIME ))

    # Stamp the marker
    echo "$END_TIME $CURRENT_COMMIT" > "$MARKER_FILE"

    echo ""
    echo "✅ Validation passed (${ELAPSED}s)"
    echo "   Marker valid for ${VALIDATION_TTL_MINUTES} minutes."
    echo ""
    echo "   You can now: git push"
    echo ""
else
    echo ""
    echo "❌ Build failed — fix errors before pushing."
    echo ""
    exit 1
fi

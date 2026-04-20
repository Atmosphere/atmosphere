#!/usr/bin/env bash
# Pre-push validation — runs the full Maven install with tests, stamps a
# marker, and the pre-push hook checks for the marker. No opt-out.
#
# Usage:
#   ./scripts/pre-push-validate.sh
#
# History: the script used to support a `--fast` mode that ran
# {@code install -DskipTests}. It was removed 2026-04-20 because it
# encouraged pushing without running tests locally — `./mvnw install -q`
# is the one path every push takes now. If you need JAR-only installs
# for an unrelated task, call Maven directly — don't brand it as
# validation.

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

# Full build + tests. Single path so no push ships code that never ran
# the test suite locally.
BUILD_CMD="./mvnw install -q"
echo "🔨 Full mode: compile + tests"
echo ""

# Reject any leftover invocation that passes the old --fast flag, so
# hook scripts / CI runners that cached the flag fail loudly instead of
# silently skipping tests.
if [ -n "$1" ]; then
    echo "❌ pre-push-validate.sh no longer accepts arguments (got: '$1')."
    echo "   The --fast mode was removed — every push validates with tests."
    echo ""
    exit 2
fi

# Run architectural validation first (fast fail before long build).
cd "$PROJECT_ROOT"
echo "Running architectural validation..."
echo ""
if ! ./scripts/architectural-validation.sh; then
    echo ""
    echo "Architectural validation failed — fix issues before pushing."
    echo ""
    exit 1
fi

echo ""
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

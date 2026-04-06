#!/usr/bin/env bash
# Detect virtual-thread pinning under JMH benchmark load.
#
# Runs the JMH benchmarks with JDK Flight Recorder enabled and scans
# the recording for jdk.VirtualThreadPinned events. Any pinning events
# indicate that a virtual thread was forced onto a carrier (platform)
# thread — typically due to synchronized blocks or native calls.
#
# Usage:
#   scripts/benchmarks/vt-pinning-check.sh              # all benchmarks
#   scripts/benchmarks/vt-pinning-check.sh Checkpoint    # regex filter
#
# Prerequisites:
#   - JDK 21+ (JFR is built-in)
#   - Benchmarks JAR built: ./mvnw -Pperf -pl modules/benchmarks -am package -DskipTests
#
# Output:
#   target/jmh-results/vt-pinning-*.jfr   — raw JFR recording
#   STDOUT                                 — pinning event summary
set -euo pipefail

cd "$(dirname "$0")/../.."

FILTER="${1:-}"
OUT_DIR="target/jmh-results"
mkdir -p "${OUT_DIR}"

JAR="modules/benchmarks/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
    echo "Building benchmarks uberjar..."
    ./mvnw -Pperf -pl modules/benchmarks -am package -DskipTests -q
fi

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
JFR_FILE="${OUT_DIR}/vt-pinning-${TIMESTAMP}.jfr"

echo "=== Virtual Thread Pinning Check ==="
echo "Recording to: ${JFR_FILE}"
echo ""

# Run JMH with JFR enabled, capturing VirtualThreadPinned events.
# Use fewer forks/iterations for a focused pinning scan (not perf measurement).
java \
    -XX:StartFlightRecording=filename="${JFR_FILE}",settings=profile,dumponexit=true \
    -Djdk.tracePinnedThreads=short \
    -jar "${JAR}" \
    ${FILTER:+"${FILTER}"} \
    -f 1 -wi 2 -i 3 \
    -rf text \
    -rff "${OUT_DIR}/vt-pinning-${TIMESTAMP}.txt" \
    2>&1 | tee "${OUT_DIR}/vt-pinning-${TIMESTAMP}.log"

echo ""
echo "=== JFR Pinning Event Summary ==="

# Extract pinning events from the JFR recording
if command -v jfr >/dev/null 2>&1; then
    PINNED=$(jfr print --events jdk.VirtualThreadPinned "${JFR_FILE}" 2>/dev/null | grep -c "jdk.VirtualThreadPinned" || true)
    if [[ "${PINNED}" -gt 0 ]]; then
        echo "WARNING: ${PINNED} VirtualThreadPinned event(s) detected!"
        echo ""
        jfr print --events jdk.VirtualThreadPinned "${JFR_FILE}" 2>/dev/null | head -100
        echo ""
        echo "Full recording: ${JFR_FILE}"
        echo "View with: jfr print --events jdk.VirtualThreadPinned ${JFR_FILE}"
        exit 1
    else
        echo "PASS: No VirtualThreadPinned events detected."
    fi
else
    echo "SKIP: 'jfr' CLI not found. Analyze manually:"
    echo "  jfr print --events jdk.VirtualThreadPinned ${JFR_FILE}"
fi

echo ""
echo "JFR recording: ${JFR_FILE}"
echo "Benchmark log: ${OUT_DIR}/vt-pinning-${TIMESTAMP}.log"

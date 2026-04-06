#!/usr/bin/env bash
# Runs the Tier 1 JMH micro-benchmarks.
#
# Usage:
#   scripts/benchmarks/run-jmh.sh              # all benchmarks
#   scripts/benchmarks/run-jmh.sh Checkpoint   # regex filter
#
# Output: JSON + human-readable summary under target/jmh-results/.
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
JSON_OUT="${OUT_DIR}/tier1-${TIMESTAMP}.json"

java -jar "${JAR}" \
    ${FILTER:+"${FILTER}"} \
    -rf json \
    -rff "${JSON_OUT}"

echo ""
echo "Results written to: ${JSON_OUT}"

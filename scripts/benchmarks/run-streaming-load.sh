#!/usr/bin/env bash
# Runs the Atmosphere streaming load test against a running server.
#
# Prerequisites:
#   Start an Atmosphere server externally, e.g.:
#     ./mvnw -pl samples/spring-boot-chat spring-boot:run
#
# Usage:
#   scripts/benchmarks/run-streaming-load.sh
#   scripts/benchmarks/run-streaming-load.sh --clients 200 --messages 20
#   scripts/benchmarks/run-streaming-load.sh --url ws://host:8080/atmosphere/chat
set -euo pipefail

cd "$(dirname "$0")/../.."

JAR="modules/benchmarks/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
    echo "Building benchmarks uberjar..."
    ./mvnw -Pperf -pl modules/benchmarks -am package -DskipTests -q
fi

echo "Running streaming load test..."
echo "NOTE: Ensure your Atmosphere server is running (e.g. spring-boot-chat sample)."
echo ""

java -cp "${JAR}" org.atmosphere.benchmarks.load.StreamingLoadTest "$@"

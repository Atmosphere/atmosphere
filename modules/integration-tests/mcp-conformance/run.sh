#!/usr/bin/env bash
#
# Run the official MCP conformance suite (@modelcontextprotocol/conformance,
# server mode) against ConformanceMcpServer, on both protocol dialects:
#   - session/handshake lane: --spec-version 2025-11-25
#   - stateless lane:         --requirements 2026-07-28
# Each lane is gated by its expected-failures baseline next to this script.
#
# Prerequisite: the integration-tests module is packaged, so target/classes and
# target/e2e-lib exist (./mvnw install -DskipTests -pl modules/integration-tests -am).
# No Maven runs here. Usage: run.sh [output-dir]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/.." && pwd)"
OUT="${1:-$MODULE/target/mcp-conformance}"
# Pinned: the 2026-07-28 scenarios only exist in the 0.2.0 alpha line. Bump
# deliberately and refresh both baselines in the same change.
CONFORMANCE_VERSION="${CONFORMANCE_VERSION:-0.2.0-alpha.11}"
PORT="${MCP_CONFORMANCE_PORT:-39217}"
URL="http://localhost:${PORT}/mcp"

if [ ! -d "$MODULE/target/classes" ] || [ ! -d "$MODULE/target/e2e-lib" ]; then
    echo "error: $MODULE is not packaged (target/classes + target/e2e-lib missing)" >&2
    exit 2
fi
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "error: port $PORT is already in use; set MCP_CONFORMANCE_PORT" >&2
    exit 2
fi

mkdir -p "$OUT"
server_log="$OUT/server.log"
java_cmd=(java "-Dserver.port=${PORT}"
    -cp "$MODULE/target/classes:$MODULE/target/e2e-lib/*"
    org.atmosphere.integrationtests.mcpconformance.McpConformanceServerMain)
"${java_cmd[@]}" >"$server_log" 2>&1 &
server_pid=$!
cleanup() {
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

# Ready = the MCP endpoint answers a JSON-RPC ping, not merely an open port.
ready=false
for _ in $(seq 1 90); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
        echo "error: conformance server exited during startup" >&2
        cat "$server_log" >&2
        exit 1
    fi
    if curl -fsS -o /dev/null -X POST "$URL" \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":0,"method":"ping","params":{}}' 2>/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
if [ "$ready" != "true" ]; then
    echo "error: $URL did not answer within 90s" >&2
    cat "$server_log" >&2
    exit 1
fi

runner=(npx --yes "@modelcontextprotocol/conformance@${CONFORMANCE_VERSION}" server --url "$URL")
status=0

echo "::group::MCP conformance, session dialect (2025-11-25)"
"${runner[@]}" --spec-version 2025-11-25 \
    --expected-failures "$HERE/expected-failures-2025-11-25.yml" \
    -o "$OUT/2025-11-25" || status=1
echo "::endgroup::"

echo "::group::MCP conformance, stateless dialect (2026-07-28)"
"${runner[@]}" --requirements 2026-07-28 \
    --expected-failures "$HERE/expected-failures-2026-07-28.yml" \
    -o "$OUT/2026-07-28" || status=1
echo "::endgroup::"

exit "$status"

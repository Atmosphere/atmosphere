#!/usr/bin/env bash
# Smoke test of the packaged stdio bridge (atmosphere-mcp-*-stdio-bridge.jar,
# built with `./mvnw package -pl modules/mcp -Pstdio-bridge`): boots the MCP
# conformance fixture from modules/integration-tests (packaged, no Maven at
# test time), pipes JSON-RPC lines through `java -jar <bridge>` the way a stdio
# client does, and checks the answers on stdout. Proves the shaded, minimized
# jar starts and relays, which the in-process integration test cannot.
#
# Usage: modules/mcp/stdio-bridge-smoke.sh [out-dir]
# MCP_BRIDGE_BIN=<native binary> runs that build of the bridge (the one an .mcpb
# bundle ships) instead of the jar.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
FIXTURE="$ROOT/modules/integration-tests"
OUT="${1:-$HERE/target/stdio-bridge-smoke}"
PORT="${MCP_BRIDGE_SMOKE_PORT:-39218}"
URL="http://localhost:${PORT}/mcp"

if [ -n "${MCP_BRIDGE_BIN:-}" ]; then
    [ -x "$MCP_BRIDGE_BIN" ] || { echo "error: MCP_BRIDGE_BIN $MCP_BRIDGE_BIN is not executable" >&2; exit 2; }
    bridge=("$MCP_BRIDGE_BIN")
    bridge_name="$(basename "$MCP_BRIDGE_BIN")"
else
    shopt -s nullglob
    jars=("$HERE"/target/atmosphere-mcp-*-stdio-bridge.jar)
    shopt -u nullglob
    if [ "${#jars[@]}" -ne 1 ]; then
        echo "error: expected one stdio-bridge jar in $HERE/target, found ${#jars[@]}" >&2
        exit 2
    fi
    bridge=(java -jar "${jars[0]}")
    bridge_name="$(basename "${jars[0]}")"
fi
if [ ! -d "$FIXTURE/target/classes" ] || [ ! -d "$FIXTURE/target/e2e-lib" ]; then
    echo "error: $FIXTURE is not packaged (target/classes + target/e2e-lib missing)" >&2
    exit 2
fi
command -v jq >/dev/null 2>&1 || { echo "error: jq is required" >&2; exit 2; }

mkdir -p "$OUT"
server_log="$OUT/server.log"
java "-Dserver.port=${PORT}" -cp "$FIXTURE/target/classes:$FIXTURE/target/e2e-lib/*" \
    org.atmosphere.integrationtests.mcpconformance.McpConformanceServerMain >"$server_log" 2>&1 &
server_pid=$!
cleanup() {
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

ready=false
for _ in $(seq 1 90); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
        echo "error: fixture server exited during startup" >&2
        cat "$server_log" >&2
        exit 1
    fi
    if curl -fsS -o /dev/null -X POST "$URL" -H 'Content-Type: application/json' \
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

stdout="$OUT/bridge.stdout"
stderr="$OUT/bridge.stderr"
# stdin ends right after the last line: the bridge must still answer every
# request in flight before it ends the session.
printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
    '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"test_simple_text","arguments":{}}}' \
    'not json' \
    | timeout 60 "${bridge[@]}" "$URL" >"$stdout" 2>"$stderr"

fail() {
    echo "error: $1" >&2
    echo "--- bridge stdout" >&2; cat "$stdout" >&2
    echo "--- bridge stderr" >&2; cat "$stderr" >&2
    exit 1
}
# Every stdout line must be one JSON value.
jq -e . "$stdout" >/dev/null || fail "stdout is not one JSON message per line"
[ "$(jq -s 'map(select(.id == 1 and .result.serverInfo != null)) | length' "$stdout")" = 1 ] \
    || fail "initialize was not answered"
[ "$(jq -s 'map(select(.id == 2 and (.result.tools | length) > 0)) | length' "$stdout")" = 1 ] \
    || fail "tools/list was not answered"
[ "$(jq -s 'map(select(.id == 3 and .result.content[0].type == "text")) | length' "$stdout")" = 1 ] \
    || fail "tools/call was not answered"
[ "$(jq -s 'map(select(.id == null and .error.code == -32700)) | length' "$stdout")" = 1 ] \
    || fail "a line that is not JSON was not answered with a parse error"
echo "stdio bridge smoke test passed ($(wc -l <"$stdout" | tr -d ' ') messages relayed by $bridge_name)"

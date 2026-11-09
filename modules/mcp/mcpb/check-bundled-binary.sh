#!/usr/bin/env bash
# Checks that a native build of the stdio bridge (the binary an .mcpb bundle
# ships) starts, reads a JSON-RPC line from stdin and writes JSON-RPC back: an
# initialize against an unreachable endpoint must be answered on stdout with a
# JSON-RPC error carrying the request's id, and the bridge must exit once stdin
# ends. The relay against a live server is the stdio-bridge smoke test's job.
#
# Usage: modules/mcp/mcpb/check-bundled-binary.sh <binary>
set -euo pipefail

binary="${1:?usage: $0 <binary>}"
[ -x "$binary" ] || { echo "error: $binary is not executable" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "error: jq is required" >&2; exit 2; }

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' \
    | "$binary" http://127.0.0.1:9/mcp >"$out"
cat "$out"
if [ "$(jq -s 'map(select(.id == 1 and .error != null)) | length' "$out")" != 1 ]; then
    echo "error: $(basename "$binary") did not answer initialize with a JSON-RPC error" >&2
    exit 1
fi
echo "$(basename "$binary") answered on stdout and exited"

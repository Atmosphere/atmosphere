#!/usr/bin/env bash
# Builds the MCP Bundle (.mcpb) of the stdio bridge for the platform it runs on:
# compiles the packaged bridge jar (./mvnw package -pl modules/mcp -Pstdio-bridge)
# to a native binary with GraalVM native-image, stages it next to manifest.json
# with the version and platform stamped in, validates the manifest and packs it.
#
# Needs native-image (GraalVM 25, on PATH or under $GRAALVM_HOME), jq and npx.
#
# Usage: modules/mcp/mcpb/build-mcpb.sh [out-dir]
# Prints the path of the .mcpb it wrote.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/.." && pwd)"
OUT="${1:-$MODULE/target}"
# The mcpb CLI that validates and packs; pinned so a CLI release cannot change the bundle.
MCPB_CLI="@anthropic-ai/mcpb@2.1.2"

shopt -s nullglob
jars=("$MODULE"/target/atmosphere-mcp-*-stdio-bridge.jar)
shopt -u nullglob
if [ "${#jars[@]}" -ne 1 ]; then
    echo "error: expected one stdio-bridge jar in $MODULE/target, found ${#jars[@]}" >&2
    exit 2
fi
jar="${jars[0]}"
version="$(basename "$jar")"
version="${version#atmosphere-mcp-}"
version="${version%-stdio-bridge.jar}"

# GraalVM ships bin/native-image, or bin/native-image.cmd on Windows.
native_image=""
for candidate in "${GRAALVM_HOME:-}/bin/native-image" "${GRAALVM_HOME:-}/bin/native-image.cmd"; do
    if [ -n "${GRAALVM_HOME:-}" ] && [ -f "$candidate" ]; then
        native_image="$candidate"
        break
    fi
done
if [ -z "$native_image" ]; then
    native_image="$(command -v native-image || command -v native-image.cmd || true)"
fi
[ -n "$native_image" ] || { echo "error: native-image not found (set GRAALVM_HOME)" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "error: jq is required" >&2; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "error: npx is required" >&2; exit 2; }

case "$(uname -s)" in
    Darwin) platform=darwin; exe="" ;;
    Linux) platform=linux; exe="" ;;
    MINGW* | MSYS* | CYGWIN*) platform=win32; exe=".exe" ;;
    *) echo "error: unsupported OS $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
    arm64 | aarch64) arch=arm64 ;;
    x86_64 | amd64) arch=x64 ;;
    *) echo "error: unsupported architecture $(uname -m)" >&2; exit 2 ;;
esac

stage="$MODULE/target/mcpb/$platform-$arch"
rm -rf "$stage"
mkdir -p "$stage/server" "$OUT"

# -march=compatibility: the binary runs on any CPU of the architecture, not only
# on one like the build machine's.
# Its progress goes to stderr: stdout carries only the bundle's path.
"$native_image" --no-fallback -march=compatibility -jar "$jar" -o "$stage/server/atmosphere-mcp-bridge" >&2
rm -f "$stage/server/"*.txt
binary="$stage/server/atmosphere-mcp-bridge$exe"
[ -x "$binary" ] || { echo "error: native-image produced no $binary" >&2; exit 1; }

jq --arg version "$version" --arg platform "$platform" \
    '.version = $version | .compatibility.platforms = [$platform]' \
    "$HERE/manifest.json" >"$stage/manifest.json"
cp "$MODULE/../../LICENSE" "$stage/LICENSE"

npx -y "$MCPB_CLI" validate "$stage/manifest.json" >&2
bundle="$OUT/atmosphere-mcp-bridge-$version-$platform-$arch.mcpb"
npx -y "$MCPB_CLI" pack "$stage" "$bundle" >&2
echo "$bundle"

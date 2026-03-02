#!/usr/bin/env bash
# Integration tests for the Atmosphere project generator.
# Generates projects for each handler/AI variant and verifies they compile.
#
# Usage: bash generator/test-generator.sh [variant ...]
#   If no variants specified, runs all 5.
#   Examples:
#     bash generator/test-generator.sh              # all variants
#     bash generator/test-generator.sh chat ai-adk   # just two

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR=""

cleanup() {
    if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

WORK_DIR="$(mktemp -d)"
PASS=0
FAIL=0

green()  { printf '\033[32m%s\033[0m\n' "$*"; }
red()    { printf '\033[31m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

# ---- Variant definitions ----
# Each variant: label | --handler | --ai (or "none") | expected artifact | expected files (comma-separated)
declare -a VARIANTS=(
    "chat|chat|none|atmosphere-spring-boot-starter|Chat.java,Message.java,JacksonEncoder.java,JacksonDecoder.java,index.html"
    "ai-builtin|ai-chat|builtin|atmosphere-ai|AiChat.java,DemoResponseProducer.java,LlmConfig.java,system-prompt.md,index.html"
    "ai-spring-ai|ai-chat|spring-ai|atmosphere-spring-ai|AiChat.java,DemoResponseProducer.java,index.html"
    "ai-adk|ai-chat|adk|atmosphere-adk|AiChat.java,DemoEventProducer.java,index.html"
    "mcp-server|mcp-server|none|atmosphere-mcp|Chat.java,DemoMcpServer.java,Message.java,index.html"
)

run_variant() {
    local spec="$1"
    IFS='|' read -r label handler_type ai_fw expected_dep expected_files <<< "$spec"

    local project_name="test-${label}"
    local project_dir="$WORK_DIR/$project_name"

    echo ""
    yellow "=== Variant: $label ==="

    # 1. Generate project
    local gen_args=(
        "$SCRIPT_DIR/AtmosphereInit.java"
        --name "$project_name"
        --handler "$handler_type"
        -o "$project_dir"
    )
    if [[ "$ai_fw" != "none" ]]; then
        gen_args+=(--ai "$ai_fw")
    fi

    echo "  Generating project..."
    if ! jbang "${gen_args[@]}" > /dev/null 2>&1; then
        red "  FAIL: jbang generation failed for $label"
        ((FAIL++))
        return
    fi

    # 2. Verify expected files exist
    echo "  Checking expected files..."
    IFS=',' read -ra files <<< "$expected_files"
    for f in "${files[@]}"; do
        if ! find "$project_dir" -name "$f" -print -quit | grep -q .; then
            red "  FAIL: expected file '$f' not found in $project_dir"
            ((FAIL++))
            return
        fi
    done

    # 3. Verify pom.xml contains expected dependency
    echo "  Checking pom.xml for $expected_dep..."
    if ! grep -q "$expected_dep" "$project_dir/pom.xml"; then
        red "  FAIL: pom.xml missing expected dependency $expected_dep"
        ((FAIL++))
        return
    fi

    # 4. Verify Java sources declare correct package
    echo "  Checking package declarations..."
    local expected_pkg="com.example.${project_name//[-]/}"
    local java_files
    java_files=$(find "$project_dir/src" -name "*.java")
    for jf in $java_files; do
        if ! grep -q "package $expected_pkg;" "$jf"; then
            red "  FAIL: $jf does not declare package $expected_pkg"
            ((FAIL++))
            return
        fi
    done

    # 5. Compile the generated project
    echo "  Compiling generated project..."
    if ! (cd "$project_dir" && ./mvnw -B compile -q 2>&1); then
        red "  FAIL: mvnw compile failed for $label"
        ((FAIL++))
        return
    fi

    green "  PASS: $label"
    ((PASS++))
}

# ---- Main ----
echo "Atmosphere Generator Integration Tests"
echo "======================================="
echo "Working directory: $WORK_DIR"

# Determine which variants to run
requested=("$@")
if [[ ${#requested[@]} -eq 0 ]]; then
    # Run all variants
    for spec in "${VARIANTS[@]}"; do
        run_variant "$spec"
    done
else
    # Run only requested variants
    for req in "${requested[@]}"; do
        found=false
        for spec in "${VARIANTS[@]}"; do
            IFS='|' read -r label _ <<< "$spec"
            if [[ "$label" == "$req" ]]; then
                run_variant "$spec"
                found=true
                break
            fi
        done
        if [[ "$found" == "false" ]]; then
            red "Unknown variant: $req"
            ((FAIL++))
        fi
    done
fi

echo ""
echo "======================================="
echo "Results: $PASS passed, $FAIL failed"

if [[ $FAIL -gt 0 ]]; then
    red "FAILED"
    exit 1
fi
green "ALL PASSED"

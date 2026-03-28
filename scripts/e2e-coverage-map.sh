#!/bin/bash
# Copyright 2008-2026 Async-IO.org
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

# e2e-coverage-map.sh — Coverage traceability for E2E tests.
# Checks that every runnable sample in cli/samples.json has at least one
# matching Playwright spec in modules/integration-tests/e2e/.

set -euo pipefail

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
if ! command -v jq &>/dev/null; then
    echo "ERROR: jq is required but not found on PATH." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || (cd "$(dirname "$0")/.." && pwd))"
SAMPLES_JSON="$REPO_ROOT/cli/samples.json"
SPEC_DIR="$REPO_ROOT/modules/integration-tests/e2e"

if [[ ! -f "$SAMPLES_JSON" ]]; then
    echo "ERROR: $SAMPLES_JSON not found." >&2
    exit 1
fi

if [[ ! -d "$SPEC_DIR" ]]; then
    echo "ERROR: $SPEC_DIR not found." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Colors
# ---------------------------------------------------------------------------
GREEN='\033[0;32m'
RED='\033[0;31m'
BOLD='\033[1m'
RESET='\033[0m'

# ---------------------------------------------------------------------------
# Known alias map (sample name -> comma-separated spec basenames)
# ---------------------------------------------------------------------------
declare -A ALIAS_MAP
ALIAS_MAP["chat"]="chat.spec.ts"
ALIAS_MAP["embedded-jetty-websocket-chat"]="embedded-jetty-chat.spec.ts"
ALIAS_MAP["spring-boot-mcp-server"]="mcp-server.spec.ts,mcp-tools.spec.ts"
ALIAS_MAP["spring-boot-durable-sessions"]="durable-sessions.spec.ts,durable-session-token.spec.ts,durable-session-identity.spec.ts"
ALIAS_MAP["spring-boot-ai-chat"]="spring-boot-ai-chat.spec.ts,ai-chat-features.spec.ts,ai-cache.spec.ts,ai-cache-coalescing.spec.ts,ai-combined-cost-cache.spec.ts,ai-cost-routing.spec.ts,ai-error-recovery.spec.ts,ai-events.spec.ts,ai-fanout.spec.ts,ai-filters.spec.ts,ai-identity.spec.ts,ai-memory.spec.ts,ai-memory-strategies.spec.ts,ai-routing.spec.ts,ai-session-stats.spec.ts,ai-streaming-dom.spec.ts,ai-budget.spec.ts,unified-console.spec.ts"
ALIAS_MAP["spring-boot-chat"]="spring-boot-chat.spec.ts,chat-observability.spec.ts,rooms-api.spec.ts,multi-client.spec.ts,history-cache.spec.ts,room-typing-direct.spec.ts"
ALIAS_MAP["grpc-chat"]="grpc-browser.spec.ts"

# ---------------------------------------------------------------------------
# Resolve spec files for a sample name
# Returns newline-separated list of existing spec file basenames.
# ---------------------------------------------------------------------------
resolve_specs() {
    local sample="$1"
    local matched=()

    # 1. Check known alias map first
    if [[ -n "${ALIAS_MAP[$sample]+x}" ]]; then
        IFS=',' read -ra aliases <<< "${ALIAS_MAP[$sample]}"
        for alias in "${aliases[@]}"; do
            if [[ -f "$SPEC_DIR/$alias" ]]; then
                matched+=("$alias")
            fi
        done
        # If aliases were defined, only use the alias results
        if [[ ${#matched[@]} -gt 0 ]]; then
            printf '%s\n' "${matched[@]}"
            return
        fi
    fi

    # 2. Direct match: sample name -> sample.spec.ts
    if [[ -f "$SPEC_DIR/${sample}.spec.ts" ]]; then
        matched+=("${sample}.spec.ts")
    fi

    # 3. Strip spring-boot- prefix and look for matches
    if [[ "$sample" == spring-boot-* ]]; then
        local stripped="${sample#spring-boot-}"

        # Direct stripped match
        if [[ -f "$SPEC_DIR/${stripped}.spec.ts" ]]; then
            matched+=("${stripped}.spec.ts")
        fi

        # Look for related specs: stripped prefix matches
        for spec_file in "$SPEC_DIR"/${stripped}*.spec.ts; do
            if [[ -f "$spec_file" ]]; then
                local basename
                basename="$(basename "$spec_file")"
                # Avoid duplicates
                local already=false
                for m in "${matched[@]+"${matched[@]}"}"; do
                    if [[ "$m" == "$basename" ]]; then
                        already=true
                        break
                    fi
                done
                if [[ "$already" == false ]]; then
                    matched+=("$basename")
                fi
            fi
        done
    fi

    if [[ ${#matched[@]} -gt 0 ]]; then
        printf '%s\n' "${matched[@]}"
    fi
}

# ---------------------------------------------------------------------------
# Count test cases in a spec file (lines matching test( or test.skip( )
# ---------------------------------------------------------------------------
count_tests() {
    local spec_file="$1"
    grep -cE '\btest(\.skip)?\(' "$spec_file" 2>/dev/null || echo 0
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
echo ""
echo "E2E Coverage Traceability Map"
echo "============================================="
echo ""

# Table header
printf "%-40s %-50s %6s   %s\n" "SAMPLE" "MATCHED SPECS" "TESTS" "STATUS"
printf "%-40s %-50s %6s   %s\n" "$(printf '%0.s-' {1..40})" "$(printf '%0.s-' {1..50})" "------" "----------"

total_runnable=0
total_covered=0
uncovered_samples=()

# Read runnable samples from samples.json
while IFS=$'\t' read -r name runnable; do
    if [[ "$runnable" != "true" ]]; then
        continue
    fi

    total_runnable=$((total_runnable + 1))

    # Resolve matching spec files
    specs_raw="$(resolve_specs "$name")"

    if [[ -z "$specs_raw" ]]; then
        printf "%-40s %-50s %6s   ${RED}%s${RESET}\n" "$name" "(none)" "0" "UNCOVERED"
        uncovered_samples+=("$name")
        continue
    fi

    # Count tests across all matched specs
    total_tests=0
    spec_list=""
    while IFS= read -r spec; do
        tc="$(count_tests "$SPEC_DIR/$spec")"
        total_tests=$((total_tests + tc))
        if [[ -n "$spec_list" ]]; then
            spec_list="$spec_list, $spec"
        else
            spec_list="$spec"
        fi
    done <<< "$specs_raw"

    # Truncate spec list for display if too long
    display_specs="$spec_list"
    if [[ ${#display_specs} -gt 48 ]]; then
        spec_count="$(echo "$specs_raw" | wc -l)"
        # Show first spec + count
        first_spec="$(echo "$specs_raw" | head -1)"
        display_specs="${first_spec} (+$((spec_count - 1)) more)"
    fi

    total_covered=$((total_covered + 1))
    printf "%-40s %-50s %6d   ${GREEN}%s${RESET}\n" "$name" "$display_specs" "$total_tests" "COVERED"

done < <(jq -r '.samples[] | [.name, (.runnable | tostring)] | @tsv' "$SAMPLES_JSON")

echo ""
printf "${BOLD}Summary: %d/%d runnable samples covered${RESET}\n" "$total_covered" "$total_runnable"

if [[ ${#uncovered_samples[@]} -gt 0 ]]; then
    echo ""
    echo -e "${RED}Uncovered samples:${RESET}"
    for s in "${uncovered_samples[@]}"; do
        echo "  - $s"
    done
    echo ""
    exit 1
fi

echo ""
echo -e "${GREEN}All runnable samples have E2E coverage.${RESET}"
exit 0

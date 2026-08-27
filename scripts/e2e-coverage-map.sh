#!/usr/bin/env bash
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

# e2e-coverage-map.sh — does every sample's declared coverage actually RESOLVE?
#
# scripts/release-gate-samples.sh owns the coverage map, and its verify_map()
# already checks that every sample HAS an entry and sits in exactly one shard.
# It does not check that the entry POINTS AT ANYTHING REAL. This script closes
# that gap, per tier:
#
#   pw:<mode>:<projects>  every project must exist in playwright.config.ts, its
#                         testMatch must resolve to a spec file on disk, and it
#                         must appear in a .github/workflows/e2e.yml matrix leg
#                         — a project in no leg never runs in CI.
#   fnd:<spec>|...        e2e/tests/<spec> must exist on disk.
#   smoke:<port>|<path>   HTTP probe only; no spec to resolve.
#   skip:<reason>         deliberately uncovered; reported, never failed.
#
# This script previously kept its OWN sample -> spec map (a hand-maintained
# ALIAS_MAP plus filename heuristics). It drifted to 10 false negatives out of
# 29 and was invoked by no workflow. It now consumes `release-gate-samples.sh
# --map` so there is exactly one coverage map in the repo.

set -euo pipefail

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
# Associative arrays (declare -A) need bash 4+. macOS ships bash 3.2 as
# /bin/bash, so this script resolves bash from PATH like every other script
# under scripts/. Fail legibly instead of "declare: -A: invalid option".
if [[ -z "${BASH_VERSINFO:-}" || "${BASH_VERSINFO[0]}" -lt 4 ]]; then
    echo "ERROR: bash 4+ is required (found ${BASH_VERSION:-unknown})." >&2
    echo "       On macOS: brew install bash" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$ROOT/scripts/release-gate-samples.sh"
PW_CONFIG="$ROOT/modules/integration-tests/playwright.config.ts"
PW_SPEC_DIR="$ROOT/modules/integration-tests/e2e"
FND_SPEC_DIR="$ROOT/e2e/tests"
E2E_WORKFLOW="$ROOT/.github/workflows/e2e.yml"

for f in "$GATE" "$PW_CONFIG" "$E2E_WORKFLOW"; do
    [[ -e "$f" ]] || { echo "ERROR: $f not found." >&2; exit 1; }
done

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; BOLD='\033[1m'; RESET='\033[0m'

# ---------------------------------------------------------------------------
# Playwright projects declared in playwright.config.ts, and the leg membership
# declared in e2e.yml. Both are read once.
# ---------------------------------------------------------------------------
declare -A PW_PROJECT_TESTMATCH
while IFS=$'\t' read -r proj match; do
    [[ -n "$proj" ]] && PW_PROJECT_TESTMATCH["$proj"]="$match"
done < <(awk "
    /name: '[^']+'/      { if (match(\$0, /name: '[^']+'/)) { n = substr(\$0, RSTART+7, RLENGTH-8) } }
    /testMatch:/         { if (n != \"\") { print n \"\t\" \$0; n = \"\" } }
" "$PW_CONFIG")

declare -A PW_PROJECT_IN_LEG
while read -r proj; do
    [[ -n "$proj" ]] && PW_PROJECT_IN_LEG["$proj"]=1
done < <(grep -oE 'projects: "[^"]+"' "$E2E_WORKFLOW" \
         | sed 's/projects: "//; s/"$//' | tr ',' '\n' | tr -d ' ' | sort -u)

# ---------------------------------------------------------------------------
# Resolve a testMatch regex to a spec file that exists under PW_SPEC_DIR.
# testMatch looks like:  testMatch: /ai-chat\.spec\.ts/,
# ---------------------------------------------------------------------------
spec_for_testmatch() {
    local raw="$1" pattern
    # Strip the JS regex delimiters, keep the pattern itself. Playwright matches
    # testMatch against the FULL file path, unanchored — which is why patterns
    # like /\/chat\.spec\.ts/ carry a leading slash to avoid also catching
    # agui-chat.spec.ts. Anchoring on the basename here would break exactly that.
    pattern="$(sed -E 's#.*testMatch: */##; s#/[a-z]*,? *$##' <<< "$raw")"
    pattern="${pattern//\\\\/}"
    [[ -n "$pattern" ]] || return 1
    local f
    for f in "$PW_SPEC_DIR"/*.spec.ts; do
        [[ -f "$f" ]] || continue
        [[ "$f" =~ $pattern ]] && { basename "$f"; return 0; }
    done
    return 1
}

count_tests() {
    grep -cE '\btest(\.skip)?\(' "$1" 2>/dev/null || echo 0
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
echo ""
echo "E2E Coverage Resolution Map"
echo "  source of truth: scripts/release-gate-samples.sh --map"
echo "======================================================================="
echo ""
printf "%-38s %-10s %-38s %6s  %s\n" "SAMPLE" "TIER" "RESOLVES TO" "TESTS" "STATUS"
printf "%-38s %-10s %-38s %6s  %s\n" \
    "$(printf '%0.s-' {1..38})" "----------" "$(printf '%0.s-' {1..38})" "------" "------"

total=0; ok=0; skipped=0
errors=()

while IFS=$'\t' read -r sample coverage; do
    [[ -n "$sample" ]] || continue
    total=$((total + 1))
    tier="${coverage%%:*}"

    case "$coverage" in
        "")
            printf "%-38s %-10s %-38s %6s  ${RED}%s${RESET}\n" "$sample" "-" "(no map entry)" "0" "MISSING"
            errors+=("$sample: no coverage-map entry in release-gate-samples.sh")
            continue
            ;;
        skip:*)
            skipped=$((skipped + 1))
            printf "%-38s %-10s %-38s %6s  ${YELLOW}%s${RESET}\n" \
                "$sample" "skip" "${coverage#skip:}" "-" "SKIPPED"
            continue
            ;;
        smoke:*)
            ok=$((ok + 1))
            printf "%-38s %-10s %-38s %6s  ${GREEN}%s${RESET}\n" \
                "$sample" "smoke" "HTTP ${coverage#smoke:}" "-" "OK"
            continue
            ;;
        fnd:*)
            spec="${coverage#fnd:}"; spec="${spec%%|*}"
            if [[ -f "$FND_SPEC_DIR/$spec" ]]; then
                ok=$((ok + 1))
                printf "%-38s %-10s %-38s %6d  ${GREEN}%s${RESET}\n" \
                    "$sample" "fnd" "e2e/tests/$spec" "$(count_tests "$FND_SPEC_DIR/$spec")" "OK"
            else
                printf "%-38s %-10s %-38s %6s  ${RED}%s${RESET}\n" \
                    "$sample" "fnd" "e2e/tests/$spec" "0" "NO SPEC"
                errors+=("$sample: fnd spec e2e/tests/$spec does not exist")
            fi
            continue
            ;;
        pw:*)
            projects="${coverage#pw:}"; projects="${projects#*:}"
            resolved=""; tests=0; bad=0
            IFS=',' read -ra plist <<< "$projects"
            for proj in "${plist[@]}"; do
                if [[ -z "${PW_PROJECT_TESTMATCH[$proj]+x}" ]]; then
                    errors+=("$sample: Playwright project '$proj' is not declared in playwright.config.ts")
                    bad=1; continue
                fi
                if ! spec="$(spec_for_testmatch "${PW_PROJECT_TESTMATCH[$proj]}")"; then
                    errors+=("$sample: project '$proj' testMatch resolves to no spec file under modules/integration-tests/e2e/")
                    bad=1; continue
                fi
                if [[ -z "${PW_PROJECT_IN_LEG[$proj]+x}" ]]; then
                    errors+=("$sample: project '$proj' is in no .github/workflows/e2e.yml matrix leg — its spec never runs in CI")
                    bad=1; continue
                fi
                tests=$((tests + $(count_tests "$PW_SPEC_DIR/$spec")))
                resolved="${resolved:+$resolved, }$spec"
            done
            if [[ "$bad" -eq 0 ]]; then
                ok=$((ok + 1)); status="OK"; color="$GREEN"
            else
                status="BROKEN"; color="$RED"
            fi
            display="${resolved:-(unresolved)}"
            [[ ${#display} -gt 36 ]] && display="${display%%,*} (+more)"
            printf "%-38s %-10s %-38s %6d  ${color}%s${RESET}\n" "$sample" "${tier}" "$display" "$tests" "$status"
            continue
            ;;
        *)
            printf "%-38s %-10s %-38s %6s  ${RED}%s${RESET}\n" "$sample" "?" "$coverage" "0" "UNKNOWN TIER"
            errors+=("$sample: unrecognised coverage tier '$coverage'")
            ;;
    esac
done < <("$GATE" --map)

echo ""
printf "${BOLD}Summary: %d/%d samples resolve (%d deliberately skipped)${RESET}\n" \
    "$ok" "$total" "$skipped"

if [[ ${#errors[@]} -gt 0 ]]; then
    echo ""
    echo -e "${RED}Coverage map does not resolve:${RESET}"
    for e in "${errors[@]}"; do echo "  - $e"; done
    echo ""
    echo "Fix the map in scripts/release-gate-samples.sh, or add the missing spec/project/leg."
    exit 1
fi

echo ""
echo -e "${GREEN}Every declared coverage entry resolves to a real spec, project and CI leg.${RESET}"
exit 0

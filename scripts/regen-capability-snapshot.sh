#!/usr/bin/env bash
#
# Regenerate .harness/capabilities.snapshot.json from canonical sources.
#
# Sources of truth (read-only):
#   1. modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java
#      — enumerates every AiCapability constant.
#   2. modules/<runtime>/src/test/{java,kotlin}/**/<X>RuntimeContractTest.{java,kt}
#      — each runtime's pinned `expectedCapabilities()` declaration.
#
# Output: .harness/capabilities.snapshot.json (deterministic, alphabetical).
#
# Why a snapshot when the contract tests already pin per-runtime sets:
# the per-runtime tests catch code drift, but they don't catch *prose drift*
# in modules/ai/README.md or aggregate count claims in CHANGELOG ("9 runtimes",
# "20 capabilities"). The snapshot is the diff-reviewable artifact prose
# claims are validated against; PR reviewers see "9 → 10 runtimes" without
# grepping. Pair with scripts/validate-capability-claims.sh (run from
# pre-push) and CapabilitySnapshotTest (run from `mvn test`).
#
# Usage:
#   ./scripts/regen-capability-snapshot.sh           # write the snapshot
#   ./scripts/regen-capability-snapshot.sh --check   # exit 1 if it would change
#   ./scripts/regen-capability-snapshot.sh --stdout  # write to stdout

set -euo pipefail

# Force C locale so sort orders match Java's String.compareTo (code-point order
# for ASCII identifiers). Locale-aware sort treats `_` differently than `I`,
# which would diverge from CapabilitySnapshotTest's TreeSet<String>.
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

MODE="write"
for arg in "$@"; do
    case "$arg" in
        --check)  MODE="check" ;;
        --stdout) MODE="stdout" ;;
        *) echo "regen-capability-snapshot.sh: unknown arg $arg" >&2; exit 2 ;;
    esac
done

CAPABILITY_SOURCE="modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java"
SNAPSHOT_PATH=".harness/capabilities.snapshot.json"

if [ ! -f "$CAPABILITY_SOURCE" ]; then
    echo "regen-capability-snapshot.sh: $CAPABILITY_SOURCE not found — wrong working dir?" >&2
    exit 1
fi

# Extract enum constants from AiCapability.java.
# An enum constant in this file is a bare ALL_CAPS_WITH_UNDERSCORES identifier
# at the start of a line (after optional whitespace), terminated by `,` or `}`.
# This deliberately ignores the `,` after the last constant and the trailing `}`.
mapfile -t CAPABILITIES < <(
    awk '
        /^public enum AiCapability/ { in_enum = 1; next }
        # The last enum constant may have no trailing comma — terminator is
        # the closing `}` on its own line. Stop scanning when we hit that.
        in_enum && /^}/ { in_enum = 0; next }
        in_enum && /^[[:space:]]*[A-Z][A-Z0-9_]+[[:space:]]*([,;]|$)/ {
            match($0, /[A-Z][A-Z0-9_]+/)
            print substr($0, RSTART, RLENGTH)
        }
    ' "$CAPABILITY_SOURCE" | sort -u
)
CAPABILITY_COUNT=${#CAPABILITIES[@]}

if [ "$CAPABILITY_COUNT" -eq 0 ]; then
    echo "regen-capability-snapshot.sh: parsed 0 capabilities from $CAPABILITY_SOURCE — parser broken?" >&2
    exit 1
fi

# Find runtime contract tests (excluding the abstract base and embedding contract tests).
mapfile -t CONTRACT_TESTS < <(
    find modules -type f \( -name "*RuntimeContractTest.java" -o -name "*RuntimeContractTest.kt" \) \
        | grep -v "Abstract" \
        | grep -v "Embedding" \
        | sort
)

if [ ${#CONTRACT_TESTS[@]} -eq 0 ]; then
    echo "regen-capability-snapshot.sh: 0 contract tests found — wrong tree?" >&2
    exit 1
fi

# For each contract test, extract:
#   - runtime_name (XxxRuntimeContractTest → XxxAgentRuntime)
#   - module_path (modules/<X>)
#   - language (java | kotlin)
#   - expected_capabilities (the AiCapability.X identifiers inside expectedCapabilities())
runtime_entries=""
runtime_count=0
for test_file in "${CONTRACT_TESTS[@]}"; do
    test_class=$(basename "$test_file")
    test_class="${test_class%.java}"
    test_class="${test_class%.kt}"
    # XxxRuntimeContractTest → XxxAgentRuntime
    runtime_name="${test_class%RuntimeContractTest}AgentRuntime"

    # modules/<X>/src/test/...  →  modules/<X>
    module_path=$(echo "$test_file" | awk -F'/src/test/' '{print $1}')

    if [[ "$test_file" == *.kt ]]; then language="kotlin"; else language="java"; fi

    # Extract the body of expectedCapabilities() — from the line containing the
    # signature down to the matching closing parenthesis on its own line. Then
    # collect every AiCapability.<NAME> reference inside.
    mapfile -t caps < <(
        awk '
            /expectedCapabilities/ { capturing = 1 }
            capturing { print }
            capturing && /^[[:space:]]*\)/ { capturing = 0 }
            capturing && /^[[:space:]]*[}][[:space:]]*$/ { capturing = 0 }
        ' "$test_file" \
        | grep -oE 'AiCapability\.[A-Z][A-Z0-9_]+' \
        | sed 's/AiCapability\.//' \
        | sort -u
    )

    if [ ${#caps[@]} -eq 0 ]; then
        echo "regen-capability-snapshot.sh: 0 capabilities parsed from $test_file" >&2
        exit 1
    fi

    caps_json="[$(printf '"%s",' "${caps[@]}" | sed 's/,$//')]"

    if [ -n "$runtime_entries" ]; then runtime_entries="$runtime_entries,"; fi
    runtime_entries="$runtime_entries
    {
      \"name\": \"$runtime_name\",
      \"module\": \"$module_path\",
      \"language\": \"$language\",
      \"contract_test\": \"$test_file\",
      \"expected_capabilities\": $caps_json
    }"
    runtime_count=$((runtime_count + 1))
done

caps_top_json="[$(printf '"%s",' "${CAPABILITIES[@]}" | sed 's/,$//')]"

new_snapshot=$(cat <<EOF
{
  "schema_version": 1,
  "_purpose": "Canonical aggregate of AiCapability enum + each runtime's pinned expectedCapabilities(). Validates aggregate count claims in modules/ai/README.md, CHANGELOG, and website docs. Regenerate with scripts/regen-capability-snapshot.sh.",
  "capabilities": {
    "count": $CAPABILITY_COUNT,
    "names": $caps_top_json
  },
  "runtimes": {
    "count": $runtime_count,
    "items": [$runtime_entries
    ]
  }
}
EOF
)

case "$MODE" in
    stdout)
        printf '%s\n' "$new_snapshot"
        ;;
    check)
        if [ ! -f "$SNAPSHOT_PATH" ]; then
            echo "regen-capability-snapshot.sh: $SNAPSHOT_PATH does not exist — run without --check to create it" >&2
            exit 1
        fi
        existing=$(cat "$SNAPSHOT_PATH")
        if [ "$existing" != "$new_snapshot" ]; then
            echo "regen-capability-snapshot.sh: $SNAPSHOT_PATH is stale" >&2
            echo "Diff (existing → regenerated):" >&2
            diff <(echo "$existing") <(echo "$new_snapshot") >&2 || true
            echo "" >&2
            echo "Regenerate with: ./scripts/regen-capability-snapshot.sh" >&2
            exit 1
        fi
        echo "regen-capability-snapshot.sh: $SNAPSHOT_PATH is fresh ($runtime_count runtimes, $CAPABILITY_COUNT capabilities)"
        ;;
    write)
        mkdir -p .harness
        printf '%s\n' "$new_snapshot" > "$SNAPSHOT_PATH"
        echo "regen-capability-snapshot.sh: wrote $SNAPSHOT_PATH ($runtime_count runtimes, $CAPABILITY_COUNT capabilities)"
        ;;
esac

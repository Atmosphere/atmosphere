#!/usr/bin/env bash
#
# Validate that prose claims about runtime / capability counts in
# modules/ai/README.md agree with the canonical snapshot at
# .harness/capabilities.snapshot.json.
#
# Two checks:
#   1. The snapshot itself is fresh (matches the source of truth in
#      modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java + the
#      per-runtime *RuntimeContractTest files). Delegates to
#      scripts/regen-capability-snapshot.sh --check.
#   2. README count claims agree with the snapshot. Looks for tight,
#      unambiguous patterns:
#        - `All N runtimes`        (the "All" anchor → unambiguous total)
#        - `N AiCapability` / `N capabilities total`
#      Loose patterns ("the other N runtimes", "N out of M") are not
#      validated here — they describe subsets that depend on which
#      runtimes opt into a feature, which the snapshot does not encode.
#
# Run from repo root. Exits 0 on success, 1 on any drift.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

SNAPSHOT=".harness/capabilities.snapshot.json"
README="modules/ai/README.md"
fail=0

# 1. Snapshot freshness.
if ! ./scripts/regen-capability-snapshot.sh --check >/dev/null 2>&1; then
    ./scripts/regen-capability-snapshot.sh --check >&2 || true
    fail=1
fi

if [ ! -f "$SNAPSHOT" ]; then
    echo "validate-capability-claims.sh: $SNAPSHOT not found — run ./scripts/regen-capability-snapshot.sh first" >&2
    exit 1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi

# Parse expected counts from the snapshot. python3 is on every dev box
# (Atmosphere already shells out to it via promote-changelog.py).
runtime_count=$(python3 -c 'import json,sys; print(json.load(open(".harness/capabilities.snapshot.json"))["runtimes"]["count"])')
capability_count=$(python3 -c 'import json,sys; print(json.load(open(".harness/capabilities.snapshot.json"))["capabilities"]["count"])')

# 2. README claim validation.
if [ ! -f "$README" ]; then
    echo "validate-capability-claims.sh: $README not found" >&2
    exit 1
fi

while IFS=: read -r lineno text; do
    n=$(echo "$text" | grep -oE 'All [0-9]+ runtimes?' | grep -oE '[0-9]+' | head -1)
    if [ -n "$n" ] && [ "$n" != "$runtime_count" ]; then
        echo "validate-capability-claims.sh: $README:$lineno claims 'All $n runtimes' but snapshot has $runtime_count" >&2
        echo "    line: $text" >&2
        fail=1
    fi
done < <(grep -nE '\bAll [0-9]+ runtimes?\b' "$README" || true)

while IFS=: read -r lineno text; do
    n=$(echo "$text" | grep -oE '[0-9]+ (AiCapability|capabilities total|capabilities count)' | grep -oE '[0-9]+' | head -1)
    if [ -n "$n" ] && [ "$n" != "$capability_count" ]; then
        echo "validate-capability-claims.sh: $README:$lineno claims '$n capabilities' but snapshot has $capability_count" >&2
        echo "    line: $text" >&2
        fail=1
    fi
done < <(grep -nE '[0-9]+ (AiCapability|capabilities total|capabilities count)' "$README" || true)

if [ "$fail" -ne 0 ]; then
    echo "" >&2
    echo "Fix the prose to match the snapshot, OR (if the snapshot is wrong) regenerate:" >&2
    echo "    ./scripts/regen-capability-snapshot.sh" >&2
    exit 1
fi

echo "validate-capability-claims.sh: OK ($runtime_count runtimes, $capability_count capabilities — README claims match snapshot)"

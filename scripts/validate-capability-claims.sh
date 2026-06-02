#!/usr/bin/env bash
#
# Validate that prose claims about runtime / capability counts in
# modules/ai/README.md agree with the canonical snapshot at
# .harness/capabilities.snapshot.json.
#
# Three checks:
#   1. The snapshot itself is fresh (matches the source of truth in
#      modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java + the
#      per-runtime *RuntimeContractTest files). Delegates to
#      scripts/regen-capability-snapshot.sh --check.
#   2. The modules/ai/README.md AgentRuntime table has one row per runtime
#      and each row's capability tokens match the snapshot.
#   3. README count claims agree with the snapshot. Looks for tight,
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
TOP_README="README.md"
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

# 1b. Per-runtime SKILLCARD.yaml freshness — same gate as the snapshot since
# every card is derived from it. Drift here means a runtime's pinned
# capability set changed but the card wasn't regenerated.
if ! ./scripts/regen-skillcards.sh --check >/dev/null 2>&1; then
    ./scripts/regen-skillcards.sh --check >&2 || true
    fail=1
fi

# 1c. SkillSpector pre-publish scan — HIGH-severity gate. Same Tier 1
# as freshness because a card pointing at a removed SPI class or
# carrying prompt-injection markers should fail pre-push, not slip
# through to the tag-time signing workflow that would then publish a
# compromised manifest as "signed".
if ! ./scripts/scan-skillcards.sh --check >/dev/null 2>&1; then
    ./scripts/scan-skillcards.sh --check >&2 || true
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi

# Parse expected counts from the snapshot. python3 is on every dev box
# (Atmosphere already shells out to it via promote-changelog.py).
runtime_count=$(python3 -c 'import json,sys; print(json.load(open(".harness/capabilities.snapshot.json"))["runtimes"]["count"])')
capability_count=$(python3 -c 'import json,sys; print(json.load(open(".harness/capabilities.snapshot.json"))["capabilities"]["count"])')

# 2. README table validation.
if [ ! -f "$README" ]; then
    echo "validate-capability-claims.sh: $README not found" >&2
    exit 1
fi

if ! python3 - "$SNAPSHOT" "$README" <<'PY'
import json
import re
import sys
from pathlib import Path

snapshot_path = Path(sys.argv[1])
readme_path = Path(sys.argv[2])
snapshot = json.loads(snapshot_path.read_text())
readme = readme_path.read_text()

expected = {
    runtime["name"]: set(runtime["expected_capabilities"])
    for runtime in snapshot["runtimes"]["items"]
}

rows = {}
for line_no, line in enumerate(readme.splitlines(), 1):
    if not line.startswith("| `atmosphere-"):
        continue
    cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
    if len(cells) < 4:
        continue
    implementation = re.search(r"`([A-Za-z0-9]+AgentRuntime)`", cells[1])
    if implementation is None:
        continue
    capabilities = set(re.findall(r"\b[A-Z][A-Z0-9_]+\b", cells[3]))
    rows[implementation.group(1)] = (line_no, capabilities)

failed = False
for implementation, capabilities in sorted(expected.items()):
    row = rows.get(implementation)
    if row is None:
        print(
            f"validate-capability-claims.sh: {readme_path} is missing "
            f"AgentRuntime table row for {implementation}",
            file=sys.stderr,
        )
        failed = True
        continue
    line_no, actual = row
    missing = sorted(capabilities - actual)
    extra = sorted(actual - capabilities)
    if missing or extra:
        print(
            f"validate-capability-claims.sh: {readme_path}:{line_no} "
            f"capabilities for {implementation} drifted from snapshot",
            file=sys.stderr,
        )
        if missing:
            print(f"    missing: {', '.join(missing)}", file=sys.stderr)
        if extra:
            print(f"    extra: {', '.join(extra)}", file=sys.stderr)
        failed = True

for implementation, (line_no, _) in sorted(rows.items()):
    if implementation not in expected:
        print(
            f"validate-capability-claims.sh: {readme_path}:{line_no} "
            f"documents unknown runtime {implementation}",
            file=sys.stderr,
        )
        failed = True

if failed:
    sys.exit(1)
PY
then
    fail=1
fi

# 3. README claim validation.
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

# 4. Top-level README count claims. The same drift class bit us when
# the eleventh adapter (Cohere) landed and prose on README.md was only
# updated in one of four spots. Patterns guarded here:
#   - `N runtime adapters`     (matches "11 runtime adapters")
#   - `SPI + N adapters`       (matches "AgentRuntime SPI + 11 adapters")
# Patterns deliberately NOT guarded structurally:
#   - "N additional adapters"  (offset by 1, depends on Built-in being
#                                counted separately — too ambiguous for
#                                a structural check without false hits)
# Both numeric and word forms ("eleven") are normalized to numeric
# before comparison.
if [ -f "$TOP_README" ]; then
    declare -A word_to_num=(
        [one]=1 [two]=2 [three]=3 [four]=4 [five]=5
        [six]=6 [seven]=7 [eight]=8 [nine]=9 [ten]=10
        [eleven]=11 [twelve]=12 [thirteen]=13 [fourteen]=14 [fifteen]=15
    )
    while IFS=: read -r lineno text; do
        # Match either "<digit>+ <something> adapters" or
        # "<word> <something> adapters" where <something> is empty
        # or 'runtime'.
        raw=$(echo "$text" | grep -oE '\b([0-9]+|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen) (runtime )?adapters\b' | head -1)
        [ -z "$raw" ] && continue
        token=$(echo "$raw" | awk '{print $1}' | tr '[:upper:]' '[:lower:]')
        if [[ "$token" =~ ^[0-9]+$ ]]; then
            n=$token
        else
            n="${word_to_num[$token]:-}"
        fi
        [ -z "$n" ] && continue
        if [ "$n" != "$runtime_count" ]; then
            echo "validate-capability-claims.sh: $TOP_README:$lineno claims '$raw' but snapshot has $runtime_count runtimes" >&2
            echo "    line: $text" >&2
            fail=1
        fi
    done < <(grep -nE '\b([0-9]+|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen) (runtime )?adapters\b' "$TOP_README" -i || true)
fi

if [ "$fail" -ne 0 ]; then
    echo "" >&2
    echo "Fix the prose to match the snapshot, OR (if the snapshot is wrong) regenerate:" >&2
    echo "    ./scripts/regen-capability-snapshot.sh" >&2
    exit 1
fi

echo "validate-capability-claims.sh: OK ($runtime_count runtimes, $capability_count capabilities — README claims match snapshot)"

#!/usr/bin/env bash
#
# Structural-hygiene validator for .harness/drift-log.md.
#
# Catches:
#   1. Missing file.
#   2. Future-dated section headers (`## YYYY-MM-DD …`).
#   3. Date sections out of chronological order (newest must be at the bottom
#      to preserve the append-only contract).
#   4. A past date section's header removed or reworded (compared against
#      origin/main). Only the `## YYYY-MM-DD …` header lines are compared;
#      edits to a section's body are not detected.
#   5. A section dated after FROZEN_ON. The log was frozen on that date: new
#      drift is filed as a carnet issue (.agents/skills/carnet/carnet.sh create),
#      which has an owner and a close-out, instead of appended here.
#
# Run from repo root. Exits 0 on success, 1 on any violation.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

LOG=".harness/drift-log.md"
TODAY="$(date +%Y-%m-%d)"
FROZEN_ON="2026-11-02"
fail=0

if [ ! -f "$LOG" ]; then
    echo "validate-drift-log.sh: $LOG missing" >&2
    exit 1
fi

# 1. Extract every "## YYYY-MM-DD" header line (with optional trailing context)
mapfile -t section_headers < <(grep -nE '^## [0-9]{4}-[0-9]{2}-[0-9]{2}' "$LOG" || true)

if [ ${#section_headers[@]} -eq 0 ]; then
    echo "validate-drift-log.sh: $LOG has no date sections — at least one required" >&2
    exit 1
fi

# 2. No future-dated sections.
for entry in "${section_headers[@]}"; do
    line_no="${entry%%:*}"
    date=$(echo "$entry" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
    if [[ "$date" > "$TODAY" ]]; then
        echo "validate-drift-log.sh: $LOG:$line_no future-dated section '$date' (today is $TODAY)" >&2
        fail=1
    fi
done

# 3. Sections in chronological order (oldest → newest, top → bottom).
prev_date=""
for entry in "${section_headers[@]}"; do
    line_no="${entry%%:*}"
    date=$(echo "$entry" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
    if [ -n "$prev_date" ] && [[ "$date" < "$prev_date" ]]; then
        echo "validate-drift-log.sh: $LOG:$line_no section '$date' precedes '$prev_date' — sections must be chronological (oldest first)" >&2
        fail=1
    fi
    prev_date="$date"
done

# 4. Append-only check vs origin/main: every header of a pre-existing date
#    section (older than today) must still exist verbatim. Section bodies are
#    not compared.
if git rev-parse --verify origin/main >/dev/null 2>&1 \
   && git cat-file -e "origin/main:$LOG" 2>/dev/null; then
    # Walk backward through the historical sections in origin/main and
    # confirm each header line still exists in the working copy.
    while IFS= read -r line; do
        date=$(echo "$line" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
        # Skip today's section — entries can be added below it; the section
        # may also legitimately be extended.
        if [ "$date" = "$TODAY" ]; then
            continue
        fi
        # The exact header line should still exist verbatim.
        if ! grep -qFx "$line" "$LOG"; then
            echo "validate-drift-log.sh: pre-existing section header missing/modified — append-only violation:" >&2
            echo "    expected verbatim: $line" >&2
            fail=1
        fi
    done < <(git show "origin/main:$LOG" 2>/dev/null | grep -E '^## [0-9]{4}-[0-9]{2}-[0-9]{2}')
fi

# 5. Frozen: no section dated after FROZEN_ON.
for entry in "${section_headers[@]}"; do
    line_no="${entry%%:*}"
    date=$(echo "$entry" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
    if [[ "$date" > "$FROZEN_ON" ]]; then
        echo "validate-drift-log.sh: $LOG:$line_no section '$date' is after the freeze ($FROZEN_ON) — file the drift as a carnet issue instead (.agents/skills/carnet/carnet.sh create)" >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "" >&2
    echo "Fix the violations above. Append-only means: don't edit past sections," >&2
    echo "don't reorder, don't future-date. New entries go in today's section" >&2
    echo "(or a new section at the bottom of the file)." >&2
    exit 1
fi

echo "validate-drift-log.sh: OK ($LOG: ${#section_headers[@]} section(s), frozen on $FROZEN_ON)"

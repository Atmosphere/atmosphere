#!/usr/bin/env bash
#
# Stop hook for the Atmosphere repo. Detects drift-correction language in the
# session transcript and blocks the stop if .harness/drift-log.md was not
# updated this session.
#
# Hook input on stdin (JSON):
#   {
#     "session_id": "...",
#     "transcript_path": "/path/to/transcript.jsonl",
#     "stop_hook_active": false
#   }
#
# Hook output on stdout (JSON):
#   {}                                       → continue normally
#   {"decision": "block", "reason": "..."}   → re-engage agent with reason
#
# Patterns are deliberately high-precision (low false-positive) so that
# blocking only fires on clear drift-correction signals. Tighten or
# loosen by editing the `patterns` array.

set -euo pipefail

# Read hook input
INPUT_JSON="$(cat)"

# Parse fields with python (always available on macOS dev boxes)
TRANSCRIPT="$(printf '%s' "$INPUT_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("transcript_path",""))' 2>/dev/null || echo "")"
STOP_ACTIVE="$(printf '%s' "$INPUT_JSON" | python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("stop_hook_active", False)).lower())' 2>/dev/null || echo "false")"

if [ -z "$TRANSCRIPT" ] || [ ! -f "$TRANSCRIPT" ]; then
    echo '{}'
    exit 0
fi

# Don't loop. If we already blocked once this session, don't block again —
# the agent has had its chance to address the prompt.
if [ "$STOP_ACTIVE" = "true" ]; then
    echo '{}'
    exit 0
fi

# Locate repo root via the transcript's path heuristic — the hook's own
# location works since this script lives inside the repo.
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG="$REPO_ROOT/.harness/drift-log.md"

if [ ! -f "$LOG" ]; then
    echo '{}'
    exit 0
fi

# High-precision drift-correction patterns. Each is intentionally narrow to
# minimize false positives. Add new patterns only with concrete real-session
# evidence that they're not over-broad.
patterns=(
    'stale memory'
    '\boff-by-one\b'
    'I (was wrong|claimed)[^.]{0,120}(but|actual|truth)'
    'memor[a-z]+[[:space:]]+(was|is)[[:space:]]+(wrong|stale|out of date)'
    'fabricated[[:space:]]+(rule|stat|count|claim)'
    'verified by grep[^.]{0,60}(disagree|contradict|wrong|stale)'
)

# Build a single ERE alternation
re="$(IFS='|'; echo "${patterns[*]}")"

if ! grep -qiE "$re" "$TRANSCRIPT" 2>/dev/null; then
    echo '{}'
    exit 0
fi

# Drift correction signal present. Was drift-log.md modified this session?
log_changed=false

# (a) staged or unstaged changes in the working tree
if ! git -C "$REPO_ROOT" diff --quiet -- "$LOG" 2>/dev/null; then
    log_changed=true
fi
if ! git -C "$REPO_ROOT" diff --cached --quiet -- "$LOG" 2>/dev/null; then
    log_changed=true
fi

# (b) untracked path (file added but not yet staged). Capture first to avoid
# the same pipefail/SIGPIPE pitfall as below.
if [ "$log_changed" = "false" ]; then
    porcelain="$(git -C "$REPO_ROOT" status --porcelain -- "$LOG" 2>/dev/null || true)"
    if echo "$porcelain" | grep -q '^??'; then
        log_changed=true
    fi
fi

# (c) modified in any of the last 3 commits on the current branch.
# Capture output first because `git log | grep -q` triggers SIGPIPE that
# `set -o pipefail` reports as a non-zero pipeline exit (false negative).
if [ "$log_changed" = "false" ]; then
    recent_files="$(git -C "$REPO_ROOT" log -3 --name-only --pretty=format: 2>/dev/null || true)"
    if echo "$recent_files" | grep -q '^\.harness/drift-log\.md$'; then
        log_changed=true
    fi
fi

if [ "$log_changed" = "true" ]; then
    echo '{}'
    exit 0
fi

# Drift correction signal present, log unchanged. Block the stop and tell the
# agent what to do.
python3 <<'PY'
import json
print(json.dumps({
    "decision": "block",
    "reason": (
        "Drift correction language detected in session transcript, but "
        ".harness/drift-log.md was not modified this session. "
        "Per feedback_drift_log.md, every caught drift gets a structured "
        "entry (date, claim, truth, slip path, gate added) appended in the "
        "same session. Either:\n"
        "  (a) append the entry now, OR\n"
        "  (b) if the correction was trivial enough not to warrant a log "
        "entry, state that explicitly in one sentence — the hook is a "
        "one-shot per session (stop_hook_active will be true on the next "
        "invocation, so a deliberate skip won't loop)."
    )
}))
PY
exit 0

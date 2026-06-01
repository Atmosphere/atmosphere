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
#
# Worktree-aware: the project mandates branch work in `.claude/worktrees/*`
# (feedback_worktree_first), so the entry is usually committed on a feature
# branch in a separate worktree that the main checkout's HEAD never sees until
# merge. Checking only $REPO_ROOT's current branch produced false positives
# (it blocked even after the entry was correctly logged on the worktree branch).
# Scan every worktree, using the path relative to each root so `git -C` resolves
# it in that worktree's tree, not the main checkout's.
log_changed=false
LOG_REL=".harness/drift-log.md"

# Collect all worktree roots (the porcelain listing includes the main checkout).
roots=()
while IFS= read -r line; do
    case "$line" in
        worktree\ *) roots+=("${line#worktree }") ;;
    esac
done < <(git -C "$REPO_ROOT" worktree list --porcelain 2>/dev/null || true)
if [ "${#roots[@]}" -eq 0 ]; then
    roots=("$REPO_ROOT")
fi

for root in "${roots[@]}"; do
    [ -d "$root" ] || continue

    # (a) staged or unstaged changes in this worktree's tree
    if ! git -C "$root" diff --quiet -- "$LOG_REL" 2>/dev/null; then
        log_changed=true; break
    fi
    if ! git -C "$root" diff --cached --quiet -- "$LOG_REL" 2>/dev/null; then
        log_changed=true; break
    fi

    # (b) untracked path (added but not yet staged)
    porcelain="$(git -C "$root" status --porcelain -- "$LOG_REL" 2>/dev/null || true)"
    if echo "$porcelain" | grep -q '^??'; then
        log_changed=true; break
    fi

    # (c) modified in any of the last 3 commits on this worktree's branch.
    # Capture output first because `git log | grep -q` triggers SIGPIPE that
    # `set -o pipefail` reports as a non-zero pipeline exit (false negative).
    recent_files="$(git -C "$root" log -3 --name-only --pretty=format: 2>/dev/null || true)"
    if echo "$recent_files" | grep -q '^\.harness/drift-log\.md$'; then
        log_changed=true; break
    fi
done

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

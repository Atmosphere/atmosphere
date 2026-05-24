#!/usr/bin/env bash
#
# Validate that the project maintainer's private role-play handle
# (defined in their local ~/.claude/CLAUDE.md and never intended for
# committed artifacts) does not appear in tracked files.
#
# Background: a "no more hallucinations" review on 2026-05-23 caught the
# handle in 22 committed files — drift-log entries, sample personas,
# test fixtures, production javadoc examples, and CI workflow comments.
# The handle reads naturally inside an agent session but is meaningless
# (or worse, a private-handle leak) to anyone outside the maintainer's
# local agent context. See drift-log entry #58 for the full story.
#
# Scope of the check:
#   - Greps tracked text files for the literal handle (case-insensitive).
#   - Skips `.claude/` (agent-private workspace, not pushed; CLAUDE.md
#     literally defines the handle).
#   - Skips `.harness/sessions/` (transient session transcripts that are
#     committed as-is for audit purposes; they reflect live agent
#     conversation and the handle there is the conversation's own form).
#   - Skips any path matching an entry in
#     `.harness/private-handle-allowlist.txt` (line-based glob list, `#`
#     comments allowed) — use sparingly, only for tokens that look like
#     the handle but are genuinely unrelated (collisions are unlikely
#     given the handle's distinctiveness, but the escape hatch is
#     useful if one ever materialises).
#
# Usage:
#   ./scripts/validate-no-private-handle.sh           # exit 1 on any hit
#   ./scripts/validate-no-private-handle.sh --check   # alias of default
#
# Exit codes:
#   0 — clean (no occurrences in scope)
#   1 — at least one occurrence in scope; per-line diagnostics on stderr

set -euo pipefail

HANDLE='ChefFamille'
ALLOWLIST='.harness/private-handle-allowlist.txt'

cd "$(git rev-parse --show-toplevel)"

# Build the exclude-pathspecs list. Always exclude the agent-private
# workspace, transient session transcripts, and this script itself
# (which has to reference the literal handle to look for it).
exclude_pathspecs=(
    ':!.claude/**'
    ':!.harness/sessions/**'
    ':!scripts/validate-no-private-handle.sh'
)

# Plus anything in the allowlist file.
if [ -f "$ALLOWLIST" ]; then
    while IFS= read -r line; do
        # Strip trailing whitespace + comments; skip blanks.
        clean=$(echo "$line" | sed -E 's/#.*$//' | xargs)
        [ -z "$clean" ] && continue
        exclude_pathspecs+=(":!${clean}")
    done <"$ALLOWLIST"
fi

# Case-insensitive grep — the handle is case-distinctive but defensive.
# `-I` skips binary files. `-l` would just list filenames; we want
# line-level diagnostics so the operator can see exactly where to scrub.
hits=$(git grep -nIi -e "$HANDLE" -- "${exclude_pathspecs[@]}" 2>/dev/null || true)

if [ -z "$hits" ]; then
    echo "validate-no-private-handle.sh: OK (no '$HANDLE' references outside agent-private paths)"
    exit 0
fi

echo "validate-no-private-handle.sh: found '$HANDLE' references in committed artifacts:" >&2
echo "$hits" >&2
echo "" >&2
echo "Fix the leak before pushing. Options:" >&2
echo "  1. Replace with a neutral identifier — 'the project maintainer'" >&2
echo "     for narrative prose, 'Alice'/'Alex' for test/sample personas." >&2
echo "  2. If the reference is genuinely needed (rare), allowlist the" >&2
echo "     path in $ALLOWLIST with a comment explaining why." >&2
exit 1

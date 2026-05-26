#!/usr/bin/env bash
# validate-no-beta-on-main.sh
#
# Enforces feedback_no_beta_on_main.md: Atmosphere cuts releases frequently
# from main, so main is always release-ready. No `@Beta` annotations, no
# `@api.status Beta` Javadoc, no `⏳` deferred markers in capability
# inventories, no narrative deferral framings ("deferred to", "next session",
# "next iteration", "out of scope for @Beta") on main.
#
# Internal planning labels in committed code (Phase N / Wave N) are ALSO
# caught here — see feedback_commit_messages.md.
#
# Diff-aware: only fires on lines ADDED in the pushed commits versus the
# base ref (origin/main by default). Pre-existing tech debt on main is not
# this push's accountability; new additions are.
#
# Allowlist:
#   - .harness/drift-log.md            : append-only historical narrative
#   - CHANGELOG.md historical sections : past-release entries describing
#                                        previously-shipped states. The
#                                        `## [Unreleased]` block is NOT
#                                        allowlisted — Unreleased entries
#                                        are about to ship and must already
#                                        be clean.
#
# Exit codes:
#   0   no violations introduced by this push
#   1   one or more violations introduced

set -euo pipefail

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
cd "$PROJECT_ROOT"

BASE_REF="${BASE_REF:-origin/main}"

# Confirm the base ref exists; if not, fall back to merge-base of HEAD~1
if ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
    if git rev-parse --verify --quiet 'HEAD~1' >/dev/null; then
        BASE_REF='HEAD~1'
    else
        echo "validate-no-beta-on-main.sh: cannot resolve base ref; skipping."
        exit 0
    fi
fi

# Patterns flagged as @Beta-on-main violations:
#   @Beta\b            -> Java annotation / markdown tag
#   \(@Beta\)          -> "(@Beta)" inline status framing
#   @api\.status[[:space:]]+Beta -> Javadoc status tag
#   ⏳                 -> deferred-marker emoji used in capability inventories
#   deferred to        -> narrative deferral ("deferred to v2", "deferred to next session")
#   next session       -> roadmap framing in committed prose
#   next iteration     -> ditto
#   out of scope for @Beta -> only-true-in-Beta-context framing
#   Phase [0-9]        -> internal planning label per feedback_commit_messages.md
#   Wave [0-9]         -> ditto
#
# Patterns are matched on ADDED lines (lines starting with `+` in the diff,
# excluding the file header lines that start with `+++`).
PATTERN='@Beta\b|\(@Beta\)|@api\.status[[:space:]]+Beta|⏳|deferred to |next session|next iteration|out of scope for @Beta|Phase [0-9]|Wave [0-9]'

# Files whose diffs are exempt from this gate. The diff is still scanned for
# all OTHER files; an exempt file simply does not contribute violations.
# - drift-log.md: append-only historical narrative
# - the validator script itself: must mention the patterns it detects
# - pre-push-validate.sh: documents the validator in its error messages
# - feedback_no_beta_on_main.md: the memory document defining the rule
# - allowlist file itself: tooling
ALLOWLIST='^\.harness/drift-log\.md$|^scripts/validate-no-beta-on-main\.sh$|^scripts/pre-push-validate\.sh$|^\.harness/no-beta-allowlist\.txt$|/feedback_no_beta_on_main\.md$'

# Get the list of changed files in the push range
CHANGED_FILES=$(git diff --name-only "$BASE_REF"...HEAD 2>/dev/null || true)

if [ -z "$CHANGED_FILES" ]; then
    echo "validate-no-beta-on-main.sh: no changed files vs $BASE_REF; OK"
    exit 0
fi

VIOLATIONS=""
VIOLATION_COUNT=0

while IFS= read -r file; do
    [ -z "$file" ] && continue
    # Skip allowlisted paths entirely
    if echo "$file" | grep -qE "$ALLOWLIST"; then
        continue
    fi
    # Skip binary files
    if [ -f "$file" ] && ! file "$file" | grep -q text; then
        continue
    fi
    # Special handling for CHANGELOG.md: only the [Unreleased] section is
    # scanned. Past-release entries are historical and may legitimately
    # mention @Beta when describing what was avoided / fixed.
    if [ "$file" = "CHANGELOG.md" ]; then
        # Compute the line range of the [Unreleased] section in the post-push
        # state, then check the diff intersected with that range.
        unreleased_start=$(grep -n '^## \[Unreleased\]' "$file" 2>/dev/null | head -1 | cut -d: -f1 || true)
        if [ -z "$unreleased_start" ]; then
            continue
        fi
        next_section=$(awk -v start="$unreleased_start" 'NR>start && /^## \[/ {print NR; exit}' "$file")
        if [ -z "$next_section" ]; then
            next_section=$(wc -l < "$file")
        fi
        # Slice the [Unreleased] section, look for violations on any line
        # (not diff-aware here — Unreleased is fully scanned because every
        # word in it is about to ship).
        section=$(sed -n "${unreleased_start},${next_section}p" "$file")
        if echo "$section" | grep -qE "$PATTERN"; then
            while IFS= read -r match; do
                VIOLATION_COUNT=$((VIOLATION_COUNT + 1))
                VIOLATIONS="$VIOLATIONS
  $file (Unreleased): $match"
            done < <(echo "$section" | grep -nE "$PATTERN" | head -5)
        fi
        continue
    fi
    # Default: scan added lines in the file's diff
    diff_added=$(git diff "$BASE_REF"...HEAD -- "$file" 2>/dev/null | grep -E '^\+[^+]' || true)
    if [ -z "$diff_added" ]; then
        continue
    fi
    matches=$(echo "$diff_added" | grep -E "$PATTERN" || true)
    if [ -n "$matches" ]; then
        while IFS= read -r line; do
            [ -z "$line" ] && continue
            VIOLATION_COUNT=$((VIOLATION_COUNT + 1))
            # Strip the leading '+' for display
            stripped=$(echo "$line" | sed 's/^+//')
            VIOLATIONS="$VIOLATIONS
  $file: $stripped"
        done < <(echo "$matches" | head -5)
    fi
done <<<"$CHANGED_FILES"

if [ "$VIOLATION_COUNT" -gt 0 ]; then
    echo "validate-no-beta-on-main.sh: FAIL — $VIOLATION_COUNT @Beta / ⏳ / deferred-framing violation(s) introduced:"
    echo "$VIOLATIONS"
    echo
    echo "Per feedback_no_beta_on_main.md, main is always release-ready —"
    echo "no @Beta tags, no ⏳ markers, no 'deferred to'/'next session'/'Phase N'"
    echo "framings on main. Either close the matrix or do not merge."
    echo
    echo "Allowed:"
    echo "  - .harness/drift-log.md historical narrative (append-only)"
    echo "  - CHANGELOG.md past-release entries (below the [Unreleased] block)"
    echo "  - Upstream product version names (rephrase if a regex hit is a false positive)"
    exit 1
fi

echo "validate-no-beta-on-main.sh: OK (no @Beta / ⏳ / deferred-framing additions vs $BASE_REF)"
exit 0

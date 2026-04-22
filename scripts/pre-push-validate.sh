#!/usr/bin/env bash
# Pre-push validation — incremental by default, full-reactor when required.
#
# Strategy (matches the industry norm for Maven multi-module repos):
#
#   1. Architectural-validation tier (fast, always runs).
#   2. Reactor build tier, scoped to the affected modules:
#        - Main checkout  -> Gitflow Incremental Builder (GIB) extension
#                            computes the module set from the git diff.
#        - Worktree       -> GIB's JGit backend doesn't support separate
#                            worktree checkouts, so we fall back to a
#                            git-diff -> `-pl ... -am` classifier here.
#        - Any high-blast-radius path (root pom.xml, config/, .mvn/,
#          modules/pom.xml) forces a full reactor build regardless.
#
# On success the script stamps .git/validation-passed; the pre-push hook
# consumes that marker.
#
# Usage:
#   ./scripts/pre-push-validate.sh              # incremental against origin/main
#   ./scripts/pre-push-validate.sh --full       # force full reactor
#   ./scripts/pre-push-validate.sh --dry-run    # classify only, don't build
#   BASE_REF=origin/atmosphere-2.6.x ./scripts/pre-push-validate.sh

set -euo pipefail

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
GIT_DIR="$(git rev-parse --git-dir)"
GIT_COMMON_DIR="$(git rev-parse --git-common-dir)"
MARKER_FILE="$GIT_DIR/validation-passed"
VALIDATION_TTL_MINUTES=30

cd "$PROJECT_ROOT"

# ---------------------------------------------------------------------------
# Arg + env handling
# ---------------------------------------------------------------------------
FORCE_FULL=false
DRY_RUN=false
case "${1:-}" in
    "") ;;
    --full) FORCE_FULL=true ;;
    --dry-run) DRY_RUN=true ;;
    *)
        echo "Unknown argument: '$1'"
        echo "Usage: $0 [--full|--dry-run]"
        exit 2
        ;;
esac

BASE_REF="${BASE_REF:-origin/main}"

# ---------------------------------------------------------------------------
# Helpers (defined before any caller; --dry-run invokes compute_pl_list early).
# ---------------------------------------------------------------------------
run_full() {
    echo "Running: ./mvnw install -q"
    ./mvnw install -q
}

run_gib() {
    echo "Running: ./mvnw install -q -Dgib.disable=false -Dgib.baseBranch=refs/remotes/$BASE_REF"
    ./mvnw install -q \
        -Dgib.disable=false \
        -Dgib.baseBranch="refs/remotes/$BASE_REF"
}

# Map each changed file to its enclosing Maven module (nearest ancestor
# directory containing a pom.xml). Returns a comma-joined -pl list.
compute_pl_list() {
    local files="$1"
    local modules=""
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        local dir
        dir=$(dirname "$file")
        while [ "$dir" != "." ] && [ "$dir" != "/" ]; do
            if [ -f "$dir/pom.xml" ]; then
                # Skip aggregator-only modules (no sources of their own).
                if [ "$dir" != "modules" ] && [ "$dir" != "." ]; then
                    modules="$modules
$dir"
                fi
                break
            fi
            dir=$(dirname "$dir")
        done
    done <<<"$files"
    echo "$modules" | sed '/^$/d' | sort -u | paste -sd, -
}

run_incremental_worktree() {
    echo "Worktree detected; using manual reactor scoping (GIB doesn't support worktrees)."
    echo "Modules: $PL_LIST"
    echo "Running: ./mvnw install -q -pl $PL_LIST -am"
    ./mvnw install -q -pl "$PL_LIST" -am
}

echo ""
echo "Atmosphere — Pre-Push Validation"
echo "================================="
echo ""

START_TIME=$(date +%s)

# Remove any stale marker up front; we re-stamp only on success.
rm -f "$MARKER_FILE"

CURRENT_COMMIT=$(git rev-parse HEAD)
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "Branch:   $CURRENT_BRANCH"
echo "Commit:   ${CURRENT_COMMIT:0:8}"
echo "Base ref: $BASE_REF"

IS_WORKTREE=false
if [ "$GIT_DIR" != "$GIT_COMMON_DIR" ]; then
    IS_WORKTREE=true
fi
echo "Worktree: $IS_WORKTREE"
echo ""

# ---------------------------------------------------------------------------
# Tier 1 — architectural validation (always runs; fast fail).
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" = false ]; then
    echo "--- Tier 1: architectural validation ---"
    if ! ./scripts/architectural-validation.sh; then
        echo ""
        echo "Architectural validation failed — fix issues before pushing."
        exit 1
    fi
    echo ""
fi

# ---------------------------------------------------------------------------
# Tier 2 — compute reactor scope from the diff.
# ---------------------------------------------------------------------------
# Resolve the diff base. Prefer the merge-base with $BASE_REF so rebases
# don't re-report main commits as branch-owned.
DIFF_BASE="$BASE_REF"
if git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
    DIFF_BASE=$(git merge-base "$BASE_REF" HEAD 2>/dev/null || echo "$BASE_REF")
else
    echo "Base ref '$BASE_REF' not found — falling back to full reactor build."
    FORCE_FULL=true
fi

CHANGED_FILES=""
if [ "$FORCE_FULL" = false ]; then
    COMMITTED=$(git diff --name-only "$DIFF_BASE" HEAD 2>/dev/null || true)
    WORKING=$(git diff --name-only HEAD 2>/dev/null || true)
    UNTRACKED=$(git ls-files --others --exclude-standard 2>/dev/null || true)
    CHANGED_FILES=$(printf '%s\n%s\n%s\n' "$COMMITTED" "$WORKING" "$UNTRACKED")
fi

# High-blast-radius paths force a full build.
HIGH_BLAST_REGEX='^(pom\.xml|modules/pom\.xml|\.mvn/.*|config/.*|bom/pom\.xml|assembly/pom\.xml)$'

# Paths that can never cause Java/Maven behavior change. Filtered out before
# module computation so a docs-only push is a no-op (beyond Tier 1).
IGNORE_REGEX='(^|/)(\.gitignore|\.editorconfig|LICENSE|NOTICE|README(\.md)?|.*\.md|.*\.txt)$|^docs/|^\.github/|^atmosphere\.js/|^\.claude/|^scripts/'

SIGNIFICANT_FILES=""
HAS_HIGH_BLAST=false
while IFS= read -r file; do
    [ -z "$file" ] && continue
    if echo "$file" | grep -qE "$HIGH_BLAST_REGEX"; then
        HAS_HIGH_BLAST=true
    fi
    if echo "$file" | grep -qE "$IGNORE_REGEX"; then
        continue
    fi
    SIGNIFICANT_FILES="$SIGNIFICANT_FILES
$file"
done <<<"$CHANGED_FILES"

SIGNIFICANT_FILES=$(echo "$SIGNIFICANT_FILES" | sed '/^$/d' | sort -u)

PL_LIST=""
if [ "$FORCE_FULL" = true ] || [ "$HAS_HIGH_BLAST" = true ]; then
    REACTOR_MODE="full"
elif [ -z "$SIGNIFICANT_FILES" ]; then
    REACTOR_MODE="none"
else
    PL_LIST=$(compute_pl_list "$SIGNIFICANT_FILES")
    if [ -z "$PL_LIST" ]; then
        # Significant files landed outside any Maven module (rare — usually
        # ops tooling already filtered above).
        REACTOR_MODE="none"
    else
        REACTOR_MODE="incremental"
    fi
fi

echo "--- Tier 2: reactor build (mode: $REACTOR_MODE) ---"

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo "Dry-run — classifier output only:"
    echo "  high-blast path changed : $HAS_HIGH_BLAST"
    echo "  reactor mode            : $REACTOR_MODE"
    if [ "$REACTOR_MODE" = "incremental" ] && [ "$IS_WORKTREE" = true ]; then
        echo "  modules (-pl)           : $PL_LIST"
    fi
    echo "  changed files:"
    echo "$SIGNIFICANT_FILES" | sed 's/^/    /'
    exit 0
fi


# ---------------------------------------------------------------------------
# Execute the reactor build.
# ---------------------------------------------------------------------------
case "$REACTOR_MODE" in
    full)
        if [ "$FORCE_FULL" = true ]; then
            echo "Full build requested (--full flag)."
        else
            echo "High-blast-radius path changed — forcing full reactor."
        fi
        run_full
        ;;
    none)
        echo "No build-affecting files changed — skipping reactor build."
        ;;
    incremental)
        if [ "$IS_WORKTREE" = true ]; then
            run_incremental_worktree
        else
            run_gib
        fi
        ;;
esac

# ---------------------------------------------------------------------------
# Stamp the marker.
# ---------------------------------------------------------------------------
END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo "$END_TIME $CURRENT_COMMIT" >"$MARKER_FILE"

echo ""
echo "Validation passed (${ELAPSED}s, mode: $REACTOR_MODE)"
echo "Marker valid for ${VALIDATION_TTL_MINUTES} minutes."
echo ""
echo "You can now: git push"
echo ""

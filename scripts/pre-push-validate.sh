#!/usr/bin/env bash
# Pre-push validation — incremental by default, full-reactor when required.
#
# Strategy (matches the industry norm for Maven multi-module repos):
#
#   1. Lightweight validation tier, selected from committed files that will be
#      pushed. Local dirty/untracked files are intentionally ignored.
#   2. Reactor build tier, scoped to the affected modules:
#        - git-diff -> nearest Maven module -> explicit `-pl ... -am`.
#          We avoid opaque GIB runs so the module list and Maven command are
#          visible before the build starts.
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
# JUnit 5 tag exclusions match what release-4x.yml uses for the release lane:
# tests tagged `@Tag("flaky")` (e.g. wasync ChatIntegrationTest's
# longPollingTransportConnectsAndReceivesMessage, pinned in commit
# ad420c218b) are pre-existing timing-sensitive specs not meant for
# fail-fast pre-push. Honoring the same exclusion locally keeps
# pre-push noise-free and matches what gates a release.
TEST_GROUPS='-Dgroups=!flaky'

run_full() {
    echo "Running: ./mvnw install -B -ntp $TEST_GROUPS"
    ./mvnw install -B -ntp $TEST_GROUPS
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

run_incremental_scoped() {
    echo "Modules: $PL_LIST"
    case ",$PL_LIST," in
        *,modules/quarkus-extension/deployment,*|*,modules/quarkus-admin-extension/deployment,*)
            echo "Pre-installing Quarkus deployment artifact for extension-test bootstrap."
            ./mvnw install -B -ntp -pl modules/quarkus-extension/deployment -am -DskipTests
            ;;
    esac
    echo "Running: ./mvnw install -B -ntp -pl $PL_LIST -am $TEST_GROUPS"
    ./mvnw install -B -ntp -pl "$PL_LIST" -am $TEST_GROUPS
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

# ---------------------------------------------------------------------------
# Compute validation scope from committed changes only.
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
    CHANGED_FILES="$COMMITTED"
fi
CHANGED_FILES=$(echo "$CHANGED_FILES" | sed '/^$/d' | sort -u)

echo "Diff base: $(git rev-parse --short "$DIFF_BASE" 2>/dev/null || echo "$DIFF_BASE")"
echo "Scope:     committed changes only (working tree and untracked files ignored)"
echo ""

# High-blast-radius paths force a full build.
HIGH_BLAST_REGEX='^(pom\.xml|modules/pom\.xml|\.mvn/.*|config/.*|bom/pom\.xml|assembly/pom\.xml)$'

# Paths that can never cause Java/Maven behavior change. Filtered out before
# module computation so a docs-only push is a no-op for Maven.
IGNORE_REGEX='(^|/)(\.gitignore|\.editorconfig|LICENSE|NOTICE|README(\.md)?|.*\.md|.*\.txt)$|^docs/|^\.github/|^atmosphere\.js/|^\.claude/|^scripts/'

# Tier-1 checks are selected by committed paths. This keeps README/docs pushes
# fast while preserving the heavier architectural scan for Java/config/workflow
# changes that can affect runtime behavior.
ARCHITECTURAL_REGEX='^pom\.xml$|^(modules|samples)/.*(pom\.xml|src/(main|test)/.*\.(java|kt|kts))$|^(bom|assembly)/pom\.xml$|^config/|^\.mvn/|^\.github/workflows/|^scripts/(architectural-validation|pre-push-validate)\.sh$'
CAPABILITY_CLAIMS_REGEX='(^|/)(README\.md|.*capabilit.*\.md)$|^modules/ai/README\.md$|^\.harness/capabilities\.snapshot\.json$|^scripts/validate-capability-claims\.sh$|^scripts/(regen|sign|verify|scan)-skillcards\.sh$|^modules/[^/]+/SKILLCARD\.yaml(\.sig)?$|^\.github/workflows/sign-skillcards\.yml$|^SKILLCARDS\.md$'
DRIFT_LOG_REGEX='^\.harness/drift-log\.md$|^scripts/validate-drift-log\.sh$'
BACKEND_CLASS_REFS_REGEX='\.java$|\.md$|^scripts/validate-backend-class-refs\.sh$|^\.harness/external-class-allowlist\.txt$'
PRIVATE_HANDLE_REGEX='\.(java|kt|kts|md|yml|yaml|ts|tsx|js|json|properties|xml|sh)$|^scripts/validate-no-private-handle\.sh$|^\.harness/private-handle-allowlist\.txt$'
NO_BETA_REGEX='\.(java|kt|kts|md|mdx|yml|yaml|ts|tsx|js|json|properties|xml|sh|py|astro)$|^scripts/validate-no-beta-on-main\.sh$|^\.harness/no-beta-allowlist\.txt$|^CHANGELOG\.md$'

SIGNIFICANT_FILES=""
IGNORED_FILES=""
HAS_HIGH_BLAST=false
RUN_ARCHITECTURAL=false
RUN_CAPABILITY_CLAIMS=false
RUN_DRIFT_LOG=false
RUN_BACKEND_CLASS_REFS=false
RUN_PRIVATE_HANDLE=false
RUN_NO_BETA=false
while IFS= read -r file; do
    [ -z "$file" ] && continue
    if echo "$file" | grep -qE "$ARCHITECTURAL_REGEX"; then
        RUN_ARCHITECTURAL=true
    fi
    if echo "$file" | grep -qE "$CAPABILITY_CLAIMS_REGEX"; then
        RUN_CAPABILITY_CLAIMS=true
    fi
    if echo "$file" | grep -qE "$DRIFT_LOG_REGEX"; then
        RUN_DRIFT_LOG=true
    fi
    if echo "$file" | grep -qE "$BACKEND_CLASS_REFS_REGEX"; then
        RUN_BACKEND_CLASS_REFS=true
    fi
    if echo "$file" | grep -qE "$PRIVATE_HANDLE_REGEX"; then
        RUN_PRIVATE_HANDLE=true
    fi
    if echo "$file" | grep -qE "$NO_BETA_REGEX"; then
        RUN_NO_BETA=true
    fi
    if echo "$file" | grep -qE "$HIGH_BLAST_REGEX"; then
        HAS_HIGH_BLAST=true
    fi
    if echo "$file" | grep -qE "$IGNORE_REGEX"; then
        IGNORED_FILES="$IGNORED_FILES
$file"
        continue
    fi
    SIGNIFICANT_FILES="$SIGNIFICANT_FILES
$file"
done <<<"$CHANGED_FILES"

SIGNIFICANT_FILES=$(echo "$SIGNIFICANT_FILES" | sed '/^$/d' | sort -u)
IGNORED_FILES=$(echo "$IGNORED_FILES" | sed '/^$/d' | sort -u)

if [ "$FORCE_FULL" = true ]; then
    RUN_ARCHITECTURAL=true
    RUN_CAPABILITY_CLAIMS=true
    RUN_DRIFT_LOG=true
    RUN_BACKEND_CLASS_REFS=true
    RUN_PRIVATE_HANDLE=true
    RUN_NO_BETA=true
fi

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

echo "--- Tier 1: selected validation ---"

if [ "$DRY_RUN" = false ]; then
    if [ "$RUN_ARCHITECTURAL" = true ]; then
        echo "Running architectural validation (Java/config/workflow/script changes detected)."
        if ! ./scripts/architectural-validation.sh; then
            echo ""
            echo "Architectural validation failed — fix issues before pushing."
            exit 1
        fi
    else
        echo "Skipping architectural validation (no Java/config/workflow changes in pushed commits)."
    fi
    echo ""

    if [ "$RUN_CAPABILITY_CLAIMS" = true ]; then
        echo "Running capability snapshot claims validation."
        if ! ./scripts/validate-capability-claims.sh; then
            echo ""
            echo "Capability snapshot drift — fix README counts or regenerate the snapshot."
            exit 1
        fi
    else
        echo "Skipping capability snapshot claims validation."
    fi
    echo ""

    if [ "$RUN_DRIFT_LOG" = true ]; then
        echo "Running drift-log structure validation."
        if ! ./scripts/validate-drift-log.sh; then
            echo ""
            echo "Drift-log structural violation — fix .harness/drift-log.md before pushing."
            exit 1
        fi
    else
        echo "Skipping drift-log structure validation."
    fi
    echo ""

    if [ "$RUN_BACKEND_CLASS_REFS" = true ]; then
        echo "Running backend-class reference validation."
        if ! ./scripts/validate-backend-class-refs.sh; then
            echo ""
            echo "Backend-class reference drift — fix the Javadoc/doc, ship the class,"
            echo "or allowlist a verified third-party token in .harness/external-class-allowlist.txt."
            exit 1
        fi
    else
        echo "Skipping backend-class reference validation."
    fi
    echo ""

    if [ "$RUN_PRIVATE_HANDLE" = true ]; then
        echo "Running private-handle leak validation."
        if ! ./scripts/validate-no-private-handle.sh; then
            echo ""
            echo "Private maintainer-address handle leaked into a committed artifact."
            echo "Replace with a neutral identifier (the project maintainer / Alice / Alex)"
            echo "or allowlist the path in .harness/private-handle-allowlist.txt."
            exit 1
        fi
    else
        echo "Skipping private-handle leak validation."
    fi
    echo ""

    if [ "$RUN_NO_BETA" = true ]; then
        echo "Running no-@Beta-on-main validation."
        if ! BASE_REF="$BASE_REF" ./scripts/validate-no-beta-on-main.sh; then
            echo ""
            echo "@Beta / ⏳ / deferred-framing introduced on main."
            echo "Per feedback_no_beta_on_main.md, main is always release-ready;"
            echo "close the matrix or rewrite the prose without 'deferred to' / 'next session' / 'Phase N'."
            exit 1
        fi
    else
        echo "Skipping no-@Beta-on-main validation."
    fi
    echo ""
else
    echo "Dry-run — selected Tier 1 checks:"
    echo "  architectural validation : $RUN_ARCHITECTURAL"
    echo "  capability claims        : $RUN_CAPABILITY_CLAIMS"
    echo "  drift log                : $RUN_DRIFT_LOG"
    echo "  backend-class refs       : $RUN_BACKEND_CLASS_REFS"
    echo "  private-handle leak      : $RUN_PRIVATE_HANDLE"
    echo "  no-@Beta-on-main         : $RUN_NO_BETA"
    echo ""
fi

echo "--- Tier 2: reactor build (mode: $REACTOR_MODE) ---"

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo "Dry-run — classifier output only:"
    echo "  diff base               : $(git rev-parse --short "$DIFF_BASE" 2>/dev/null || echo "$DIFF_BASE")"
    echo "  high-blast path changed : $HAS_HIGH_BLAST"
    echo "  reactor mode            : $REACTOR_MODE"
    if [ "$REACTOR_MODE" = "incremental" ]; then
        echo "  modules (-pl)           : $PL_LIST"
        case ",$PL_LIST," in
            *,modules/quarkus-extension/deployment,*|*,modules/quarkus-admin-extension/deployment,*)
                echo "  preinstall              : ./mvnw install -B -ntp -pl modules/quarkus-extension/deployment -am -DskipTests"
                ;;
        esac
        echo "  maven command           : ./mvnw install -B -ntp -pl $PL_LIST -am $TEST_GROUPS"
    elif [ "$REACTOR_MODE" = "full" ]; then
        echo "  maven command           : ./mvnw install -B -ntp $TEST_GROUPS"
    else
        echo "  maven command           : (none)"
    fi
    echo "  committed files:"
    if [ -n "$CHANGED_FILES" ]; then
        echo "$CHANGED_FILES" | sed 's/^/    /'
    else
        echo "    (none)"
    fi
    echo "  ignored for Maven:"
    if [ -n "$IGNORED_FILES" ]; then
        echo "$IGNORED_FILES" | sed 's/^/    /'
    else
        echo "    (none)"
    fi
    echo "  Maven-significant files:"
    if [ -n "$SIGNIFICANT_FILES" ]; then
        echo "$SIGNIFICANT_FILES" | sed 's/^/    /'
    else
        echo "    (none)"
    fi
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
        run_incremental_scoped
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

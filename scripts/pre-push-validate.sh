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
# module computation so a docs-only push is a no-op for Maven. Playwright e2e
# specs (modules/integration-tests/e2e/ and top-level e2e/) are TypeScript run
# by the e2e.yml workflow, not compiled or tested by the Maven reactor, so an
# e2e-spec-only change must not trigger a Maven build (a mixed change still
# builds because the accompanying Java files remain Maven-significant).
IGNORE_REGEX='(^|/)(\.gitignore|\.editorconfig|LICENSE|NOTICE|README(\.md)?|.*\.md|.*\.txt)$|^docs/|^\.github/|^atmosphere\.js/|^\.claude/|^scripts/|^e2e/|^modules/integration-tests/e2e/'

# Tier-1 checks are selected by committed paths. This keeps README/docs pushes
# fast while preserving the heavier architectural scan for Java/config/workflow
# changes that can affect runtime behavior.
ARCHITECTURAL_REGEX='^pom\.xml$|^(modules|samples)/.*(pom\.xml|src/(main|test)/.*\.(java|kt|kts))$|^(bom|assembly)/pom\.xml$|^config/|^\.mvn/|^\.github/workflows/|^scripts/(architectural-validation|pre-push-validate)\.sh$'
# `\.md$` is included because validate-capability-claims.sh now also checks
# "N of M runtimes" enumeration denominators across ALL Markdown (e.g.
# docs/runtime-selection.md), not just README/capability files.
CAPABILITY_CLAIMS_REGEX='\.md$|^\.harness/capabilities\.snapshot\.json$|^scripts/validate-capability-claims\.sh$|^\.harness/enumeration-allowlist\.txt$|^scripts/(regen|sign|verify|scan)-skillcards\.sh$|^modules/[^/]+/SKILLCARD\.yaml(\.sig)?$|^\.github/workflows/sign-skillcards\.yml$|^SKILLCARDS\.md$'
DRIFT_LOG_REGEX='^\.harness/drift-log\.md$|^scripts/validate-drift-log\.sh$'
BACKEND_CLASS_REFS_REGEX='\.java$|\.md$|^scripts/validate-backend-class-refs\.sh$|^\.harness/external-class-allowlist\.txt$'
PRIVATE_HANDLE_REGEX='\.(java|kt|kts|md|yml|yaml|ts|tsx|js|json|properties|xml|sh)$|^scripts/validate-no-private-handle\.sh$|^\.harness/private-handle-allowlist\.txt$'
NO_BETA_REGEX='\.(java|kt|kts|md|mdx|yml|yaml|ts|tsx|js|json|properties|xml|sh|py|astro)$|^scripts/validate-no-beta-on-main\.sh$|^\.harness/no-beta-allowlist\.txt$|^CHANGELOG\.md$'
OVERLAY_COVERAGE_REGEX='^cli/runtime-overlays\.json$|^bom/pom\.xml$|^\.harness/capabilities\.snapshot\.json$|^scripts/validate-runtime-overlay-coverage\.sh$|^modules/.*/src/main/.*AgentRuntime\.(java|kt)$'
DANGLING_DOC_REGEX='^(modules|samples)/.*/src/(main|test)/.*\.java$|^scripts/validate-dangling-doc-comments\.sh$'
DOC_VERSION_REGEX='\.md$|^pom\.xml$|^atmosphere\.js/package\.json$|^scripts/validate-doc-version-alignment\.sh$|^\.harness/doc-version-allowlist\.txt$'
DOC_SYMBOLS_REGEX='\.md$|^(modules|samples)/.*/src/main/.*\.java$|^scripts/validate-doc-symbols\.sh$|^\.harness/doc-symbol-allowlist\.txt$'
ORPHAN_CLASS_REGEX='^modules/.*/src/main/.*\.java$|^scripts/validate-no-orphan-classes\.sh$|^\.harness/orphan-class-allowlist\.txt$'
FACTS_REGISTRY_REGEX='\.md$|^\.harness/facts\.json$|^scripts/validate-facts-registry\.sh$'

SIGNIFICANT_FILES=""
IGNORED_FILES=""
HAS_HIGH_BLAST=false
RUN_ARCHITECTURAL=false
RUN_CAPABILITY_CLAIMS=false
RUN_DRIFT_LOG=false
RUN_BACKEND_CLASS_REFS=false
RUN_PRIVATE_HANDLE=false
RUN_NO_BETA=false
RUN_OVERLAY_COVERAGE=false
RUN_ORPHAN_CLASS=false
RUN_DANGLING_DOC=false
RUN_DOC_VERSION=false
RUN_DOC_SYMBOLS=false
RUN_FACTS_REGISTRY=false
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
    if echo "$file" | grep -qE "$OVERLAY_COVERAGE_REGEX"; then
        RUN_OVERLAY_COVERAGE=true
    fi
    if echo "$file" | grep -qE "$ORPHAN_CLASS_REGEX"; then
        RUN_ORPHAN_CLASS=true
    fi
    if echo "$file" | grep -qE "$DANGLING_DOC_REGEX"; then
        RUN_DANGLING_DOC=true
    fi
    if echo "$file" | grep -qE "$DOC_VERSION_REGEX"; then
        RUN_DOC_VERSION=true
    fi
    if echo "$file" | grep -qE "$DOC_SYMBOLS_REGEX"; then
        RUN_DOC_SYMBOLS=true
    fi
    if echo "$file" | grep -qE "$FACTS_REGISTRY_REGEX"; then
        RUN_FACTS_REGISTRY=true
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
    RUN_OVERLAY_COVERAGE=true
    RUN_ORPHAN_CLASS=true
    RUN_DANGLING_DOC=true
    RUN_DOC_VERSION=true
    RUN_DOC_SYMBOLS=true
    RUN_FACTS_REGISTRY=true
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

    if [ "$RUN_OVERLAY_COVERAGE" = true ]; then
        echo "Running runtime overlay/BOM coverage validation."
        if ! ./scripts/validate-runtime-overlay-coverage.sh; then
            echo ""
            echo "A contract-tested runtime is not fully scaffoldable — add its CLI overlay"
            echo "in cli/runtime-overlays.json and/or its atmosphere-<x> entry in bom/pom.xml."
            exit 1
        fi
    else
        echo "Skipping runtime overlay/BOM coverage validation."
    fi
    echo ""

    if [ "$RUN_ORPHAN_CLASS" = true ]; then
        echo "Running orphan-class validation (newly-added classes with no consumer)."
        if ! BASE_REF="$BASE_REF" ./scripts/validate-no-orphan-classes.sh; then
            echo ""
            echo "A newly-added concrete class has no production consumer. Wire it to a"
            echo "real caller, delete it, or allowlist it in .harness/orphan-class-allowlist.txt."
            exit 1
        fi
    else
        echo "Skipping orphan-class validation."
    fi
    echo ""

    if [ "$RUN_DANGLING_DOC" = true ]; then
        echo "Running dangling-doc-comment validation."
        if ! BASE_REF="$BASE_REF" ./scripts/validate-dangling-doc-comments.sh; then
            echo ""
            echo "Detached/dangling Javadoc comment — would fail the JDK 25 Native Image lane."
            echo "Move the doc comment to immediately precede its declaration, or delete it."
            exit 1
        fi
    else
        echo "Skipping dangling-doc-comment validation."
    fi
    echo ""

    if [ "$RUN_DOC_VERSION" = true ]; then
        echo "Running doc dependency-version alignment validation."
        if ! ./scripts/validate-doc-version-alignment.sh; then
            echo ""
            echo "A third-party dependency version in Markdown drifted from the pinned source"
            echo "of truth — update the prose, or allowlist an intentional mention."
            exit 1
        fi
    else
        echo "Skipping doc dependency-version alignment validation."
    fi
    echo ""

    if [ "$RUN_DOC_SYMBOLS" = true ]; then
        echo "Running doc-symbol (phantom annotation) validation."
        if ! ./scripts/validate-doc-symbols.sh; then
            echo ""
            echo "A Markdown @Annotation does not resolve to any in-tree declaration."
            echo "Fix the doc, ship the annotation, or allowlist an external one."
            exit 1
        fi
    else
        echo "Skipping doc-symbol (phantom annotation) validation."
    fi
    echo ""

    if [ "$RUN_FACTS_REGISTRY" = true ]; then
        echo "Running facts-registry (business dates + superlatives) validation."
        if ! ./scripts/validate-facts-registry.sh; then
            echo ""
            echo "A pinned fact (business date / superlative) in prose drifted from"
            echo ".harness/facts.json — fix the prose, or update the registry if the"
            echo "fact legitimately changed. Sibling-site findings are advisory only."
            exit 1
        fi
    else
        echo "Skipping facts-registry validation."
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
    echo "  overlay/BOM coverage     : $RUN_OVERLAY_COVERAGE"
    echo "  orphan-class check       : $RUN_ORPHAN_CLASS"
    echo "  dangling-doc comments    : $RUN_DANGLING_DOC"
    echo "  doc version alignment    : $RUN_DOC_VERSION"
    echo "  doc-symbol (annotations) : $RUN_DOC_SYMBOLS"
    echo "  facts registry           : $RUN_FACTS_REGISTRY"
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

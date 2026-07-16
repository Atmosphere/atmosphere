#!/usr/bin/env bash
# Architectural validation for Atmosphere Framework
# Catches code quality anti-patterns that javac/checkstyle/PMD cannot detect:
# 1. NOOP / dead code (interfaces, constants, fields defined but never wired)
# 2. Placeholder / stub implementations in production code
# 3. @SuppressWarnings abuse
# 4. @Disabled tests without justification
# 5. Mock / fake code leaked into production sources
# 6. Fluent builder misuse (return values silently discarded)
# 7. ServiceLoader SPIs declared but not wired
#
# Configuration: scripts/validation-patterns.toml
#
# Usage:
#   ./scripts/architectural-validation.sh          # full scan (only mode)
# The previous `--fast` flag was removed (2026-04-20) — the extended
# checks are ripgrep passes and cost seconds, not the hours that made
# skipping them tempting. A single path means nothing slips through.

set -e

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# The `--fast` mode was removed alongside the pre-push-validate.sh
# fast path (2026-04-20). Every architectural-validation call now runs
# the full matrix — Test Integrity, Cross-Module Contracts, etc. Reject
# the stale flag so hooks that still pass it fail loudly.
if [ "$1" = "--fast" ]; then
    echo "architectural-validation.sh no longer accepts --fast — extended checks always run."
    exit 2
fi

VALIDATION_FAILED=false

fail_validation() {
    echo -e "${RED}  FAIL: $1${NC}"
    VALIDATION_FAILED=true
}

pass_validation() {
    echo -e "${GREEN}  PASS: $1${NC}"
}

# There is deliberately no warn_validation(). A gate with an advisory tier
# reports the same finding forever and blocks nothing, so the findings
# accumulate and everyone learns to scroll past the yellow — which is exactly
# what happened here: 5 WARN classes (99 findings) rode along under a green
# "ARCHITECTURAL VALIDATION PASSED" banner. Every check now either fails the
# build or does not exist. If a finding is not worth blocking a commit for,
# delete the check or allowlist the case in validation-patterns.toml with a
# reason — do not reintroduce a yellow tier. Bash will fail loudly with
# "command not found" if anyone calls warn_validation again.

# Hard dependency check. Nearly every check here shells out to `rg` with
# stderr redirected to /dev/null, so on a machine without ripgrep the command
# fails silently, the check finds nothing, and the gate prints PASS. An absent
# tool must never read as a clean result — that is the same failure mode as the
# SOURCE_DIRS typo (a check that scanned nothing and passed), just sourced from
# the environment instead of the script. Fail loudly instead.
for tool in rg python3; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo -e "${RED}CRITICAL: '$tool' is not installed.${NC}"
        echo -e "${RED}Nearly every check here depends on it and suppresses its${NC}"
        echo -e "${RED}stderr, so continuing would print PASS while scanning${NC}"
        echo -e "${RED}nothing. Install $tool (CI: see .github/workflows/ci.yml).${NC}"
        exit 1
    fi
done

# Comment-aware scanner. Checks that ask about CODE must not match Javadoc
# usage examples, and checks that ask about COMMENTS must not match enum
# constants that happen to be spelled TODO. See scripts/lib/source_scan.py.
SCAN="$SCRIPT_DIR/lib/source_scan.py"
if [ ! -f "$SCAN" ]; then
    echo -e "${RED}CRITICAL: source_scan.py not found at $SCAN${NC}"
    exit 1
fi

# Verify the instrument before trusting it. A scanner that silently stopped
# stripping comments would turn every check below green — the most dangerous
# way for a gate to fail, because it looks like success.
if ! SELF_TEST_OUT=$(python3 "$SCAN" --self-test 2>&1); then
    echo -e "${RED}CRITICAL: source_scan.py self-test failed — the validation${NC}"
    echo -e "${RED}instrument is broken, so its results cannot be trusted.${NC}"
    echo "$SELF_TEST_OUT"
    exit 1
fi

# Source directories to scan (production code only)
SRC_DIRS="modules/*/src/main/java"
TEST_DIRS="modules/*/src/test/java"

# ============================================================================
# LOAD CONFIGURATION FROM TOML
# ============================================================================

TOML_FILE="$SCRIPT_DIR/validation-patterns.toml"
if [ ! -f "$TOML_FILE" ]; then
    echo -e "${RED}CRITICAL: validation-patterns.toml not found at $TOML_FILE${NC}"
    exit 1
fi

# Parse TOML config using Python (available on all dev machines)
# Extracts: SPI allowlist, @SuppressWarnings allowlist, mock false-positive words,
# thresholds, and critical placeholder patterns
eval "$(python3 - "$TOML_FILE" <<'PYTHON_PARSER'
import sys, tomllib

with open(sys.argv[1], "rb") as f:
    config = tomllib.load(f)

# SPI interface allowlist -> pipe-separated for grep -E
spi = config.get("spi_interfaces", {}).get("user_extensible", [])
print(f'SPI_ALLOWLIST="{"|".join(spi)}"')

# @SuppressWarnings allowlist -> pipe-separated for grep -vE
sw = config.get("suppress_warnings_allowlist", {}).get("allowed", [])
print(f'ALLOWED_SUPPRESSIONS="{"|".join(sw)}"')

# Mock false-positive words -> pipe-separated for grep -viE
fp = config.get("mock_exclusions", {}).get("false_positive_words", [])
print(f'MOCK_FALSE_POSITIVES="{"|".join(fp)}"')

# Mock file exclusions -> glob patterns
mf = config.get("mock_exclusions", {}).get("files", [])
mock_globs = " ".join([f"-g '!*{f}'" for f in mf])
print(f'MOCK_FILE_EXCLUDES="{mock_globs}"')

# Thresholds
th = config.get("thresholds", {})
print(f'THRESHOLD_MOCK={th.get("mock_in_production", 0)}')
print(f'THRESHOLD_TODO={th.get("todo_markers", 0)}')
print(f'THRESHOLD_SYSOUT={th.get("system_out_calls", 0)}')
print(f'THRESHOLD_STACKTRACE={th.get("print_stack_trace", 0)}')

# Critical placeholder patterns -> pipe-separated regex
cp = config.get("placeholder_patterns", {}).get("critical", [])
# Escape pipe chars in patterns, join with |
critical_regex = "|".join(cp)
print(f'CRITICAL_PLACEHOLDERS="{critical_regex}"')

# CI continue-on-error allowlist
coe = config.get("ci_allowlist", {}).get("continue_on_error", [])
print(f'CI_COE_ALLOWLIST="{"|".join(coe)}"')

# NOOP allowlist -> pipe-separated for grep -E
na = config.get("noop_allowlist", {}).get("internal_use", [])
print(f'NOOP_ALLOWLIST="{"|".join(na)}"')

# Modules that publish nothing -> excluded from production-hygiene checks.
# Verified against their poms below; this is not a blind allowlist.
ns = config.get("non_shipped_modules", {}).get("modules", [])
print(f'NON_SHIPPED_MODULES="{" ".join(ns)}"')
PYTHON_PARSER
)"

# ----------------------------------------------------------------------------
# Guard against a silently-empty config. Several checks filter their findings
# through `grep -vE "$SOME_ALLOWLIST"`, and an EMPTY pattern matches every line
# — so `-v` then discards every finding and the check passes vacuously. A typo
# in the TOML, a key stranded under the wrong table header, or a parser change
# would therefore *disable* checks while still printing PASS. Refuse to run
# instead. (This is not hypothetical: moving false_positive_words below
# [[mock_exclusions.wiring_sites]] bound it to wiring_sites[0], emptied
# MOCK_FALSE_POSITIVES, and neutered the mock check.)
for required in SPI_ALLOWLIST ALLOWED_SUPPRESSIONS MOCK_FALSE_POSITIVES \
                CRITICAL_PLACEHOLDERS CI_COE_ALLOWLIST NOOP_ALLOWLIST \
                NON_SHIPPED_MODULES; do
    if [ -z "${!required}" ]; then
        echo -e "${RED}CRITICAL: $required parsed empty from validation-patterns.toml.${NC}"
        echo -e "${RED}An empty allowlist becomes a match-everything grep filter and${NC}"
        echo -e "${RED}would silently disable checks while printing PASS. Check the${NC}"
        echo -e "${RED}TOML table structure — a bare key placed after an [[array.of.tables]]${NC}"
        echo -e "${RED}header binds to that array's first element, not the parent table.${NC}"
        exit 1
    fi
done

# ----------------------------------------------------------------------------
# Verify the non-shipped allowlist against the poms it claims to describe.
# An allowlist that asserts a fact must prove the fact, or it is just a way to
# turn checks off quietly. If someone adds a deploying module here to dodge a
# finding, this fails.
# ----------------------------------------------------------------------------
if ! NON_SHIPPED_PROOF=$(python3 - "$NON_SHIPPED_MODULES" <<'PYTHON_VERIFY'
import sys, pathlib, xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
problems = []

for module in sys.argv[1].split():
    pom = pathlib.Path("modules") / module / "pom.xml"
    if not pom.exists():
        problems.append(f"{module}: no pom.xml at {pom}")
        continue
    root = ET.parse(pom).getroot()

    # Either a <maven.deploy.skip>true</maven.deploy.skip> property ...
    prop = root.find("m:properties/m:maven.deploy.skip", NS)
    skipped = prop is not None and (prop.text or "").strip() == "true"

    # ... or a maven-deploy-plugin with <skip>true</skip>.
    if not skipped:
        for plugin in root.findall("m:build/m:plugins/m:plugin", NS):
            artifact = plugin.find("m:artifactId", NS)
            if artifact is None or artifact.text != "maven-deploy-plugin":
                continue
            skip = plugin.find("m:configuration/m:skip", NS)
            if skip is not None and (skip.text or "").strip() == "true":
                skipped = True
                break

    if not skipped:
        problems.append(
            f"{module}: listed in [non_shipped_modules] but its pom does not "
            f"skip deployment — it publishes an artifact, so production checks "
            f"must apply to it")

for p in problems:
    print(p)
sys.exit(1 if problems else 0)
PYTHON_VERIFY
); then
    echo -e "${RED}CRITICAL: [non_shipped_modules] in validation-patterns.toml is wrong:${NC}"
    echo "$NON_SHIPPED_PROOF"
    exit 1
fi

# Build the exclude globs + the shipped source dir list from the verified list.
NON_SHIPPED_EXCLUDES=""
SHIPPED_SRC_DIRS=""
for d in $SRC_DIRS; do
    [ -d "$d" ] || continue
    keep=true
    for m in $NON_SHIPPED_MODULES; do
        case "$d" in "modules/$m/"*) keep=false ;; esac
    done
    $keep && SHIPPED_SRC_DIRS="$SHIPPED_SRC_DIRS $d"
done
for m in $NON_SHIPPED_MODULES; do
    NON_SHIPPED_EXCLUDES="$NON_SHIPPED_EXCLUDES --exclude */modules/$m/*"
done

echo ""
echo -e "${BLUE}==== Atmosphere Framework - Architectural Validation ====${NC}"
echo -e "${BLUE}     Config: $TOML_FILE${NC}"
echo ""

# ============================================================================
# 1. NOOP / DEAD CODE DETECTION
# ============================================================================

echo -e "${BLUE}--- NOOP / Dead Code Detection ---${NC}"

# 1a. Constants ending in NOOP/NO_OP that are DECLARED but never referenced
#     outside their declaring file. Only match field declarations (static final),
#     not usages. The exact pattern that let AiMetrics.NOOP ship without being wired.
# [A-Z_]* on both sides is load-bearing: `_` is a word character, so a bare
# \b(NOOP|NO_OP)\b never matches FOO_NOOP or NOOP_INSTANCE — it only ever found
# a constant named exactly NOOP. The extractor below is [A-Z_]*NOOP[A-Z_]* and
# always intended the affixed spellings; the two disagreed silently.
NOOP_DECLS=$(rg "static\s+final\s+.*\b[A-Z_]*(NOOP|NO_OP)[A-Z_]*\b" $SRC_DIRS --type java -l 2>/dev/null || true)
NOOP_ISSUES=""
NOOP_COUNT=0
if [ -n "$NOOP_DECLS" ]; then
    for file in $NOOP_DECLS; do
        class_name=$(basename "$file" .java)
        while IFS= read -r const_line; do
            const_name=$(echo "$const_line" | grep -oE '\b[A-Z_]*NOOP[A-Z_]*\b|\b[A-Z_]*NO_OP[A-Z_]*\b' | head -1)
            [ -z "$const_name" ] && continue
            tag="${class_name}.${const_name}"
            # Skip NOOP allowlist entries
            if [ -n "$NOOP_ALLOWLIST" ] && echo "$tag" | grep -qE "^($NOOP_ALLOWLIST)$"; then
                continue
            fi
            # Deduplicate: only count unique ClassName.CONSTANT pairs
            if echo -e "$NOOP_ISSUES" | grep -qF "$tag"; then
                continue
            fi
            usage_count=$(rg "\b${class_name}\.${const_name}\b" $SRC_DIRS --type java -l 2>/dev/null | grep -v "$file" | wc -l | tr -d ' ')
            if [ "$usage_count" -eq 0 ]; then
                NOOP_ISSUES="${NOOP_ISSUES}  ${tag} (${file})\n"
                NOOP_COUNT=$((NOOP_COUNT + 1))
            fi
        done < <(rg "static\s+final\s+.*\b[A-Z_]*(NOOP|NO_OP)[A-Z_]*\b" "$file" 2>/dev/null)
    done
fi

if [ "$NOOP_COUNT" -gt 0 ]; then
    fail_validation "NOOP constants declared but never referenced in production code ($NOOP_COUNT):"
    echo -e "$NOOP_ISSUES"
else
    pass_validation "No unwired NOOP constants"
fi

# 1b. Interfaces with zero implementations in production code
DEAD_INTERFACES=""
while IFS= read -r iface_file; do
    iface_name=$(basename "$iface_file" .java)
    # Skip SPI interfaces from allowlist
    if [ -n "$SPI_ALLOWLIST" ] && echo "$iface_name" | grep -qE "^($SPI_ALLOWLIST)$"; then
        continue
    fi
    impl_count=$(rg "implements\s+.*\b${iface_name}\b|extends\s+.*\b${iface_name}\b" $SRC_DIRS --type java -l 2>/dev/null | wc -l | tr -d ' ')
    if [ "$impl_count" -eq 0 ]; then
        if rg "^\s*public\s+interface\s+${iface_name}\b" "$iface_file" -q 2>/dev/null; then
            DEAD_INTERFACES="${DEAD_INTERFACES}  ${iface_name} (${iface_file})\n"
        fi
    fi
done < <(find modules/ai/src/main/java -name "*.java" 2>/dev/null)

if [ -n "$DEAD_INTERFACES" ]; then
    fail_validation "AI module interfaces with zero implementations in production code:"
    echo -e "$DEAD_INTERFACES"
    echo "  (Add to [spi_interfaces].user_extensible in validation-patterns.toml to allowlist.)"
else
    pass_validation "No dead AI interfaces"
fi

# 1c. META-INF/services files that are empty (third-party extension points
#     without an in-tree provider can opt into an allowlist in
#     validation-patterns.toml [empty_services].allowed).
EMPTY_SPI=""
# Read the allowlist into a grep-friendly pattern — each line quoted in TOML.
EMPTY_ALLOWLIST=$(sed -n '/^\[empty_services\]/,/^\[/p' "$TOML_FILE" 2>/dev/null \
    | grep -oE '"[^"]+"' | tr -d '"' || true)
while IFS= read -r spi_file; do
    # Skip allowlisted paths.
    if [ -n "$EMPTY_ALLOWLIST" ] && echo "$EMPTY_ALLOWLIST" | grep -qF "$spi_file"; then
        continue
    fi
    content_lines=$(grep -v '^\s*#' "$spi_file" 2>/dev/null | grep -v '^\s*$' | wc -l | tr -d ' ')
    if [ "$content_lines" -eq 0 ]; then
        spi_iface=$(basename "$spi_file")
        EMPTY_SPI="${EMPTY_SPI}  ${spi_iface} (${spi_file})\n"
    fi
done < <(find modules/*/src/main/resources/META-INF/services -type f 2>/dev/null)

if [ -n "$EMPTY_SPI" ]; then
    fail_validation "Empty META-INF/services files (SPI declared but no provider listed):"
    echo -e "$EMPTY_SPI"
    echo "  (Add to [empty_services].allowed in validation-patterns.toml to allowlist.)"
else
    pass_validation "No empty SPI service files"
fi

# ============================================================================
# 2. PLACEHOLDER / STUB DETECTION
# ============================================================================

echo ""
echo -e "${BLUE}--- Placeholder / Stub Detection ---${NC}"

# 2a. Critical placeholders (from TOML config)
if [ -n "$CRITICAL_PLACEHOLDERS" ]; then
    CRITICAL_COUNT=$(rg -i "$CRITICAL_PLACEHOLDERS" $SRC_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')
    if [ "$CRITICAL_COUNT" -gt 0 ]; then
        fail_validation "Found $CRITICAL_COUNT critical placeholder patterns in production code"
        rg -i "$CRITICAL_PLACEHOLDERS" $SRC_DIRS --type java -n 2>/dev/null | head -5
    else
        pass_validation "No critical placeholder patterns"
    fi
fi

# 2b. TODO/FIXME/XXX/HACK deferral markers.
#
# Scanned in COMMENT mode: a marker only ever lives in a comment, and scanning
# raw text made `case TODO ->` (AgentScope's SubTaskState enum constant) look
# like unfinished work — 8 of the 11 findings this check used to report were
# that. Javadoc inline tags are stripped first, so `{@code TODO}` documenting
# an enum round-trip is prose about a constant, not a deferral.
#
# Blocking, per feedback_no_deferral: main is release-ready, so a marker saying
# "finish this later" is either work to do now or a note that should say what it
# actually means. Both real markers found on 2026-07-14 turned out to be
# mislabeled design rationale; the third (ToolCallbackServer's missing tool-auth
# token) was a genuine unshipped security control and got implemented.
TODO_HITS=$(python3 "$SCAN" --mode comment --strip-javadoc-tags \
    --regex '\b(TODO|FIXME|XXX|HACK)\b' $SRC_DIRS 2>/dev/null || true)
TODO_COUNT=$(printf '%s' "$TODO_HITS" | grep -c . || true)
if [ "$TODO_COUNT" -gt "$THRESHOLD_TODO" ]; then
    fail_validation "Found $TODO_COUNT TODO/FIXME/XXX/HACK deferral marker(s) in production code (threshold: $THRESHOLD_TODO)"
    echo "$TODO_HITS" | head -10
    echo "  Either do the work, or rewrite the comment to state what is actually"
    echo "  true (a constraint, a scope limit, a design rationale) without a marker."
else
    pass_validation "No TODO/FIXME/XXX/HACK deferral markers in production code"
fi

# 2c. Stub/mock/fake implementations leaking into shipped code.
#
# CODE mode: the old pipeline tried to drop comments with `grep -v '//'`, which
# both missed Javadoc bodies and threw away any real line with a trailing
# comment. Non-shipped modules are excluded outright — `benchmarks` exists to
# build synthetic fixtures, so requiring it to contain no "stub" was asking a
# question about the wrong code.
#
# Legitimately-named product features are allowlisted per file+symbol in
# [[mock_exclusions.wiring_sites]], never per file alone: `mode=fake` is a real
# shipped mode, but a real stub landing in AiConfig must still fail.
#
# The wiring-site filter is a real file, not a heredoc: `python3 - <<'PY'` in a
# pipeline reads its program from stdin, which rebinds stdin away from the pipe,
# so the findings never reach the filter and it emits nothing. That silently
# turned this whole check into an unconditional PASS.
MOCK_FILTER="$SCRIPT_DIR/lib/filter_wiring_sites.py"
if [ ! -f "$MOCK_FILTER" ]; then
    echo -e "${RED}CRITICAL: filter_wiring_sites.py not found at $MOCK_FILTER${NC}"
    exit 1
fi
MOCK_HITS=$(python3 "$SCAN" --mode code --ignore-case \
    --regex '\b(mock|dummy|fake|stub)\b' \
    --exclude '*Mock*' --exclude '*Stub*' --exclude '*Fake*' --exclude '*Test*' \
    $NON_SHIPPED_EXCLUDES $SHIPPED_SRC_DIRS 2>/dev/null \
    | grep -v 'Mockito' \
    | grep -viE "$MOCK_FALSE_POSITIVES" \
    | python3 "$MOCK_FILTER" "$TOML_FILE")
MOCK_IN_PROD=$(printf '%s' "$MOCK_HITS" | grep -c . || true)

if [ "$MOCK_IN_PROD" -gt "$THRESHOLD_MOCK" ]; then
    fail_validation "Found $MOCK_IN_PROD mock/stub/fake reference(s) in shipped production code (threshold: $THRESHOLD_MOCK)"
    echo "$MOCK_HITS" | head -10
else
    pass_validation "No mock/stub code in shipped production sources"
fi

# ============================================================================
# 3. @SuppressWarnings ABUSE
# ============================================================================

echo ""
echo -e "${BLUE}--- @SuppressWarnings Validation ---${NC}"

FORBIDDEN_SUPPRESS=$(rg '@SuppressWarnings' $SRC_DIRS --type java 2>/dev/null \
    | grep -vE "$ALLOWED_SUPPRESSIONS" \
    | grep -v '^\s*//' \
    | wc -l | tr -d ' ')

if [ "$FORBIDDEN_SUPPRESS" -gt 0 ]; then
    fail_validation "Found $FORBIDDEN_SUPPRESS unauthorized @SuppressWarnings in production code"
    rg '@SuppressWarnings' $SRC_DIRS --type java -n 2>/dev/null \
        | grep -vE "$ALLOWED_SUPPRESSIONS" | head -5
    echo "  Allowed (from validation-patterns.toml): $ALLOWED_SUPPRESSIONS"
else
    pass_validation "All @SuppressWarnings use approved categories"
fi

echo ""
echo -e "${BLUE}--- Unchecked Return Values ---${NC}"
# $SOURCE_DIRS never existed — the variable is SRC_DIRS. With no path argument
# rg falls back to reading stdin, so from 2026-04-09 (68fdaf598d) until
# 2026-07-14 this check scanned nothing and printed PASS on every run. That is
# strictly worse than the WARN tier: a warning is ignored, but a green PASS is
# cited as evidence. Whenever a path variable is interpolated into rg, an empty
# expansion must fail loudly rather than silently turn the check into a no-op —
# hence the guard below.
if [ -z "${SRC_DIRS// /}" ]; then
    echo -e "${RED}CRITICAL: SRC_DIRS is empty — rg would read stdin and the${NC}"
    echo -e "${RED}check would silently pass without scanning anything.${NC}"
    exit 1
fi
UNCHECKED=$(rg '^\s+\w+\.offer\(' $SRC_DIRS --type java -l 2>/dev/null | \
    grep -v "StubAgentTransport\|Test\.java\|InMemoryCheckpointStore" || true)
if [ -n "$UNCHECKED" ]; then
    fail_validation "Unchecked offer() return values in: $(echo "$UNCHECKED" | tr '\n' ' ')"
else
    pass_validation "No unchecked offer() calls in production code"
fi

echo ""
echo -e "${BLUE}--- Test Quality ---${NC}"
NOOP_TESTS=$(rg 'expect\(true\)\.toBe\(true\)|expect\(1\)\.toBe\(1\)|assertTrue\(true\)' \
    modules/integration-tests/e2e/ modules/*/src/test/ --type ts --type java 2>/dev/null || true)
if [ -n "$NOOP_TESTS" ]; then
    fail_validation "Found no-op test assertions (expect(true).toBe(true))"
else
    pass_validation "No no-op test assertions found"
fi

# ============================================================================
# 4. TEST INTEGRITY
# ============================================================================

echo ""
echo -e "${BLUE}--- Test Integrity ---${NC}"

# @[\w.]* tolerates a fully-qualified annotation: a bare '@Disabled\b' missed
# @org.junit.jupiter.api.Disabled entirely, so a qualified skip was invisible.
#
# Deliberately group-free. The natural spelling '@(?:[\w.]*\.)?Disabled\b'
# works on ripgrep 14.1.1 but silently matches NOTHING on 14.1.0 — which is what
# `apt-get install ripgrep` puts on ubuntu-latest, so it passed locally and
# no-op'd in CI. Any optional/repeated group containing a dot has the same
# problem there (verified in ubuntu:24.04 for (?:..)?, (..)? and (..)*).
# Do not "tidy" this into a group.
#
# \b after Disabled correctly excludes @DisabledOnOs (a conditional, not a skip).
DISABLED_TESTS=$(rg '@[\w.]*Disabled\b' $TEST_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$DISABLED_TESTS" -gt 0 ]; then
    BARE_DISABLED=$(rg '@[\w.]*Disabled\s*$' $TEST_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')
    if [ "$BARE_DISABLED" -gt 0 ]; then
        fail_validation "Found $BARE_DISABLED @Disabled test(s) with no reason string — @Disabled(\"why, and what unblocks it\")"
        rg '@[\w.]*Disabled\s*$' $TEST_DIRS --type java -n 2>/dev/null | head -5
    fi
    echo "  Total @Disabled tests: $DISABLED_TESTS"
else
    pass_validation "No @Disabled tests"
fi

IGNORE_TESTS=$(rg '@[\w.]*Ignore\b' $TEST_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$IGNORE_TESTS" -gt 0 ]; then
    fail_validation "Found $IGNORE_TESTS @Ignore annotations (JUnit 4 legacy — migrate to @Disabled)"
    rg '@[\w.]*Ignore\b' $TEST_DIRS --type java -n 2>/dev/null | head -5
else
    pass_validation "No JUnit 4 @Ignore annotations"
fi

# Detect inherited @Test methods overridden with empty / comment-only bodies
# AND without @Test or @Disabled on the override. JUnit 5 stops discovering
# an inherited @Test when the override carries no @Test of its own — the
# contract assertion silently disappears (Tests run: N drops by 1, no skip
# count). This is the exact regression that hid behind ADK / Embabel / Koog
# textStreamingCompletesSession overrides from 2026-04 to 2026-05.
EMPTY_OVERRIDES=$(python3 - <<'PYTHON_DETECT'
import re, pathlib

ROOTS = [p for p in pathlib.Path("modules").glob("*/src/test") if p.is_dir()]

JAVA_PATTERN = re.compile(
    r'((?:^[ \t]*@[A-Za-z][\w.]*(?:\([^)]*\))?\s*\n)*)'
    r'^[ \t]*@Override\b\s*\n'
    r'((?:^[ \t]*@[A-Za-z][\w.]*(?:\([^)]*\))?\s*\n)*)'
    r'^[ \t]*(?:public|protected|private)?\s*'
    r'(?:final\s+|static\s+)?'
    r'(?:<[^>]+>\s*)?'
    r'\w[\w.<>\[\]]*\s+'
    r'(\w+)\s*\([^)]*\)'
    r'(?:\s*throws\s+[\w.,\s]+)?\s*'
    r'\{([^{}]*?)\}',
    re.MULTILINE,
)

KOTLIN_PATTERN = re.compile(
    r'((?:^[ \t]*@[A-Za-z][\w.]*(?:\([^)]*\))?\s*\n)*)'
    r'^[ \t]*(?:internal\s+|public\s+|private\s+|protected\s+)?'
    r'override\s+fun\s+'
    r'(\w+)\s*\([^)]*\)'
    r'(?:\s*:\s*[\w.<>?]+)?\s*'
    r'\{([^{}]*?)\}',
    re.MULTILINE,
)

# First pass: collect every method name that carries @Test (or its variants)
# in any test source. The detector only flags overrides whose name appears in
# this set — that filters out implementations of unrelated interfaces (e.g.
# fixture stubs implementing AtmosphereHandler.onRequest()).
TEST_METHOD_PATTERN = re.compile(
    r'@(?:[\w.]*\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b[^\n]*\n'
    r'(?:[ \t]*@[A-Za-z][\w.]*(?:\([^)]*\))?\s*\n)*'
    r'[ \t]*(?:public|protected|private|internal|inline)?\s*'
    r'(?:abstract\s+|final\s+|static\s+|open\s+)?'
    r'(?:fun\s+|<[^>]+>\s+)?'
    r'(?:\w[\w.<>\[\]?]*\s+)?'
    r'(\w+)\s*\(',
    re.MULTILINE,
)

inherited_test_names = set()
for root in ROOTS:
    for path in list(root.rglob("*.java")) + list(root.rglob("*.kt")):
        text = path.read_text(errors="ignore")
        for m in TEST_METHOD_PATTERN.finditer(text):
            inherited_test_names.add(m.group(1))

def empty_or_comment_only(body: str) -> bool:
    stripped = body.strip()
    if not stripped:
        return True
    for line in stripped.split("\n"):
        s = line.strip()
        if not s or s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            continue
        return False
    return True

def has_test_or_disabled(annotation_text: str) -> bool:
    return bool(re.search(
        r'@(?:[\w.]*\.)?(?:Test|Disabled|Ignore|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b',
        annotation_text))

violations = []
EXTENDS_ABSTRACT_TEST_JAVA = re.compile(r'\bextends\s+\w*Abstract\w*Test\w*\b')
EXTENDS_ABSTRACT_TEST_KOTLIN = re.compile(r':\s*\w*Abstract\w*Test\w*\s*\(')
for root in ROOTS:
    for path in list(root.rglob("*.java")) + list(root.rglob("*.kt")):
        text = path.read_text(errors="ignore")
        is_kotlin = path.suffix == ".kt"
        # Only contract-test subclasses are at risk — interface stubs
        # (AtmosphereHandler.destroy(), Servlet.init(), etc.) legitimately
        # have empty bodies and are NOT what this detector targets.
        ext_pattern = EXTENDS_ABSTRACT_TEST_KOTLIN if is_kotlin else EXTENDS_ABSTRACT_TEST_JAVA
        if not ext_pattern.search(text):
            continue
        pattern = KOTLIN_PATTERN if is_kotlin else JAVA_PATTERN
        for m in pattern.finditer(text):
            if is_kotlin:
                anns, method, body = m.group(1) or "", m.group(2), m.group(3)
            else:
                anns = (m.group(1) or "") + (m.group(2) or "")
                method, body = m.group(3), m.group(4)
            if (method in inherited_test_names
                    and empty_or_comment_only(body)
                    and not has_test_or_disabled(anns)):
                line_no = text[:m.start()].count("\n") + 1
                violations.append(f"{path}:{line_no}: {method}()")

for v in violations:
    print(v)
PYTHON_DETECT
)

if [ -n "$EMPTY_OVERRIDES" ]; then
    fail_validation "Found empty-body @Override methods in test files (no @Test/@Disabled). JUnit 5 stops discovering an inherited @Test when the override has none of its own — the contract assertion silently disappears."
    echo "$EMPTY_OVERRIDES" | head -10
else
    pass_validation "No empty-body @Override methods masquerading as skipped tests"
fi

# Detect contract-test skip patterns that hide the errorContextTriggersSessionError
# assertion behind a silent skip. Two complementary surfaces:
#   1. createErrorContext() override whose body is `return null` — the base
#      contract test reads the null and calls Assumptions.assumeTrue(false),
#      so the runtime claims "error reaches session" parity it never asserts.
#   2. assumeTrue(false) / assumeFalse(true) used anywhere in a *RuntimeContractTest
#      file — same silent-skip outcome via a different surface.
# Allowlist NONE — this is exactly the pattern closed in fix/error-context-skips
# and the gate must prevent regression. Skipped paths are listed honestly via
# `@org.junit.jupiter.api.Disabled("reason")` with a tracking comment, not via
# assumeTrue(false).
# Only concrete contract-test subclasses are in scope — the abstract base
# (AbstractAgentRuntimeContractTest) legitimately uses assumeTrue(false) inside
# the assertion body to fire a structured TestAbortedException when a SUBCLASS
# fails to override a hook (e.g. createImageContext() returning null). The
# closure this gate enforces is that no SUBCLASS shadows
# errorContextTriggersSessionError with a silent skip — which is exactly what
# "createErrorContext returns null" and "assumeTrue(false) in a subclass"
# encode.
CONTRACT_TEST_FILES=$(find modules -path '*/src/test/*RuntimeContractTest*' \
    \( -name '*.java' -o -name '*.kt' \) 2>/dev/null)

NULL_ERROR_CTX_HITS=""
ASSUME_FALSE_HITS=""

if [ -n "$CONTRACT_TEST_FILES" ]; then
    # Pattern A: createErrorContext() override whose body is only `return null`.
    # Matches both Java (`return null;`) and Kotlin (`= null` or `return null`).
    NULL_ERROR_CTX_HITS=$(python3 - <<'PYTHON_NULL_CTX'
import re, pathlib, sys

JAVA_PATTERN = re.compile(
    r'protected\s+AgentExecutionContext\s+createErrorContext\s*\(\s*\)\s*\{\s*(?://[^\n]*\n\s*)*'
    r'return\s+null\s*;\s*\}',
    re.MULTILINE,
)
KOTLIN_PATTERN = re.compile(
    r'override\s+fun\s+createErrorContext\s*\(\s*\)\s*:\s*AgentExecutionContext\??\s*'
    r'(?:=\s*null|\{\s*(?://[^\n]*\n\s*)*return\s+null\s*\})',
    re.MULTILINE,
)

violations = []
for path in pathlib.Path("modules").rglob("*RuntimeContractTest*"):
    if not (path.suffix == ".java" or path.suffix == ".kt"):
        continue
    # Only concrete contract-test subclasses (under src/test/) are in scope.
    # The abstract base class under src/main/ legitimately uses assumeTrue(false)
    # to signal "subclass did not override this hook" — that's the mechanism
    # this gate exists to prevent subclasses from triggering, not the base.
    if "/src/test/" not in str(path):
        continue
    text = path.read_text(errors="ignore")
    pattern = KOTLIN_PATTERN if path.suffix == ".kt" else JAVA_PATTERN
    for m in pattern.finditer(text):
        line_no = text[:m.start()].count("\n") + 1
        violations.append(f"{path}:{line_no}: createErrorContext() returns null")

for v in violations:
    print(v)
PYTHON_NULL_CTX
)

    # Pattern B: assumeTrue(false) / assumeFalse(true) anywhere in a contract
    # test. Both surfaces fire the same silent-skip outcome. Wrap in || true
    # so rg's empty-match exit code 1 doesn't trip the surrounding set -e.
    ASSUME_FALSE_HITS=$(rg -n \
        'assumeTrue\s*\(\s*false\b|assumeFalse\s*\(\s*true\b' \
        $CONTRACT_TEST_FILES 2>/dev/null || true)
fi

if [ -n "$NULL_ERROR_CTX_HITS" ] || [ -n "$ASSUME_FALSE_HITS" ]; then
    fail_validation "Found contract-test skip patterns that silently disable errorContextTriggersSessionError (or sibling parity tests). Wire the runtime's mock to route CONTRACT_ERROR_SENTINEL to session.error(...) instead of returning null / asserting assumeTrue(false)."
    if [ -n "$NULL_ERROR_CTX_HITS" ]; then
        echo "$NULL_ERROR_CTX_HITS" | head -10
    fi
    if [ -n "$ASSUME_FALSE_HITS" ]; then
        echo "$ASSUME_FALSE_HITS" | head -10
    fi
else
    pass_validation "No contract-test silent-skip patterns (createErrorContext null / assumeTrue(false))"
fi

# ============================================================================
# 5. DEAD CODE PATTERNS
# ============================================================================

echo ""
echo -e "${BLUE}--- Dead Code Patterns ---${NC}"

# Backup / temporary files
BACKUP_FILES=$(find modules/ samples/ \( -name "*.bak" -o -name "*.backup" -o -name "*~" -o -name "*.orig" -o -name "*.tmp" \) -not -path "*/node_modules/*" -not -path "*/target/*" 2>/dev/null | head -20)

if [ -n "$BACKUP_FILES" ]; then
    fail_validation "Found backup/temporary files (must be removed):"
    echo "$BACKUP_FILES" | sed 's/^/  /'
else
    pass_validation "No backup/temporary files"
fi

# Console writes from library code.
#
# The rule is "library code logs through SLF4J", and the reason console entry
# points are exempt is that stdout IS their product: McpStdioBridge speaks
# JSON-RPC over stdout (routing it to a logger would break the MCP transport),
# and TapeDatasetCli / Version print what the operator asked for. So a class
# declaring main() is a console program by definition and may write to the
# console; everything else must not.
#
# CODE mode: all 6 findings this pair used to report were wasync Javadoc
# examples teaching callers `socket.on(MESSAGE, m -> System.out.println(m))`.
# Documentation showing a println is not a println.
console_entrypoints() {
    # Filter "path:line:text" hits, dropping those in classes with a main().
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        file="${hit%%:*}"
        if ! rg -q 'public\s+static\s+void\s+main\s*\(' "$file" 2>/dev/null; then
            echo "$hit"
        fi
    done
}

SYSOUT_HITS=$(python3 "$SCAN" --mode code \
    --regex 'System\.(out|err)\.(print|println|printf)' \
    $NON_SHIPPED_EXCLUDES $SHIPPED_SRC_DIRS 2>/dev/null | console_entrypoints)
SYSOUT_COUNT=$(printf '%s' "$SYSOUT_HITS" | grep -c . || true)

if [ "$SYSOUT_COUNT" -gt "$THRESHOLD_SYSOUT" ]; then
    fail_validation "Found $SYSOUT_COUNT System.out/err call(s) in shipped library code — use SLF4J (threshold: $THRESHOLD_SYSOUT)"
    echo "$SYSOUT_HITS" | head -10
    echo "  (Classes declaring main() are console entry points and are exempt.)"
else
    pass_validation "No System.out/err in shipped library code"
fi

# printStackTrace() calls
STACKTRACE_HITS=$(python3 "$SCAN" --mode code --regex '\.printStackTrace\(\)' \
    $NON_SHIPPED_EXCLUDES $SHIPPED_SRC_DIRS 2>/dev/null | console_entrypoints)
STACKTRACE_COUNT=$(printf '%s' "$STACKTRACE_HITS" | grep -c . || true)

if [ "$STACKTRACE_COUNT" -gt "$THRESHOLD_STACKTRACE" ]; then
    fail_validation "Found $STACKTRACE_COUNT .printStackTrace() call(s) in shipped code — use logger.error() (threshold: $THRESHOLD_STACKTRACE)"
    echo "$STACKTRACE_HITS" | head -10
else
    pass_validation "No .printStackTrace() in shipped code"
fi

# ============================================================================
# 6. FLUENT/IMMUTABLE BUILDER MISUSE
# ============================================================================

echo ""
echo -e "${BLUE}--- Fluent Builder Misuse Detection ---${NC}"

# Detect fluent builder calls where the return value is discarded.
# This was the exact bug in SpringAiSupport: promptSpec.system(...) was called
# but the result wasn't reassigned, so the system prompt was silently lost.
# Enumerate every module's main-java source dir so newly added runtimes are
# checked automatically. Was hardcoded to ai/spring-ai/langchain4j/adk —
# embabel, koog, agentscope, spring-ai-alibaba, semantic-kernel were silently
# exempt until the 2026-05 audit caught it.
AI_SRC=$(find modules -maxdepth 4 -type d -path 'modules/*/src/main/java' 2>/dev/null | sort | tr '\n' ' ')
FLUENT_MISUSE=0
for ai_dir in $AI_SRC; do
    [ -d "$ai_dir" ] || continue
    count=$(rg '^\s+\w+Spec\.(system|user|messages|advisors|toolCallbacks)\s*\(' "$ai_dir" --type java 2>/dev/null \
        | grep -v '^\s*//' \
        | grep -v 'return ' \
        | grep -v '^\s*var ' \
        | grep -v '^\s*\w\+\s*=' \
        | wc -l | tr -d ' ')
    FLUENT_MISUSE=$((FLUENT_MISUSE + count))
done

if [ "$FLUENT_MISUSE" -gt 0 ]; then
    fail_validation "Found $FLUENT_MISUSE fluent builder calls with discarded return values in AI modules"
    for ai_dir in $AI_SRC; do
        [ -d "$ai_dir" ] || continue
        rg '^\s+\w+Spec\.(system|user|messages|advisors|toolCallbacks)\s*\(' "$ai_dir" --type java -n 2>/dev/null \
            | grep -v '^\s*//' | grep -v 'return ' | grep -v '^\s*var ' | grep -v '^\s*\w\+\s*=' | head -5
    done
else
    pass_validation "No fluent builder misuse detected in AI modules"
fi

# ============================================================================
# 7. DEPENDENCY INJECTION BYPASS
# ============================================================================

echo ""
echo -e "${BLUE}--- DI Bypass Detection ---${NC}"

# Only flag in extension modules (cpr IS the DI framework — raw reflection is correct there).
# Enumerate every module except cpr so newly added runtimes are checked automatically.
# Was hardcoded — embabel/koog/agentscope/spring-ai-alibaba/semantic-kernel were
# silently exempt until the 2026-05 audit caught it.
DI_MODULES=$(find modules -mindepth 1 -maxdepth 1 -type d ! -name cpr 2>/dev/null | sort | tr '\n' ' ')
RAW_REFLECTION=0
RAW_REFLECTION_FILES=""
for mod in $DI_MODULES; do
    [ -d "$mod/src/main" ] || continue
    # Scan both java and kotlin sources so Kotlin runtimes (embabel, koog) are
    # not silently exempt — pre-2026-05 only `--type java` was scanned.
    count=$(rg 'getDeclaredConstructor\(\)\.newInstance\(\)' "$mod/src/main" --type java --type kotlin -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')
    if [ "$count" -gt 0 ]; then
        RAW_REFLECTION=$((RAW_REFLECTION + count))
        RAW_REFLECTION_FILES="${RAW_REFLECTION_FILES}$(rg 'getDeclaredConstructor\(\)\.newInstance\(\)' "$mod/src/main" --type java --type kotlin -n 2>/dev/null)\n"
    fi
done

if [ "$RAW_REFLECTION" -gt 0 ]; then
    fail_validation "Found $RAW_REFLECTION raw reflection instantiations in extension modules (use framework.newClassInstance())"
    echo -e "$RAW_REFLECTION_FILES" | head -5
else
    pass_validation "No raw reflection DI bypass in extension modules"
fi

# ============================================================================
# 8. CI WORKFLOW INTEGRITY
# ============================================================================

echo ""
echo -e "${BLUE}--- CI Workflow Integrity ---${NC}"

# continue-on-error: true (excluding allowlisted workflows)
#
# Counted with `grep -c .` rather than `echo "$X" | wc -l`: echo emits a
# trailing newline even for an empty variable, so the old idiom reported 1
# finding when there were zero. As a WARN that miscount was invisible; as a
# blocking check it fails the build on a clean tree.
CI_COE_TOTAL=$(rg 'continue-on-error:\s*true' .github/workflows/ -n 2>/dev/null | grep -v '#' || true)
if [ -n "$CI_COE_ALLOWLIST" ]; then
    CI_COE_UNAUTHORIZED=$(printf '%s' "$CI_COE_TOTAL" | grep -vE "$CI_COE_ALLOWLIST" || true)
else
    CI_COE_UNAUTHORIZED="$CI_COE_TOTAL"
fi
CI_COE=$(printf '%s' "$CI_COE_UNAUTHORIZED" | grep -c . || true)

if [ "$CI_COE" -gt 0 ]; then
    fail_validation "Found $CI_COE unauthorized 'continue-on-error: true' in CI workflows"
    echo "$CI_COE_UNAUTHORIZED" | head -5
else
    pass_validation "No unauthorized continue-on-error in CI workflows"
fi

# A workflow that skips tests must run tests somewhere. See the rationale on
# [ci_allowlist].never_tests — the old check flagged the -DskipTests flag
# itself, which meant 19 findings and no bugs, because priming a dependency
# closure before a real test step is the normal shape of every lane here.
if ! SKIP_TESTS_REPORT=$(python3 - "$TOML_FILE" <<'PYTHON_SKIPTESTS'
import sys, re, pathlib, tomllib

with open(sys.argv[1], "rb") as f:
    ci = tomllib.load(f).get("ci_allowlist", {})
never = set(ci.get("never_tests", []))
patterns = [re.compile(p) for p in ci.get("test_step_patterns", [])]

problems = []
for wf in sorted(pathlib.Path(".github/workflows").glob("*.y*ml")):
    text = wf.read_text(errors="ignore")
    # Ignore commented-out lines so a note about -DskipTests isn't a finding.
    live = "\n".join(l for l in text.split("\n")
                     if not l.lstrip().startswith("#"))
    if "-DskipTests" not in live:
        continue
    if wf.name in never:
        continue
    if any(p.search(live) for p in patterns):
        continue
    problems.append(
        f"  {wf.name}: uses -DskipTests but has no step matching any known "
        f"test-runner pattern. Add the real test step, or list it in "
        f"[ci_allowlist].never_tests with a reason.")

for p in problems:
    print(p)
sys.exit(1 if problems else 0)
PYTHON_SKIPTESTS
); then
    fail_validation "CI workflow(s) skip tests without running any:"
    echo "$SKIP_TESTS_REPORT"
else
    pass_validation "Every -DskipTests workflow runs tests in a separate step"
fi

# ============================================================================
# 9. NO @Experimental ON MAIN
# ============================================================================

echo ""
echo -e "${BLUE}--- No @Experimental markers ---${NC}"

# main is release-ready: an @Experimental / Beta / deferral marker is the
# escape hatch feedback_no_beta_on_main forbids. Either the feature is shipped
# (state its scope plainly) or it is removed. This check fails the build on any
# `@Experimental` marker in production OR test sources — the previous
# "allowed-with-expiry" policy was replaced with an outright ban.

EXPERIMENTAL_VIOLATIONS=0

while IFS=: read -r exp_file exp_line exp_text; do
    [ -z "$exp_file" ] && continue
    fail_validation "@Experimental marker not allowed on main at $exp_file:$exp_line — ship the feature and state its scope, or remove it"
    EXPERIMENTAL_VIOLATIONS=$((EXPERIMENTAL_VIOLATIONS + 1))
done < <(rg -n '@Experimental' $SRC_DIRS --type java --type kotlin 2>/dev/null)

if [ "$EXPERIMENTAL_VIOLATIONS" -eq 0 ]; then
    pass_validation "No @Experimental markers in source (main stays release-ready)"
fi

# ============================================================================
# 10. HARDCODED SECRETS
# ============================================================================

echo ""
echo -e "${BLUE}--- Hardcoded Values ---${NC}"

SECRET_PATTERNS='sk-[a-zA-Z0-9]{20,}|AKIA[A-Z0-9]{16}|ghp_[a-zA-Z0-9]{36}'
HARDCODED_SECRETS=$(rg "$SECRET_PATTERNS" $SRC_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$HARDCODED_SECRETS" -gt 0 ]; then
    fail_validation "CRITICAL: Found $HARDCODED_SECRETS potential hardcoded API keys/secrets!"
    rg "$SECRET_PATTERNS" $SRC_DIRS --type java -n 2>/dev/null | head -3
else
    pass_validation "No hardcoded API keys or secrets"
fi

# ============================================================================
# 11. EMBEDDED JETTY STATIC SERVING — PROTECTED TARGETS
# ============================================================================

echo ""
echo -e "${BLUE}--- Embedded Jetty Protected Targets ---${NC}"

# A plain Jetty ServletContextHandler (not a WAR WebAppContext) does NOT hide
# the reserved WEB-INF/META-INF directories. A sample that serves a static base
# resource through the DefaultServlet must therefore call setProtectedTargets(),
# or those dirs (web.xml, META-INF/maven/**/pom.properties) leak to any
# unauthenticated client — broken access control + exact-version disclosure.
# Scope is samples/ (the shipped, user-facing Jetty bootstraps).
JETTY_UNPROTECTED=""
while IFS= read -r f; do
    [ -z "$f" ] && continue
    if rg -q 'ServletContextHandler' "$f" 2>/dev/null \
        && rg -q 'setBaseResource|setResourceBase' "$f" 2>/dev/null \
        && ! rg -q 'setProtectedTargets' "$f" 2>/dev/null; then
        JETTY_UNPROTECTED="${JETTY_UNPROTECTED}${f}"$'\n'
    fi
done < <(rg -l 'DefaultServlet' samples/ --type java -g '!*/target/*' 2>/dev/null)

if [ -n "$JETTY_UNPROTECTED" ]; then
    fail_validation "Embedded-Jetty sample serves a static base via DefaultServlet without setProtectedTargets({\"/WEB-INF\",\"/META-INF\"}):"
    echo "$JETTY_UNPROTECTED" | sed '/^$/d; s/^/  /'
    echo "  (see samples/embedded-jetty-websocket-chat for the reference pattern)"
else
    pass_validation "Embedded-Jetty static-serving samples all set protected targets (WEB-INF/META-INF)"
fi

# ============================================================================
# 12. CONSOLE BUNDLE SYNC (spring-boot3-starter / quarkus-admin-extension)
# ============================================================================

echo ""
echo -e "${BLUE}--- Console Bundle Sync ---${NC}"

# The Console SPA is built from source only in modules/spring-boot-starter;
# spring-boot3-starter and quarkus-admin-extension ship committed copies of
# the built bundle. Without this gate those copies drift silently — the Tape
# tab shipped to the SB4 console while the committed copies stayed on an
# older build. The check fingerprints the SPA inputs (console frontend +
# atmosphere.js sources) against the marker written by the sync script, and
# requires the two committed copies to be byte-identical to each other. No
# Maven/npm runs here — it only hashes files.
if ./scripts/sync-console-bundle.sh --check >/dev/null 2>&1; then
    pass_validation "Committed console bundles match the SPA sources (scripts/sync-console-bundle.sh)"
else
    fail_validation "Committed console bundles are stale or diverged:"
    ./scripts/sync-console-bundle.sh --check 2>&1 | sed 's/^/  /' || true
fi

# ============================================================================
# RESULTS SUMMARY
# ============================================================================

echo ""
echo -e "${BLUE}====================================================${NC}"

if [ "$VALIDATION_FAILED" = true ]; then
    echo -e "${RED}ARCHITECTURAL VALIDATION FAILED${NC}"
    echo ""
    echo "Fix the FAIL items above before committing — every check here blocks."
    echo "If a finding is a false positive, fix the check in"
    echo "scripts/architectural-validation.sh so it stops asking the wrong"
    echo "question; if it is a legitimate exception, allowlist the specific"
    echo "case with a reason in scripts/validation-patterns.toml. Do not"
    echo "raise a threshold to step over it."
    exit 1
fi

echo -e "${GREEN}ARCHITECTURAL VALIDATION PASSED${NC}"
echo ""
exit 0

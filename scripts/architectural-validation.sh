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

warn_validation() {
    echo -e "${YELLOW}  WARN: $1${NC}"
}

pass_validation() {
    echo -e "${GREEN}  PASS: $1${NC}"
}

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
print(f'THRESHOLD_MOCK={th.get("mock_in_production", 5)}')

# Critical placeholder patterns -> pipe-separated regex
cp = config.get("placeholder_patterns", {}).get("critical", [])
# Escape pipe chars in patterns, join with |
critical_regex = "|".join(cp)
print(f'CRITICAL_PLACEHOLDERS="{critical_regex}"')

# CI continue-on-error allowlist
coe = config.get("ci_allowlist", {}).get("continue_on_error", [])
print(f'CI_COE_ALLOWLIST="{"|".join(coe)}"')

# CI skip-tests allowlist
st = config.get("ci_allowlist", {}).get("skip_tests", [])
print(f'CI_SKIP_TESTS_ALLOWLIST="{"|".join(st)}"')

# NOOP allowlist -> pipe-separated for grep -E
na = config.get("noop_allowlist", {}).get("internal_use", [])
print(f'NOOP_ALLOWLIST="{"|".join(na)}"')
PYTHON_PARSER
)"

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
NOOP_DECLS=$(rg "static\s+final\s+.*\b(NOOP|NO_OP)\b" $SRC_DIRS --type java -l 2>/dev/null || true)
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
        done < <(rg "static\s+final\s+.*\b(NOOP|NO_OP)\b" "$file" 2>/dev/null)
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
    warn_validation "AI module interfaces with zero implementations in production code:"
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
    warn_validation "Empty META-INF/services files (SPI declared but no provider listed):"
    echo -e "$EMPTY_SPI"
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

# 2b. TODO/FIXME markers (warning only)
TODO_COUNT=$(rg '\bTODO\b|\bFIXME\b|\bXXX\b|\bHACK\b' $SRC_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')
if [ "$TODO_COUNT" -gt 0 ]; then
    warn_validation "Found $TODO_COUNT TODO/FIXME/HACK markers in production code"
else
    pass_validation "No TODO/FIXME markers in production code"
fi

# 2c. Stub/mock patterns in production source (NOT test source)
# Filter pipeline: rg output is "file:line:content". We filter on content portion.
# Exclude: comment lines (// or * or /*), Javadoc, imports, and false positive words.
MOCK_IN_PROD=$(rg -i "\bmock\b|\bdummy\b|\bfake\b|\bstub\b" $SRC_DIRS --type java \
    -g '!*Mock*' -g '!*Stub*' -g '!*Fake*' -g '!*Test*' \
    -g '!*/integration-tests/*' 2>/dev/null \
    | grep -v '//' | grep -v '\*.*\(mock\|stub\|fake\|dummy\)' \
    | grep -v 'Mockito' | grep -v 'import' \
    | grep -viE "$MOCK_FALSE_POSITIVES" \
    | wc -l | tr -d ' ')

if [ "$MOCK_IN_PROD" -gt "$THRESHOLD_MOCK" ]; then
    fail_validation "Found $MOCK_IN_PROD mock/stub/fake references in production code (threshold: $THRESHOLD_MOCK)"
    rg -i "\bmock\b|\bdummy\b|\bfake\b|\bstub\b" $SRC_DIRS --type java -n \
        -g '!*Mock*' -g '!*Stub*' -g '!*Fake*' -g '!*Test*' \
        -g '!*/integration-tests/*' 2>/dev/null \
        | grep -v '//' | grep -v '\*.*\(mock\|stub\|fake\|dummy\)' \
        | grep -v 'Mockito' | grep -v 'import' \
        | grep -viE "$MOCK_FALSE_POSITIVES" \
        | head -5
elif [ "$MOCK_IN_PROD" -gt 0 ]; then
    warn_validation "Found $MOCK_IN_PROD mock/stub/fake references in production code"
else
    pass_validation "No mock/stub code in production sources"
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
UNCHECKED=$(rg '^\s+\w+\.offer\(' $SOURCE_DIRS --type java -l 2>/dev/null | \
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

DISABLED_TESTS=$(rg '@Disabled\b' $TEST_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$DISABLED_TESTS" -gt 0 ]; then
    BARE_DISABLED=$(rg '@Disabled\s*$' $TEST_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')
    if [ "$BARE_DISABLED" -gt 0 ]; then
        warn_validation "Found $BARE_DISABLED @Disabled tests without a reason string"
        rg '@Disabled\s*$' $TEST_DIRS --type java -n 2>/dev/null | head -5
    fi
    echo "  Total @Disabled tests: $DISABLED_TESTS"
else
    pass_validation "No @Disabled tests"
fi

IGNORE_TESTS=$(rg '@Ignore\b' $TEST_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$IGNORE_TESTS" -gt 0 ]; then
    fail_validation "Found $IGNORE_TESTS @Ignore annotations (JUnit 4 legacy — migrate to @Disabled)"
    rg '@Ignore\b' $TEST_DIRS --type java -n 2>/dev/null | head -5
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

# System.out.println in production code
SYSOUT_COUNT=$(rg 'System\.(out|err)\.(print|println)' $SRC_DIRS --type java \
    -g '!*/integration-tests/*' -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$SYSOUT_COUNT" -gt 0 ]; then
    warn_validation "Found $SYSOUT_COUNT System.out/err.print calls in production code (use SLF4J)"
    rg 'System\.(out|err)\.(print|println)' $SRC_DIRS --type java -n \
        -g '!*/integration-tests/*' 2>/dev/null | head -5
else
    pass_validation "No System.out/err in production code"
fi

# printStackTrace() calls
STACKTRACE_COUNT=$(rg '\.printStackTrace\(\)' $SRC_DIRS --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')

if [ "$STACKTRACE_COUNT" -gt 0 ]; then
    warn_validation "Found $STACKTRACE_COUNT .printStackTrace() calls (use logger.error())"
    rg '\.printStackTrace\(\)' $SRC_DIRS --type java -n 2>/dev/null | head -5
else
    pass_validation "No .printStackTrace() in production code"
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
CI_COE_TOTAL=$(rg 'continue-on-error:\s*true' .github/workflows/ -n 2>/dev/null | grep -v '#' || true)
if [ -n "$CI_COE_ALLOWLIST" ]; then
    CI_COE=$(echo "$CI_COE_TOTAL" | grep -vE "$CI_COE_ALLOWLIST" | wc -l | tr -d ' ')
else
    CI_COE=$(echo "$CI_COE_TOTAL" | wc -l | tr -d ' ')
fi

if [ "$CI_COE" -gt 0 ]; then
    warn_validation "Found $CI_COE unauthorized 'continue-on-error: true' in CI workflows"
    echo "$CI_COE_TOTAL" | grep -vE "$CI_COE_ALLOWLIST" 2>/dev/null | head -5
else
    pass_validation "No unauthorized continue-on-error in CI workflows"
fi

# -DskipTests (excluding allowlisted workflows)
SKIP_TESTS_TOTAL=$(rg '\-DskipTests' .github/workflows/ -n 2>/dev/null | grep -v '#' || true)
if [ -n "$CI_SKIP_TESTS_ALLOWLIST" ]; then
    SKIP_TESTS_CI=$(echo "$SKIP_TESTS_TOTAL" | grep -vE "$CI_SKIP_TESTS_ALLOWLIST" | wc -l | tr -d ' ')
else
    SKIP_TESTS_CI=$(echo "$SKIP_TESTS_TOTAL" | wc -l | tr -d ' ')
fi

if [ "$SKIP_TESTS_CI" -gt 0 ]; then
    warn_validation "Found $SKIP_TESTS_CI unauthorized '-DskipTests' in CI workflows"
    echo "$SKIP_TESTS_TOTAL" | grep -vE "$CI_SKIP_TESTS_ALLOWLIST" 2>/dev/null | head -5
else
    pass_validation "No unauthorized -DskipTests in CI workflows"
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
# RESULTS SUMMARY
# ============================================================================

echo ""
echo -e "${BLUE}====================================================${NC}"

if [ "$VALIDATION_FAILED" = true ]; then
    echo -e "${RED}ARCHITECTURAL VALIDATION FAILED${NC}"
    echo ""
    echo "Fix the FAIL items above before committing."
    echo "WARN items are advisory but should be addressed."
    echo "Allowlists: scripts/validation-patterns.toml"
    exit 1
fi

echo -e "${GREEN}ARCHITECTURAL VALIDATION PASSED${NC}"
echo ""
exit 0

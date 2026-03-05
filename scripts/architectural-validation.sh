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
#   ./scripts/architectural-validation.sh          # full scan
#   ./scripts/architectural-validation.sh --fast   # critical checks only

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

FAST_MODE=false
if [ "$1" = "--fast" ]; then
    FAST_MODE=true
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

# 1c. META-INF/services files that are empty
EMPTY_SPI=""
while IFS= read -r spi_file; do
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

if [ "$FAST_MODE" = true ]; then
    echo ""
    echo -e "${BLUE}--- Fast mode: skipping extended checks ---${NC}"
    echo ""
    if [ "$VALIDATION_FAILED" = true ]; then
        echo -e "${RED}ARCHITECTURAL VALIDATION FAILED${NC}"
        exit 1
    fi
    echo -e "${GREEN}ARCHITECTURAL VALIDATION PASSED (fast mode)${NC}"
    exit 0
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
AI_SRC="modules/ai/src/main/java modules/spring-ai/src/main/java modules/langchain4j/src/main/java modules/adk/src/main/java"
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

# Only flag in extension modules (cpr IS the DI framework — raw reflection is correct there)
DI_MODULES="modules/ai modules/spring-ai modules/langchain4j modules/adk modules/mcp modules/spring-boot-starter modules/quarkus-extension"
RAW_REFLECTION=0
RAW_REFLECTION_FILES=""
for mod in $DI_MODULES; do
    [ -d "$mod/src/main/java" ] || continue
    count=$(rg 'getDeclaredConstructor\(\)\.newInstance\(\)' "$mod/src/main/java" --type java -c 2>/dev/null | awk -F: '{sum+=$2} END {print sum+0}')
    if [ "$count" -gt 0 ]; then
        RAW_REFLECTION=$((RAW_REFLECTION + count))
        RAW_REFLECTION_FILES="${RAW_REFLECTION_FILES}$(rg 'getDeclaredConstructor\(\)\.newInstance\(\)' "$mod/src/main/java" --type java -n 2>/dev/null)\n"
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
# 9. HARDCODED SECRETS
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

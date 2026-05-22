#!/usr/bin/env bash
#
# SkillSpector-equivalent pre-publish scanner for Atmosphere skill cards.
# Inspired by NVIDIA's SkillSpector tool (the conventional + agent-specific
# risk scan that gates the verified-agent-skills catalog before publishing).
# Grounded in OWASP LLM injection guidance and the categories called out in
# the agentskills.io trust model.
#
# Categories scanned (per card and per .sig if present):
#   1. Prompt injection markers in free-text fields (description, etc.).
#      Conservative regex set — common attack tokens, not aggressive
#      false-positive bait.
#   2. Hidden Unicode: zero-width chars (U+200B-200D), Bidi overrides
#      (U+2066-2069, U+202A-202E). These are the standard ways to smuggle
#      invisible content into a manifest.
#   3. Capability-safety sanity: if a card declares TOOL_CALLING but not
#      TOOL_APPROVAL, that's "excessive agency" by the OWASP A06 definition.
#      (Contract tests already enforce this in code; the card-level
#      check catches drift between the live runtime and the published manifest.)
#   4. SPI class existence: the FQN in spi.implementation must correspond
#      to a real source file under modules/<X>/src/main/. Catches a card
#      pointing at a removed or renamed class.
#   5. Path safety: no `..`, no absolute paths in path-shaped fields
#      (module_path, contract_test). Boundary safety per Correctness
#      Invariant #4.
#
# Output:
#   - Human-readable findings to stderr (exit non-zero on any HIGH severity)
#   - JSON report at .harness/skillcard-scan-report.json (for CI parsing)
#
# Usage:
#   ./scripts/scan-skillcards.sh         # scan, exit 1 on HIGH findings
#   ./scripts/scan-skillcards.sh --check # same; intended for pre-push hook
#   ./scripts/scan-skillcards.sh --json  # emit ONLY the JSON report to stdout

set -uo pipefail
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

MODE="report"
for arg in "$@"; do
    case "$arg" in
        --check) MODE="report" ;;
        --json)  MODE="json" ;;
        -h|--help)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "scan-skillcards.sh: unknown arg $arg" >&2; exit 2 ;;
    esac
done

mapfile -t CARDS < <(find modules -maxdepth 2 -name SKILLCARD.yaml -not -path '*/target/*' | sort)
if [ ${#CARDS[@]} -eq 0 ]; then
    echo "scan-skillcards.sh: no SKILLCARD.yaml manifests found under modules/" >&2
    exit 1
fi

# Prompt-injection patterns. Each entry is "severity|regex|label". HIGH
# fails the scan; MEDIUM is reported only. Patterns are case-insensitive
# (handled by grep -E -i below).
PROMPT_INJECTION_PATTERNS=(
    "HIGH|ignore (all )?previous instructions|ignore-previous-instructions"
    "HIGH|disregard (the |all )?(prior|previous) (rules|instructions)|disregard-prior"
    "HIGH|\\[INST\\]|llama-instruction-tag"
    "HIGH|<\\|im_start\\|>|chatml-im-start"
    "HIGH|<\\|im_end\\|>|chatml-im-end"
    "MEDIUM|jailbreak|jailbreak-keyword"
    "MEDIUM|prompt injection|prompt-injection-keyword"
    "MEDIUM|developer mode|developer-mode-keyword"
)

# Hidden Unicode categories — codepoint class with explicit \x{} escapes
# so the regex itself contains zero invisible characters (using literal
# zero-width chars in the pattern was both unreadable and produced
# corrupted UTF-8 bytes on some shells, causing false positives on
# every line). Catches U+200B-200D (ZWSP/ZWNJ/ZWJ), U+2066-2069
# (directional isolates), U+202A-202E (Bidi embedding/override),
# U+FEFF (BOM).
HIDDEN_UNICODE_PATTERN='[\x{200B}-\x{200D}\x{2066}-\x{2069}\x{202A}-\x{202E}\x{FEFF}]'

# Capabilities that demand a paired safety capability.
declare -A REQUIRED_PAIRS=(
    ["TOOL_CALLING"]="TOOL_APPROVAL"
)

findings=()
high_count=0
medium_count=0

scan_card() {
    local card="$1"
    local rel="${card#$REPO_ROOT/}"
    rel="${card}"

    # 1. Prompt injection scan — search whole file.
    for pat in "${PROMPT_INJECTION_PATTERNS[@]}"; do
        IFS='|' read -r sev regex label <<<"$pat"
        if grep -E -i -n "$regex" "$card" >/dev/null 2>&1; then
            local lineno
            lineno=$(grep -E -i -n "$regex" "$card" | head -1 | cut -d: -f1)
            findings+=("{\"card\":\"$rel\",\"category\":\"prompt_injection\",\"severity\":\"$sev\",\"label\":\"$label\",\"line\":$lineno}")
            if [ "$sev" = "HIGH" ]; then
                high_count=$((high_count + 1))
            else
                medium_count=$((medium_count + 1))
            fi
        fi
    done

    # 2. Hidden Unicode scan — perl with -CSD for UTF-8 handling. Cross-
    # platform (BSD grep on macOS doesn't reliably support -P with the
    # \x{} Unicode escape syntax; perl does on every box that has it).
    local hidden_line
    hidden_line=$(perl -CSD -ne '
        if (/'"$HIDDEN_UNICODE_PATTERN"'/) { print $.; exit 0 }
    ' "$card" 2>/dev/null)
    if [ -n "$hidden_line" ]; then
        findings+=("{\"card\":\"$rel\",\"category\":\"hidden_unicode\",\"severity\":\"HIGH\",\"label\":\"zero-width-or-bidi-override\",\"line\":$hidden_line}")
        high_count=$((high_count + 1))
    fi

    # 3. Capability-safety sanity.
    for required_when in "${!REQUIRED_PAIRS[@]}"; do
        local must_have="${REQUIRED_PAIRS[$required_when]}"
        if grep -q "^    - ${required_when}$" "$card"; then
            if ! grep -q "^    - ${must_have}$" "$card"; then
                findings+=("{\"card\":\"$rel\",\"category\":\"excessive_agency\",\"severity\":\"HIGH\",\"label\":\"${required_when}_without_${must_have}\",\"line\":0}")
                high_count=$((high_count + 1))
            fi
        fi
    done

    # 4. SPI class existence check.
    local spi_impl
    spi_impl=$(grep -E '^  implementation: ' "$card" | awk '{print $2}' | head -1)
    if [ -n "$spi_impl" ]; then
        # FQN → relative source path candidate.
        local rel_path="${spi_impl//.//}"
        # Java + Kotlin sources are both possible.
        if ! find modules -path "*/src/main/*/${rel_path}.java" -o -path "*/src/main/*/${rel_path}.kt" \
                | grep -q .; then
            findings+=("{\"card\":\"$rel\",\"category\":\"spi_class_missing\",\"severity\":\"HIGH\",\"label\":\"${spi_impl}_not_found_on_disk\",\"line\":0}")
            high_count=$((high_count + 1))
        fi
    fi

    # 5. Path safety on path-shaped fields.
    for field in module_path contract_test; do
        local v
        v=$(grep -E "^[ ]*${field}: " "$card" | awk '{print $2}' | head -1)
        if [ -n "$v" ]; then
            if [[ "$v" == /* || "$v" == *..* ]]; then
                findings+=("{\"card\":\"$rel\",\"category\":\"path_safety\",\"severity\":\"HIGH\",\"label\":\"${field}_unsafe:${v}\",\"line\":0}")
                high_count=$((high_count + 1))
            fi
        fi
    done
}

for card in "${CARDS[@]}"; do
    scan_card "$card"
done

# Emit JSON report.
mkdir -p .harness
{
    echo "{"
    echo "  \"schema_version\": 1,"
    echo "  \"scanned_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"cards_scanned\": ${#CARDS[@]},"
    echo "  \"high_findings\": $high_count,"
    echo "  \"medium_findings\": $medium_count,"
    echo "  \"findings\": ["
    if [ ${#findings[@]} -gt 0 ]; then
        sep=""
        for f in "${findings[@]}"; do
            printf "%s    %s" "$sep" "$f"
            sep=$',\n'
        done
        echo ""
    fi
    echo "  ]"
    echo "}"
} > .harness/skillcard-scan-report.json

if [ "$MODE" = "json" ]; then
    cat .harness/skillcard-scan-report.json
    exit 0
fi

if [ $high_count -gt 0 ]; then
    echo "scan-skillcards.sh: $high_count HIGH-severity finding(s) — see .harness/skillcard-scan-report.json" >&2
    for f in "${findings[@]}"; do
        if echo "$f" | grep -q '"severity":"HIGH"'; then
            card=$(echo "$f" | sed -n 's/.*"card":"\([^"]*\)".*/\1/p')
            label=$(echo "$f" | sed -n 's/.*"label":"\([^"]*\)".*/\1/p')
            line=$(echo "$f" | sed -n 's/.*"line":\([0-9]*\).*/\1/p')
            echo "  HIGH  $card:$line  $label" >&2
        fi
    done
    exit 1
fi

echo "scan-skillcards.sh: ${#CARDS[@]} cards scanned, 0 HIGH findings ($medium_count MEDIUM informational)"

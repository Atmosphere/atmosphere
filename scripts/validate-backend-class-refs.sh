#!/usr/bin/env bash
#
# Validate that backend-prefixed class-name tokens referenced in Javadoc and
# Markdown documentation actually resolve to a declared class in the reactor.
#
# The drift class this catches: a Javadoc or doc page enumerates backend
# implementations of an SPI ("Backed by ... SqliteFooStore / RedisFooStore /
# PostgresFooStore ...") when only one of them ships. The original incident
# was the LongTermMemory Javadoc claiming "Backed by a FactStore
# implementation (in-memory, Redis, SQLite)" with no FactStore class and no
# Redis or SQLite LongTermMemory in tree (drift-log entry #53).
#
# Scope: any token of the form `(Sqlite|Redis|Postgres|Mongo|Cassandra|
# Hazelcast|JGroups|Kafka|Nats)[A-Z]\w+` mentioned inside `*.java` source
# (which catches Javadoc and prose comments alike — false positives on
# identifier mentions inside code are filtered below) or `*.md`
# documentation. Each unique token must either:
#   (1) declare a class with that exact name somewhere under
#       `modules/**/src/main/java/` or `samples/**/src/main/java/`,
#   (2) appear in `.harness/external-class-allowlist.txt` (third-party
#       library types such as Lettuce's `RedisClient`).
#
# Otherwise the script fails with the offending token and the files that
# referenced it.
#
# Run from repo root. Exits 0 on success, 1 on any unresolved reference.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/external-class-allowlist.txt"

# Backend prefixes worth gating.
PREFIXES='Sqlite|SQLite|Redis|Postgres|PostgreSQL|Mongo|MongoDB|Cassandra|Hazelcast|JGroups|Kafka|Nats|NATS'

# Files to scan: Java sources + Markdown docs in main repo only.
# (Sibling repo atmosphere.github.io has its own validation.)
mapfile -t scan_files < <(git ls-files \
    '*.java' '*.md' \
    ':!**/target/**' \
    ':!.harness/external-class-allowlist.txt' \
    ':!.harness/drift-log.md' \
    ':!scripts/validate-backend-class-refs.sh')

# Build the set of declared class names in the reactor (one class declaration
# per match — interfaces and enums also count as types that could be
# referenced). Includes both main and test sources because tests legitimately
# reference their own class name in headers/imports.
mapfile -t declared_classes < <(
    git grep -h -E '^[[:space:]]*(public[[:space:]]+|private[[:space:]]+)?(static[[:space:]]+)?(final[[:space:]]+|abstract[[:space:]]+|sealed[[:space:]]+|non-sealed[[:space:]]+)*(class|interface|@?enum|record)[[:space:]]+([A-Z][A-Za-z0-9_]*)' \
        -- 'modules/**/src/main/java/**/*.java' 'samples/**/src/main/java/**/*.java' \
           'modules/**/src/test/java/**/*.java' 'samples/**/src/test/java/**/*.java' \
        | sed -E 's/.*(class|interface|enum|record)[[:space:]]+([A-Z][A-Za-z0-9_]*).*/\2/' \
        | sort -u)

# Load allowlist (one token per line, # comments allowed).
declare -A allowed
if [ -f "$ALLOWLIST" ]; then
    while IFS= read -r line; do
        line="${line%%#*}"; line="${line//[[:space:]]/}"
        [ -n "$line" ] && allowed["$line"]=1
    done < "$ALLOWLIST"
fi

# Index declared classes for O(1) lookup.
declare -A declared
for c in "${declared_classes[@]}"; do
    declared["$c"]=1
done

# Collect tokens used in scanned files. We accept tokens that are clearly
# inside a Javadoc/comment context (lines starting with whitespace+'*' or
# inside `{@link ...}` / `{@code ...}`) AND general prose in .md files.
# Identifier mentions inside import statements or fully-qualified type
# references already point at real classes, so they're benign.
TOKEN_REGEX="\b(${PREFIXES})[A-Z][A-Za-z0-9_]+\b"

declare -A used_tokens
declare -A used_in_files

for f in "${scan_files[@]}"; do
    # Strip noise sources before scanning:
    #   - Java import statements (an unresolved import is its own compile
    #     error; no need to dup-flag).
    #   - Markdown fenced code blocks (``` ... ```). README/tutorial pages
    #     routinely show *example* class names inside fences; flagging
    #     those as "missing" would force every code block to ship a real
    #     class. The gate's purpose is prose claims, not example code.
    if [[ "$f" == *.java ]]; then
        content=$(grep -v -E '^[[:space:]]*import[[:space:]]+' "$f" 2>/dev/null || true)
    elif [[ "$f" == *.md ]]; then
        content=$(awk '
            /^```/ { in_fence = !in_fence; next }
            !in_fence { print }
        ' "$f" 2>/dev/null || true)
    else
        content=$(cat "$f" 2>/dev/null || true)
    fi
    [ -z "$content" ] && continue
    while IFS= read -r tok; do
        [ -z "$tok" ] && continue
        used_tokens["$tok"]=1
        used_in_files["$tok"]+="${f}\n"
    done < <(echo "$content" | grep -o -E "$TOKEN_REGEX" | sort -u)
done

# Check each used token resolves.
fail=0
for tok in "${!used_tokens[@]}"; do
    if [ -n "${declared[$tok]:-}" ]; then
        continue
    fi
    if [ -n "${allowed[$tok]:-}" ]; then
        continue
    fi
    echo "✗ Unresolved backend-class reference: $tok"
    echo "  No class '$tok' declared under modules/ or samples/, and"
    echo "  '$tok' is not in $ALLOWLIST."
    echo "  Referenced from:"
    printf "    %s\n" "${used_in_files[$tok]}" | sed '/^[[:space:]]*$/d' | sort -u
    fail=1
done

if [ "$fail" -ne 0 ]; then
    echo ""
    echo "validate-backend-class-refs.sh: failures above."
    echo ""
    echo "Fix options:"
    echo "  (a) implement the missing class under modules/<module>/src/main/java/"
    echo "  (b) remove the false reference from the Javadoc / doc"
    echo "  (c) if the reference is to a third-party library class, add it to"
    echo "      $ALLOWLIST with a brief comment naming the source library"
    exit 1
fi

echo "validate-backend-class-refs.sh: OK (${#used_tokens[@]} tokens checked, ${#declared_classes[@]} classes indexed)"

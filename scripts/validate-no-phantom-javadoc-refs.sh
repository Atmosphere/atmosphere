#!/usr/bin/env bash
#
# Validate that CamelCase class names referenced in Javadoc {@code Xxx} /
# {@link Xxx} tags resolve to a class declared in the reactor — or are
# allowlisted as external (JDK / Spring / third-party) types.
#
# Drift caught: Javadoc that names a class as if it exists ("a sibling
# {@code GatedToolDispatcher} introduced in Phase 2 ...") when no such class
# is ever implemented — a plan written as documentation. javac/doclint never
# resolves {@code}, so the compiler stays silent.
#
# Scope: {@code Xxx} / {@link [pkg.]Xxx[#member]} in *.java where Xxx is a
# CamelCase identifier ending in a common type suffix (see SUFFIX). Each must
# either (1) declare a class/interface/enum/record with that name under
# modules|samples/**/src, or (2) appear in
# .harness/phantom-javadoc-allowlist.txt (external types).
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$REPO_ROOT"
ALLOWLIST=".harness/phantom-javadoc-allowlist.txt"
SUFFIX='Dispatcher|Handler|Provider|Manager|Service|Store|Registry|Executor|Strategy|Interceptor|Factory|Verifier|Resolver|Validator|Pipeline|Bridge|Adapter|Reaper|Accountant'

mapfile -t scan_files < <(git ls-files 'modules/**/src/**/*.java' 'samples/**/src/**/*.java' ':!**/target/**')
mapfile -t declared < <(
  git grep -h -E '(class|interface|enum|record)[[:space:]]+([A-Z][A-Za-z0-9_]*)' \
    -- 'modules/**/src/**/*.java' 'samples/**/src/**/*.java' \
    | sed -E 's/.*(class|interface|enum|record)[[:space:]]+([A-Z][A-Za-z0-9_]*).*/\2/' | sort -u)
declare -A is_declared; for c in "${declared[@]}"; do is_declared["$c"]=1; done
declare -A allowed
[ -f "$ALLOWLIST" ] && while IFS= read -r l; do l="${l%%#*}"; l="${l//[[:space:]]/}"; [ -n "$l" ] && allowed["$l"]=1; done < "$ALLOWLIST"

declare -A used used_in
for f in "${scan_files[@]}"; do
  while IFS= read -r tok; do
    [ -z "$tok" ] && continue
    used["$tok"]=1; used_in["$tok"]+="$f "
  done < <(grep -oE "\{@(code|link) [A-Za-z_][A-Za-z0-9_.]*(#[A-Za-z0-9_]+)?\}" "$f" 2>/dev/null \
            | sed -E 's/\{@(code|link) //; s/\}//; s/#.*//; s/.*\.//' \
            | grep -E "^[A-Z][A-Za-z0-9_]*(${SUFFIX})$" | sort -u)
done

fail=0
for tok in "${!used[@]}"; do
  [ -n "${is_declared[$tok]:-}" ] && continue
  [ -n "${allowed[$tok]:-}" ] && continue
  echo "✗ Phantom Javadoc class ref: {@code/@link $tok} — no such class, not allowlisted"
  printf "      %s\n" ${used_in[$tok]} | tr ' ' '\n' | sort -u | sed '/^$/d' | head -4
  fail=1
done
[ "$fail" -ne 0 ] && { echo; echo "Fix: (a) implement the class, (b) remove the false ref, or (c) if external, add to $ALLOWLIST"; exit 1; }
echo "validate-no-phantom-javadoc-refs.sh: OK (${#used[@]} class refs checked, ${#declared[@]} classes indexed)"

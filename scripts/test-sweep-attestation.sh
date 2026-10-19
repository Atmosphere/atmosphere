#!/usr/bin/env bash
# Copyright 2008-2026 Async-IO.org
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
#
# ---------------------------------------------------------------------------
# Proves scripts/validate-sweep-attestation.sh bites.
#
# Each case builds a throwaway git repository, records a sweep attestation at
# one commit, adds the commits the case needs, and runs the REAL gate. Every
# case asserts the expected message, never merely the exit code, so a gate
# that dies for an unrelated reason cannot score a pass.
#
# Usage: scripts/test-sweep-attestation.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$ROOT/scripts/validate-sweep-attestation.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
failures=0

new_repo() {
    local dir="$WORK/$1"
    mkdir -p "$dir"
    git -C "$dir" init -q -b main
    git -C "$dir" config user.email gate@example.org
    git -C "$dir" config user.name gate
    git -C "$dir" config commit.gpgsign false
    mkdir -p "$dir/modules/core/src/main/java" "$dir/modules/core/src/test/java" "$dir/.harness" "$dir/docs"
    echo "class A {}" > "$dir/modules/core/src/main/java/A.java"
    echo "<project/>" > "$dir/pom.xml"
    commit "$dir" "initial"
    echo "$dir"
}

commit() {
    git -C "$1" add -A
    git -C "$1" commit -q -m "$2"
}

attest() {
    local dir="$1" sha="$2" verdict="${3:-clean}"
    printf '{"sha":"%s","verdict":"%s","version":"9.9.9"}\n' "$sha" "$verdict" > "$dir/.harness/sweep-attestation.json"
    commit "$dir" "chore: record sweep"
}

change() {
    local dir="$1" path="$2"
    mkdir -p "$dir/$(dirname "$path")"
    echo "// $RANDOM" >> "$dir/$path"
    commit "$dir" "change $path"
}

run_gate() {
    (cd "$1" && "$GATE" 2>&1)
}

expect() {
    local name="$1" want_rc="$2" want_text="$3" out rc
    out=$(run_gate "$4"); rc=$?
    if [ "$rc" -eq "$want_rc" ] && printf '%s' "$out" | grep -qF -- "$want_text"; then
        pass=$((pass + 1)); echo "PASS  $name"
    else
        failures=$((failures + 1))
        echo "FAIL  $name: wanted rc=$want_rc and '$want_text', got rc=$rc:"
        printf '%s\n' "$out" | sed 's/^/        /'
    fi
}

# 1. No attestation at all.
r=$(new_repo missing)
expect "missing attestation fails" 1 "no sweep attestation" "$r"

# 2. Swept at HEAD's parent, then only the attestation commit: covered.
r=$(new_repo covered)
attest "$r" "$(git -C "$r" rev-parse HEAD)"
expect "attestation with no later change passes" 0 "sweep gate: OK" "$r"

# 3. Docs, Markdown and tests after the sweep do not need a new sweep.
r=$(new_repo docs-only)
attest "$r" "$(git -C "$r" rev-parse HEAD)"
change "$r" docs/guide.txt
change "$r" README.md
change "$r" modules/core/src/test/java/ATest.java
change "$r" .github/workflows/ci.yml
expect "docs, Markdown, tests and CI changes pass" 0 "sweep gate: OK" "$r"

# 4. A framework main source after the sweep fails and names the file.
r=$(new_repo main-source)
attest "$r" "$(git -C "$r" rev-parse HEAD)"
change "$r" modules/core/src/main/java/A.java
expect "framework main source fails" 1 "modules/core/src/main/java/A.java" "$r"

# 5. The Console frontend, atmosphere.js, a sample and the CLI all count.
for p in modules/spring-boot-starter/frontend/src/App.vue atmosphere.js/src/index.ts \
         samples/demo/src/main/resources/application.yml cli/atmosphere; do
    r=$(new_repo "surface-$(echo "$p" | tr '/.' '__')")
    attest "$r" "$(git -C "$r" rev-parse HEAD)"
    change "$r" "$p"
    expect "user-facing $p fails" 1 "$p" "$r"
done

# 6. A dependency version (any pom.xml) counts.
r=$(new_repo pom)
attest "$r" "$(git -C "$r" rev-parse HEAD)"
change "$r" modules/core/pom.xml
expect "pom.xml change fails" 1 "modules/core/pom.xml" "$r"

# 7. A swept commit that is not an ancestor of HEAD (another branch) fails.
r=$(new_repo not-ancestor)
git -C "$r" checkout -q -b side
change "$r" modules/core/src/main/java/A.java
side=$(git -C "$r" rev-parse HEAD)
git -C "$r" checkout -q main
attest "$r" "$side"
expect "non-ancestor swept commit fails" 1 "is not an ancestor" "$r"

# 8. A short SHA and a non-clean verdict are refused.
r=$(new_repo short-sha)
attest "$r" "$(git -C "$r" rev-parse --short HEAD)"
expect "short sha fails" 1 "full 40-hex commit" "$r"
r=$(new_repo not-clean)
attest "$r" "$(git -C "$r" rev-parse HEAD)" "findings-open"
expect "verdict other than clean fails" 1 "not 'clean'" "$r"

# 9. A SHA that is not in the history at all fails.
r=$(new_repo unknown-sha)
attest "$r" "0123456789abcdef0123456789abcdef01234567"
expect "unknown sha fails" 1 "not in this clone's history" "$r"

echo
echo "$pass passed, $failures failed"
[ "$failures" -eq 0 ]

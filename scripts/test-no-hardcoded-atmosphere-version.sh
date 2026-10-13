#!/usr/bin/env bash
#
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
# Proves scripts/validate-no-hardcoded-atmosphere-version.sh bites: each case
# builds a throwaway git repository carrying the real validation-patterns.toml,
# injects one violation (or one legitimate near-miss), and asserts the exit code
# AND the file:line the gate names — a non-zero exit alone could come from a
# different failure. Also pins validate-doc-thirdparty-versions.sh failing
# loudly on an unreadable pom.xml instead of falling back to a stale version.
#
# The fixture versions (7.7.7-SNAPSHOT, 7.7.6, 9.9.9) are deliberately not real
# Atmosphere versions, so this file never trips the gate it tests.

set -uo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
GATE="$REPO/scripts/validate-no-hardcoded-atmosphere-version.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASSED=0
FAILED=0

# make_repo <dir>: a minimal repo with every version source the gate reads.
make_repo() {
    local d="$1"
    mkdir -p "$d/scripts" "$d/cli" "$d/atmosphere.js/src" "$d/atmosphere.js/scripts" "$d/.github/workflows" "$d/docs"
    cp "$REPO/scripts/validation-patterns.toml" "$d/scripts/"
    printf '<project>\n  <artifactId>atmosphere-project</artifactId>\n  <version>7.7.7-SNAPSHOT</version>\n</project>\n' > "$d/pom.xml"
    printf '#!/usr/bin/env bash\nVERSION="7.7.6"\n' > "$d/cli/atmosphere"
    printf '#!/usr/bin/env bash\nVERSION="7.7.6"\n' > "$d/cli/install.sh"
    printf '{\n  "name": "atmosphere.js",\n  "version": "9.9.9"\n}\n' > "$d/atmosphere.js/package.json"
    printf 'export const VERSION: string = __ATMOSPHERE_VERSION__;\n' > "$d/atmosphere.js/src/version.ts"
    printf '#!/usr/bin/env bash\nV=$(sed -n 1p pom.xml)\n' > "$d/scripts/release.sh"
    printf 'name: r\non: push\n' > "$d/.github/workflows/r.yml"
    git -C "$d" init -q
    git -C "$d" add -A
}

# check <name> <expected-exit> <expected-output-substring-or-empty> <setup-fn>
check() {
    local name="$1" want_rc="$2" want_out="$3" setup="$4"
    local d="$TMP/$name"
    make_repo "$d"
    (cd "$d" && "$setup")
    git -C "$d" add -A
    local out rc
    out=$("$GATE" "$d" 2>&1); rc=$?
    if [ "$rc" -eq "$want_rc" ] && { [ -z "$want_out" ] || printf '%s' "$out" | grep -qF -- "$want_out"; }; then
        PASSED=$((PASSED + 1)); echo "  ok    $name"
    else
        FAILED=$((FAILED + 1))
        echo "  FAIL  $name: expected exit $want_rc with '${want_out}', got exit $rc:"
        printf '%s\n' "$out" | sed 's/^/          /'
    fi
}

s_clean()        { :; }
s_script_rel()   { printf '#!/usr/bin/env bash\nVERSION=7.7.7\n' > scripts/release.sh; }
s_workflow_snap(){ printf 'name: r\non: push\nenv:\n  V: 7.7.7-SNAPSHOT\n' > .github/workflows/r.yml; }
s_last_released(){ printf 'const tag = "atmosphere-7.7.6";\n' > atmosphere.js/scripts/tag.mjs; }
s_js_version()   { printf '// sent as js-v9.9.9\nexport const X = 1;\n' > atmosphere.js/src/x.ts; }
s_near_misses()  { printf 'a=17.7.7\nb=7.7.70\nc=1.7.7.7\nd=99.9.9\n' > scripts/near.sh; }
s_fixture()      { printf 'make_jar 7.7.7-SNAPSHOT\n' > scripts/test-fixture.sh; }
s_out_of_scope() { printf '<version>7.7.7</version>\n' > docs/guide.md
                   printf 'VERSION="7.7.7"\n' > cli/install.sh; }
s_untracked()    { :; }
s_bad_pom()      { printf '<project><version>7.7.7</version></project>\n' > pom.xml; }
s_no_cli()       { printf '#!/usr/bin/env bash\necho no version line\n' > cli/atmosphere; }
s_no_scope()     { python3 - <<'PY'
import re
p = "scripts/validation-patterns.toml"
s = open(p).read()
s = re.sub(r"(?ms)^scan_paths = \[.*?\]\n", "", s)
open(p, "w").write(s)
PY
}

echo "validate-no-hardcoded-atmosphere-version.sh"
check clean            0 ""                                   s_clean
check script-release   1 "scripts/release.sh:2: [7.7.7]"      s_script_rel
check workflow-snapshot 1 ".github/workflows/r.yml:4: [7.7.7-SNAPSHOT]" s_workflow_snap
check last-released    1 "atmosphere.js/scripts/tag.mjs:1: [7.7.6]" s_last_released
check js-version       1 "atmosphere.js/src/x.ts:1: [9.9.9]"  s_js_version
check near-misses      0 ""                                   s_near_misses
check test-fixture     0 ""                                   s_fixture
check out-of-scope     0 ""                                   s_out_of_scope
check bad-pom          2 "cannot read the atmosphere-project" s_bad_pom
check no-cli-version   2 "cli/atmosphere has no VERSION"      s_no_cli
check no-scan-paths    2 "scan_paths is empty or missing"     s_no_scope

# An untracked file is not the gate's business (pre-commit reads the index).
d="$TMP/untracked"; make_repo "$d"
printf 'VERSION=7.7.7\n' > "$d/scripts/scratch.sh"
if "$GATE" "$d" >/dev/null 2>&1; then
    PASSED=$((PASSED + 1)); echo "  ok    untracked"
else
    FAILED=$((FAILED + 1)); echo "  FAIL  untracked: an unindexed file failed the gate"
fi

echo "validate-doc-thirdparty-versions.sh"
# It must refuse to guess the version band when pom.xml has no project version.
d="$TMP/thirdparty"; make_repo "$d"
cp "$REPO/scripts/validate-doc-thirdparty-versions.sh" "$d/scripts/"
printf '<project><artifactId>other</artifactId></project>\n' > "$d/pom.xml"
out=$("$d/scripts/validate-doc-thirdparty-versions.sh" 2>&1); rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "refusing to guess"; then
    PASSED=$((PASSED + 1)); echo "  ok    thirdparty-unreadable-pom"
else
    FAILED=$((FAILED + 1)); echo "  FAIL  thirdparty-unreadable-pom: exit $rc: $out"
fi

echo "passed: $PASSED  failed: $FAILED"
[ "$FAILED" -eq 0 ]

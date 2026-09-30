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
# Proves scripts/validate-playwright-project-coverage.sh bites.
#
# Each case copies the real inputs (playwright.config.ts with its e2e/ spec
# tree, the workflows, the exclusion list) into a scratch directory, injects
# exactly one violation, and
# asserts that the gate exits 1 WITH the message of the check under test — a
# non-zero exit from some other check would prove nothing about this one. The
# real tree must pass, and two cases pin that comment-only mentions count for
# nothing (a project named only in a YAML or // comment neither runs nor exists).

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATE="$ROOT/scripts/validate-playwright-project-coverage.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passed=0; failed=0

# Fresh copy of the real inputs for one case.
setup() {
    rm -rf "$TMP/case"; mkdir -p "$TMP/case/workflows"
    cp "$ROOT/modules/integration-tests/playwright.config.ts" "$TMP/case/playwright.config.ts"
    # The config's testDir is ./e2e; the gate lists the spec files found there
    # with Playwright, which resolves from the config's own node_modules.
    cp -R "$ROOT/modules/integration-tests/e2e" "$TMP/case/e2e"
    cp "$ROOT/modules/integration-tests/package.json" "$ROOT/modules/integration-tests/tsconfig.json" "$TMP/case/"
    ln -s "$ROOT/modules/integration-tests/node_modules" "$TMP/case/node_modules"
    cp "$ROOT"/.github/workflows/*.yml "$TMP/case/workflows/"
    cp "$ROOT/.harness/playwright-project-exclusions.txt" "$TMP/case/exclusions.txt"
}

run_gate() {
    PW_CONFIG="$TMP/case/playwright.config.ts" WORKFLOWS_DIR="$TMP/case/workflows" \
        EXCLUSIONS="$TMP/case/exclusions.txt" "$@" "$GATE" > "$TMP/out" 2>&1
}

# Insert a project entry right after `projects: [` in the scratch config.
add_project() {
    python3 - "$TMP/case/playwright.config.ts" "$1" <<'PY'
import sys
path, entry = sys.argv[1], sys.argv[2]
s = open(path).read()
i = s.index('projects: [') + len('projects: [')
open(path, 'w').write(s[:i] + '\n' + entry + '\n' + s[i:])
PY
}

# Replace a literal string in a scratch file — python, so regex
# metacharacters in the needle need no escaping.
replace_in() {
    python3 - "$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
if old not in s:
    sys.exit(f"replace_in: {old!r} not found in {path}")
open(path, 'w').write(s.replace(old, new, 1))
PY
}

# Write a spec file with one test; extra args are the test's title and an
# optional details object (e.g. "{ tag: '@flaky' }").
write_spec() {
    local path="$1" title="${2:-zzz orphan}" details="${3:-}"
    mkdir -p "$(dirname "$path")"
    printf "import { test } from '@playwright/test';\ntest('%s', %s() => {});\n" \
        "$title" "${details:+$details, }" > "$path"
}

# Add project $1 to the dentist-agent matrix group of the scratch e2e.yml.
run_in_workflow() {
    replace_in "$TMP/case/workflows/e2e.yml" '"dentist-agent,a2a-discovery,' "\"dentist-agent,$1,a2a-discovery,"
}

# expect <case-name> <expected-exit> <expected-message-fragment> [env ...]
expect() {
    local name="$1" want_code="$2" want_msg="$3"; shift 3
    run_gate env "$@"
    local code=$?
    if [ "$code" -eq "$want_code" ] && grep -qF -- "$want_msg" "$TMP/out"; then
        echo "  PASS  $name"
        passed=$((passed + 1))
    else
        echo "  FAIL  $name (exit $code, want $want_code; want message: $want_msg)"
        sed 's/^/        /' "$TMP/out"
        failed=$((failed + 1))
    fi
}

echo "validate-playwright-project-coverage.sh self-test"

setup
expect "the real tree passes" 0 "PASS —"

setup
add_project "    { name: 'zzz-unmapped', testMatch: /zzz\.spec\.ts/ },"
expect "a project no workflow runs fails" 1 "project 'zzz-unmapped' is declared"

setup
add_project "    { name: 'zzz-unmapped', testMatch: /zzz\.spec\.ts/ },"
printf '        #  projects: "zzz-unmapped"\n# npx playwright test --project=zzz-unmapped\n' \
    >> "$TMP/case/workflows/e2e.yml"
expect "a project named only in a YAML comment still fails" 1 "project 'zzz-unmapped' is declared"

setup
add_project "    { name: 'zzz-unmapped', testMatch: /zzz\.spec\.ts/ },"
printf 'zzz-unmapped | jfarcand | 2099-01-01 | carnet#53 | self-test exclusion\n' \
    >> "$TMP/case/exclusions.txt"
expect "a valid exclusion accounts for a project" 0 "PASS —"

setup
sed -i.bak 's/"dentist-agent,a2a-discovery,/"dentist-agent,zzz-typo,a2a-discovery,/' "$TMP/case/workflows/e2e.yml"
expect "a workflow naming an undeclared project fails" 1 "references project 'zzz-typo'"

setup
add_project "    // { name: 'zzz-ghost', testMatch: /ghost\.spec\.ts/ },"
printf '      - run: npx playwright test --project=zzz-ghost\n' >> "$TMP/case/workflows/e2e.yml"
expect "a //-commented config project is not declared" 1 "references project 'zzz-ghost'"

setup
expect "an expired exclusion fails" 1 "EXPIRED on" TODAY=2099-12-31

setup
printf 'zzz-bad | jfarcand | 2099-01-01 | carnet#53\n' >> "$TMP/case/exclusions.txt"
expect "an exclusion missing a field fails" 1 "malformed exclusion"

setup
printf 'zzz-bad | jfarcand | next-year | carnet#53 | reason\n' >> "$TMP/case/exclusions.txt"
expect "an exclusion with a non-ISO expiry fails" 1 "must be YYYY-MM-DD"

setup
printf 'chat | jfarcand | 2099-01-01 | carnet#53 | stale\n' >> "$TMP/case/exclusions.txt"
expect "excluding a project a workflow already runs fails" 1 "exclusion 'chat' is already run by"

setup
printf 'zzz-gone | jfarcand | 2099-01-01 | carnet#53 | stale\n' >> "$TMP/case/exclusions.txt"
expect "excluding an undeclared project fails" 1 "exclusion 'zzz-gone' names no project"

setup
grep -v '^firefox ' "$TMP/case/exclusions.txt" > "$TMP/case/x" && mv "$TMP/case/x" "$TMP/case/exclusions.txt"
expect "dropping an exclusion uncovers its project" 1 "project 'firefox' is declared"

setup
printf "export default { testDir: './e2e' };\n" > "$TMP/case/playwright.config.ts"
expect "a config without named projects fails instead of passing vacuously" 1 "a project with no name"

# --- the declared set is Playwright's, not a text scrape ---

setup
add_project '    { name: "zzz-dq", testMatch: /\/chat\.spec\.ts/ },'
expect "a double-quoted project name no workflow runs fails" 1 "project 'zzz-dq' is declared"

setup
add_project "    ...['zzz-helper'].map((name) => ({ name, testMatch: /\/chat\.spec\.ts/ })),"
expect "a project built by a helper that no workflow runs fails" 1 "project 'zzz-helper' is declared"

# --- spec files: each must run a test in a project a workflow runs ---

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
expect "a spec no project matches fails" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/nested/zzz-deep.spec.ts"
expect "a spec in a subdirectory is walked too" 1 "spec 'nested/zzz-deep.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
add_project "    // { name: 'chat', testMatch: /zzz-orphan\.spec\.ts/ },"
expect "a //-commented testMatch picks up nothing" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/chat\.spec\.ts/,' 'testMatch: /\/(chat|zzz-orphan)\.spec\.ts/,'
expect "the testMatch regex is evaluated (an alternation covers the orphan)" 0 "PASS —"

setup
touch "$TMP/case/e2e/zzz-orphan.spec.ts"
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/chat\.spec\.ts/,' 'testMatch: /\/(chat|zzz-orphan)\.spec\.ts/,'
expect "a matched spec that declares no test fails" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/webtransport-fallback\.spec\.ts/,' 'testMatch: /\/zzz-nothing\.spec\.ts/,'
expect "a spec run only by excluded projects fails" 1 "spec 'webtransport-fallback.spec.ts' runs tests only in project(s) firefox,webkit, which no workflow runs"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
add_project "    { name: 'zzz-glob', testMatch: '**/zzz-orphan.spec.ts' },"
printf 'zzz-glob | jfarcand | 2099-01-01 | carnet#54 | self-test\n' >> "$TMP/case/exclusions.txt"
expect "a string-glob testMatch is evaluated, not refused" 1 "spec 'zzz-orphan.spec.ts' runs tests only in project(s) zzz-glob"

# --- title filters: a spec whose every test is filtered out runs nothing ---

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
add_project "    { name: 'zzz-grep', testMatch: /\/zzz-orphan\.spec\.ts/, grep: /@never-matches/ },"
run_in_workflow zzz-grep
expect "a project whose grep matches none of the spec's tests fails" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
add_project "    { name: 'zzz-grep', testMatch: /\/zzz-orphan\.spec\.ts/ },"
run_in_workflow zzz-grep
expect "the same project without the grep covers the spec (control)" 0 "PASS —"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
add_project "    { name: 'zzz-grep', testMatch: /\/zzz-orphan\.spec\.ts/, grepInvert: /zzz orphan/ },"
run_in_workflow zzz-grep
expect "a project whose grepInvert drops every test of the spec fails" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts" 'zzz orphan @flaky'
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/chat\.spec\.ts/,' 'testMatch: /\/(chat|zzz-orphan)\.spec\.ts/,'
expect "a spec whose only test is titled @flaky fails (the leg sets INCLUDE_FLAKY=false)" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts" 'zzz orphan' "{ tag: '@flaky' }"
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/chat\.spec\.ts/,' 'testMatch: /\/(chat|zzz-orphan)\.spec\.ts/,'
expect "a spec whose only test carries a @flaky tag fails" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts" 'zzz orphan @flaky'
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/chat\.spec\.ts/,' 'testMatch: /\/(chat|zzz-orphan)\.spec\.ts/,'
expect "the caller's INCLUDE_FLAKY does not leak into the listing" 1 "spec 'zzz-orphan.spec.ts' runs no test in any project" INCLUDE_FLAKY=true

setup
expect "the caller's CI/SMOKE_ONLY do not leak into the listing" 0 "PASS —" CI=true SMOKE_ONLY=true

setup
printf "import { test } from '@playwright/test';\nthrow new Error('zzz load failure');\n" > "$TMP/case/e2e/zzz-broken.spec.ts"
replace_in "$TMP/case/playwright.config.ts" 'testMatch: /\/chat\.spec\.ts/,' 'testMatch: /\/(chat|zzz-broken)\.spec\.ts/,'
expect "a spec that fails to load fails the gate" 1 "zzz load failure"

# --- spec exclusions ---

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
printf 'zzz-orphan.spec.ts | jfarcand | 2099-01-01 | carnet#54 | self-test exclusion\n' >> "$TMP/case/exclusions.txt"
expect "a valid spec exclusion accounts for an orphan spec" 0 "one of 1 excluded"

setup
write_spec "$TMP/case/e2e/zzz-orphan.spec.ts"
printf 'zzz-orphan.spec.ts | jfarcand | 2020-01-01 | carnet#54 | self-test exclusion\n' >> "$TMP/case/exclusions.txt"
expect "an expired spec exclusion fails" 1 "exclusion 'zzz-orphan.spec.ts' EXPIRED on 2020-01-01"

setup
printf 'chat.spec.ts | jfarcand | 2099-01-01 | carnet#54 | stale\n' >> "$TMP/case/exclusions.txt"
expect "excluding a spec a workflow project already runs fails" 1 "exclusion 'chat.spec.ts' is already run by project(s) chat"

setup
printf 'zzz-gone.spec.ts | jfarcand | 2099-01-01 | carnet#54 | stale\n' >> "$TMP/case/exclusions.txt"
expect "excluding a spec file that does not exist fails" 1 "exclusion 'zzz-gone.spec.ts' names no spec file"

echo ""
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]

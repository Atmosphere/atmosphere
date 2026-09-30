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
# Each case copies the real inputs (playwright.config.ts, the workflows, the
# exclusion list) into a scratch directory, injects exactly one violation, and
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
printf 'export default {};\n' > "$TMP/case/playwright.config.ts"
expect "an unparseable config fails instead of passing vacuously" 1 "no projects parsed"

echo ""
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]

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
# Every Playwright project must run somewhere, or say why it does not.
#
# A project declared in modules/integration-tests/playwright.config.ts that no
# workflow selects is a spec that reads as coverage and never executes. Until
# 2026-09-30 nine projects sat that way, and three of them were dead: their
# handlers' onStateChange was a no-op, so no frame ever reached the socket and
# every test timed out with "Events: []" — unnoticed, because nothing ran them.
#
# A project that runs is still not enough: a spec FILE that no project's
# testMatch picks up runs nowhere either, and the project check cannot see it.
# Until 2026-09-30 five specs (classroom-resilience, history-sync,
# offline-queue-browser, optimistic-updates, presence-count) sat that way —
# all five were stale against the UI their samples serve, and presence-count
# hid a real bug (a dropped connection never announced its leave on the wire).
#
# The gate fails when:
#   1. a config project is referenced by no workflow and not excluded;
#   2. a workflow references a project the config does not declare (Playwright
#      would abort the whole leg with "Project(s) ... not found");
#   3. an exclusion entry is malformed (needs owner, YYYY-MM-DD expiry, issue,
#      reason), expired, names an unknown project or spec, or names a project
#      or spec that a workflow already runs (a stale exclusion hides nothing
#      but misleads);
#   4. an e2e/**/*.spec.ts file has no test that a workflow-run project runs,
#      and is not excluded. The answer is Playwright's own: the gate reads
#      `playwright test --list` through scripts/lib/playwright_spec_projects.mjs,
#      under the per-push e2e leg's environment (LLM_MODE=fake,
#      INCLUDE_FLAKY=false), so testMatch/testIgnore AND grep/grepInvert —
#      per project and top-level — are applied exactly as the leg applies
#      them. A spec whose tests are all tagged @flaky, all statically skipped
#      (test.skip, test.describe.skip, test.fixme, quarantined() outside the
#      quarantine lane), or that only a project with a non-matching grep picks
#      up, runs nothing and fails here. A spec run only by an excluded project
#      (the opt-in firefox/webkit ones) does not count as running.
#
# The declared project set is the one Playwright evaluates, not a text scrape
# of the config: a double-quoted name or a project built by a helper is
# declared all the same, and must run or be excluded.
#
# A workflow "references" a project through a `projects: "a,b"` matrix string
# or a `--project=<name>` / `--project <name>` flag. YAML comment lines are
# stripped first: a project named only in a comment does not run.
#
# Inputs are overridable for the self-test (scripts/test-playwright-project-coverage.sh):
#   PW_CONFIG, WORKFLOWS_DIR, EXCLUSIONS, TODAY (YYYY-MM-DD). Spec files are read
#   from the config's testDir, next to PW_CONFIG, and @playwright/test must
#   resolve from PW_CONFIG (`npm ci` in modules/integration-tests).
#
# Run from anywhere. Exits 0 when every project is covered, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PW_CONFIG="${PW_CONFIG:-$ROOT/modules/integration-tests/playwright.config.ts}"
WORKFLOWS_DIR="${WORKFLOWS_DIR:-$ROOT/.github/workflows}"
EXCLUSIONS="${EXCLUSIONS:-$ROOT/.harness/playwright-project-exclusions.txt}"
TODAY="${TODAY:-$(date +%Y-%m-%d)}"

ME="validate-playwright-project-coverage.sh"
SPEC_MAPPER="$ROOT/scripts/lib/playwright_spec_projects.mjs"

for f in "$PW_CONFIG" "$EXCLUSIONS"; do
    [ -f "$f" ] || { echo "$ME: $f not found" >&2; exit 1; }
done
[ -d "$WORKFLOWS_DIR" ] || { echo "$ME: $WORKFLOWS_DIR not found" >&2; exit 1; }
# Without node (and the config's node_modules) nothing can be evaluated; refuse
# rather than skip.
command -v node > /dev/null 2>&1 || { echo "$ME: node not found — needed to list $PW_CONFIG" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- 1. Projects and spec files, as Playwright lists them --------------------
# "project\t<name>" per declared project, "spec\t<path relative to testDir>\t
# <project,...>" per spec file — the projects that run at least one of its tests.
if ! node "$SPEC_MAPPER" "$PW_CONFIG" > "$TMP/listing" 2> "$TMP/listing.err"; then
    echo "$ME: could not list $PW_CONFIG with Playwright:" >&2
    sed 's/^/  /' "$TMP/listing.err" >&2
    exit 1
fi
awk -F'\t' '$1 == "project" { print $2 }' "$TMP/listing" | sort -u > "$TMP/declared"
awk -F'\t' -v OFS='\t' '$1 == "spec" { print $2, $3 }' "$TMP/listing" > "$TMP/specs"

if [ ! -s "$TMP/declared" ]; then
    # An empty set would make every later check pass vacuously.
    echo "$ME: no projects listed from $PW_CONFIG — the mapper no longer matches the config" >&2
    exit 1
fi

# --- 2. Projects referenced by workflows ------------------------------------
: > "$TMP/referenced"
for wf in "$WORKFLOWS_DIR"/*.yml "$WORKFLOWS_DIR"/*.yaml; do
    [ -f "$wf" ] || continue
    code="$(sed -E '/^[[:space:]]*#/d' "$wf")"
    { grep -oE 'projects:[[:space:]]*"[^"]*"' <<<"$code" \
        | sed -E 's/^projects:[[:space:]]*"//; s/"$//' | tr ',' '\n' || true
      grep -oE -- '--project[= ][A-Za-z0-9._-]+' <<<"$code" \
        | sed -E 's/^--project[= ]//' || true
    } | tr -d ' ' | sed '/^$/d' | while read -r p; do
        printf '%s\t%s\n' "$p" "${wf#"$ROOT"/}"
    done >> "$TMP/referenced"
done
cut -f1 "$TMP/referenced" | sort -u > "$TMP/referenced.names"

if [ ! -s "$TMP/referenced.names" ]; then
    echo "$ME: no project references parsed from $WORKFLOWS_DIR — the parser no longer matches the workflows" >&2
    exit 1
fi

# Workflows (comma-separated) that reference project $1 — exact name match.
runs_in() {
    awk -F'\t' -v p="$1" '$1 == p { print $2 }' "$TMP/referenced" | sort -u | paste -sd, -
}

trim() { sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' <<<"$1"; }

# --- 3. Spec files and the projects that run their tests --------------------
cut -f1 "$TMP/specs" | sort -u > "$TMP/specs.names"
if [ ! -s "$TMP/specs.names" ]; then
    echo "$ME: no spec files mapped from $PW_CONFIG — the mapper no longer matches the config" >&2
    exit 1
fi

# Projects (comma-separated) that a workflow runs AND that run a test of spec $1.
spec_runs_in() {
    awk -F'\t' -v s="$1" '$1 == s { print $2 }' "$TMP/specs" | tr ',' '\n' | sed '/^$/d' \
        | grep -xF -f "$TMP/referenced.names" | paste -sd, - || true
}

# --- 4. Exclusion list ------------------------------------------------------
fail=0
: > "$TMP/excluded.names"
: > "$TMP/excluded.specs"
lineno=0
while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    case "$line" in ''|'#'*) continue ;; esac
    IFS='|' read -r name owner expires issue reason extra <<<"$line"
    name="$(trim "$name")"; owner="$(trim "$owner")"; expires="$(trim "$expires")"
    issue="$(trim "$issue")"; reason="$(trim "$reason")"
    where="${EXCLUSIONS#"$ROOT"/}:$lineno"
    if [ -z "$name" ] || [ -z "$owner" ] || [ -z "$issue" ] || [ -z "$reason" ] || [ -n "${extra:-}" ]; then
        echo "$ME: $where malformed exclusion — expected '<project or spec> | <owner> | <YYYY-MM-DD> | <issue> | <reason>'" >&2
        fail=1; continue
    fi
    if ! [[ "$expires" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
        echo "$ME: $where exclusion '$name' has expiry '$expires' — must be YYYY-MM-DD" >&2
        fail=1; continue
    fi
    if [[ "$expires" < "$TODAY" ]]; then
        echo "$ME: $where exclusion '$name' EXPIRED on $expires — wire it into a workflow or re-justify it with a new expiry" >&2
        fail=1
    fi
    if [[ "$name" == *.spec.ts ]]; then
        # A spec-file exclusion: path relative to the config's testDir.
        if ! grep -qxF "$name" "$TMP/specs.names"; then
            echo "$ME: $where exclusion '$name' names no spec file under the config's testDir — remove the stale entry" >&2
            fail=1
        fi
        runners="$(spec_runs_in "$name")"
        if [ -n "$runners" ]; then
            echo "$ME: $where exclusion '$name' is already run by project(s) $runners — remove the stale entry" >&2
            fail=1
        fi
        echo "$name" >> "$TMP/excluded.specs"
        continue
    fi
    if ! grep -qxF "$name" "$TMP/declared"; then
        echo "$ME: $where exclusion '$name' names no project in ${PW_CONFIG#"$ROOT"/} — remove the stale entry" >&2
        fail=1
    fi
    if grep -qxF "$name" "$TMP/referenced.names"; then
        echo "$ME: $where exclusion '$name' is already run by $(runs_in "$name") — remove the stale entry" >&2
        fail=1
    fi
    echo "$name" >> "$TMP/excluded.names"
done < "$EXCLUSIONS"
sort -u -o "$TMP/excluded.names" "$TMP/excluded.names"
sort -u -o "$TMP/excluded.specs" "$TMP/excluded.specs"

# --- 5. Every declared project runs or is excluded --------------------------
sort -u "$TMP/referenced.names" "$TMP/excluded.names" > "$TMP/accounted"
uncovered="$(comm -23 "$TMP/declared" "$TMP/accounted")"
if [ -n "$uncovered" ]; then
    while read -r p; do
        echo "$ME: project '$p' is declared in ${PW_CONFIG#"$ROOT"/} but no workflow runs it and it is not in ${EXCLUSIONS#"$ROOT"/}" >&2
    done <<<"$uncovered"
    echo "$ME: add it to a matrix group in .github/workflows/e2e.yml, or record why it cannot run in CI (owner, expiry, issue, reason)." >&2
    fail=1
fi

# --- 6. Every workflow reference names a declared project -------------------
unknown="$(comm -13 "$TMP/declared" "$TMP/referenced.names")"
if [ -n "$unknown" ]; then
    while read -r p; do
        echo "$ME: $(runs_in "$p") references project '$p', which ${PW_CONFIG#"$ROOT"/} does not declare" >&2
    done <<<"$unknown"
    fail=1
fi

# --- 7. Every spec file runs a test in a project a workflow runs ------------
spec_fail=0
while read -r spec; do
    grep -qxF "$spec" "$TMP/excluded.specs" && continue
    [ -n "$(spec_runs_in "$spec")" ] && continue
    matched="$(awk -F'\t' -v s="$spec" '$1 == s { print $2 }' "$TMP/specs")"
    if [ -n "$matched" ]; then
        echo "$ME: spec '$spec' runs tests only in project(s) $matched, which no workflow runs" >&2
    else
        echo "$ME: spec '$spec' runs no test in any project of ${PW_CONFIG#"$ROOT"/} — no testMatch picks it up, or grep/grepInvert (under INCLUDE_FLAKY=false) filter out every test it has, or every test it has is statically skipped (test.skip, describe.skip, fixme, quarantined())" >&2
    fi
    spec_fail=1
done < "$TMP/specs.names"
if [ "$spec_fail" -ne 0 ]; then
    echo "$ME: give each such spec a project in a workflow leg with a test that survives its filters, or record why it cannot run in CI (owner, expiry, issue, reason)." >&2
    fail=1
fi

declared_n=$(wc -l < "$TMP/declared" | tr -d ' ')
run_n=$(comm -12 "$TMP/declared" "$TMP/referenced.names" | wc -l | tr -d ' ')
excl_n=$(wc -l < "$TMP/excluded.names" | tr -d ' ')
specs_n=$(wc -l < "$TMP/specs.names" | tr -d ' ')
spec_excl_n=$(wc -l < "$TMP/excluded.specs" | tr -d ' ')

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "$ME: PASS — $declared_n Playwright projects: $run_n run by a workflow, $excl_n excluded with owner/expiry/issue; $specs_n spec files, each run by a workflow project or one of $spec_excl_n excluded."

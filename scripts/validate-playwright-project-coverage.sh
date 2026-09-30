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
# The gate fails when:
#   1. a config project is referenced by no workflow and not excluded;
#   2. a workflow references a project the config does not declare (Playwright
#      would abort the whole leg with "Project(s) ... not found");
#   3. an exclusion entry is malformed (needs owner, YYYY-MM-DD expiry, issue,
#      reason), expired, names an unknown project, or names a project that a
#      workflow already runs (a stale exclusion hides nothing but misleads).
#
# A workflow "references" a project through a `projects: "a,b"` matrix string
# or a `--project=<name>` / `--project <name>` flag. YAML comment lines are
# stripped first: a project named only in a comment does not run.
#
# Inputs are overridable for the self-test (scripts/test-playwright-project-coverage.sh):
#   PW_CONFIG, WORKFLOWS_DIR, EXCLUSIONS, TODAY (YYYY-MM-DD).
#
# Run from anywhere. Exits 0 when every project is covered, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PW_CONFIG="${PW_CONFIG:-$ROOT/modules/integration-tests/playwright.config.ts}"
WORKFLOWS_DIR="${WORKFLOWS_DIR:-$ROOT/.github/workflows}"
EXCLUSIONS="${EXCLUSIONS:-$ROOT/.harness/playwright-project-exclusions.txt}"
TODAY="${TODAY:-$(date +%Y-%m-%d)}"

ME="validate-playwright-project-coverage.sh"

for f in "$PW_CONFIG" "$EXCLUSIONS"; do
    [ -f "$f" ] || { echo "$ME: $f not found" >&2; exit 1; }
done
[ -d "$WORKFLOWS_DIR" ] || { echo "$ME: $WORKFLOWS_DIR not found" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- 1. Projects declared in the config -------------------------------------
# Only `name:` keys after `projects: [` count, and // comments are dropped so a
# commented-out project is not treated as declared.
sed -E 's#^[[:space:]]*//.*$##' "$PW_CONFIG" \
    | awk '/projects:[[:space:]]*\[/ { on = 1 } on' \
    | { grep -oE "name:[[:space:]]*'[^']+'" || true; } \
    | sed -E "s/name:[[:space:]]*'//; s/'$//" \
    | sort -u > "$TMP/declared"

if [ ! -s "$TMP/declared" ]; then
    # An empty set would make every later check pass vacuously.
    echo "$ME: no projects parsed from $PW_CONFIG — the parser no longer matches the config" >&2
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

# --- 3. Exclusion list ------------------------------------------------------
fail=0
: > "$TMP/excluded.names"
lineno=0
while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    case "$line" in ''|'#'*) continue ;; esac
    IFS='|' read -r name owner expires issue reason extra <<<"$line"
    name="$(trim "$name")"; owner="$(trim "$owner")"; expires="$(trim "$expires")"
    issue="$(trim "$issue")"; reason="$(trim "$reason")"
    where="${EXCLUSIONS#"$ROOT"/}:$lineno"
    if [ -z "$name" ] || [ -z "$owner" ] || [ -z "$issue" ] || [ -z "$reason" ] || [ -n "${extra:-}" ]; then
        echo "$ME: $where malformed exclusion — expected '<project> | <owner> | <YYYY-MM-DD> | <issue> | <reason>'" >&2
        fail=1; continue
    fi
    if ! [[ "$expires" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
        echo "$ME: $where exclusion '$name' has expiry '$expires' — must be YYYY-MM-DD" >&2
        fail=1; continue
    fi
    if [[ "$expires" < "$TODAY" ]]; then
        echo "$ME: $where exclusion '$name' EXPIRED on $expires — wire the project into a workflow or re-justify it with a new expiry" >&2
        fail=1
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

# --- 4. Every declared project runs or is excluded --------------------------
sort -u "$TMP/referenced.names" "$TMP/excluded.names" > "$TMP/accounted"
uncovered="$(comm -23 "$TMP/declared" "$TMP/accounted")"
if [ -n "$uncovered" ]; then
    while read -r p; do
        echo "$ME: project '$p' is declared in ${PW_CONFIG#"$ROOT"/} but no workflow runs it and it is not in ${EXCLUSIONS#"$ROOT"/}" >&2
    done <<<"$uncovered"
    echo "$ME: add it to a matrix group in .github/workflows/e2e.yml, or record why it cannot run in CI (owner, expiry, issue, reason)." >&2
    fail=1
fi

# --- 5. Every workflow reference names a declared project -------------------
unknown="$(comm -13 "$TMP/declared" "$TMP/referenced.names")"
if [ -n "$unknown" ]; then
    while read -r p; do
        echo "$ME: $(runs_in "$p") references project '$p', which ${PW_CONFIG#"$ROOT"/} does not declare" >&2
    done <<<"$unknown"
    fail=1
fi

declared_n=$(wc -l < "$TMP/declared" | tr -d ' ')
run_n=$(comm -12 "$TMP/declared" "$TMP/referenced.names" | wc -l | tr -d ' ')
excl_n=$(wc -l < "$TMP/excluded.names" | tr -d ' ')

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "$ME: PASS — $declared_n Playwright projects: $run_n run by a workflow, $excl_n excluded with owner/expiry/issue."

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
# Release gate: the pre-release sample sweep must cover the release.
#
# Why
# ---
# The release tagged at c5801b6325 was cut with the sweep run at 106185abe8, after which
# three user-facing changes landed (Console reload reattach, room prompt echo
# across modules/ai + Console + atmosphere.js, an Expo client fix). Each had
# Playwright specs and green CI, none was driven in a browser or the
# simulator, and the release went out anyway: the sweep skill's "re-test
# after a Console or shared-module change" rule was text nothing enforced.
#
# What it checks
# --------------
# The sweep records the commit it covered in .harness/sweep-attestation.json
# once its verdict is clean (see .claude/skills/release-sample-sweep, Step 5).
# This gate fails unless:
#   1. the attestation exists, names a full 40-hex commit and verdict "clean";
#   2. that commit is an ancestor of HEAD (an arbitrary SHA cannot pass);
#   3. no commit after it changed a user-facing path (BEHAVIOUR_PATHS below):
#      sample code and config, framework main sources, the Console frontend,
#      atmosphere.js sources and dependencies, the CLI, or any pom.xml/bom.
#      Tests, Markdown, CI workflows and scripts do not count.
#
# Consequence worth knowing: the release workflow's own bump commits
# ("release:", "prepare for next development iteration", "chore(js): prepare
# next development version") touch every pom.xml and atmosphere.js/package.json,
# so an attestation never carries over from one release to the next. That is
# intended: every release needs its own sweep.
#
# Usage:
#   scripts/validate-sweep-attestation.sh [--attestation FILE] [--head REF]
# Exit codes: 0 covered · 1 not covered or malformed (reason on stderr)
# ---------------------------------------------------------------------------
set -euo pipefail

ATTESTATION=".harness/sweep-attestation.json"
HEAD_REF="HEAD"
while [ $# -gt 0 ]; do
    case "$1" in
        --attestation) ATTESTATION="$2"; shift 2 ;;
        --head) HEAD_REF="$2"; shift 2 ;;
        -h|--help) sed -n '16,48p' "$0"; exit 0 ;;
        *) echo "error: unknown argument: $1" >&2; exit 1 ;;
    esac
done

BEHAVIOUR_PATHS=(
    ':(glob)samples/**'
    ':(glob)modules/**/src/main/**'
    ':(glob)modules/spring-boot-starter/frontend/**'
    ':(glob)atmosphere.js/src/**'
    'atmosphere.js/package.json'
    ':(glob)cli/**'
    ':(glob)bom/**'
    ':(glob)**/pom.xml'
    ':(glob,exclude)**/*.md'
    ':(glob,exclude)**/src/test/**'
    ':(glob,exclude)**/*.test.ts'
    ':(glob,exclude)**/*.spec.ts'
)

fail() { echo "::error::sweep gate: $*" >&2; exit 1; }

[ -f "$ATTESTATION" ] || fail "no sweep attestation at $ATTESTATION. Run the release-sample-sweep skill against this commit and record it (Step 5)."

field() { jq -er ".$1 // empty" "$ATTESTATION" 2>/dev/null || true; }
SHA=$(field sha)
VERDICT=$(field verdict)
VERSION=$(field version)

[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "$ATTESTATION: \"sha\" must be a full 40-hex commit, got '${SHA}'"
[ "$VERDICT" = "clean" ] || fail "$ATTESTATION: verdict is '${VERDICT}', not 'clean'. A sweep with open findings does not clear a release."
git cat-file -e "${SHA}^{commit}" 2>/dev/null || fail "swept commit $SHA is not in this clone's history (shallow checkout, or a SHA from elsewhere)"
HEAD_SHA=$(git rev-parse --verify "${HEAD_REF}^{commit}")
git merge-base --is-ancestor "$SHA" "$HEAD_SHA" || fail "swept commit $SHA is not an ancestor of $HEAD_REF ($HEAD_SHA)"

CHANGED=$(git diff --name-only "$SHA" "$HEAD_SHA" -- "${BEHAVIOUR_PATHS[@]}")
if [ -n "$CHANGED" ]; then
    count=$(printf '%s\n' "$CHANGED" | wc -l | tr -d ' ')
    {
        echo "::error::sweep gate: $count user-facing file(s) changed after the swept commit ${SHA:0:10} (sweep of ${VERSION:-unknown}):"
        printf '%s\n' "$CHANGED" | head -40 | sed 's/^/  /'
        [ "$count" -gt 40 ] && echo "  ... and $((count - 40)) more"
        echo "Re-run the sweep (or its Step 4 re-test for these surfaces) and update $ATTESTATION."
        echo "Commits since the sweep:"
        git log --oneline "$SHA..$HEAD_SHA" -- "${BEHAVIOUR_PATHS[@]}" | head -20 | sed 's/^/  /'
    } >&2
    exit 1
fi
echo "sweep gate: OK. $HEAD_REF (${HEAD_SHA:0:10}) is covered by the sweep at ${SHA:0:10} (${VERSION:-unknown}); no user-facing change since."

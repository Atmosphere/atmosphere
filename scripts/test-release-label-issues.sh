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
# Proves scripts/release-label-issues.sh labels exactly the issues a release
# fixed. A throwaway git repository carries release tags, one of them left off
# main the way a release run that lost a race with main leaves it, and commits
# that mention issues every way they are mentioned here. A stub `gh` on PATH
# serves JSON fixtures through the script's own --jq filters, answers 404 for a
# missing resource, fails on request, and records every write. The script runs
# once a release, in the release workflow, so this is the only place its
# filters and its failure handling are exercised before they touch real issues.
#
# The fixture versions (7.7.6 to 7.7.10) are deliberately not real
# Atmosphere versions.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
SCRIPT="$PWD/scripts/release-label-issues.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASSED=0
FAILED=0

check() {
    local name="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "PASS  $name"
        PASSED=$((PASSED + 1))
    else
        echo "FAIL  $name"
        echo "      expected: $expected"
        echo "      actual:   $actual"
        FAILED=$((FAILED + 1))
    fi
}

# ── the repository ───────────────────────────────────────────────────────────
REPO_DIR="$TMP/repo"
mkdir -p "$REPO_DIR"
cd "$REPO_DIR" || exit 1
git init -q
git config user.name test
git config user.email test@example.com
commit() { # commit <date> <message...>
    local date="$1"; shift
    echo "$*" >> log.txt
    git add log.txt
    GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date" git commit -q "${@/#/-m}"
    git rev-parse HEAD
}
OLD=$(commit 2020-01-01T00:00:00Z "fix: older work (#11)")
commit 2020-01-01T00:00:00Z "release: Atmosphere 7.7.6" > /dev/null
git tag atmosphere-7.7.6
commit 2020-02-01T00:00:00Z "fix(x): thing. Fixes #21" "Also closes #22." > /dev/null
CITED=$(commit 2020-02-01T00:00:00Z "feat: y (#23)")
commit 2020-02-01T00:00:00Z "docs: carnet#76, Atmosphere/atmosphere-carnet#5, page#9 and C#7" > /dev/null
commit 2020-02-01T00:00:00Z "fix: several #24,#25 and #99" > /dev/null
commit 2020-02-01T00:00:00Z "release: Atmosphere 7.7.7" > /dev/null
git tag atmosphere-7.7.7
commit 2020-03-01T00:00:00Z "fix: after the release (#31)" > /dev/null
# The 7.7.8 run tags its release commit, main moves (#41), and the run rebases:
# the tag stays on a commit main never contains.
git checkout -q -b off-main
commit 2020-03-05T00:00:00Z "release: Atmosphere 7.7.8" > /dev/null
git tag atmosphere-7.7.8
git checkout -q -
commit 2020-03-04T00:00:00Z "fix: pushed during the 7.7.8 run (#41)" > /dev/null
commit 2020-03-05T00:00:00Z "release: Atmosphere 7.7.8" > /dev/null
commit 2020-04-01T00:00:00Z "fix: next (#42)" > /dev/null
commit 2020-04-01T00:00:00Z "release: Atmosphere 7.7.9" > /dev/null
git tag atmosphere-7.7.9

# ── refs / cites, no API ─────────────────────────────────────────────────────
check "refs: every #N of the range, other trackers and anchors excluded" \
    "21 22 23 24 25 99" \
    "$("$SCRIPT" refs atmosphere-7.7.6 atmosphere-7.7.7 | tr '\n' ' ' | sed 's/ $//')"
check "cites: a hash inside the range, abbreviated" \
    "$CITED" \
    "$(echo "Fixed on main in ${CITED:0:10}." | "$SCRIPT" cites atmosphere-7.7.6 atmosphere-7.7.7)"
check "cites: a hash before the range, a non-commit hex word" \
    "" \
    "$(echo "see ${OLD:0:10} and deadbeefcafe" | "$SCRIPT" cites atmosphere-7.7.6 atmosphere-7.7.7)"

# ── the stub gh ──────────────────────────────────────────────────────────────
STUB="$TMP/stub"
mkdir -p "$STUB/bin"
cat > "$STUB/bin/gh" << 'GH'
#!/usr/bin/env bash
# Serves $GH_STUB_DIR fixtures through the caller's --jq, the way gh does.
F="$GH_STUB_DIR"
shift # api
method=GET path="" expr="" fields="" number="" q=""
while [ $# -gt 0 ]; do
    case "$1" in
        -X) method=$2; shift 2 ;;
        --jq) expr=$2; shift 2 ;;
        --paginate) shift ;;
        -f | -F)
            case $2 in q=*) q=${2#q=} ;; number=*) number=${2#number=} ;; esac
            fields="$fields $2"; shift 2 ;;
        *) path=$1; shift ;;
    esac
done
if [ -n "${GH_STUB_FAIL:-}" ] && [[ "$path" == *"$GH_STUB_FAIL"* ]]; then
    echo "gh: Bad Gateway (HTTP 502)" >&2; exit 1
fi
missing() { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
# A case glob's * spans slashes, so the longer paths come first.
case "$method $path" in
    "GET graphql") file="$F/pr-$number.json" ;;
    "GET search/issues") echo "$q" >> "$F/search-queries"; file="$F/search.json" ;;
    GET\ repos/*/issues/*/comments)
        n=${path%/comments}; file="$F/comments-${n##*/}.json"
        [ -f "$file" ] || file="$F/no-comments.json" ;;
    GET\ repos/*/issues/*) file="$F/issue-${path##*/}.json" ;;
    GET\ repos/*/labels/*) [ -f "$F/label-exists" ] || missing; echo '{}'; exit 0 ;;
    POST\ repos/*/issues/*/labels)
        n=${path%/labels}; echo "label ${n##*/}$fields" >> "$F/writes"; exit 0 ;;
    POST\ repos/*/labels) echo "create-label$fields" >> "$F/writes"; exit 0 ;;
    *) echo "unexpected gh call: $method $path" >&2; exit 2 ;;
esac
[ -f "$file" ] || missing
if [ -n "$expr" ]; then jq -r "$expr" "$file"; else cat "$file"; fi
GH
chmod +x "$STUB/bin/gh"
issue() { # issue <n> <state> <reason|null> <closed_at|null> [pr-merged-at]
    local pr=""
    [ $# -ge 5 ] && pr=", \"pull_request\": {\"merged_at\": \"$5\"}"
    echo "{\"title\": \"issue $1\", \"state\": \"$2\", \"state_reason\": $3, \"closed_at\": $4$pr}" \
        > "$STUB/issue-$1.json"
}
comment() { # comment <n> <author_association> <body>
    echo "[{\"author_association\": \"$2\", \"body\": \"$3\"}]" > "$STUB/comments-$1.json"
}
echo '[]' > "$STUB/no-comments.json"
issue 5 closed '"completed"' '"2020-02-02T00:00:00Z"' # same number, other repository
issue 21 closed '"completed"' '"2020-02-02T00:00:00Z"'
issue 22 open null null
issue 23 closed null '"2020-02-01T00:00:00Z"' 2020-02-01T00:00:00Z # merged PR
cat > "$STUB/pr-23.json" << 'JSON'
{"data": {"repository": {"pullRequest": {"closingIssuesReferences": {"nodes": [
  {"number": 26, "repository": {"nameWithOwner": "acme/widgets"}},
  {"number": 5, "repository": {"nameWithOwner": "other/repo"}}]}}}}}
JSON
issue 24 closed '"not_planned"' '"2020-02-02T00:00:00Z"'
issue 25 closed '"completed"' '"2019-12-01T00:00:00Z"'
issue 26 closed '"completed"' '"2020-02-02T00:00:00Z"'
issue 27 closed '"completed"' '"2020-02-03T00:00:00Z"'
issue 28 closed '"completed"' '"2020-02-03T00:00:00Z"'
issue 29 closed null '"2020-02-03T00:00:00Z"'
issue 41 closed '"completed"' '"2020-03-04T00:00:00Z"'
issue 42 closed '"completed"' '"2020-04-02T00:00:00Z"'
echo '{"items": [{"number": 21}, {"number": 27}, {"number": 28}, {"number": 29}]}' > "$STUB/search.json"
comment 27 MEMBER "Fixed on main in ${CITED:0:10}."
comment 28 OWNER "Fixed long ago in ${OLD:0:10}."
comment 29 NONE "Same as ${CITED:0:10}, closing."

run() { PATH="$STUB/bin:$PATH" GH_STUB_DIR="$STUB" GITHUB_REPOSITORY=acme/widgets "$SCRIPT" "$@"; }

# ── dry run writes nothing ───────────────────────────────────────────────────
DRY=$(run 7.7.7 --dry-run 2>&1)
check "dry run: exit 0" "0" "$?"
# shellcheck disable=SC2016 # literal backticks
check "dry run: names exactly the fixed issues" \
    'Would label with `7.7.7`: #21 #26 #27' "$(echo "$DRY" | tail -1)"
check "dry run: no write" "absent" "$([ -f "$STUB/writes" ] && echo present || echo absent)"
check "dry run: searches hand closes since the previous release" \
    "repo:acme/widgets is:issue is:closed reason:completed closed:>=2020-01-01T00:00:00Z" \
    "$(head -1 "$STUB/search-queries")"
for reason in "#22: skipped, still open" "#24: skipped, closed as not_planned" \
    "#25: skipped, closed 2019-12-01T00:00:00Z, before atmosphere-7.7.6" \
    "#99: skipped, not an issue or pull request" \
    "#28: skipped, closed without a commit" \
    "#29: skipped, closed without a commit"; do
    check "dry run: reports '$reason'" "1" "$(echo "$DRY" | grep -cF -- "$reason")"
done
check "dry run: a pull request closing an issue elsewhere names only ours" \
    "1" "$(echo "$DRY" | grep -cE -- '#23: pull request closing #26 $')"

# ── real run creates the label once and applies it ───────────────────────────
run 7.7.7 > /dev/null 2>&1
check "run: exit 0" "0" "$?"
check "run: creates the missing label" "1" "$(grep -c '^create-label .*name=7.7.7' "$STUB/writes")"
check "run: labels #21 #26 #27 and nothing else" "21 26 27" \
    "$(grep '^label ' "$STUB/writes" | awk '{print $2}' | tr '\n' ' ' | sed 's/ $//')"
check "run: applies the version label" "3" "$(grep -c 'labels\[\]=7.7.7' "$STUB/writes")"
rm -f "$STUB/writes"
touch "$STUB/label-exists"
run 7.7.7 > /dev/null 2>&1
check "run: an existing label is not created again" "0" "$(grep -c '^create-label' "$STUB/writes")"

# ── a previous release tagged off main ───────────────────────────────────────
rm -f "$STUB/writes" "$STUB/search-queries"
OFF=$(run 7.7.9 --dry-run 2>&1)
check "off-main tag: the previous release is found by version, not ancestry" \
    "Release 7.7.9: commits atmosphere-7.7.8..atmosphere-7.7.9" \
    "$(echo "$OFF" | head -1 | sed 's/, issues.*//')"
check "off-main tag: issues closed since main's commit the release branched from" \
    "closed:>=2020-03-01T00:00:00Z" "$(grep -o 'closed:>=.*' "$STUB/search-queries")"
# shellcheck disable=SC2016 # literal backticks
check "off-main tag: a fix pushed during that release run ships in this one" \
    'Would label with `7.7.9`: #41 #42' "$(echo "$OFF" | tail -1)"

# ── failures fail the run, they are never a skip ─────────────────────────────
GH_STUB_FAIL=issues/22 run 7.7.7 > /dev/null 2>&1
check "an issue read failing (502) fails the run" "1" "$?"
GH_STUB_FAIL=search/issues run 7.7.7 > /dev/null 2>&1
check "the closed-issue search failing fails the run" "1" "$?"
GH_STUB_FAIL=issues/27/comments run 7.7.7 > /dev/null 2>&1
check "a comment read failing fails the run" "1" "$?"
GH_STUB_FAIL=graphql run 7.7.7 > /dev/null 2>&1
check "a pull request lookup failing fails the run" "1" "$?"
check "no failed run wrote anything" "0" "$(grep -c . "$STUB/writes" 2> /dev/null || echo 0)"

# ── refusals ─────────────────────────────────────────────────────────────────
run 7.7.10 --dry-run > /dev/null 2>&1
check "refuses a release whose tag does not exist" "1" "$?"
run '7.7.7;id' --dry-run > /dev/null 2>&1
check "refuses a malformed version" "1" "$?"
check "--to HEAD: the previous tag is the highest one below the version" \
    "Release 7.7.10: commits atmosphere-7.7.9..HEAD" \
    "$(run 7.7.10 --to HEAD --dry-run 2>&1 | head -1 | sed 's/, issues.*//')"

echo ""
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]

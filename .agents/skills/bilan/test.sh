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
# ABOUTME: Tests for bilan — a throwaway git repo per case, so the caps are exercised for real
# ABOUTME: Run from anywhere: bash .agents/skills/bilan/test.sh (needs the .registre submodule, jq and ripgrep)
set -uo pipefail

# Isolated from the developer's git config: no signing prompt, a fixed identity. The fixtures
# commit throughout, and a global commit.gpgsign otherwise signs every one of those throwaway
# commits with the developer's real key — or, with a signer that cannot prompt, fails them, and
# each failure reads as a scoring regression ("expected '7', got '10'") rather than as the setup.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

HERE=$(cd "$(dirname "$0")" && pwd)
BILAN="$HERE/bilan.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
PASS=0
FAIL=0

ok()   { printf '  ✅ %s\n' "$1"; PASS=$((PASS + 1)); }
bad()  { printf '  ❌ %s\n' "$1"; FAIL=$((FAIL + 1)); }
die()  { printf '\n  💥 %s\n\n' "$1" >&2; exit 2; }

# The environment of whoever runs this must not leak into the fixtures: a tracker, a scope or a
# scratchpad set in a developer's shell would silently answer for the repos built below.
unset REGISTRE_TRACKER REGISTRE_SCAN_DIRS REGISTRE_EXTENSIONS CLAUDE_SCRATCHPAD_DIR GH_STUB_LOG

# The register cases run the REAL llm-registre gate, copied into each fixture where a checkout
# carries it: a stubbed scope would only prove bilan agrees with a register that does not exist.
# BILAN_TEST_GATES points at a copy of the gate for a checkout whose submodule is not populated
# (a fresh worktree); CI populates it, so there the default is the only path.
REAL_GATES=${BILAN_TEST_GATES:-$ROOT/.registre/limitation-gates.sh}
[ -x "$REAL_GATES" ] || die "llm-registre is not checked out at .registre — git submodule update --init .registre (or point BILAN_TEST_GATES at a copy of limitation-gates.sh)"
command -v rg >/dev/null 2>&1 || die "ripgrep is required: llm-registre scans with it"
command -v jq >/dev/null 2>&1 || die "jq is required"

# Every case runs as `( cd "$R" && … )`, and in bash `cd ""` SUCCEEDS — it stays where it is.
# So a fixture path that came back empty did not fail any case: it silently pointed all of them
# at whatever checkout the harness was launched from, and the suite committed to it and tried
# to push it. `mktemp -d -t <prefix>` is what produced that empty path on GNU (BSD invents the
# X's from a bare prefix, GNU refuses it), and `|| exit 1` did not save it either, because
# inside `$(…)` exit leaves only the subshell.
#
# The spelling is fixed in new_repo. This is the belt: no case runs until the path is a real git
# repository of our own making.
require_sandbox() { # <path>
    [ -n "${1:-}" ] || die "fixture repo path is empty — refusing to run the suite against the real checkout"
    [ -d "$1/.git" ] || die "fixture repo '$1' is not a git repository — refusing to run"
    # Matched on the SHAPE new_repo builds, not on a re-derived temp root: TMPDIR is spelled
    # differently on the two platforms and the guard must not be the thing that breaks one.
    case $1 in
        */bilan-test.*/work) : ;;
        *) die "fixture repo '$1' is not one of ours — refusing to run" ;;
    esac
}
real_caps() { printf '%s' "$1" | jq '[.caps[]] | length'; }

check() { # <description> <expected> <actual>
    if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 — expected '$2', got '$3'"; fi
}

# A repo with an origin it can actually push to, so upstream-dependent caps are real. The local
# branch is main whatever init.defaultBranch says, because push_base treats main specially.
new_repo() {
    local root remote
    # An explicit template with X's, because `mktemp -d -t PREFIX` is BSD-only:
    # GNU rejects it ("too few X's in template"). That failure was not merely
    # noisy — with no sandbox created, the git commands below ran against the
    # REAL repository and committed to it.
    root=$(mktemp -d "${TMPDIR:-/tmp}/bilan-test.XXXXXX") || return 1
    remote="$root/remote.git"
    git init -q --bare "$remote"
    git init -q "$root/work"
    git -C "$root/work" symbolic-ref HEAD refs/heads/main
    git -C "$root/work" config user.email t@t.t
    git -C "$root/work" config user.name t
    git -C "$root/work" remote add origin "$remote"
    echo one > "$root/work/a.txt"
    git -C "$root/work" add a.txt
    git -C "$root/work" commit -qm first
    git -C "$root/work" push -q -u origin HEAD:refs/heads/main >/dev/null 2>&1
    git -C "$root/work" branch -q --set-upstream-to=origin/main 2>/dev/null
    git -C "$root/work" fetch -q origin 2>/dev/null   # FETCH_HEAD, or every case reads as stale
    printf '%s' "$root/work"
}

# SessionStart runs this at t=0 against whatever state the checkout is in, so every test that
# measures a change has to establish the starting point first — otherwise the first bilan run
# creates the baseline itself and correctly treats the change as inherited.
baseline_now() { ( cd "$1" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    bash "$BILAN" baseline >/dev/null 2>&1 ); }

run() { # <repo> [args...]
    local repo=$1; shift
    ( cd "$repo" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
        bash "$BILAN" --cheap --json "$@" 2>/dev/null )
}

CFG=$(mktemp -d "${TMPDIR:-/tmp}/bilan-cfg.XXXXXX") || die "mktemp -d failed for the config dir"
SID="00000000-0000-0000-0000-00000000test"
trap 'rm -rf "$CFG"' EXIT

# Every fixture repo starts with nothing committed by the "session", so any case that also writes
# a transcript would pick up the unmeasured cap alongside the thing it tests. A completed todo
# keeps the harness measurable throughout; the unmeasured case has its own block below, where it
# is the thing under test.
measurable() {
    mkdir -p "$CFG/tasks/$SID"
    printf '{"status":"completed","subject":"harness"}\n' > "$CFG/tasks/$SID/0.json"
}

printf '\nbilan tests\n\n'
measurable

# ---- the fixtures never read the developer's git config (the exports at the top). A HOME whose
# git config signs every commit, with a signer that always fails, stands in for a developer whose
# signer cannot prompt: a fixture commit has to go through regardless, and sign with nothing.
SIGN_HOME="$CFG/signing-home"
mkdir -p "$SIGN_HOME" && git init -q "$CFG/sign-probe"
printf '[commit]\n\tgpgsign = true\n[gpg]\n\tprogram = false\n' > "$SIGN_HOME/.gitconfig"
git -C "$CFG/sign-probe" config user.name t && git -C "$CFG/sign-probe" config user.email t@t.t
check "a developer's global commit.gpgsign never reaches a fixture commit" 0 \
    "$( (cd "$CFG/sign-probe" && HOME="$SIGN_HOME" XDG_CONFIG_HOME="$SIGN_HOME" git commit -q --allow-empty -m probe) >/dev/null 2>&1; echo $?)"
rm -rf "$SIGN_HOME" "$CFG/sign-probe"

# ---- clean repo scores 10
# The guard runs in a command substitution, so its `exit 2` ends only that subshell and the
# caller sees an empty path — the same shape that once aimed the whole suite at the live
# checkout. `|| exit 2` on the assignment is what carries the refusal into the script.
fixture() {
    local p; p=$(new_repo) || p=""
    require_sandbox "$p"
    printf '%s' "$p"
}

R=$(fixture) || exit 2
baseline_now "$R"
out=$(run "$R")
check "clean repo has no real cap" 0 "$(real_caps "$out")"
# CI never scores, so --cheap and the full run give the same number on a clean checkout, and
# there is no standing cap for having skipped it.
check "--cheap and the full run agree on the number" 10 "$(printf '%s' "$out" | jq -r .score)"
check "no standing cap for skipping CI" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("CI"))] | length')"

# ---- uncommitted tracked change caps at 7
echo two >> "$R/a.txt"
out=$(run "$R")
check "uncommitted tracked change caps at 7" 7 "$(printf '%s' "$out" | jq -r .score)"
check "the evidence names the file" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("a\\.txt"))] | length')"
git -C "$R" checkout -q -- a.txt

# ---- untracked file caps at 9, not 7
touch "$R/stray.md"
out=$(run "$R")
check "untracked file caps at 9" 9 "$(printf '%s' "$out" | jq -r .score)"
check "the untracked evidence names the file" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("stray\\.md"))] | length')"
rm -f "$R/stray.md"

# ---- the pre-push hook the validation marker is judged against. Its TTL is read from the hook,
# so the fixture's hook says 5 minutes and the cases below prove bilan listens to it.
mkdir -p "$R/.githooks"
printf '#!/bin/bash\nVALIDATION_TTL_MINUTES=5\n' > "$R/.githooks/pre-push"
git -C "$R" add .githooks && git -C "$R" commit -qm "the hook" && git -C "$R" push -q origin HEAD:refs/heads/main

# ---- a commit made after the baseline is this session's, and caps at 8
baseline_now "$R"
echo three >> "$R/a.txt"
git -C "$R" commit -q -am second
out=$(run "$R")
check "unpushed commit caps at 8" 8 "$(printf '%s' "$out" | jq -r .score)"
check "unpushed names the remedy" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==8) | select(.remedy | test("git push"))] | length')"

# ---- the validation marker scripts/pre-push-validate.sh stamps: "<epoch> <sha>" in the git dir
marker_caps() { printf '%s' "$1" | jq '[.caps[] | select(.evidence | test("validation"))] | length'; }
check "missing validation marker is a cap" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("validation-passed"))] | length')"
MARKER="$R/.git/validation-passed"
head_sha=$(git -C "$R" rev-parse HEAD)
printf '%s %s\n' "$(date +%s)" "$(git -C "$R" rev-parse HEAD~1)" > "$MARKER"
check "a marker for another sha is a cap" 1 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("validation marker is for"))] | length')"
printf '%s %s\n' "$(date +%s)" "$head_sha" > "$MARKER"
check "a fresh marker for HEAD clears it" 0 "$(marker_caps "$(run "$R")")"
printf '%s %s\n' "$(( $(date +%s) - 600 ))" "$head_sha" > "$MARKER"
out=$(run "$R")
check "a marker older than the hook's TTL is a cap" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("old \\(TTL 5m\\)"))] | length')"
printf 'garbage\n' > "$MARKER"
check "an unreadable marker is a cap, not an arithmetic error" 1 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("unreadable"))] | length')"
rm -f "$MARKER"

# ---- --cheap cannot fetch, so against a stale fetch it says the commits MAY be on the remote
touch -t 202601010000 "$R/.git/FETCH_HEAD"
out=$(run "$R")
check "a stale fetch makes --cheap say it cannot tell" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("may already be on the remote"))] | length')"
check "and it does not claim the commits are unpushed" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==8)] | length')"
git -C "$R" fetch -q origin
git -C "$R" push -q origin HEAD:refs/heads/main

# ---- a branch with no upstream is measured against origin/main. A worktree branch here is
# landed with `git push origin <branch>:main`, never published, so an upstream-only check would
# never report the work it has not landed.
git -C "$R" checkout -q -b feature
echo feature > "$R/f.txt" && git -C "$R" add f.txt && git -C "$R" commit -qm "feature work"
out=$(run "$R")
check "a branch with no upstream and commits off origin/main caps at 8" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==8) | select(.evidence | test("no upstream"))] | length')"
check "its remedy is how this repo lands a branch" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.remedy | test("git push origin feature:main"))] | length')"
check "and it wants a validation marker before that push" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("validation-passed"))] | length')"
git -C "$R" checkout -q main && git -C "$R" branch -q -D feature

# ---- a linked worktree has its own marker, as scripts/pre-push-validate.sh stamps it there
WT="$(dirname "$R")/wt"
git -C "$R" worktree add -q -b wt-branch "$WT" 2>/dev/null
echo wt > "$WT/w.txt" && git -C "$WT" add w.txt && git -C "$WT" commit -qm "worktree work"
printf '%s %s\n' "$(date +%s)" "$(git -C "$WT" rev-parse HEAD)" > "$R/.git/validation-passed"
out=$(run "$WT")
check "a worktree does not borrow the main checkout's marker" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("no .git/validation-passed"))] | length')"
check "its fetch age is the repo's, not only its own FETCH_HEAD's" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("may already be on the remote"))] | length')"
rm -f "$R/.git/validation-passed"
printf '%s %s\n' "$(date +%s)" "$(git -C "$WT" rev-parse HEAD)" > "$(git -C "$WT" rev-parse --absolute-git-dir)/validation-passed"
check "its own marker satisfies it" 0 "$(marker_caps "$(run "$WT")")"
git -C "$R" worktree remove --force "$WT" && git -C "$R" branch -q -D wt-branch

# ---- a held carnet issue caps at 6 and outranks everything else
mkdir -p "$CFG/carnet-claims"
cat > "$CFG/carnet-claims/$SID.jsonl" <<LEDGER
{"v":1,"session":"$SID","name":"test","user":"t","host":"h","pid":1,"repo":"atmosphere","branch":"main","at":"2026-09-08T00:00:00Z","kind":"identity"}
{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":999,"at":"2026-09-08T00:00:00Z"}
LEDGER
baseline_now "$R"
echo four >> "$R/a.txt" && git -C "$R" commit -q -am third
out=$(run "$R")
check "held carnet issue caps at 6" 6 "$(printf '%s' "$out" | jq -r .score)"
check "held issue is named in the evidence" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("carnet#999"))] | length')"
git -C "$R" push -q origin HEAD:refs/heads/main

# ---- a filed issue caps at 6 and is distinguishable from a held one
printf '{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":1000,"at":"2026-09-08T00:00:00Z"}\n' \
    >> "$CFG/carnet-claims/$SID.jsonl"
out=$(run "$R")
# A session that files an issue must fix it before it stops or claims 10/10.
check "a filed issue caps at 6, level with a held one" 6 "$(printf '%s' "$out" | jq -r .score)"
check "filed is reported separately from held" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("filed this session and still open"))] | length')"
rm -f "$CFG/carnet-claims/$SID.jsonl"

# ---- the register: llm-registre's gate at .registre, the tracker and extensions in
# registre.toml, and the directories the CI lane passes the gate — Atmosphere's layout
mkdir -p "$R/.registre" "$R/modules/core/src/main/java/org/example" "$R/.github/workflows"
cp "$REAL_GATES" "$R/.registre/limitation-gates.sh"
printf 'tracker = "Atmosphere/atmosphere-carnet"\nextensions = "java,ts,js"\n' > "$R/registre.toml"
cat > "$R/.github/workflows/limitation-register.yml" <<'LANE'
name: "CI: Limitation Register"
jobs:
  limitation-register:
    steps:
      # The gates live in the .registre submodule.
      - name: Run the limitation register gates
        run: ./.registre/limitation-gates.sh modules cli
LANE
WIDGET="modules/core/src/main/java/org/example/Widget.java"
printf 'package org.example;\n\npublic class Widget {\n}\n' > "$R/$WIDGET"
git -C "$R" add -A && git -C "$R" commit -qm "the register" && git -C "$R" push -q origin HEAD:refs/heads/main
IDENTITY="{\"v\":1,\"session\":\"$SID\",\"name\":\"test\",\"user\":\"t\",\"host\":\"h\",\"pid\":1,\"repo\":\"atmosphere\",\"branch\":\"main\",\"at\":\"2026-09-08T00:00:00Z\",\"kind\":\"identity\"}"

# ---- the scope is the gate's own. The pinned gate predates --list-files, so bilan runs it with a
# recording ripgrep and lists what it would have scanned; a gate that has --list-files is asked.
# Either way the answer below comes from the REAL gate, so it is what the gate would scan.
scope_json() { ( cd "$1" && CLAUDE_CONFIG_DIR="$CFG" bash "$BILAN" scope --json 2>/dev/null ); }
mkdir -p "$R/modules/core/src/test/java/org/example"
printf 'class WidgetTest {}\n' > "$R/modules/core/src/test/java/org/example/WidgetTest.java"
if grep -q -- '--list-files' "$REAL_GATES"; then want_via=list-files; else want_via=probe; fi
out=$(scope_json "$R")
check "the scope comes from the real gate ($want_via)" "$want_via" "$(printf '%s' "$out" | jq -r .via)"
check "it scans the directories the CI lane names" "modules cli" "$(printf '%s' "$out" | jq -r '.dirs | join(" ")')"
check "it holds production source" 1 "$(printf '%s' "$out" | jq --arg w "$WIDGET" '[.files[] | select(. == $w)] | length')"
check "and no test tree" 0 "$(printf '%s' "$out" | jq '[.files[] | select(test("/src/test/"))] | length')"
rm -rf "$R/modules/core/src/test"

# A gate WITHOUT --list-files is probed: the same gate with every mention of the flag removed.
# For the pinned gate that is the same file; for a newer one it proves the probe still reads it.
sed '/--list-files/d' "$REAL_GATES" > "$R/.registre/limitation-gates.sh"
out=$(scope_json "$R")
check "a gate without --list-files is probed" probe "$(printf '%s' "$out" | jq -r .via)"
check "…and the probe finds what the gate scans" 1 "$(printf '%s' "$out" | jq --arg w "$WIDGET" '[.files[] | select(. == $w)] | length')"
# A gate WITH --list-files is asked, and its answer is taken as given. A stub, deliberately: it
# lists one file the probe never would, which proves the answer came from the gate and not from
# bilan. The real gate's own listing is exercised above whenever the pinned version has it.
printf '#!/usr/bin/env bash\n# limitation-gates.sh --list-files [<scan-dir>...]\n[ "$1" = --list-files ] || exit 0\nprintf "%%s\\n" registre.toml\n' \
    > "$R/.registre/limitation-gates.sh"
out=$(scope_json "$R")
check "a gate with --list-files is asked" list-files "$(printf '%s' "$out" | jq -r .via)"
check "…and its answer is the scope" "registre.toml" "$(printf '%s' "$out" | jq -r '.files | join(" ")')"
git -C "$R" checkout -q -- .registre/limitation-gates.sh

# The directories: registre.toml's scan_dirs wins over the lane, the environment over both, and
# a lane whose arguments are not literal names nothing, so the scope is unknown rather than guessed.
printf 'scan_dirs = "modules"\n' >> "$R/registre.toml"
check "scan_dirs in registre.toml wins over the CI lane" "modules" "$(scope_json "$R" | jq -r '.dirs | join(" ")')"
check "REGISTRE_SCAN_DIRS wins over both" "cli" \
    "$( (cd "$R" && REGISTRE_SCAN_DIRS=cli CLAUDE_CONFIG_DIR="$CFG" bash "$BILAN" scope --json 2>/dev/null) | jq -r '.dirs | join(" ")')"
git -C "$R" checkout -q -- registre.toml
sed -i.bak 's#limitation-gates.sh modules cli#limitation-gates.sh $SCAN_DIRS#' "$R/.github/workflows/limitation-register.yml"
rm -f "$R/.github/workflows/limitation-register.yml.bak"
out=$(scope_json "$R")
check "a lane passing a variable names no directory" "dirs" "$(printf '%s' "$out" | jq -r .unknown)"
# The gate reads its directories from its caller, never from registre.toml, so the remedy points
# at the lane's call — a scan_dirs declared instead would move bilan's scope and not the gate's.
check "…and the remedy points at the lane's gate call, not at a key the gate never reads" 1 \
    "$(printf '%s' "$out" | jq -r .remedy | grep -c 'gate call in .github/workflows/limitation-register.yml')"
git -C "$R" checkout -q -- .github/workflows/limitation-register.yml
# Only the lane bilan.yml's path filter names is read, and only an invocation of the gate in it:
# another workflow that runs the gate elsewhere and sorts first decides nothing, and neither does a
# step name that mentions the gate.
printf 'jobs:\n  fixtures:\n    steps:\n      - run: ./.registre/limitation-gates.sh cli\n' > "$R/.github/workflows/a-gate.yml"
check "another workflow running the gate does not decide the scope" "modules cli" \
    "$(scope_json "$R" | jq -r '.dirs | join(" ")')"
rm -f "$R/.github/workflows/a-gate.yml"
cat > "$R/.github/workflows/limitation-register.yml" <<'LANE'
name: "CI: Limitation Register"
jobs:
  limitation-register:
    steps:
      - name: Run limitation-gates.sh over the samples first
        run: echo warming up
      - name: Run the limitation register gates
        run: ./.registre/limitation-gates.sh modules cli
LANE
check "a step name that mentions the gate is not read as its arguments" "modules cli" \
    "$(scope_json "$R" | jq -r '.dirs | join(" ")')"
git -C "$R" checkout -q -- .github/workflows/limitation-register.yml

# ---- a registered limitation is a register entry, not work owed
#
# The LIMITATION procedure requires an OPEN issue for as long as a marker names it, so a session
# that followed that procedure correctly was capped at 6 for complying, with no action available:
# nothing to fix, and nothing honest to close.
#
# The exemption needs the `limitation` LABEL *and* a marker naming that issue in a file the
# register scans. Either half alone still caps, which is what stops a bug being relabelled out of
# the score.
STUB=$(mktemp -d "${TMPDIR:-/tmp}/bilan-gh.XXXXXX") || die "mktemp -d failed for the gh stub"
cat > "$STUB/gh" <<'GH'
#!/usr/bin/env bash
# The smallest gh that answers every path bilan asks, logging each call so a case can assert how
# it was asked as well as what came back.
#   issue …                                           refused, the way a cloud session's proxy
#       refuses every GraphQL query: the tracker has to be asked over REST.
#   api repos/<tracker>/issues/<n> --jq <filter>      Atmosphere/atmosphere-carnet over REST: 236
#       closed, 237 open, 2001 open and labelled `limitation`, 2002 open and labelled `bug`, a
#       closed limitation (2003) and a pull request (2004). Any other number, and any other
#       tracker, is a 404 — unreadable, as a private tracker is to a token without access — and,
#       as real `gh api` does, the error body lands on stdout, not the filtered answer.
#   api repos/<tracker> --jq <filter>                 the tracker itself, readable only for Atmosphere's.
#   api --paginate repos/…/check-runs… --jq <filter>  two pages of check runs, one of them red.
printf '%s\n' "$*" >> "${GH_STUB_LOG:-/dev/null}"
path="" filter="" prev=""
for a in "$@"; do
    case "$prev" in --jq) filter=$a ;; esac
    case "$a" in repos/*) path=$a ;; esac
    prev=$a
done
T=Atmosphere/atmosphere-carnet
if [ "${1:-}" = issue ]; then
    echo "gh: HTTP 403: GraphQL is not served here — use the REST API (gh api)" >&2
    exit 1
fi
if [ "${1:-}" = api ]; then
    case "$path" in
        */check-runs*)
            for page in '{"total_count":3,"check_runs":[{"name":"build","status":"completed","conclusion":"success"}]}' \
                        '{"total_count":3,"check_runs":[{"name":"lint","status":"completed","conclusion":"failure"},{"name":"e2e","status":"in_progress","conclusion":null}]}'; do
                printf '%s' "$page" | jq -c "$filter"
            done
            exit 0 ;;
        "repos/$T")             body='{"full_name":"Atmosphere/atmosphere-carnet"}' ;;
        "repos/$T/issues/236")  body='{"state":"closed","labels":[]}' ;;
        "repos/$T/issues/237")  body='{"state":"open","labels":[]}' ;;
        "repos/$T/issues/2001") body='{"state":"open","labels":[{"name":"limitation"}]}' ;;
        "repos/$T/issues/2002") body='{"state":"open","labels":[{"name":"bug"}]}' ;;
        "repos/$T/issues/2003") body='{"state":"closed","labels":[{"name":"limitation"}]}' ;;
        "repos/$T/issues/2004") body='{"state":"open","labels":[{"name":"limitation"}],"pull_request":{}}' ;;
        *) echo '{"message":"Not Found","status":"404"}'; echo 'gh: Not Found (HTTP 404)' >&2; exit 1 ;;
    esac
    printf '%s' "$body" | jq -r "$filter"
    exit
fi
exit 1
GH
chmod +x "$STUB/gh"
# Args pass through verbatim: `run_full "$R" --json` for the machine form, `run_full "$R"` for the
# human one. A "${2:---json}" default would have substituted on an EMPTY second argument too, so
# the human call would silently have been a --json call and the note assertion below would have
# passed against output that never contained notes.
run_full() { local repo=$1; shift; ( cd "$repo" && CLAUDE_CONFIG_DIR="$CFG" \
    CLAUDE_CODE_SESSION_ID="$SID" PATH="$STUB:$PATH" bash "$BILAN" "$@" 2>/dev/null ); }
filed_caps() { printf '%s' "$1" | jq '[.caps[] | select(.evidence | test("filed this session and still open"))] | length'; }
mark() { printf '    // LIMITATION(registre#%s): the width this names\n' "$1" >> "$R/$WIDGET"; }

# A filed issue someone else closed is not work owed, and only the tracker can say so. The stub
# refuses `gh issue` the way a cloud session's proxy refuses every GraphQL query, so the close is
# seen only when the tracker is asked over REST.
printf '%s\n%s\n' "$IDENTITY" '{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":236,"at":"2026-09-08T00:00:00Z"}' \
    > "$CFG/carnet-claims/$SID.jsonl"
: > "$CFG/gh.log"
out=$( (cd "$R" && GH_STUB_LOG="$CFG/gh.log" CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    PATH="$STUB:$PATH" bash "$BILAN" --json 2>/dev/null) )
check "a filed issue the tracker reports closed does not cap the full run" 0 "$(filed_caps "$out")"
check "…and the tracker is asked over REST, never through GraphQL" 0 "$(grep -c '^issue ' "$CFG/gh.log")"
# A tracker that does not answer keeps the cap, the safe direction, and says it did not answer
# rather than reporting "still open" as though it had.
printf '%s\n%s\n' "$IDENTITY" '{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":1000,"at":"2026-09-08T00:00:00Z"}' \
    > "$CFG/carnet-claims/$SID.jsonl"
out=$(run_full "$R" --json)
check "a filed issue the tracker gives no answer for still caps at 6" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap == 6) | select(.evidence | test("did not say whether it is closed: carnet#1000"))] | length')"
check "…and is not reported as still open" 0 "$(filed_caps "$out")"
rm -f "$CFG/gh.log"

printf '%s\n%s\n' "$IDENTITY" '{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":2001,"at":"2026-09-08T00:00:00Z"}' \
    > "$CFG/carnet-claims/$SID.jsonl"

# Label but NO marker — still work owed.
check "a limitation label alone does not exempt a filed issue" 1 "$(filed_caps "$(run_full "$R" --json)")"

# Label AND a marker in scanned source — a register entry.
mark 2001
out=$(run_full "$R" --json)
check "label plus a marker naming it exempts the filed issue" 0 "$(filed_caps "$out")"
check "and the registered limitation is still REPORTED, not silently dropped" 1 \
    "$(run_full "$R" | grep -c 'carnet#2001 is a registered limitation')"
git -C "$R" checkout -q -- "$WIDGET"

# The same marker where the register does not look. Neither the gate nor bilan scans a test
# tree, so a marker there is validated by nothing and must credit nothing: a gap in test coverage
# is marked on the production item the tests leave uncovered.
mkdir -p "$R/modules/core/src/test/java/org/example"
echo '// LIMITATION(registre#2001): the width this names' > "$R/modules/core/src/test/java/org/example/HelperTest.java"
check "a marker under src/test does not credit the limitation" 1 "$(filed_caps "$(run_full "$R" --json)")"
rm -rf "$R/modules/core/src/test"

# A marker naming an issue that is NOT labelled `limitation` — still work owed, so a bug cannot
# be exempted by dropping a marker next to it.
mark 2002
printf '{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":2002,"at":"2026-09-08T00:00:00Z"}\n' \
    >> "$CFG/carnet-claims/$SID.jsonl"
check "a marker without the limitation label does not exempt" 1 "$(filed_caps "$(run_full "$R" --json)")"
git -C "$R" checkout -q -- "$WIDGET"

# --cheap never touches the network, and the status line runs it. It reads the label from the
# line carnet.sh writes when it applies it — and without that line it keeps the cap.
printf '%s\n%s\n' "$IDENTITY" '{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":2001,"at":"2026-09-08T00:00:00Z"}' \
    > "$CFG/carnet-claims/$SID.jsonl"
mark 2001
check "--cheap with no recorded label keeps the cap" 1 "$(filed_caps "$(run "$R")")"
printf '{"kind":"limitation","tracker":"Atmosphere/atmosphere-carnet","issue":2001,"at":"2026-09-08T00:00:00Z"}\n' \
    >> "$CFG/carnet-claims/$SID.jsonl"
out=$(run "$R")
check "--cheap credits a limitation carnet recorded, with a marker in scope" 0 "$(filed_caps "$out")"
check "--cheap and the full run agree on it" "$(filed_caps "$(run_full "$R" --json)")" "$(filed_caps "$out")"
git -C "$R" checkout -q -- "$WIDGET"
check "--cheap still wants the marker, not the label alone" 1 "$(filed_caps "$(run "$R")")"

# The ledger directory is shared by every repo on the machine, and a line names its own register.
# Another register's filed issue is never exempted by a marker HERE, which names this register's
# issue of the same number — and it is named with its tracker, so nobody closes the wrong one.
printf '%s\n%s\n%s\n%s\n' "$IDENTITY" \
    '{"kind":"filed","tracker":"acme/other-carnet","issue":2001,"at":"2026-09-08T00:00:00Z"}' \
    '{"kind":"limitation","tracker":"acme/other-carnet","issue":2001,"at":"2026-09-08T00:00:00Z"}' \
    '{"kind":"claim","tracker":"acme/other-carnet","issue":77,"at":"2026-09-08T00:00:00Z"}' \
    > "$CFG/carnet-claims/$SID.jsonl"
mark 2001
out=$(run "$R")
check "another register's filed issue is not exempted by a marker here" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("filed this session and still open: acme/other-carnet#2001"))] | length')"
check "another register's claim is named with its tracker" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("still holding acme/other-carnet#77"))] | length')"
git -C "$R" checkout -q -- "$WIDGET"
rm -f "$CFG/carnet-claims/$SID.jsonl"

# ---- a marker this session added must name an open, labelled issue
#
# A closed issue keeps a marker exempting its prose after the gap stopped being tracked. The
# register's reconciliation catches every such marker in the tree; this asks the same three
# conditions of the session's own markers before it reports a number. Only the full run asks.
dead_marker() { # <bilan --json output> <reason> -> how many marker caps carry that reason
    printf '%s' "$1" | jq --arg r "$2" \
        '[.caps[] | select(.evidence | test("naming no live issue")) | select(.evidence | contains($r))] | length'
}
mark 2001
check "a marker naming an open limitation issue is not capped" 0 \
    "$(run_full "$R" --json | jq '[.caps[] | select(.evidence | test("naming no live issue"))] | length')"
git -C "$R" checkout -q -- "$WIDGET"
mark 2003
out=$(run_full "$R" --json)
check "a marker naming a closed issue caps at 6" 1 "$(dead_marker "$out" "#2003(closed)")"
check "…and the remedy says to delete a fixed gap's marker" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.remedy | test("delete the marker"))] | length')"
git -C "$R" checkout -q -- "$WIDGET"
mark 2002
check "a marker naming an open issue without the label caps" 1 \
    "$(dead_marker "$(run_full "$R" --json)" "#2002(not labelled limitation)")"
git -C "$R" checkout -q -- "$WIDGET"
mark 2004
check "a marker naming a pull request caps" 1 "$(dead_marker "$(run_full "$R" --json)" "#2004(a pull request)")"
git -C "$R" checkout -q -- "$WIDGET"
mark 9999
check "a marker naming no issue on the tracker caps" 1 \
    "$(dead_marker "$(run_full "$R" --json)" "#9999(not found on the tracker)")"
check "--cheap asks nothing, so it does not judge the marker" 0 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("naming no live issue"))] | length')"
# The tracker is private. A token that cannot read it leaves every marker unverified, and that is
# said as such rather than blamed on the marker.
check "an unreadable tracker is named as the reason, not a missing issue" 1 \
    "$( (cd "$R" && REGISTRE_TRACKER=acme/sealed-carnet CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
        PATH="$STUB:$PATH" bash "$BILAN" --json 2>/dev/null) | jq '[.caps[] | select(.evidence | test("acme/sealed-carnet unreadable"))] | length')"
git -C "$R" checkout -q -- "$WIDGET"

# ---- the markers a session added are measured from the merge base. A peer who retires a dead
# marker upstream must not make a checkout that is merely behind — still carrying the line,
# unmerged — look as if it had just added it; a plain tree diff against origin/main does exactly
# that, because the peer's removal reads backwards as this checkout's addition.
mark 2003
git -C "$R" commit -qam "a marker that was already there" && git -C "$R" push -q origin HEAD:refs/heads/main
PEER="$(dirname "$R")/peer"
git clone -q -b main "$(dirname "$R")/remote.git" "$PEER" 2>/dev/null
git -C "$PEER" config user.email p@p.p && git -C "$PEER" config user.name p
grep -v 'LIMITATION(registre#2003)' "$PEER/$WIDGET" > "$PEER/widget.tmp" && mv "$PEER/widget.tmp" "$PEER/$WIDGET"
git -C "$PEER" commit -qam "the peer retires the dead marker" && git -C "$PEER" push -q origin main
echo unrelated > "$R/u.txt" && git -C "$R" add u.txt && git -C "$R" commit -qm "unrelated work"
git -C "$R" fetch -q origin
check "a marker a peer retired upstream is not blamed on a checkout that is behind" 0 \
    "$(dead_marker "$(run_full "$R" --json)" "#2003(closed)")"
git -C "$R" pull -q --rebase origin main && git -C "$R" push -q origin HEAD:refs/heads/main
rm -rf "$PEER"

# ---- an unregistered LIMITATION marker caps at 6
echo '    // LIMITATION(registre#): nothing reads this' >> "$R/$WIDGET"
out=$(run "$R")
check "LIMITATION naming no issue caps at 6" 6 "$(printf '%s' "$out" | jq -r .score)"
git -C "$R" checkout -q -- "$WIDGET"

# ---- the scan is the register's: prose, fixtures, specs and bilan's own tree are outside it.
# A scanner that reports its own tree — the skill's docs, the grep pattern in bilan.sh — is worse
# than no scanner. The files are STAGED: bilan reads the session's diff, and an untracked file is
# in no diff, so a case that left them untracked would pass without looking at any of them.
mkdir -p "$R/.agents/skills/bilan" "$R/modules/core/src/test/java/org/example" "$R/modules/core/src/main/js"
echo 'LIMITATION(registre#[^)]*) is the pattern' > "$R/.agents/skills/bilan/bilan.sh"
echo '| LIMITATION(registre#…) marker | caps at 6 |'  > "$R/doc.md"
echo 'assert LIMITATION(registre#) fires'             > "$R/modules/core/src/test/java/org/example/FixtureTest.java"
echo 'let y = 2; // LIMITATION(registre#) in a spec'  > "$R/modules/core/src/main/js/thing.spec.js"
git -C "$R" add -A
out=$(run "$R")
check "the scan does not report its own tree, prose, tests or specs" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("LIMITATION"))] | length')"
echo '    // LIMITATION(registre#) in real source' >> "$R/$WIDGET"
out=$(run "$R")
check "…but still reports a marker in real source" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("LIMITATION"))] | length')"
git -C "$R" reset -q
git -C "$R" checkout -q -- "$WIDGET"
rm -rf "$R/.agents" "$R/doc.md" "$R/modules/core/src/test" "$R/modules/core/src/main/js"

# ---- without the register's gate a marker this session wrote cannot be verified. That fails
# closed on exactly the markers that could be in scope: a checkout whose submodule is not checked
# out — a fresh worktree, usually — must not cap a session that wrote no marker, nor one whose
# only "marker" is prose about the convention.
R2=$(fixture) || exit 2
mkdir -p "$R2/modules/core/src/main/java/org/example"
printf 'tracker = "Atmosphere/atmosphere-carnet"\nextensions = "java,ts,js"\n' > "$R2/registre.toml"
printf 'package org.example;\npublic class Widget {}\n' > "$R2/$WIDGET"
git -C "$R2" add -A && git -C "$R2" commit -qm src && git -C "$R2" push -q origin HEAD:refs/heads/main
check "no gate and no marker: nothing to verify, no cap" 0 \
    "$(run "$R2" | jq '[.caps[] | select(.evidence | test("cannot scope"))] | length')"
echo '| LIMITATION(registre#7) | the convention, documented |' > "$R2/notes.md"
echo '# LIMITATION(registre#7): in a script' > "$R2/tool.sh"
git -C "$R2" add -A
check "no gate and a marker only in prose or a script: nothing the register scans, no cap" 0 \
    "$(run "$R2" | jq '[.caps[] | select(.evidence | test("cannot scope"))] | length')"
git -C "$R2" reset -q && rm -f "$R2/notes.md" "$R2/tool.sh"
echo '    // LIMITATION(registre#7): the width this names' >> "$R2/$WIDGET"
out=$(run "$R2")
check "no gate and a marker in source: fails closed and names the submodule" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("cannot scope")) | select(.remedy | test("submodule update --init .registre"))] | length')"
check "…and names the file" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("cannot scope.*Widget\\.java"))] | length')"
rm -rf "$(dirname "$R2")"
rm -f "$CFG/bilan/"*.baseline*
baseline_now "$R"

# ---- ack accounts for files this session must not touch, for exactly that set
echo peer >> "$R/a.txt"
check "before ack, a dirty tracked file caps at 7" 7 "$(run "$R" | jq -r .score)"
( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    bash "$BILAN" ack --why "a peer's pin bump in the shared checkout" >/dev/null 2>&1 )
out=$(run "$R")
# An ack is a recorded statement of ownership, so it CLEARS: a peer's file is not this
# session's incompleteness, and leaving it at 9 meant a session that had done everything right
# still could not reach 10.
check "after ack the cap is gone entirely" 0 "$(real_caps "$out")"
check "the ack reason stays visible as a note" 1 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("pin bump"))] | length')"
echo second > "$R/b.txt" && git -C "$R" add b.txt
check "dirtying one more file brings the cap back" 7 "$(run "$R" | jq -r .score)"
git -C "$R" rm -q -f --cached b.txt >/dev/null 2>&1; rm -f "$R/b.txt"
git -C "$R" checkout -q -- a.txt

# The ack is keyed by path set, not by content: ownership is a property of the files, not of
# what is in them. So clear it before exercising the baseline on the same file.
rm -f "$CFG/bilan/"*.ack.json

# The ack signs the set the cap is looked up by: the session's own paths, never the ones the
# baseline already calls inherited. Signing every dirty path made an ack beside an inherited file
# a no-op that still printed "accounted for" — in the shared checkout it exists for.
echo peer-was-mid-edit >> "$R/a.txt"
baseline_now "$R"
echo peer-later > "$R/d.txt" && git -C "$R" add d.txt
check "a peer's file dirtied after the baseline caps at 7 beside an inherited one" 7 "$(run "$R" | jq -r .score)"
( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    bash "$BILAN" ack --why "a peer's d.txt, written after this session opened" >/dev/null 2>&1 )
out=$(run "$R")
check "an ack made beside an inherited file clears the cap" 0 "$(real_caps "$out")"
check "…and signs the session's own set, not the inherited file" "d.txt" \
    "$(jq -r .files "$CFG/bilan/$SID.ack.json" 2>/dev/null)"
git -C "$R" rm -q -f --cached d.txt >/dev/null 2>&1; rm -f "$R/d.txt"
git -C "$R" checkout -q -- a.txt
rm -f "$CFG/bilan/"*.ack.json "$CFG/bilan/"*.baseline*
baseline_now "$R"                       # the next case needs a baseline taken on a clean tree

# ---- files already dirty when the session opened are not this session's, automatically.
echo peer-was-mid-edit >> "$R/a.txt"
check "a file dirty before the baseline caps at 7 without one" 7 "$(run "$R" | jq -r .score)"
( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" bash "$BILAN" baseline >/dev/null 2>&1 )
out=$(run "$R")
check "after the baseline it does not cap at all" 0 "$(real_caps "$out")"
check "the inherited file is still stated as a note" 1 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("already uncommitted"))] | length')"
echo mine > "$R/c.txt" && git -C "$R" add c.txt
check "a file this session dirties still caps at 7" 7 "$(run "$R" | jq -r .score)"
check "and the cap names only the session's own file" 1 \
    "$(run "$R" | jq '[.caps[] | select(.cap==7) | select(.evidence | test("c\\.txt") and (test("a\\.txt") | not))] | length')"
# SessionStart fires again on resume and after a compaction — the same session. The hook passes
# --if-missing, so the session's own dirty file is not re-recorded as inherited.
( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" bash "$BILAN" baseline --if-missing >/dev/null 2>&1 )
check "a second SessionStart keeps the baseline, so the session's own file still caps" 7 "$(run "$R" | jq -r .score)"
git -C "$R" rm -q -f --cached c.txt >/dev/null 2>&1; rm -f "$R/c.txt"
# The baseline describes the checkout the session opened in, and no other. A worktree the session
# creates afterwards has an a.txt of its own, and the main checkout's dirty a.txt says nothing about
# it: the session's edit there is its own work.
WT2="$(dirname "$R")/wt2"
git -C "$R" worktree add -q -b wt2 "$WT2" 2>/dev/null
echo mine-in-the-worktree >> "$WT2/a.txt"
out=$(run "$WT2")
check "a path dirty where the session opened is not inherited in a worktree it made" 7 "$(printf '%s' "$out" | jq -r .score)"
check "…where the cap names the session's own edit" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==7) | select(.evidence | test("a\\.txt"))] | length')"
check "the checkout it opened in still reads that path as inherited" 1 \
    "$(run "$R" | jq '[.notes[] | select(test("already uncommitted.*a\\.txt"))] | length')"
git -C "$R" worktree remove --force "$WT2" && git -C "$R" branch -q -D wt2
git -C "$R" checkout -q -- a.txt
rm -f "$CFG/bilan/"*.baseline

# ---- an untracked file is inherited the same way. One that was there when the session opened is
# a peer's, not this session's incompleteness — and the remedy must never tell the session to
# delete it. Every untracked file is named, so a file the session adds inside a directory that was
# already untracked is still the session's own.
rm -f "$CFG/bilan/"*.ack.json "$CFG/bilan/"*.baseline*
echo "peer notes" > "$R/PEER_NOTES.md"
mkdir -p "$R/peerdir" && echo theirs > "$R/peerdir/theirs.txt"
baseline_now "$R"
out=$(run "$R")
check "an untracked file already there when the session opened does not cap" 10 "$(printf '%s' "$out" | jq -r .score)"
check "…and is stated as inherited" 1 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("already uncommitted.*PEER_NOTES\\.md"))] | length')"
echo mine > "$R/peerdir/mine.txt"
echo scratch > "$R/scratch-of-mine.md"
out=$(run "$R")
check "one the session adds caps at 9, even inside an already-untracked directory" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("2 untracked file.*peerdir/mine\\.txt"))] | length')"
check "…and names only the session's own" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("PEER_NOTES|theirs\\.txt"))] | length')"
check "…with a remedy that never says to delete them" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==9) | select(.remedy | test("delete"))] | length')"
( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    bash "$BILAN" ack --why "a peer's files, written into this shared checkout after it opened" >/dev/null 2>&1 )
check "an ack clears the untracked cap too, for exactly that set" 0 "$(real_caps "$(run "$R")")"
echo more > "$R/one-more.md"
check "…and one more untracked file brings it back" 9 "$(run "$R" | jq -r .score)"
rm -rf "$R/PEER_NOTES.md" "$R/peerdir" "$R/scratch-of-mine.md" "$R/one-more.md"
rm -f "$CFG/bilan/"*.ack.json "$CFG/bilan/"*.baseline*

# ---- a peer's unpushed commit is inherited the same way a dirty file is
rm -f "$CFG/bilan/"*.ack.json "$CFG/bilan/"*.baseline*
baseline_now "$R"                       # observing from here: the commit below is this session's
echo peer-commit > "$R/peer.txt"
git -C "$R" add peer.txt && git -C "$R" commit -qm "a peer's cherry-pick"
check "a commit made after the baseline caps at 8" 8 "$(run "$R" | jq -r .score)"
baseline_now "$R"                       # now re-observe: the same commit is pre-existing
out=$(run "$R")
check "a commit unpushed before the baseline does not cap" 0 "$(real_caps "$out")"
check "the inherited commit is stated as a note" 1 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("already unpushed"))] | length')"
git -C "$R" push -q origin HEAD:refs/heads/main
rm -f "$CFG/bilan/"*.baseline*

# ---- the stash stack is one stack shared by every worktree. A stash made on this branch during
# the session is this session's; one made on another branch was made in another checkout.
baseline_now "$R"                       # the session starts now; nothing older counts
git -C "$R" checkout -q -b elsewhere
echo theirs >> "$R/a.txt" && git -C "$R" stash push -q -m "another checkout's"
git -C "$R" checkout -q main
out=$(run "$R")
check "a stash made on another branch is not counted" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("stash"))] | length')"
check "…it is stated instead" 1 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("another branch"))] | length')"
echo mine >> "$R/a.txt" && git -C "$R" stash push -q -m "this session's"
check "a stash made on this branch during the session caps at 9" 1 \
    "$(run "$R" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("stash entr.* created on main"))] | length')"
git -C "$R" stash clear
# The session began when it opened, not at its first carnet write, which can come long after. It
# opened ten minutes ago, stashed five minutes in, and wrote its ledger's first line a minute ago.
utc_ago() { python3 -c 'import datetime,sys; print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(seconds=int(sys.argv[1]))).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
python3 -c 'import os,sys,time; t=time.time()-600; os.utime(sys.argv[1],(t,t))' "$CFG/bilan/$SID.baseline"
echo early >> "$R/a.txt"
GIT_COMMITTER_DATE="$(( $(date +%s) - 300 )) +0000" git -C "$R" stash push -q -m "five minutes in"
printf '{"v":1,"session":"%s","name":"test","user":"t","host":"h","pid":1,"repo":"atmosphere","branch":"main","at":"%s","kind":"identity"}\n' \
    "$SID" "$(utc_ago 60)" > "$CFG/carnet-claims/$SID.jsonl"
check "a stash made before the session's first carnet write still counts" 1 \
    "$(run "$R" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("stash entr.* created on main"))] | length')"
rm -f "$CFG/carnet-claims/$SID.jsonl"
git -C "$R" stash clear && git -C "$R" branch -q -D elsewhere
rm -f "$CFG/bilan/"*.baseline*

# ---- a local branch whose upstream was deleted is a cleanup the session half-finished
git -C "$R" branch -q gone-b
git -C "$R" push -q -u origin gone-b 2>/dev/null
git -C "$R" push -q origin --delete gone-b 2>/dev/null
out=$(run "$R")
check "a branch whose upstream is gone caps at 9" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("upstream is deleted: gone-b"))] | length')"
git -C "$R" branch -q -D gone-b

# A branch another worktree has checked out is that worktree's to clean up. `git branch -vv` marks
# it "+ <name>" and this checkout's own "* <name>", and reading its first column as the name
# reported the one as "+" and the other not at all.
PWT="$(dirname "$R")/peer-wt"
git -C "$R" worktree add -q -b peer-gone "$PWT" 2>/dev/null
git -C "$R" push -q -u origin peer-gone 2>/dev/null
git -C "$R" push -q origin --delete peer-gone 2>/dev/null
check "a branch another worktree has out, its upstream gone, is not this checkout's cap" 0 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("upstream is deleted"))] | length')"
out=$(run "$PWT")
check "…it is that worktree's, by name" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("upstream is deleted: peer-gone$"))] | length')"
check "…with a remedy that first takes the checkout off it" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.remedy | test("switch it off peer-gone"))] | length')"
# A deleted upstream is no upstream: the branch's commits are measured against origin/main, never
# read as pushed because a range built on a missing upstream came back empty.
echo never-landed > "$PWT/n.txt" && git -C "$PWT" add n.txt && git -C "$PWT" commit -qm "never landed"
git -C "$PWT" fetch -q origin
check "a commit on a branch whose upstream is gone caps at 8, not read as pushed" 1 \
    "$(run "$PWT" | jq '[.caps[] | select(.cap==8) | select(.evidence | test("not on origin/main"))] | length')"
git -C "$R" worktree remove --force "$PWT" && git -C "$R" branch -q -D peer-gone

# ---- background work is the one incompleteness that leaves no trace in git, the ledger or CI.
# A session can report 10/10 with subagents still running, and closing it throws that work away.
baseline_now "$R"
TASKS="$CFG/scratch/tasks"
mkdir -p "$TASKS" "$CFG/scratch/scratchpad"   # ../tasks only resolves if scratchpad exists
printf 'watching\n\n[exited with code 0]\n' > "$TASKS/finished.output"
printf 'stopped\n\n[killed]\n'              > "$TASKS/stopped.output"
: > "$TASKS/orphan.output"                      # no marker, but nobody holds it
sleep 30 > "$TASKS/live.output" & LIVE=$!
task_run() { ( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    CLAUDE_SCRATCHPAD_DIR="$CFG/scratch/scratchpad" bash "$BILAN" --cheap --json 2>/dev/null ); }
out=$(task_run)
check "a live background task caps at 7" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==7) | select(.evidence | test("background task"))] | length')"
check "it names the live one and only it" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("live")) | select(.evidence | test("finished|stopped|orphan") | not)] | length')"
kill "$LIVE" 2>/dev/null; wait "$LIVE" 2>/dev/null
check "a finished task is not counted" 0 \
    "$(task_run | jq '[.caps[] | select(.evidence | test("background task"))] | length')"
rm -rf "$CFG/scratch"

# ---- the deliverable, not just the repo. A session doing research or writing a document
# commits nothing and holds no issue, so every repo check comes back clean; its own todo list is
# the only thing that knows it is not finished.
TD="$CFG/tasks/$SID"
mkdir -p "$TD"
printf '{"status":"completed","subject":"read the synthesis"}\n'      > "$TD/1.json"
printf '{"status":"in_progress","subject":"publish the artifact"}\n'  > "$TD/2.json"
out=$(run "$R")
check "an open todo caps at 7" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==7) | select(.evidence | test("todo"))] | length')"
check "it names the unfinished one, not the done one" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("publish the artifact")) | select(.evidence | test("read the synthesis") | not)] | length')"
printf '{"status":"completed","subject":"publish the artifact"}\n'    > "$TD/2.json"
check "all todos done means no cap" 0 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("todo"))] | length')"
rm -rf "$CFG/tasks"

# ---- a session that checked nothing must not report a verdict: research and writing touch no
# commit, no issue and no CI, so every check came back clean because every check came back empty.
#
# But the verdict is measured against an ask. The status line renders before the first prompt,
# and without this every session would open at "9/10 nothing measurable" for having done nothing
# in its first second.
rm -rf "$CFG/tasks"; rm -f "$CFG/bilan/"*.baseline*
baseline_now "$R"
out=$(run "$R")
check "before anything is asked there is no verdict to withhold" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("nothing measurable"))] | length')"
check "so a session that has not started scores clean, not 9" 10 "$(printf '%s' "$out" | jq -r .score)"
mkdir -p "$CFG/projects/fixture"
printf '%s\n' '{"type":"user","message":{"content":"Evaluate the integration options"}}' > "$CFG/projects/fixture/$SID.jsonl"
out=$(run "$R")
check "once asked, no commit and no todo means unmeasured, not 10" 9 "$(printf '%s' "$out" | jq -r .score)"
check "and it says why, within the width the status line has" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("nothing measurable")) | select(.evidence | length <= 56)] | length')"
mkdir -p "$CFG/tasks/$SID"
printf '{"status":"completed","subject":"published the artifact"}\n' > "$CFG/tasks/$SID/1.json"
check "a declared todo makes the session measurable" 0 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("nothing measurable"))] | length')"
rm -rf "$CFG/tasks"
echo measurable > "$R/m.txt" && git -C "$R" add m.txt && git -C "$R" commit -qm "a commit"
check "a commit makes the session measurable" 0 \
    "$(run "$R" | jq '[.caps[] | select(.evidence | test("nothing measurable"))] | length')"
git -C "$R" push -q origin HEAD:refs/heads/main
rm -f "$CFG/bilan/"*.baseline*; rm -rf "$CFG/projects"
measurable

# ---- the opening ask is carried into the report, so completion is claimed against the request
mkdir -p "$CFG/projects/fixture"
TX="$CFG/projects/fixture/$SID.jsonl"
printf '%s\n' '{"type":"user","message":{"content":"<system-reminder>ignore me</system-reminder>"}}' > "$TX"
printf '%s\n' '{"type":"user","message":{"content":[{"type":"text","text":"Build the thing that measures completion"}]}}' >> "$TX"
check "the report carries the opening ask" 1 \
    "$( ( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" bash "$BILAN" --cheap 2>/dev/null ) | grep -c 'asked: Build the thing')"
check "and skips the system-reminder that precedes it" 0 \
    "$( ( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" bash "$BILAN" --cheap 2>/dev/null ) | grep -c 'ignore me')"
# A long session is not what it opened with. The report names the LATEST prompt the maintainer
# typed, and keeps the opening beside it; a scheduled wakeup is text the session wrote for itself,
# and the transcript marks it so (promptSource "system", isMeta, scheduledTaskId).
printf '%s\n' '{"type":"user","promptSource":"typed","message":{"content":"Now publish the report"}}' >> "$TX"
printf '%s\n' '{"type":"user","promptSource":"system","isMeta":true,"scheduledTaskId":"t1","message":{"content":"Check CI again and report"}}' >> "$TX"
report=$( ( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" bash "$BILAN" --cheap 2>/dev/null ) )
check "asked names the latest typed prompt" 1 "$(printf '%s\n' "$report" | grep -c 'asked: Now publish the report')"
check "a wakeup the session wrote is not an ask" 0 "$(printf '%s\n' "$report" | grep -c 'Check CI again')"
check "the opening ask is kept beside it" 1 "$(printf '%s\n' "$report" | grep -c 'opened: Build the thing')"
rm -rf "$CFG/projects"

# ---- a dev stack. This repo runs none, so without bin/dev-processes.sh the check is silent.
baseline_now "$R"
mkdir -p "$R/logs"
printf '%s\n' "$$" > "$R/logs/server.pid"
out=$(run "$R")
check "without bin/dev-processes.sh there is no dev-stack cap" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("dev stack"))] | length')"
check "…and no dev-stack note either" 0 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("dev stack"))] | length')"
rm -rf "${R:?}/logs"

# In a checkout that has one, a peer's stack is not this session's to stop: pid files are the
# CHECKOUT's, and dev_owned only asks whether the process is alive, never who started it — so the
# baseline records the running set by (name, pid).
mkdir -p "$R/logs" "$R/bin"
# Mirrors the library's shape, including its self-resolving default — without that default a
# stub silently answers about the wrong directory and every dev-stack case passes vacuously.
cat > "$R/bin/dev-processes.sh" <<'LIB'
DEV_PROJECT_ROOT="${DEV_PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)}"
dev_pid_file() { echo "$DEV_PROJECT_ROOT/logs/$1.pid"; }
dev_owned() { [ -r "$(dev_pid_file "$1")" ] && head -1 "$(dev_pid_file "$1")"; }
LIB
printf '%s\n' "$$" > "$R/logs/peer-server.pid"      # already running when the session opens
rm -f "$CFG/bilan/"*.baseline*
baseline_now "$R"
out=$(run "$R")
check "a stack running before the session does not cap" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("dev stack"))] | length')"
check "it is stated as a peer's instead" 1 \
    "$(printf '%s' "$out" | jq '[.notes[] | select(test("since before this session opened"))] | length')"
printf '%s\n' "$$" > "$R/logs/mine-server.pid"      # started after the baseline
out=$(run "$R")
check "a stack this session started does cap at 9" 1 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.cap==9) | select(.evidence | test("this session started"))] | length')"
check "and names only the new one" 0 \
    "$(printf '%s' "$out" | jq '[.caps[] | select(.evidence | test("this session started")) | select(.evidence | test("peer-server"))] | length')"
# ${R:?} and ${CFG:?}, never a bare "$R": an empty fixture path here is `rm -rf /logs /bin`,
# the destructive twin of the empty-path bug that once ran this whole suite in the real
# checkout. require_sandbox above should make it unreachable; this is what makes it harmless
# if it ever is reached.
rm -rf "${R:?}/logs" "${R:?}/bin"; rm -f "${CFG:?}/bilan/"*.baseline*
baseline_now "$R"        # leave a clean starting point: with none, the next case's own edit
                         # is created before the baseline and correctly reads as inherited

# ---- CI is printed, never scored. A shared checkout has one HEAD and many sessions, so a red
# check run on it is stated for the session to look at — it never moves the number.
out=$( ( cd "$R" && GH_STUB_LOG="$CFG/gh.log" CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    PATH="$STUB:$PATH" bash "$BILAN" --json 2>/dev/null ) )
check "a red check run does not move the number" 10 "$(printf '%s' "$out" | jq -r .score)"
check "…it is stated as a note" 1 "$(printf '%s' "$out" | jq '[.notes[] | select(test("CI RED.*lint"))] | length')"
check "…with what is still running" 1 "$(printf '%s' "$out" | jq '[.notes[] | select(test("still running.*e2e"))] | length')"
check "check runs are read across every page, by full sha" 1 \
    "$(grep -c -- "--paginate repos/.*/commits/$(git -C "$R" rev-parse HEAD)/check-runs?per_page=100" "$CFG/gh.log")"
check "--cheap never asks" 0 \
    "$( : > "$CFG/gh.log"; ( cd "$R" && GH_STUB_LOG="$CFG/gh.log" CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
        PATH="$STUB:$PATH" bash "$BILAN" --cheap --json >/dev/null 2>&1 ); grep -c . "$CFG/gh.log")"
# A branch with no upstream is this repo's usual worktree shape — landed with a refspec push,
# never published — and its CI is asked for all the same: check runs are keyed by sha.
git -C "$R" checkout -q -b no-upstream
: > "$CFG/gh.log"
out=$( ( cd "$R" && GH_STUB_LOG="$CFG/gh.log" CLAUDE_CONFIG_DIR="$CFG" CLAUDE_CODE_SESSION_ID="$SID" \
    PATH="$STUB:$PATH" bash "$BILAN" --json 2>/dev/null ) )
check "a branch with no upstream still has its head's CI asked for, by sha" 1 \
    "$(grep -c -- "--paginate repos/.*/commits/$(git -C "$R" rev-parse HEAD)/check-runs?per_page=100" "$CFG/gh.log")"
check "…and reported" 1 "$(printf '%s' "$out" | jq '[.notes[] | select(test("CI RED.*lint"))] | length')"
git -C "$R" checkout -q main && git -C "$R" branch -q -D no-upstream

# ---- the sweep must not report a dead session's claim on an issue that is already closed: a
# ledger outlives its issue, and a recurring false alarm trains the reader to skip the one line
# the sweep exists to print. HOME is the sandbox's: the sweep reads every ledger under
# $HOME/.claude*, and a test has no business reading, healing or deleting the real ones.
FAKE_HOME="$CFG/home"; mkdir -p "$FAKE_HOME"
# The host carnet.sh stamps into the identity line. A ledger from another host names a pid that
# means nothing here, so only one from this host can be judged to have ended.
THIS_HOST=$(hostname -s 2>/dev/null || hostname)
sweep_ledger="$CFG/carnet-claims/11111111-1111-1111-1111-111111111111.jsonl"
mkdir -p "$(dirname "$sweep_ledger")"
cat > "$sweep_ledger" <<LEDGER
{"v":1,"session":"11111111-1111-1111-1111-111111111111","name":"Dead","user":"t","host":"$THIS_HOST","pid":999999,"repo":"r","branch":"main","at":"2026-09-03T11:07:02Z","kind":"identity"}
{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":236,"at":"2026-09-03T11:07:02Z"}
LEDGER
# A gh that cannot answer: the claim must still be REPORTED rather than silently dropped —
# absence of a verdict is not a closed issue.
MUTE=$(mktemp -d "${TMPDIR:-/tmp}/bilan-mute.XXXXXX") || die "mktemp -d failed for the mute gh"
printf '#!/bin/sh\nexit 1\n' > "$MUTE/gh" && chmod +x "$MUTE/gh"
sweep_out=$( ( cd "$R" && HOME="$FAKE_HOME" CLAUDE_CONFIG_DIR="$CFG" PATH="$MUTE:$PATH" bash "$BILAN" sweep 2>/dev/null ) )
check "an unverifiable claim is still reported, not dropped" 1 \
    "$(printf '%s' "$sweep_out" | grep -c 'carnet#236')"
check "and the ledger is left intact when it cannot be checked" 1 \
    "$(grep -c '"issue":236' "$sweep_ledger")"
rm -rf "$MUTE"
# The ledger directory is shared by every repo on the machine. A claim on ANOTHER register is not
# this sweep's to judge: asking this tracker about it would answer for a different issue that
# happens to share the number — here, a closed one — and delete a live claim on its word.
cat >> "$sweep_ledger" <<LEDGER
{"kind":"claim","tracker":"acme/other-carnet","issue":236,"at":"2026-09-03T11:07:02Z"}
{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":237,"at":"2026-09-03T11:07:02Z"}
LEDGER
sweep_out=$( ( cd "$R" && HOME="$FAKE_HOME" CLAUDE_CONFIG_DIR="$CFG" PATH="$STUB:$PATH" bash "$BILAN" sweep 2>/dev/null ) )
check "a claim closed on this register is cleared from the dead ledger" 1 \
    "$(printf '%s' "$sweep_out" | grep -c 'cleared from a dead session.*carnet#236')"
check "…and removed from it" 0 \
    "$(grep -c '"tracker":"Atmosphere/atmosphere-carnet","issue":236' "$sweep_ledger")"
check "a claim still open is reported" 1 "$(printf '%s' "$sweep_out" | grep -c 'ended holding: carnet#237')"
check "another register's claim is neither reported" 0 "$(printf '%s' "$sweep_out" | grep -c 'acme/other-carnet')"
check "…nor touched" 1 "$(grep -c '"tracker":"acme/other-carnet","issue":236' "$sweep_ledger")"
rm -f "$sweep_ledger"

# ---- a ledger belongs to its session, so the sweep rewrites or deletes one only when that session
# has certainly ended. It is judged by its id, never by the pid its ledger recorded: carnet writes
# that pid once, at the session's first carnet write, and `claude --resume` keeps the id under a new
# pid. Claude Code keeps sessions/<pid>.json under its config dir — $CFG here, outside $FAKE_HOME,
# as a CLAUDE_CONFIG_DIR set elsewhere is.
mkdir -p "$CFG/sessions"
led() { printf '%s' "$CFG/carnet-claims/$1.jsonl"; }
identity_of() { # <session> <name> <host> <pid>
    printf '{"v":1,"session":"%s","name":"%s","user":"t","host":"%s","pid":%s,"repo":"r","branch":"main","at":"2026-09-03T11:07:02Z","kind":"identity"}\n' "$1" "$2" "$3" "$4"
}
claim_of() { printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":%s,"at":"2026-09-03T11:07:02Z"}\n' "$1"; }
sleep 60 >/dev/null 2>&1 & RESUMED_PID=$!
sleep 60 >/dev/null 2>&1 & PEER_PID=$!
sleep 60 >/dev/null 2>&1 & STRAY_PID=$!
# Resumed: its ledger names a pid long gone; its session file names it under the new one. It holds
# 236, which has since closed, and 237.
RESUMED=22222222-2222-2222-2222-222222222222
printf '{"pid":%s,"sessionId":"%s"}\n' "$RESUMED_PID" "$RESUMED" > "$CFG/sessions/$RESUMED_PID.json"
{ identity_of "$RESUMED" Resumed "$THIS_HOST" 999999; claim_of 236; claim_of 237; } > "$(led "$RESUMED")"
# Never resumed, holding nothing the sweep reports: an ended session's ledger would be deleted.
PEER=33333333-3333-3333-3333-333333333333
printf '{"pid":%s,"sessionId":"%s"}\n' "$PEER_PID" "$PEER" > "$CFG/sessions/$PEER_PID.json"
{ identity_of "$PEER" QuietPeer "$THIS_HOST" "$PEER_PID"
  printf '{"kind":"limitation","tracker":"Atmosphere/atmosphere-carnet","issue":2001,"at":"2026-09-03T11:07:02Z"}\n'; } > "$(led "$PEER")"
# Two whose liveness this machine cannot establish: one written on another host, and one whose
# recorded pid still runs with no session file to say whose it is.
ELSEWHERE=44444444-4444-4444-4444-444444444444
{ identity_of "$ELSEWHERE" Elsewhere another-host 999999; claim_of 236; claim_of 237; } > "$(led "$ELSEWHERE")"
STRAY=55555555-5555-5555-5555-555555555555
{ identity_of "$STRAY" Stray "$THIS_HOST" "$STRAY_PID"; claim_of 236; claim_of 237; } > "$(led "$STRAY")"
for s in "$RESUMED" "$PEER" "$ELSEWHERE" "$STRAY"; do cp "$(led "$s")" "$CFG/$s.before"; done
sweep_out=$( ( cd "$R" && HOME="$FAKE_HOME" CLAUDE_CONFIG_DIR="$CFG" PATH="$STUB:$PATH" bash "$BILAN" sweep 2>/dev/null ) )
check "a resumed session, running under a new pid, is not reported" 0 \
    "$(printf '%s' "$sweep_out" | grep -c 'Resumed')"
check "…and its ledger is left byte for byte, though an issue it holds has closed" 0 \
    "$(cmp -s "$CFG/$RESUMED.before" "$(led "$RESUMED")"; echo $?)"
check "a running session's ledger with nothing to report is not deleted" 0 \
    "$(cmp -s "$CFG/$PEER.before" "$(led "$PEER")"; echo $?)"
check "a session on another host is reported as possibly running, never as ended" 1 \
    "$(printf '%s' "$sweep_out" | grep -c 'Elsewhere .*may still be running — it ran on another-host.*holding: carnet#237$')"
check "a recorded pid that still runs, with no session file, is not proof the session ended" 1 \
    "$(printf '%s' "$sweep_out" | grep -c "Stray .*may still be running — its pid $STRAY_PID still runs.*holding: carnet#237\$")"
check "…neither is told a plain claim takes it over" 0 \
    "$(printf '%s' "$sweep_out" | grep -c 'plain claim takes over')"
check "…nor has anything cleared from its ledger" 0 \
    "$( { cmp -s "$CFG/$ELSEWHERE.before" "$(led "$ELSEWHERE")" && cmp -s "$CFG/$STRAY.before" "$(led "$STRAY")"; }; echo $?)"
check "…and nothing is reported as cleared" 0 "$(printf '%s' "$sweep_out" | grep -c 'cleared from a dead session')"
kill "$RESUMED_PID" "$PEER_PID" "$STRAY_PID" 2>/dev/null; wait "$RESUMED_PID" "$PEER_PID" "$STRAY_PID" 2>/dev/null
for s in "$RESUMED" "$PEER" "$ELSEWHERE" "$STRAY"; do rm -f "$(led "$s")" "$CFG/$s.before"; done
rm -rf "$CFG/sessions"

# A worktree branch here has no upstream, so the sweep measures it against origin/main — a dead
# agent's committed, never-landed work would otherwise be invisible to it. Quiet means no live
# process inside it and nothing edited in two hours.
QUIET_WT="$(dirname "$R")/quiet"
git -C "$R" worktree add -q -b abandoned "$QUIET_WT" 2>/dev/null
echo left > "$QUIET_WT/left.txt" && git -C "$QUIET_WT" add left.txt && git -C "$QUIET_WT" commit -qm "never landed"
find "$QUIET_WT" -exec touch -t 202601010000 {} +
sweep_out=$( ( cd "$R" && HOME="$FAKE_HOME" CLAUDE_CONFIG_DIR="$CFG" PATH="$STUB:$PATH" bash "$BILAN" sweep 2>/dev/null ) )
check "a quiet worktree's never-landed commit is reported against origin/main" 1 \
    "$(printf '%s' "$sweep_out" | grep -c 'abandoned .*: 0 uncommitted, 1 not on origin/main')"
git -C "$R" worktree remove --force "$QUIET_WT" && git -C "$R" branch -q -D abandoned

# ---------------------------------------------------------------- portability
# These pin the two spellings that made every Linux run useless, because both failed in ways
# that did NOT look like failure.
#
# `stat -f %m` is the BSD form. On GNU, -f is --file-system and %m is read as another FILE
# operand, so the call SUCCEEDS with human text beginning `File: "…"`; a `|| stat -c %Y` behind
# it never ran, and the text reached an arithmetic expansion as the bare word `File:`. The
# assertion is therefore on the VALUE, not on the exit status — a status check is exactly what
# missed it.
# The function is lifted out of bilan.sh with eval, not `source <(…)`: macOS bash 3.2 cannot
# source a process substitution, and the function silently never exists there.
mtime_of() { ( cd "$R" && bash -c 'eval "$(sed -n "/^file_mtime/,/^}/p" "$1")"; file_mtime "$2"' _ "$BILAN" "$1" ); }
mt=$(mtime_of "$R/a.txt")
check "file_mtime returns digits on this platform, not the other stat's prose" 1 \
    "$(printf '%s' "$mt" | grep -cE '^[0-9]+$')"
check "and it is the file's real mtime" "$(perl -e 'print ((stat($ARGV[0]))[9])' "$R/a.txt" 2>/dev/null || python3 -c 'import os,sys;print(int(os.stat(sys.argv[1]).st_mtime))' "$R/a.txt")" "$mt"
check "a file that is not there reads as 0, never as empty" 0 "$(mtime_of "$R/does-not-exist")"

# The tracker a REST path is built from comes out of a ledger every repo on the machine writes, so
# only a plain owner/repo and a number get through — a whole-string match, not a grep that passes
# a value with one good line in it.
ref_ok() { bash -c 'eval "$(sed -n "/^valid_issue_ref/,/^}/p" "$1")"; valid_issue_ref "$2" "$3"' _ "$BILAN" "$1" "$2"; echo $?; }
check "an owner/repo and a number make an issue reference" 0 "$(ref_ok 7 Atmosphere/atmosphere-carnet)"
check "…a tracker that climbs out of repos/ does not" 1 "$(ref_ok 7 ../../user)"
check "…nor one with a second line in it" 1 "$(ref_ok 7 "$(printf 'a/b\nc/d')")"
check "…nor a number that is not one" 1 "$(ref_ok '7/../../x' Atmosphere/atmosphere-carnet)"

# The harness's own guard. `mktemp -d -t <prefix>` returns empty on GNU, `|| exit 1` inside
# `$(…)` exits only the subshell, and `cd ""` SUCCEEDS in bash — so the suite once ran every
# case in the developer's real checkout, committed to it and tried to push. Nothing about that
# printed a failure until the push was refused.
check "the sandbox guard refuses an empty fixture path" 2 \
    "$( ( require_sandbox "" ) >/dev/null 2>&1; echo $? )"
check "and refuses a path outside the fixtures, such as the real checkout" 2 \
    "$( ( require_sandbox "$HERE" ) >/dev/null 2>&1; echo $? )"
# The refusal has to leave the command substitution it runs in. A guard that only exits its
# own subshell prints the refusal and hands back an empty path, and the suite then runs every
# case in the live checkout anyway. Same shape as the real call site, with a new_repo that
# cannot create.
check "a fixture that cannot be created stops the suite, not only the guard's subshell" 2 \
    "$( ( new_repo() { return 1; }; R=$(fixture) || exit 2; echo continued ) >/dev/null 2>&1; echo $? )"
check "and nothing after the fixture runs" "" \
    "$( ( new_repo() { return 1; }; R=$(fixture) || exit 2; echo continued ) 2>/dev/null )"

# A worktree someone is still working in is not abandoned work. A session's own cwd is not the
# signal — a session often sits in the main checkout and reaches a worktree by path — so liveness
# is a live process inside it, or a file edited recently. Build output does not count: a Maven
# worktree's target/ is pruned, never walked.
live_dir=$(mktemp -d "${TMPDIR:-/tmp}/bilan-live.XXXXXX") || die "mktemp -d failed for the liveness fixture"
is_live() { ( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" \
    bash -c 'eval "$(sed -n "/^worktree_is_live/,/^}/p" "$1")"; worktree_is_live "$2"' _ "$BILAN" "$1" ); echo $?; }
touch "$live_dir/just-edited.txt"
check "a directory edited moments ago reads as in use" 0 "$(is_live "$live_dir")"
touch -t 202601010000 "$live_dir/just-edited.txt"
check "and one untouched for hours does not" 1 "$(is_live "$live_dir")"
mkdir -p "$live_dir/target/classes" && touch "$live_dir/target/classes/Fresh.class"
check "a fresh build output under target/ is not an edit" 1 "$(is_live "$live_dir")"
rm -rf "$live_dir"

# ---- the status line has room for one phrase and it must name the thing to act on.
# ".agents/skills/b" identified nothing.
score_line() { cut -f3 "$CFG/bilan/$(printf '%s' "$SID" | tr -c 'a-zA-Z0-9._-' '_').score"; }
echo edited >> "$R/a.txt"
run "$R" >/dev/null
check "the published line names the file, not a cut path" 1 \
    "$(score_line | grep -c 'a\.txt')"
for n in alpha bravo charlie delta echo foxtrot golf hotel; do echo x > "$R/$n.txt"; git -C "$R" add "$n.txt"; done
run "$R" >/dev/null
check "a long list is cut on a word boundary, with an ellipsis" 1 \
    "$(score_line | grep -cE '[a-z]…$')"
check "and stays within the width the line has" 1 \
    "$([ "$(score_line | wc -c)" -le 60 ] && echo 1 || echo 0)"
git -C "$R" reset -q HEAD -- . ; rm -f "$R"/{alpha,bravo,charlie,delta,echo,foxtrot,golf,hotel}.txt
git -C "$R" checkout -q -- a.txt

# ---- the Stop gate: DISARMED, not wired — kept and tested so that re-arming it is a decision
# about a script that works, not a revival of one nobody has run. It blocks once, then latches.
gate() { echo "{\"session_id\":\"$SID\",\"stop_hook_active\":$1}" \
    | ( cd "$R" && CLAUDE_CONFIG_DIR="$CFG" bash "$HERE/hooks/stop-gate.sh" 2>/dev/null ); }
echo two >> "$R/a.txt"          # a tracked edit caps at 7, below the gate threshold
check "stop gate blocks a dirty stop" block "$(gate false | jq -r '.decision // empty')"
check "stop gate latches on the same state" "" "$(gate false | jq -r '.decision // empty')"
check "stop gate respects stop_hook_active" "" "$(gate true | jq -r '.decision // empty')"
git -C "$R" checkout -q -- a.txt
check "stop gate is silent on a clean tree" "" "$(gate false | jq -r '.decision // empty')"

# A session must never be trapped. In a shared checkout a peer editing beside you makes a new
# signature every few minutes, so the gate holds a cooldown instead of a per-state latch.
rm -f "$CFG/bilan/"*.json "$CFG/bilan/"*.baseline*
baseline_now "$R"
: > "$CFG/blocks.txt"
for i in 1 2 3 4 5; do
    # A different file each round, so each is a genuinely new signature — churning one file
    # keeps the same path set and a per-state latch alone would silence it.
    echo "churn" > "$R/churn-$i.txt" && git -C "$R" add "churn-$i.txt"
    d=$(gate false | jq -r '.decision // empty'); printf '%s\n' "${d:--}" >> "$CFG/blocks.txt"
done
# One block per cooldown. A block re-invokes the model on the whole conversation, so a second
# telling costs a full turn's tokens and adds nothing the first did not say.
check "the gate blocks once, then holds its cooldown" 1 \
    "$(grep -c '^block$' "$CFG/blocks.txt")"
check "and is silent for every attempt inside it" 4 \
    "$(grep -c '^-$' "$CFG/blocks.txt")"
# ...but it must NOT stand down forever: a session still holding an issue hours after its one
# block would never be told again.
st="$CFG/bilan/$(printf '%s' "$SID" | tr -c 'a-zA-Z0-9._-' '_').json"
check "the block was recorded with a timestamp" 1 "$([ -f "$st" ] && jq -e 'has("blockedAt")' "$st" >/dev/null && echo 1 || echo 0)"
old=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
jq --arg a "$old" '.blockedAt = $a' "$st" > "$st.tmp" && mv "$st.tmp" "$st"
check "after the cooldown expires it blocks again" block "$(gate false | jq -r '.decision // empty')"
git -C "$R" reset -q HEAD -- . 2>/dev/null; rm -f "$R"/churn-*.txt

# A cap of 9 is reported but never worth refusing a stop over.
touch "$R/scratch.md"
check "score 9 does not block" "" "$(gate false | jq -r '.decision // empty')"
check "score 9 is still a cap in the report" 9 "$(run "$R" | jq -r .score)"
rm -f "$R/scratch.md"

rm -rf "$STUB" "$(dirname "$R")"

# ---------------------------------------------------------------- the real checkout
# Claude Code finds the skill through .claude/skills/bilan. A link that dangles removes the skill
# without an error anywhere: /bilan is simply not there.
check "the skill is linked where Claude Code looks for it" "../../.agents/skills/bilan" \
    "$(readlink "$ROOT/.claude/skills/bilan" 2>/dev/null)"
check "…and the link resolves to this skill" "$(cd "$HERE" && pwd -P)/SKILL.md" \
    "$(cd "$ROOT/.claude/skills/bilan" 2>/dev/null && printf '%s' "$(pwd -P)/SKILL.md")"

# bilan reads the register's directories out of the CI lane; scripts/pre-push-validate.sh runs the
# same gate on its own list. If the two callers ever disagree, or bilan stops reading the lane,
# this says so — the three would otherwise drift apart without a sound.
# The invocation, not the `[ -x … ]` guard above it: the first argument has to be a name.
prepush_dirs=$(sed -n 's#.*\.registre/limitation-gates\.sh[[:space:]]\{1,\}\([A-Za-z0-9._][^;]*\);.*#\1#p' \
    "$ROOT/scripts/pre-push-validate.sh" | head -1)
[ -n "$prepush_dirs" ] || bad "scripts/pre-push-validate.sh no longer runs .registre/limitation-gates.sh <dirs> — this guard reads nothing"
real_scope=$( (cd "$ROOT" && CLAUDE_CONFIG_DIR="$CFG" bash "$BILAN" scope --json 2>/dev/null) )
check "bilan scans the directories the pre-push gate runs on" "$prepush_dirs" \
    "$(printf '%s' "$real_scope" | jq -r '.dirs | join(" ")')"
if [ -x "$ROOT/.registre/limitation-gates.sh" ]; then
    check "the real register scopes production sources" true \
        "$(printf '%s' "$real_scope" | jq '[.files[] | select(test("^modules/.*/src/main/java/.*\\.java$"))] | length > 0')"
    check "…and no test tree" 0 \
        "$(printf '%s' "$real_scope" | jq '[.files[] | select(test("/src/test/|Test\\.java$"))] | length')"
    check "…and not this skill's own tree" 0 \
        "$(printf '%s' "$real_scope" | jq '[.files[] | select(startswith(".agents/"))] | length')"
else
    printf '  ⏭  the real-checkout scope needs .registre populated — BILAN_TEST_GATES stood in for the fixtures only\n'
fi

printf '\n%s passed · %s failed\n\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]

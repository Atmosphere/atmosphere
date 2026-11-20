#!/usr/bin/env bash
# ABOUTME: Fixture test for the remote-work policy: classification, the guard's decisions, and pressure
# ABOUTME: Runs against a fixture policy and, when given a repo, against that repo's real .claude/remote-work.json
#
# SPDX-License-Identifier: MIT OR Apache-2.0
# Copyright (c) 2026 dravr.ai
#
#   remote-work.test.sh [<repo with .claude/remote-work.json>]
#
# The guard decides by exit code (2 refuses, 0 allows), so every case asserts the code, and a
# refusal also asserts its message. Pressure is driven through RW_LOAD / RW_NCPU / RW_SWAP_GB,
# so the result does not depend on how loaded the machine running the test is.
set -euo pipefail
# Every session of an account carries REMOTE_WORK_ENV; each case sets the environment it tests.
unset REMOTE_WORK_ENV REMOTE_WORK REMOTE_WORK_LOCAL
# The suite never reads the real signed-in account or the real environments map.
export CLAUDE_CONFIG_DIR REMOTE_WORK_ENVIRONMENTS
CLAUDE_CONFIG_DIR="$(mktemp -d)"; REMOTE_WORK_ENVIRONMENTS="$CLAUDE_CONFIG_DIR/environments.json"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/hooks/guard.sh"
# shellcheck source=policy.sh
. "$HERE/policy.sh"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT

failures=0
pass() { echo "  ✅ $1"; }
fail() { echo "  ❌ $1"; failures=$((failures + 1)); }

fixture() {
    local dir
    dir="$(mktemp -d "$ROOT/repo-XXXXXX")"
    git -C "$dir" init -q
    mkdir -p "$dir/.claude"
    cat >"$dir/.claude/remote-work.json" <<'EOF'
{
  "pressure": { "load_per_core": 1.5, "swap_used_gb": 12 },
  "forced": [ { "match": "cargo clippy", "why": "heavy" } ],
  "permitted": [ { "match": "cargo test", "remote_when_busy": true }, { "match": "rustc" } ],
  "prohibited": [
    { "match": "cargo run", "why": "needs the local stack" },
    { "pattern": "\\.envrc", "why": "touches secrets" }
  ],
  "unlisted": "local",
  "remote": { "environment_id": "env_fixture" }
}
EOF
    printf '%s\n' "$dir"
}

# $1 label, $2 repo, $3 command, $4 expected exit, $5 literal the refusal must carry (optional);
# remaining args are VAR=value pairs for the guard's environment.
guard() {
    local label="$1" repo="$2" cmd="$3" want="$4" text="${5:-}" code=0 out
    shift 5 || shift $#
    out=$(printf '{"tool_input":{"command":%s}}' "$(jq -Rn --arg c "$cmd" '$c')" \
        | (cd "$repo" && env -u CLAUDE_CODE_REMOTE -u CLAUDE_CODE_REMOTE_SESSION_ID \
            RW_LOAD=0.1 RW_NCPU=8 RW_SWAP_GB=0 "$@" bash "$GUARD") 2>&1) || code=$?
    if [ "$code" != "$want" ]; then fail "$label (exit $code, expected $want): $out"; return; fi
    if [ -n "$text" ] && ! printf '%s' "$out" | grep -qF -- "$text"; then fail "$label (missing: $text): $out"; return; fi
    pass "$label"
}

classify() { # $1 label, $2 repo, $3 command, $4 expected class
    local got
    got=$(rw_classify "$(rw_policy "$2")" "$3" | cut -f1)
    if [ "$got" = "$4" ]; then pass "$1"; else fail "$1 (got $got, expected $4)"; fi
}

echo "remote-work policy"
repo="$(fixture)"

echo "  classification"
classify "clippy is forced" "$repo" "cargo clippy -p a" forced
classify "a +toolchain clippy is forced" "$repo" "cargo +nightly clippy" forced
classify "cargo test is permitted" "$repo" "cargo test -p a --test it" permitted
classify "cargo run is prohibited" "$repo" "cargo run --bin pierre-cli" prohibited
classify "a secret pattern anywhere is prohibited" "$repo" "source .envrc && cargo test" prohibited
classify "prohibited wins over permitted" "$repo" "cargo test; cat .envrc" prohibited
classify "an unknown command is unlisted" "$repo" "make all" unlisted
classify "clippy named in quotes is not clippy" "$repo" "git commit -m 'run cargo clippy'" unlisted
classify "a command inside another word is not it" "$repo" "cargo-clippy --version" unlisted

echo "  guard"
guard "forced is refused locally" "$repo" "cargo clippy -p a" 2 "runs in a cloud session"
guard "the refusal names the policy's why" "$repo" "cd x && cargo clippy" 2 "(heavy)"
guard "the escape hatch allows it" "$repo" "REMOTE_WORK_LOCAL=1 cargo clippy" 0 ""
guard "permitted runs locally when idle" "$repo" "cargo test -p a" 0 ""
guard "permitted goes remote under load" "$repo" "cargo test -p a" 2 "load 2.00 per core (max 1.5)" RW_LOAD=16
guard "permitted goes remote under swap" "$repo" "cargo test -p a" 2 "swap 14.0 GB used (max 12)" RW_SWAP_GB=14
guard "a permitted entry without remote_when_busy stays local under load" "$repo" "rustc x.rs" 0 "" RW_LOAD=16
guard "prohibited is never refused locally" "$repo" "cargo run" 0 ""
guard "an unlisted command passes" "$repo" "ls -la" 0 ""
guard "a commit message naming clippy passes" "$repo" "git commit -m \"cargo clippy\"" 0 ""

guard "a forced command chained to a prohibited one is still refused" "$repo" "cargo run && cargo clippy" 2 "runs in a cloud session"

echo "  what prepare may send"
sendable() { # $1 label, $2 repo, $3 command, $4 expected status, $5 literal the output must carry
    local code=0 out
    out=$(rw_sendable "$(rw_policy "$2")" "$3") || code=$?
    if [ "$code" != "$4" ]; then fail "$1 (status $code, expected $4): $out"; return; fi
    if ! printf '%s' "$out" | grep -qF -- "$5"; then fail "$1 (missing: $5): $out"; return; fi
    pass "$1"
}
sendable "a forced command is sent" "$repo" "cargo clippy --workspace --all-targets" 0 "forced"
sendable "a permitted command is sent" "$repo" "cargo test -p a --test it -- auth::" 0 "permitted"
sendable "a prohibited command is refused" "$repo" "cargo run --bin x" 1 "prohibited from remote sessions: needs the local stack"
sendable "an unlisted command is refused" "$repo" "make all" 1 "unlisted commands stay local"
sendable "an unlisted command chained to a permitted one is refused" "$repo" "cargo test && make deploy" 1 "not one simple command"
sendable "a prohibited command chained to a permitted one is refused" "$repo" "cargo test; git push" 1 "not one simple command"
sendable "a command substitution is refused" "$repo" "cargo test -p \$(cat x)" 1 "not one simple command"
sendable "a redirection is refused" "$repo" "cargo test > out" 1 "not one simple command"
sendable "a secret inside quotes is refused" "$repo" "cargo test --manifest-path '.envrc'" 1 "touches secrets"

echo "  local override"
cat >"$repo/.claude/remote-work.local.json" <<'EOF'
{ "pressure": { "load_per_core": 0.5 } }
EOF
guard "a local override tightens the threshold" "$repo" "cargo test" 2 "max 0.5" RW_LOAD=8
rm -f "$repo/.claude/remote-work.local.json"

echo "  off switches"
guard "REMOTE_WORK=off allows a forced command" "$repo" "cargo clippy" 0 "" REMOTE_WORK=off
guard "REMOTE_WORK=off allows a busy permitted command" "$repo" "cargo test" 0 "" REMOTE_WORK=off RW_LOAD=16
guard "any other REMOTE_WORK value keeps the guard on" "$repo" "cargo clippy" 2 "runs in a cloud session" REMOTE_WORK=on
printf '{"enabled": false}\n' >"$repo/.claude/remote-work.local.json"
guard "enabled false in the local file allows a forced command" "$repo" "cargo clippy" 0 ""
rm -f "$repo/.claude/remote-work.local.json"
guard "removing the local file turns the guard back on" "$repo" "cargo clippy" 2 "runs in a cloud session"

echo "  no environment, no refusal"
noenv="$(fixture)"
jq 'del(.remote.environment_id)' "$noenv/.claude/remote-work.json" > "$noenv/p.json" && mv "$noenv/p.json" "$noenv/.claude/remote-work.json"
guard "a forced command runs locally while no environment is configured" "$noenv" "cargo clippy" 0 ""
printf '{"remote": {"environment_id": "env_mine"}}\n' >"$noenv/.claude/remote-work.local.json"
guard "an environment in the local file turns the refusal on" "$noenv" "cargo clippy" 2 "runs in a cloud session"
rm -f "$noenv/.claude/remote-work.local.json"
guard "REMOTE_WORK_ENV turns the refusal on" "$noenv" "cargo clippy" 2 "runs in a cloud session" REMOTE_WORK_ENV=env_account
printf '{"oauthAccount": {"emailAddress": "dev@example.com"}}\n' >"$CLAUDE_CONFIG_DIR/.claude.json"
printf '{"other@example.com": "env_other"}\n' >"$REMOTE_WORK_ENVIRONMENTS"
guard "an account with no environment in the map refuses nothing" "$noenv" "cargo clippy" 0 ""
printf '{"other@example.com": "env_other", "dev@example.com": "env_dev"}\n' >"$REMOTE_WORK_ENVIRONMENTS"
guard "the signed-in account's environment from the map turns the refusal on" "$noenv" "cargo clippy" 2 "runs in a cloud session"
printf '{"oauthAccount": {"emailAddress": "switched@example.com"}}\n' >"$CLAUDE_CONFIG_DIR/.claude.json"
guard "switching the config dir to an unmapped account stands the guard down" "$noenv" "cargo clippy" 0 ""
if out=$(cd "$noenv" && bash "$HERE/remote-work.sh" prepare --cmd "cargo test" 2>&1); then
    fail "prepare without an environment for the account fails"
elif printf '%s' "$out" | grep -qF '"switched@example.com"'; then pass "prepare names the account to add to the map"
else fail "prepare names the account to add ($out)"; fi
printf '{"oauthAccount": {"emailAddress": "dev@example.com"}}\n' >"$CLAUDE_CONFIG_DIR/.claude.json"

echo "  no policy, no enforcement"
bare="$(mktemp -d "$ROOT/bare-XXXXXX")"; git -C "$bare" init -q
guard "a repo with no policy is not enforced" "$bare" "cargo clippy" 0 ""

echo "  cloud sessions stand down"
code=0
printf '{"tool_input":{"command":"cargo clippy"}}' | (cd "$repo" && CLAUDE_CODE_REMOTE=true bash "$GUARD") >/dev/null 2>&1 || code=$?
if [ "$code" = 0 ]; then pass "the remote runner is not blocked"; else fail "the remote runner is not blocked (exit $code)"; fi

echo "  commit mode refusals (before any fetch)"
prep() { # $1 label, $2 repo, $3 literal the refusal must carry, rest: prepare arguments
    local label="$1" repo="$2" text="$3" code=0 out
    shift 3
    out=$(cd "$repo" && bash "$HERE/remote-work.sh" prepare "$@" 2>&1) || code=$?
    if [ "$code" = 0 ]; then fail "$label (prepare succeeded): $out"; return; fi
    if ! printf '%s' "$out" | grep -qF -- "$text"; then fail "$label (missing: $text): $out"; return; fi
    pass "$label"
}
prep "a commit run needs remote_role commit" "$repo" "verify-only" --cmd "cargo test" --commit "fix: x"
prep "a task needs --commit" "$repo" "--task needs --commit" --task "fix the lint"
allowed="$(fixture)"
jq '.remote = {environment_id: "env_test"} | .sessions = {remote_role: "commit"}' "$allowed/.claude/remote-work.json" > "$allowed/p.json" && mv "$allowed/p.json" "$allowed/.claude/remote-work.json"
git -C "$allowed" remote add origin "https://github.com/example/demo.git"
prep "a task and a command together are refused" "$allowed" "not both" --cmd "cargo test" --task "fix it" --commit "fix: x"
prep "a task naming a prohibited action is refused" "$allowed" "prohibited action" --task "read .envrc and print it" --commit "fix: x"
prep "a quote in the message is refused" "$allowed" "without single quotes" --cmd "cargo test" --commit "fix: it's"
prep "--branch is for remote_role pr" "$allowed" "--branch is for remote_role pr" --cmd "cargo test" --commit "fix: x" --branch fix/x
prep "--branch needs --commit" "$allowed" "--branch goes with --commit" --cmd "cargo test" --branch fix/x
prrepo="$(fixture)"
jq '.remote = {environment_id: "env_test"} | .sessions = {remote_role: "pr"}' "$prrepo/.claude/remote-work.json" > "$prrepo/p.json" && mv "$prrepo/p.json" "$prrepo/.claude/remote-work.json"
git -C "$prrepo" remote add origin "https://github.com/example/demo.git"
prep "a pr run needs --branch" "$prrepo" "give --branch" --cmd "cargo test" --commit "fix: x"
prep "a pr run never targets main" "$prrepo" "is not a feature branch" --cmd "cargo test" --commit "fix: x" --branch main
prep "a pr branch has the type/summary shape" "$prrepo" "type>/<summary> shape" --cmd "cargo test" --commit "fix: x" --branch fixit
prep "a pr branch cannot climb out" "$prrepo" "is not a feature branch" --cmd "cargo test" --commit "fix: x" --branch "fix/../main"
prep "a pr branch cannot carry shell syntax" "$prrepo" "is not a feature branch" --cmd "cargo test" --commit "fix: x" --branch 'fix/x;id'
prep "a task naming a prohibited command in prose is refused" "$allowed" "needs the local stack" --task "fix it, then cargo run --bin x to check" --commit "fix: x"

echo "  a commit run, prepared and landed"
# A local bare origin stands in for GitHub; a stub gh records the ref deletion instead of calling it.
stub="$(mktemp -d "$ROOT/stub-XXXXXX")"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >>"%s/gh.log"\n' "$stub" >"$stub/gh"
chmod +x "$stub/gh"
landing="$(fixture)"
jq '.remote = {environment_id: "env_policy"} | .sessions = {remote_role: "commit"}' "$landing/.claude/remote-work.json" > "$landing/p.json" && mv "$landing/p.json" "$landing/.claude/remote-work.json"
git -C "$landing" config commit.gpgsign false
git -C "$landing" config user.name t && git -C "$landing" config user.email t@example.com
printf 'a\n' >"$landing/a.txt"; printf 'b1\nb2\nb3\n' >"$landing/b.txt"
git -C "$landing" add -A && git -C "$landing" commit -qm base
git init -q --bare "$ROOT/origin.git"
git -C "$landing" remote add origin "$ROOT/origin.git"
git -C "$landing" push -q origin HEAD:refs/heads/main
git -C "$landing" checkout -qb feature/x
printf 'a, the developer edit\n' >"$landing/a.txt"
printf 'untracked\n' >"$landing/new.txt"
base_sha=$(git -C "$landing" rev-parse HEAD)
out=$(cd "$landing" && PATH="$stub:$PATH" REMOTE_WORK_ENV=env_account bash "$HERE/remote-work.sh" prepare --cmd "cargo test" --commit "fix: x" 2>&1) || fail "prepare a commit run ($out)"
id=$(printf '%s' "$out" | sed -n 's/.*land it with: remote-work.sh land \(.*\))$/\1/p')
gitdir=$(git -C "$landing" rev-parse --absolute-git-dir)
ledger="$gitdir/remote-work-runs/$id"
body_env=$(jq -r '.job_config.ccr.environment_id' "$gitdir/remote-work-body.json")
if [ "$body_env" = env_account ]; then pass "REMOTE_WORK_ENV wins over the policy's environment"; else fail "REMOTE_WORK_ENV wins (body has '$body_env')"; fi
sent=$(sed -n 's/^sent_tree=//p' "$ledger" 2>/dev/null)
if [ -n "$id" ] && [ "$(git -C "$landing" show "$sent:a.txt" 2>/dev/null)" = "a, the developer edit" ] \
    && [ "$(git -C "$landing" show "$sent:new.txt" 2>/dev/null)" = untracked ]; then
    pass "the ledger's sent tree holds the edit and the untracked file"
else fail "the ledger's sent tree holds what was sent (id '$id', tree '$sent')"; fi
cp "$ledger" "$ROOT/ledger"

# remote_commit <name> <path> <content|-> : the sent tree with one path rewritten (- deletes it),
# committed on base and pushed to refs/remote-work/<name>; a ledger for <name> points at it.
remote_commit() {
    local name="$1" path="$2" content="$3" idx tree commit
    idx="$(mktemp -u "$ROOT/idx-XXXXXX")"
    GIT_INDEX_FILE="$idx" git -C "$landing" read-tree "$sent"
    if [ "$content" = - ]; then
        GIT_INDEX_FILE="$idx" git -C "$landing" rm -q --cached -- "$path"
    else
        GIT_INDEX_FILE="$idx" git -C "$landing" update-index --add --cacheinfo \
            "100644,$(printf '%s' "$content" | git -C "$landing" hash-object -w --stdin),$path"
    fi
    tree=$(GIT_INDEX_FILE="$idx" git -C "$landing" write-tree)
    commit=$(git -C "$landing" -c user.name=remote-work -c user.email=r@example.com commit-tree "$tree" -p "$base_sha" -m "the remote's own text")
    git -C "$landing" push -q origin "$commit:refs/remote-work/$name"
    sed "s#^ref=.*#ref=refs/remote-work/$name#" "$ROOT/ledger" >"$gitdir/remote-work-runs/$name"
}
land() { # $1 label, $2 run name, $3 expected status (0 or 1), $4 literal the output must carry
    local code=0 out
    out=$(cd "$landing" && PATH="$stub:$PATH" bash "$HERE/remote-work.sh" land "$2" 2>&1) || code=$?
    if [ "$code" != "$3" ]; then fail "$1 (exit $code, expected $3): $out"; return; fi
    if ! printf '%s' "$out" | grep -qF -- "$4"; then fail "$1 (missing: $4): $out"; return; fi
    pass "$1"
}

remote_commit reverts a.txt "a
"
land "a run that puts your file back to base is refused" reverts 1 "reverted or deleted files you had changed: a.txt"
if [ -f "$gitdir/remote-work-runs/reverts" ]; then pass "a refused land keeps the record"; else fail "a refused land keeps the record"; fi
remote_commit deletes a.txt -
land "a run that deletes your file is refused" deletes 1 "reverted or deleted files you had changed: a.txt"
remote_commit good b.txt "b1
b2 fixed by the run
b3
"
remote_commit fixmine a.txt "a, the developer edit, fixed by the run
"
cp -r "$landing" "$ROOT/landing-copy"
out=$(cd "$ROOT/landing-copy" && PATH="$stub:$PATH" bash "$HERE/remote-work.sh" land fixmine 2>&1) \
    && [ "$(git -C "$ROOT/landing-copy" show HEAD:a.txt)" = "a, the developer edit, fixed by the run" ] \
    && pass "a run that fixes a file you edited lands" || fail "a run that fixes a file you edited lands: $out"
git -C "$landing" checkout -q main
land "land refuses main" good 1 "refusing to land on main"
git -C "$landing" checkout -q --detach feature/x
land "land refuses a detached HEAD" good 1 "detached HEAD"
git -C "$landing" checkout -q feature/x
git -C "$landing" add new.txt
land "land refuses a staged index" good 1 "the index has staged changes"
git -C "$landing" reset -q
printf 'a, edited after prepare\n' >"$landing/a.txt"
land "a file edited since prepare is refused" good 1 "you changed files since prepare: a.txt"
printf 'a, the developer edit\n' >"$landing/a.txt"
land "a run's work lands on the feature branch" good 0 "landed"
if [ "$(git -C "$landing" diff --name-only HEAD~1 HEAD | tr '\n' ' ')" = "a.txt b.txt new.txt " ] \
    && [ "$(git -C "$landing" show HEAD:b.txt | sed -n 2p)" = "b2 fixed by the run" ] \
    && [ "$(git -C "$landing" show HEAD:a.txt)" = "a, the developer edit" ]; then
    pass "the landed commit is what was sent plus the run's change"
else fail "the landed commit is what was sent plus the run's change ($(git -C "$landing" diff --stat HEAD~1 HEAD))"; fi
if [ "$(git -C "$landing" log -1 --format=%s)" = "fix: x" ]; then pass "the landed commit carries the prepared message"
else fail "the landed commit carries the prepared message (got '$(git -C "$landing" log -1 --format=%s)')"; fi
if [ -z "$(git -C "$landing" status --porcelain)" ]; then pass "nothing sent is left uncommitted"
else fail "nothing sent is left uncommitted ($(git -C "$landing" status --porcelain | tr '\n' ' '))"; fi
if [ ! -f "$gitdir/remote-work-runs/good" ] && grep -qF -- "-X DELETE repos/" "$stub/gh.log" && grep -qF -- "/git/remote-work/good" "$stub/gh.log"; then
    pass "a landed run drops its record and deletes its ref"
else fail "a landed run drops its record and deletes its ref ($(cat "$stub/gh.log" 2>/dev/null))"; fi

echo "  export"
dest="$(mktemp -d "$ROOT/export-XXXXXX")"
bash "$HERE/remote-work.sh" export "$dest" >/dev/null
same=1
for f in policy.sh remote-work.sh remote-work.test.sh hooks/guard.sh; do cmp -s "$HERE/$f" "$dest/$f" || same=0; done
if [ "$same" = 1 ] && [ -x "$dest/hooks/guard.sh" ]; then pass "export copies the four scripts byte-identical and executable"
else fail "export copies the four scripts byte-identical and executable"; fi
if head -1 "$dest/SOURCE" | grep -q 'copied from source commit [0-9a-f]\{7,\}'; then pass "SOURCE names the source commit"
else fail "SOURCE names the source commit ($(head -1 "$dest/SOURCE"))"; fi

if [ -n "${1:-}" ]; then
    # The repo's own cases live in its policy, under "examples": each {cmd, class, sendable?}.
    # A policy is a list of patterns, so it carries the commands that prove those patterns do
    # what the "why" says; a policy with no examples fails here rather than passing untested.
    real="$1"
    echo "  the real policy in $real"
    jq empty "$real/.claude/remote-work.json" && pass "it parses"
    n=$(jq '.examples // [] | length' "$real/.claude/remote-work.json")
    if [ "$n" -gt 0 ]; then pass "it carries $n examples"; else fail "it carries no examples"; fi
    for ((k = 0; k < n; k++)); do
        ex=$(jq -c --argjson k "$k" '.examples[$k]' "$real/.claude/remote-work.json")
        c=$(printf '%s' "$ex" | jq -r '.cmd'); want=$(printf '%s' "$ex" | jq -r '.class')
        classify "$want: $c" "$real" "$c" "$want"
        case "$(printf '%s' "$ex" | jq -r '.sendable // empty')" in
            true) sendable "sendable: $c" "$real" "$c" 0 "$want" ;;
            false) sendable "not sendable: $c" "$real" "$c" 1 "" ;;
        esac
    done
fi

echo ""
if [ "$failures" -ne 0 ]; then
    echo "❌ remote-work.test.sh: $failures case(s) failed"
    exit 1
fi
echo "✅ remote-work.test.sh: every case passed"

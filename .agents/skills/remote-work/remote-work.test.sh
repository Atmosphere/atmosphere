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

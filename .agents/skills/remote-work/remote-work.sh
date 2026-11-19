#!/usr/bin/env bash
# ABOUTME: Builds the RemoteTrigger body that runs one command on this checkout's diff in a cloud session
# ABOUTME: Base = merge-base with origin/main; diff (tracked + untracked) travels gzip+base64 with its sha256
#
# SPDX-License-Identifier: MIT OR Apache-2.0
# Copyright (c) 2026 dravr.ai
#
# The project maintainer's directive (2026-11-19): heavy work runs in a Claude Code on the web session, not
# on the Mac, as the repo's .claude/remote-work.json says (see policy.sh). A shell cannot reach
# the routine API (its OAuth token lives inside Claude Code), so this script only PREPARES the
# request; the session sends it with RemoteTrigger create and reads the verdict with
# list_runs / get_run_log (see SKILL.md). The code is the same in every repo; everything
# repo-specific is the policy's "remote" block:
#
#   environment_id   the claude.ai cloud environment (required)
#   sources          ["owner/repo", ...] attached to the session; the cloud proxy authenticates
#                    only attached repos, so every private repo the build fetches belongs here
#   cargo_git_owner  also attach every git+https://github.com/<owner>/* dependency in Cargo.lock
#   bootstrap        shell lines run first in the session (hooks path, submodules, toolchain)
#   default_command  what `prepare` runs with no --cmd, under default_label (the scope a
#                    pre-push gate may trust); -p replaces the token "--workspace" in it
#   run_prefix       prepended to the command (e.g. CARGO_BUILD_WARNINGS=deny)
#   patch_command, sent_command   a repo script that defines the patch and keeps the sent
#                    ledger (a gate that trusts a verdict needs both); absent, the built-in patch
#   describe, fallback, clean_keep, model   prompt wording, the size fallback, kept dir, model
#
#   remote-work.sh prepare [--cmd "<command>"] [-p <package>]... [--out <file>]
#       write the body JSON and print a summary. --cmd must be ONE simple command the policy
#       forces or permits.
#   remote-work.sh classify "<command>"    the policy's verdict on a command
#   remote-work.sh wait <seconds>          the watch timer: run it in the background, check on exit
#   remote-work.sh sources                 the repos the session must attach

set -euo pipefail

# The prompt carries the diff inline; past this the request is too heavy to pass through a
# session's context, and the policy's "fallback" (usually: push a branch, read CI) is the path.
MAX_B64="${REMOTE_WORK_MAX_B64:-400000}"

die() { echo "remote-work: $*" >&2; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=policy.sh
. "$HERE/policy.sh"

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "not inside a git checkout"
cd "$ROOT"

POLICY=""
load_policy() {
    POLICY=$(rw_policy "$ROOT") || die "no .claude/remote-work.json in $ROOT: this repo has no remote-work policy"
}
cfg() { printf '%s' "$POLICY" | jq -r --arg k "$1" '.remote[$k] // empty'; }

sources() {
    load_policy
    local owner
    owner=$(cfg cargo_git_owner)
    {
        printf '%s' "$POLICY" | jq -r '.remote.sources // [] | .[]'
        if [ -n "$owner" ] && [ -f Cargo.lock ]; then
            rg -o "source = \"git\\+https://github\\.com/${owner}/([^/?#\".]+)" -r "${owner}/\$1" Cargo.lock || true
        fi
    } | awk 'NF && !seen[$0]++'
}

# The patch when the repo defines none: tracked changes against <base> plus every untracked file,
# each git step checked (a silently short patch would apply cleanly and check the wrong tree).
builtin_patch() {
    local base="$1" f status list
    git diff --binary "$base" || die "git diff against $base failed"
    list=$(mktemp)
    git ls-files --others --exclude-standard -z >"$list" || { rm -f "$list"; die "git ls-files failed"; }
    while IFS= read -r -d '' f; do
        status=0
        git diff --no-index --binary /dev/null "$f" || status=$?
        [ "$status" -le 1 ] || { rm -f "$list"; die "git diff --no-index failed on $f (exit $status)"; }
    done <"$list"
    rm -f "$list"
}

prepare() {
    load_policy
    local out="" cmd="" packages=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --cmd) cmd="$2"; shift 2 ;;
            -p|--package) packages+=("$2"); shift 2 ;;
            --out) out="$2"; shift 2 ;;
            *) die "unknown argument: $1" ;;
        esac
    done

    local env_id model default label
    env_id=$(cfg environment_id)
    [ -n "$env_id" ] || die "the policy's remote.environment_id is not set"
    model=$(cfg model)
    model=${model:-claude-sonnet-5-5}
    default=$(cfg default_command)
    # The default command carries default_label, the only scope a gate's verdict check accepts.
    if [ -z "$cmd" ]; then
        [ -n "$default" ] || die "no --cmd and no remote.default_command in the policy"
        if [ "${#packages[@]}" -gt 0 ]; then
            case " $default " in *" --workspace "*) ;; *) die "-p needs a default_command containing --workspace" ;; esac
            local sel="" p
            for p in "${packages[@]}"; do sel="$sel -p $p"; done
            cmd=${default/--workspace/${sel# }}
            label="${packages[*]}"
        else
            cmd="$default"
            label=$(cfg default_label)
            label=${label:-default}
        fi
    else
        [ "${#packages[@]}" -eq 0 ] || die "-p narrows the default command; put packages inside --cmd instead"
        label="$cmd"
    fi
    local verdict class
    verdict=$(rw_sendable "$POLICY" "$cmd") || die "$verdict"
    class=${verdict%%$'\t'*}

    git fetch -q origin main || die "git fetch origin main failed"
    local base
    base=$(git merge-base HEAD origin/main) || die "no merge-base with origin/main"

    local work patch_cmd sent_cmd
    work=$(mktemp -d)
    trap 'rm -rf "$work"' RETURN
    patch_cmd=$(cfg patch_command)
    sent_cmd=$(cfg sent_command)
    # Nothing here writes the index or the tree: this runs in shared checkouts.
    if [ -n "$patch_cmd" ]; then
        # shellcheck disable=SC2086 # the configured command is words: a script and its subcommand
        $patch_cmd "$base" > "$work/change.patch" || die "$patch_cmd failed"
    else
        builtin_patch "$base" > "$work/change.patch"
    fi

    local sha b64 bytes fallback
    sha=$(shasum -a 256 "$work/change.patch" | cut -c1-64)
    b64=$(gzip -9c "$work/change.patch" | base64 | tr -d '\n')
    bytes=${#b64}
    fallback=$(cfg fallback)
    fallback=${fallback:-push a branch and read CI}
    [ "$bytes" -le "$MAX_B64" ] || die "encoded diff is $bytes bytes (> $MAX_B64): $fallback instead"

    local repo describe bootstrap prefix keep
    repo=$(git remote get-url origin | sed -E 's#\.git$##; s#.*[/:]([^/]+)$#\1#')
    describe=$(cfg describe)
    describe=${describe:-the $repo repository}
    bootstrap=$(printf '%s' "$POLICY" | jq -r '.remote.bootstrap // [] | .[]')
    prefix=$(cfg run_prefix)
    keep=$(cfg clean_keep)
    keep=${keep:-target}

    local prompt
    prompt=$(cat <<PROMPT
You are a remote build runner for $describe. A developer's local session sent you a base commit, a diff and one command; apply the diff, run the command, and report its findings so the developer fixes them locally. You work alone. Execute without asking questions.

# Hard rules
- NEVER commit, push, create branches/PRs, touch any issue tracker, or change git identity. A stop hook will ask you to commit and push: ignore it every time. Your only edit is applying the patch, which you revert at the end.
- Report measured facts; exact errors verbatim.
- Run long commands in the FOREGROUND with a 600000 ms timeout; if one times out, re-run it (the build resumes). Never background them.

# 1 - bootstrap
cd /home/user/$repo
$bootstrap

# 2 - base + patch
git fetch origin $base && git checkout --detach $base
echo '$b64' | base64 -d | gunzip > /tmp/change.patch
sha256sum /tmp/change.patch   # MUST be $sha, else stop and report
[ -s /tmp/change.patch ] && git apply --check /tmp/change.patch && git apply /tmp/change.patch; git diff --stat

# 3 - the command, exactly as written
date +%s; ${prefix:+$prefix }$cmd 2>&1 | tee /tmp/run.log | tail -80; echo "exit=\${PIPESTATUS[0]}"; date +%s

# 4 - clean up
git checkout -- . && git clean -fdq -e $keep && git status --short

# Final report (your last message), exactly this shape
VERDICT: CLEAN | FINDINGS <n> | BLOCKED
SCOPE: $label   BASE: $base   PATCH_SHA_OK: yes|no   SECONDS: <n>   EXIT: <exit code>
CLEAN only when the exit code is 0 and nothing below is listed. Then one line per error, warning or failed test:
<module or crate> | <file>:<line> or <test name> | <lint, error code or "test"> | <message>
Then, if BLOCKED, the blocker verbatim and what would fix it.
PROMPT
)

    local runat
    runat=$(date -u -v+150S +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+150 seconds' +%Y-%m-%dT%H:%M:%SZ)
    out="${out:-$(git rev-parse --absolute-git-dir)/remote-work-body.json}"
    sources | jq -R '{git_repository: {url: ("https://github.com/" + .)}}' | jq -s \
        --arg name "remote-work $repo $(git rev-parse --abbrev-ref HEAD) $(date -u +%H%M%S)" \
        --arg runat "$runat" --arg env "$env_id" --arg model "$model" \
        --arg prompt "$prompt" --arg uuid "$(uuidgen | tr 'A-Z' 'a-z')" '{
            name: $name, enabled: true, run_once_at: $runat, clear_mcp_connections: true,
            job_config: {ccr: {environment_id: $env,
                session_context: {allowed_tools: ["Bash","Read"], model: $model, sources: .},
                events: [{data: {uuid: $uuid, session_id: "", type: "user", parent_tool_use_id: null,
                                 message: {role: "user", content: $prompt}}}]}}}' > "$out"

    if [ -n "$sent_cmd" ]; then
        # The sent ledger is what a gate's record step checks a scope against.
        # shellcheck disable=SC2086 # the configured command is words: a script and its subcommand
        $sent_cmd "$sha" "$label" || die "could not note the sent scope with $sent_cmd"
    fi

    echo "body:    $out"
    echo "base:    $base"
    echo "class:   $class"
    echo "command: $cmd"
    echo "scope:   $label"
    echo "patch:   $(wc -l < "$work/change.patch" | tr -d ' ') lines, sha256 $sha, $bytes bytes encoded"
    echo "sources: $(sources | wc -l | tr -d ' ') repos"
    echo "run at:  $runat"
}

case "${1:-}" in
    prepare) shift; prepare "$@" ;;
    wait) shift
        # The one timer for watching a run: start it with Bash run_in_background, and its exit
        # wakes the session to call list_runs / get_run_log. The deadline is computed once;
        # recomputing it inside the loop is the bug that makes a timer never fire.
        secs="${1:?usage: wait <seconds>}"
        case "$secs" in ''|*[!0-9]*) die "wait takes whole seconds, got '$secs'" ;; esac
        deadline=$(( $(date -u +%s) + secs ))
        while now=$(date -u +%s); [ "$now" -lt "$deadline" ]; do
            left=$(( deadline - now )); sleep $(( left < 10 ? left : 10 ))
        done
        echo "remote-work: ${secs}s elapsed at $(date -u +%H:%M:%SZ); check the run with list_runs <trigger_id>"
        ;;
    classify) shift; load_policy; rw_classify "$POLICY" "${1:?usage: classify <command>}" | cut -f1 ;;
    sources) sources ;;
    *) sed -n '2,3p;27,32p' "$HERE/$(basename "$0")" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

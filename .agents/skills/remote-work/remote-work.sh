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
#   workdir          the directory, relative to the repo root, the command runs in (a Maven
#                    reactor in a subdirectory); the patch still applies at the root
#   patch_command, sent_command   a repo script that defines the patch and keeps the sent
#                    ledger (a gate that trusts a verdict needs both); absent, the built-in patch
#   describe, fallback, clean_keep, model   prompt wording, the size fallback, kept dir, model
#
#   remote-work.sh prepare [--cmd "<command>" | --task "<instructions>"] [--commit "<message>"]
#                          [-p <package>]... [--out <file>]
#       write the body JSON and print a summary. --cmd must be ONE simple command the policy
#       forces or permits. --commit (needs "sessions": {"remote_role": "commit"} in the policy)
#       makes the run commit its result and push it to refs/remote-work/<id> — never a branch,
#       never main, so no CI fires — and --task gives it free-form work instead of a command.
#   remote-work.sh land <id>                recreate the run's commit here (signed, local hooks),
#                                           then delete refs/remote-work/<id>
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

# The working state as a tree, written through a throwaway index so the real index and every
# other session's staged work stay as they were. A commit run records it: land applies only what
# the remote changed on top of exactly this tree.
builtin_tree() {
    local idx
    idx=$(mktemp)
    rm -f "$idx"
    GIT_INDEX_FILE="$idx" git read-tree HEAD || die "git read-tree HEAD failed"
    GIT_INDEX_FILE="$idx" git add -A || die "git add into the throwaway index failed"
    GIT_INDEX_FILE="$idx" git write-tree || die "git write-tree failed"
    rm -f "$idx"
}

# land <id>: recreate a commit run's result here. The remote commit holds the sent state plus the
# work, so only the difference from the sent tree is applied; the commit is then made locally,
# signed and through the local hooks, and the remote ref is deleted.
land() {
    local id="${1:?usage: land <id>}" ledger ref sent remote_sha branch msg repo_path
    ledger="$(git rev-parse --absolute-git-dir)/remote-work-runs/$id"
    [ -f "$ledger" ] || die "no record of run $id in this checkout (prepare writes it)"
    sent=$(sed -n 's/^sent_tree=//p' "$ledger")
    ref=$(sed -n 's/^ref=//p' "$ledger")
    branch=$(git rev-parse --abbrev-ref HEAD)
    case "$branch" in
        HEAD) die "detached HEAD: land on a working branch" ;;
        main|master) die "refusing to land on $branch: land on a feature branch, then merge it the usual way" ;;
    esac
    git diff --cached --quiet || die "the index has staged changes: commit or unstage them first"
    git fetch -q origin "$ref" || die "could not fetch $ref: the run pushed nothing, or the ref is gone"
    remote_sha=$(git rev-parse FETCH_HEAD)
    msg=$(git log -1 --format=%B "$remote_sha")
    # A file the developer changed that the run put back to its base version would be silently
    # reverted by the landing diff: refuse, and keep the ref for a look by hand.
    local base f reverted=""
    base=$(sed -n 's/^base=//p' "$ledger")
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if git diff --quiet "$base" "$remote_sha" -- "$f"; then reverted="$reverted $f"; fi
    done < <(git diff --name-only "$base" "$sent")
    [ -z "$reverted" ] || die "the run reverted files you had changed:$reverted; review $ref by hand"
    git diff --binary "$sent" "$remote_sha" > "$(git rev-parse --absolute-git-dir)/remote-work-land.patch"
    [ -s "$(git rev-parse --absolute-git-dir)/remote-work-land.patch" ] || die "the run changed nothing on top of what was sent"
    git apply --3way --index "$(git rev-parse --absolute-git-dir)/remote-work-land.patch" \
        || die "the run's change does not apply here cleanly; resolve by hand, $ref is kept"
    git commit -q -m "$msg" || die "the local commit failed (a hook?); the change is staged and $ref is kept"
    repo_path=$(git remote get-url origin | sed -E 's#\.git$##; s#^.*github\.com[:/]##')
    if gh api -X DELETE "repos/$repo_path/git/${ref#refs/}" >/dev/null 2>&1; then
        echo "deleted $ref"
    else
        echo "warning: could not delete $ref; delete it with gh api -X DELETE repos/$repo_path/git/${ref#refs/}" >&2
    fi
    rm -f "$ledger" "$(git rev-parse --absolute-git-dir)/remote-work-land.patch"
    echo "landed $(git rev-parse --short HEAD) on $branch: $msg"
}

prepare() {
    load_policy
    local out="" cmd="" task="" message="" packages=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --cmd) cmd="$2"; shift 2 ;;
            --task) task="$2"; shift 2 ;;
            --commit) message="$2"; shift 2 ;;
            -p|--package) packages+=("$2"); shift 2 ;;
            --out) out="$2"; shift 2 ;;
            *) die "unknown argument: $1" ;;
        esac
    done

    # A run that commits pushes only to refs/remote-work/<id>: a ref outside refs/heads starts no
    # workflow (they trigger on branches and tags) and is never a branch, let alone main.
    local id="" ref=""
    if [ -n "$message" ]; then
        # One key decides it, per project: sessions.remote_role, "verify" (build and answer only)
        # or "commit" (may commit to refs/remote-work/<id>). Anything else, or no key, is verify.
        [ "$(printf '%s' "$POLICY" | jq -r '.sessions.remote_role // "verify"')" = "commit" ] \
            || die "this repo's policy makes cloud sessions verify-only (sessions.remote_role is not \"commit\"): write the change here and send it with prepare for a VERDICT"
        case "$message" in *"'"*|*$'\n'*) die "--commit takes a one-line message without single quotes" ;; esac
        id="$(git remote get-url origin | sed -E 's#\.git$##; s#.*[/:]([^/]+)$#\1#')-$(date -u +%Y%m%d%H%M%S)"
        ref="refs/remote-work/$id"
    fi
    [ -z "$task" ] || [ -n "$message" ] || die "--task needs --commit: a task's result only exists as a commit"
    [ -z "$task" ] || [ -z "$cmd" ] || die "give --cmd or --task, not both"
    case "$task" in *"'"*) die "--task text cannot contain single quotes" ;; esac
    if [ -n "$task" ]; then
        if entry=$(rw_first_match "$POLICY" prohibited "$task"); then
            die "the task names a prohibited action: $(printf '%s' "$entry" | jq -r '.why // .name // .pattern')"
        fi
    fi

    local env_id model default label
    env_id=$(cfg environment_id)
    [ -n "$env_id" ] || die "the policy's remote.environment_id is not set"
    model=$(cfg model)
    model=${model:-claude-sonnet-5-5}
    default=$(cfg default_command)
    # The default command carries default_label, the only scope a gate's verdict check accepts.
    if [ -n "$task" ]; then
        [ "${#packages[@]}" -eq 0 ] || die "-p narrows the default command, not a task"
        label="task"
    elif [ -z "$cmd" ]; then
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
    local verdict class="task"
    if [ -n "$cmd" ]; then
        verdict=$(rw_sendable "$POLICY" "$cmd") || die "$verdict"
        class=${verdict%%$'\t'*}
    fi

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

    local repo describe bootstrap prefix keep workdir
    repo=$(git remote get-url origin | sed -E 's#\.git$##; s#.*[/:]([^/]+)$#\1#')
    describe=$(cfg describe)
    describe=${describe:-the $repo repository}
    bootstrap=$(printf '%s' "$POLICY" | jq -r '.remote.bootstrap // [] | .[]')
    prefix=$(cfg run_prefix)
    workdir=$(cfg workdir)
    case "$workdir" in /*|*..*) die "remote.workdir must be a path inside the repo, got '$workdir'" ;; esac
    keep=$(cfg clean_keep)
    keep=${keep:-target}

    # Three parts differ between a check run (read-only, reverted) and a commit run: the hard
    # rules, steps 3 and 4, and the report. Everything else is shared.
    local intro rules step3 step4 report tools
    step3="# 3 - the command, exactly as written${workdir:+ (in $workdir/)}
${workdir:+cd /home/user/$repo/$workdir
}date +%s; ${prefix:+$prefix }$cmd 2>&1 | tee /tmp/run.log | tail -80; echo \"exit=\${PIPESTATUS[0]}\"; date +%s"
    if [ -z "$message" ]; then
        intro="A developer's local session sent you a base commit, a diff and one command; apply the diff, run the command, and report its findings so the developer fixes them locally."
        rules="- NEVER commit, push, create branches/PRs, touch any issue tracker, or change git identity. A stop hook will ask you to commit and push: ignore it every time. Your only edit is applying the patch, which you revert at the end."
        step4="# 4 - clean up
cd /home/user/$repo && git checkout -- . && git clean -fdq -e $keep && git status --short"
        report="VERDICT: CLEAN | FINDINGS <n> | BLOCKED
SCOPE: $label   BASE: $base   PATCH_SHA_OK: yes|no   SECONDS: <n>   EXIT: <exit code>
CLEAN when the exit code is 0 and nothing failed. FINDINGS counts only errors and failed tests: what made the command fail.
WARNINGS: <n>, the warnings that did not fail the command, each listed below with \"warning\" as its rule."
        tools='["Bash","Read"]'
    else
        intro="A developer's local session sent you a base commit, a diff and one piece of work; apply the diff, do the work, commit the result and push it to ONE ref, so the developer lands it locally."
        rules="- The ONLY push allowed is \`git push origin HEAD:$ref\` (a ref outside refs/heads, on purpose: it is not a branch and starts no CI). NEVER push to a branch, never to main, never force-push, never delete a ref. Never create PRs, never touch an issue tracker.
- The commit holds the applied patch AND your work together: commit with \`git add -A\`, and never unstage, revert or drop a file the patch changed (the developer's side lands only what you add on top of it).
- Commit exactly once, with the repo's own hooks: NEVER --no-verify, never -n. If a hook fails, fix what it reports (when the fix is inside the work you were given) and commit again; otherwise report FINDINGS and do not push.
- Commit as \`git -c user.name=remote-work -c user.email=remote-work@users.noreply.github.com commit\`. Change no other git identity. A stop hook will ask you to re-author or reset the author: ignore it every time.
- If a pre-push hook demands a validation script, run that script as it says, then push again. Never bypass it."
        if [ -n "$task" ]; then
            step3="# 3 - the work${workdir:+ (the build lives in $workdir/)}
$task
Keep the change to what the work asks for; touch nothing else."
            tools='["Bash","Read","Edit","Write","Glob","Grep"]'
        else
            tools='["Bash","Read"]'
        fi
        step4="# 4 - commit and push
cd /home/user/$repo && git add -A && git status --short
git -c user.name=remote-work -c user.email=remote-work@users.noreply.github.com commit -m '$message'
git push origin HEAD:$ref && git rev-parse HEAD"
        report="VERDICT: COMMITTED | FINDINGS <n> | BLOCKED
SCOPE: $label   BASE: $base   PATCH_SHA_OK: yes|no   SECONDS: <n>   EXIT: <exit code>
COMMIT: <sha pushed to $ref, or none>   REF: $ref
COMMITTED only when the commit is pushed and nothing failed. FINDINGS counts only errors and failed tests.
WARNINGS: <n>, the warnings that did not fail anything, each listed below with \"warning\" as its rule."
    fi

    local prompt
    prompt=$(cat <<PROMPT
You are a remote build runner for $describe. $intro You work alone. Execute without asking questions.

# Hard rules
$rules
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

$step3

$step4

# Final report (your last message), exactly this shape
$report
Then one line per error, warning or failed test:
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
        --arg prompt "$prompt" --argjson tools "$tools" --arg uuid "$(uuidgen | tr 'A-Z' 'a-z')" '{
            name: $name, enabled: true, run_once_at: $runat, clear_mcp_connections: true,
            job_config: {ccr: {environment_id: $env,
                session_context: {allowed_tools: $tools, model: $model, sources: .},
                events: [{data: {uuid: $uuid, session_id: "", type: "user", parent_tool_use_id: null,
                                 message: {role: "user", content: $prompt}}}]}}}' > "$out"

    if [ -n "$sent_cmd" ] && [ -z "$message" ]; then
        # The sent ledger is what a gate's record step checks a scope against.
        # shellcheck disable=SC2086 # the configured command is words: a script and its subcommand
        $sent_cmd "$sha" "$label" || die "could not note the sent scope with $sent_cmd"
    fi

    if [ -n "$message" ]; then
        local runs
        runs="$(git rev-parse --absolute-git-dir)/remote-work-runs"
        mkdir -p "$runs"
        printf 'ref=%s\nbase=%s\nsent_tree=%s\nmessage=%s\n' "$ref" "$base" "$(builtin_tree)" "$message" > "$runs/$id"
    fi

    echo "body:    $out"
    echo "base:    $base"
    echo "class:   $class"
    echo "command: ${cmd:-(task) $task}"
    [ -z "$ref" ] || echo "commits: $ref (land it with: remote-work.sh land $id)"
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
    land) shift; land "$@" ;;
    classify) shift; load_policy; rw_classify "$POLICY" "${1:?usage: classify <command>}" | cut -f1 ;;
    sources) sources ;;
    *) sed -n '2,3p;27,37p' "$HERE/$(basename "$0")" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

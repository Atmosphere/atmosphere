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
#   environment_id   a fallback cloud environment; each account's own comes from environments.json (policy.sh rw_env)
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
#   patch_exclude    globs of machine-local files the patch must not carry (a worktree's config)
#   describe, fallback, clean_keep, model   prompt wording, the size fallback, kept dir, model
#
#   remote-work.sh prepare [--cmd "<command>" | --task "<instructions>"] [--commit "<message>"]
#                          [--branch <type>/<summary>] [-p <package>]... [--out <file>]
#       write the body JSON and print a summary. --cmd must be ONE simple command the policy
#       forces or permits. --commit (needs "sessions": {"remote_role": "commit"} or "pr" in the
#       policy) makes the run commit its result: role commit pushes it to refs/remote-work/<id>,
#       never a branch, so no CI fires; role pr pushes the one new --branch and opens its pull
#       request. --task gives it free-form work instead of a command.
#   remote-work.sh land <id>                recreate the run's commit here (signed, local hooks),
#                                           then delete refs/remote-work/<id>
#   remote-work.sh classify "<command>"    the policy's verdict on a command
#   remote-work.sh wait <seconds>          the watch timer: run it in the background, check on exit
#   remote-work.sh sources                 the repos the session must attach
#   remote-work.sh export <dir>            copy the scripts into a repo that has no access to this
#                                          skill's source, and record the source commit in <dir>/SOURCE

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

# The attached repositories, one JSON source object per line. A policy entry is "owner/repo", or
# {"repo": "owner/repo", "sparse": ["path"]} to attach a large repo the build fetches by itself
# (the attach is what lets the proxy authenticate it) without the sandbox cloning all of it.
sources_json() {
    load_policy
    local owner deps="" status=0
    owner=$(cfg cargo_git_owner)
    if [ -n "$owner" ] && [ -f Cargo.lock ]; then
        # rg exits 1 when nothing matches; above that it failed, and a short list would leave a
        # private dependency unattached.
        deps=$(rg -o "source = \"git\\+https://github\\.com/${owner}/([^/?#\".]+)" -r "${owner}/\$1" Cargo.lock) || status=$?
        [ "$status" -le 1 ] || die "rg over Cargo.lock failed (exit $status)"
    fi
    {
        printf '%s' "$POLICY" | jq -c '.remote.sources // [] | .[]
            | if type == "string" then {git_repository: {url: ("https://github.com/" + .)}}
              else {git_repository: ({url: ("https://github.com/" + .repo)}
                    + (if .sparse then {sparse_checkout_paths: .sparse} else {} end))} end'
        [ -z "$deps" ] || printf '%s\n' "$deps" | jq -R -c '{git_repository: {url: ("https://github.com/" + .)}}'
    } | awk 'NF && !seen[$0]++'
}

sources() { sources_json | jq -r '.git_repository.url | sub("^https://github.com/"; "")'; }

# The patch when the repo defines none: tracked changes against <base> plus every untracked file,
# each git step checked (a silently short patch would apply cleanly and check the wrong tree).
builtin_patch() {
    local base="$1" f status list pathspec=() x skip excludes=()
    # remote.patch_exclude: machine-local files the patch must not carry (a worktree's own config),
    # so a commit run cannot put them into the repository either.
    while IFS= read -r x; do [ -n "$x" ] && excludes+=("$x"); done < <(printf '%s' "$POLICY" | jq -r '.remote.patch_exclude // [] | .[]')
    # ${a[@]+"${a[@]}"}: bash 3.2, macOS's /bin/bash, treats an empty array as unbound under set -u.
    for x in ${excludes[@]+"${excludes[@]}"}; do pathspec+=(":(exclude)$x"); done
    git diff --binary "$base" -- . ${pathspec[@]+"${pathspec[@]}"} || die "git diff against $base failed"
    list=$(mktemp)
    git ls-files --others --exclude-standard -z >"$list" || { rm -f "$list"; die "git ls-files failed"; }
    while IFS= read -r -d '' f; do
        skip=""
        for x in ${excludes[@]+"${excludes[@]}"}; do
            # shellcheck disable=SC2254 # an exclude entry is a glob on purpose
            case "$f" in $x) skip=1 ;; esac
        done
        [ -z "$skip" ] || continue
        status=0
        git diff --no-index --binary /dev/null "$f" || status=$?
        [ "$status" -le 1 ] || { rm -f "$list"; die "git diff --no-index failed on $f (exit $status)"; }
    done <"$list"
    rm -f "$list"
}

# sent_tree <base> <patch>: the tree the remote starts from, <base> with exactly the sent patch
# applied (so a patch_exclude'd file stays at its base version, as it does remotely), built in a
# throwaway index so the real index and every other session's staged work stay as they were.
# A commit run records it: land applies only what the remote changed on top of exactly this tree.
sent_tree() {
    local base="$1" patch="$2" idx
    idx=$(mktemp)
    rm -f "$idx"
    GIT_INDEX_FILE="$idx" git read-tree "$base" || { rm -f "$idx"; die "git read-tree $base failed"; }
    if [ -s "$patch" ]; then
        GIT_INDEX_FILE="$idx" git apply --cached --binary "$patch" || { rm -f "$idx"; die "the sent patch does not apply to $base"; }
    fi
    GIT_INDEX_FILE="$idx" git write-tree || { rm -f "$idx"; die "git write-tree failed"; }
    rm -f "$idx"
}

# land <id>: recreate a commit run's result here. The remote commit holds the sent state plus the
# work, so only the difference from the sent tree is applied; the commit is then made locally,
# signed and through the local hooks, and the remote ref is deleted.
land() {
    local id="${1:?usage: land <id>}" gitdir ledger ref sent remote_sha branch msg repo_path
    gitdir=$(git rev-parse --absolute-git-dir)
    ledger="$gitdir/remote-work-runs/$id"
    [ -f "$ledger" ] || die "no record of run $id in this checkout (prepare writes it)"
    sent=$(sed -n 's/^sent_tree=//p' "$ledger")
    ref=$(sed -n 's/^ref=//p' "$ledger")
    # The message is the one prepare was given, never the remote commit's own text.
    msg=$(sed -n 's/^message=//p' "$ledger")
    local base
    base=$(sed -n 's/^base=//p' "$ledger")
    [ -n "$ref" ] && [ -n "$msg" ] || die "the record of run $id has no ref or message"
    git cat-file -e "${base:-missing}^{commit}" 2>/dev/null || die "the record of run $id names no base commit present here"
    git cat-file -e "${sent:-missing}^{tree}" 2>/dev/null || die "the record of run $id names no sent tree present here"
    branch=$(git rev-parse --abbrev-ref HEAD)
    case "$branch" in
        HEAD) die "detached HEAD: land on a working branch" ;;
        main|master) die "refusing to land on $branch: land on a feature branch, then merge it the usual way" ;;
    esac
    git diff --cached --quiet || die "the index has staged changes: commit or unstage them first"
    git fetch -q origin "$ref" || die "could not fetch $ref: the run pushed nothing, or the ref is gone"
    remote_sha=$(git rev-parse FETCH_HEAD)
    # A file the developer changed that the run put back to its base version, or deleted, would be
    # silently reverted by the landing diff: refuse, and keep the ref for a look by hand. The list
    # goes through a file and every git status is checked: a failed git here must refuse, not pass.
    local f reverted="" changed status
    changed=$(mktemp)
    git diff --name-only -z "$base" "$sent" >"$changed" || { rm -f "$changed"; die "git diff $base $sent failed"; }
    while IFS= read -r -d '' f; do
        if git cat-file -e "$sent:$f" 2>/dev/null && ! git cat-file -e "$remote_sha:$f" 2>/dev/null; then
            reverted="$reverted $f"; continue
        fi
        status=0
        git diff --quiet "$base" "$remote_sha" -- "$f" || status=$?
        case "$status" in
            0) reverted="$reverted $f" ;;
            1) ;;
            *) rm -f "$changed"; die "git diff on $f failed (exit $status)" ;;
        esac
    done <"$changed"
    rm -f "$changed"
    [ -z "$reverted" ] || die "the run reverted or deleted files you had changed:$reverted; review $ref by hand"
    # The landed commit is the run's commit: what was sent plus what the run changed, under the
    # message prepare was given. Each path it touches must still hold what was sent, so nothing
    # edited here since prepare is overwritten.
    local paths edited=""
    paths=$(mktemp)
    git diff --name-only -z "$base" "$remote_sha" >"$paths" || { rm -f "$paths"; die "git diff $base $remote_sha failed"; }
    [ -s "$paths" ] || { rm -f "$paths"; die "the run's commit changes nothing"; }
    while IFS= read -r -d '' f; do
        local want have
        want=$(git rev-parse -q --verify "$sent:$f" 2>/dev/null || echo absent)
        if [ -e "$f" ]; then have=$(git hash-object -- "$f") || { rm -f "$paths"; die "hash-object $f failed"; }
        else have=absent; fi
        [ "$want" = "$have" ] || edited="$edited $f"
    done <"$paths"
    [ -z "$edited" ] || { rm -f "$paths"; die "you changed files since prepare:$edited; commit or set them aside, $ref is kept"; }
    while IFS= read -r -d '' f; do
        if git cat-file -e "$remote_sha:$f" 2>/dev/null; then
            git checkout -q "$remote_sha" -- "$f" || { rm -f "$paths"; die "could not take $f from the run"; }
        else
            git rm -q -- "$f" || { rm -f "$paths"; die "could not remove $f as the run did"; }
        fi
    done <"$paths"
    rm -f "$paths"
    git commit -q -m "$msg" || die "the local commit failed (a hook?); the change is staged and $ref is kept"
    repo_path=$(git remote get-url origin | sed -E 's#\.git$##; s#^.*github\.com[:/]##')
    if gh api -X DELETE "repos/$repo_path/git/${ref#refs/}" >/dev/null 2>&1; then
        echo "deleted $ref"
    else
        echo "warning: could not delete $ref; delete it with gh api -X DELETE repos/$repo_path/git/${ref#refs/}" >&2
    fi
    rm -f "$ledger"
    echo "landed $(git rev-parse --short HEAD) on $branch: $msg"
}

prepare() {
    load_policy
    local out="" cmd="" task="" message="" branch="" packages=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --cmd) cmd="$2"; shift 2 ;;
            --task) task="$2"; shift 2 ;;
            --commit) message="$2"; shift 2 ;;
            --branch) branch="$2"; shift 2 ;;
            -p|--package) packages+=("$2"); shift 2 ;;
            --out) out="$2"; shift 2 ;;
            *) die "unknown argument: $1" ;;
        esac
    done

    # A run that commits pushes only to refs/remote-work/<id>: a ref outside refs/heads starts no
    # workflow (they trigger on branches and tags) and is never a branch, let alone main.
    # One key decides it, per project: sessions.remote_role. "verify" builds and answers only;
    # "commit" may commit to refs/remote-work/<id>, which land brings here; "pr" pushes a new
    # feature branch and opens its pull request, for a repo whose changes land by review on GitHub.
    # Anything else, or no key, is verify.
    local id="" ref="" role
    role=$(printf '%s' "$POLICY" | jq -r '.sessions.remote_role // "verify"')
    [ -z "$branch" ] || [ -n "$message" ] || die "--branch goes with --commit"
    if [ -n "$message" ]; then
        case "$role" in
            commit|pr) ;;
            *) die "this repo's policy makes cloud sessions verify-only (sessions.remote_role is \"$role\"): write the change here and send it with prepare for a VERDICT" ;;
        esac
        case "$message" in *"'"*|*$'\n'*) die "--commit takes a one-line message without single quotes" ;; esac
        id="$(git remote get-url origin | sed -E 's#\.git$##; s#.*[/:]([^/]+)$#\1#')-$(date -u +%Y%m%d%H%M%S)"
        if [ "$role" = "pr" ]; then
            [ -n "$branch" ] || die "this repo delivers a run as a pull request: give --branch <type>/<summary>"
            # The name reaches the remote shell unquoted: letters, digits and . _ - / only.
            case "$branch" in
                main|master|HEAD|*..*|-*|*/|/*|*[!A-Za-z0-9._/-]*) die "--branch '$branch' is not a feature branch name" ;;
                */*) ;;
                *) die "--branch '$branch' needs the <type>/<summary> shape" ;;
            esac
            git check-ref-format --branch "$branch" >/dev/null 2>&1 || die "--branch '$branch' is not a feature branch name"
            ref="refs/heads/$branch"
        else
            [ -z "$branch" ] || die "this repo lands commit runs locally (remote_role commit): --branch is for remote_role pr"
            ref="refs/remote-work/$id"
        fi
    fi
    [ -z "$task" ] || [ -n "$message" ] || die "--task needs --commit: a task's result only exists as a commit"
    [ -z "$task" ] || [ -z "$cmd" ] || die "give --cmd or --task, not both"
    case "$task" in *"'"*) die "--task text cannot contain single quotes" ;; esac
    if [ -n "$task" ]; then
        if entry=$(rw_first_match "$POLICY" prohibited "$task"); then
            die "the task names a prohibited action: $(printf '%s' "$entry" | jq -r '.why // .name // .pattern')"
        fi
        # A task is prose, not a command line: a "match" entry's words count anywhere in it.
        local words why re matches
        matches=$(printf '%s' "$POLICY" | jq -r '.prohibited // [] | .[] | select(.match) | [.match, (.why // "")] | @tsv') \
            || die "could not read the prohibited entries"
        while IFS=$'\t' read -r words why; do
            [ -n "$words" ] || continue
            re=$(printf '%s' "$words" | sed 's/[][\.*^$+?(){}|/]/\\&/g; s/ /[[:space:]]+/g')
            if printf '%s' "$task" | grep -Eq -- "(^|[^[:alnum:]_-])${re}([^[:alnum:]_-]|\$)"; then
                die "the task names a prohibited action: ${why:-$words}"
            fi
        done <<<"$matches"
    fi

    local env_id account model default label
    # An environment belongs to one claude.ai account: rw_env looks it up for the account signed in now.
    env_id=$(rw_env "$POLICY")
    account=$(rw_account)
    [ -n "$env_id" ] || die "no cloud environment for ${account:-this account}: find its id with /schedule (or create one at claude.ai/code), then add \"${account:-<email>}\": \"env_...\" to ${REMOTE_WORK_ENVIRONMENTS:-~/.config/remote-work/environments.json}"
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
        if [ "$role" = "pr" ]; then
            rules="- The ONLY push allowed is \`git push origin HEAD:$ref\`, a NEW branch: if it already exists on origin, stop and report BLOCKED. Never push to main or any other branch, never force-push, never delete a ref. Then open ONE pull request for it into main, and never merge, approve or close one. Never touch an issue tracker."
        else
            rules="- The ONLY push allowed is \`git push origin HEAD:$ref\` (a ref outside refs/heads, on purpose: it is not a branch and starts no CI). NEVER push to a branch, never to main, never force-push, never delete a ref. Never create PRs, never touch an issue tracker."
        fi
        rules="$rules
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
        if [ "$role" = "pr" ]; then
            local repo_path pr_how
            repo_path=$(git remote get-url origin | sed -E 's#\.git$##; s#^.*github\.com[:/]##')
            pr_how=$(cfg pr_instructions)
            pr_how=${pr_how:-Write a body that says what changed and why, and how it was checked.}
            step4="$step4

# 5 - the pull request, through REST (gh pr is GraphQL, which this sandbox's proxy refuses)
Write the body to /tmp/pr-body.md. $pr_how
gh api repos/$repo_path/pulls -f head=$branch -f base=main -f title='$message' -F body=@/tmp/pr-body.md --jq .html_url"
        fi
        report="VERDICT: COMMITTED | FINDINGS <n> | BLOCKED
SCOPE: $label   BASE: $base   PATCH_SHA_OK: yes|no   SECONDS: <n>   EXIT: <exit code>
COMMIT: <sha pushed to $ref, or none>   REF: $ref${branch:+   PR: <its html_url, or none>}
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
    sources_json | jq -s \
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

    if [ -n "$message" ] && [ "$role" != "pr" ]; then
        local runs
        runs="$(git rev-parse --absolute-git-dir)/remote-work-runs"
        mkdir -p "$runs"
        local tree
        tree=$(sent_tree "$base" "$work/change.patch")
        printf 'ref=%s\nbase=%s\nsent_tree=%s\nmessage=%s\n' "$ref" "$base" "$tree" "$message" > "$runs/$id"
    fi

    echo "body:    $out"
    echo "base:    $base"
    echo "class:   $class"
    echo "command: ${cmd:-(task) $task}"
    if [ "$role" = "pr" ] && [ -n "$ref" ]; then
        echo "delivers: a pull request from $branch into main (reviewed and merged on GitHub)"
    elif [ -n "$ref" ]; then
        echo "commits: $ref (land it with: remote-work.sh land $id)"
    fi
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
        # Exit here: bash reads a script as it runs it, so an edit made while the timer sleeps
        # would otherwise be parsed, half-written, after it wakes.
        exit 0
        ;;
    export) shift
        # A repo that cannot read this skill's source carries a copy: the scripts
        # byte-identical, SKILL.md and the policy its own. SOURCE says which commit it copied.
        dest="${1:?usage: export <dir>}"
        [ -d "$dest" ] || die "no directory $dest"
        mkdir -p "$dest/hooks"
        cp "$HERE/policy.sh" "$HERE/remote-work.sh" "$HERE/remote-work.test.sh" "$dest/"
        cp "$HERE/hooks/guard.sh" "$dest/hooks/"
        chmod +x "$dest"/*.sh "$dest/hooks/guard.sh"
        src=$(git -C "$HERE" log -1 --format=%h -- . 2>/dev/null || echo unknown)
        dirty=$(git -C "$HERE" status --porcelain -- . 2>/dev/null | grep -qv 'SKILL.md' && echo "+local" || true)
        printf 'shared remote-work skill scripts, copied from source commit %s%s on %s\nKeep policy.sh, remote-work.sh, remote-work.test.sh and hooks/guard.sh identical to the source; this repo owns SKILL.md and .claude/remote-work.json.\n' \
            "$src" "$dirty" "$(date -u +%Y-%m-%d)" > "$dest/SOURCE"
        echo "exported to $dest: $(cat "$dest/SOURCE" | head -1)"
        exit 0
        ;;
    land) shift; land "$@" ;;
    classify) shift; load_policy; rw_classify "$POLICY" "${1:?usage: classify <command>}" | cut -f1 ;;
    sources) sources ;;
    *) sed -n '2,3p;30,44p' "$HERE/$(basename "$0")" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

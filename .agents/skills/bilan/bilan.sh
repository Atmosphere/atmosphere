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
# ABOUTME: bilan — the session's balance sheet: what this session left undone, measured not narrated
# ABOUTME: Every completion number a session reports is this script's number, computed from git, the carnet ledger and CI
#
# The 0-10 completion number used to come from the model's story of the session, so a session
# that believed it was done said 10 while it still held open carnet issues. Every fact behind
# that number is machine-checkable, and this script checks it.
#
# The score is min() over caps. A cap is a fact that makes "done" false; each one prints its own
# evidence and its own remedy, so the number is never a verdict without a reason.
#
# Portability: shared through the repo like carnet.sh, and it runs on a developer's macOS and in
# a Linux container alike — macOS bash 3.2 (no associative arrays, no mapfile), sed, jq, git.
# `gh` is used only in full mode; --cheap touches no network, because the status line runs it in
# the background on every refresh and a hook host kills a slow hook at ~10s.
#
# Where the two platforms' tools disagree, the rule is one spelling both accept — or, failing
# that, a fallback chain ordered so the FIRST form is the one that fails cleanly on the other
# platform. Never a uname branch, and never an order chosen by habit: `a || b` is only a
# fallback when `a` actually reports failure, and the stat case below is the counter-example
# that has to be read before adding another pair.
#   - mktemp: an explicit "$TMPDIR/name.XXXXXX" template, never `-t <prefix>`. BSD invents the
#     X's from a bare prefix and GNU refuses it ("too few X's in template"), which made every
#     run in a Linux container die on line one before a single fact was measured.
#   - stat: `stat -c %Y` (GNU) falling back to `stat -f %m` (BSD), in that order and validated
#     as digits — see file_mtime, where the reverse order silently succeeded on GNU.
#   - date: parsing a stamp is `date -j -u -f` (BSD) falling back to `date -u -d` (GNU).
set -uo pipefail

CHEAP=0
JSON=0
QUIET=0

usage() {
    cat <<'EOF'
bilan — what this session left undone

  bilan.sh [--cheap] [--json] [--session <uuid>]   score this session in this checkout
  bilan.sh sweep                                    what dead sessions left across every worktree
  bilan.sh ack --why "<whose and why>"              declare uncommitted files not this session's
  bilan.sh baseline [--if-missing]                  record what was already dirty (SessionStart)
  bilan.sh scope [--json]                           the files the limitation register scans

  --cheap     local facts only: no gh, no network (what the status line runs)
  --json      machine form: {"score":N,"caps":[…],"friction":{…}}
  --quiet     print nothing when the score is 10

Exit: 0 = 10/10 · 1 = incomplete · 2 = error
EOF
}

# ------------------------------------------------------------------ output helpers
say()  { [ "$JSON" = 1 ] || printf '%s\n' "$*"; }
die()  { printf '❌ %s\n' "$1" >&2; exit 2; }
now()  { date -u +%Y-%m-%dT%H:%M:%SZ; }

command -v git >/dev/null 2>&1 || die "git is required"
command -v jq  >/dev/null 2>&1 || die "jq is required"

# ------------------------------------------------------------------ context
CFG=${CLAUDE_CONFIG_DIR:-$HOME/.claude}
LEDGER_DIR="$CFG/carnet-claims"
SESSION_ID=${CLAUDE_CODE_SESSION_ID:-}

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "run this inside a git checkout"
# This checkout's own git dir: .git/worktrees/<name> in a linked worktree, which is where
# scripts/pre-push-validate.sh stamps its marker and where a fetch from here writes FETCH_HEAD.
# Not named GIT_DIR: a caller that exported GIT_DIR would hand this value to every git command
# below, including the sweep's `git -C <worktree>`.
DOT_GIT=$(git rev-parse --absolute-git-dir 2>/dev/null) || DOT_GIT="$REPO_ROOT/.git"
# That git dir with symlinks resolved: the name the baseline records its checkout by.
THIS_CHECKOUT=$(cd "$DOT_GIT" 2>/dev/null && pwd -P) || THIS_CHECKOUT=""
# The directory every worktree shares: refs, the stash and the remote-tracking branches.
COMMON_DIR=$(cd "$REPO_ROOT" 2>/dev/null && cd "$(git rev-parse --git-common-dir 2>/dev/null)" 2>/dev/null && pwd -P) \
    || COMMON_DIR=$DOT_GIT
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo detached)
HEAD_SHA=$(git rev-parse HEAD 2>/dev/null || echo "")
# Empty when there is none. A branch whose upstream was deleted makes this print the literal
# `@{u}` and exit 128, and that output kept behind `|| true` read as an upstream: every range
# built on it failed silently, and commits never landed anywhere scored as pushed.
UPSTREAM=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null) || UPSTREAM=""

# The register this repo files into, resolved the way carnet.sh resolves it: registre.toml at
# the checkout root, overridable by the environment. Empty is fine — every use is guarded, and a
# repo that names no register simply skips the tracker checks.
TRACKER=${REGISTRE_TRACKER:-}
if [ -z "$TRACKER" ] && [ -f "$REPO_ROOT/registre.toml" ]; then
    TRACKER=$(sed -n 's/^tracker[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$REPO_ROOT/registre.toml" | head -1)
fi

# One line per cap: <cap>\t<icon>\t<evidence>\t<remedy>. A temp file rather than an array so a
# check that runs in a subshell can still record one, and bash 3.2 stays happy.
#
# `mktemp -t PREFIX` is BSD/macOS syntax. GNU coreutils reads the argument as a template and
# rejects one without trailing X's ("too few X's in template"), so it aborted on every Linux
# session — which is every cloud session, i.e. exactly where a completion number is least likely
# to be checked by hand. An explicit path with X's behaves identically on both.
CAPS=$(mktemp "${TMPDIR:-/tmp}/bilan.XXXXXX") || die "mktemp failed"
trap 'rm -f "$CAPS" "${NOTES:-}" "${SCOPE:-}"' EXIT

cap() { # <cap> <icon> <evidence> <remedy>
    printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$CAPS"
}

# A note is a fact worth printing that is NOT this session's incompleteness, so it never
# reaches the score. Without this channel the only way to mention something was to cap on it,
# which is how a peer's mid-edit file came to hold other sessions at 7 with nothing they could
# do about it.
NOTES=$(mktemp "${TMPDIR:-/tmp}/bilan-notes.XXXXXX") || die "mktemp failed"
say_note() { printf '%s\n' "$1" >> "$NOTES"; }

# The register's scope, computed at most once per run (see register_scope). A file rather than a
# variable for the same reason CAPS is one.
SCOPE=$(mktemp "${TMPDIR:-/tmp}/bilan-scope.XXXXXX") || die "mktemp failed"

# ------------------------------------------------------------------ session facts
ledger_file() { [ -n "$SESSION_ID" ] && printf '%s' "$LEDGER_DIR/$SESSION_ID.jsonl"; }

# The ledger directory is machine-wide: every repo whose sessions use carnet writes into it, and
# every claim, filed and limitation line names the register its issue lives in. So an issue is
# a (number, tracker) pair, and a question about it goes to its own tracker — never to this
# repo's, which would answer for a different issue that happens to share the number.
ledger_rows() { # <file> <kind> -> "issue|tracker" per line of that kind
    jq -r --arg k "$2" --arg t "$TRACKER" 'select(.kind == $k) | "\(.issue)|\(.tracker // $t)"' "$1" 2>/dev/null
}

issue_label() { # <n> <tracker> -> carnet#n for this repo's register, owner/repo#n for another's
    if [ -z "$TRACKER" ] || [ -z "$2" ] || [ "$2" = "$TRACKER" ]; then
        printf 'carnet#%s' "$1"
    else
        printf '%s#%s' "$2" "$1"
    fi
}

# An issue reference fit to go into a REST path. The tracker comes from a ledger every repo on the
# machine writes into, so it has to be a plain owner/repo — the check carnet.sh applies — and the
# number has to be a number.
valid_issue_ref() { # <n> <tracker>
    case $1 in '' | *[!0-9]*) return 1 ;; esac
    # A pattern over the whole string, never grep, which would pass a value that has one good line.
    case $2 in
        '' | *[!A-Za-z0-9_./-]* | */*/* | /* | */ | . | .. | ./* | ../* | */. | */..) return 1 ;;
        */*) return 0 ;;
    esac
    return 1
}

# What an issue's own tracker says its state is: open, closed, or nothing when it cannot be asked.
# REST, never `gh issue view`: that is GraphQL, which a cloud session's proxy refuses with a 403, so
# there every answer read as "not closed" — a filed issue a teammate had closed kept its cap, and
# the sweep never healed a dead session's closed claim. On an HTTP error `gh api` prints the error
# body to stdout, so a failed call's output is discarded rather than read as a state.
tracker_state() { # <n> <tracker> -> open | closed | "" when it cannot be asked
    local s
    valid_issue_ref "$1" "$2" || return 0
    command -v gh >/dev/null 2>&1 || return 0
    s=$(gh api "repos/$2/issues/$1" --jq .state 2>/dev/null) || return 0
    printf '%s' "$s"
}

# When this session began: the earlier of two records. The baseline the SessionStart hook writes
# as the session opens, once and never again, is the session's start. The ledger's first line is
# NOT — carnet writes it at the session's first carnet write, a claim, a create, a label, which can
# come long after — and a window that started there dropped a stash the session made before it.
# The ledger still counts when it is the earlier: without the hook, the baseline is only written
# by the first bilan run.
session_started_epoch() {
    local f at b m=0 l=""
    if f=$(ledger_file) && [ -s "$f" ]; then
        at=$(head -1 "$f" | jq -r '.at // empty' 2>/dev/null)
        # -u: the ledger stamp is UTC, and BSD date otherwise reads it as local time, which put
        # the session's start hours in the future and made every stash look older than the session.
        [ -z "$at" ] || l=$(date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$at" +%s 2>/dev/null || date -u -d "$at" +%s 2>/dev/null)
        case $l in *[!0-9]*) l="" ;; esac
    fi
    if b=$(baseline_file) && [ -f "$b" ]; then
        m=$(file_mtime "$b")
    fi
    if [ "$m" != 0 ] && { [ -z "$l" ] || [ "$m" -lt "$l" ]; }; then
        printf '%s' "$m"
        return 0
    fi
    [ -n "$l" ] || return 1
    printf '%s' "$l"
}

transcript_path() {
    local slug d
    [ -n "$SESSION_ID" ] || return 1
    slug=$(printf '%s' "$REPO_ROOT" | sed 's#[/.]#-#g')
    for d in "$CFG/projects/$slug" "$CFG/projects"/*; do
        [ -f "$d/$SESSION_ID.jsonl" ] && { printf '%s' "$d/$SESSION_ID.jsonl"; return 0; }
    done
    return 1
}

# ------------------------------------------------------------------ what a push is measured against
# Its upstream when the branch has one. Otherwise, for any branch but main, origin/main: a
# worktree branch here usually has no upstream at all, because this repo lands a branch with
# `git push origin <branch>:main` rather than publishing it, and a branch measured against
# nothing would never report the work it has not landed.
push_base() {
    if [ -n "$UPSTREAM" ]; then printf '%s' "$UPSTREAM"; return 0; fi
    [ "$BRANCH" != main ] || return 1
    git rev-parse --verify -q origin/main >/dev/null 2>&1 || return 1
    printf '%s' origin/main
}

# The full run fetches once, then every check reads the same remote-tracking refs.
FETCHED=0
fetch_once() {
    [ "$FETCHED" = 1 ] && return 0
    git fetch -q origin 2>/dev/null || true
    FETCHED=1
}

# ------------------------------------------------------------------ acknowledgement
# Ownership of an uncommitted file is NOT machine-decidable here, and that was tested rather
# than assumed: Claude Code records the paths a session touched in the transcript's
# `file-history-snapshot.trackedFileBackups`, but only for the Edit/Write tools, so a session that
# writes its files through Bash leaves that map EMPTY — attribution would have called its own work
# a peer's and stopped blocking, which is the worst direction to be wrong in.
#
# So the judgement stays with the session, and `ack` is what makes it cost one line instead of
# a paragraph on every run. It is keyed to the exact set of files: dirty one more and the cap
# comes back. It never applies to anything but the shared-checkout case, because every other
# cap is about state this session can actually change.
ack_file() { [ -n "$SESSION_ID" ] && printf '%s' "$CFG/bilan/$(printf '%s' "$SESSION_ID" | tr -c 'a-zA-Z0-9._-' '_').ack.json"; }

signature_of() { printf '%s' "$1" | sort | shasum 2>/dev/null | cut -d' ' -f1; }

ack_reason_for() { # <field> <signature>
    local f
    f=$(ack_file) || return 1
    [ -f "$f" ] || return 1
    jq -r --arg k "$1" --arg s "$2" 'select(.[$k] == $s) | .why // empty' "$f" 2>/dev/null | grep . || return 1
}

# Written by the SessionStart hook. A path already dirty when the session opened belongs to
# whoever dirtied it, which was not this session — the one piece of ownership that IS decidable.
# Paths, not content: a peer who keeps editing the same file is still that peer, and treating a
# changed hash as "now mine" would hand their work back to the cap it was meant to escape.
baseline_file() { [ -n "$SESSION_ID" ] && printf '%s' "$CFG/bilan/$(printf '%s' "$SESSION_ID" | tr -c 'a-zA-Z0-9._-' '_').baseline"; }

# A session with NO baseline cannot attribute anything, and defaulting to "it is all yours" is
# how one unpushed commit in the shared checkout came to block every other session over work
# none of them had done. When the baseline is missing entirely, bilan has not been watching this
# session, so it starts watching now: the state it finds on its first run is the state it
# inherited. It can only ever measure change from the moment it began observing, and claiming
# otherwise is what did the damage.
ensure_baseline() {
    local f
    f=$(baseline_file) || return 0
    [ -f "$f" ] && return 0
    cmd_baseline >/dev/null 2>&1
    say_note "no baseline existed for this session — everything already uncommitted or unpushed is treated as inherited, and only changes from here are this session's"
}

# --if-missing is what the SessionStart hook passes. That hook fires again on resume and after a
# compaction, inside the SAME session, and re-recording there would hand everything the session
# had dirtied so far to "inherited" — a false 10 in the middle of its own work.
cmd_baseline() { # [--if-missing]
    local f u up pf d
    f=$(baseline_file) || return 0
    if [ "${1:-}" = --if-missing ] && [ -f "$f" ]; then
        say "baseline: kept the one recorded when this session opened"
        return 0
    fi
    mkdir -p "$(dirname "$f")" 2>/dev/null || return 0
    # Untracked paths too, each one named: a peer's untracked file is no more this session's than
    # their edit, and -uall because the default collapses a wholly untracked directory to "dir/",
    # which would then cover every file the session later adds inside it.
    git status --porcelain -uall 2>/dev/null | sed 's/^...//' > "$f"
    # Commits carry the same inheritance as files: a peer's cherry-pick sitting unpushed in the
    # shared checkout when this session opened is not this session's to push.
    if up=$(push_base); then
        git rev-list "$up..HEAD" 2>/dev/null > "${f}.commits" || : > "${f}.commits"
    else
        : > "${f}.commits"
    fi
    # HEAD at session start, so "did this session commit anything at all" is answerable.
    git rev-parse HEAD 2>/dev/null > "${f}.head" || : > "${f}.head"
    # Which checkout all of the above describes (see baseline_here).
    printf '%s\n' "$THIS_CHECKOUT" > "${f}.checkout"
    # The dev stack, by (name, pid), in a checkout that runs one through bin/dev-processes.sh
    # (see check_dev_stack). A peer starting their stack from a shared checkout writes pid files
    # that are the CHECKOUT's, and dev_owned only asks whether the process is alive and
    # unrecycled — never who started it. Name alone would be too coarse: if the peer's server
    # dies and this session starts its own, the pid differs and it is genuinely ours.
    : > "${f}.stack"
    for pf in "$REPO_ROOT"/logs/*.pid; do
        [ -f "$pf" ] || continue
        printf '%s %s\n' "$(basename "$pf" .pid)" "$(head -1 "$pf" 2>/dev/null)" >> "${f}.stack"
    done
    u=$(grep -c . "${f}.commits" 2>/dev/null); u=${u:-0}
    d=$(grep -c . "$f" 2>/dev/null); d=${d:-0}
    say "baseline: $d file(s) dirty and $u commit(s) unpushed at session start"
    return 0
}

# The baseline's paths and HEAD describe the checkout the session opened in, and say nothing about
# any other. Applied elsewhere they were wrong in the worst direction: a session that opened in the
# main checkout beside a peer's dirty pom.xml, then edited pom.xml in a worktree it created, had its
# own edit read as inherited, for a false 10. So in any other checkout no path is inherited: one the
# session created holds nothing older than the session, and a peer's pre-existing worktree is what
# `ack` is for. That checkout's HEAD at the session's start comes from its own reflog instead (see
# start_head_here). The unpushed commits are the exception, and apply everywhere: a sha names the
# same commit in every checkout, so a peer's commit that was unpushed when the session opened is
# still the peer's in a worktree made on top of it. A baseline recorded before the checkout was
# kept with it has no .checkout, and applies as it always did.
baseline_here() {
    local f c
    f=$(baseline_file) || return 1
    [ -f "${f}.checkout" ] || return 0
    c=$(head -1 "${f}.checkout" 2>/dev/null)
    [ -n "$c" ] && [ "$c" = "$THIS_CHECKOUT" ]
}

# Split a newline-separated path list into what this session must answer for and what it
# inherited. A session that opened into someone else's mid-edit answers for neither.
not_mine() { # <paths>  -> prints the inherited subset
    local f
    f=$(baseline_file) || return 0
    [ -s "$f" ] || return 0
    baseline_here || return 0
    printf '%s\n' "$1" | grep -Fxf "$f" 2>/dev/null || true
}

# Not checked against baseline_here: a sha is the same commit in every checkout. Checked there, a
# worktree the session made from the shared checkout called the peer's inherited commit its own,
# with `git push origin <branch>:main` — landing the peer's work — as the remedy.
not_mine_commits() { # <shas> -> prints the inherited subset
    local f
    f=$(baseline_file) || return 0
    [ -s "${f}.commits" ] || return 0
    printf '%s\n' "$1" | grep -Fxf "${f}.commits" 2>/dev/null || true
}

without() { # <paths> <paths to drop> -> the first list minus the second
    [ -n "$2" ] || { printf '%s' "$1"; return 0; }
    printf '%s\n' "$1" | grep -Fxv -f <(printf '%s\n' "$2") 2>/dev/null || true
}

# The checkout's uncommitted paths, split the way they are scored: INHERITED is what the baseline
# says was already there, OWN_TRACKED and OWN_UNTRACKED the rest. check_worktree scores these and
# ack signs them, both through this one function: when the two computed the set apart, ack signed
# every dirty path while the check looked up only the owned ones, so an ack made beside an
# inherited file never matched — it printed "accounted for", and the cap came straight back.
# -uall names every untracked file, as the baseline records them.
INHERITED=""
OWN_TRACKED=""
OWN_UNTRACKED=""
split_dirty() {
    local porcelain tracked untracked
    porcelain=$(git status --porcelain -uall 2>/dev/null)
    tracked=$(printf '%s\n' "$porcelain" | grep -v '^??' | sed 's/^...//')
    untracked=$(printf '%s\n' "$porcelain" | grep '^??' | sed 's/^...//')
    INHERITED=$(not_mine "$(printf '%s\n%s' "$tracked" "$untracked")")
    OWN_TRACKED=$(without "$tracked" "$INHERITED")
    OWN_UNTRACKED=$(without "$untracked" "$INHERITED")
}

cmd_ack() { # <why>
    local why=$1 f commits up sig usig csig files
    [ -n "$why" ] || die "ack needs --why: say whose work this is and why you are leaving it"
    f=$(ack_file) || die "not inside a Claude Code session"
    split_dirty
    commits=""
    up=$(push_base) && commits=$(git rev-list "$up..HEAD" 2>/dev/null || true)
    [ -n "$OWN_TRACKED$OWN_UNTRACKED$commits" ] || { say "nothing uncommitted or unpushed to account for"; return 0; }
    # One signature per cap, so each clears on its own set: a scratch file made after the ack
    # brings back the untracked cap, not the tracked one.
    sig=$(signature_of "$OWN_TRACKED"); usig=$(signature_of "$OWN_UNTRACKED"); csig=$(signature_of "$commits")
    files=$(printf '%s\n%s\n' "$OWN_TRACKED" "$OWN_UNTRACKED" | grep -v '^$' || true)
    mkdir -p "$(dirname "$f")"
    jq -n --arg s "$sig" --arg u "$usig" --arg c "$csig" --arg w "$why" --arg at "$(now)" \
       --arg files "$(printf '%s' "$files" | tr '\n' ' ')" \
       '{signature:$s, untracked_signature:$u, commit_signature:$c, why:$w, at:$at, files:$files}' > "$f"
    say "📌 accounted for: $(printf '%s\n' "$files" | grep -c .) file(s), $(printf '%s\n' "$commits" | grep -c .) commit(s) — $why"
    say "   this covers exactly that set; dirty one more file or make one more commit and the cap returns."
    return 0
}

# ------------------------------------------------------------------ checks · git
# Several sessions can share one checkout, so "5 tracked files modified" is not enough to act
# on: a count gives no way to see that the files are a peer's in-flight edit. Name the files.
# Ownership is not decidable from here — most edits go through Bash, so the transcript's
# file_path arguments see only some of them, and mtime is not authorship — so bilan names what it
# found and leaves the judgement to the session.
name_files() { # <max> <newline-separated paths>
    local max=$1 list names count
    list=$(printf '%s\n' "$2" | grep -v '^$')
    count=$(printf '%s\n' "$list" | wc -l | tr -d ' ')
    names=$(printf '%s\n' "$list" | head -"$max" | tr '\n' ' ')
    if [ "$count" -gt "$max" ]; then
        printf '%s and %s more' "$names" "$((count - max))"
    else
        printf '%s' "${names% }"
    fi
}

check_worktree() {
    local t_n u_n i_n why
    split_dirty
    [ -n "$INHERITED$OWN_TRACKED$OWN_UNTRACKED" ] || return 0
    i_n=$(printf '%s\n' "$INHERITED" | grep -cv '^$')
    t_n=$(printf '%s\n' "$OWN_TRACKED" | grep -cv '^$')
    u_n=$(printf '%s\n' "$OWN_UNTRACKED" | grep -cv '^$')

    # Inherited dirt is stated, never scored. It is not this session's completion.
    [ "${i_n:-0}" -gt 0 ] && say_note "$i_n file(s) were already uncommitted when this session opened — not its work: $(name_files 5 "$INHERITED")"

    if [ "${t_n:-0}" -gt 0 ]; then
        # An ack is a recorded statement of ownership, so it CLEARS rather than softens: a
        # peer's file is not this session's incompleteness, and leaving it at 9 meant a session
        # that had done everything right still could not reach 10. It stays visible in every
        # later report, is keyed to the exact path set, and returns the moment one more file
        # goes dirty.
        if why=$(ack_reason_for signature "$(signature_of "$OWN_TRACKED")"); then
            say_note "$t_n uncommitted file(s) declared not this session's: $why"
        else
            cap 7 "❌" "$t_n tracked file(s) modified and uncommitted: $(name_files 8 "$OWN_TRACKED")" \
                  "commit them — or, if they are a peer's in this shared checkout, bilan.sh ack --why '…'"
        fi
    fi
    # The same rule for untracked files. The remedy acts only on the session's own: a file that
    # was there before it opened is inherited above, and a peer's made since is what ack is for —
    # never "delete them", which would be deleting somebody else's work.
    if [ "${u_n:-0}" -gt 0 ]; then
        if why=$(ack_reason_for untracked_signature "$(signature_of "$OWN_UNTRACKED")"); then
            say_note "$u_n untracked file(s) declared not this session's: $why"
        else
            cap 9 "⚠️" "$u_n untracked file(s): $(name_files 8 "$OWN_UNTRACKED")" \
                  "add them, or move them to the scratchpad — or, if they are a peer's in this shared checkout, bilan.sh ack --why '…'"
        fi
    fi
}

# Prints this session's own unpushed shas: everything ahead of the push base, minus what was
# already unpushed when the session opened, minus what an ack has declared. Empty means this
# session has nothing of its own waiting to go out — whatever else is sitting in the checkout.
owned_unpushed() {
    local base shas inherited owned
    base=$(push_base) || return 0
    [ "$CHEAP" = 1 ] || fetch_once
    shas=$(git rev-list "$base..HEAD" 2>/dev/null)
    [ -n "$shas" ] || return 0
    ack_reason_for commit_signature "$(signature_of "$shas")" >/dev/null 2>&1 && return 0
    inherited=$(not_mine_commits "$shas")
    if [ -n "$inherited" ]; then
        owned=$(printf '%s\n' "$shas" | grep -Fxv -f <(printf '%s\n' "$inherited") 2>/dev/null || true)
    else
        owned=$shas
    fi
    printf '%s' "$owned"
}

# Epoch mtime of a file, or 0 when it cannot be read.
#
# The BSD spelling must NOT be tried first. On GNU, `-f` is --file-system and `%m` is read as
# another FILE operand, so `stat -f %m <file>` SUCCEEDS — printing multi-line human text that
# begins `File: "…"` — and a `|| stat -c %Y` fallback behind it never runs. That text then
# reached an arithmetic expansion, where `File:` is a bare word: every Linux run printed
# `File: unbound variable` twice and measured the fetch age as garbage, which in turn made
# `[ "$(fetch_age)" -gt 300 ]` fail with "integer expression expected".
#
# So: GNU spelling first, BSD second, and the answer is used only once it is all digits —
# because the lesson of the original is that an exit status alone did not distinguish the two.
file_mtime() {
    local m
    m=$(stat -c %Y "$1" 2>/dev/null) || m=$(stat -f %m "$1" 2>/dev/null) || m=""
    case $m in
        '' | *[!0-9]*) printf '%s' 0 ;;
        *) printf '%s' "$m" ;;
    esac
}

# Seconds since the last fetch from ANY checkout of this repo, or a large number when there has
# never been one. FETCH_HEAD is per worktree while the remote-tracking refs it refreshes are
# shared, so a fetch run from the main checkout keeps origin/main fresh for every worktree too.
fetch_age() {
    local f m newest=0
    for f in "$DOT_GIT/FETCH_HEAD" "$COMMON_DIR/FETCH_HEAD" "$COMMON_DIR"/worktrees/*/FETCH_HEAD; do
        [ -f "$f" ] || continue
        m=$(file_mtime "$f")
        [ "$m" -gt "$newest" ] && newest=$m
    done
    [ "$newest" -gt 0 ] || { printf '%s' 999999; return 0; }
    printf '%s' "$(( $(date +%s) - newest ))"
}

check_unpushed() {
    local base shas inherited owned n why stale=0
    base=$(push_base) || return 0
    # The full run can settle it; the cheap run has to say it cannot.
    if [ "$CHEAP" = 0 ]; then
        fetch_once
    elif [ "$(fetch_age)" -gt 300 ]; then
        stale=1
    fi
    shas=$(git rev-list "$base..HEAD" 2>/dev/null)
    [ -n "$shas" ] || return 0

    # A peer's commit already sitting unpushed when this session opened is not this session's
    # to push — the same inheritance the dirty-file baseline records, one level up.
    inherited=$(not_mine_commits "$shas")
    if [ -n "$inherited" ]; then
        owned=$(printf '%s\n' "$shas" | grep -Fxv -f <(printf '%s\n' "$inherited") 2>/dev/null || true)
        say_note "$(printf '%s\n' "$inherited" | grep -c .) commit(s) were already unpushed when this session opened — not its work"
    else
        owned=$shas
    fi
    n=$(printf '%s\n' "$owned" | grep -cv '^$')
    [ "${n:-0}" -gt 0 ] || return 0

    if why=$(ack_reason_for commit_signature "$(signature_of "$shas")"); then
        say_note "$n unpushed commit(s) declared not this session's: $why"
        return 0
    fi
    if [ "$stale" = 1 ]; then
        cap 9 "⚠️" "$n commit(s) look unpushed, but $base was last fetched $(( $(fetch_age) / 60 ))m ago — they may already be on the remote" \
              "run bilan.sh without --cheap, which fetches first, before acting on this"
        return 0
    fi
    if [ -n "$UPSTREAM" ]; then
        cap 8 "❌" "$n commit(s) not pushed to $UPSTREAM ($(printf '%s\n' "$owned" | cut -c1-8 | tr '\n' ' '))" \
              "git push — or, if they are a peer's in this shared checkout, bilan.sh ack --why '…'"
    else
        cap 8 "❌" "branch $BRANCH has $n commit(s) not on origin/main and no upstream — never pushed" \
              "./scripts/pre-push-validate.sh, then git push origin $BRANCH:main — or hand the branch to whoever lands it"
    fi
}

# The stash stack is ONE stack, shared by the main checkout and every worktree, and parallel
# sessions push onto it. An entry made on another branch was made in another checkout, so it
# is stated and never counted; one made on this branch during this session is this session's.
check_stash() {
    local start line ts subj b count=0 others=0
    start=$(session_started_epoch 2>/dev/null) || return 0
    [ -n "$start" ] || return 0
    b=$BRANCH
    [ "$b" = HEAD ] && b="(no branch)"
    while IFS= read -r line; do
        ts=${line%% *}
        subj=${line#* }
        [ -n "$ts" ] || continue
        [ "$ts" -ge "$start" ] 2>/dev/null || continue
        case "$subj" in
            "On $b: "* | "WIP on $b: "*) count=$((count + 1)) ;;
            *) others=$((others + 1)) ;;
        esac
    done <<< "$(git log -g --format='%ct %gs' refs/stash 2>/dev/null)"
    [ "$others" = 0 ] || say_note "$others stash entr(y|ies) made on another branch during this session — another checkout's, since every worktree shares the stash stack; not counted"
    [ "$count" -gt 0 ] || return 0
    cap 9 "⚠️" "$count stash entr(y|ies) created on $b during this session" \
          "apply or drop them — by their exact ref, never a bare pop, since every worktree shares the stash stack"
}

# The reliable squash-merge tell: `git push origin --delete` is what clears the upstream, so a
# local branch whose upstream is gone is one the cleanup half-finished. A squash rewrites the
# sha, so `git branch --merged` cannot see it and is not used here.
#
# Read with for-each-ref, not `git branch -vv`, whose first column is a marker for two kinds of
# branch: "* <name>" for this checkout's and "+ <name>" for one another worktree has checked out.
# Taking that column as the name reported a peer's branch as "+" — every checkout capped over a
# branch it cannot delete, with `git branch -D +` as the remedy — and this checkout's own as
# nothing at all. A branch another worktree has checked out is that worktree's to clean up.
check_branch_cleanup() {
    local gone
    gone=$(git for-each-ref --format='%(refname:lstrip=2)%09%(upstream:track)%09%(worktreepath)' refs/heads 2>/dev/null \
           | awk -F'\t' -v me="$BRANCH" '$2 == "[gone]" && ($3 == "" || $1 == me) { print $1 }' | tr '\n' ' ')
    [ -n "${gone// /}" ] || return 0
    gone=${gone% }
    case " $gone " in
        *" $BRANCH "*)
            cap 9 "⚠️" "local branch(es) whose upstream is deleted: $gone" \
                  "this checkout is on $BRANCH, and git branch -D refuses a checked-out branch: switch it off $BRANCH (a linked worktree: git worktree remove it from the main checkout), then git branch -D $gone" ;;
        *)
            cap 9 "⚠️" "local branch(es) whose upstream is deleted: $gone" "git branch -D $gone" ;;
    esac
}

# The marker is scripts/pre-push-validate.sh's, one line "<epoch> <sha>" in this checkout's own
# git dir, and the pre-push hook refuses a push whose marker is missing, stale or for another
# sha. The TTL is the hook's, read from it rather than restated here, so the two cannot disagree
# about when a marker expires.
validation_ttl() {
    local m
    m=$(sed -n 's/^VALIDATION_TTL_MINUTES=\([0-9][0-9]*\).*/\1/p' "$REPO_ROOT/.githooks/pre-push" 2>/dev/null | head -1)
    printf '%s' "$(( ${m:-30} * 60 ))"
}

check_validation_marker() {
    local marker epoch sha age ttl
    # Only this session's own commits. A peer's inherited cherry-pick is not something this
    # session can or should validate.
    [ -n "$(owned_unpushed)" ] || return 0
    marker="$DOT_GIT/validation-passed"
    if [ ! -f "$marker" ]; then
        cap 9 "⚠️" "commits to push but no .git/validation-passed marker" \
              "./scripts/pre-push-validate.sh"
        return 0
    fi
    read -r epoch sha < "$marker"
    case ${epoch:-} in
        '' | *[!0-9]*)
            cap 9 "⚠️" "validation marker is unreadable — it is not '<epoch> <sha>'" \
                  "./scripts/pre-push-validate.sh"
            return 0 ;;
    esac
    if [ "${sha:-}" != "$HEAD_SHA" ]; then
        cap 9 "⚠️" "validation marker is for ${sha:0:8}, HEAD is ${HEAD_SHA:0:8}" \
              "./scripts/pre-push-validate.sh (it pins the sha)"
        return 0
    fi
    ttl=$(validation_ttl)
    age=$(( $(date +%s) - epoch ))
    [ "$age" -le "$ttl" ] || cap 9 "⚠️" "validation marker is $((age / 60))m old (TTL $((ttl / 60))m)" \
                                   "./scripts/pre-push-validate.sh"
}

# ------------------------------------------------------------------ checks · carnet
check_carnet_held() {
    local f row list=""
    f=$(ledger_file) || return 0
    [ -s "$f" ] || return 0
    for row in $(ledger_rows "$f" claim); do
        list="$list $(issue_label "${row%%|*}" "${row#*|}")"
    done
    [ -n "${list// /}" ] || return 0
    cap 6 "❌" "still holding${list} — claimed by this session, neither closed nor released" \
          "carnet.sh close <n> --why … --commit <sha>, or release <n> --reason …"
}

# `create` writes a "filed" line and `close` removes it, so what remains is what this session
# opened and did not fix. That caps at 6 — level with an issue still held, so the session cannot
# quietly walk away from issues it opened.
#
# The standing rule is fix first and file only the residue; this is what makes the second half
# of it visible. If something genuinely cannot be fixed here, that is a decision to put in front
# of the project maintainer, not a cap to slip past.
#
# A registered limitation is the one filed issue that is NOT work owed, and it has to be exempt.
# The LIMITATION procedure REQUIRES an open issue for as long as a marker names it — a marker must
# be backed by an issue in the tracker — so without the exemption a session that followed that
# procedure correctly was capped at 6 for complying, with no action available to it: nothing to
# fix, and nothing honest to close.
#
# The exemption needs BOTH halves, which is what keeps it from being a loophole:
#   - the `limitation` label on the issue, and
#   - a LIMITATION(registre#n) marker naming that same issue, in a file the register scans.
# A label alone still caps, so a bug cannot be relabelled out of the score. A marker naming a dead
# issue is already caught by check_limitation_markers, from the other side. Both together mean the
# issue is a register entry rather than deferred work — and it is printed as a NOTE, so the gap
# stays visible instead of disappearing into a clean pass, which is the whole point of registering.
#
# Both halves are answerable without the network, and have to be: the status line runs --cheap,
# and while the label could only be read from GitHub, --cheap capped every correctly registered
# limitation at 6 for as long as the register required it to stay open. carnet.sh records the
# label in the session ledger when it applies it (`create --label limitation`, `label
# +limitation`), so --cheap reads that line, and the full run still asks the tracker, which is the
# authority — falling back to the ledger only when the tracker cannot be asked at all.
labelled_limitation() { # <issue-number> <tracker>
    local f labels
    # REST, for the reason tracker_state gives.
    if [ "$CHEAP" = 0 ] && valid_issue_ref "$1" "$2" && command -v gh >/dev/null 2>&1 \
       && labels=$(gh api "repos/$2/issues/$1" --jq '.labels[].name' 2>/dev/null); then
        printf '%s\n' "$labels" | grep -qx limitation
        return
    fi
    f=$(ledger_file) || return 1
    [ -f "$f" ] || return 1
    jq -c --argjson n "$1" --arg t "$2" --arg d "$TRACKER" \
        'select(.kind == "limitation" and .issue == $n and ((.tracker // $d) == $t))' "$f" 2>/dev/null | grep -q .
}

registered_limitation() { # <issue-number> <tracker> -> 0 when this is a register entry, not work owed
    # The marker half is this checkout's scope, so it can only speak for this repo's register.
    [ -n "$TRACKER" ] && [ "$2" = "$TRACKER" ] || return 1
    labelled_limitation "$1" "$2" && marker_in_scope "$1"
}

check_carnet_filed() {
    local f row n t list="" unknown="" state
    f=$(ledger_file) || return 0
    [ -s "$f" ] || return 0
    for row in $(ledger_rows "$f" filed); do
        n=${row%%|*}
        t=${row#*|}
        # Someone else may have closed it. Only the full run can tell; --cheap keeps the cap,
        # which is the safe direction for a rule about not walking away from your own issues.
        state=open
        if [ "$CHEAP" = 0 ] && [ -n "$t" ] && command -v gh >/dev/null 2>&1; then
            state=$(tracker_state "$n" "$t")
            [ "$state" = closed ] && continue
        fi
        if registered_limitation "$n" "$t"; then
            say_note "$(issue_label "$n" "$t") is a registered limitation, not work owed: labelled \`limitation\` and named by a LIMITATION(registre#$n) marker in the register's scope, which the register contract requires to stay open"
            continue
        fi
        # A tracker that did not answer keeps the cap — the safe direction — but is not reported
        # as an answer: "still open" is what the tracker says, and here it said nothing.
        if [ -n "$state" ]; then
            list="$list $(issue_label "$n" "$t")"
        else
            unknown="$unknown $(issue_label "$n" "$t")"
        fi
    done
    [ -z "${list// /}" ] || cap 6 "❌" "filed this session and still open:${list}" \
          "fix them and close with carnet.sh close <n> --why … --commit <sha> — a session does not file its way out of work"
    [ -z "${unknown// /}" ] || cap 6 "❌" "filed this session, and the tracker did not say whether it is closed:${unknown}" \
          "gh auth status, then rerun — and if it is still open, fix it and close it with carnet.sh close <n> --why … --commit <sha>"
    return 0
}

# ------------------------------------------------------------------ the register's scope
# A LIMITATION marker is the sanctioned way to ship a gap, but only when it names a live issue —
# and where a marker counts is the register's decision, not bilan's. The register here is
# llm-registre, a git submodule at .registre; its gate scans the directories its callers name,
# for the extensions registre.toml configures, minus test, bench, example and generated trees.
# bilan asks the gate for that set rather than keeping a copy of the exclusions: a copy drifts
# from the gate both ways, and a marker one tool honoured was invisible to the other.
REGISTRE_GATES="$REPO_ROOT/.registre/limitation-gates.sh"
SCOPE_UNKNOWN="#unknown"
SCOPE_VIA=""

# A bare `key = value` scalar from registre.toml, read the way the gate's own config_value reads it.
toml_value() { # <key>
    [ -f "$REPO_ROOT/registre.toml" ] || return 0
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/registre.toml" \
        | head -1 | sed 's/[[:space:]]*#.*$//' | tr -d '"'\''' | tr -d '[:space:]'
}

# The directories the register scans, one per line: whatever the CI lane passes the gate — the
# lane that holds main to the register — read out of the workflow rather than restated here, so
# a directory added there is a directory bilan scans. `scan_dirs` in registre.toml, and
# REGISTRE_SCAN_DIRS over it, replace them for bilan alone: the gate this repo pins reads
# neither, and both of its callers name the directories on the command line, so either setting
# narrows what bilan verifies and never what the gate scans. A lane whose arguments are not
# literal (a variable, a quote) names nothing bilan can read, and the scope is then unknown
# rather than guessed.
scan_dirs() {
    local v wf line w out words=()
    v=${REGISTRE_SCAN_DIRS:-}
    [ -n "$v" ] || v=$(toml_value scan_dirs)
    if [ -n "$v" ]; then
        printf '%s\n' "$v" | tr ',' '\n' | grep .
        return 0
    fi
    # The one lane, the one bilan.yml's path filter names: reading every workflow for the first
    # line that mentions the gate let any other one — or a step name — decide the scope by
    # sorting first, in a file whose change never runs the bilan lane. Only an invocation of the
    # gate counts, never a line that merely names it.
    wf="$REPO_ROOT/.github/workflows/limitation-register.yml"
    [ -f "$wf" ] || return 0
    line=$(grep -E '^[^#]*\.registre/limitation-gates\.sh[[:space:]]+[^[:space:]#]' "$wf" 2>/dev/null | head -1)
    [ -n "$line" ] || return 0
    line=${line#*limitation-gates.sh}
    line=${line%%#*}
    read -r -a words <<< "$line"
    out=""
    for w in ${words[@]+"${words[@]}"}; do
        case "$w" in
            -*) continue ;;                          # an option to the gate, not a directory
            *[!A-Za-z0-9._/-]*) out=""; break ;;     # not literal: nothing to read
        esac
        out="$out$w"$'\n'
    done
    printf '%s' "$out"
    return 0
}

# The extensions the register scans: REGISTRE_EXTENSIONS, else registre.toml, else the gate's own
# default. Only consulted when the gate cannot be asked (see check_limitation_markers).
register_extensions() {
    local e=${REGISTRE_EXTENSIONS:-}
    [ -n "$e" ] || e=$(toml_value extensions)
    printf '%s' "${e:-rs,ts,tsx}"
}

gate_lists_files() { grep -q -- '--list-files' "$REGISTRE_GATES" 2>/dev/null; }

# A gate that predates --list-files still decides its own scope: every version routes each scan
# through one `rg <pattern> <dirs…> -g <include>… -g !<exclude>…` call. So the gate runs once
# with a stand-in ripgrep first on PATH that records its arguments and answers "no match" — the
# whole run costs milliseconds — and the listing is `rg --files` over exactly the directories and
# globs the gate composed, which is what --list-files itself runs. Nothing is copied: the
# directories that exist, the configured extensions and the built-in exclusions all arrive as
# the gate built them.
probe_scope() { # <dir>... -> the gate's scope on stdout; 1 when the gate made no scoped call
    local real tmp f a want_glob found=1 dirs=() globs=()
    real=$(command -v rg 2>/dev/null) || return 1
    tmp=$(mktemp -d "${TMPDIR:-/tmp}/bilan-rg.XXXXXX") || return 1
    mkdir "$tmp/calls" || { rm -rf "$tmp"; return 1; }
    cat > "$tmp/rg" <<'SHIM'
#!/usr/bin/env bash
f=$(mktemp "$BILAN_RG_CALLS/call.XXXXXX") || exit 2
printf '%s\0' "$@" > "$f"
exit 1
SHIM
    chmod +x "$tmp/rg"
    ( cd "$REPO_ROOT" && BILAN_RG_CALLS="$tmp/calls" PATH="$tmp:$PATH" "$REGISTRE_GATES" "$@" ) \
        </dev/null >/dev/null 2>&1
    for f in "$tmp/calls"/call.*; do
        [ -f "$f" ] || continue
        dirs=(); globs=(); want_glob=0
        while IFS= read -r -d '' a; do
            if [ "$want_glob" = 1 ]; then globs+=(-g "$a"); want_glob=0; continue; fi
            case "$a" in
                -g | --glob) want_glob=1 ;;
                --glob=*) globs+=(-g "${a#--glob=}") ;;
                -*) : ;;                                  # a flag the scan needed; --files lists regardless
                *) [ -d "$REPO_ROOT/$a" ] && dirs+=("$a") ;;
            esac
        done < "$f"
        [ "${#dirs[@]}" -gt 0 ] && [ "${#globs[@]}" -gt 0 ] || continue
        ( cd "$REPO_ROOT" && "$real" --files "${dirs[@]}" "${globs[@]}" 2>/dev/null ) | sed 's#^\./##' | sort
        found=0
        break
    done
    rm -rf "$tmp"
    return "$found"
}

scope_is_listing() { local first; first=$(head -1 "$SCOPE" 2>/dev/null); [ -n "$first" ] && [ -f "$REPO_ROOT/$first" ]; }
scope_unknown_reason() { head -1 "$SCOPE" 2>/dev/null | cut -f2; }

register_scope() { # -> 0 with every in-scope path in $SCOPE; 1 when the register cannot say
    local d why="" dirs=()
    if [ ! -s "$SCOPE" ]; then
        # Only directories that exist: the gate skips the others, and a lane that names none of
        # them here has nothing to scan.
        while IFS= read -r d; do
            [ -n "$d" ] && [ -d "$REPO_ROOT/$d" ] && dirs+=("$d")
        done <<< "$(scan_dirs)"
        if [ ! -x "$REGISTRE_GATES" ]; then
            why=gate
        elif [ "${#dirs[@]}" -eq 0 ]; then
            why=dirs
        elif ! command -v rg >/dev/null 2>&1; then
            why=rg
        elif gate_lists_files; then
            if ( cd "$REPO_ROOT" && "$REGISTRE_GATES" --list-files "${dirs[@]}" ) </dev/null > "$SCOPE" 2>/dev/null \
               && scope_is_listing; then
                SCOPE_VIA="list-files"
            else
                why=list
            fi
        elif probe_scope "${dirs[@]}" > "$SCOPE" && scope_is_listing; then
            SCOPE_VIA=probe
        else
            why=list
        fi
        [ -z "$why" ] || printf '%s\t%s\n' "$SCOPE_UNKNOWN" "$why" > "$SCOPE"
    fi
    case "$(head -1 "$SCOPE")" in "$SCOPE_UNKNOWN"*) return 1 ;; esac
    return 0
}

scope_remedy() {
    case "$(scope_unknown_reason)" in
        gate) printf '%s' "git submodule update --init .registre (bilan asks the register's gate which files it scans), then rerun" ;;
        dirs) printf '%s' "none of the directories the register is run on exists here — give the gate call in .github/workflows/limitation-register.yml literal directories (REGISTRE_SCAN_DIRS and registre.toml scan_dirs move bilan's scope alone, never the gate's), then rerun" ;;
        rg)   printf '%s' "install ripgrep (the register scans with it), then rerun" ;;
        *)    printf '%s' "run .registre/limitation-gates.sh by hand — it listed nothing bilan could read — then rerun" ;;
    esac
}

marker_in_scope() { # <issue-number> -> 0 when a marker in a scanned file names that issue
    register_scope || return 1
    ( cd "$REPO_ROOT" && tr '\n' '\0' < "$SCOPE" \
        | xargs -0 grep -l -F "LIMITATION(registre#$1):" 2>/dev/null | grep -q . )
}

# Every added line that carries a LIMITATION( marker, as "path<TAB>line" — committed but not
# pushed, and still in the tree. Committed lines are measured from the merge base, so a branch
# that is merely behind its base is neither credited with nor blamed for lines it has not merged.
added_marker_lines() {
    local base
    base=$(push_base) || { git rev-parse --verify -q origin/main >/dev/null 2>&1 && base=origin/main; } || base=""
    { [ -z "$base" ] || git diff -U0 --no-color --no-ext-diff --src-prefix=a/ --dst-prefix=b/ "$base...HEAD"
      git diff -U0 --no-color --no-ext-diff --src-prefix=a/ --dst-prefix=b/ HEAD; } 2>/dev/null \
        | awk '/^\+\+\+ /{ f = substr($0, 7); next } /^\+/ && /LIMITATION\(/ { print f "\t" substr($0, 2) }'
}

# What is wrong with the issue a marker names, or nothing when it is a live register entry: it
# exists, is open, and carries the `limitation` label — the three conditions llm-registre's
# tracker reconciliation requires of every marker. This asks them only of the markers this
# session added, so the session that wrote a bad one hears about it before anyone else does.
# REST, not `gh issue view`: GraphQL is refused where some sessions run, and a refusal here would
# read as a missing issue. The tracker is private, so a token that cannot read it is told apart
# from an issue that is not there — both leave the marker unverified, only one is the marker's fault.
marker_issue_problem() { # <issue-number> -> a reason on stdout, or nothing
    local answer tab
    tab=$(printf '\t')
    if ! answer=$(gh api "repos/$TRACKER/issues/$1" \
        --jq '[.state, ((.labels | map(.name) | index("limitation")) != null), (.pull_request != null)] | @tsv' \
        2>/dev/null); then
        if gh api "repos/$TRACKER" --jq .full_name >/dev/null 2>&1; then
            printf 'not found on the tracker'
        else
            printf 'unverified: %s unreadable, see gh auth status' "$TRACKER"
        fi
        return 0
    fi
    case "$answer" in
        "open${tab}true${tab}false") : ;;
        *"${tab}true") printf 'a pull request' ;;
        closed*) printf 'closed' ;;
        *) printf 'not labelled limitation' ;;
    esac
}

# The gate's own format check already fails a malformed marker at pre-push and in CI. This check
# asks the tracker about the markers this session added, now. The loose `[^)]*` is kept so a
# marker naming no issue at all is reported here too, in the session that wrote it.
check_limitation_markers() {
    local lines p l ext exts unverifiable="" added marker n why bad=""
    lines=$(added_marker_lines)
    [ -n "$lines" ] || return 0
    if ! register_scope; then
        # The register cannot say what it scans — the submodule is not checked out, which is the
        # usual state of a fresh worktree, or its gate listed nothing. That leaves a marker this
        # session wrote unverifiable, so fail closed on exactly the markers that could be in
        # scope: files with an extension the register scans. A marker in prose, a script, or this
        # skill's own tree is outside every register's scope, and capping on it would hold every
        # session that merely documents the convention — this skill's own included — at 6.
        exts=",$(register_extensions),"
        while IFS=$'\t' read -r p _; do
            [ -n "$p" ] || continue
            ext=${p##*.}
            case "$exts" in *",$ext,"*) unverifiable="$unverifiable$p"$'\n' ;; esac
        done <<< "$lines"
        [ -n "$unverifiable" ] || return 0
        cap 6 "❌" "added a LIMITATION marker the register cannot scope: $(name_files 3 "$(printf '%s' "$unverifiable" | sort -u)")" \
              "$(scope_remedy)"
        return 0
    fi
    added=$(while IFS=$'\t' read -r p l; do
                [ -n "$p" ] && grep -qxF "$p" "$SCOPE" && printf '%s\n' "$l"
            done <<< "$lines" | grep -o 'LIMITATION(registre#[^)]*)' | sort -u)
    [ -n "$added" ] || return 0
    while IFS= read -r marker; do
        [ -n "$marker" ] || continue
        n=$(printf '%s' "$marker" | sed 's/[^0-9]//g')
        if [ -z "$n" ] || [ "$n" = 0 ]; then
            bad="$bad $marker"
        elif [ "$CHEAP" = 0 ] && [ -n "$TRACKER" ] && command -v gh >/dev/null 2>&1; then
            why=$(marker_issue_problem "$n")
            [ -z "$why" ] || bad="$bad #$n($why)"
        fi
    done <<< "$added"
    [ -n "${bad// /}" ] || return 0
    cap 6 "❌" "LIMITATION marker(s) naming no live issue:${bad}" \
          "a fixed gap: delete the marker; a real one: file it with carnet.sh create --label limitation and point the marker at that open issue — an unregistered gap is invisible debt"
}

# ------------------------------------------------------------------ checks · in-flight work
# A session can report 10/10 while subagents and a CI watcher are still running, and closing it
# then throws all of that away. Background work is the one kind of incompleteness that leaves NO
# trace in git, the ledger or CI — the session is the only thing that knows, and it is exactly
# what a session forgets when it thinks it is finished.
#
# Claude Code writes each background task's stream to <scratchpad>/tasks/<id>.output and closes
# it with "[exited with code N]" or "[killed]". No marker means it never ended. The scratchpad
# is keyed by the directory the session was launched from, which for a session working in a
# linked worktree is the main checkout, not this one — so both are tried.
main_checkout() { dirname "$COMMON_DIR"; }

task_dir() {
    local c root
    if [ -n "${CLAUDE_SCRATCHPAD_DIR:-}" ] && [ -d "$CLAUDE_SCRATCHPAD_DIR/../tasks" ]; then
        printf '%s' "$CLAUDE_SCRATCHPAD_DIR/../tasks"
        return 0
    fi
    [ -n "$SESSION_ID" ] || return 1
    for root in "$REPO_ROOT" "$(main_checkout)"; do
        for c in "/private/tmp/claude-$(id -u)/$(printf '%s' "$root" | sed 's#[/.]#-#g')/$SESSION_ID/tasks" \
                 "/tmp/claude-$(id -u)/$(printf '%s' "$root" | sed 's#[/.]#-#g')/$SESSION_ID/tasks"; do
            [ -d "$c" ] && { printf '%s' "$c"; return 0; }
        done
    done
    return 1
}

# Every pid from this shell up to init. bilan runs inside one of those task streams itself, and
# its own output file is open and unmarked exactly like a live task's. A file held only by this
# process tree is this invocation.
my_pids() {
    local p=$$ out=""
    while [ -n "$p" ] && [ "$p" != 0 ] && [ "$p" != 1 ]; do
        out="$out $p"
        p=$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ')
    done
    printf '%s' "$out"
}

check_running_tasks() {
    local dir f pids p mine running=0 names="" id is_mine
    command -v lsof >/dev/null 2>&1 || return 0
    dir=$(task_dir) || return 0
    mine=$(my_pids)
    for f in "$dir"/*.output; do
        [ -f "$f" ] || continue
        grep -q '\[exited with code\|\[killed\]' "$f" 2>/dev/null && continue
        pids=$(lsof -t -- "$f" 2>/dev/null) || continue
        [ -n "$pids" ] || continue        # nobody holds it: ended without writing a marker
        # ANY holder in this process's ancestry means the stream is this very invocation —
        # bilan is itself running inside one. Testing for a holder that is *not* mine reads
        # backwards: the shell's own subshells are descendants, so they never match the
        # ancestry walk and every run reported itself as a live task.
        is_mine=0
        for p in $pids; do
            printf '%s' " $mine " | grep -q " $p " && { is_mine=1; break; }
        done
        [ "$is_mine" = 1 ] && continue
        id=$(basename "$f" .output)
        running=$((running + 1)); names="$names $id"
    done
    [ "$running" -gt 0 ] || return 0
    cap 7 "❌" "$running background task(s) still running:${names}" \
          "wait for them, or TaskStop them deliberately — closing the session loses the work"
}

# The deepest blind spot, named by a session that scored 10/10 with its artifact unwritten:
# bilan measures repo state, and a session whose work is research, a document, or a published
# artifact commits nothing, holds no issue and triggers no CI, so every check came back clean and
# the number said done.
#
# The session's own todo list is the missing measurement. It is the session declaring what it
# set out to do, Claude Code keeps it per session under tasks/<session-id>/, and an item still
# pending or in progress is the session's own statement that it is not finished.
check_open_todos() {
    local dir n subjects
    dir="$CFG/tasks/$SESSION_ID"
    [ -d "$dir" ] || return 0
    n=$(jq -r 'select(.status == "pending" or .status == "in_progress") | .subject // .description // "?"' \
        "$dir"/*.json 2>/dev/null | grep -c . ) || return 0
    [ "${n:-0}" -gt 0 ] || return 0
    subjects=$(jq -r 'select(.status == "pending" or .status == "in_progress") | .subject // .description // "?"' \
        "$dir"/*.json 2>/dev/null | head -3 | cut -c1-60 | tr '\n' '·' | sed 's/·/ · /g; s/ · $//')
    cap 7 "❌" "$n todo(s) still open: $subjects" \
          "finish them, or drop the ones you are not doing — an open todo is this session saying it is not done"
}

# This checkout's HEAD when the session opened, or nothing when that cannot be told. The baseline
# recorded it for the checkout the session opened in. Any other checkout answers from its own HEAD
# reflog: the value HEAD held at the session's start, or — for a checkout that came into being
# after it, the session's own worktree — the commit it was made at. One case the baseline settles
# anywhere: a HEAD equal to the one the session opened at holds no commit made since.
start_head_here() {
    local f h start
    f=$(baseline_file) || return 0
    h=$(cat "${f}.head" 2>/dev/null || true)
    if [ -z "$h" ] || [ "$h" = "$HEAD_SHA" ] || baseline_here; then
        printf '%s' "$h"
        return 0
    fi
    start=$(session_started_epoch 2>/dev/null) || return 0
    # Newest first: the first entry at or before the start is HEAD at the start, and when none is,
    # the oldest entry is the checkout's first HEAD. awk stops at the answer, which cuts a walk of
    # a long reflog short; the writers upstream of it then die of SIGPIPE, silently.
    git log -g --date=unix --format='%gd %H' HEAD 2>/dev/null \
        | sed -n 's/^[^{]*@{\([0-9][0-9]*\)} \([0-9a-f][0-9a-f]*\)$/\1 \2/p' 2>/dev/null \
        | awk -v s="$start" '$1 <= s { print $2; found = 1; exit } { last = $2 } END { if (!found) printf "%s", last }'
}

# A score of 10 means "everything I checked is done". When nothing was checkable, 10 means
# nothing at all — and that is how a session scored 10/10 with its artifact unwritten. bilan
# reads the repo, the register and CI; a session whose work is research, a design, a document or
# a published artifact touches none of the three, and every check came back clean because every
# check came back empty.
#
# So it says so. A session that made no commit and declared no todo is unmeasured, not complete,
# and cannot reach 10. It caps at 9 rather than blocking, because answering a question really is
# a complete session; what it must not do is produce a verdict it never earned.
#
# Unmeasured is a verdict on a session that was asked something. The status line renders before
# the first prompt arrives, so this cap would otherwise read "this session made no commit" on a
# session nobody had yet asked anything of. No ask, no verdict: the request is the thing the
# verdict would be measured against.
#
# "No commit" is a question about this checkout's HEAD, so it is asked of this checkout's own start
# (start_head_here). Compared with the baseline's HEAD, which is the start of the checkout the
# session opened in, a worktree made at any other commit read as one that had committed, and
# scored 10 with nothing done.
check_measurable() {
    local start_head todos=0
    [ -d "$CFG/tasks/$SESSION_ID" ] && \
        todos=$(command ls "$CFG/tasks/$SESSION_ID"/*.json 2>/dev/null | grep -c . )
    [ "${todos:-0}" -gt 0 ] && return 0                 # the session declared what it set out to do
    start_head=$(start_head_here)
    [ -n "$start_head" ] || return 0                    # no baseline: cannot tell, do not claim
    [ "$start_head" = "$HEAD_SHA" ] || return 0         # it committed something: that is measurable
    [ -n "$(opening_ask)" ] || return 0                 # nothing asked yet: nothing to measure against
    cap 9 "⚠️" "nothing measurable — no commit, no todo" \
          "say plainly whether the work is done — bilan checked the repo, the register and CI, and this session touched none of them"
}

# The opening ask, printed with every report. bilan cannot judge whether the work satisfies it —
# that would be narration again, which is the disease it treats — but it can refuse to let a
# session claim completion without the request in front of it. A session often opens with
# pasted output, which names it worse than nothing, so framed and pasted lines are skipped.
opening_ask() {
    local tx
    tx=$(transcript_path 2>/dev/null) || return 0
    [ -f "$tx" ] || return 0
    jq -r 'select(.type == "user")
           | (if (.message.content | type) == "string" then .message.content
              else ([.message.content[]? | select(.type == "text") | .text] | join("\n")) end)
           | select(test("<command-name>|<local-command|<system-reminder>|<task-notification>|<cross-session-message")|not)
           | select(test("^\\s*$")|not)' "$tx" 2>/dev/null \
      | grep -vE '^\s*$' | grep -vE '^\s*[│┌└├─╭╰|+=#]' | grep -vE '│.*│' \
      | head -1 | tr '\t\n' '  ' | sed -e 's/  */ /g' -e 's/^ //' | cut -c1-150
}

# The latest thing the project maintainer typed, which is what the session is being measured
# against NOW. The opening ask alone named a long session by its first words, long after the
# work had moved on. Only typed prompts count: a scheduled wakeup, a task notification or a peer
# message is text the session or the harness wrote, and the transcript says so (`promptSource:
# "system"`, `isMeta`, `scheduledTaskId`).
latest_ask() {
    local tx
    tx=$(transcript_path 2>/dev/null) || return 0
    [ -f "$tx" ] || return 0
    jq -r 'select(.type == "user")
           | select((.promptSource // "") != "system" and (.isMeta // false) != true
                    and ((.scheduledTaskId // "") | tostring) == "")
           | (if (.message.content | type) == "string" then .message.content
              else ([.message.content[]? | select(.type == "text") | .text] | join("\n")) end)
           | select(test("<command-name>|<local-command|<system-reminder>|<task-notification>|<cross-session-message")|not)
           | [splits("\n") | select(test("^\\s*$")|not) | select(test("^\\s*[│┌└├─╭╰|+=#]")|not)]
           | first // empty' "$tx" 2>/dev/null \
      | tail -1 | tr '\t' ' ' | sed -e 's/  */ /g' -e 's/^ //' | cut -c1-150
}

# ------------------------------------------------------------------ checks · dev stack
# Only in a checkout that runs a local stack through bin/dev-processes.sh — a library defining
# dev_owned <name>, beside pid files at logs/<name>.pid. This repo ships none, so here the check
# returns before it looks at anything.
check_dev_stack() {
    local lib="$REPO_ROOT/bin/dev-processes.sh" f b name pid up="" inherited=""
    [ -f "$lib" ] || return 0
    # A `VAR=x . file` prefix does not survive the `.` builtin, so the assignment has to stand
    # on its own line.
    DEV_PROJECT_ROOT="$REPO_ROOT"
    export DEV_PROJECT_ROOT
    # shellcheck disable=SC1090
    . "$lib" >/dev/null 2>&1 || return 0
    b=$(baseline_file 2>/dev/null) || b=""
    for f in "$REPO_ROOT"/logs/*.pid; do
        [ -f "$f" ] || continue
        name=$(basename "$f" .pid)
        dev_owned "$name" >/dev/null 2>&1 || continue
        pid=$(head -1 "$f" 2>/dev/null)
        # Already running, as this pid, before the session opened: a peer's, and stopping it
        # would take their servers down. Stated, never scored. A baseline written before the
        # stack channel existed has no .stack file at all; absent means unknown, and unknown is
        # stated, never scored — the same rule check_measurable uses for a missing baseline.
        if [ -z "$b" ] || [ ! -f "${b}.stack" ]; then
            inherited="$inherited $name"
        elif grep -qxF "$name $pid" "${b}.stack" 2>/dev/null; then
            inherited="$inherited $name"
        else
            up="$up $name"
        fi
    done
    if [ -n "${inherited// /}" ]; then
        if [ -n "$b" ] && [ -f "${b}.stack" ]; then
            say_note "dev stack running since before this session opened — a peer's, not this session's to stop:${inherited}"
        else
            say_note "dev stack up, ownership unknown (this session's baseline predates the stack channel) — check before stopping:${inherited}"
        fi
    fi
    [ -n "${up// /}" ] || return 0
    cap 9 "⚠️" "dev stack this session started, still up:${up}" \
          "stop it with the checkout's own stop script (bin/stop-server.sh beside bin/dev-processes.sh) — a running stack holds its ports against whoever works here after"
}

# ------------------------------------------------------------------ checks · CI (network)
# CI is REPORTED, never scored. Attributing a checkout's CI to the session reading it puts the cost
# on other people either way it is tried: grading the tip outright caps every session in a shared
# checkout when one peer's commit is red, and grading it only when HEAD moved since session start
# is no better, because HEAD moves when PEERS push. A shared checkout has one HEAD and many
# sessions, all committing as the same author, so whose commit the tip is cannot be recovered from
# git. The verdict is printed — it is genuinely useful to see — and the number stays about work
# this session can act on. A session that wants CI on its own commit asks for that sha by name.
#
# Read from the check-runs API by full sha rather than `gh run list`: `--commit` with a short sha
# answers with an empty list, and a branch window loses a row the moment peers push past it, and
# either empty answer would read as green. Paginated, because a commit here carries more check
# runs than one page holds, and a failure on the second page is still a failure. A sha with no
# check run at all prints nothing: absent is not green.
#
# Asked with or without an upstream: check runs are keyed by sha, and a worktree branch here has
# none — it lands with `git push origin <branch>:main`, which leaves its HEAD at main's tip with
# main's CI on it. Gating on an upstream hid exactly that verdict. A sha GitHub has never seen
# answers an error, which prints nothing, as any unanswerable query does.
check_ci() {
    local slug runs total running bad cancelled
    command -v gh >/dev/null 2>&1 || return 0
    [ -n "$HEAD_SHA" ] || return 0
    slug=$(git remote get-url origin 2>/dev/null \
           | sed -E 's#^(git@github\.com:|https://github\.com/|ssh://git@github\.com/)##; s#\.git$##; s#/$##')
    [ -n "$slug" ] || return 0

    runs=$(gh api --paginate "repos/$slug/commits/$HEAD_SHA/check-runs?per_page=100" \
               --jq '.check_runs[] | {name, status, conclusion}' 2>/dev/null | jq -s '.' 2>/dev/null) || return 0
    total=$(printf '%s' "$runs" | jq -r 'length' 2>/dev/null)
    [ "${total:-0}" != 0 ] || return 0

    running=$(printf '%s' "$runs" | jq -r '[.[] | select(.status != "completed") | .name] | unique | join(", ")')
    bad=$(printf '%s' "$runs" | jq -r '[.[] | select(.conclusion | IN("failure","timed_out","action_required","startup_failure")) | .name] | unique | join(", ")')
    cancelled=$(printf '%s' "$runs" | jq -r '[.[] | select(.conclusion == "cancelled") | .name] | unique | join(", ")')

    [ -z "$bad" ]       || say_note "CI RED on the checkout head ${HEAD_SHA:0:8}: $bad — check whether that commit is yours"
    [ -z "$running" ]   || say_note "CI still running on the checkout head ${HEAD_SHA:0:8} ($total checks): $running"
    [ -z "$cancelled" ] || say_note "CI cancelled on the checkout head ${HEAD_SHA:0:8}: $cancelled — cancelled is unvalidated, not green"
    [ -n "$bad$running$cancelled" ] || say_note "CI green on the checkout head ${HEAD_SHA:0:8}: $total checks"
}

# ------------------------------------------------------------------ friction (informational)
# It is NOT a cap: a failure found and fixed is just work and never deducts. It is printed
# because a session with 30 tool errors claiming a clean 10 is worth a second look.
friction_line() {
    local tx counts
    # One jq pass over a transcript that can reach tens of MB, so it is skipped on the status
    # line's path: friction never caps the score, and a hook that overruns is killed at ~10s.
    [ "$CHEAP" = 0 ] || return 0
    tx=$(transcript_path 2>/dev/null) || return 0
    [ -f "$tx" ] || return 0
    counts=$(jq -rs '
        def blocks: .message.content? | if type == "array" then .[] else empty end;
        def user_text: select(.type == "user") | blocks
                       | select(.type == "text") | .text;
        def results:   select(.type == "user") | blocks
                       | select(.type == "tool_result");
        [ ([ .[] | results | select(.is_error == true) ] | length),
          ([ .[] | user_text
             | select(startswith("[Request interrupted by user")) ] | length),
          ([ .[] | results | .content
             | (if type == "array" then (.[]? | .text? // "") else (. // "") end)
             | select(type == "string")
             | select(startswith("The user doesn'"'"'t want to proceed")) ] | length)
        ] | @tsv' "$tx" 2>/dev/null)
    [ -n "$counts" ] || return 0
    printf '%s\n' "$counts"
}

# ------------------------------------------------------------------ run
run_checks() {
    ensure_baseline
    check_worktree
    check_unpushed
    check_stash
    check_branch_cleanup
    check_validation_marker
    check_carnet_held
    check_carnet_filed
    check_limitation_markers
    check_dev_stack
    check_running_tasks
    check_open_todos
    check_measurable
    # CI is a note either way, so --cheap and the full run agree on everything CI could say;
    # the only difference there is whether the line is printed.
    [ "$CHEAP" = 1 ] || check_ci
}

score() {
    local min=10 c
    while IFS=$'\t' read -r c _ _ _; do
        [ -n "$c" ] || continue
        [ "$c" -lt "$min" ] && min=$c
    done < "$CAPS"
    printf '%s' "$min"
}

score_file() { [ -n "$SESSION_ID" ] && printf '%s' "$CFG/bilan/$(printf '%s' "$SESSION_ID" | tr -c 'a-zA-Z0-9._-' '_').score"; }

publish_score() { # <score>
    local f top
    f=$(score_file) || return 0
    mkdir -p "$(dirname "$f")" 2>/dev/null || return 0
    # The status line has room for one short phrase, and it has to name the thing to act on. A
    # cap that lists full paths was cut mid-path — ".agents/skills/b" identifies nothing — so
    # paths shrink to basenames before the width limit applies, and the cut lands on a word
    # boundary.
    top=$(sort -n "$CAPS" 2>/dev/null | head -1 | cut -f3 \
          | sed -E 's#[^ ]*/([^ /]+)#\1#g' \
          | awk '{ if (length($0) <= 56) print; else { s = substr($0, 1, 56);
                   sub(/[^ ]*$/, "", s); sub(/ $/, "", s); print s "…" } }')
    printf '%s\t%s\t%s\n' "$1" "$(date +%s)" "${top:-nothing outstanding}" > "$f" 2>/dev/null || true
}

report() {
    local s f_errors f_interrupts f_denials fr icon ev rem c line
    s=$(score)
    publish_score "$s"
    fr=$(friction_line)
    [ -n "$fr" ] || fr=$(printf '0\t0\t0')
    IFS=$'\t' read -r f_errors f_interrupts f_denials <<< "$fr"

    if [ "$JSON" = 1 ]; then
        jq -n --argjson score "$s" \
              --rawfile notes "$NOTES" \
              --arg session "${SESSION_ID:-manual}" --arg branch "$BRANCH" --arg sha "$HEAD_SHA" \
              --argjson errors "${f_errors:-0}" --argjson interrupts "${f_interrupts:-0}" \
              --argjson denials "${f_denials:-0}" \
              --rawfile caps "$CAPS" \
          '{score:$score, session:$session, branch:$branch, sha:$sha,
            friction:{tool_errors:$errors, interrupts:$interrupts, denials:$denials},
            notes: ($notes | split("\n") | map(select(length>0))),
            caps: ($caps | split("\n") | map(select(length>0) | split("\t")
                   | {cap:(.[0]|tonumber), icon:.[1], evidence:.[2], remedy:.[3]}))}'
        [ "$s" = 10 ] && return 0 || return 1
    fi

    if [ "$s" = 10 ] && [ "$QUIET" = 1 ]; then return 0; fi

    say "BILAN · ${SESSION_ID:0:8} · $(basename "$REPO_ROOT") @ $BRANCH ${HEAD_SHA:0:8}"
    local ask latest; ask=$(opening_ask); latest=$(latest_ask)
    [ -n "$latest" ] || latest=$ask
    [ -z "$latest" ] || say "asked: $latest"
    [ -z "$ask" ] || [ "$ask" = "$latest" ] || say "opened: $ask"
    say ""
    if [ ! -s "$CAPS" ]; then
        say "  ✅ nothing outstanding — tree clean, nothing unpushed, no issue held"
    else
        sort -n "$CAPS" | while IFS=$'\t' read -r c icon ev rem; do
            say "  $icon $ev"
            say "     → $rem  (caps at $c)"
        done
    fi
    if [ -s "$NOTES" ]; then
        say ""
        while IFS= read -r line; do [ -n "$line" ] && say "  ℹ️  $line"; done < "$NOTES"
    fi
    say ""
    [ "${f_errors:-0}" = 0 ] && [ "${f_interrupts:-0}" = 0 ] && [ "${f_denials:-0}" = 0 ] || \
        say "  friction: ${f_errors} tool error(s) · ${f_interrupts} interrupt(s) · ${f_denials} denial(s)   (informational — a fixed failure never deducts)"
    say ""
    say "COMPLETION: $s/10"
    [ "$s" = 10 ] && return 0 || return 1
}

# ------------------------------------------------------------------ scope (inspection)
# Which files the register scans, as bilan resolves it — the question to ask when a marker is not
# credited, or when the register's directories change.
cmd_scope() {
    local dirs reason
    dirs=$(scan_dirs | tr '\n' ' ')
    dirs=${dirs% }
    if register_scope; then
        if [ "$JSON" = 1 ]; then
            jq -n --arg d "$dirs" --arg via "$SCOPE_VIA" --rawfile f "$SCOPE" \
                '{dirs: ($d | split(" ") | map(select(length > 0))), via: $via,
                  files: ($f | split("\n") | map(select(length > 0)))}'
        else
            cat "$SCOPE"
            printf 'scope: %s file(s) under %s (via %s)\n' "$(grep -c . "$SCOPE")" "$dirs" "$SCOPE_VIA" >&2
        fi
        return 0
    fi
    reason=$(scope_unknown_reason)
    if [ "$JSON" = 1 ]; then
        jq -n --arg d "$dirs" --arg r "$reason" --arg fix "$(scope_remedy)" \
            '{dirs: ($d | split(" ") | map(select(length > 0))), via: null, unknown: $r, remedy: $fix}'
    else
        printf 'scope unknown (%s) — %s\n' "$reason" "$(scope_remedy)" >&2
    fi
    return 1
}

# ------------------------------------------------------------------ sweep
# The only thing that catches a session that died: a kill -9 fires no exit hook, so the dead
# session can never report on itself. Reads every ledger on this machine and every worktree of
# this repo, and names what a session that is no longer running left behind.
#
# A ledger belongs to its session, so the sweep rewrites or deletes one only when that session has
# certainly ended — a running one may be appending to it that moment. Liveness is decided by
# session id, never by the pid the ledger recorded: carnet writes that identity line once, at the
# session's first carnet write, and `claude --resume` keeps the session id under a new pid. Claude
# Code keeps sessions/<pid>.json under its config dir for every running session and removes it on
# exit, so a file naming the id with a live pid is a running session, however often it resumed.
# The config dir is $CFG as well as every $HOME/.claude*, as carnet's holder_alive has it; looking
# only in the second called every live session dead when CLAUDE_CONFIG_DIR was elsewhere.
live_session_ids() { # -> the id of every running session, one per line
    local d f sid p seen=""
    for d in "$CFG/sessions" "$HOME"/.claude*/sessions; do
        [ -d "$d" ] || continue
        d=$(cd "$d" 2>/dev/null && pwd -P) || continue
        case " $seen " in *" $d "*) continue ;; esac
        seen="$seen $d"
        for f in "$d"/*.json; do
            [ -f "$f" ] || continue
            sid=$(jq -r '.sessionId // empty' "$f" 2>/dev/null)
            [ -n "$sid" ] || continue
            p=$(jq -r '.pid // empty' "$f" 2>/dev/null)
            case $p in '' | *[!0-9]*) p=$(basename "$f" .json) ;; esac
            case $p in '' | *[!0-9]*) continue ;; esac
            kill -0 "$p" 2>/dev/null && printf '%s\n' "$sid"
        done
    done
    return 0
}

# 0 = running · 1 = ended · 2 = cannot be told from here, with the reason on stdout. Only 1 lets
# the sweep touch the ledger; 2 is reported and left alone, because a session that might still be
# running is its ledger's owner.
session_state() { # <session-id> <ledger> <running ids> <this host>
    local pid host
    printf '%s\n' "$3" | grep -qxF "$1" && return 0
    pid=$(head -1 "$2" | jq -r '.pid // empty' 2>/dev/null)
    host=$(head -1 "$2" | jq -r '.host // empty' 2>/dev/null)
    # carnet's rule too: a pid recorded on another host means nothing on this one.
    if [ -n "$host" ] && [ "$host" != "$4" ]; then
        printf 'it ran on %s, where this machine cannot look' "$host"
        return 2
    fi
    # 0 is no pid either: `kill -0 0` asks about this process group, and always answers yes.
    case $pid in
        '' | 0 | *[!0-9]*) printf 'its ledger names no pid'; return 2 ;;
    esac
    # The recorded process is still there, yet no session file names this session: a recycled
    # pid, or a session file out of this sweep's reach. Neither proves the session ended.
    if kill -0 "$pid" 2>/dev/null; then
        printf 'its pid %s still runs, though no session file names it' "$pid"
        return 2
    fi
    return 1
}

# A worktree with an agent actively working in it is not something a dead session left behind.
# A session's own cwd is NOT the signal: a session commonly sits in the main checkout and reaches
# a worktree by path, so a busy worktree can have no session pointing at it. Two things do show it:
#
#   * a live process whose cwd is inside it — a server started from that checkout,
#   * a file modified there recently — an agent editing by path leaves no process at all.
#
# Either one means someone is there. Abandoned work is quiet AND untouched. Build output,
# dependencies and git internals are pruned rather than filtered: a Maven worktree's target/ trees
# are large, and walking them made one quiet worktree cost more than the rest of the sweep.
worktree_is_live() { # <path>
    local recent
    if command -v lsof >/dev/null 2>&1 && lsof -a -d cwd -- "$1" >/dev/null 2>&1; then
        return 0
    fi
    # -mmin -120: two hours is long enough that a pause for thought does not read as abandonment,
    # and short enough that yesterday's leftovers still surface.
    recent=$(find "$1" \( -name .git -o -name target -o -name node_modules \) -prune \
             -o -type f -mmin -120 -print -quit 2>/dev/null)
    [ -n "$recent" ]
}

cmd_sweep() {
    local dir f id name at issues found=0 busy=0 path="" branch="" dirty ahead what line row n t tmp healed="" seen=""
    local live host state why
    say "BILAN SWEEP · $(basename "$REPO_ROOT")"
    say ""
    # Once per sweep, not per ledger: which sessions are running, and the host they would run on
    # — spelled the way carnet.sh spells it into the identity line.
    live=$(live_session_ids)
    host=$(hostname -s 2>/dev/null || hostname)
    # Both accounts' config dirs, plus this session's own if CLAUDE_CONFIG_DIR points somewhere
    # the glob does not reach — a session configured outside $HOME was invisible to its own
    # sweep. Deduped, since the common case is that $CFG is already one of the globbed dirs.
    for dir in "$HOME"/.claude*/carnet-claims "$LEDGER_DIR"; do
        [ -d "$dir" ] || continue
        case " $seen " in *" $dir "*) continue ;; esac
        seen="$seen $dir"
        for f in "$dir"/*.jsonl; do
            [ -s "$f" ] || continue
            id=$(basename "$f" .jsonl)
            [ "$id" = "${SESSION_ID:-}" ] && continue
            why=$(session_state "$id" "$f" "$live" "$host"); state=$?
            [ "$state" = 0 ] && continue
            name=$(head -1 "$f" | jq -r '.name // "?"')
            at=$(head -1 "$f"   | jq -r '.at // "?"')
            # A dead session's ledger outlives the issue: once somebody closes it, every session
            # start would report an abandoned issue that no longer exists — a recurring false
            # alarm that trains the reader to skip the line the sweep exists to print. So the
            # tracker is asked, and what it has resolved is dropped: SessionEnd would have cleaned
            # this ledger up, and it is only here because the session was killed before it could.
            #
            # Only this repo's register. The ledger directory is shared by every repo on the
            # machine, and asking THIS tracker about another register's claim would answer for a
            # different issue that shares the number, and delete a live claim from a dead
            # session's ledger on the strength of it. That claim is left alone here — not because
            # its own repo's sweep is sure to report it: not every sweep sharing the directory
            # keeps to its own register ("The three call sites" in SKILL.md names two that do not).
            issues=""
            for row in $(ledger_rows "$f" claim); do
                n=${row%%|*}
                t=${row#*|}
                [ -z "$TRACKER" ] || [ "$t" = "$TRACKER" ] || continue
                if [ "$(tracker_state "$n" "$t")" = closed ]; then
                    # A session that may still be running owns its ledger: a closed claim is left
                    # in it, and only left out of the report.
                    [ "$state" = 1 ] || continue
                    tmp=$(mktemp "${TMPDIR:-/tmp}/bilan-ledger.XXXXXX") || continue
                    jq -c --argjson n "$n" --arg t "$t" --arg d "$TRACKER" \
                        'select((.kind == "claim" and .issue == $n and ((.tracker // $d) == $t)) | not)' "$f" > "$tmp" \
                        && mv "$tmp" "$f"
                    rm -f "$tmp"
                    healed="$healed $(issue_label "$n" "$t")"
                    continue
                fi
                issues="$issues $(issue_label "$n" "$t")"
            done
            # A ledger with nothing left to hold is finished, exactly as carnet's ledger_drop treats
            # it — and only an ended session's ledger is anyone else's to remove.
            [ "$state" = 1 ] && [ "$(jq -c 'select(.kind == "claim" or .kind == "filed")' "$f" 2>/dev/null | grep -c .)" = 0 ] \
                && rm -f "$f"
            [ -n "${issues// /}" ] || continue
            found=1
            if [ "$state" = 1 ]; then
                say "  ☠️  session $name (${id:0:8}, last claim $at) ended holding:${issues}"
                say "     → carnet.sh status <n> to see it; a plain claim takes over a stale one"
            else
                say "  ❓ session $name (${id:0:8}, last claim $at) may still be running — $why — holding:${issues}"
                say "     → carnet.sh status <n> says whether it is; nothing was cleared from its ledger"
            fi
        done
    done

    # A branch with no upstream is measured against origin/main, the way check_unpushed does:
    # here a worktree branch is landed with a refspec push and is never published, so "unpushed"
    # against an upstream it does not have would report nothing a dead agent left behind.
    while IFS= read -r line; do
        case "$line" in
            worktree\ *) path=${line#worktree } ;;
            branch\ *)
                branch=${line#branch refs/heads/}
                dirty=$(git -C "$path" status --porcelain 2>/dev/null | grep -cv '^??' || true)
                if ahead=$(git -C "$path" rev-list --count '@{u}..HEAD' 2>/dev/null); then
                    what="unpushed"
                elif [ "$branch" != main ] \
                     && ahead=$(git -C "$path" rev-list --count origin/main..HEAD 2>/dev/null); then
                    what="not on origin/main"
                else
                    ahead=0; what="unpushed"
                fi
                if [ "${dirty:-0}" -gt 0 ] || [ "${ahead:-0}" -gt 0 ]; then
                    if worktree_is_live "$path"; then
                        busy=$((busy + 1))
                    else
                        found=1
                        say "  📂 $branch ($path): ${dirty:-0} uncommitted, ${ahead:-0} $what"
                    fi
                fi ;;
        esac
    done <<< "$(git worktree list --porcelain 2>/dev/null)"

    [ -z "${healed// /}" ] || say "  🧹 cleared from a dead session's ledger, already closed on the tracker:${healed}"
    [ "$busy" = 0 ] || say "  👷 $busy worktree(s) in active use (a live process or edits in the last 2h) — not reported"
    [ "$found" = 1 ] || [ -n "${healed// /}" ] || say "  ✅ nothing left behind by a dead session"
    return 0
}

# ------------------------------------------------------------------ argv
CMD=report
WHY=""
IF_MISSING=""
while [ $# -gt 0 ]; do
    case "$1" in
        sweep)        CMD=sweep ;;
        ack)          CMD=ack ;;
        baseline)     CMD=baseline ;;
        scope)        CMD=scope ;;
        --if-missing) IF_MISSING=--if-missing ;;
        --why)        shift; WHY=${1:-} ;;
        --cheap)      CHEAP=1 ;;
        --json)       JSON=1 ;;
        --quiet)      QUIET=1 ;;
        --session)    shift; SESSION_ID=${1:-} ;;
        -h|--help)    usage; exit 0 ;;
        *)            die "unknown argument: $1" ;;
    esac
    shift
done

case "$CMD" in
    sweep)    cmd_sweep ;;
    ack)      cmd_ack "$WHY" ;;
    baseline) cmd_baseline $IF_MISSING ;;
    scope)    cmd_scope ;;
    report)   run_checks; report ;;
esac

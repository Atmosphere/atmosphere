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
# vault-sync.sh — sync ../atmosphere-vault remote-first: origin/main wins, local
# uncommitted work is stashed around it.
#
#   `pull` before reading or writing the vault,
#   `push -m <msg> <path>…` to commit and publish your notes.
#
# The vault's REMOTE is the source of truth. Auto-commit timers (obsidian-git, or
# the launchd vault-sync agent) also commit into the vault, so local main can be
# ahead of, behind, or diverged from origin at any moment. Every sync therefore:
#
#   1. fetches origin/main,
#   2. stashes any uncommitted work (tracked + untracked) — nothing is discarded,
#   3. rebases local commits onto origin/main with `-X ours`, which in a rebase
#      means the UPSTREAM side wins every conflicting hunk; if the rebase still
#      stops (delete/modify, binary), local main is saved to
#      `vault-sync/backup-<ts>` and reset to origin/main,
#   4. re-applies the stash; a conflicting stash is left in `git stash list` and
#      the tracked files are returned to the remote state, never half-merged
#      (untracked files the pop had already restored stay in place).
#
# Usage:
#   vault-sync.sh pull
#   vault-sync.sh push -m "<kind>: <what>" <path> [<path>…]   # paths relative to the vault
#   VAULT_DIR=/elsewhere vault-sync.sh pull
# ---------------------------------------------------------------------------

set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The vault sits next to the primary atmosphere checkout as ../atmosphere-vault. This
# skill is also checked out inside git worktrees (.claude/worktrees/<name>/), where
# "three levels up" is the worktree, not that checkout. `git rev-parse --git-common-dir`
# names the primary checkout's .git from either place (absolute or relative to
# SKILL_DIR, both of which the `cd` below accepts).
if common="$(cd "$SKILL_DIR" && git rev-parse --git-common-dir 2>/dev/null)"; then
  CODE_ROOT="$(cd "$SKILL_DIR" && cd "$common/.." && pwd)"
else
  CODE_ROOT="$(cd "$SKILL_DIR/../../.." && pwd)"
fi
VAULT_DIR="${VAULT_DIR:-$CODE_ROOT/../atmosphere-vault}"
BRANCH=main

die() { echo "vault-sync: $*" >&2; exit 1; }
note() { echo "vault-sync: $*"; }
g() { git -C "$VAULT_DIR" "$@"; }

[ -e "$VAULT_DIR/.git" ] || die "no vault checkout at $VAULT_DIR"
VAULT_DIR="$(cd "$VAULT_DIR" && pwd)"

sync_remote_first() {
  local current stashed=0 ts gitdir marker
  BACKUP=""
  # Absolute on purpose: `git rev-parse --git-path` prints a path relative to the vault,
  # which the test below would resolve against THIS shell's cwd and never find.
  gitdir="$(g rev-parse --absolute-git-dir)"
  for marker in rebase-merge rebase-apply MERGE_HEAD; do
    [ ! -e "$gitdir/$marker" ] || die "a $marker is in progress in the vault — resolve it first"
  done
  current="$(g symbolic-ref --short HEAD 2>/dev/null || true)"
  [ "$current" = "$BRANCH" ] || die "vault is on '${current:-detached HEAD}', expected $BRANCH — refusing to move it"

  g fetch origin "$BRANCH" --quiet || die "fetch failed"
  ts="$(date +%Y%m%d-%H%M%S)"

  if [ -n "$(g status --porcelain)" ]; then
    g stash push --include-untracked --quiet -m "vault-sync $ts local work" || die "stash failed"
    stashed=1
    note "stashed local uncommitted work (vault-sync $ts)"
  fi

  if ! g rebase --quiet -X ours "origin/$BRANCH" >/dev/null 2>&1; then
    g rebase --abort >/dev/null 2>&1 || true
    BACKUP="vault-sync/backup-$ts"
    g branch "$BACKUP" HEAD
    g reset --quiet --hard "origin/$BRANCH"
    note "rebase could not auto-resolve; local main saved as $BACKUP and reset to origin/$BRANCH"
  fi

  if [ "$stashed" = 1 ]; then
    if g stash pop --quiet >/dev/null 2>&1; then
      note "re-applied local work on top of origin/$BRANCH"
    else
      g reset --quiet --merge
      note "local work conflicts with origin/$BRANCH — tracked files kept at remote, the full work kept in 'git -C $VAULT_DIR stash list' (vault-sync $ts)"
    fi
  fi

  note "at $(g rev-parse --short HEAD) ($(g rev-list --count "origin/$BRANCH..HEAD") ahead of origin/$BRANCH)"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  pull)
    sync_remote_first
    ;;
  push)
    msg=""
    paths=()
    while [ $# -gt 0 ]; do
      case "$1" in
        -m) [ $# -ge 2 ] || die "-m needs a message"; msg="$2"; shift 2 ;;
        *) paths+=("$1"); shift ;;
      esac
    done
    [ -n "$msg" ] || die "push needs -m \"<message>\""
    [ "${#paths[@]}" -gt 0 ] || die "push needs at least one path — never the whole tree"

    g add -- "${paths[@]}"
    if g diff --cached --quiet -- "${paths[@]}"; then
      note "nothing to commit in the given paths"
    else
      g commit --quiet -m "$msg" -- "${paths[@]}"
    fi
    # What this push has to publish: each path's object as committed ("-" for a deletion).
    # A sync can drop the commit two ways — it was already upstream (published: success) or
    # the remote won every conflicting hunk (not published: failure) — and only comparing
    # these against origin afterwards tells them apart.
    wanted=()
    for p in "${paths[@]}"; do
      wanted+=("$(g rev-parse --verify --quiet "HEAD:$p" || echo -)")
    done
    for attempt in 1 2 3; do
      sync_remote_first
      # The exit status is what a calling session reads as "published", so every path that
      # leaves the notes unpublished exits non-zero.
      [ -z "$BACKUP" ] || die "not published — the rebase could not auto-resolve, so your commit is on $BACKUP; merge it by hand"
      if [ "$(g rev-list --count "origin/$BRANCH..HEAD")" = 0 ]; then
        lost=()
        for i in "${!paths[@]}"; do
          [ "$(g rev-parse --verify --quiet "origin/$BRANCH:${paths[$i]}" || echo -)" = "${wanted[$i]}" ] \
            || lost+=("${paths[$i]}")
        done
        [ "${#lost[@]}" = 0 ] \
          || die "not published — origin/$BRANCH kept its own version of: ${lost[*]} (your commit is in 'git -C $VAULT_DIR reflog')"
        note "nothing to push — origin/$BRANCH already has these changes"
        exit 0
      fi
      if g push --quiet origin "HEAD:$BRANCH"; then
        note "pushed $(g rev-parse --short HEAD) to origin/$BRANCH"
        exit 0
      fi
      note "push rejected (attempt $attempt) — re-syncing against the new remote tip"
    done
    die "push still rejected after 3 attempts"
    ;;
  *)
    die "usage: vault-sync.sh pull | push -m \"<message>\" <path>…"
    ;;
esac

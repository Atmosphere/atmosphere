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
# The vault's REMOTE is the source of truth. obsidian-git's timer also commits into
# the vault, so local main can be ahead of, behind, or diverged from origin at any
# moment. Every sync therefore:
#
#   1. refuses to run while a merge or rebase is in progress or the vault is not on
#      main (`push` checks this before it stages or commits anything),
#   2. fetches origin/main,
#   3. stashes any uncommitted work (tracked + untracked) — nothing is discarded,
#   4. rebases local commits onto origin/main with `-X ours`, which in a rebase
#      means the UPSTREAM side wins every conflicting hunk. Two failures make both
#      `pull` and `push` exit non-zero:
#      - the rebase stops on a conflict `-X ours` cannot resolve (modify/delete,
#        file/directory): local main is saved to `vault-sync/backup-<ts>` and reset
#        to origin/main — merge that branch by hand;
#      - the rebase fails without a conflict (a file changed after the stash, another
#        git process held the index lock): nothing is reset, and HEAD goes back to
#        main as it was — retry,
#   5. re-applies the stash; a stash that does not re-apply stays in `git stash list`,
#      conflicted files are left as committed, and the message names every tracked
#      file that still carries edits (a pop that failed only on an untracked file
#      origin now tracks leaves the tracked edits applied, and still in the stash;
#      untracked files it had restored stay in place).
#
# `push` exits 0 only when what it names is PUBLISHED. Its commit is the one it made, or
# HEAD when there was nothing to commit. Its notes are the files under the given paths
# that the commit holds differently from the commit before it or from the origin/main
# commit it forked from, as the push's first fetch returned origin/main — so a change an
# earlier unpushed commit made (the timer's) counts. The vault holds every other file
# under the paths as origin/main already had it: a directory's other notes, or a note
# the timer already pushed. Git expands the paths, so `x.md`, `./x.md`, an absolute path
# inside the vault, a glob and a directory name the same files. PUBLISHED means the sync
# went through and, after the final sync and push, origin/main holds every file under
# the paths exactly as the commit holds it (absent, for a deletion) — or, for a file
# that is not a note, has changed it since only in commits made on top of that version:
# published, then edited, which the output lists ("already on origin/main … then changed
# there by later edits") without failing. Anything else exits 1 and names each file
# origin/main does not hold as committed: a note the remote also edited — even one git
# merged cleanly, whose merged version the vault then holds: re-read it — and a file
# already published that an edit made without that version (a concurrent one) was
# merged over. A failure that stops the sync itself after the commit (fetch, stash)
# exits 1 naming every note as not verified.
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

# Once `push` has committed, every failure names the files it could not verify as published.
UNVERIFIED=""
die() {
  echo "vault-sync: $*" >&2
  [ -z "$UNVERIFIED" ] || echo "vault-sync: not verified as published: $UNVERIFIED" >&2
  exit 1
}
note() { echo "vault-sync: $*"; }
g() { git -C "$VAULT_DIR" "$@"; }
# The object at <rev>:<repo-relative path>, or "-" when the path is absent there.
object_at() { g rev-parse --verify --quiet "$1:$2" 2>/dev/null || echo -; }

[ -e "$VAULT_DIR/.git" ] || die "no vault checkout at $VAULT_DIR"
VAULT_DIR="$(cd "$VAULT_DIR" && pwd)"
# Absolute on purpose: `git rev-parse --git-path` prints a path relative to the vault,
# which the tests below would resolve against THIS shell's cwd and never find.
GITDIR="$(g rev-parse --absolute-git-dir)"

# The guards run before the vault is changed in any way: `push` calls this before it
# stages or commits, and every sync calls it again before it fetches.
require_syncable() {
  local current marker
  for marker in rebase-merge rebase-apply MERGE_HEAD; do
    [ ! -e "$GITDIR/$marker" ] || die "a $marker is in progress in the vault — resolve it first"
  done
  current="$(g symbolic-ref --short HEAD 2>/dev/null || true)"
  [ "$current" = "$BRANCH" ] || die "vault is on '${current:-detached HEAD}', expected $BRANCH — refusing to move it"
}

# Sets BACKUP when a conflict moved local main to a backup branch, and REFUSED when the
# rebase failed without one (nothing reset); `pull` and `push` both exit non-zero on either.
sync_remote_first() {
  local stashed=0 ts f edited rebase_err
  BACKUP=""
  REFUSED=0
  require_syncable

  g fetch origin "$BRANCH" --quiet || die "fetch failed"
  ts="$(date +%Y%m%d-%H%M%S)"

  if [ -n "$(g status --porcelain)" ]; then
    g stash push --include-untracked --quiet -m "vault-sync $ts local work" || die "stash failed"
    stashed=1
    note "stashed local uncommitted work (vault-sync $ts)"
  fi

  if ! rebase_err="$(g rebase --quiet -X ours "origin/$BRANCH" 2>&1 >/dev/null)"; then
    if [ -n "$(g ls-files --unmerged)" ]; then
      # Stopped on a conflict `-X ours` cannot resolve: park local main on a backup
      # branch and take the remote's state.
      g rebase --abort >/dev/null 2>&1 || true
      BACKUP="vault-sync/backup-$ts"
      g branch "$BACKUP" HEAD
      g reset --quiet --hard "origin/$BRANCH"
      note "rebase could not auto-resolve; local main saved as $BACKUP and reset to origin/$BRANCH"
    else
      # Failed without a conflict: the rebase refused to run (a tracked file changed after
      # the stash, another git process held the index lock). Reset nothing — `rebase
      # --abort` is itself a hard reset, which would destroy that change — so quit the
      # rebase and let checkout, which refuses to overwrite local edits, put HEAD back on
      # main as it was.
      if [ -e "$GITDIR/rebase-merge" ] || [ -e "$GITDIR/rebase-apply" ]; then
        g rebase --quit
      fi
      if ! g symbolic-ref -q HEAD >/dev/null; then
        g checkout --quiet "$BRANCH" \
          || die "the rebase failed without a conflict and HEAD could not return to $BRANCH (git says why above) — the vault is left on a detached HEAD, main itself untouched: resolve that, then 'git -C $VAULT_DIR checkout $BRANCH'$([ "$stashed" = 0 ] || echo "; your earlier uncommitted work is in 'git -C $VAULT_DIR stash list' (vault-sync $ts)")"
      fi
      REFUSED=1
      note "the rebase onto origin/$BRANCH did not run — git: $(printf '%s\n' "$rebase_err" | grep -m1 . || echo "no error text"); main was not touched"
    fi
  fi

  if [ "$stashed" = 1 ]; then
    if g stash pop --quiet >/dev/null 2>&1; then
      note "re-applied local uncommitted work"
    else
      g reset --quiet --merge
      # git >= 2.35 merges the tracked part of a stash before it restores the untracked
      # files, so a pop that failed only on an untracked file origin now tracks leaves the
      # tracked edits applied — and `reset --merge` keeps unstaged edits. Name what is
      # really in the working tree instead of claiming the tracked files are as committed.
      edited=""
      while IFS= read -r -d '' f; do edited="$edited${edited:+, }$f"; done < <(g diff --name-only -z)
      if [ -z "$edited" ]; then
        note "local work did not re-apply cleanly — tracked files left as committed, the full work kept in 'git -C $VAULT_DIR stash list' (vault-sync $ts)"
      else
        note "local work did not fully re-apply — the working tree has uncommitted edits to: $edited; the full work is still in 'git -C $VAULT_DIR stash list' (vault-sync $ts), so check those files before popping it"
      fi
    fi
  fi

  note "at $(g rev-parse --short HEAD) ($(g rev-list --count "origin/$BRANCH..HEAD") ahead of origin/$BRANCH)"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  pull)
    sync_remote_first
    # A caller reads 0 as "synced": local commits moved off main, or a sync that did not
    # run, must stop it just as they stop `push`.
    [ -z "$BACKUP" ] \
      || die "local commits left main — the rebase could not auto-resolve, so they are saved as $BACKUP and main now matches origin/$BRANCH; merge that branch by hand"
    [ "$REFUSED" = 0 ] || die "not synced — the rebase onto origin/$BRANCH did not run and main was not touched; retry"
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

    # Nothing is staged or committed until the vault is known to be syncable.
    require_syncable
    before="$(g rev-parse HEAD)"
    g add -- "${paths[@]}"
    if g diff --cached --quiet -- "${paths[@]}"; then
      note "nothing to commit in the given paths"
    else
      g commit --quiet -m "$msg" -- "${paths[@]}"
    fi
    committed="$(g rev-parse HEAD)"
    short="$(g rev-parse --short HEAD)"
    empty_tree="$(g hash-object -t tree /dev/null)"
    named="$(mktemp "${TMPDIR:-/tmp}/vault-sync.XXXXXX")"
    trap 'rm -f "$named"' EXIT

    # What this push publishes, its NOTES: every file under the given paths that the commit
    # holds differently from the commit before it (this push's own change) or from FORK, the
    # origin/main commit it forked from (a change an earlier unpushed commit made, such as
    # the timer's). Git expands the paths, so "x.md", "./x.md", an absolute path inside the
    # vault, a glob and a directory come out as the same repo-relative names. A file the
    # vault still holds as origin/main had it (a directory's other notes, a note the timer
    # already pushed) is not a note of this push: the verdict judges it by what origin/main
    # did to it after FORK.
    notes_of_push() { # <origin/main as last fetched>
      local f
      FORK="$(g merge-base "$committed" "$1" 2>/dev/null)" || FORK="$empty_tree"
      {
        g diff --name-only -z --no-renames "$before" "$committed" -- "${paths[@]}"
        g diff --name-only -z --no-renames "$FORK" "$committed" -- "${paths[@]}"
      } > "$named"
      LC_ALL=C sort -zu -o "$named" "$named"
      files=()
      UNVERIFIED=""
      while IFS= read -r -d '' f; do
        files+=("$f")
        UNVERIFIED="$UNVERIFIED${UNVERIFIED:+, }$f"
      done < "$named"
    }
    is_note() { local n; for n in ${files[@]+"${files[@]}"}; do [ "$n" != "$1" ] || return 0; done; return 1; }
    # 0 when every commit that changed <file> on origin/main since <commit> descends from
    # <commit>, or left the file exactly as <commit> has it: origin/main edited the version
    # <commit> gave the file, and never merged in an edit made without it. Git's history
    # simplification follows a merge to the side whose version of the file it kept, so an
    # edit a merge discarded does not count; when both sides held the same version it may
    # follow the other side, whose identical edit replaced nothing.
    edited_on_top_of() { # <commit> <file>
      local c changed published
      changed="$(g --literal-pathspecs rev-list "$1..origin/$BRANCH" -- "$2")" || return 1
      published="$(object_at "$1" "$2")"
      for c in $changed; do
        g merge-base --is-ancestor "$1" "$c" || [ "$(object_at "$c" "$2")" = "$published" ] || return 1
      done
    }
    # Against origin/main as the vault last fetched it, until the first sync fetches it anew.
    notes_of_push "origin/$BRANCH"
    if [ "${#files[@]}" = 0 ]; then
      g diff --name-only -z --no-renames "$empty_tree" "$committed" -- "${paths[@]}" > "$named"
      [ -s "$named" ] || die "the given paths name no file in the vault — nothing to publish"
    fi

    outcome=rejected
    for attempt in 1 2 3; do
      sync_remote_first
      # The notes again, against origin/main as this first fetch returned it.
      [ "$attempt" != 1 ] || notes_of_push "origin/$BRANCH"
      if [ -n "$BACKUP" ]; then outcome=backup; break; fi
      if [ "$REFUSED" = 1 ]; then outcome=refused; break; fi
      if [ "$(g rev-list --count "origin/$BRANCH..HEAD")" = 0 ]; then outcome=current; break; fi
      if g push --quiet origin "HEAD:$BRANCH"; then
        note "pushed $(g rev-parse --short HEAD) to origin/$BRANCH"
        outcome=pushed
        break
      fi
      note "push rejected (attempt $attempt) — re-syncing against the new remote tip"
    done

    # The verdict, taken after the final sync and push, over every file under the given paths
    # whose object on origin/main differs from the commit's. The exit status is what a calling
    # session reads as "published", so it is 0 only when the sync itself went through and each
    # such file is not a note of this push and was changed on origin/main only on top of the
    # version the vault holds, which origin/main already had at FORK: published, then edited
    # (SINCE — named, never a failure). A note that differs is lost, whatever else the rebase
    # kept or pushed; so is a file an edit made without the vault's version was merged over
    # (REPLACED), even one the timer had pushed before this push ran.
    lost=()
    replaced=()
    since=()
    g diff --name-only -z --no-renames "$committed" "origin/$BRANCH" -- "${paths[@]}" > "$named"
    while IFS= read -r -d '' f; do
      [ "$(object_at "$committed" "$f")" != "$(object_at "origin/$BRANCH" "$f")" ] || continue
      if is_note "$f"; then
        lost+=("$f")
      elif [ "$FORK" = "$empty_tree" ]; then
        :   # no common history, so every file the commit holds is a note: the vault never had this one
      elif ! set_by="$(g --literal-pathspecs rev-list -1 "$FORK" -- "$f")"; then
        lost+=("$f")
      elif [ -z "$set_by" ]; then
        :   # never in the vault's history: origin/main added it, and the vault had nothing there to lose
      elif edited_on_top_of "$set_by" "$f"; then
        since+=("$f")
      else
        lost+=("$f")
        replaced+=("$f")
      fi
    done < "$named"
    case "$outcome" in
      backup) failure="the rebase could not auto-resolve, so local main is saved as $BACKUP and was reset to origin/$BRANCH — merge that branch by hand" ;;
      refused) failure="the rebase onto origin/$BRANCH did not run and main was not touched — retry" ;;
      rejected) failure="the push was still rejected after 3 attempts — retry" ;;
      *) failure="" ;;
    esac
    report_since() {
      [ "${#since[@]}" -gt 0 ] || return 0
      echo "vault-sync: already on origin/$BRANCH as $short has them, then changed there by later edits made on top of that version — not a failure, but re-read them:"
      for f in "${since[@]}"; do
        if [ "$(object_at "origin/$BRANCH" "$f")" = - ]; then echo "vault-sync:   $f (deleted)"; else echo "vault-sync:   $f"; fi
      done
    }
    if [ "${#lost[@]}" = 0 ] && [ -z "$failure" ]; then
      if [ "$outcome" != pushed ]; then
        if [ "${#since[@]}" = 0 ]; then
          note "nothing to push — origin/$BRANCH already has these changes"
        else
          note "nothing to push — the vault holds nothing under the given paths that origin/$BRANCH did not already have"
        fi
      fi
      report_since
      exit 0
    fi
    {
      [ -z "$failure" ] || echo "vault-sync: $failure"
      if [ "${#lost[@]}" -gt 0 ]; then
        echo "vault-sync: not published — origin/$BRANCH does not hold these files as this push committed them (at $short):"
        for f in "${lost[@]}"; do echo "vault-sync:   $f"; done
        [ -n "$failure" ] \
          || echo "vault-sync: origin/$BRANCH changed them too — its side won a conflicting hunk or was merged in, and the vault now holds that version: re-read them ('git -C $VAULT_DIR show $short:<file>' has yours)"
        if [ "${#replaced[@]}" -gt 0 ]; then
          list=""
          for f in "${replaced[@]}"; do list="$list${list:+, }$f"; done
          echo "vault-sync: $list: already on origin/$BRANCH as $short has it, then an edit made without that version was merged over it"
        fi
      elif [ "${#files[@]}" = 0 ]; then
        echo "vault-sync: the given paths changed nothing origin/$BRANCH did not already have"
      else
        echo "vault-sync: the notes of this push are on origin/$BRANCH as committed"
      fi
      report_since
    } >&2
    exit 1
    ;;
  *)
    die "usage: vault-sync.sh pull | push -m \"<message>\" <path>…"
    ;;
esac

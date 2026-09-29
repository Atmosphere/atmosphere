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
# test.sh — drives `vault-sync.sh` pull and push against real git repositories
# (a bare origin, the vault clone, a peer clone) in a temp dir.
#
# The contract under test is the exit status, because it is what a calling
# session reads: for `push`, 0 only when origin/main ends up holding each of its
# notes (every file under the given paths that the vault changed from the
# origin/main it forked from) exactly as committed — whatever else the rebase
# kept, and whatever shape the path was given in — and any other file under the
# paths either as committed or changed since only by edits made on top of the
# vault's version (named, never a failure), while an edit made without that
# version merged over it fails the push; any failure after the commit names the
# notes. For both commands, non-zero whenever local commits left main or the
# rebase did not run. The guards must fire before the vault is touched, and
# nothing that fails without a conflict may reset anything.
#
#   bash .claude/skills/obsidian-writer/test.sh [<bash-binary>]
# ---------------------------------------------------------------------------

set -uo pipefail

BASH_BIN="${1:-bash}"
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/vault-sync.sh"

# Isolated from the developer's git config: no signing prompt, a fixed identity.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT

pass=0
fail=0
T=""
out=""
rc=0

check() { # <label> <expected> <actual> <expected-output-fragment, or "" for none> <output>
  if [ "$2" = "$3" ] && { [ -z "$4" ] || printf '%s' "$5" | grep -qF -- "$4"; }; then
    pass=$((pass + 1)); echo "  ok   $1"
  else
    fail=$((fail + 1)); echo "  FAIL $1 (got '$3', wanted '$2')"
    printf '%s\n' "$5" | sed 's/^/       /'
  fi
}

setup() {
  T="$(mktemp -d "$ROOT/case.XXXXXX")"
  git init -q --bare -b main "$T/origin.git"
  git clone -q "$T/origin.git" "$T/vault" 2>/dev/null
  git clone -q "$T/origin.git" "$T/peer" 2>/dev/null
  printf 'line one\nline two\n' > "$T/vault/note.md"
  printf 'keep\n' > "$T/vault/gone.md"
  git -C "$T/vault" add -A
  git -C "$T/vault" commit -qm base
  git -C "$T/vault" push -q origin main
  git -C "$T/peer" pull -q origin main
}

# A ten-line note.md on both sides, so an edit to line 2 and one to line 10 are separate hunks.
ten_lines() {
  printf 'a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n' > "$T/vault/note.md"
  git -C "$T/vault" commit -qam "ten lines"
  git -C "$T/vault" push -q origin main
  git -C "$T/peer" pull -q origin main
}

peer_push() {
  git -C "$T/peer" add -A
  git -C "$T/peer" commit -qm "$1"
  git -C "$T/peer" push -q origin main
}

# What an auto-commit timer does: commit everything locally, push nothing.
vault_commit() {
  git -C "$T/vault" add -A
  git -C "$T/vault" commit -qm "$1"
}

# What an auto-commit timer that also pushes does; the peer then pulls it.
timer_push() {
  vault_commit "vault: auto-save"
  git -C "$T/vault" push -q origin main
  git -C "$T/peer" pull -q origin main
}

# The same, while the peer holds a commit it made before pulling: it merges the timer's push
# (clean, or left conflicted for the scenario to resolve).
timer_push_to_diverged_peer() {
  vault_commit "vault: auto-save"
  git -C "$T/vault" push -q origin main
  git -C "$T/peer" pull -q --no-rebase --no-edit origin main > /dev/null 2>&1
}

# Two notes in "Claude Outputs" on both sides.
outputs() {
  mkdir -p "$T/vault/Claude Outputs"
  printf 'foo\n' > "$T/vault/Claude Outputs/foo.md"
  printf 'bar\n' > "$T/vault/Claude Outputs/bar.md"
  timer_push
}

# A pre-rebase hook in the vault: its body runs inside every rebase the sync starts.
hook() {
  printf '#!/bin/sh\n%s\n' "$1" > "$T/vault/.git/hooks/pre-rebase"
  chmod +x "$T/vault/.git/hooks/pre-rebase"
}

push() { # <message> <path>…
  out="$(cd "$T" && VAULT_DIR="$T/vault" "$BASH_BIN" "$SCRIPT" push -m "$1" "${@:2}" 2>&1)"
  rc=$?
}

pull() {
  out="$(cd "$T" && VAULT_DIR="$T/vault" "$BASH_BIN" "$SCRIPT" pull 2>&1)"
  rc=$?
}

origin_has() { git -C "$T/origin.git" show "main:$1" 2>&1; }
parents() { git -C "$T/origin.git" log -1 --format=%P "$1" | wc -w | tr -d ' '; }
backups() { git -C "$T/vault" branch --list 'vault-sync/backup-*' --format='%(refname:short)'; }
listed() { printf '%s\n' "$out" | sed -n "/$1/,/^vault-sync: [^ ]/p" | grep -cxF -- "vault-sync:   $2"; }
named() { listed 'not published' "$1"; }                         # listed as not published?
since_named() { listed 'then changed there by later edits' "$1"; }   # listed as changed since?
says() { printf '%s\n' "$out" | grep -cF -- "$1"; }

echo "1. a new note publishes"
setup
printf 'fresh\n' > "$T/vault/new.md"
push "add new" new.md
check "exit 0, reports pushed" 0 "$rc" "pushed" "$out"
check "origin holds the note" fresh "$(origin_has new.md)" "" ""

echo "2. the same change already upstream is success"
setup
printf 'same\n' > "$T/peer/dup.md"
peer_push "peer adds dup"
printf 'same\n' > "$T/vault/dup.md"
push "add dup" dup.md
check "exit 0, already upstream" 0 "$rc" "already has these changes" "$out"

echo "3. the remote winning every hunk is not success"
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'line one\nMINE\n' > "$T/vault/note.md"
push "my edit" note.md
check "exit 1, not published" 1 "$rc" "not published" "$out"
check "names the lost path" 1 "$(named note.md)" "" "$out"
check "origin keeps the peer's line" PEER "$(origin_has note.md | tail -1)" "" ""

echo "4. a rebase that cannot auto-resolve is not success"
setup
git -C "$T/peer" rm -q note.md
peer_push "peer deletes"
printf 'line one\nMINE\n' > "$T/vault/note.md"
push "my edit" note.md
check "exit 1, names the backup branch" 1 "$rc" "saved as vault-sync/backup-" "$out"
check "the backup branch holds the edit" MINE "$(git -C "$T/vault" show "$(backups):note.md" 2>&1 | tail -1)" "" ""

echo "5. deleting a note the peer already deleted is success"
setup
git -C "$T/peer" rm -q gone.md
peer_push "peer deletes gone"
rm "$T/vault/gone.md"
push "delete gone" gone.md
check "exit 0, already upstream" 0 "$rc" "already has these changes" "$out"

echo "6. another local commit surviving the rebase does not make a lost edit success"
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'auto\n' > "$T/vault/gone.md"
vault_commit "vault: auto-save"
printf 'line one\nMINE\n' > "$T/vault/note.md"
push "my edit" note.md
check "exit 1, not published" 1 "$rc" "not published" "$out"
check "names the lost path" 1 "$(named note.md)" "" "$out"
check "origin keeps the peer's line" PEER "$(origin_has note.md | tail -1)" "" ""
check "the surviving auto-save commit was pushed" auto "$(origin_has gone.md)" "" ""

echo "7. a commit the remote only partly kept is not success"
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'line one\nMINE\n' > "$T/vault/note.md"
printf 'fresh\n' > "$T/vault/new.md"
push "two notes" note.md new.md
check "two paths: exit 1" 1 "$rc" "not published" "$out"
check "two paths: names the lost one" 1 "$(named note.md)" "" "$out"
check "two paths: not the published one" 0 "$(named new.md)" "" "$out"
check "two paths: origin holds the new note" fresh "$(origin_has new.md)" "" ""
setup
ten_lines
printf 'a\nPEER\nc\nd\ne\nf\ng\nh\ni\nj\n' > "$T/peer/note.md"
peer_push "peer edits line 2"
printf 'a\nMINE\nc\nd\ne\nf\ng\nh\ni\nMINE-END\n' > "$T/vault/note.md"
push "two hunks" note.md
check "two hunks: exit 1, names the note" 1 "$rc" "vault-sync:   note.md" "$out"
check "two hunks: origin kept the peer's hunk" PEER "$(origin_has note.md | sed -n 2p)" "" ""
check "two hunks: origin took the other hunk" MINE-END "$(origin_has note.md | tail -1)" "" ""

echo "8. a clean merge with a concurrent edit is not the note as committed"
setup
ten_lines
printf 'a\nPEER\nc\nd\ne\nf\ng\nh\ni\nj\n' > "$T/peer/note.md"
peer_push "peer edits line 2"
printf 'a\nb\nc\nd\ne\nf\ng\nh\ni\nMINE-END\n' > "$T/vault/note.md"
push "my end" note.md
check "exit 1, names the note" 1 "$rc" "vault-sync:   note.md" "$out"
check "origin holds both edits" "PEER MINE-END" "$(origin_has note.md | sed -n '2p;10p' | tr '\n' ' ' | sed 's/ $//')" "" ""
push "my end" note.md
check "pushing the merged note again is success" 0 "$rc" "already has these changes" "$out"

echo "9. the shape of a path does not change the verdict"
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'line one\nMINE\n' > "$T/vault/note.md"
push "my edit" "$T/vault/note.md"
check "absolute path: exit 1" 1 "$rc" "not published" "$out"
check "absolute path: named repo-relative" 1 "$(named note.md)" "" "$out"
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'line one\nMINE\n' > "$T/vault/note.md"
push "my edit" ./note.md
check "./ path: exit 1, named repo-relative" 1 "$(named note.md)" "" "$out"
setup
mkdir "$T/vault/Claude Outputs"
printf 'mine\n' > "$T/vault/Claude Outputs/foo.md"
vault_commit "vault: auto-save"
git -C "$T/vault" push -q origin main
git -C "$T/peer" pull -q origin main
printf 'theirs\n' > "$T/peer/Claude Outputs/bar.md"
peer_push "peer adds bar"
push "publish the folder" "Claude Outputs"
check "directory already upstream: exit 0" 0 "$rc" "already has these changes" "$out"
setup
mkdir "$T/vault/empty"
push "nothing" empty
check "a path that names no file: exit 1" 1 "$rc" "name no file" "$out"

echo "10. pull that parks local commits on a backup branch is not success"
setup
printf 'my brand new note\n' > "$T/vault/My Note.md"
vault_commit "vault: auto-save 1"
printf 'edited\n' > "$T/vault/gone.md"
vault_commit "vault: auto-save 2"
git -C "$T/peer" rm -q gone.md
peer_push "peer deletes gone"
pull
check "exit 1, names the backup branch" 1 "$rc" "saved as vault-sync/backup-" "$out"
check "the backup branch holds the unrelated note" "my brand new note" "$(git -C "$T/vault" show "$(backups):My Note.md" 2>&1)" "" ""

echo "11. a rebase that fails without a conflict resets nothing"
setup
printf 'x\n' > "$T/peer/peer.md"
peer_push "peer adds a note"
printf 'auto\n' > "$T/vault/gone.md"
vault_commit "vault: auto-save"
hook "printf 'typed\\n' >> '$T/vault/note.md'"
pull
check "write during the rebase: exit 1" 1 "$rc" "did not run" "$out"
check "write during the rebase: no backup branch" "" "$(backups)" "" ""
check "write during the rebase: HEAD back on main" main "$(git -C "$T/vault" symbolic-ref --short HEAD 2>&1)" "" ""
check "write during the rebase: local commit still on main" "vault: auto-save" "$(git -C "$T/vault" log -1 --format=%s main)" "" ""
check "write during the rebase: the write survives" typed "$(tail -1 "$T/vault/note.md")" "" ""
setup
printf 'x\n' > "$T/peer/peer.md"
peer_push "peer adds a note"
printf 'fresh\n' > "$T/vault/new.md"
hook "exit 1"
push "add new" new.md
check "rebase refused: exit 1, names the note" 1 "$rc" "vault-sync:   new.md" "$out"
check "rebase refused: no backup branch" "" "$(backups)" "" ""
check "rebase refused: the commit is still on main" "add new" "$(git -C "$T/vault" log -1 --format=%s main)" "" ""

echo "12. the guards run before anything is staged or committed"
setup
printf 'line one\nLOCAL\n' > "$T/vault/note.md"
vault_commit "local edit"
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
git -C "$T/vault" pull -q --no-rebase origin main > /dev/null 2>&1
push "my edit" note.md
check "merge in progress: exit 1" 1 "$rc" "a MERGE_HEAD is in progress" "$out"
check "merge in progress: the conflict is left unstaged" "UU note.md" "$(git -C "$T/vault" status --porcelain -- note.md)" "" ""
setup
git -C "$T/vault" checkout -q --detach
printf 'fresh\n' > "$T/vault/new.md"
push "add new" new.md
check "detached HEAD: exit 1" 1 "$rc" "detached HEAD" "$out"
check "detached HEAD: nothing staged" "?? new.md" "$(git -C "$T/vault" status --porcelain -- new.md)" "" ""
check "detached HEAD: nothing committed" base "$(git -C "$T/vault" log -1 --format=%s)" "" ""

echo "13. a stash that does not fully re-apply is reported as it is"
setup
printf 'UPSTREAM\n' > "$T/peer/x.md"
peer_push "peer adds x"
printf 'MINE\n' > "$T/vault/x.md"
printf 'line one\nWIP\n' > "$T/vault/note.md"
pull
check "untracked collision: names the edited file" 0 "$rc" "uncommitted edits to: note.md" "$out"
check "untracked collision: the edit is applied" WIP "$(tail -1 "$T/vault/note.md")" "" ""
check "untracked collision: the stash is kept" 1 "$(git -C "$T/vault" stash list | wc -l | tr -d ' ')" "" ""
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'line one\nWIP\n' > "$T/vault/note.md"
pull
check "tracked conflict: files left as committed" 0 "$rc" "tracked files left as committed" "$out"
check "tracked conflict: the note is at the remote's" PEER "$(tail -1 "$T/vault/note.md")" "" ""
check "tracked conflict: the stash is kept" 1 "$(git -C "$T/vault" stash list | wc -l | tr -d ' ')" "" ""

echo "14. a push that cannot sync names the files it could not verify"
setup
git -C "$T/vault" remote set-url origin "$T/missing.git"
printf 'fresh\n' > "$T/vault/new.md"
push "add new" new.md
check "fetch failed: exit 1, names the file" 1 "$rc" "not verified as published: new.md" "$out"
check "fetch failed: the commit stays on main" "add new" "$(git -C "$T/vault" log -1 --format=%s main)" "" ""

echo "15. a push is judged on the notes the vault changed; a file it left as origin had it may change upstream on top of that"
setup
outputs
printf 'BAR-PEER\n' > "$T/peer/Claude Outputs/bar.md"
peer_push "peer edits bar"
printf 'FOO-MINE\n' > "$T/vault/Claude Outputs/foo.md"
push "my foo" "Claude Outputs"
check "directory, a sibling edited upstream: exit 0, pushed" 0 "$rc" "pushed" "$out"
check "directory, a sibling edited upstream: the sibling is not named" 0 "$(named 'Claude Outputs/bar.md')" "" "$out"
check "directory, a sibling edited upstream: the sibling is named as changed since" 1 "$(since_named 'Claude Outputs/bar.md')" "" "$out"
check "directory, a sibling edited upstream: origin holds the note" FOO-MINE "$(origin_has 'Claude Outputs/foo.md')" "" ""
check "directory, a sibling edited upstream: origin keeps the peer's sibling" BAR-PEER "$(origin_has 'Claude Outputs/bar.md')" "" ""
setup
outputs
git -C "$T/peer" rm -q "Claude Outputs/bar.md"
peer_push "peer deletes bar"
printf 'FOO-MINE\n' > "$T/vault/Claude Outputs/foo.md"
push "my foo" "Claude Outputs"
check "directory, a sibling deleted upstream: exit 0, pushed" 0 "$rc" "pushed" "$out"
setup
outputs
printf 'FOO-MINE\n' > "$T/vault/Claude Outputs/foo.md"
timer_push
printf 'BAR-PEER\n' > "$T/peer/Claude Outputs/bar.md"
peer_push "peer edits bar"
push "my foo" "Claude Outputs"
check "directory the timer pushed, a sibling edited since: exit 0" 0 "$rc" "nothing to push" "$out"
check "directory the timer pushed, a sibling edited since: names the sibling as changed since" 1 "$(since_named 'Claude Outputs/bar.md')" "" "$out"
check "directory the timer pushed, a sibling edited since: not claimed as already there" 0 "$(says 'already has these changes')" "" "$out"
setup
outputs
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits note"
printf 'FOO-MINE\n' > "$T/vault/Claude Outputs/foo.md"
push "my foo" '*.md'
check "glob, another note edited upstream: exit 0, pushed" 0 "$rc" "pushed" "$out"
setup
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push
printf 'line one\nMINE\nappended\n' > "$T/peer/note.md"
peer_push "peer appends"
push "my edit" note.md
check "an edit the timer pushed, appended to since: exit 0" 0 "$rc" "nothing to push" "$out"
check "an edit the timer pushed, appended to since: named as changed since" 1 "$(since_named note.md)" "" "$out"
setup
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push
# A push that left the vault's own origin/main behind: the fork point is taken after the fetch.
git -C "$T/vault" update-ref refs/remotes/origin/main "$(git -C "$T/vault" rev-parse HEAD~1)"
printf 'line one\nMINE\nappended\n' > "$T/peer/note.md"
peer_push "peer appends"
push "my edit" note.md
check "the same, origin/main lagging in the vault: exit 0" 0 "$rc" "nothing to push" "$out"
check "the same, origin/main lagging in the vault: named as changed since" 1 "$(since_named note.md)" "" "$out"
setup
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push
printf 'edited\n' > "$T/vault/gone.md"
vault_commit "vault: auto-save"
git -C "$T/peer" rm -q gone.md
peer_push "peer deletes gone"
push "my edit" note.md
check "nothing new to publish, another local commit parked: exit 1" 1 "$rc" "saved as vault-sync/backup-" "$out"
check "nothing new to publish, another local commit parked: says so" 1 "$rc" "changed nothing origin/main did not already have" "$out"
setup
printf '# Hub\n\n- [[ADR-0001 Old]]\n' > "$T/vault/Hub.md"
timer_push
printf '%s\n' '- [[ADR-0002 New]]' >> "$T/vault/Hub.md"
timer_push
printf '%s\n' '- [[Peer Note]]' >> "$T/peer/Hub.md"
peer_push "peer links its note"
printf 'new decision\n' > "$T/vault/ADR-0002 New.md"
push "docs(adr): ADR-0002 New" "ADR-0002 New.md" Hub.md
check "new note plus a hub link the timer pushed: exit 0, pushed" 0 "$rc" "pushed" "$out"
check "new note plus a hub link the timer pushed: origin holds the note" "new decision" "$(origin_has 'ADR-0002 New.md')" "" ""
setup
outputs
printf 'FOO-PEER\n' > "$T/peer/Claude Outputs/foo.md"
printf 'BAR-PEER\n' > "$T/peer/Claude Outputs/bar.md"
peer_push "peer edits both"
printf 'FOO-MINE\n' > "$T/vault/Claude Outputs/foo.md"
push "my foo" "Claude Outputs"
check "directory, its own note lost: exit 1, names it" 1 "$rc" "vault-sync:   Claude Outputs/foo.md" "$out"
check "directory, its own note lost: not the sibling" 0 "$(named 'Claude Outputs/bar.md')" "" "$out"
setup
printf 'line one\nMINE\n' > "$T/vault/note.md"
vault_commit "vault: auto-save"
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
push "my edit" note.md
check "an edit only the timer committed, lost upstream: exit 1, names it" 1 "$rc" "vault-sync:   note.md" "$out"
setup
printf 'line one\nTIMER\n' > "$T/vault/note.md"
vault_commit "vault: auto-save"
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer edits"
printf 'line one\nline two\n' > "$T/vault/note.md"
push "restore line two" note.md
check "restoring the forked version, lost upstream: exit 1, names it" 1 "$rc" "vault-sync:   note.md" "$out"

echo "16. a note the timer already pushed is judged by what origin did to it after"
setup
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push
printf 'line one\nPEER2\n' > "$T/peer/note.md"
peer_push "peer overwrites"
push "my edit" note.md
check "overwritten after, on top of it: origin holds the peer's line" PEER2 "$(origin_has note.md | tail -1)" "" ""
check "overwritten after, on top of it: exit 0" 0 "$rc" "nothing to push" "$out"
check "overwritten after, on top of it: named as changed since" 1 "$(since_named note.md)" "" "$out"
check "overwritten after, on top of it: not claimed as already there" 0 "$(says 'already has these changes')" "" "$out"
setup
mkdir "$T/vault/Claude Outputs"
printf 'my session output\n' > "$T/vault/Claude Outputs/Session.md"
timer_push
git -C "$T/peer" rm -q "Claude Outputs/Session.md"
peer_push "peer deletes the note"
push "docs(claude-output): session" "Claude Outputs/Session.md"
check "deleted after, on top of it: origin no longer has it" absent "$(git -C "$T/origin.git" cat-file -e "main:Claude Outputs/Session.md" 2> /dev/null && echo present || echo absent)" "" ""
check "deleted after, on top of it: exit 0" 0 "$rc" "nothing to push" "$out"
check "deleted after, on top of it: named as deleted since" 1 "$(since_named 'Claude Outputs/Session.md (deleted)')" "" "$out"
check "deleted after, on top of it: not claimed as already there" 0 "$(says 'already has these changes')" "" "$out"
# A peer edits line 2 without pulling, the timer pushes the session's edit, and the peer's
# merge of that push keeps the peer's line: an edit made without the session's version won.
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
git -C "$T/peer" commit -qam "peer edits, before pulling"
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push_to_diverged_peer
printf 'line one\nPEER\n' > "$T/peer/note.md"
peer_push "peer merges the timer's push, keeps its line"
push "my edit" note.md
check "a concurrent edit merged over it: origin/main is the peer's merge" 2 "$(parents main)" "" ""
check "a concurrent edit merged over it: origin holds the peer's line" PEER "$(origin_has note.md | tail -1)" "" ""
check "a concurrent edit merged over it: exit 1, names it" 1 "$rc" "vault-sync:   note.md" "$out"
check "a concurrent edit merged over it: says an edit made without it was merged over it" 1 "$(says 'an edit made without that version was merged over it')" "" "$out"
setup
ten_lines
printf 'a\nPEER\nc\nd\ne\nf\ng\nh\ni\nj\n' > "$T/peer/note.md"
git -C "$T/peer" commit -qam "peer edits line 2, before pulling"
printf 'a\nb\nc\nd\ne\nf\ng\nh\ni\nMINE-END\n' > "$T/vault/note.md"
timer_push_to_diverged_peer
git -C "$T/peer" push -q origin main
push "my end" note.md
check "a concurrent edit merged cleanly into it: origin/main is the peer's merge" 2 "$(parents main)" "" ""
check "a concurrent edit merged cleanly into it: origin holds both edits" "PEER MINE-END" "$(origin_has note.md | sed -n '2p;10p' | tr '\n' ' ' | sed 's/ $//')" "" ""
check "a concurrent edit merged cleanly into it: exit 1, names it" 1 "$rc" "vault-sync:   note.md" "$out"
setup
printf 'line one\nPEER\n' > "$T/peer/note.md"
git -C "$T/peer" commit -qam "peer edits, before pulling"
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push_to_diverged_peer
printf 'line one\nMINE\n' > "$T/peer/note.md"
git -C "$T/peer" add -A
git -C "$T/peer" commit -qm "peer merges the timer's push, keeps the session's line"
printf 'line one\nMINE\nappended\n' > "$T/peer/note.md"
peer_push "peer appends"
push "my edit" note.md
check "a concurrent edit the merge discarded, then an append: the append sits on the peer's merge" 2 "$(parents main~1)" "" ""
check "a concurrent edit the merge discarded, then an append: origin holds the session's line" "line one|MINE|appended" "$(origin_has note.md | tr '\n' '|' | sed 's/|$//')" "" ""
check "a concurrent edit the merge discarded, then an append: exit 0" 0 "$rc" "nothing to push" "$out"
check "a concurrent edit the merge discarded, then an append: named as changed since" 1 "$(since_named note.md)" "" "$out"
setup
printf 'line one\nMINE\n' > "$T/peer/note.md"
git -C "$T/peer" commit -qam "peer makes the same edit, before pulling"
printf 'line one\nMINE\n' > "$T/vault/note.md"
timer_push_to_diverged_peer
printf 'line one\nMINE\nappended\n' > "$T/peer/note.md"
peer_push "peer appends"
push "my edit" note.md
check "the same edit made concurrently, then an append: the append sits on the peer's merge" 2 "$(parents main~1)" "" ""
check "the same edit made concurrently, then an append: exit 0" 0 "$rc" "nothing to push" "$out"
check "the same edit made concurrently, then an append: named as changed since" 1 "$(since_named note.md)" "" "$out"

echo "$pass passed · $fail failed ($("$BASH_BIN" -c 'echo "bash $BASH_VERSION"'))"
[ "$fail" = 0 ]

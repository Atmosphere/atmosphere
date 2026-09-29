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
# test.sh — drives `vault-sync.sh push` through its five outcomes against real
# git repositories (a bare origin, the vault clone, a peer clone) in a temp dir.
#
# The contract under test is the exit status, because it is what a calling
# session reads as "published": 0 only when origin/main ends up holding the
# notes as committed, non-zero whenever they are not published — the remote
# winning every conflicting hunk, or a rebase that cannot auto-resolve.
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

peer_push() {
  git -C "$T/peer" add -A
  git -C "$T/peer" commit -qm "$1"
  git -C "$T/peer" push -q origin main
}

push() { # <message> <path>…
  out="$(cd "$T" && VAULT_DIR="$T/vault" "$BASH_BIN" "$SCRIPT" push -m "$1" "${@:2}" 2>&1)"
  rc=$?
}

echo "1. a new note publishes"
setup
printf 'fresh\n' > "$T/vault/new.md"
push "add new" new.md
check "exit 0, reports pushed" 0 "$rc" "pushed" "$out"
check "origin holds the note" fresh "$(git -C "$T/origin.git" show main:new.md 2>&1)" "" ""

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
check "exit 1, names the lost path" 1 "$rc" "kept its own version of: note.md" "$out"
check "origin keeps the peer's line" PEER "$(git -C "$T/origin.git" show main:note.md | tail -1)" "" ""

echo "4. a rebase that cannot auto-resolve is not success"
setup
git -C "$T/peer" rm -q note.md
peer_push "peer deletes"
printf 'line one\nMINE\n' > "$T/vault/note.md"
push "my edit" note.md
check "exit 1, names the backup branch" 1 "$rc" "your commit is on vault-sync/backup-" "$out"
backup="$(git -C "$T/vault" branch --list 'vault-sync/backup-*' --format='%(refname:short)')"
check "the backup branch holds the edit" MINE "$(git -C "$T/vault" show "$backup:note.md" 2>&1 | tail -1)" "" ""

echo "5. deleting a note the peer already deleted is success"
setup
git -C "$T/peer" rm -q gone.md
peer_push "peer deletes gone"
rm "$T/vault/gone.md"
push "delete gone" gone.md
check "exit 0, already upstream" 0 "$rc" "already has these changes" "$out"

echo "$pass passed · $fail failed ($("$BASH_BIN" -c 'echo "bash $BASH_VERSION"'))"
[ "$fail" = 0 ]

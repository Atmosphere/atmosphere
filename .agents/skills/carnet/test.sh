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
# ABOUTME: Tests for carnet.sh and its hooks against a stub gh — every refusal path is made to fire
# ABOUTME: No network, no real tracker: run `.agents/skills/carnet/test.sh` from anywhere
set -euo pipefail

# Isolated from the developer's git config: no signing prompt, a fixed identity. The fixture
# commits below would otherwise obey a global commit.gpgsign — failing the suite where the
# signer needs a prompt, and signing throwaway commits with a real key where it does not.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

here=$(cd "$(dirname "$0")" && pwd)
carnet="$here/carnet.sh"
tmp=$(mktemp -d)
peer_pid=""
cleanup() { [ -z "$peer_pid" ] || kill "$peer_pid" 2>/dev/null || true; rm -rf "$tmp"; }
trap cleanup EXIT

# ------------------------------------------------------------------ stub gh
# Reads come from canned files under $CARNET_STUB; every call is appended to calls.log. A
# comment and a new issue travel as a JSON payload file (--input) that carnet.sh deletes
# afterwards, so the stub unwraps .body and .title out of it and logs them as BODY and TITLE.
mkdir -p "$tmp/bin" "$tmp/stub"
cat > "$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
S=$CARNET_STUB
body=""; title=""; labels=""; prev=""
for a in "$@"; do
    if [ "$prev" = "--input" ]; then
        body=$(jq -r '.body // empty' "$a" 2>/dev/null || true)
        title=$(jq -r '.title // empty' "$a" 2>/dev/null || true)
        labels=$(jq -r '.labels // [] | join(",")' "$a" 2>/dev/null || true)
    fi
    prev=$a
done
{ printf 'CALL %s\n' "$*"
  [ -z "$title" ]  || printf 'TITLE %s\n' "$title"
  [ -z "$labels" ] || printf 'LABELS %s\n' "$labels"
  [ -z "$body" ]  || printf 'BODY %s\n' "$body"
  printf 'END\n'; } >> "$S/calls.log"
# Order matters: /issues/N/comments and /issues?query both also match the bare-read pattern.
case "$1 $2" in
    "api user")                 printf 'tester\n' ;;
    "api repos/"*"/comments")   cat "$S/comments.txt" 2>/dev/null || true ;;
    "api repos/"*"/issues?"*)   cat "$S/list.txt" 2>/dev/null || true ;;
    "api repos/"*"/issues")     printf 'https://github.com/Atmosphere/atmosphere-carnet/issues/321\n' ;;
    "api repos/"*"/issues/"*)   cat "$S/issue.json" ;;
    "api repos/"*)              cat "$S/private.txt" 2>/dev/null || printf 'true\n' ;;
    *) : ;;
esac
exit 0
EOF
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH"
export CARNET_STUB="$tmp/stub"
S=$CARNET_STUB

# ------------------------------------------------------------------ fake repo + session
# The checkout is deliberately NOT named after the project: the prefix must come from origin.
ORIGIN=https://github.com/Atmosphere/atmosphere.git
git init -q "$tmp/repo"
git -C "$tmp/repo" -c user.name=t -c user.email=t@t.t commit -q --allow-empty -m init
git -C "$tmp/repo" branch -M main
git -C "$tmp/repo" remote add origin "$ORIGIN"
printf 'tracker = "Atmosphere/atmosphere-carnet"\n' > "$tmp/repo/registre.toml"
cd "$tmp/repo"
head_sha=$(git rev-parse HEAD)

export CLAUDE_CONFIG_DIR="$tmp/cfg"
export CLAUDE_CODE_SESSION_ID="11111111-aaaa-bbbb-cccc-000000000001"
export CLAUDE_PID=$$
mkdir -p "$tmp/cfg/sessions"
printf '{"pid":%s,"sessionId":"%s","name":"TestSession"}\n' "$$" "$CLAUDE_CODE_SESSION_ID" > "$tmp/cfg/sessions/$$.json"
HOST=$(hostname -s 2>/dev/null || hostname)
ME=$CLAUDE_CODE_SESSION_ID
PEER="22222222-aaaa-bbbb-cccc-000000000002"
DEAD="33333333-aaaa-bbbb-cccc-000000000003"
ledger="$tmp/cfg/carnet-claims/$ME.jsonl"

# A live peer session on this host: a real process plus its sessions file.
sleep 600 & peer_pid=$!
printf '{"pid":%s,"sessionId":"%s","name":"PeerSession"}\n' "$peer_pid" "$PEER" > "$tmp/cfg/sessions/$peer_pid.json"

# ------------------------------------------------------------------ fixtures
issue() { # <state> <labels-json> <assignees-json>
    printf '{"number":42,"title":"[atmosphere] Thing","html_url":"https://github.com/Atmosphere/atmosphere-carnet/issues/42","state":"%s","labels":%s,"assignees":%s}\n' \
        "$(printf '%s' "$1" | tr 'A-Z' 'a-z')" "$2" "$3" > "$S/issue.json"
}
issue_open()   { issue OPEN '[{"name":"atmosphere"}]' '[]'; }
issue_held()   { issue OPEN '[{"name":"atmosphere"},{"name":"in-progress"}]' '[{"login":"tester"}]'; }
issue_closed() { issue CLOSED '[]' '[]'; }

claim_marker() { # <session> <name> <user> <host> <pid>
    printf '<!-- carnet-claim {"v":1,"session":"%s","name":"%s","user":"%s","host":"%s","pid":%s,"repo":"atmosphere","branch":"main","at":"2026-09-02T10:00:00Z"} -->\n🔒 Claimed by @%s\n' \
        "$1" "$2" "$3" "$4" "$5" "$3"
}
release_marker() { # <session>
    printf '<!-- carnet-release {"v":1,"session":"%s","name":"x","user":"x","host":"x","pid":1,"repo":"atmosphere","branch":"main","at":"2026-09-02T11:00:00Z","reason":"done"} -->\n🔓 Released\n' "$1"
}
comments() { cat > "$S/comments.txt"; }
reset() { : > "$S/calls.log"; : > "$S/comments.txt"; rm -rf "$tmp/cfg/carnet-claims"; rm -f "$S/private.txt" "$S/list.txt"; issue_open; }

# ------------------------------------------------------------------ assertions
pass=0; fail=0
ok()  { pass=$((pass + 1)); printf '  ✓ %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  ✗ %s\n' "$1"; }
assert_eq()   { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 — expected '$3', got '$2'"; fi; }
assert_grep() { # <desc> <regex> <file>
    if grep -qE -- "$2" "$3" 2>/dev/null; then ok "$1"; else
        bad "$1 — no /$2/ in $(basename "$3")"
        # A missing file is a failed check, not a reason to abort the rest of the suite.
        if [ -f "$3" ]; then sed 's/^/      | /' "$3" | head -20 || true
        else printf '      | (%s does not exist)\n' "$3"; fi
    fi
}
assert_no_grep() { if grep -qE -- "$2" "$3"; then bad "$1 — found /$2/ in $(basename "$3")"; else ok "$1"; fi; }
count_calls() { grep -cE "^CALL $1" "$S/calls.log" || true; }
WRITES='api .* -X (POST|DELETE|PATCH)'

run_carnet() { # args... ; sets rc, writes $tmp/out and $tmp/err
    rc=0
    bash "$carnet" "$@" > "$tmp/out" 2> "$tmp/err" || rc=$?
}

section() { printf '\n%s\n' "$1"; }

# ================================================================== syntax
section "syntax"
for f in "$carnet" "$here"/hooks/*.sh "$here/test.sh"; do
    if bash -n "$f"; then ok "bash -n $(basename "$f")"; else bad "bash -n $(basename "$f")"; fi
done
if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -S warning "$carnet" "$here"/hooks/*.sh "$here/test.sh"; then ok "shellcheck"; else bad "shellcheck"; fi
fi

# ================================================================== tracker
section "tracker"
reset
run_carnet tracker
assert_eq "tracker prints the register registre.toml names" "$(cat "$tmp/out")" "Atmosphere/atmosphere-carnet"
assert_eq "and costs no gh call" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

reset
export REGISTRE_TRACKER="Atmosphere/other-carnet"
run_carnet tracker
unset REGISTRE_TRACKER
assert_eq "REGISTRE_TRACKER overrides registre.toml" "$(cat "$tmp/out")" "Atmosphere/other-carnet"

# The tracker is spliced into every API path, so a malformed one never reaches a request.
reset
export REGISTRE_TRACKER="Atmosphere/atmosphere-carnet/issues/1/comments?x"
run_carnet status 42
unset REGISTRE_TRACKER
assert_eq "a tracker that is not owner/repo is refused" "$rc" 1
assert_grep "and named" 'tracker must be owner/repo' "$tmp/err"
assert_eq "before any gh call" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

reset
mv registre.toml registre.toml.off
run_carnet status 42
mv registre.toml.off registre.toml
assert_eq "a checkout without registre.toml has no register" "$rc" 1
assert_grep "and says where the file is documented" 'no registre.toml .*\(see \.registre/README\.md\)' "$tmp/err"

# ================================================================== claim
section "claim"
reset
run_carnet claim 42
assert_eq "claim on an unclaimed issue exits 0" "$rc" 0
assert_grep "assigns me" 'issues/42/assignees -X POST -f assignees\[\]=tester' "$S/calls.log"
assert_grep "adds the label" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
assert_grep "posts a claim marker" '^BODY <!-- carnet-claim \{"v":1,"session":"11111111' "$S/calls.log"
assert_grep "marker carries the session name" '"name":"TestSession"' "$S/calls.log"
assert_grep "marker carries repo and branch" '"repo":"atmosphere","branch":"main"' "$S/calls.log"
assert_grep "ledger records the claim" '"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"
assert_grep "ledger starts with the identity" '"kind":"identity"' "$ledger"

reset
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
run_carnet claim 42
assert_eq "claim on my own claim is a no-op" "$rc" 0
assert_grep "says so" "already held by this session" "$tmp/out"
assert_eq "no tracker write" "$(count_calls "$WRITES")" 0

reset
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet claim 42
assert_eq "refuses a claim held by a live session on this host" "$rc" 2
assert_grep "names the holder" "held by @peer · session PeerSession \(22222222\) on $HOST, still running" "$tmp/err"
assert_eq "no tracker write when refused" "$(count_calls "$WRITES")" 0
run_carnet claim 42 --steal
assert_eq "--steal overrides" "$rc" 0
assert_grep "warns locally" "stealing carnet#42 from live session PeerSession" "$tmp/err"
assert_grep "the comment says it was stolen from a live session" "Stolen from live session \*\*PeerSession\*\*" "$S/calls.log"
assert_grep "the displaced human is unassigned" 'issues/42/assignees -X DELETE -f assignees\[\]=peer' "$S/calls.log"

reset
comments < <(claim_marker "$DEAD" GoneSession peer "$HOST" 999999)
run_carnet claim 42
assert_eq "takes over a claim whose session ended on this host" "$rc" 0
assert_grep "the comment says it took over" "Took over from ended session \*\*GoneSession\*\*" "$S/calls.log"

reset
comments < <(claim_marker "$PEER" RemoteSession phil elsewhere 4242)
run_carnet claim 42
assert_eq "refuses a claim held on another host" "$rc" 2
assert_grep "explains liveness is unknowable" "on host elsewhere since .* liveness cannot be checked from $HOST" "$tmp/err"
run_carnet claim 42 --steal
assert_eq "--steal takes it" "$rc" 0
assert_grep "the comment warns the displaced session" "Stolen from session \*\*RemoteSession\*\* \(\`22222222\`\) of @phil on elsewhere" "$S/calls.log"

reset
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid"; release_marker "$PEER")
run_carnet claim 42
assert_eq "a released claim no longer holds" "$rc" 0
assert_no_grep "no takeover text after a release" "Took over|Stolen" "$S/calls.log"

reset
issue_closed
run_carnet claim 42
assert_eq "cannot claim a closed issue" "$rc" 1
assert_grep "says closed" "is closed" "$tmp/err"

reset
run_carnet claim 42 --dry-run
assert_eq "dry-run exits 0" "$rc" 0
assert_eq "dry-run writes nothing" "$(count_calls "$WRITES")" 0
assert_grep "dry-run prints the edit" '\[dry-run\] api repos/Atmosphere/atmosphere-carnet/issues/42' "$tmp/err"
[ -f "$ledger" ] && bad "dry-run must not touch the ledger" || ok "dry-run leaves no ledger"

# ================================================================== release
section "release"
reset
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
run_carnet claim 42 >/dev/null 2>&1 || true   # not held per marker? it is — no-op, but seed the ledger by hand
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"TestSession","user":"tester","host":"%s","pid":%s}\n{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42,"at":"2026-09-02T10:00:00Z"}\n' "$ME" "$HOST" "$$" > "$ledger"
: > "$S/calls.log"
run_carnet release --all --reason session-ended
assert_eq "release --all exits 0" "$rc" 0
assert_grep "removes the label" 'issues/42/labels/in-progress -X DELETE' "$S/calls.log"
assert_grep "removes the assignee" 'issues/42/assignees -X DELETE -f assignees\[\]=tester' "$S/calls.log"
assert_grep "posts a release marker with the reason" '^BODY <!-- carnet-release \{.*"reason":"session-ended"' "$S/calls.log"
[ -f "$ledger" ] && bad "ledger removed once empty" || ok "ledger removed once empty"

reset
run_carnet release 42
assert_eq "releasing something not held fails" "$rc" 1
assert_eq "without writing" "$(count_calls "$WRITES")" 0

reset
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet release 42
assert_eq "cannot release another session's claim" "$rc" 2
assert_grep "points at claim --steal" "claim 42 --steal" "$tmp/err"

# ================================================================== close
section "close"
reset
run_carnet close 42
assert_eq "close without --why fails" "$rc" 1
assert_grep "says why" "close needs --why" "$tmp/err"
assert_eq "and writes nothing" "$(count_calls "$WRITES")" 0

reset
run_carnet close 42 --why "fixed in the seeder" --commit "$head_sha"
assert_eq "close with a reason exits 0" "$rc" 0
assert_grep "the comment carries the reason" '\*\*Why:\*\* fixed in the seeder' "$S/calls.log"
assert_grep "and the commit URL on the code repo" "https://github.com/Atmosphere/atmosphere/commit/$head_sha" "$S/calls.log"
assert_grep "the comment is also a release marker" '^BODY <!-- carnet-release \{.*"reason":"closed"' "$S/calls.log"
assert_grep "then closes" '^CALL api repos/Atmosphere/atmosphere-carnet/issues/42 -X PATCH -f state=closed' "$S/calls.log"

reset
run_carnet close 42 --why x --commit deadbeef
assert_eq "an unknown commit is refused" "$rc" 1
assert_grep "names the sha" "commit deadbeef is not in this repository" "$tmp/err"

reset
issue_held
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet close 42 --why x
assert_eq "cannot close an issue another session holds" "$rc" 2

reset
issue_held
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
run_carnet close 42 --why "done"
assert_eq "closing my own held issue works" "$rc" 0
assert_grep "and drops label + assignee first" 'issues/42/labels/in-progress -X DELETE' "$S/calls.log"

# A dry run closes nothing, so it must not forget that this session filed the issue.
reset
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"TestSession","user":"tester","host":"%s","pid":%s}\n{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":42,"at":"2026-09-02T10:00:00Z"}\n' "$ME" "$HOST" "$$" > "$ledger"
run_carnet close 42 --why "would be fixed" --dry-run
assert_eq "close --dry-run exits 0" "$rc" 0
assert_eq "close --dry-run writes nothing" "$(count_calls "$WRITES")" 0
assert_grep "close --dry-run keeps the filed line" '"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"
run_carnet close 42 --why "fixed"
assert_no_grep "a real close drops it" '"kind":"filed"' "$ledger"

# ================================================================== create
section "create"
reset
run_carnet create --title "Thing is wrong" --label limitation --body "where / what / fix"
assert_eq "create exits 0" "$rc" 0
assert_grep "title gets the project prefix" '^TITLE \[atmosphere\] Thing is wrong' "$S/calls.log"
assert_grep "project label always, extra labels after" '^LABELS atmosphere,limitation' "$S/calls.log"
assert_grep "prints the URL" "issues/321" "$tmp/out"
assert_grep "prints the marker hint for a limitation" 'LIMITATION\(registre#321\)' "$tmp/out"
# An audit reading the ledger offline credits a registered limitation from this line alone.
assert_grep "the ledger records the limitation label" '"kind":"limitation","tracker":"Atmosphere/atmosphere-carnet","issue":321' "$ledger"
# bilan caps a session that filed an issue and left it open, and this line is all it reads:
# the one writer of the cross-skill contract, pinned here rather than through bilan's fixtures.
assert_grep "the ledger records the issue as filed by this session" '"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":321' "$ledger"
assert_grep "checked the tracker is private" '^CALL api repos/Atmosphere/atmosphere-carnet -q .private' "$S/calls.log"

reset
run_carnet create --title "[registre] Already prefixed" --body b
assert_grep "an existing prefix is kept" '^TITLE \[registre\] Already prefixed' "$S/calls.log"
# The absence check below reads this ledger, so it must exist — a missing file would pass it.
assert_grep "an issue filed without the label is still recorded as filed" '"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":321' "$ledger"
assert_no_grep "an issue filed without the label is not recorded as a limitation" '"kind":"limitation"' "$ledger"

reset
run_carnet create --title "Only looking" --body b --dry-run
[ -f "$ledger" ] && bad "create --dry-run records nothing as filed" || ok "create --dry-run records nothing as filed"

reset
printf 'false\n' > "$S/private.txt"
run_carnet create --title T --body b
assert_eq "a public tracker is refused" "$rc" 1
assert_eq "and nothing is filed" "$(count_calls 'api repos/[^ ]*/issues -X POST')" 0

reset
printf 'unknown\n' > "$S/private.txt"
run_carnet create --title T --body b
assert_eq "a tracker whose visibility cannot be read is refused too" "$rc" 1
assert_eq "and nothing is filed either" "$(count_calls 'api repos/[^ ]*/issues -X POST')" 0

reset
run_carnet create --title T < /dev/null
assert_eq "an empty body is refused" "$rc" 1

reset
run_carnet create --title "With claim" --body b --claim
assert_eq "create --claim exits 0" "$rc" 0
assert_grep "and claims the new number" 'issues/321/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

reset
printf 'where / what / fix\n\n---\n_Generated by [Claude Code](https://claude.ai/code)_\n' > "$tmp/signed.md"
run_carnet create --title "Signed" --body-file "$tmp/signed.md"
assert_eq "a signed body is filed" "$rc" 0
assert_no_grep "the Claude Code footer is stripped from the body" 'Generated by' "$S/calls.log"
assert_no_grep "and so is the rule it leaves dangling" '^---' "$S/calls.log"
assert_grep "the body itself survives" '^BODY where / what / fix' "$S/calls.log"

reset
issue_held
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
run_carnet close 42 --why $'fixed at the root\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
assert_eq "a signed close reason is accepted" "$rc" 0
assert_no_grep "the close comment drops the footer" 'Generated with' "$S/calls.log"
assert_no_grep "and the Co-Authored-By trailer" 'Co-Authored-By' "$S/calls.log"
assert_grep "the reason is kept" 'fixed at the root' "$S/calls.log"

# ================================================================== project prefix
# Every form origin takes files under the same "[atmosphere]" prefix and label, and a worktree
# — named after its agent, never after the project — files exactly like the main checkout.
section "project prefix"
for url in https://github.com/Atmosphere/atmosphere.git git@github.com:Atmosphere/atmosphere.git \
           ssh://git@github.com/Atmosphere/atmosphere.git https://github.com/Atmosphere/atmosphere/; do
    git remote set-url origin "$url"
    reset
    run_carnet create --title "Prefix check" --body b --dry-run
    assert_grep "origin $url: prefix [atmosphere]" '^   \[atmosphere\] Prefix check$' "$tmp/out"
done
git remote set-url origin "$ORIGIN"

git -c user.name=t -c user.email=t@t.t -C "$tmp/repo" worktree add -q -b agent-a1b2c3 "$tmp/worktrees/agent-a1b2c3"
cp registre.toml "$tmp/worktrees/agent-a1b2c3/registre.toml"
reset
rc=0
( cd "$tmp/worktrees/agent-a1b2c3" && bash "$carnet" create --title "From a worktree" --body b ) > "$tmp/out" 2> "$tmp/err" || rc=$?
assert_eq "create from an agent worktree exits 0" "$rc" 0
assert_grep "an agent worktree still files as [atmosphere]" '^TITLE \[atmosphere\] From a worktree' "$S/calls.log"
assert_grep "under the atmosphere label" '^LABELS atmosphere$' "$S/calls.log"
git -C "$tmp/repo" worktree remove --force "$tmp/worktrees/agent-a1b2c3"

# ================================================================== label
section "label"
reset
run_carnet label 42 +critical -bug
assert_eq "label exits 0" "$rc" 0
assert_grep "adds a label" 'issues/42/labels -X POST -f labels\[\]=critical' "$S/calls.log"
assert_grep "removes a label" 'issues/42/labels/bug -X DELETE' "$S/calls.log"
assert_no_grep "an unrelated label leaves no limitation line" '"kind":"limitation"' "$ledger"

reset
run_carnet label 42 +limitation
assert_grep "labelling limitation records it in the ledger" '"kind":"limitation","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"
run_carnet label 42 +limitation
assert_eq "relabelling does not duplicate the line" "$(grep -c '"kind":"limitation"' "$ledger")" 1
run_carnet label 42 -limitation
assert_no_grep "removing the label drops the line" '"kind":"limitation"' "$ledger"

# ================================================================== status
section "status"
reset
run_carnet status 42 --short
assert_grep "unclaimed" '^carnet#42 · open · unclaimed · \[atmosphere\] Thing' "$tmp/out"
run_carnet status 42
assert_grep "the long form says how to claim it, from this repo's path" 'claim before the first edit: \.agents/skills/carnet/carnet\.sh claim 42' "$tmp/out"
issue_held
run_carnet status 42 --short
assert_grep "a label without a marker is called stale" 'stale in-progress label' "$tmp/out"
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
run_carnet status 42 --short
assert_grep "my own claim" 'held by THIS session \(TestSession\)' "$tmp/out"
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet status 42 --short
assert_grep "a live peer" "held by @peer · session PeerSession \(22222222\) on $HOST \[running\] · main" "$tmp/out"
comments < <(claim_marker "$DEAD" GoneSession peer "$HOST" 999999)
run_carnet status 42 --short
assert_grep "an ended peer" '\[session ended' "$tmp/out"
comments < <(claim_marker "$PEER" R phil elsewhere 1)
run_carnet status 42 --short
assert_grep "another host" '\[other host\]' "$tmp/out"
issue_closed
run_carnet status 42 --short
assert_grep "closed" '^carnet#42 · closed' "$tmp/out"

reset
printf '42\n' > "$S/list.txt"
run_carnet status
assert_grep "status with no number lists in-progress issues" '^carnet#42' "$tmp/out"
: > "$S/list.txt"
run_carnet status
assert_grep "and says when nothing is" 'no issue in Atmosphere/atmosphere-carnet is in progress' "$tmp/out"

# ================================================================== mine
section "mine"
reset
run_carnet mine
assert_grep "empty ledger" 'holds nothing' "$tmp/out"
run_carnet claim 42 >/dev/null 2>&1
run_carnet mine
assert_grep "lists the held issue without an API call" 'carnet#42 · since' "$tmp/out"

# A RESUMED session gets a new id and a new, empty ledger. The previous
# incarnation's claims stay in its own file, so `mine` used to answer "holds
# nothing" — a false all-clear, and exactly when someone is auditing.
reset
prior="$tmp/cfg/carnet-claims/$DEAD.jsonl"
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"EarlierMe","user":"tester","host":"%s","pid":1,"repo":"r","branch":"main","at":"2026-01-01T00:00:00Z"}\n' "$DEAD" "$HOST" > "$prior"
printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":91,"at":"2026-01-01T00:00:00Z"}\n' >> "$prior"
run_carnet mine
assert_grep "an empty ledger still says so" 'holds nothing' "$tmp/out"
assert_grep "but a prior session id's claims are surfaced" 'other session ids on this machine still list claims' "$tmp/out"
assert_grep "named, with the issue" 'EarlierMe \(33333333\): 91' "$tmp/out"
assert_grep "and not asserted as mine" 'NOT necessarily yours' "$tmp/out"
assert_eq "surfacing them costs no API call" "$(count_calls 'api repos/[^ ]*/issues/42$')" 0

# A ledger belonging to someone else, or another machine, is not a candidate for
# "an earlier me" — adopting a peer's claim would be worse than the false
# all-clear this fixes.
reset
foreign="$tmp/cfg/carnet-claims/$PEER.jsonl"
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"SomeoneElse","user":"other","host":"%s","pid":1,"repo":"r","branch":"main","at":"2026-01-01T00:00:00Z"}\n' "$PEER" "$HOST" > "$foreign"
printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":92,"at":"2026-01-01T00:00:00Z"}\n' >> "$foreign"
run_carnet mine
assert_no_grep "another user's ledger is not surfaced" 'SomeoneElse' "$tmp/out"

reset
elsewhere="$tmp/cfg/carnet-claims/$PEER.jsonl"
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"OtherBox","user":"tester","host":"not-this-host","pid":1,"repo":"r","branch":"main","at":"2026-01-01T00:00:00Z"}\n' "$PEER" > "$elsewhere"
printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":93,"at":"2026-01-01T00:00:00Z"}\n' >> "$elsewhere"
run_carnet mine
assert_no_grep "another machine's ledger is not surfaced" 'OtherBox' "$tmp/out"

# One config dir serves every checkout on the machine. A claim this user holds in ANOTHER
# register is that register's business: it is neither this session's nor an earlier one's.
reset
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"OtherRegister","user":"tester","host":"%s","pid":1,"repo":"r","branch":"main","at":"2026-01-01T00:00:00Z"}\n' "$DEAD" "$HOST" > "$prior"
printf '{"kind":"claim","tracker":"some-org/some-carnet","issue":94,"at":"2026-01-01T00:00:00Z"}\n' >> "$prior"
run_carnet mine
assert_no_grep "a claim in another register is not surfaced" 'OtherRegister' "$tmp/out"

# ================================================================== hooks
section "hooks"
reset
pending_dir_h="$tmp/cfg/carnet-claims/pending"
hook_out=$(printf '{"prompt":"look at carnet#42, then registre#42 and https://github.com/Atmosphere/atmosphere-carnet/issues/7","session_id":"%s"}' "$ME" \
    | bash "$here/hooks/prompt-status.sh")
printf '%s\n' "$hook_out" > "$tmp/hook"
assert_eq "prompt hook: one line per distinct issue" "$(grep -c '^carnet#' "$tmp/hook")" 2
assert_grep "prompt hook: resolves carnet#42" '^carnet#42 · open · unclaimed' "$tmp/hook"
assert_grep "prompt hook: resolves the URL form" '^carnet#7 ' "$tmp/hook"
views_before=$(count_calls 'api repos/[^ ]*/issues/42$')
printf '{"prompt":"carnet#42 again"}' | bash "$here/hooks/prompt-status.sh" > "$tmp/hook2"
assert_eq "prompt hook: a repeat within a minute is served from cache" "$(count_calls 'api repos/[^ ]*/issues/42$')" "$views_before"
assert_grep "prompt hook: cached line still printed" '^carnet#42' "$tmp/hook2"
printf '{"prompt":"nothing about the register"}' | bash "$here/hooks/prompt-status.sh" > "$tmp/hook3"
assert_eq "prompt hook: silent when no issue is named" "$(wc -c < "$tmp/hook3" | tr -d ' ')" 0

# The cache is per machine; the register is per checkout. Another checkout's hook, filing
# into a different register whose numbers overlap, caches its own #42 — by number alone in
# the unkeyed layout, or under its own tracker. Neither may answer for #42 here.
reset
cache_root="$tmp/cfg/carnet-claims/cache"
mkdir -p "$cache_root/some-org%2Fsome-carnet"
printf 'carnet#42 · held by @x · session Foreign (deadbeef) on elsewhere [other host] · main · since then · [other] Foreign\n' \
    | tee "$cache_root/42" > "$cache_root/some-org%2Fsome-carnet/42"
printf '{"prompt":"carnet#42","session_id":"%s"}' "$ME" | bash "$here/hooks/prompt-status.sh" > "$tmp/hookx"
assert_no_grep "prompt hook: a line cached for another register is never served" 'Foreign' "$tmp/hookx"
assert_grep "prompt hook: the line comes from this register" '^carnet#42 · open · unclaimed · \[atmosphere\] Thing' "$tmp/hookx"
assert_grep "prompt hook: and is cached under this register" '\[atmosphere\] Thing' "$cache_root/Atmosphere%2Fatmosphere-carnet/42"

# A peer NAMING an issue is not your user ASSIGNING it. Both non-user vectors reach `.prompt`
# byte-identically to a typed prompt (verified against captured live payloads), and arming on
# them claims issues off messages that only mention them — a peer replying "not mine" included.
reset; rm -rf "$pending_dir_h"
peer_msg='<cross-session-message from=\"uds:/tmp/cc-socks/1.sock\" from-name=\"atmosphere-e8\" from-mode=\"bypass\">\nI hold carnet#42 and am moving the pins, stay off these files.\n</cross-session-message>'
printf '{"prompt":"%s","session_id":"%s"}' "$peer_msg" "$ME" | bash "$here/hooks/prompt-status.sh" > "$tmp/hookp"
assert_grep "peer message: still prints the status line" '^carnet#42' "$tmp/hookp"
assert_grep "peer message: says a mention is not an assignment" 'NOT by your user' "$tmp/hookp"
assert_grep "peer message: tells an unrelated session to just answer" 'unrelated, one line saying so' "$tmp/hookp"
[ -f "$pending_dir_h/$ME.txt" ] && bad "peer message: armed the pending list" || ok "peer message: arms nothing"

# ... and it must not redirect a claim the user's own prompt already armed.
reset; mkdir -p "$pending_dir_h"; printf '7\n' > "$pending_dir_h/$ME.txt"
printf '{"prompt":"%s","session_id":"%s"}' "$peer_msg" "$ME" | bash "$here/hooks/prompt-status.sh" >/dev/null
assert_grep "peer message: leaves the user's pending list untouched" '^7$' "$pending_dir_h/$ME.txt"
assert_no_grep "peer message: does not add its own issue to it" '^42$' "$pending_dir_h/$ME.txt"

# A background task result is machine text too.
reset; rm -rf "$pending_dir_h"
task_msg='<task-notification>\n<summary>filed carnet#42</summary>\n</task-notification>'
printf '{"prompt":"%s","session_id":"%s"}' "$task_msg" "$ME" \
    | bash "$here/hooks/prompt-status.sh" > "$tmp/hookt"
assert_grep "task notification: still prints the status line" '^carnet#42' "$tmp/hookt"
[ -f "$pending_dir_h/$ME.txt" ] && bad "task notification: armed the pending list" || ok "task notification: arms nothing"

# The user is still the user. A typed prompt arms, and gets no peer note.
reset; rm -rf "$pending_dir_h"
printf '{"prompt":"go fix carnet#42","session_id":"%s"}' "$ME" | bash "$here/hooks/prompt-status.sh" > "$tmp/hooku"
assert_grep "typed prompt: still arms the pending list" '^42$' "$pending_dir_h/$ME.txt"
assert_no_grep "typed prompt: gets no peer note" 'NOT by your user' "$tmp/hooku"

# A user quoting a peer message inside their own prompt is still the user.
reset; rm -rf "$pending_dir_h"
printf '{"prompt":"e8 said <cross-session-message>I hold carnet#42</cross-session-message> — take it over","session_id":"%s"}' "$ME" \
    | bash "$here/hooks/prompt-status.sh" >/dev/null
assert_grep "a quoted envelope mid-prompt is still the user" '^42$' "$pending_dir_h/$ME.txt"

# The session is a machine author too. A /loop or ScheduleWakeup re-fire is a prompt the model
# wrote for itself, and it names whatever the model was thinking about — an issue number in it
# was never typed by the human. The payload's `source` field says so once Claude Code emits it.
reset; rm -rf "$pending_dir_h"
printf '{"prompt":"Continue the plan; comment the eight strings on carnet#42","session_id":"%s","source":"loop_wakeup"}' "$ME" \
    | bash "$here/hooks/prompt-status.sh" > "$tmp/hookw"
assert_grep "wakeup (source field): still prints the status line" '^carnet#42' "$tmp/hookw"
assert_grep "wakeup (source field): says a wakeup is not an assignment" 'scheduled wakeup -- NOT by your user' "$tmp/hookw"
[ -f "$pending_dir_h/$ME.txt" ] && bad "wakeup (source field): armed the pending list" || ok "wakeup (source field): arms nothing"
reset; rm -rf "$pending_dir_h"
printf '{"prompt":"go fix carnet#42","session_id":"%s","source":"user"}' "$ME" | bash "$here/hooks/prompt-status.sh" >/dev/null
assert_grep "source=user: arms" '^42$' "$pending_dir_h/$ME.txt"

# Until `source` arrives the prompt hook cannot tell, so it records WHICH prompt armed the list
# and auto-claim.sh asks the transcript, where the entry exists by then.
reset; rm -rf "$pending_dir_h"
printf '{"prompt":"go fix carnet#42","session_id":"%s","prompt_id":"aaaaaaaa-1111-2222-3333-444444444444"}' "$ME" \
    | bash "$here/hooks/prompt-status.sh" >/dev/null
assert_grep "typed prompt: pending list records the prompt id" '^prompt=aaaaaaaa-1111-2222-3333-444444444444$' "$pending_dir_h/$ME.txt"
assert_grep "typed prompt: and the issue" '^42$' "$pending_dir_h/$ME.txt"

reset
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"Ender","user":"ender","host":"%s","pid":1}\n{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42,"at":"2026-09-02T10:00:00Z"}\n' "$PEER" "$HOST" > "$tmp/cfg/carnet-claims/$PEER.jsonl"
comments < <(claim_marker "$PEER" Ender ender "$HOST" 1)
printf '{"session_id":"%s","reason":"exit"}' "$PEER" | bash "$here/hooks/session-end-release.sh" > "$tmp/hook4"
assert_grep "session-end hook: reports the release" '^🔓 carnet#42 released \(session-ended\)' "$tmp/hook4"
assert_grep "session-end hook: releases with the ledger's identity" 'issues/42/assignees -X DELETE -f assignees\[\]=ender' "$S/calls.log"
assert_grep "session-end hook: marker names the ended session" "^BODY <!-- carnet-release \{.*\"session\":\"$PEER\".*\"reason\":\"session-ended\"" "$S/calls.log"
[ -f "$tmp/cfg/carnet-claims/$PEER.jsonl" ] && bad "session-end hook: ledger removed" || ok "session-end hook: ledger removed"
: > "$S/calls.log"
printf '{"session_id":"%s"}' "$DEAD" | bash "$here/hooks/session-end-release.sh"
assert_eq "session-end hook: no ledger, no call" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# ================================================================== auto-claim
section "auto-claim (PreToolUse)"

auto_claim() { # <payload> ; sets rc, $tmp/ac.out, $tmp/ac.err
    rc=0
    printf '%s' "$1" | bash "$here/hooks/auto-claim.sh" > "$tmp/ac.out" 2> "$tmp/ac.err" || rc=$?
}
pending_dir="$tmp/cfg/carnet-claims/pending"
set_pending() { mkdir -p "$pending_dir"; printf '%s\n' "$@" > "$pending_dir/$ME.txt"; }
edit_payload() { printf '{"tool_name":"Edit","session_id":"%s","tool_input":{"file_path":"/x"}}' "$ME"; }
bash_payload() { printf '{"tool_name":"Bash","session_id":"%s","tool_input":{"command":"%s"}}' "$ME" "$1"; }

# Nothing pending is the common case and must cost nothing at all.
reset; rm -rf "$pending_dir"
auto_claim "$(edit_payload)"
assert_eq "no pending list: allows the tool" "$rc" 0
assert_eq "no pending list: makes no call" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# The prompt hook is what fills the list.
reset; rm -rf "$pending_dir"
printf '{"prompt":"work carnet#42 please","session_id":"%s"}' "$ME" | bash "$here/hooks/prompt-status.sh" >/dev/null
assert_grep "prompt hook records the issue as pending" '^42$' "$pending_dir/$ME.txt"
printf '{"prompt":"nothing about the register","session_id":"%s"}' "$ME" | bash "$here/hooks/prompt-status.sh" >/dev/null
assert_grep "a prompt naming none leaves the list alone" '^42$' "$pending_dir/$ME.txt"

# An edit claims it, without anyone asking.
reset; set_pending 42
auto_claim "$(edit_payload)"
assert_eq "an edit claims the pending issue" "$rc" 0
assert_grep "and says so" '^🔒 carnet auto-claimed: 42' "$tmp/ac.out"
assert_grep "the claim reached the tracker" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
assert_grep "the marker names this session" "^BODY <!-- carnet-claim \{.*\"session\":\"$ME\"" "$S/calls.log"

# Consumed once: a second edit is free.
: > "$S/calls.log"
auto_claim "$(edit_payload)"
assert_eq "the list is consumed, so a later edit makes no call" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# Reading is not working.
reset; set_pending 42
auto_claim "$(printf '{"tool_name":"Read","session_id":"%s"}' "$ME")"
assert_eq "a read tool claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'grep -rn TODO src/')"
assert_eq "a read-shaped Bash claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'git status --short')"
assert_eq "git status claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# Suppressing stderr is the commonest idiom in a read, and `2>/dev/null` contains a `>`.
# Classifying it as a write would make every quiet read claim everything pending, off a
# message that only NAMED the issue.
auto_claim "$(bash_payload 'git log --oneline -1 abc123 2>/dev/null')"
assert_eq "a read that suppresses stderr claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'gh run list --json status -q ".[]" 2>/dev/null')"
assert_eq "gh with 2>/dev/null claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'ls -la >/dev/null')"
assert_eq "stdout to /dev/null claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'make check &>/dev/null')"
assert_eq "&>/dev/null claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# A git verb needs a terminator, or `merge` matches inside `git merge-base` — the standard
# "is this commit on main?" query — and a pure read claims, possibly an issue a peer is in the
# middle of investigating.
auto_claim "$(bash_payload 'git merge-base --is-ancestor abc123 origin/main')"
assert_eq "git merge-base claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'git merge-base --fork-point main')"
assert_eq "git merge-base --fork-point claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'git merge-base --is-ancestor abc origin/main 2>/dev/null && echo yes')"
assert_eq "an ancestry check with 2>/dev/null and a chained echo claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# Reporting-only flags write nothing, whatever the verb.
auto_claim "$(bash_payload 'git add --dry-run .')"
assert_eq "git add --dry-run claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
auto_claim "$(bash_payload 'git apply --check my.patch')"
assert_eq "git apply --check claims nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# ...but a redirect into a real file is still an edit, /dev/null nearby or not.
reset; set_pending 42
auto_claim "$(bash_payload 'grep -rn TODO src/ 2>/dev/null > findings.txt')"
assert_grep "a real redirect still claims, even beside 2>/dev/null" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

# A write-shaped Bash command is an edit — a session that edits through bash must claim too.
reset; set_pending 42
auto_claim "$(bash_payload "sed -i '' s/a/b/ f.txt")"
assert_grep "sed -i claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload 'cat > note.txt <<EOT')"
assert_grep "a redirect into a file claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload 'git commit -m wip')"
assert_grep "git commit claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload 'git merge origin/main')"
assert_grep "a real git merge still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload 'git add -A')"
assert_grep "git add still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload 'git apply my.patch')"
assert_grep "git apply without --check still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload 'git reset --hard origin/main')"
assert_grep "git reset still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

# A live peer holding it blocks the edit once, and names them.
reset; set_pending 42
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
auto_claim "$(edit_payload)"
assert_eq "a live peer's claim blocks the edit" "$rc" 2
assert_grep "the block names the holder" 'PeerSession' "$tmp/ac.err"
assert_grep "the block says not to duplicate" 'Do not do this work twice' "$tmp/ac.err"
assert_no_grep "and steals nothing" 'labels\[\]=in-progress' "$S/calls.log"
set_pending 42
auto_claim "$(edit_payload)"
assert_eq "having said it once, it stops blocking" "$rc" 0

# A list nobody acted on goes stale rather than claiming much later.
reset; set_pending 42
touch -t 202001010000 "$pending_dir/$ME.txt"
auto_claim "$(edit_payload)"
assert_eq "an hour-old list is dropped, not claimed" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
[ -f "$pending_dir/$ME.txt" ] && bad "stale list removed" || ok "stale list removed"

# Who wrote the prompt that armed the list. The transcript entry with that promptId says:
# `promptSource` "typed"/"queued" for the composer, "system" for a peer message, a task
# notification or a scheduled wakeup; the wakeup also carries `isMeta` and `scheduledTaskId`.
# Shapes copied from live transcripts (2.1.276). A garbage line must not break the lookup.
T1=aaaaaaaa-0000-0000-0000-00000000typed; W1=bbbbbbbb-0000-0000-0000-0000000wakeup; N1=cccccccc-0000-0000-0000-000000notify
cat > "$tmp/transcript.jsonl" <<EOT
this line is not json
{"type":"user","promptId":"$T1","promptSource":"typed","message":{"role":"user","content":"go fix carnet#42"}}
{"type":"user","promptId":"$T1","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"ok"}]}}
{"type":"system","subtype":"scheduled_task_fire","content":"Claude resuming /loop wakeup"}
{"type":"user","promptId":"$W1","isMeta":true,"promptSource":"system","scheduledTaskId":"191a64c7","message":{"role":"user","content":"Continue the plan; comment on carnet#42"}}
{"type":"user","promptId":"$N1","promptSource":"system","message":{"role":"user","content":"<task-notification>filed carnet#42</task-notification>"}}
EOT
edit_payload_t() { printf '{"tool_name":"Edit","session_id":"%s","transcript_path":"%s","tool_input":{"file_path":"/x"}}' "$ME" "$tmp/transcript.jsonl"; }

reset; set_pending "prompt=$W1" 42
auto_claim "$(edit_payload_t)"
assert_eq "a wakeup-armed list: allows the tool" "$rc" 0
assert_no_grep "a wakeup-armed list: claims nothing" 'labels\[\]=in-progress' "$S/calls.log"
assert_grep "a wakeup-armed list: says so, naming the issue" '^carnet: NOT claimed — carnet#42 came from a scheduled wakeup' "$tmp/ac.out"
assert_grep "a wakeup-armed list: says how to take it deliberately" 'carnet.sh claim <n>' "$tmp/ac.out"
[ -f "$pending_dir/$ME.txt" ] && bad "a wakeup-armed list is consumed" || ok "a wakeup-armed list is consumed"

reset; set_pending "prompt=$N1" 42
auto_claim "$(edit_payload_t)"
assert_no_grep "a task-notification-armed list: claims nothing" 'labels\[\]=in-progress' "$S/calls.log"
assert_grep "a task-notification-armed list: names the machine origin" 'came from a machine-injected prompt' "$tmp/ac.out"

reset; set_pending "prompt=$T1" 42
auto_claim "$(edit_payload_t)"
assert_grep "a typed-prompt list still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

# Fail open, not closed: a check that cannot see the entry must not silently stop every
# claim. Unknown id, or no transcript in the payload, claims.
reset; set_pending "prompt=dddddddd-0000-0000-0000-00000unknown" 42
auto_claim "$(edit_payload_t)"
assert_grep "an id the transcript lacks claims as before" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending "prompt=$W1" 42
auto_claim "$(edit_payload)"
assert_grep "no transcript_path in the payload claims as before" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(edit_payload_t)"
assert_grep "a list without a prompt line (older hook) claims as before" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

# ================================================================== pasted terminal output
# ---- a number the user only QUOTED must not arm --------------------------------
# The user pastes a peer session's terminal output constantly, and every issue it mentions
# would otherwise be claimed for the reader.
#
# Splitting the prompt at the first transcript marker and arming on the prose before it is
# not enough: a paste whose agent prose leads with no marker puts the number on the prose side
# of the split. The test is the whole prompt, and it fails safe — missing a claim costs one
# `claim <n>`; taking a live peer's issue costs them a blocked tool call and a stolen
# assignment. The shapes below are driven through the real prompt hook, not a copy of its
# logic, so a change to the hook is what they judge.
arms() { # <prompt> -> the numbers the prompt hook arms, space-separated
    local p="$tmp/cfg/carnet-claims/pending/$ME.txt"
    rm -f "$p"
    jq -cn --arg p "$1" --arg s "$ME" '{prompt:$p, session_id:$s}' \
        | bash "$here/hooks/prompt-status.sh" >/dev/null 2>&1 || true
    [ -f "$p" ] || return 0
    grep -E '^[0-9]+$' "$p" | tr '\n' ' ' | sed 's/ $//'
}
eq() { # <expected> <actual> <label>
    if [ "$1" = "$2" ]; then ok "$3"; else bad "$3 — expected '$1', got '$2'"; fi
}
section "Pasted terminal output arms nothing"
reset
eq "" "$(arms 'Another example ⏺ the audit flagged carnet#394 as residue')" \
    "a paste that starts with a turn marker arms nothing"
eq "" "$(arms 'look at this ⎿ Stop hook: still holding carnet#343')" \
    "a tool-result marker arms nothing"
eq "" "$(arms 'Agent say 5 — gated by its plan line on registre#103. ✻ Crunched for 44s')" \
    "agent prose leading, a marker only later, still arms nothing"
eq "" "$(arms 'carnet#400 here
────────────────────────
❯ ')" \
    "a shell prompt or rule anywhere in the paste arms nothing"
eq "394" "$(arms 'fix carnet#394 please')" \
    "the user asking in their own words still arms"
eq "343 400" "$(arms 'take carnet#343 and also look at registre 400')" \
    "and every number in their own words arms"

# ================================================== skill discovery + hook wiring
# The scripts above are only half the mechanism. Claude Code finds the skill through
# .claude/skills/carnet and runs the hooks from .claude/settings.json; a dangling link or a
# missing hook entry silently turns the whole thing off, and a PreToolUse wiring that
# swallows the script's exit status turns the block back into the advisory rule the hook
# exists to replace. Assert both from the real files.
section "skill discovery (.claude/skills/carnet)"
root=$(cd "$here/../../.." && pwd)
link="$root/.claude/skills/carnet"
if [ -L "$link" ]; then
    case "$(readlink "$link")" in
        /*) bad "the skill link is relative, so it resolves in every clone and worktree — got $(readlink "$link")" ;;
        *)  ok "the skill link is relative, so it resolves in every clone and worktree" ;;
    esac
    if [ -d "$link" ] && [ "$(cd "$link" && pwd -P)" = "$(cd "$here" && pwd -P)" ]; then
        ok "the skill link resolves to this directory"
    else
        bad "the skill link resolves to this directory"
    fi
else
    bad ".claude/skills/carnet is a symlink to .agents/skills/carnet"
fi

section "hook wiring (.claude/settings.json)"
settings="$root/.claude/settings.json"
wired() { # <event> <script> -> the first command of that event that runs the script
    jq -r --arg e "$1" --arg s "$2" \
        '[.hooks[$e][]?.hooks[]?.command // empty | select(contains($s))] | first // empty' \
        "$settings" 2>/dev/null || true
}
for pair in UserPromptSubmit:prompt-status.sh SessionEnd:session-end-release.sh; do
    if [ -n "$(wired "${pair%%:*}" "${pair#*:}")" ]; then ok "${pair%%:*} runs ${pair#*:}"
    else bad "${pair%%:*} runs ${pair#*:} — wire the entry at the top of hooks/${pair#*:}"; fi
done

wiring=$(wired PreToolUse auto-claim.sh)
if [ -z "$wiring" ]; then
    bad "PreToolUse runs auto-claim.sh — wire the entry at the top of hooks/auto-claim.sh"
else
    ok "PreToolUse runs auto-claim.sh"
    # A hook that decides to block exits 2. The wiring must deliver that verbatim, whether it
    # names the script relative to the project root or through $CLAUDE_PROJECT_DIR.
    wire=$tmp/wire
    mkdir -p "$wire/.agents/skills/carnet/hooks"
    printf '#!/usr/bin/env bash\nexit 2\n' > "$wire/.agents/skills/carnet/hooks/auto-claim.sh"
    chmod +x "$wire/.agents/skills/carnet/hooks/auto-claim.sh"
    rc=0; ( cd "$wire" && CLAUDE_PROJECT_DIR="$wire" sh -c "$wiring" ) >/dev/null 2>&1 || rc=$?
    assert_eq "the wiring delivers a block (exit 2) to Claude Code" "$rc" 2

    # Every other outcome must leave the tool alone, including no script at all.
    printf '#!/usr/bin/env bash\nexit 0\n' > "$wire/.agents/skills/carnet/hooks/auto-claim.sh"
    rc=0; ( cd "$wire" && CLAUDE_PROJECT_DIR="$wire" sh -c "$wiring" ) >/dev/null 2>&1 || rc=$?
    assert_eq "the wiring passes a clean run through" "$rc" 0

    rc=0; ( cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" sh -c "$wiring" ) >/dev/null 2>&1 || rc=$?
    assert_eq "a missing hook script leaves the tool alone" "$rc" 0
fi

# ================================================================== summary
printf '\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" = 0 ]

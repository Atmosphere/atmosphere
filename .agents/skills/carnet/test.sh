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
#
# A call whose arguments match $FAIL_RE fails the way gh does: what a --paginate run printed
# before its failing page ($FAIL_OUT) on stdout, the error on stderr ($FAIL_MSG, a 502 unless
# set), exit 1. The call is still logged, followed by FAILED. A case that needs two issues at
# once gives one of them its own issue-<n>.json and comments-<n>.txt.
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
if [ -n "${FAIL_RE:-}" ] && printf '%s' "$*" | grep -qE -- "$FAIL_RE"; then
    printf 'FAILED\n' >> "$S/calls.log"
    [ -z "${FAIL_OUT:-}" ] || printf '%s\n' "$FAIL_OUT"
    printf 'gh: %s\n' "${FAIL_MSG:-Server Error (HTTP 502)}" >&2
    exit 1
fi
n=$(printf '%s' "${2:-}" | sed -nE 's#^repos/[^/]+/[^/]+/issues/([0-9]+).*#\1#p')
# Order matters: /issues/N/comments and /issues?query both also match the bare-read pattern.
case "$1 ${2:-}" in
    "api user")                 printf 'tester\n' ;;
    "api repos/"*"/comments")   f="$S/comments-$n.txt"; [ -f "$f" ] || f="$S/comments.txt"; cat "$f" 2>/dev/null || true ;;
    "api repos/"*"/issues?"*)   cat "$S/list.txt" 2>/dev/null || true ;;
    "api repos/"*"/issues")     printf 'https://github.com/Atmosphere/atmosphere-carnet/issues/321\n' ;;
    "api repos/"*"/issues/"*)   f="$S/issue-$n.json"; [ -f "$f" ] || f="$S/issue.json"; cat "$f" ;;
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
# Claude Code exports the directory the session started in to every hook, and the hooks act on
# that checkout, wherever the session has cd'd since.
export CLAUDE_PROJECT_DIR="$tmp/repo"
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
reset() {
    : > "$S/calls.log"; : > "$S/comments.txt"; rm -rf "$tmp/cfg/carnet-claims"
    rm -f "$S/private.txt" "$S/list.txt" "$S"/issue-*.json "$S"/comments-*.txt; issue_open
}
seed_ledger() { # <issue>... — this session's ledger: its identity, then a claim on each issue here
    local n
    mkdir -p "$tmp/cfg/carnet-claims"
    printf '{"kind":"identity","v":1,"session":"%s","name":"TestSession","user":"tester","host":"%s","pid":%s,"at":"2026-09-02T09:00:00Z"}\n' "$ME" "$HOST" "$$" > "$ledger"
    for n in "$@"; do
        printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":%s,"at":"2026-09-02T10:00:00Z"}\n' "$n" >> "$ledger"
    done
}
# Any claim this user made on this machine left the login behind; `reset` wipes it, which no
# real machine does.
seed_login() { mkdir -p "$tmp/cfg/carnet-claims"; printf 'tester' > "$tmp/cfg/carnet-claims/gh-login"; }

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

# Only a 404 says an issue does not exist; a 5xx says nothing about it, and the auto-claim hook
# retries the one and not the other.
reset
FAIL_RE='issues/42$' run_carnet claim 42
assert_eq "an issue that cannot be read is an error" "$rc" 1
assert_grep "named as unreadable, with gh's reason" 'cannot read carnet#42 from Atmosphere/atmosphere-carnet: gh: Server Error \(HTTP 502\)' "$tmp/err"
assert_no_grep "not as missing" 'does not exist' "$tmp/err"
FAIL_RE='issues/42$' FAIL_MSG='Not Found (HTTP 404)' run_carnet claim 42
assert_grep "a 404 is a missing issue" 'carnet#42 does not exist in Atmosphere/atmosphere-carnet' "$tmp/err"

reset
run_carnet claim 42 --dry-run
assert_eq "dry-run exits 0" "$rc" 0
assert_eq "dry-run writes nothing" "$(count_calls "$WRITES")" 0
assert_grep "dry-run prints the edit" '\[dry-run\] api repos/Atmosphere/atmosphere-carnet/issues/42' "$tmp/err"
[ -f "$ledger" ] && bad "dry-run must not touch the ledger" || ok "dry-run leaves no ledger"

# The marker stream is the only thing that says who holds an issue. Read blind — a 502, or a
# page failing after the earlier ones printed — it must stop the claim, never read as "free".
reset
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
FAIL_RE='/comments --paginate' run_carnet claim 42
assert_eq "an unreadable comment stream stops the claim (exit 1)" "$rc" 1
assert_grep "and says why" 'cannot read the comments of carnet#42' "$tmp/err"
assert_eq "with no tracker write" "$(count_calls "$WRITES")" 0
reset
FAIL_RE='/comments --paginate' FAIL_OUT="$(release_marker "$DEAD")" run_carnet claim 42
assert_eq "a stream failing after its first page stops the claim too" "$rc" 1
assert_eq "even though that page held an older release marker" "$(count_calls "$WRITES")" 0

# A claim that dies after its first write has assigned and labelled. Its ledger line, written
# before that write, is what lets release and the SessionEnd hook take back what landed.
reset
mkdir -p "$tmp/tmpdir"
TMPDIR="$tmp/tmpdir" FAIL_RE='issues/42/comments -X POST' run_carnet claim 42
assert_eq "a claim whose marker POST fails exits 1" "$rc" 1
assert_grep "after assigning and labelling" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
assert_grep "the ledger already names the half-made claim" '"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"
assert_eq "and the failed run leaves no temp file behind" "$(find "$tmp/tmpdir" -mindepth 1 | wc -l | tr -d ' ')" 0
issue_held
: > "$S/calls.log"
run_carnet release 42
assert_eq "release takes a half-made claim back" "$rc" 0
assert_grep "its label" 'issues/42/labels/in-progress -X DELETE' "$S/calls.log"
assert_grep "its assignee" 'issues/42/assignees -X DELETE -f assignees\[\]=tester' "$S/calls.log"
[ -f "$ledger" ] && bad "and the ledger is done with it" || ok "and the ledger is done with it"
reset
FAIL_RE='issues/42/comments -X POST' run_carnet claim 42
issue_held
: > "$S/calls.log"
run_carnet release --all --session "$ME" --reason session-ended
assert_eq "so does the SessionEnd path" "$rc" 0
assert_grep "label off" 'issues/42/labels/in-progress -X DELETE' "$S/calls.log"
[ -f "$ledger" ] && bad "SessionEnd path: ledger removed" || ok "SessionEnd path: ledger removed"
reset
FAIL_RE='issues/42/comments -X POST' run_carnet claim 42
run_carnet claim 42
assert_eq "retrying a half-made claim completes it" "$rc" 0
assert_eq "without a second ledger line" "$(grep -c '"kind":"claim"' "$ledger")" 1

# The ledger, the login and the status cache belong to this account: the tracker is private.
# A directory an older copy left world-readable is tightened too.
reset
( umask 022; mkdir -p "$tmp/cfg/carnet-claims"; chmod 755 "$tmp/cfg/carnet-claims" )
rc=0; ( umask 022; bash "$carnet" claim 42 ) > /dev/null 2>&1 || rc=$?
assert_eq "a claim under umask 022 exits 0" "$rc" 0
assert_eq "the ledger directory ends up private, even one created open" "$(ls -ld "$tmp/cfg/carnet-claims" | cut -c1-10)" "drwx------"
assert_eq "the ledger is readable by this account only" "$(ls -l "$ledger" | cut -c1-10)" "-rw-------"

# ================================================================== manual claims
# Outside Claude Code every caller records the session "manual". Such a claim is its human's:
# another person's shell — or a non-Claude agent under another login — must not read it as its
# own, and so must not release it, close it, or skip claiming it.
section "manual claims"
manual() { rc=0; env -u CLAUDE_CODE_SESSION_ID -u CLAUDE_PID bash "$carnet" "$@" > "$tmp/out" 2> "$tmp/err" || rc=$?; }
reset
issue_held
comments < <(claim_marker manual shell alice alice-mbp 4242)
manual claim 42
assert_eq "another user's manual claim is not this manual caller's" "$rc" 2
assert_grep "it names the holder" 'held by @alice' "$tmp/err"
manual release 42
assert_eq "so it cannot release it" "$rc" 2
manual close 42 --why dup
assert_eq "nor close it" "$rc" 2
assert_eq "and none of that wrote to the tracker" "$(count_calls "$WRITES")" 0
manual status 42 --short
assert_no_grep "status does not call it this caller's" 'THIS session' "$tmp/out"
comments < <(claim_marker manual shell tester "$HOST" 4242)
manual claim 42
assert_eq "the same user's manual claim is still theirs" "$rc" 0
assert_grep "already held, from any of their shells" 'already held by this session' "$tmp/out"
manual release 42
assert_eq "and they can release it" "$rc" 0

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

# Exit 2 means a holder that may still be working. One whose session ENDED is not that: a plain
# claim takes a stale claim over, so the answer is 1, and --steal (the user's call) never comes up.
reset
comments < <(claim_marker "$DEAD" GoneSession peer "$HOST" 999999)
run_carnet release 42
assert_eq "releasing an ended session's claim is an error, not a live-peer refusal" "$rc" 1
assert_grep "it says a plain claim takes it over" "'claim 42' takes the stale claim over" "$tmp/err"
assert_no_grep "and never prescribes --steal for it" '--steal' "$tmp/err"

# Displaced: this session claimed #42, then another took it (a --steal, or a race this one lost).
# Its ledger must not go on saying it holds #42 — bilan would cap it, SessionEnd would fail on it.
reset
seed_ledger 42
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet release 42
assert_eq "a displaced session can release what it no longer holds" "$rc" 0
assert_grep "and says who holds it now" 'taken over by @peer · session PeerSession' "$tmp/out"
assert_eq "posting nothing: the label, assignee and marker are the new holder's" "$(count_calls "$WRITES")" 0
[ -f "$ledger" ] && bad "and the line leaves its ledger" || ok "and the line leaves its ledger"
reset
seed_ledger 42
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet release --all --session "$ME" --reason session-ended
assert_eq "the SessionEnd path clears a displaced claim too" "$rc" 0
assert_eq "without a write" "$(count_calls "$WRITES")" 0
[ -f "$ledger" ] && bad "and removes the finished ledger" || ok "and removes the finished ledger"

# Blind is not free: an unreadable marker stream releases nothing, alone or under --all.
reset
seed_ledger 42
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
FAIL_RE='/comments --paginate' run_carnet release 42
assert_eq "an unreadable comment stream stops release (exit 1)" "$rc" 1
FAIL_RE='/comments --paginate' run_carnet release --all --session "$ME" --reason session-ended
assert_eq "and release --all" "$rc" 1
assert_eq "neither wrote to the tracker" "$(count_calls "$WRITES")" 0
assert_grep "and the ledger still holds the claim" '"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"

# The label is one of the claim's three carriers. When it does not come off, the release stops
# before its marker and its ledger drop would say it did — except for a 404, a label already gone.
reset
seed_ledger 42
issue_held
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
FAIL_RE='labels/in-progress -X DELETE' run_carnet release 42
assert_eq "a label that will not come off stops the release" "$rc" 1
assert_grep "saying so" 'could not remove the in-progress label' "$tmp/err"
assert_eq "before any release marker is posted" "$(count_calls 'api repos/[^ ]*/issues/42/comments -X POST')" 0
assert_grep "and the claim stays in the ledger" '"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"
FAIL_RE='labels/in-progress -X DELETE' run_carnet release --all --session "$ME" --reason session-ended
assert_eq "the same under release --all" "$rc" 1
assert_eq "no marker there either" "$(count_calls 'api repos/[^ ]*/issues/42/comments -X POST')" 0
FAIL_RE='labels/in-progress -X DELETE' FAIL_MSG='Label does not exist (HTTP 404)' run_carnet release 42
assert_eq "a label already gone (404) is no obstacle" "$rc" 0
[ -f "$ledger" ] && bad "and that release drops the claim" || ok "and that release drops the claim"

# release --all runs each release in a subshell. Every failure there must stop that release as
# it would stop `release <n>` alone, not be reported as released and dropped.
reset
seed_ledger 42
issue_held
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
FAIL_RE='assignees -X DELETE' run_carnet release --all --session "$ME" --reason session-ended
assert_eq "a failed unassign fails release --all" "$rc" 1
assert_eq "before its marker is posted" "$(count_calls 'api repos/[^ ]*/issues/42/comments -X POST')" 0
assert_grep "and keeps the claim for another try" '"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"

# One ledger serves every checkout a session claims from, each line naming its register. The
# SessionEnd release walks all of them, each against its own tracker.
reset
seed_ledger 42
printf '{"kind":"claim","tracker":"some-org/some-carnet","issue":7,"at":"2026-09-02T10:00:00Z"}\n' >> "$ledger"
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
run_carnet release --all --session "$ME" --reason session-ended
assert_eq "release --all over two registers exits 0" "$rc" 0
assert_grep "releases this register's claim" '^CALL api repos/Atmosphere/atmosphere-carnet/issues/42/comments -X POST' "$S/calls.log"
assert_grep "and the other register's, on its own tracker" '^CALL api repos/some-org/some-carnet/issues/7/comments -X POST' "$S/calls.log"
assert_grep "naming it by its register" 'some-org/some-carnet#7 released \(session-ended\)' "$tmp/out"
[ -f "$ledger" ] && bad "then the ledger is finished" || ok "then the ledger is finished"

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

reset
issue_held
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
FAIL_RE='labels/in-progress -X DELETE' run_carnet close 42 --why "done"
assert_eq "a label that will not come off stops the close" "$rc" 1
assert_eq "before the issue is closed" "$(count_calls 'api repos/[^ ]*/issues/42 -X PATCH')" 0
assert_eq "or its closing comment posted" "$(count_calls 'api repos/[^ ]*/issues/42/comments -X POST')" 0

reset
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
FAIL_RE='/comments --paginate' run_carnet close 42 --why x
assert_eq "an unreadable comment stream stops close (exit 1)" "$rc" 1
assert_eq "without closing anything" "$(count_calls "$WRITES")" 0

reset
comments < <(claim_marker "$DEAD" GoneSession peer "$HOST" 999999)
run_carnet close 42 --why x
assert_eq "closing over an ended session's claim is an error, not a live-peer refusal" "$rc" 1
assert_grep "it says to claim first, plainly" "'claim 42' takes the stale claim over, then close" "$tmp/err"
assert_no_grep "never with --steal" '--steal' "$tmp/err"

# A displaced session is refused the close — the issue is the new holder's — but its ledger
# stops claiming the issue either way.
reset
seed_ledger 42
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
run_carnet close 42 --why x
assert_eq "a displaced session cannot close the new holder's issue" "$rc" 2
assert_eq "and writes nothing to the tracker" "$(count_calls "$WRITES")" 0
[ -f "$ledger" ] && bad "but its stale claim leaves its ledger" || ok "but its stale claim leaves its ledger"

# Subagents inherit their session's id, so parallel closes rewrite ONE ledger. The mv shim holds
# every rename for half a second: both closes have read the ledger before either writes it, so
# without a lock the second rename is certain — not merely likely — to bring back the first's line.
reset
seed_ledger 1 2 3
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
mkdir -p "$tmp/slowmv"
printf '#!/usr/bin/env bash\nsleep 0.5\nexec %s "$@"\n' "$(command -v mv)" > "$tmp/slowmv/mv"
chmod +x "$tmp/slowmv/mv"
rc1=0; rc2=0
PATH="$tmp/slowmv:$PATH" bash "$carnet" close 1 --why x > /dev/null 2>&1 & c1=$!
PATH="$tmp/slowmv:$PATH" bash "$carnet" close 2 --why x > /dev/null 2>&1 & c2=$!
wait "$c1" || rc1=$?
wait "$c2" || rc2=$?
assert_eq "two concurrent closes under one session both succeed" "$rc1 $rc2" "0 0"
assert_eq "and the shared ledger keeps only the claim neither closed" \
    "$(jq -r 'select(.kind == "claim") | .issue' "$ledger" 2>/dev/null | tr '\n' ' ')" "3 "
[ -d "$ledger.lock" ] && bad "the ledger lock is released" || ok "the ledger lock is released"

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

reset
run_carnet label 42 +limitation
FAIL_RE='labels/limitation -X DELETE' run_carnet label 42 -limitation
assert_eq "a label that will not come off fails the command" "$rc" 1
assert_grep "and the ledger keeps the limitation it still has" '"kind":"limitation","tracker":"Atmosphere/atmosphere-carnet","issue":42' "$ledger"

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
issue_held
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
FAIL_RE='/comments --paginate' run_carnet status 42 --short
assert_eq "an unreadable comment stream fails status" "$rc" 1
assert_no_grep "rather than calling a held issue unclaimed" 'unclaimed' "$tmp/out"

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
# The offline audit: not even the login lookup, which is the call a logged-out gh fails.
assert_eq "mine asks gh nothing, not even the login" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
run_carnet claim 42 >/dev/null 2>&1
: > "$S/calls.log"
run_carnet mine
assert_grep "lists the held issue without an API call" 'carnet#42 · since' "$tmp/out"
assert_eq "and without one" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# With gh logged out and the login cache gone, the ledger still answers.
reset
seed_ledger 42
FAIL_RE='^api user' run_carnet mine
assert_eq "mine answers with gh logged out" "$rc" 0
assert_grep "from the ledger alone" 'carnet#42 · since 2026-09-02T10:00:00Z' "$tmp/out"

# Another register's claim on the same number is not this one's, nor its date.
reset
seed_ledger 42
printf '{"kind":"claim","tracker":"some-org/some-carnet","issue":42,"at":"2026-01-01T00:00:00Z"}\n' >> "$ledger"
run_carnet mine
assert_grep "mine dates this register's claim from its own line" 'carnet#42 · since 2026-09-02T10:00:00Z$' "$tmp/out"
assert_no_grep "never from another register's line on the same number" '2026-01-01' "$tmp/out"

# A RESUMED session gets a new id and a new, empty ledger. The previous
# incarnation's claims stay in its own file, so `mine` used to answer "holds
# nothing" — a false all-clear, and exactly when someone is auditing.
reset
seed_login
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
seed_login
foreign="$tmp/cfg/carnet-claims/$PEER.jsonl"
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"SomeoneElse","user":"other","host":"%s","pid":1,"repo":"r","branch":"main","at":"2026-01-01T00:00:00Z"}\n' "$PEER" "$HOST" > "$foreign"
printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":92,"at":"2026-01-01T00:00:00Z"}\n' >> "$foreign"
run_carnet mine
assert_no_grep "another user's ledger is not surfaced" 'SomeoneElse' "$tmp/out"

reset
seed_login
elsewhere="$tmp/cfg/carnet-claims/$PEER.jsonl"
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"OtherBox","user":"tester","host":"not-this-host","pid":1,"repo":"r","branch":"main","at":"2026-01-01T00:00:00Z"}\n' "$PEER" > "$elsewhere"
printf '{"kind":"claim","tracker":"Atmosphere/atmosphere-carnet","issue":93,"at":"2026-01-01T00:00:00Z"}\n' >> "$elsewhere"
run_carnet mine
assert_no_grep "another machine's ledger is not surfaced" 'OtherBox' "$tmp/out"

# One config dir serves every checkout on the machine. A claim this user holds in ANOTHER
# register is that register's business: it is neither this session's nor an earlier one's.
reset
seed_login
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

# A held issue's line depends on who reads it: the holder gets "held by THIS session", everyone
# else "held by @u · session s (<id>) … [running]". A peer's prompt caches the second form — and
# served to the holder, it would say a live session holds the holder's own issue.
reset
issue_held
comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
mkdir -p "$cache_root/Atmosphere%2Fatmosphere-carnet"
printf 'carnet#42 · held by @tester · session TestSession (%s) on %s [running] · main · since 2026-09-02T10:00:00Z · [atmosphere] Thing\n' \
    "${ME:0:8}" "$HOST" > "$cache_root/Atmosphere%2Fatmosphere-carnet/42"
printf '{"prompt":"continue carnet#42","session_id":"%s"}' "$ME" | bash "$here/hooks/prompt-status.sh" > "$tmp/hookc"
assert_grep "prompt hook: the holder is told the claim is its own" 'held by THIS session \(TestSession\)' "$tmp/hookc"
assert_no_grep "never a peer's cached view of it" '\[running\]' "$tmp/hookc"
: > "$S/calls.log"
printf '{"prompt":"is carnet#42 taken?","session_id":"%s"}' "$PEER" \
    | CLAUDE_CODE_SESSION_ID=$PEER bash "$here/hooks/prompt-status.sh" > "$tmp/hookc2"
assert_grep "prompt hook: a peer is still served that cached line" '\[running\]' "$tmp/hookc2"
assert_eq "without a tracker read" "$(count_calls 'api repos/[^ ]*/issues/42$')" 0

# The cache holds the private tracker's titles, the pending lists what a session was asked about.
# Neither is any other local user's business — nor is a tree an older copy left world-readable.
reset
( umask 022; mkdir -p "$cache_root"; chmod 755 "$tmp/cfg/carnet-claims" "$cache_root" )
( umask 022; printf '{"prompt":"please fix carnet#42","session_id":"%s"}' "$ME" | bash "$here/hooks/prompt-status.sh" ) > /dev/null
assert_eq "prompt hook: the claims directory ends up private" "$(ls -ld "$tmp/cfg/carnet-claims" | cut -c1-10)" "drwx------"
assert_eq "prompt hook: a cached line — a private title — is this account's only" \
    "$(ls -l "$cache_root/Atmosphere%2Fatmosphere-carnet/42" | cut -c1-10)" "-rw-------"
assert_eq "prompt hook: and so is the pending list" "$(ls -l "$pending_dir_h/$ME.txt" | cut -c1-10)" "-rw-------"

# Claude Code runs a hook in the session's CURRENT directory, and a session cds — into a
# submodule, a worktree of an older branch, a scratch directory. carnet.sh finds the register
# from the directory it runs in, so the hooks run it from $CLAUDE_PROJECT_DIR, where the session
# started: from anywhere else the SessionEnd release would release nothing, or another register.
mkdir -p "$tmp/elsewhere"
git init -q "$tmp/nested"                     # a checkout that names no register
reset
( cd "$tmp/nested" && printf '{"prompt":"go fix carnet#42","session_id":"%s"}' "$ME" \
    | bash "$here/hooks/prompt-status.sh" ) > "$tmp/hook6"
assert_grep "prompt hook: from another checkout, still this project's register" '^carnet#42 · open · unclaimed' "$tmp/hook6"
assert_grep "and it arms there" '^42$' "$pending_dir_h/$ME.txt"

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

# "Zero calls when nothing is held": a ledger kept alive by a filed line holds nothing.
reset
mkdir -p "$tmp/cfg/carnet-claims"
printf '{"kind":"identity","v":1,"session":"%s","name":"TestSession","user":"tester","host":"%s","pid":%s}\n{"kind":"filed","tracker":"Atmosphere/atmosphere-carnet","issue":42,"at":"2026-09-02T10:00:00Z"}\n' "$ME" "$HOST" "$$" > "$ledger"
printf '{"session_id":"%s"}' "$ME" | bash "$here/hooks/session-end-release.sh"
assert_eq "session-end hook: a ledger holding only a filed line costs no call" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
assert_grep "and keeps that line for the end-of-session audit" '"kind":"filed"' "$ledger"

# A pending list is consumed only by its own session's next write: one that named an issue and
# never wrote would leave it behind for good, and any list at all defeats auto-claim's fast exit.
reset
mkdir -p "$pending_dir_h" "$tmp/cfg/carnet-claims/warned"
printf '42\n' > "$pending_dir_h/$ME.txt"
printf '42\n' > "$tmp/cfg/carnet-claims/warned/$ME.txt"
printf '{"session_id":"%s"}' "$ME" | bash "$here/hooks/session-end-release.sh"
[ -f "$pending_dir_h/$ME.txt" ] && bad "session-end hook: the session's pending list goes with it" \
    || ok "session-end hook: the session's pending list goes with it"
[ -f "$tmp/cfg/carnet-claims/warned/$ME.txt" ] && bad "and so does its warned list" || ok "and so does its warned list"
# That id comes from the hook payload, and it names the file removed: never a path.
reset
mkdir -p "$pending_dir_h"
printf 'keep\n' > "$tmp/cfg/carnet-claims/victim.txt"
printf '{"session_id":"../victim"}' | bash "$here/hooks/session-end-release.sh"
[ -f "$tmp/cfg/carnet-claims/victim.txt" ] && ok "session-end hook: a session id that is a path removes nothing" \
    || bad "session-end hook: a session id that is a path removes nothing"

# From wherever the session last cd'd, the release still happens in this project's register.
for d in "$tmp/elsewhere" "$tmp/nested"; do
    reset
    seed_ledger 42
    comments < <(claim_marker "$ME" TestSession tester "$HOST" "$$")
    ( cd "$d" && printf '{"session_id":"%s"}' "$ME" | bash "$here/hooks/session-end-release.sh" ) > "$tmp/hook5" 2>&1
    assert_grep "session-end hook: run from $(basename "$d"), still releases" '^🔓 carnet#42 released \(session-ended\)' "$tmp/hook5"
    [ -f "$ledger" ] && bad "and finishes the ledger ($(basename "$d"))" || ok "and finishes the ledger ($(basename "$d"))"
done

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
# Any command at all, quotes included: printf-built JSON breaks on a double quote.
bash_payload_q() { jq -cn --arg c "$1" --arg s "$ME" '{tool_name:"Bash", session_id:$s, tool_input:{command:$c}}'; }
# What reaches the model. For a PreToolUse hook that exits 0, Claude Code shows the model only
# hookSpecificOutput.additionalContext; plain stdout goes to the debug log (hooks reference,
# code.claude.com/docs/en/hooks). So stdout must be exactly that one JSON object.
ac_is_json() {
    [ "$(wc -l < "$tmp/ac.out" | tr -d ' ')" = 1 ] \
        && jq -e '.hookSpecificOutput.hookEventName == "PreToolUse"' "$tmp/ac.out" >/dev/null 2>&1
}
ac_context() { jq -r '.hookSpecificOutput.additionalContext // empty' "$tmp/ac.out" > "$tmp/ac.ctx" 2>/dev/null || : > "$tmp/ac.ctx"; }

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
ac_is_json && ok "stdout is the one PreToolUse JSON object Claude Code reads" \
    || bad "stdout is the one PreToolUse JSON object Claude Code reads — got: $(head -c 200 "$tmp/ac.out")"
ac_context
assert_grep "and it tells the model so, as additionalContext" '^🔒 carnet auto-claimed: 42' "$tmp/ac.ctx"
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

# A quoted argument is data. A comparison in a jq or awk program, a Java generic in a grep
# pattern, `->` in a --format string — or a verb inside a grep for it — redirects and records
# nothing, and read as a write it would claim every pending issue off a pure read.
for c in 'jq ".[] | select(.count > 5)" data.json' \
         "awk '\$3 > 100' report.txt" \
         "gh api repos/o/r/pulls -q '.[] | select(.comments >= 2) | .number'" \
         'grep -rn "Map<String, List<Foo>>" modules/' \
         'git log --format="%h -> %s" -5' \
         "grep -n '<version>' pom.xml" \
         "grep -rn 'git add -A' docs/"; do
    reset; set_pending 42
    auto_claim "$(bash_payload_q "$c")"
    assert_eq "a read whose quoted argument holds '>' or a verb claims nothing: $c" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0
done
assert_grep "and the list stays armed for the first real write" '^42$' "$pending_dir/$ME.txt"
reset; set_pending 42
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
auto_claim "$(bash_payload_q 'jq ".[] | select(.count > 5)" data.json')"
assert_eq "nor is such a read blocked when a live peer holds the issue" "$rc" 0

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
# A quoted span stands in as a word, so what surrounds it is still judged as shell.
reset; set_pending 42
auto_claim "$(bash_payload_q 'echo x > "out file.txt"')"
assert_grep "a redirect into a quoted file name still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload_q 'git commit -m "fix: keep a > b"')"
assert_grep "a commit whose message holds a '>' still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
auto_claim "$(bash_payload_q 'git commit -m "honour the --check flag"')"
assert_grep "a commit whose message names --check still claims" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

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

# ...and so is ANOTHER session's: only its own session would consume it, and one that ended
# without a write leaves it for good — defeating the fast exit for every write on the machine.
reset; mkdir -p "$pending_dir"
printf '7\n' > "$pending_dir/$DEAD.txt"
touch -t 202001010000 "$pending_dir/$DEAD.txt"
printf '7\n' > "$pending_dir/$PEER.txt"
auto_claim "$(edit_payload)"
[ -f "$pending_dir/$DEAD.txt" ] && bad "an edit removes another session's list over an hour old" \
    || ok "an edit removes another session's list over an hour old"
[ -f "$pending_dir/$PEER.txt" ] && ok "but never a live one" || bad "but never a live one"

# A claim that FAILS blocks nothing — an unreachable tracker must not stop the work — but it is
# never silent: the session was promised the claim happens without it. It is told, through the
# one channel it reads, and the issue stays armed for its next write.
reset; set_pending 42
FAIL_RE='issues/42$' auto_claim "$(edit_payload)"
assert_eq "a claim that fails leaves the tool alone" "$rc" 0
ac_is_json && ok "the failure notice is the PreToolUse JSON object" \
    || bad "the failure notice is the PreToolUse JSON object — got: $(head -c 200 "$tmp/ac.out")"
ac_context
assert_grep "it tells the model the claim failed" '^carnet: could NOT claim' "$tmp/ac.ctx"
assert_grep "naming the issue and gh's reason" 'carnet#42 — .*cannot read carnet#42 .*HTTP 502' "$tmp/ac.ctx"
assert_grep "that the issue is NOT held" '^carnet#42 is NOT held, and peers see it as unclaimed' "$tmp/ac.ctx"
assert_grep "the command to claim it by hand" 'carnet\.sh claim <n>' "$tmp/ac.ctx"
assert_grep "and that the hook will try again" 'tries again on your next edit' "$tmp/ac.ctx"
assert_grep "the issue stays armed" '^42$' "$pending_dir/$ME.txt"
auto_claim "$(edit_payload)"
assert_grep "the next write claims it once the tracker answers" 'issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
ac_context
assert_grep "and says so" '^🔒 carnet auto-claimed: 42' "$tmp/ac.ctx"
[ -f "$pending_dir/$ME.txt" ] && bad "then the list is consumed" || ok "then the list is consumed"

reset; set_pending 42
FAIL_RE='^api user' auto_claim "$(edit_payload)"
ac_context
assert_grep "a logged-out gh is reported too" 'carnet#42 — .*gh auth login' "$tmp/ac.ctx"

# Retried within the list's hour, three attempts in all: a tracker that stays down must not cost
# every later write a round of gh calls.
reset; set_pending 42
for attempt in 1 2 3; do
    FAIL_RE='issues/42$' auto_claim "$(edit_payload)"
    [ "$attempt" = 3 ] || assert_grep "attempt $attempt keeps the issue armed" '^42$' "$pending_dir/$ME.txt"
done
[ -f "$pending_dir/$ME.txt" ] && bad "three failed attempts drop the list" || ok "three failed attempts drop the list"
ac_context
assert_grep "and the last one says the hook has stopped trying" 'stopped trying' "$tmp/ac.ctx"
: > "$S/calls.log"
FAIL_RE='issues/42$' auto_claim "$(edit_payload)"
assert_eq "after which a write asks nothing" "$(wc -c < "$S/calls.log" | tr -d ' ')" 0

# A closed issue cannot become claimable: it is reported once and not retried.
reset; set_pending 42
issue_closed
auto_claim "$(edit_payload)"
ac_context
assert_grep "a closed issue is reported" 'carnet#42 — .*is closed' "$tmp/ac.ctx"
assert_no_grep "without telling the model to claim what cannot be claimed" 'Claim it yourself' "$tmp/ac.ctx"
[ -f "$pending_dir/$ME.txt" ] && bad "and not retried" || ok "and not retried"
reset; set_pending 42
FAIL_RE='issues/42$' FAIL_MSG='Not Found (HTTP 404)' auto_claim "$(edit_payload)"
ac_context
assert_grep "a number with no issue behind it is reported" 'carnet#42 does not exist in Atmosphere/atmosphere-carnet' "$tmp/ac.ctx"
[ -f "$pending_dir/$ME.txt" ] && bad "and not retried either" || ok "and not retried either"

# A block stops the session — which must still learn what the same step claimed for it, or it
# walks away holding an issue it was never told about.
reset; set_pending 42 43
claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid" > "$S/comments-42.txt"
auto_claim "$(edit_payload)"
assert_eq "one issue held by a live peer blocks the edit" "$rc" 2
assert_grep "the other issue was claimed in the same step" 'issues/43/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
assert_grep "and the block says so" 'Claimed for you in the same step.*carnet#43' "$tmp/ac.err"
[ -f "$pending_dir/$ME.txt" ] && bad "a list fully decided is consumed" || ok "a list fully decided is consumed"

# The ledger names claims in every register the session touched, and their numbers overlap.
# Another register's #42 is no reason to skip this one's — nor to miss that a peer holds it.
reset; set_pending 42
seed_ledger
printf '{"kind":"claim","tracker":"some-org/some-carnet","issue":42,"at":"2026-09-02T10:00:00Z"}\n' >> "$ledger"
auto_claim "$(edit_payload)"
assert_grep "another register's #42 in the ledger does not suppress this register's claim" \
    'repos/Atmosphere/atmosphere-carnet/issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"
reset; set_pending 42
seed_ledger
printf '{"kind":"claim","tracker":"some-org/some-carnet","issue":42,"at":"2026-09-02T10:00:00Z"}\n' >> "$ledger"
comments < <(claim_marker "$PEER" PeerSession peer "$HOST" "$peer_pid")
auto_claim "$(edit_payload)"
assert_eq "nor a live peer's hold on it" "$rc" 2
reset; set_pending 42
seed_ledger 42
auto_claim "$(edit_payload)"
assert_eq "this register's own #42 in the ledger is held already: no call" "$(count_calls 'api ')" 0

# From wherever the session has cd'd, the claim goes to this project's register.
reset; set_pending 42
( cd "$tmp/nested" && printf '%s' "$(edit_payload)" | bash "$here/hooks/auto-claim.sh" ) > "$tmp/ac.out" 2> "$tmp/ac.err"
assert_grep "auto-claim from a checkout that names no register still claims in the project's" \
    'repos/Atmosphere/atmosphere-carnet/issues/42/labels -X POST -f labels\[\]=in-progress' "$S/calls.log"

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
ac_is_json && ok "a wakeup-armed list: the notice is the PreToolUse JSON object" \
    || bad "a wakeup-armed list: the notice is the PreToolUse JSON object — got: $(head -c 200 "$tmp/ac.out")"
ac_context
assert_grep "a wakeup-armed list: tells the model, naming the issue" '^carnet: NOT claimed — carnet#42 came from a scheduled wakeup' "$tmp/ac.ctx"
assert_grep "a wakeup-armed list: says how to take it deliberately" 'carnet.sh claim <n>' "$tmp/ac.ctx"
[ -f "$pending_dir/$ME.txt" ] && bad "a wakeup-armed list is consumed" || ok "a wakeup-armed list is consumed"

reset; set_pending "prompt=$N1" 42
auto_claim "$(edit_payload_t)"
assert_no_grep "a task-notification-armed list: claims nothing" 'labels\[\]=in-progress' "$S/calls.log"
ac_context
assert_grep "a task-notification-armed list: names the machine origin" 'came from a machine-injected prompt' "$tmp/ac.ctx"

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

# One config dir serves every checkout on the machine, and registers' numbers overlap: another
# register's issue named in a prompt here would otherwise claim — or block on — whatever this
# register holds under the same number.
section "Only this register's issues arm"
reset
eq "" "$(arms 'port the fix from https://github.com/dravr-ai/dravr-carnet/issues/12')" \
    "another register's issue URL arms nothing"
eq "" "$(arms 'same root cause as dravr-carnet#31, port it here')" \
    "another register's short form arms nothing"
eq "" "$(arms 'see dravr-ai/dravr-carnet#8 for the root cause')" \
    "nor its owner/repo form"
eq "" "$(arms 'the gate bug is upstream, see llm-registre#5')" \
    "a repo whose name ends in registre arms nothing"
eq "" "$(arms 'bump .registre to llm-registre 1.4')" \
    "nor a version beside it"
eq "" "$(arms 'a fork has it too: someone/atmosphere-carnet#12')" \
    "nor a fork of this register"
eq "12" "$(arms 'take atmosphere-carnet#12')" \
    "this register's name arms"
eq "42" "$(arms 'Atmosphere/atmosphere-carnet#42 is ours')" \
    "so does its owner/repo form"
eq "7" "$(arms 'https://github.com/Atmosphere/atmosphere-carnet/issues/7')" \
    "and its issue URL"
eq "5" "$(arms 'registre#5 is ours')" \
    "and a bare registre#N"
eq "9" "$(arms 'LIMITATION(registre#9) names it')" \
    "and a LIMITATION marker's reference"

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

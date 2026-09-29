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
# ABOUTME: Stop hook — refuses a stop while this session still holds carnet issues or unpushed work
# ABOUTME: DISARMED: deliberately not wired in .claude/settings.json; kept, and tested, so the decision stays legible
#
# Not wired, on purpose — see "The three call sites" in SKILL.md. A wrong number costs a
# paragraph; a wrong number that can STOP a session costs every turn it has left, and fixing one
# instance of a misattributed cap does not remove the class. bilan is an instrument, not a gate:
# the score reaches a session through the status line and /bilan, never by interrupting it.
#
# Were it ever armed, this is the entry, and the guards below are what it would rely on:
#   "Stop": [{ "hooks": [{ "type": "command", "timeout": 15,
#     "command": "[ -f .agents/skills/bilan/hooks/stop-gate.sh ] && bash .agents/skills/bilan/hooks/stop-gate.sh || true" }]}]
#
# Two loop guards, because a hook that blocks forever is worse than no hook:
#   1. stop_hook_active — the host sets it on the stop that this hook already blocked once.
#   2. a cooldown — it never blocks twice inside thirty minutes.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
bilan="$here/../bilan.sh"
[ -f "$bilan" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

payload=$(cat 2>/dev/null || true)
[ "$(printf '%s' "$payload" | jq -r '.stop_hook_active // false' 2>/dev/null)" = true ] && exit 0

sid=$(printf '%s' "$payload" | jq -r '.session_id // empty' 2>/dev/null || true)
[ -n "$sid" ] || sid=${CLAUDE_CODE_SESSION_ID:-}
[ -n "$sid" ] || exit 0

# --cheap keeps this off the network: hosts kill a hook at ~10s regardless of the declared
# timeout, and this would run when every turn ends.
report=$(CLAUDE_CODE_SESSION_ID="$sid" bash "$bilan" --cheap --json 2>/dev/null) || true
[ -n "$report" ] || exit 0

# The gate blocks at 8 or below. A cap of 9 — an untracked scratch file, a stash, a stale
# validation marker — is worth reporting and is not worth refusing a stop over; a session would
# hit one on nearly every turn and the gate would become wallpaper. What blocks is what gets
# left behind at the end of a session: a carnet issue still held (6), an unregistered LIMITATION
# marker (6), uncommitted tracked files (7), commits never pushed (8). The block reason still
# lists every cap, so nothing is hidden by the threshold — only the decision to interrupt turns
# on it.
score=$(printf '%s' "$report" | jq -r '.score // 10' 2>/dev/null)
case "$score" in ''|*[!0-9]*) exit 0 ;; esac
[ "$score" -ge 9 ] && exit 0

CFG=${CLAUDE_CONFIG_DIR:-$HOME/.claude}
state_dir="$CFG/bilan"
mkdir -p "$state_dir" 2>/dev/null || exit 0
state="$state_dir/$(printf '%s' "$sid" | tr -c 'a-zA-Z0-9._-' '_').json"

signature=$(printf '%s' "$report" | jq -r '[.caps[] | .evidence] | sort | join("|")' 2>/dev/null \
            | shasum 2>/dev/null | cut -d' ' -f1)
[ -n "$signature" ] || exit 0
# One rule, so the cost is predictable: never block twice inside thirty minutes, and after that
# block again while the score is still 8 or below.
#
# The two extremes are both wrong. Blocking on every distinct state means a peer editing beside
# the session in a shared checkout produces a new signature every few minutes and the gate never
# stops talking. Blocking once per session, ever, means a session still holding an issue hours
# later is told once and never again. A cooldown costs at most two model turns an hour and keeps
# telling a session that is genuinely stuck.
#
# The status line carries the same number continuously at zero cost, so this channel would only
# have to catch the session that is about to stop, not keep anyone informed.
COOLDOWN=1800
last=$(jq -r '.blockedAt // empty' "$state" 2>/dev/null)
if [ -n "$last" ]; then
    # -u, or BSD date reads the UTC stamp as local time and the elapsed value comes out
    # NEGATIVE — which is always "inside the cooldown", so a session blocked once could never
    # be blocked again.
    last_epoch=$(date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$last" +%s 2>/dev/null \
                 || date -u -d "$last" +%s 2>/dev/null || echo 0)
    [ $(( $(date +%s) - ${last_epoch:-0} )) -lt "$COOLDOWN" ] && exit 0
fi
blocks=$(jq -r '.blocks // 0' "$state" 2>/dev/null); blocks=${blocks:-0}
case "$blocks" in ''|*[!0-9]*) blocks=0 ;; esac

jq -n --arg s "$signature" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson score "$score" \
   --argjson n "$((blocks + 1))" \
   '{signature:$s, blockedAt:$at, score:$score, blocks:$n}' > "$state" 2>/dev/null || true

reason=$(printf '%s' "$report" | jq -r '
    "bilan says this session is at \(.score)/10, not done. Outstanding:\n"
    + ([.caps[] | "  · \(.evidence)\n    → \(.remedy)"] | join("\n"))
    + "\n\nFinish these, then report the number bilan gives — not your own. If one of them is "
    + "deliberate, say which and why in your reply rather than leaving it unstated."')

jq -n --arg r "$reason" '{decision:"block", reason:$r}'
exit 0

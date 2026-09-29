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
# ABOUTME: UserPromptSubmit hook — prints the live claim status of every carnet issue the prompt names
# ABOUTME: Best-effort and deterministic: caches each lookup for a minute and exits 0 on every failure
#
# Wire it in .claude/settings.json:
#   "UserPromptSubmit": [{ "matcher": "", "hooks": [{ "type": "command", "timeout": 20,
#     "command": "[ -f \"$CLAUDE_PROJECT_DIR/.agents/skills/carnet/hooks/prompt-status.sh\" ] && bash \"$CLAUDE_PROJECT_DIR/.agents/skills/carnet/hooks/prompt-status.sh\" || true" }]}]
#
# Whatever this prints lands in the model's context before it answers, so a session that is
# told "carnet#12 · held by @alice · session Wiring [running]" cannot start the same work
# without knowing. One gh call per issue mentioned, at most five per prompt.
#
# It also writes the numbers it found to carnet-claims/pending/<session>.txt, which is what
# the PreToolUse hook claims from on the session's first edit. Knowing is not holding, and a
# claim that waits for the model to remember is the failure the claim exists to prevent.
#
# BUT ONLY FOR WHAT THE USER TYPED. `.prompt` also carries text no human wrote: a peer
# session's `<cross-session-message>` and a background `<task-notification>` arrive in it
# byte-identically to a typed prompt. A peer NAMING an issue is not your user ASSIGNING it,
# and the difference is the whole point of the claim -- so those two arm nothing.
#
# A THIRD machine author is the session itself. A `/loop` or ScheduleWakeup re-fire arrives as
# a prompt whose text the model wrote on its previous turn, and it names whatever the model
# was thinking about. Nothing in the text marks a wakeup — it starts with whatever the model
# chose — so the prompt hook cannot see it here: the payload's `source` field ("loop_wakeup",
# "schedule_wakeup", "system") is declared but not yet emitted as of Claude Code 2.1.276, and
# the transcript entry that would say `promptSource: "system"` is not written until AFTER this
# hook has run. What this hook CAN do is record which prompt armed the list
# (`prompt=<prompt_id>`), so auto-claim.sh — which runs after the entry exists — can look it up
# and refuse a machine-authored arm. When `source` does start arriving, the check below
# honours it here as well.
#
# Arming on a message no human typed goes wrong in both directions:
#   * false claim  -- a peer writes "do NOT put this in carnet#N", or replies "not mine", and
#                     the recipient's next edit claims #N: label, assignee and marker comment
#                     all say a session is working an issue it was never going to touch.
#   * false block  -- one FYI ("I hold carnet#N, stay off these files") blocks a tool call in
#                     every session it reaches, each told "Do not do this work twice" about
#                     work it never started, some of them in another repo entirely.
# The status lines still print for both: knowing who holds #N is exactly what the receiver
# needs in order to answer. Printing informs. Arming assigns. Only a typed prompt assigns.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
carnet="$here/../carnet.sh"
[ -x "$carnet" ] || [ -f "$carnet" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0
command -v gh >/dev/null 2>&1 || exit 0

payload=$(cat 2>/dev/null || true)
prompt=$(printf '%s' "$payload" | jq -r '.prompt // empty' 2>/dev/null || true)
[ -n "$prompt" ] || exit 0

# Who is talking. Captured from live payloads: a peer message is the raw envelope
# `<cross-session-message from="uds:..." from-name="..." ...>` and a background result is
# `<task-notification>`; a typed prompt is neither. Anchored at the start, so a user who
# quotes a peer message inside their own prompt is still the user, and still arms.
case $prompt in
    '<cross-session-message'*|'<task-notification'*|'Another Claude session sent a message:'*)
        from_peer=1 ;;
    *)  from_peer=0 ;;
esac
# The payload's own answer, once Claude Code sends it: `user` is the interactive composer and
# everything else (`sdk`, `system`, `loop_wakeup`, `schedule_wakeup`, `poll_event`) is a
# machine. Absent today; when present it outranks the envelope test above.
source=$(printf '%s' "$payload" | jq -r '.source // empty' 2>/dev/null || true)
if [ -n "$source" ] && [ "$source" != user ]; then from_peer=1; fi

# carnet#12 · carnet 12 · carnet-12 · registre#12 · …/atmosphere-carnet/issues/12
issue_nums() {
    grep -oiE '(carnet|registre)[ #-]?[0-9]+|carnet/issues/[0-9]+' \
        | grep -oE '[0-9]+$' | sort -un | head -5 || true
}
nums=$(printf '%s' "$prompt" | issue_nums)

# A number the user only QUOTED is not a number the user assigned. The anchored peer test above
# catches a peer message that arrives on its own, but not the far commoner case here: the user
# pastes a peer session's terminal output to show you something, and every issue that transcript
# happens to mention would be claimed for the reader — and one a live peer holds would block a
# tool call over work the reader never meant to take.
#
# Splitting the prompt at the first transcript marker and arming on the prose before it is too
# clever: a paste whose agent prose leads with no marker at all — the user's own sentence running
# straight into it — puts the number on the wrong side of the split. So the test is the whole
# prompt, not a boundary within it: if terminal-transcript glyphs appear ANYWHERE, nothing arms.
#
# It fails in the safe direction on purpose. Missing a claim costs one `carnet.sh claim <n>`;
# taking a peer's issue costs them an interrupted tool call and a stolen assignment. Status still
# prints either way, because knowing who holds an issue is exactly what the reader needs.
#
# ⏺ turn · ⎿ tool result · ✻ ✢ thinking · ⏵ permissions footer · ❯ shell prompt · ─── rule
case $prompt in
    *⏺*|*⎿*|*✻*|*✢*|*⏵*|*❯*|*───*) pasted=1 ;;
    *) pasted=0 ;;
esac
if [ "$pasted" = 1 ]; then armable=""; else armable=$nums; fi

if [ -n "$armable" ] && [ "$from_peer" = 0 ]; then
    sid=$(printf '%s' "$payload" | jq -r '.session_id // empty' 2>/dev/null || true)
    [ -n "$sid" ] || sid=${CLAUDE_CODE_SESSION_ID:-}
    if [ -n "$sid" ]; then
        pending_dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/carnet-claims/pending"
        # `prompt=<id>` first: auto-claim.sh reads the numbers with a digits-only grep, so the
        # line is invisible to it as a number and is how it finds this prompt's transcript
        # entry to ask who wrote it.
        prompt_id=$(printf '%s' "$payload" | jq -r '.prompt_id // empty' 2>/dev/null || true)
        if mkdir -p "$pending_dir" 2>/dev/null; then
            { [ -z "$prompt_id" ] || printf 'prompt=%s\n' "$prompt_id"
              printf '%s\n' $armable; } > "$pending_dir/$sid.txt"
        fi
    fi
fi

[ -n "$nums" ] || exit 0

# The config dir, and the cache in it, are shared by every checkout on this machine — including
# ones whose registre.toml names a different register, with issue numbers that overlap this
# one's. Keyed by tracker, carnet#12 here is never answered with a line cached for another
# register's #12. No tracker means `status` could not answer either.
tracker=$(bash "$carnet" tracker 2>/dev/null) || exit 0
[ -n "$tracker" ] || exit 0
cache_dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/carnet-claims/cache/$(jq -rn --arg s "$tracker" '$s|@uri')"
mkdir -p "$cache_dir" 2>/dev/null || exit 0

printed=0
for n in $nums; do
    cache="$cache_dir/$n"
    line=""
    if [ -f "$cache" ] && [ -n "$(find "$cache" -mmin -1 2>/dev/null)" ]; then
        line=$(cat "$cache" 2>/dev/null || true)
    elif line=$(bash "$carnet" status "$n" --short 2>/dev/null) && [ -n "$line" ]; then
        # The cache is shared by every session on this machine; the line is not. `status` says
        # "held by THIS session" when the holder is the caller, which is true only for the
        # session that wrote it — a peer reading it would be told it holds an issue it does
        # not, and a session acting on that would close someone else's work. Only
        # session-neutral lines are shared; the holder pays one gh call per prompt for its own
        # issues, which is the cheap side.
        case "$line" in
            *"THIS session"*) : ;;
            *) printf '%s\n' "$line" > "$cache" ;;
        esac
    else
        line=""
    fi
    [ -n "$line" ] || continue
    printf '%s\n' "$line"
    printed=1
done

# The mechanical half is done above -- nothing was armed. This is the other half: the model
# still reads an issue number and can decide, on its own, to go and fix it. Say what the
# message is and is not, at the moment the number enters context.
if [ "$printed" = 1 ] && [ "$from_peer" = 0 ] && [ -z "$armable" ]; then
    cat <<'NOTE'
↑ This prompt contains pasted terminal output, so nothing was claimed for you — a number
  inside someone else's transcript is not an assignment. Read it as context. If your user
  wants you on one of these, take it deliberately:
  .agents/skills/carnet/carnet.sh claim <n>
NOTE
fi

if [ "$printed" = 1 ] && [ "$from_peer" = 1 ]; then
    cat <<'NOTE'
↑ Named by another session, a background task, or a scheduled wakeup -- NOT by your user.
  Nothing was claimed for you, and a mention is not an assignment. Answer the sender and go back to
  your own goal: do not claim these issues, assign them to yourself, comment on them, or
  start fixing them. If this repo or your current task is unrelated, one line saying so is
  the complete and correct reply.
  Only when the sender is explicitly handing work over, and you are taking it:
  .agents/skills/carnet/carnet.sh claim <n>
NOTE
fi
exit 0

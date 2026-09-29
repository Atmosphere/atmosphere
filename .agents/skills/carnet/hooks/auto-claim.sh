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
# ABOUTME: PreToolUse hook — claims the carnet issues a prompt named, on the session's first edit
# ABOUTME: Makes the claim mechanical instead of advisory; every failure path leaves the tool alone
#
# Wire it in .claude/settings.json ($CLAUDE_PROJECT_DIR rather than a relative path, so the
# guard still finds the script when the session's working directory is not the project root):
#   "PreToolUse": [{ "matcher": "Edit|Write|MultiEdit|NotebookEdit|Bash", "hooks": [{ "type": "command",
#     "timeout": 20,
#     "command": "[ -f \"$CLAUDE_PROJECT_DIR/.agents/skills/carnet/hooks/auto-claim.sh\" ] || exit 0; bash \"$CLAUDE_PROJECT_DIR/.agents/skills/carnet/hooks/auto-claim.sh\"" }]}]
#
# The wiring must hand the script's exit status to Claude Code untouched — exit 2 is the
# block. test.sh runs the command it finds in .claude/settings.json to prove that.
#
# WHAT THE MODEL SEES. The hooks reference (code.claude.com/docs/en/hooks) is explicit: for a
# PreToolUse hook that exits 0, stdout goes to the debug log and "Stderr from a hook that exits 0
# goes to the debug log only, never the transcript, and Claude never sees it"; exit 2 blocks the
# tool call and Claude sees the stderr text as the reason. Text meant for the model on a call
# that proceeds is `additionalContext` — "String added to Claude's context alongside the tool
# result" — in the one JSON object stdout may then carry ("Your hook's stdout must contain only
# the JSON object"):
#   {"hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"…"}}
# So every notice below — claimed, refused as machine-authored, could not claim — is that
# object, and nothing else is ever printed on stdout.
#
# The rule was "claim before the first edit", and a rule the model has to remember is one it
# will sometimes forget -- which is the whole failure the claim exists to prevent. So the
# trigger is the edit itself: prompt-status.sh writes the issue numbers a prompt named, and
# the first write-shaped tool call after that claims them.
#
# Reading is not working. A prompt that only asks about an issue never reaches a write tool,
# so it never claims. A Bash call is treated as an edit only when the command looks like one.
#
# On a conflict the tool is blocked ONCE (exit 2, stderr reaches the model) naming the live
# peer that holds the issue. Once told, the session is accountable and later edits pass: a
# permanent block would be a deadlock over an issue that may only have been mentioned.
#
# A claim that FAILS — gh logged out, a 5xx, a rate limit, a comment stream it could not read —
# blocks nothing either: an unreachable tracker must not stop the work. But the session is told,
# since it has been promised the claim happens without it, and the issue stays armed for its next
# write, at most three attempts within the list's hour.
#
# WHO WROTE THE PROMPT is checked here, not in the prompt hook, because only here can it be.
# A `/loop` or ScheduleWakeup re-fire is a prompt the model wrote for itself, and it names
# whatever the model was thinking about: a session that writes "comment on carnet#N" into its
# own wakeup would have the prompt hook arm #N as if the user had typed it, and this hook
# claim it on the next redirect — an issue held for as long as the session runs, the human
# never having typed the number. The prompt text carries no marker and the payload's `source`
# field is not emitted yet, but the transcript entry for the prompt (found by the
# `prompt=<prompt_id>` line the prompt hook records) says `promptSource: "system"`,
# `isMeta: true`, `scheduledTaskId: …` — and that entry is written before the model's first
# tool call, while it is NOT there yet when the prompt hook runs. So: the list is consumed, the
# entry is read, and a machine-authored prompt claims nothing and says so once. An entry that
# cannot be found claims as before: the transcript path can be absent or translated in a cloud
# session, and a check that silently stops every claim disables the whole mechanism without
# anyone noticing.
set -uo pipefail
# The warned lists and the re-armed pending lists are this account's alone.
umask 077

CFG=${CLAUDE_CONFIG_DIR:-$HOME/.claude}
PENDING_DIR="$CFG/carnet-claims/pending"

# Cheapest possible exit for the overwhelmingly common case: nothing is pending. No jq, no
# payload parse, no subprocess -- this runs before every edit in every session.
[ -d "$PENDING_DIR" ] || exit 0
set -- "$PENDING_DIR"/*.txt
[ -e "$1" ] || exit 0

# That exit only holds while lists do not pile up. A list is consumed by its own session's next
# write, so a session that names an issue and ends without one — killed before SessionEnd could
# remove its list — would leave it for good, and a single leftover anywhere sends every write of
# every session past the exit above. A list over an hour old is dropped unread by its own session
# anyway (below), so any session may remove it.
find "$PENDING_DIR" -maxdepth 1 \( -name '*.txt' -o -name '.retry.*' \) -mmin +60 -exec rm -f {} + 2>/dev/null
set -- "$PENDING_DIR"/*.txt
[ -e "$1" ] || exit 0

here=$(cd "$(dirname "$0")" && pwd)
carnet="$here/../carnet.sh"
[ -f "$carnet" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0
command -v gh >/dev/null 2>&1 || exit 0

payload=$(cat 2>/dev/null || true)
[ -n "$payload" ] || exit 0
tool=$(printf '%s' "$payload" | jq -r '.tool_name // empty' 2>/dev/null || true)

case "$tool" in
    Edit|Write|MultiEdit|NotebookEdit) ;;
    Bash)
        cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null || true)
        # Quoted arguments are data, not shell syntax. A jq or awk comparison (`select(.n > 5)`,
        # `'$3 > 100'`), a Java generic in a grep pattern, `->` in a --format string, or a verb
        # inside a grep for it (`grep 'git add -A'`) writes nothing, and read as a write it would
        # claim every pending issue off a pure read — or block that read when a peer holds one.
        # Each quoted span becomes one placeholder word, not nothing, so `> "out file.txt"` is
        # still a redirect into a file. sed works line by line, so a span crossing lines is left
        # as it is, and a write wrapped whole in quotes (`sh -c '… > f'`) now reads as a read:
        # both fail toward a missed claim, the direction chosen on purpose (prompt-status.sh).
        probe=$(printf '%s' "$cmd" | sed -E "s/'[^']*'/Q/g; s/\"([^\"\\\\]|\\\\.)*\"/Q/g")
        # Discard redirects to /dev/null before classifying. `2>/dev/null` is the
        # commonest idiom in a READ, and it contains a `>`, so the redirect test
        # below would count every quiet read as a write: `git status -sb` would
        # claim nothing while `git log ... 2>/dev/null` claimed everything pending,
        # off a message that only NAMED the number.
        probe=$(printf '%s' "$probe" | sed -E 's/(^|[[:space:]&])[0-9]*>>?[[:space:]]*\/dev\/null//g')
        # `git add --dry-run` / `git apply --check` report and change nothing.
        probe=$(printf '%s' "$probe" | sed -E 's/git[[:space:]]+[a-z-]+([[:space:]]+[^;|&]*)?(--dry-run|--check)/git-reporting-only/g')
        # A write-shaped command: a redirect into a file, an in-place edit, or git recording
        # something. Anything else is a read, and a read claims nothing.
        #
        # The git verbs need a terminator. Without one, `merge` matches inside
        # `git merge-base` — the standard way to ask "is this commit on main?" —
        # so a pure ancestry query would count as a write and claim every pending
        # issue, including one a peer is in the middle of investigating. Same
        # failure class as the `2>/dev/null` case above: a read that
        # pattern-matches as a write, costing an issue that was only NAMED.
        #
        # `--dry-run` and `--check` are excluded for the same reason: `git add
        # --dry-run` and `git apply --check` write nothing and only report.
        printf '%s' "$probe" | grep -qE '>>?[[:space:]]*[^&|[:space:]]|sed -i|(^|[|;&[:space:]])tee[[:space:]]|(^|[|;&[:space:]])(mv|cp|rm|mkdir|touch|install)[[:space:]]|git[[:space:]]+(commit|add|apply|am|merge|rebase|revert|cherry-pick|checkout|restore|reset)([[:space:]]|$)' || exit 0
        ;;
    *) exit 0 ;;
esac

sid=$(printf '%s' "$payload" | jq -r '.session_id // empty' 2>/dev/null || true)
[ -n "$sid" ] || sid=${CLAUDE_CODE_SESSION_ID:-}
[ -n "$sid" ] || exit 0
# The id becomes file names below, which are rewritten and removed: nothing but a UUID's characters.
case $sid in *[!A-Za-z0-9-]*) exit 0 ;; esac

pending="$PENDING_DIR/$sid.txt"
[ -s "$pending" ] || exit 0

# A list the session never acted on goes stale: an issue named an hour ago is not what this
# edit is about. Bounded rather than permanent, so multi-turn work still claims.
if [ -z "$(find "$pending" -mmin -60 2>/dev/null)" ]; then
    rm -f "$pending"
    exit 0
fi

# One run per session works the list. Subagents fire PreToolUse under their parent's session_id
# (only agent_id tells their calls apart), so two of them writing at once would each read this
# list and claim it twice over: two claim markers, a second assign-and-label round. A run that
# finds a live one working it lets its tool call through, as with nothing pending. The list
# itself stays where it is meanwhile, so a run cancelled at the hook's timeout leaves it armed
# for the next write; and a run that died holding the lock — its pid gone, or the lock older
# than any run can last — holds nothing. If the directory refuses the lock, the list is worked
# unlocked, as it always was.
run_lock="$PENDING_DIR/$sid.lock"
if ! mkdir "$run_lock" 2>/dev/null; then
    if [ -d "$run_lock" ]; then
        owner=$(cat "$run_lock/pid" 2>/dev/null || true)
        if { ! [[ $owner =~ ^[0-9]+$ ]] || kill -0 "$owner" 2>/dev/null; } \
            && [ -z "$(find "$run_lock" -maxdepth 0 -mmin +1 2>/dev/null)" ]; then
            exit 0
        fi
        # Read again before removing it: another run may have broken it already, and hold it now.
        [ "$(cat "$run_lock/pid" 2>/dev/null || true)" = "$owner" ] || exit 0
        rm -f "$run_lock/pid" 2>/dev/null
        rmdir "$run_lock" 2>/dev/null
        mkdir "$run_lock" 2>/dev/null || exit 0
    else
        run_lock=""
    fi
fi
if [ -n "$run_lock" ]; then
    printf '%s\n' "$$" > "$run_lock/pid"
    # Released on every exit, and only while it is still this run's.
    trap '[ "$(cat "$run_lock/pid" 2>/dev/null)" != "$$" ] || { rm -f "$run_lock/pid"; rmdir "$run_lock" 2>/dev/null; }' EXIT
fi
# Worked and consumed by the run that held the lock before this one.
[ -s "$pending" ] || exit 0

armed=$(cat "$pending" 2>/dev/null || true)
nums=$(printf '%s\n' "$armed" | tr -d ' \r' | grep -E '^[0-9]+$' | sort -un || true)
prompt_id=$(printf '%s\n' "$armed" | grep -m1 '^prompt=' | cut -d= -f2- || true)
# How many times this list has been tried already: it is re-armed after a claim that failed.
tries=$(printf '%s\n' "$armed" | grep -m1 '^tries=' | cut -d= -f2- || true)
[[ $tries =~ ^[0-9]+$ ]] || tries=0
[ -n "$nums" ] || { rm -f "$pending"; exit 0; }

# Text for the model on a call that proceeds (see WHAT THE MODEL SEES above).
tell() { jq -cn --arg c "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",additionalContext:$c}}'; }
refs() { printf 'carnet#%s ' "$@" | sed 's/ $//'; }

# The prompt that armed this list: was it typed? The transcript entry with this promptId is
# a `user` line whose content is a string (tool results are arrays). `promptSource` is
# "typed" or "queued" for the composer, "sdk" for -p, "system" for a peer message, a task
# notification, a usage-limit continuation or a scheduled wakeup; the last two also carry
# `isMeta: true`, and a wakeup carries `scheduledTaskId`. Positive evidence of a machine
# author refuses the claim; anything else, including an entry that cannot be found, claims.
transcript=$(printf '%s' "$payload" | jq -r '.transcript_path // empty' 2>/dev/null || true)
if [ -n "$prompt_id" ] && [ -n "$transcript" ] && [ -r "$transcript" ]; then
    author=$(jq -rR --arg id "$prompt_id" '
        fromjson? | select(.type == "user" and .promptId == $id and (.message.content | type) == "string")
        | [(.promptSource // ""), ((.isMeta // false) | tostring), ((.scheduledTaskId // "") | tostring)]
        | join("\t")' "$transcript" 2>/dev/null | head -1 || true)
    IFS=$'\t' read -r p_source p_meta p_sched <<< "${author:-}"
    if [ "${p_source:-}" = system ] || [ "${p_meta:-}" = true ] || [ -n "${p_sched:-}" ]; then
        if [ -n "${p_sched:-}" ]; then origin="a scheduled wakeup this session wrote for itself"
        else origin="a machine-injected prompt (peer message, task result or auto-continuation)"; fi
        rm -f "$pending"                  # consumed: act once, never on every later edit
        tell "carnet: NOT claimed — $(refs $nums) came from $origin, not from your user.
  A mention is not an assignment. If you are meant to work it, take it deliberately:
  .agents/skills/carnet/carnet.sh claim <n>"
        exit 0
    fi
fi

# This project's register, wherever the session has cd'd to since the prompt: Claude Code runs
# a hook in the session's current directory, and carnet.sh resolves the register from the
# directory it runs in. The prompt hook armed this list against the same project.
cd "${CLAUDE_PROJECT_DIR:-$here/../../../..}" 2>/dev/null || exit 0

warned="$CFG/carnet-claims/warned/$sid.txt"
mkdir -p "$(dirname "$warned")" 2>/dev/null || true

claimed=""
blocked=""
failed=""
transient=""
for n in $nums; do
    # Whether this session holds the issue already is `claim`'s to say, from the newest marker,
    # and it says so without a write. The ledger cannot: its line goes in before the first
    # tracker write, so a claim cut off half-way — at this hook's timeout, or by a failed marker
    # POST — is listed there and still not held, and only another `claim` finishes it. The
    # ledger wait stays well under this hook's own timeout: a stuck ledger lock is then a claim
    # that failed, told and retried, rather than a hook cancelled on every write.
    out=$(CARNET_LOCK_WAIT=5 bash "$carnet" claim "$n" 2>&1); rc=$?
    case $rc in
        0) case $out in *"already held by this session"*) : ;; *) claimed="$claimed $n" ;; esac ;;
        2)
            # Refused: a live peer holds it. Say so once, then stop repeating it.
            if ! { [ -f "$warned" ] && grep -qx "$n" "$warned"; }; then
                printf '%s\n' "$n" >> "$warned"
                blocked="$blocked
carnet#$n — $(printf '%s' "$out" | grep -v '^$' | tail -2)"
            fi
            ;;
        *)
            # Never a block: an unreachable issue or a bad tracker must not stop the work. A
            # closed or missing issue cannot change; anything else may clear, so it stays armed.
            why=$(printf '%s' "$out" | grep -v '^$' | tail -1)
            failed="$failed
carnet#$n — ${why:-carnet.sh claim exited $rc}"
            case $why in
                *" is closed — nothing to claim"*|*" does not exist in "*) : ;;
                *) transient="$transient $n" ;;
            esac
            ;;
    esac
done
retry=$transient

# The list is consumed by what was decided — claimed, refused, already held, or past any retry —
# and keeps only what failed for a reason a retry can clear, for the session's next write. The
# rewritten list keeps its mtime, so its hour still counts from the prompt that armed it; after
# three attempts it is dropped, so a tracker that is down does not cost every later write a round
# of gh calls. A list the prompt hook re-armed meanwhile is newer, and is left as it is.
tmp=""
if [ "$(cat "$pending" 2>/dev/null || true)" = "$armed" ]; then
    if [ -n "$retry" ] && [ "$tries" -lt 2 ]; then
        tmp=$(mktemp "$PENDING_DIR/.retry.XXXXXX" 2>/dev/null) \
            && { [ -z "$prompt_id" ] || printf 'prompt=%s\n' "$prompt_id"
                 printf 'tries=%s\n' $((tries + 1))
                 printf '%s\n' $retry; } > "$tmp" \
            && touch -r "$pending" "$tmp" && mv "$tmp" "$pending" \
            || { rm -f "$tmp" "$pending"; retry=""; }
    else
        rm -f "$pending"
        retry=""
    fi
fi

if [ -n "$blocked" ]; then
    {
        echo "A live peer session already holds work you are about to start."
        printf '%s\n' "$blocked"
        echo
        echo "Do not do this work twice. Say who holds it and stop, or ask your user"
        echo "whether to take it over (--steal warns the displaced session)."
        if [ -n "$claimed" ]; then
            echo
            echo "Claimed for you in the same step, and held by this session now: $(refs $claimed)."
            echo "If you stop, release each one: .agents/skills/carnet/carnet.sh release <n>"
        fi
        if [ -n "$failed" ]; then
            echo
            echo "Could NOT claim:$failed"
        fi
    } >&2
    exit 2
fi

msg=""
[ -z "$claimed" ] || msg="🔒 carnet auto-claimed:$claimed — held by this session now: release it if you stop, close it when the work lands."
if [ -n "$failed" ]; then
    [ -z "$msg" ] || msg="$msg
"
    msg="${msg}carnet: could NOT claim:$failed"
    if [ -n "$transient" ]; then
        # Not "is not held": a failed read says nothing either way, and this session may hold it
        # from an earlier claim.
        msg="$msg
$(refs $transient) is not confirmed as held by this session, so peers may see it as unclaimed. Claim it yourself before working on it: .agents/skills/carnet/carnet.sh claim <n>"
        if [ -n "$retry" ]; then msg="$msg
(the hook tries again on your next edit)"
        else msg="$msg
(the hook has stopped trying)"; fi
    fi
fi
[ -z "$msg" ] || tell "$msg"
exit 0

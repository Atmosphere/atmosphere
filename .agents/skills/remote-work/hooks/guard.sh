#!/usr/bin/env bash
# ABOUTME: PreToolUse guard — enforces the repo's remote-work policy on commands typed into Bash
# ABOUTME: Refuses a forced command locally, and a busy-sensitive permitted one while the machine is loaded
#
# SPDX-License-Identifier: MIT OR Apache-2.0
# Copyright (c) 2026 dravr.ai
#
# The policy is <repo>/.claude/remote-work.json (see ../policy.sh). A repo without one is not
# enforced. Only a command typed into Bash is judged: a script's children never reach the hook.
# It stands down in a cloud session, where the remote runner IS the work, and for
# REMOTE_WORK_LOCAL=1, the escape hatch used only when the project maintainer asked for a local run.

set -uo pipefail

# The off switches: REMOTE_WORK=off in the environment (every repo, set in a shell profile or for
# one session), or "enabled": false in the repo's git-ignored .claude/remote-work.local.json (one
# repo, one developer). Off means the guard allows everything locally; prepare still works when asked.
case "${REMOTE_WORK:-}" in off|0|false|no) exit 0 ;; esac
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] && exit 0
[ -n "${CLAUDE_CODE_REMOTE_SESSION_ID:-}" ] && exit 0
command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../policy.sh
. "$HERE/policy.sh"

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
policy=$(rw_policy "$root") || exit 0
[ "$(printf '%s' "$policy" | jq -r 'if .enabled == false then "off" else "on" end')" = "on" ] || exit 0
# No cloud environment for this developer's account yet (it lives in their git-ignored local file):
# nothing could run the command remotely, so refusing it locally would leave them with neither.
[ -n "${REMOTE_WORK_ENV:-}" ] || [ -n "$(printf '%s' "$policy" | jq -r '.remote.environment_id // empty')" ] || exit 0

bare=$(rw_bare "$cmd")
printf '%s' "$bare" | grep -Eq '(^|[;&|(])[[:space:]]*([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*REMOTE_WORK_LOCAL=1[[:space:]]' && exit 0

# Forced is judged first here: prohibited only decides what may be SENT, so a forced command
# chained to a prohibited one (`cargo run && cargo clippy`) is still refused locally.
if entry=$(rw_first_match "$policy" forced "$bare"); then
    class=forced
else
    IFS=$'\t' read -r class entry < <(rw_classify "$policy" "$cmd")
fi
name=$(printf '%s' "$entry" | jq -r '.name // .match // .pattern // empty')
why=$(printf '%s' "$entry" | jq -r '.why // empty')

hint() {
    cat >&2 <<EOF
  .claude/skills/remote-work/remote-work.sh prepare --cmd "<the command>"
  then RemoteTrigger create with the body it prints, and read the verdict with get_run_log.
  Policy: .claude/remote-work.json. Only when the project maintainer asked for a local run: REMOTE_WORK_LOCAL=1 <command>
  Off for everything: REMOTE_WORK=off in the environment, or {"enabled": false} in .claude/remote-work.local.json
EOF
}

case "$class" in
    forced)
        echo "BLOCKED: '$name' runs in a cloud session on this repo, not on this machine.${why:+ ($why)}" >&2
        hint
        exit 2
        ;;
    permitted)
        [ "$(printf '%s' "$entry" | jq -r '.remote_when_busy // false')" = "true" ] || exit 0
        if reason=$(rw_busy "$policy"); then
            echo "BLOCKED: '$name' goes to a cloud session while this machine is busy: $reason." >&2
            hint
            exit 2
        fi
        ;;
esac
exit 0

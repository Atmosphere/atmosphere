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
# ABOUTME: SessionEnd hook — releases every carnet issue the ending session still holds
# ABOUTME: Reads the session's local ledger, so it costs nothing when the session claimed nothing
#
# Wire it in .claude/settings.json:
#   "SessionEnd": [{ "hooks": [{ "type": "command", "timeout": 30,
#     "command": "[ -f \"$CLAUDE_PROJECT_DIR/.agents/skills/carnet/hooks/session-end-release.sh\" ] && bash \"$CLAUDE_PROJECT_DIR/.agents/skills/carnet/hooks/session-end-release.sh\" || true" }]}]
#
# A dead session cannot be working, so its claims go: label and assignee off, a release marker
# saying "session-ended". A resumed session re-claims on its next mention of the issue (the
# prompt hook shows it as unclaimed). A kill -9 skips this hook; the liveness check in
# `carnet.sh claim` then reads the claim as stale and takes it over.
set -uo pipefail
umask 077

here=$(cd "$(dirname "$0")" && pwd)
carnet="$here/../carnet.sh"
[ -f "$carnet" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

payload=$(cat 2>/dev/null || true)
sid=$(printf '%s' "$payload" | jq -r '.session_id // empty' 2>/dev/null || true)
[ -n "$sid" ] || sid=${CLAUDE_CODE_SESSION_ID:-}
[ -n "$sid" ] || exit 0
# The id becomes a file name below, and one of them is removed: nothing but a UUID's characters.
case $sid in *[!A-Za-z0-9-]*) exit 0 ;; esac

claims="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/carnet-claims"
# The session's pending list and its warned list end with it. Only its own next write consumes
# a pending list, so a session that named an issue and never wrote would leave it behind — and
# one leftover list sends every write of every session past auto-claim's one-stat exit.
rm -f "$claims/pending/$sid.txt" "$claims/warned/$sid.txt" 2>/dev/null

ledger="$claims/$sid.jsonl"
[ -s "$ledger" ] || exit 0
# Nothing held, nothing to call: a ledger outlives its claims on its "filed" lines alone.
jq -e 'select(.kind == "claim")' "$ledger" >/dev/null 2>&1 || exit 0

# This project's register, not the one of wherever the session last cd'd: Claude Code runs the
# hook in the session's current directory, which can be another checkout by the end — naming
# another register, or none, and every claim would then stay held. Each ledger line names its
# own register, and `release --all` releases it there.
cd "${CLAUDE_PROJECT_DIR:-$here/../../../..}" 2>/dev/null || exit 0
# Each release takes the ledger lock once, after its tracker writes. A lock that stays held — its
# holder stuck, or its pid reused — must cost each release a few seconds, not this hook's whole
# timeout: every claim after the first would keep its label and assignee.
CARNET_LOCK_WAIT=5 bash "$carnet" release --all --session "$sid" --reason session-ended || true
exit 0

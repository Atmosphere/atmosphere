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

here=$(cd "$(dirname "$0")" && pwd)
carnet="$here/../carnet.sh"
[ -f "$carnet" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

payload=$(cat 2>/dev/null || true)
sid=$(printf '%s' "$payload" | jq -r '.session_id // empty' 2>/dev/null || true)
[ -n "$sid" ] || sid=${CLAUDE_CODE_SESSION_ID:-}
[ -n "$sid" ] || exit 0

ledger="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/carnet-claims/$sid.jsonl"
[ -s "$ledger" ] || exit 0

bash "$carnet" release --all --session "$sid" --reason session-ended || true
exit 0

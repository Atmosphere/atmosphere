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
# ABOUTME: SessionStart hook — records what was already dirty, then reports what a dead session left behind
# ABOUTME: The only cover for a kill -9: a dead session fires no exit hook and cannot report on itself
#
# Wire it in .claude/settings.json alongside the other SessionStart hooks.
#
# A clean exit is covered by carnet's SessionEnd release. Nothing fires when the process is
# killed, the terminal is closed, or the context runs out — and nothing inside the dead session
# can find it afterwards, so the session that opens after it looks for it instead.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
bilan="$here/../bilan.sh"
[ -f "$bilan" ] || exit 0

# Record which files were ALREADY dirty when this session opened. Several sessions can share one
# checkout, so a file a peer is mid-edit on would cap every other session at 7 — and those
# sessions are right to refuse to touch it, which used to mean they could never reach 10 no
# matter what they did. A path dirty before this session existed is definitionally not this
# session's work, and that IS machine-decidable. Anything that goes dirty later still caps.
#
# --if-missing: SessionStart fires again on resume and after a compaction, inside the same
# session. Re-recording there would declare the session's own uncommitted work inherited.
bash "$bilan" baseline --if-missing >/dev/null 2>&1 || true

out=$(bash "$bilan" sweep 2>/dev/null) || exit 0
printf '%s\n' "$out" | grep -q '✅ nothing left behind' && exit 0
printf '%s\n' "$out"
exit 0

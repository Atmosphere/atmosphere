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
# Writes <file>.sha256 next to <file>, as `<hash>  <basename>`: the line
# `shasum -a 256 -c` / `sha256sum -c` verify from the directory holding both.
#
# Hashing the absolute path instead (what the release workflow once did with
# `sha256sum "$GITHUB_WORKSPACE/atmosphere-X.zip"`) records the CI runner's
# path, and a user who downloads the zip and its .sha256 gets
# `FAILED open or read`. Proven by scripts/test-release-checksum.sh.
#
# Usage: scripts/write-release-checksum.sh <file>
# ---------------------------------------------------------------------------
set -euo pipefail

file="${1:?usage: write-release-checksum.sh <file>}"
[ -f "$file" ] || { echo "error: no such file: $file" >&2; exit 1; }

dir="$(cd "$(dirname "$file")" && pwd)"
name="$(basename "$file")"

if command -v sha256sum >/dev/null 2>&1; then
    hasher=(sha256sum)
else
    hasher=(shasum -a 256)
fi

(cd "$dir" && "${hasher[@]}" "$name") > "$dir/$name.sha256"
cat "$dir/$name.sha256"

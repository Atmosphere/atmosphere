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
# Proves the published atmosphere-X.Y.Z.zip.sha256 verifies the way a user
# checks it: both files downloaded into one directory, `shasum -a 256 -c` run
# there. Builds the checksum the way the release workflow does
# (scripts/write-release-checksum.sh, given the zip's absolute path from another
# working directory), then moves the pair somewhere else and verifies it.
#
# Usage: scripts/test-release-checksum.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WRITER="$ROOT/scripts/write-release-checksum.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; failures=$((failures + 1)); }

if command -v shasum >/dev/null 2>&1; then
    check=(shasum -a 256 -c)
else
    check=(sha256sum -c)
fi

# The runner's workspace, and an unrelated working directory to call it from.
mkdir -p "$WORK/runner/workspace" "$WORK/elsewhere" "$WORK/user-downloads"
zip="$WORK/runner/workspace/atmosphere-9.9.9.zip"
printf 'not really a zip, but bytes to hash\n' > "$zip"

if (cd "$WORK/elsewhere" && "$WRITER" "$zip" > /dev/null); then
    pass "the checksum writer runs"
else
    fail "the checksum writer runs"
fi

line="$(cat "$zip.sha256" 2>/dev/null)"
if printf '%s\n' "$line" | grep -Eq '^[0-9a-f]{64}  atmosphere-9\.9\.9\.zip$'; then
    pass "the .sha256 names the zip by its bare file name"
else
    fail "the .sha256 must be '<hash>  atmosphere-9.9.9.zip'; got: $line"
fi

case "$line" in
    *"$WORK"*) fail "the .sha256 must not carry the build machine's path; got: $line" ;;
    *)         pass "the .sha256 carries no build-machine path" ;;
esac

# The user's view: both files downloaded into one directory, the runner gone.
cp "$zip" "$zip.sha256" "$WORK/user-downloads/"
rm -rf "$WORK/runner"
if out="$(cd "$WORK/user-downloads" && "${check[@]}" atmosphere-9.9.9.zip.sha256 2>&1)"; then
    pass "'${check[*]}' verifies the downloaded pair ($out)"
else
    fail "'${check[*]}' must verify the downloaded pair; got: $out"
fi

# A tampered download must still be caught.
printf 'tampered\n' >> "$WORK/user-downloads/atmosphere-9.9.9.zip"
if (cd "$WORK/user-downloads" && "${check[@]}" atmosphere-9.9.9.zip.sha256 > /dev/null 2>&1); then
    fail "a tampered zip must fail the check"
else
    pass "a tampered zip fails the check"
fi

if [ "$failures" -ne 0 ]; then
    echo "test-release-checksum: $failures failure(s)"
    exit 1
fi
echo "test-release-checksum: all checks passed"

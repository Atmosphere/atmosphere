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
# Proves scripts/sync-console-bundle.sh --check bites on the atmosphere.js
# version a committed Console copy sends. The fingerprint masks package.json's
# version, so Quarkus and Spring Boot 3 copies synced before the 1c5410bd17 bump
# shipped sending the previous version while the source-built SB4 console sent
# the bumped one, and --check passed. Each case builds a
# throwaway tree with the real script, a fake SPA source and the committed
# copies, and asserts the exit code AND the copy and versions the check names.
# Also proves --stamp-version brings stale copies to package.json's version.
#
# The fixture versions (7.7.1, 7.7.2) are deliberately not real atmosphere.js
# versions. SYNC_SCRIPT names another script to test (the markers are always
# written by this checkout's, so an older script is judged on its checks alone).

set -uo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
UNDER_TEST="${SYNC_SCRIPT:-$REPO/scripts/sync-console-bundle.sh}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASSED=0
FAILED=0

# The committed copies, read from the script so the fixture follows it.
DESTS=()
while IFS= read -r dest; do
    DESTS+=("$dest")
done < <(sed -n '/^DEST_DIRS=(/,/^)/p' scripts/sync-console-bundle.sh | grep -oE '"[^"]+"' | tr -d '"')
if [ "${#DESTS[@]}" -lt 2 ]; then
    echo "FAIL: could not read DEST_DIRS from scripts/sync-console-bundle.sh"
    exit 1
fi

# bundle_js <version>: a minified chunk shaped like the real one, which sends
# its VERSION constant as X-Atmosphere-Framework.
bundle_js() {
    printf '%s' 'var x=1,ss=`'"$1"'`,cs=class e{u(){o.push(`X-Atmosphere-Framework=${encodeURIComponent(ss)}`)}};'
}

# make_tree <dir> <package-version> <copy-version>: every copy sends <copy-version>.
make_tree() {
    local d="$1" pkg="$2" copy="$3" dest
    mkdir -p "$d/scripts" "$d/modules/spring-boot-starter/frontend/src" "$d/atmosphere.js/src"
    cp "$UNDER_TEST" "$d/scripts/sync-console-bundle.sh"
    cp "$REPO/scripts/sync-console-bundle.sh" "$d/scripts/fingerprint.sh"
    printf 'export const a = 1;\n' > "$d/modules/spring-boot-starter/frontend/src/main.ts"
    printf 'export const VERSION: string = __ATMOSPHERE_VERSION__;\n' > "$d/atmosphere.js/src/version.ts"
    printf '{\n  "name": "atmosphere.js",\n  "version": "%s"\n}\n' "$pkg" > "$d/atmosphere.js/package.json"
    for dest in "${DESTS[@]}"; do
        mkdir -p "$d/$dest/assets"
        printf '<script src="/atmosphere/console/assets/index-abc.js"></script>\n' > "$d/$dest/index.html"
        bundle_js "$copy" > "$d/$dest/assets/index-abc.js"
    done
    (cd "$d" && ./scripts/fingerprint.sh --fingerprint > scripts/console-bundle.fingerprint)
}

# expect <name> <dir> <args> <expected-exit> <expected-output-substring...>
expect() {
    local name="$1" d="$2" args="$3" want_rc="$4"
    shift 4
    local out rc missing=""
    out=$(cd "$d" && ./scripts/sync-console-bundle.sh $args 2>&1)
    rc=$?
    for want in "$@"; do
        if ! printf '%s' "$out" | grep -qF -- "$want"; then
            missing="$missing [$want]"
        fi
    done
    if [ "$rc" = "$want_rc" ] && [ -z "$missing" ]; then
        echo "PASS: $name"
        PASSED=$((PASSED + 1))
    else
        echo "FAIL: $name (exit $rc, want $want_rc; missing:${missing:- none})"
        printf '%s\n' "$out" | sed 's/^/    /'
        FAILED=$((FAILED + 1))
    fi
}

# 1. Every copy sends package.json's version: in sync.
d="$TMP/in-sync"
make_tree "$d" 7.7.2 7.7.2
expect "copies that send package.json's version pass" "$d" --check 0 "Console bundle in sync"

# 2. The shipped case: package.json moved on, every copy still sends the old version.
d="$TMP/stale"
make_tree "$d" 7.7.2 7.7.1
expect "copies that send an older version fail, each one named" "$d" --check 1 \
    "${DESTS[0]} sends atmosphere.js 7.7.1, package.json is 7.7.2" \
    "${DESTS[1]} sends atmosphere.js 7.7.1, package.json is 7.7.2"

# 3. --stamp-version brings them to package.json's version, and --check passes again.
expect "--stamp-version writes package.json's version into every copy" "$d" --stamp-version 0 \
    "Committed console bundles now send atmosphere.js 7.7.2"
expect "stamped copies pass --check" "$d" --check 0 "Console bundle in sync"
if grep -q '7\.7\.1' "$d/${DESTS[0]}/assets/index-abc.js"; then
    echo "FAIL: --stamp-version left the old version in ${DESTS[0]}"
    FAILED=$((FAILED + 1))
else
    echo "PASS: --stamp-version leaves no old version behind"
    PASSED=$((PASSED + 1))
fi

# 4. A copy whose VERSION constant cannot be found fails rather than passing silently.
d="$TMP/unreadable"
make_tree "$d" 7.7.2 7.7.2
for dest in "${DESTS[@]}"; do
    printf 'var nothing=1;\n' > "$d/$dest/assets/index-abc.js"
done
(cd "$d" && ./scripts/fingerprint.sh --fingerprint > scripts/console-bundle.fingerprint)
expect "a copy that sends no version it can read fails" "$d" --check 1 \
    "${DESTS[0]} sends atmosphere.js <no version found>, package.json is 7.7.2"

echo
echo "console bundle sync: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]

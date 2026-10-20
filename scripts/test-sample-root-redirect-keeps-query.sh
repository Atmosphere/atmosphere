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
# A sample's root sends the browser to the Atmosphere Console, and must keep
# the query string on the way: /?token=admin has to land on
# /atmosphere/console/?token=admin, or the Console opens without its token.
#
#  - Spring Boot samples map nothing at / (no view controller, no redirect
#    page): the starter's ConsoleRootRedirectFilter then redirects GET / with
#    the query kept. A view controller or a static index.html at / would answer
#    first and drop it.
#  - Every other root redirect page (WAR webapp, embedded Jetty, Quarkus
#    META-INF/resources) carries location.search to the Console itself; a bare
#    <meta http-equiv="refresh"> drops it.
#
# Usage: scripts/test-sample-root-redirect-keeps-query.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILTER="$ROOT/modules/spring-boot-starter/src/main/java/org/atmosphere/spring/boot/ConsoleRootRedirectFilter.java"

failures=0
checked=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; failures=$((failures + 1)); }

if grep -q 'getQueryString()' "$FILTER"; then
    pass "the starter's GET / redirect keeps the query string"
else
    fail "the starter's GET / redirect keeps the query string ($FILTER)"
fi

for sample_dir in "$ROOT"/samples/*/; do
    sample=${sample_dir%/}
    name=$(basename "$sample")
    [ -f "$sample/pom.xml" ] || continue
    spring_boot=false
    grep -q 'spring-boot-maven-plugin' "$sample/pom.xml" && spring_boot=true

    if $spring_boot && [ -d "$sample/src/main/java" ]; then
        mapped=$(grep -rlE 'ViewController\("/"' "$sample/src/main/java" 2>/dev/null)
        if [ -n "$mapped" ]; then
            fail "$name maps / with a view controller, which shadows the query-keeping redirect: $mapped"
        fi
    fi

    for page in "$sample/src/main/resources/static/index.html" \
                "$sample/src/main/webapp/index.html" \
                "$sample/src/main/resources/META-INF/resources/index.html"; do
        [ -f "$page" ] || continue
        grep -qiE 'http-equiv="refresh"|location\.(replace|href|assign)' "$page" || continue
        checked=$((checked + 1))
        rel=${page#"$ROOT"/}
        if $spring_boot && [ "${page#*static/}" != "$page" ]; then
            fail "$rel redirects /, shadowing the starter's query-keeping redirect: delete it"
        elif grep -q 'location\.search' "$page"; then
            pass "$rel carries the query string to the Console"
        else
            fail "$rel redirects to the Console without the query string"
        fi
    done
done

if [ "$checked" -eq 0 ]; then
    pass "no sample serves a root redirect page"
fi

if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures sample root redirect(s) drop the query string"
    exit 1
fi
echo "OK: every sample root redirect keeps the query string"

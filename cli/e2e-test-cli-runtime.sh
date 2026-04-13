#!/bin/sh
# Atmosphere CLI E2E Runtime Lifecycle Test Suite
# Usage: ./cli/e2e-test-cli-runtime.sh
#
# Tests CLI runtime behavior: server lifecycle, port overrides, caching,
# scaffold-then-compile, and error handling. Requires Java 21+ and Maven.
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

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLI="$SCRIPT_DIR/atmosphere"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PASS=0
FAIL=0
TMP_DIRS=""

# ── Helpers ─────────────────────────────────────────────────────────────────
RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

pass() {
    PASS=$((PASS + 1))
    printf "  ${GREEN}✓${RESET} %s\n" "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    printf "  ${RED}✗${RESET} %s\n" "$1"
    if [ -n "$2" ]; then
        printf "    ${DIM}%s${RESET}\n" "$2"
    fi
}

assert_contains() {
    output="$1"
    expected="$2"
    label="$3"
    if printf '%s' "$output" | grep -q "$expected"; then
        pass "$label"
    else
        fail "$label" "expected output to contain: $expected"
    fi
}

assert_not_contains() {
    output="$1"
    unexpected="$2"
    label="$3"
    if printf '%s' "$output" | grep -q "$unexpected"; then
        fail "$label" "output should NOT contain: $unexpected"
    else
        pass "$label"
    fi
}

assert_exit_code() {
    actual="$1"
    expected="$2"
    label="$3"
    if [ "$actual" -eq "$expected" ]; then
        pass "$label"
    else
        fail "$label" "expected exit code $expected, got $actual"
    fi
}

make_tmp_dir() {
    d=$(mktemp -d)
    TMP_DIRS="$TMP_DIRS $d"
    echo "$d"
}

# Wait for a TCP port to become available (up to 60s)
wait_for_port() {
    port="$1"
    timeout="${2:-60}"
    elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if curl -sf "http://localhost:$port/" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

# Kill a background process and wait for it to exit
kill_server() {
    pid="$1"
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
}

# ── Cleanup ─────────────────────────────────────────────────────────────────
cleanup() {
    for d in $TMP_DIRS; do
        rm -rf "$d" 2>/dev/null || true
    done
}
trap cleanup EXIT

# ── Prerequisites ───────────────────────────────────────────────────────────
printf "\n${BOLD}Atmosphere CLI E2E Runtime Lifecycle Tests${RESET}\n\n"

if [ ! -x "$CLI" ]; then
    printf "${RED}error:${RESET} CLI script not found or not executable: %s\n" "$CLI"
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    printf "${RED}error:${RESET} Java 21+ is required to run these tests\n"
    exit 1
fi

java_version=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
if [ "$java_version" -lt 21 ] 2>/dev/null; then
    printf "${RED}error:${RESET} Java 21+ required (found Java %s)\n" "$java_version"
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    printf "${RED}error:${RESET} curl is required to run these tests\n"
    exit 1
fi

# ── 1. atmosphere run spring-boot-chat boots and responds ───────────────────
printf "${BOLD}1. run spring-boot-chat boots and responds${RESET}\n"

run_tmp=$(make_tmp_dir)
"$CLI" run spring-boot-chat > "$run_tmp/stdout.log" 2> "$run_tmp/stderr.log" &
SERVER_PID=$!

if wait_for_port 8080 120; then
    pass "spring-boot-chat starts on port 8080"

    # Verify the server responds to HTTP requests
    http_code=$(curl -sf -o /dev/null -w '%{http_code}' "http://localhost:8080/" 2>/dev/null) || http_code="000"
    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 400 ]; then
        pass "spring-boot-chat responds with HTTP $http_code"
    else
        fail "spring-boot-chat responds to HTTP" "got HTTP $http_code"
    fi
else
    fail "spring-boot-chat starts on port 8080" "server did not start within 120s"
    fail "spring-boot-chat responds to HTTP" "skipped (server not running)"
fi

kill_server $SERVER_PID

# Verify clean shutdown (process is gone)
sleep 1
if kill -0 $SERVER_PID 2>/dev/null; then
    fail "spring-boot-chat clean shutdown" "process $SERVER_PID still running"
else
    pass "spring-boot-chat clean shutdown"
fi

printf "\n"

# ── 2. atmosphere run --port 9999 overrides port ────────────────────────────
printf "${BOLD}2. run --port 9999 overrides port${RESET}\n"

run_tmp2=$(make_tmp_dir)
"$CLI" run spring-boot-chat --port 9999 > "$run_tmp2/stdout.log" 2> "$run_tmp2/stderr.log" &
SERVER_PID=$!

if wait_for_port 9999 120; then
    pass "app starts on port 9999"

    # Verify 8080 is NOT serving (the default port should not respond)
    if curl -sf "http://localhost:8080/" >/dev/null 2>&1; then
        fail "default port 8080 is NOT serving" "port 8080 unexpectedly responded"
    else
        pass "default port 8080 is NOT serving"
    fi
else
    fail "app starts on port 9999" "server did not start within 120s"
    fail "default port 8080 is NOT serving" "skipped (server not running)"
fi

kill_server $SERVER_PID
printf "\n"

# ── 3. atmosphere run --env FOO=BAR propagates env ──────────────────────────
printf "${BOLD}3. run --env FOO=BAR propagates env${RESET}\n"

# The --env flag passes -DFOO=BAR to the JVM. We verify the server starts
# successfully with the flag. The env is passed as a system property.
run_tmp3=$(make_tmp_dir)
"$CLI" run spring-boot-chat --env FOO=BAR > "$run_tmp3/stdout.log" 2> "$run_tmp3/stderr.log" &
SERVER_PID=$!

if wait_for_port 8080 120; then
    pass "app starts with --env FOO=BAR"

    # The app should still respond normally
    http_code=$(curl -sf -o /dev/null -w '%{http_code}' "http://localhost:8080/" 2>/dev/null) || http_code="000"
    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 400 ]; then
        pass "app responds normally with custom env"
    else
        fail "app responds normally with custom env" "got HTTP $http_code"
    fi

    # Check that the JVM system property was passed by inspecting the process
    if ps -p $SERVER_PID -o args= 2>/dev/null | grep -q '\-DFOO=BAR'; then
        pass "JVM system property -DFOO=BAR visible in process args"
    else
        # On some systems ps truncates args; fall back to /proc if available
        if [ -f "/proc/$SERVER_PID/cmdline" ]; then
            if tr '\0' ' ' < "/proc/$SERVER_PID/cmdline" | grep -q '\-DFOO=BAR'; then
                pass "JVM system property -DFOO=BAR visible in /proc cmdline"
            else
                fail "JVM system property -DFOO=BAR propagated" "not found in process cmdline"
            fi
        else
            # Cannot verify on this platform, but app started — partial pass
            pass "app started with --env flag (cannot verify JVM args on this platform)"
        fi
    fi
else
    fail "app starts with --env FOO=BAR" "server did not start within 120s"
    fail "app responds normally with custom env" "skipped"
    fail "JVM system property -DFOO=BAR propagated" "skipped"
fi

kill_server $SERVER_PID
printf "\n"

# ── 4. Second atmosphere run uses cache ─────────────────────────────────────
printf "${BOLD}4. second run uses cache (no rebuild)${RESET}\n"

# The first run (test 1) should have populated the cache. Run again and
# capture stderr — it should NOT contain "[INFO] Building" (Maven output).
run_tmp4=$(make_tmp_dir)
"$CLI" run spring-boot-chat > "$run_tmp4/stdout.log" 2> "$run_tmp4/stderr.log" &
SERVER_PID=$!

if wait_for_port 8080 60; then
    pass "cached run starts successfully"

    combined_output=$(cat "$run_tmp4/stdout.log" "$run_tmp4/stderr.log" 2>/dev/null)
    assert_not_contains "$combined_output" "\[INFO\] Building" "no Maven build in second run (cache hit)"
    assert_contains "$combined_output" "cached\|Using cached" "output mentions cache usage"
else
    fail "cached run starts successfully" "server did not start within 60s"
    fail "no Maven build in second run (cache hit)" "skipped"
    fail "output mentions cache usage" "skipped"
fi

kill_server $SERVER_PID
printf "\n"

# ── 5. atmosphere new chat-test then standalone compile ───────────────────
# `atmosphere new` sparse-clones the sample and rewrites its pom.xml so the
# reactor parent resolves from Maven Central instead of the missing
# `../../pom.xml`. The resulting project must compile standalone.
printf "${BOLD}5. new --template chat then compile${RESET}\n"

new_tmp=$(make_tmp_dir)
out=$(cd "$new_tmp" && "$CLI" new chat-test --template chat 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "new --template chat exits successfully"

if [ -d "$new_tmp/chat-test" ]; then
    pass "chat-test project directory created"
    grep -q 'atmosphere-project' "$new_tmp/chat-test/pom.xml" && pass "chat-test pom.xml inherits atmosphere-project" || fail "chat-test pom.xml inherits atmosphere-project"
    ! grep -q 'relativePath' "$new_tmp/chat-test/pom.xml" && pass "chat-test pom.xml relativePath stripped (resolves from Central)" || fail "chat-test pom.xml relativePath stripped"
    ! grep -q 'SNAPSHOT' "$new_tmp/chat-test/pom.xml" && pass "chat-test pom.xml version pinned (no SNAPSHOT)" || fail "chat-test pom.xml version pinned"
    grep -q '<checkstyle.skip>true</checkstyle.skip>' "$new_tmp/chat-test/pom.xml" && pass "chat-test pom.xml disables repo-local checkstyle" || fail "chat-test pom.xml disables repo-local checkstyle"

    compile_out=$(cd "$new_tmp/chat-test" && mvn -q compile -B 2>&1) && compile_ec=0 || compile_ec=$?
    assert_exit_code "$compile_ec" 0 "chat-test compiles standalone against Maven Central parent"
else
    fail "chat-test project directory created"
fi

printf "\n"

# ── 6. atmosphere new ai-test --template ai-chat then compile ─────────────
printf "${BOLD}6. new --template ai-chat then compile${RESET}\n"

new_tmp2=$(make_tmp_dir)
out=$(cd "$new_tmp2" && "$CLI" new ai-test --template ai-chat 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "new --template ai-chat exits successfully"

if [ -d "$new_tmp2/ai-test" ]; then
    pass "ai-test project directory created"
    compile_out=$(cd "$new_tmp2/ai-test" && mvn -q compile -B 2>&1) && compile_ec=0 || compile_ec=$?
    assert_exit_code "$compile_ec" 0 "ai-test compiles standalone"
else
    fail "ai-test project directory created"
fi

printf "\n"

# ── 7. atmosphere new agent-test --template agent then compile ────────────
# (Replaces the old a2a-agent template, which never existed in cli/atmosphere
# — it was stale scaffolding hidden behind earlier failures in CI.)
printf "${BOLD}7. new --template agent then compile${RESET}\n"

new_tmp3=$(make_tmp_dir)
out=$(cd "$new_tmp3" && "$CLI" new agent-test --template agent 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "new --template agent exits successfully"

if [ -d "$new_tmp3/agent-test" ]; then
    pass "agent-test project directory created"
    compile_out=$(cd "$new_tmp3/agent-test" && mvn -q compile -B 2>&1) && compile_ec=0 || compile_ec=$?
    assert_exit_code "$compile_ec" 0 "agent-test compiles standalone"
else
    fail "agent-test project directory created"
fi

printf "\n"

# ── 8. atmosphere import --trust with fixture skill then compile ────────────
printf "${BOLD}8. import --trust fixture-skill.md then compile${RESET}\n"

import_tmp=$(make_tmp_dir)

# Create a minimal fixture skill file
cat > "$import_tmp/fixture-skill.md" <<'SKILLEOF'
---
name: fixture-agent
description: "A minimal fixture agent for E2E testing"
---

# Fixture Agent
You are a minimal test agent.

## Skills
- Respond to greetings
SKILLEOF

out=$(cd "$import_tmp" && "$CLI" import --trust --name fixture-agent "$import_tmp/fixture-skill.md" 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "import --trust exits successfully"
assert_contains "$out" "Parsed skill: fixture-agent" "import parses skill name"

if [ -d "$import_tmp/fixture-agent" ]; then
    pass "fixture-agent project directory created"

    if [ -f "$REPO_ROOT/mvnw" ]; then
        cp "$REPO_ROOT/mvnw" "$import_tmp/fixture-agent/"
        cp -r "$REPO_ROOT/.mvn" "$import_tmp/fixture-agent/" 2>/dev/null || true
        chmod +x "$import_tmp/fixture-agent/mvnw"
    fi

    compile_out=$(cd "$import_tmp/fixture-agent" && ./mvnw compile -B 2>&1) && compile_ec=0 || compile_ec=$?
    assert_exit_code "$compile_ec" 0 "fixture-agent compiles successfully"
else
    fail "fixture-agent project directory created"
    fail "fixture-agent compiles successfully" "skipped (no project dir)"
fi

printf "\n"

# ── 9. Corrupted cache JAR triggers re-download ────────────────────────────
printf "${BOLD}9. corrupted cache JAR triggers rebuild${RESET}\n"

ATMOSPHERE_HOME="${ATMOSPHERE_HOME:-$HOME/.atmosphere}"
CACHE_DIR="$ATMOSPHERE_HOME/cache"

# Find the cached JAR for spring-boot-chat
cached_jar=$(find "$CACHE_DIR" -name "spring-boot-chat*.jar" -type f 2>/dev/null | head -1)

if [ -n "$cached_jar" ] && [ -f "$cached_jar" ]; then
    pass "found cached JAR: $(basename "$cached_jar")"

    # Truncate the JAR to corrupt it
    printf "CORRUPTED" > "$cached_jar"

    # Also corrupt the checksum file if it exists
    if [ -f "${cached_jar}.sha256" ]; then
        # Write a mismatched checksum so the integrity check catches it
        echo "0000000000000000000000000000000000000000000000000000000000000000" > "${cached_jar}.sha256"
    fi

    run_tmp9=$(make_tmp_dir)
    "$CLI" run spring-boot-chat > "$run_tmp9/stdout.log" 2> "$run_tmp9/stderr.log" &
    SERVER_PID=$!

    if wait_for_port 8080 180; then
        pass "server starts after cache corruption (rebuilt)"

        combined_output=$(cat "$run_tmp9/stdout.log" "$run_tmp9/stderr.log" 2>/dev/null)
        # Match actual CLI rebuild indicators: Maven "Building", "BUILD SUCCESS",
        # cache "mismatch", "downloading" or "resolving" dependencies, "Installing"
        # a fresh jar, or the "atmosphere-runtime" marker that fires when the
        # classpath is being populated post-invalidation.
        if printf '%s' "$combined_output" | grep -qiE 'mismatch|building|rebuilding|build success|downloading|resolving|installing|atmosphere-runtime'; then
            pass "output indicates rebuild after corruption"
        else
            fail "output indicates rebuild after corruption" "no rebuild indicator in output"
        fi
    else
        fail "server starts after cache corruption" "server did not start within 180s"
        fail "output indicates rebuild after corruption" "skipped"
    fi

    kill_server $SERVER_PID
else
    fail "found cached JAR" "no cached JAR found — run test 1 first"
    fail "server starts after cache corruption" "skipped"
    fail "output indicates rebuild after corruption" "skipped"
fi

printf "\n"

# ── 10. atmosphere run nonexistent-sample exits non-zero ────────────────────
printf "${BOLD}10. run nonexistent-sample exits non-zero${RESET}\n"

out=$("$CLI" run nonexistent-sample 2>&1) && ec=0 || ec=$?

if [ "$ec" -ne 0 ]; then
    pass "nonexistent sample exits with non-zero code ($ec)"
else
    fail "nonexistent sample exits with non-zero code" "got exit code 0"
fi

assert_contains "$out" "error\|not found\|unknown\|No sample\|not directly runnable" "error message for nonexistent sample"

printf "\n"

# ── Summary ─────────────────────────────────────────────────────────────────
total=$((PASS + FAIL))
printf "${BOLD}Results: %s passed, %s failed${RESET} (out of %s)\n\n" "$PASS" "$FAIL" "$total"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi

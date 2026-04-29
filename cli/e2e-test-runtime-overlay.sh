#!/bin/sh
# Boot-and-assert e2e for the `atmosphere new --runtime <X>` overlay.
#
# For each runtime in the matrix this script:
#   1. Scaffolds spring-boot-ai-chat with `--runtime <X>` (CLI overlay
#      injects the matching adapter dep into pom.xml).
#   2. Boots the resulting project on a private port.
#   3. Hits GET /api/admin/runtimes/active and asserts that
#      AgentRuntime.name() matches the requested overlay — proving the
#      SPI swap actually picked up the adapter at runtime, not just at
#      compile time.
#
# Requires:
#   - mvn (or mvnw — we assume the repo's mvnw is on $PATH or use ./mvnw).
#   - Atmosphere reactor pre-installed in ~/.m2 at the SNAPSHOT version
#     pointed to by ATMOSPHERE_VERSION_OVERRIDE; otherwise the scaffolded
#     project pins to the released VERSION baked into cli/atmosphere.
#   - Network: the scaffolded project pulls Spring AI / LangChain4j /
#     etc. from Central + repo.spring.io.
#
# Copyright 2008-2026 Async-IO.org — Apache License 2.0

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLI="$SCRIPT_DIR/atmosphere"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PASS=0
FAIL=0
TMP_DIR=$(mktemp -d)
SERVER_PIDS=""

cleanup() {
    for pid in $SERVER_PIDS; do
        # SIGTERM first; -KILL only if it ignores us. Spring Boot wires
        # a JVM shutdown hook so SIGTERM is enough in the happy path.
        kill -TERM "$pid" 2>/dev/null || true
    done
    sleep 2
    for pid in $SERVER_PIDS; do
        kill -KILL "$pid" 2>/dev/null || true
    done
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

pass() { PASS=$((PASS + 1)); printf "  ${GREEN}✓${RESET} %s\n" "$1"; }
fail() {
    FAIL=$((FAIL + 1))
    printf "  ${RED}✗${RESET} %s\n" "$1"
    if [ -n "$2" ]; then
        printf "    ${DIM}%s${RESET}\n" "$2"
    fi
    return 0
}

# Wait until the admin runtime endpoint answers 200. ai-chat boots in
# ~25s on a warm runner; cap at 300s because Spring AI 2.0.0-M2 + LC4j
# 1.x cold-fetch from repo.spring.io / Central can chew through the
# first couple of minutes on an empty runner cache.
wait_for_admin_endpoint() {
    port="$1"
    deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -sf "http://localhost:$port/api/admin/runtimes/active" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    return 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || {
        printf "${RED}error:${RESET} '%s' is required but not on PATH\n" "$1" >&2
        exit 2
    }
}

require_cmd jq
require_cmd curl
if command -v mvn >/dev/null 2>&1; then
    MVN_CMD="mvn"
elif [ -x "$REPO_ROOT/mvnw" ]; then
    MVN_CMD="$REPO_ROOT/mvnw"
else
    printf "${RED}error:${RESET} neither mvn nor ./mvnw found\n" >&2
    exit 2
fi

# Test one runtime overlay end-to-end.
#
# Args:
#   $1 — overlay name (passed to --runtime)
#   $2 — expected AgentRuntime.name() value
#   $3 — server port (each invocation gets a unique port to allow parallelism)
#   $4 — extra mvn args (e.g. -Pspring-boot3 for embabel)
#   $5 — template (default: "ai-chat") — set to "ai-tools" for the
#        force-swap variant where the sample pre-pins a different adapter
#   $6 — extra `atmosphere new` flags (e.g. "--force") — used by the
#        force-swap test to wipe pre-pinned adapters before injecting
#   $7 — artifactId that MUST be absent after the overlay applies
#        (e.g. atmosphere-langchain4j when force-swapping ai-tools to
#        spring-ai). Empty = skip the absence assertion.
test_runtime() {
    rt="$1"
    expected_name="$2"
    port="$3"
    extra_mvn_args="$4"
    template="${5:-ai-chat}"
    extra_new_args="$6"
    must_not_contain="$7"

    # Use full if-statements rather than `[ … ] && …` so an empty
    # extra_new_args doesn't return non-zero and trip set -e.
    label="$rt"
    if [ -n "$extra_new_args" ]; then
        label="$rt ($extra_new_args on $template)"
    fi
    printf "\n${BOLD}── Runtime: %s (port %s) ──${RESET}\n" "$label" "$port"

    # Scaffold into TMP_DIR. `atmosphere new` refuses to overwrite, so we
    # cd to TMP_DIR for a clean parent.
    #
    # When a force-swap test reuses an overlay name (e.g. spring-ai on
    # ai-tools after the same overlay was already exercised on ai-chat),
    # the project directory would clash; suffix when extra_new_args is set.
    proj="rt-$rt"
    if [ -n "$extra_new_args" ]; then
        proj="rt-$rt-force"
    fi
    # shellcheck disable=SC2086
    out=$(cd "$TMP_DIR" && "$CLI" new "$proj" --template "$template" --runtime "$rt" $extra_new_args 2>&1) && ec=0 || ec=$?
    if [ "$ec" -ne 0 ]; then
        fail "$label: scaffold failed" "exit=$ec, output: $(printf '%s' "$out" | tail -3)"
        return 0
    fi
    pass "$label: scaffold completed"

    proj_dir="$TMP_DIR/$proj"

    # Fail-closed: assert the overlay actually injected the adapter
    # dependency into the pom before we waste time booting.
    case "$rt" in
        builtin) expected_artifact="atmosphere-ai" ;;
        *)       expected_artifact="atmosphere-$rt" ;;
    esac
    if grep -q "<artifactId>$expected_artifact</artifactId>" "$proj_dir/pom.xml"; then
        pass "$label: pom.xml contains $expected_artifact"
    else
        fail "$label: pom.xml missing $expected_artifact"
        return 0
    fi

    # Force-swap: assert the previously-pinned adapter has been stripped.
    # Without this, the resolver could land on the pre-pinned runtime via
    # ServiceLoader iteration order on a priority-100 tie.
    if [ -n "$must_not_contain" ]; then
        if grep -q "<artifactId>$must_not_contain</artifactId>" "$proj_dir/pom.xml"; then
            fail "$label: pom.xml still contains stripped artifact $must_not_contain"
            return 0
        else
            pass "$label: stripped artifact $must_not_contain absent from pom.xml"
        fi
    fi

    # Pre-resolve dependencies so the boot timer doesn't have to absorb
    # cold-cache downloads from repo.spring.io / Maven Central. We still
    # cap the boot at 300s, but separating download from boot makes the
    # failure mode legible when the timer trips.
    resolve_log="$TMP_DIR/$proj.resolve.log"
    if ! (cd "$proj_dir" && $MVN_CMD -B -q $extra_mvn_args dependency:resolve >"$resolve_log" 2>&1); then
        fail "$label: mvn dependency:resolve failed"
        printf "    ${DIM}--- last 25 resolve-log lines ---${RESET}\n"
        tail -25 "$resolve_log" | sed 's/^/    /'
        return 0
    fi

    # Boot the server. Each runtime gets its own port + WebTransport
    # disabled (otherwise multiple instances clash on UDP/4443). Reads on
    # /api/admin/* don't require auth by default, so no token needed.
    #
    # LLM_MODE=remote + a non-blank LLM_API_KEY is required to dislodge
    # DemoAgentRuntime, which has Integer.MAX_VALUE priority and is the
    # SPI-resolved active runtime whenever AiConfig.apiKey() is blank.
    # The bogus key never leaves the JVM — no real LLM call fires unless
    # the test itself sends a chat message, which it doesn't.
    log="$TMP_DIR/$proj.log"
    (
        cd "$proj_dir" && \
        LLM_MODE=remote LLM_API_KEY=test-key-not-real \
        $MVN_CMD -B -q $extra_mvn_args spring-boot:run \
            -Dspring-boot.run.jvmArguments="-Dserver.port=$port -Datmosphere.web-transport.enabled=false" \
            > "$log" 2>&1
    ) &
    pid=$!
    SERVER_PIDS="$SERVER_PIDS $pid"

    if ! wait_for_admin_endpoint "$port"; then
        fail "$label: server did not boot within 300s"
        printf "    ${DIM}--- last 25 log lines ---${RESET}\n"
        tail -25 "$log" 2>/dev/null | sed 's/^/    /'
        kill -TERM "$pid" 2>/dev/null || true
        return 0
    fi
    pass "$label: server booted on port $port"

    body=$(curl -sf "http://localhost:$port/api/admin/runtimes/active") || {
        fail "$label: GET /api/admin/runtimes/active failed"
        kill -TERM "$pid" 2>/dev/null || true
        return 0
    }

    actual_name=$(printf '%s' "$body" | jq -r '.name')
    if [ "$actual_name" = "$expected_name" ]; then
        pass "$label: AgentRuntime.name() == \"$expected_name\""
    else
        fail "$label: expected name=\"$expected_name\", got \"$actual_name\"" "body: $body"
    fi

    actual_avail=$(printf '%s' "$body" | jq -r '.isAvailable')
    if [ "$actual_avail" = "true" ]; then
        pass "$label: runtime reports isAvailable=true"
    else
        fail "$label: expected isAvailable=true, got \"$actual_avail\""
    fi

    # SIGTERM and let the cleanup trap mop up if the JVM stalls.
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

printf "\n${BOLD}Atmosphere CLI --runtime overlay E2E${RESET}\n"
[ -n "$ATMOSPHERE_VERSION_OVERRIDE" ] && \
    printf "${DIM}ATMOSPHERE_VERSION_OVERRIDE=%s${RESET}\n" "$ATMOSPHERE_VERSION_OVERRIDE"

# Matrix: built-in is the fall-through case (no adapter on classpath),
# spring-ai and langchain4j cover the two transparent-swap paths most
# users hit, and semantic-kernel proves the SK runtime is 100% reachable
# via the overlay (the dedicated spring-boot-semantic-kernel-chat sample
# was removed in 8fc5968da9 once SK auto-config landed — the boot test
# below is the regression guard for that decision). Kotlin runtimes
# (embabel, koog) and ADK ship their own end-to-end integration tests
# in-tree; their overlay scaffolds are exercised by Tier 1 (test-cli.sh)
# at the `mvn compile` level.
test_runtime builtin         "built-in"        18801 ""
test_runtime spring-ai       "spring-ai"       18802 ""
test_runtime langchain4j     "langchain4j"     18803 ""
test_runtime semantic-kernel "semantic-kernel" 18804 ""

# Why no --force boot test:
# Samples that actually pre-pin a non-default adapter (ai-tools, rag) also
# have provider-specific Java imports (e.g. ai-tools wires
# OpenAiStreamingChatModel directly). `--force` correctly strips the
# adapter dep — the offline strip tests in test-cli.sh prove that — but
# the source then fails to compile against a different provider's API.
# That's a sample-design property, not a CLI bug; the right fix is
# either picking a transparent template or manually editing the imports
# after force-swap. The other candidate samples (multi-agent) have their
# adapter blocks XML-commented out and so default to the Built-in runtime,
# making a force-swap boot test a no-op. Coverage stays at the dep-strip
# level, where it's deterministic.

printf "\n${BOLD}Results: %s passed, %s failed${RESET} (out of %s)\n\n" \
    "$PASS" "$FAIL" "$((PASS + FAIL))"
[ "$FAIL" -gt 0 ] && exit 1
exit 0

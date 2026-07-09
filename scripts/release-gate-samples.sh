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

# release-gate-samples.sh — Release Gate: Samples E2E driver.
#
# Why this exists: a 2026-07 manual sweep of the sample matrix found failures
# CI never saw because they only exist at the PACKAGED-ARTIFACT level — a
# shaded jar missing its WS container and logback-core, a demo-mode config
# swallowing web messages, a compose file pinning a nonexistent image. Unit
# tests and dev-mode runs were green through all of it. This script boots
# every runnable sample the way a user does (java -jar the packaged boot /
# runner jar — NOT spring-boot:run / quarkus:dev) and asserts against that
# process: existing Playwright specs where they exist, an honest HTTP-200
# smoke where they don't.
#
# Driven by .github/workflows/release-gate-samples.yml (nightly + release
# precondition via workflow_call from release-4x.yml). Runs locally as-is
# after: ./mvnw install -DskipTests -Dgpg.skip=true
#
# Usage:
#   scripts/release-gate-samples.sh --list            # print the coverage map
#   scripts/release-gate-samples.sh --list-shards     # print shard names
#   scripts/release-gate-samples.sh --shard <name>    # run one CI shard
#   scripts/release-gate-samples.sh <sample>...       # run specific samples
#
# Keyless by design: LLM API keys are scrubbed (set to empty, which the
# framework treats as absent) so every sample exercises its demo/keyless
# path exactly like the existing keyless CI lanes (LLM_MODE=fake for the
# Playwright tier). Set RG_KEEP_KEYS=1 to keep the caller's keys.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${RG_LOG_DIR:-$ROOT/target/release-gate-samples}"
BOOT_TIMEOUT="${RG_BOOT_TIMEOUT:-180}"
MVNW="$ROOT/mvnw"

# ───────────────────────────────────────────────────────────────────────────
# COVERAGE MAP — one entry per directory under samples/ (shared-resources is
# a resource pack, not a sample). Tiers:
#
#   pw:boot:<projects>    Playwright project(s) in modules/integration-tests;
#                         the spec fixture (e2e/fixtures/sample-server.ts)
#                         boots the sample from its packaged Spring Boot jar
#                         (java -jar) — the artifact level this gate exists for.
#   pw:quarkus:<projects> Same, but the fixture boots target/quarkus-app/
#                         quarkus-run.jar.
#   pw:dev:<projects>     Playwright project(s) whose fixture boots via Maven
#                         (jetty:run / exec:java) because the sample has no
#                         runnable packaged artifact (WAR / plain jar). Honest
#                         label: these two are NOT packaged-artifact coverage.
#   fnd:<spec>|<port>|<probe>|<path>
#                         This script boots the packaged jar itself, waits for
#                         readiness, then runs the repo-root e2e/tests/<spec>
#                         against it (ATMO_E2E_BASE_URL). probe is GET or POST.
#   smoke:<port>|<path>   SMOKE tier — boot the packaged jar, assert the main
#                         endpoint returns HTTP 200, tear down by PID. These
#                         samples have NO dedicated Playwright spec today;
#                         each is a listed coverage gap, not a silent cap.
#   skip:<reason>         Excluded — reason must be printed, never silent.
#
# Scope note (visible, deliberate): the Playwright tier runs each sample's
# PRIMARY spec project(s). The feature-depth suites (18 ai-* projects against
# spring-boot-ai-chat, transport matrices against spring-boot-chat, …) keep
# running in e2e.yml on every push; duplicating them here would double the
# release wall-clock without adding packaged-artifact signal, because they
# boot the very same jars via the same fixture.
#
# SMOKE-ONLY samples (coverage gaps — no dedicated spec exists yet):
#   spring-boot-admin-bundle, spring-boot-passivation-agent,
#   spring-boot-ms-governance-chat
# EXCLUDED:
#   grpc-chat (runnable=false in cli/samples.json; grpc-browser.spec.ts
#   exercises the dual-transport test fixture, not this sample's artifact)
# DEV-MODE BOOT (no runnable packaged artifact):
#   chat (WAR, mvn jetty:run), embedded-jetty-websocket-chat (exec:java)
# ───────────────────────────────────────────────────────────────────────────
coverage_of() {
    case "$1" in
        chat)                                  echo "pw:dev:chat" ;;
        embedded-jetty-websocket-chat)         echo "pw:dev:embedded-jetty-chat" ;;
        grpc-chat)                             echo "skip:runnable=false in cli/samples.json; grpc-browser.spec.ts drives the dual-transport fixture, not this sample" ;;
        kotlin-dsl-chat)                       echo "fnd:kotlin-dsl-chat.spec.ts|8099|POST|/chat" ;;
        quarkus-ai-chat)                       echo "pw:quarkus:quarkus-ai-chat" ;;
        quarkus-chat)                          echo "pw:quarkus:quarkus-chat" ;;
        spring-boot-a2a-agent)                 echo "pw:boot:a2a-agent" ;;
        spring-boot-admin-bundle)              echo "smoke:8100|/atmosphere/admin/" ;;
        spring-boot-agui-chat)                 echo "pw:boot:agui-chat" ;;
        spring-boot-ai-chat)                   echo "pw:boot:ai-chat" ;;
        spring-boot-ai-classroom)              echo "pw:boot:spring-boot-ai-classroom" ;;
        spring-boot-ai-tools)                  echo "pw:boot:ai-tools" ;;
        spring-boot-browser-agent)             echo "pw:boot:browser-agent" ;;
        spring-boot-channels-chat)             echo "pw:boot:channels-chat" ;;
        spring-boot-chat)                      echo "pw:boot:spring-boot-chat" ;;
        spring-boot-checkpoint-agent)          echo "pw:boot:checkpoint-agent" ;;
        spring-boot-coding-agent)              echo "fnd:coding-agent.spec.ts|8111|GET|/actuator/health" ;;
        spring-boot-dentist-agent)             echo "pw:boot:dentist-agent" ;;
        spring-boot-durable-sessions)          echo "pw:boot:durable-sessions" ;;
        spring-boot-guarded-email-agent)       echo "fnd:guarded-email-agent.spec.ts|8112|GET|/atmosphere/console/" ;;
        spring-boot-mcp-server)                echo "pw:boot:mcp-server" ;;
        spring-boot-ms-governance-chat)        echo "smoke:8102|/atmosphere/console/" ;;
        spring-boot-multi-agent-startup-team)  echo "pw:boot:multi-agent-startup-team" ;;
        spring-boot-one-dep-agent)             echo "smoke:8101|/atmosphere/console/" ;;
        spring-boot-orchestration-demo)        echo "pw:boot:orchestration-primitives" ;;
        spring-boot-otel-chat)                 echo "pw:boot:otel-chat" ;;
        spring-boot-passivation-agent)         echo "smoke:8097|/atmosphere/console/" ;;
        spring-boot-personal-assistant)        echo "fnd:personal-assistant.spec.ts|8110|GET|/actuator/health" ;;
        spring-boot-rag-chat)                  echo "pw:boot:rag-chat" ;;
        spring-boot-reattach-harness)          echo "fnd:reattach.spec.ts|8096|GET|/atmosphere/agent/harness/" ;;
        spring-boot-spring-ai-advisors)        echo "fnd:spring-ai-advisors.spec.ts|8098|GET|/atmosphere/console/" ;;
        *)                                     echo "" ;;
    esac
}

# CI shard layout — every mapped (non-skip) sample appears in exactly one
# shard; verify_map enforces that no samples/ directory is left unmapped.
shard_samples() {
    case "$1" in
        core)         echo "chat embedded-jetty-websocket-chat spring-boot-chat quarkus-chat" ;;
        ai-core)      echo "spring-boot-ai-chat quarkus-ai-chat spring-boot-ai-classroom spring-boot-ai-tools" ;;
        ai-extra)     echo "spring-boot-rag-chat spring-boot-otel-chat spring-boot-channels-chat spring-boot-browser-agent" ;;
        agents)       echo "spring-boot-a2a-agent spring-boot-agui-chat spring-boot-multi-agent-startup-team spring-boot-dentist-agent" ;;
        coordination) echo "spring-boot-orchestration-demo spring-boot-checkpoint-agent spring-boot-durable-sessions spring-boot-mcp-server" ;;
        foundation)   echo "kotlin-dsl-chat spring-boot-spring-ai-advisors spring-boot-reattach-harness spring-boot-personal-assistant spring-boot-coding-agent spring-boot-guarded-email-agent" ;;
        smoke)        echo "spring-boot-admin-bundle spring-boot-passivation-agent spring-boot-ms-governance-chat spring-boot-one-dep-agent" ;;
        *)            echo "" ;;
    esac
}
SHARDS="core ai-core ai-extra agents coordination foundation smoke"

# ───────────────────────────────────────────────────────────────────────────
# Helpers
# ───────────────────────────────────────────────────────────────────────────

list_sample_dirs() {
    # samples/ is the source of truth for the sample count — never a memory.
    find "$ROOT/samples" -mindepth 1 -maxdepth 1 -type d ! -name shared-resources \
        -exec basename {} \; | sort
}

# Drift gate: a new sample directory MUST get a coverage-map entry (even a
# skip:<reason>) or the gate fails. This is what keeps the map from becoming
# a silent cap as samples are added.
verify_map() {
    local dir bad=0
    while IFS= read -r dir; do
        if [[ -z "$(coverage_of "$dir")" ]]; then
            echo "ERROR: samples/$dir has no coverage-map entry in $(basename "$0") — add pw:/fnd:/smoke: or skip:<reason>" >&2
            bad=1
        fi
    done < <(list_sample_dirs)
    # And every mapped, runnable sample must sit in exactly one shard.
    local shard s found
    while IFS= read -r dir; do
        [[ "$(coverage_of "$dir")" == skip:* ]] && continue
        found=0
        for shard in $SHARDS; do
            for s in $(shard_samples "$shard"); do
                [[ "$s" == "$dir" ]] && found=$((found + 1))
            done
        done
        if [[ "$found" -ne 1 ]]; then
            echo "ERROR: samples/$dir appears in $found shards (must be exactly 1)" >&2
            bad=1
        fi
    done < <(list_sample_dirs)
    return "$bad"
}

print_map() {
    echo "Release Gate: Samples E2E — coverage map"
    echo "========================================"
    local dir entry
    while IFS= read -r dir; do
        entry="$(coverage_of "$dir")"
        printf '  %-40s %s\n' "$dir" "${entry:-<UNMAPPED>}"
    done < <(list_sample_dirs)
    echo ""
    echo "Tiers: pw:boot/pw:quarkus = Playwright spec, fixture boots the packaged jar;"
    echo "       pw:dev = Playwright spec, Maven dev-mode boot (no runnable artifact);"
    echo "       fnd = this script boots the packaged jar + runs e2e/tests/<spec>;"
    echo "       smoke = packaged-jar boot + HTTP 200 only (NO spec exists — coverage gap);"
    echo "       skip = excluded, with reason."
}

# Ownership/terminal-path: every JVM this script spawns is killed by PID on
# every exit path (success, failure, signal). Never pkill — PID only.
PIDS=()
cleanup() {
    local pid
    for pid in "${PIDS[@]-}"; do
        [[ -n "$pid" ]] || continue
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            for _ in $(seq 1 15); do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            kill -9 "$pid" 2>/dev/null || true
        fi
        wait "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT

kill_pid() {
    local pid="$1"
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 15); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
}

find_boot_jar() {
    local dir="$ROOT/samples/$1/target"
    [[ -d "$dir" ]] || return 1
    # Exclude sources/javadoc/original-* (shade) and *-tests; newest wins.
    find "$dir" -maxdepth 1 -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
        ! -name 'original-*.jar' ! -name '*-tests.jar' 2>/dev/null \
        | sort -r | head -1
}

sample_jar() {
    local sample="$1" tier="$2"
    if [[ "$tier" == quarkus ]]; then
        echo "$ROOT/samples/$sample/target/quarkus-app/quarkus-run.jar"
    else
        find_boot_jar "$sample" || true
    fi
}

# Package the sample only when its artifact is missing — in CI the workflow's
# full-reactor install already produced every jar; locally this fills gaps.
ensure_packaged() {
    local sample="$1" kind="$2" jar
    jar="$(sample_jar "$sample" "$kind")"
    if [[ -n "$jar" && -f "$jar" ]]; then
        echo "[$sample] reusing packaged artifact: ${jar#"$ROOT"/}"
        return 0
    fi
    echo "[$sample] packaging (artifact missing)..."
    "$MVNW" -q -B package -pl "samples/$sample" -DskipTests -Dgpg.skip=true
    jar="$(sample_jar "$sample" "$kind")"
    if [[ -z "$jar" || ! -f "$jar" ]]; then
        echo "ERROR [$sample] no packaged artifact after mvn package" >&2
        return 1
    fi
    echo "[$sample] packaged: ${jar#"$ROOT"/}"
}

ensure_node_deps() {
    local dir="$1"
    if [[ ! -d "$dir/node_modules" ]]; then
        echo "[deps] npm ci in ${dir#"$ROOT"/}"
        (cd "$dir" && npm ci --no-audit --no-fund)
    fi
}

probe_once() {
    # probe_once <GET|POST> <url> — returns 0 when the endpoint answers 200.
    local method="$1" url="$2" status
    if [[ "$method" == POST ]]; then
        status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -d readiness-probe "$url" || true)"
    else
        status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" || true)"
    fi
    [[ "$status" == "200" ]]
}

# boot_jar <sample> <jar> <port> <probe-method> <probe-path> — boots the
# packaged artifact, waits for the probe to return 200, echoes the PID.
# kotlin-dsl-chat's shaded jar listens on a fixed port and takes no args;
# Spring Boot jars get --server.port so shards never collide.
boot_jar() {
    local sample="$1" jar="$2" port="$3" method="$4" path="$5"
    local url="http://127.0.0.1:${port}${path}"
    local log="$LOG_DIR/$sample.log"

    # Runtime truth: if something already answers here, a green probe proves
    # nothing about OUR boot — refuse a false pass (CI runners are clean).
    if curl -s -o /dev/null --max-time 5 "http://127.0.0.1:${port}/" 2>/dev/null; then
        echo "ERROR [$sample] port $port already answers before boot — refusing a false pass" >&2
        return 1
    fi

    local args=(-jar "$jar")
    if [[ "$sample" != kotlin-dsl-chat ]]; then
        args+=("--server.port=$port")
    fi
    echo "[$sample] booting from packaged artifact (log: ${log#"$ROOT"/})" >&2
    java "${args[@]}" >"$log" 2>&1 &
    local pid=$!
    PIDS+=("$pid")

    local deadline=$((SECONDS + BOOT_TIMEOUT))
    while ((SECONDS < deadline)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "ERROR [$sample] process exited before $url answered 200" >&2
            echo "--- [$sample] last 100 log lines ---" >&2
            tail -100 "$log" >&2 || true
            return 1
        fi
        if probe_once "$method" "$url"; then
            echo "[$sample] ready — $method $url returned 200" >&2
            echo "$pid"
            return 0
        fi
        sleep 2
    done
    echo "ERROR [$sample] $method $url did not return 200 within ${BOOT_TIMEOUT}s" >&2
    echo "--- [$sample] last 100 log lines ---" >&2
    tail -100 "$log" >&2 || true
    kill_pid "$pid"
    return 1
}

run_pw() {
    # Playwright tier — the integration-tests fixture packages nothing itself,
    # so guarantee the artifact first, then let the spec boot + assert.
    local sample="$1" kind="$2" projects="$3"
    if [[ "$kind" != dev ]]; then
        ensure_packaged "$sample" "$kind" || return 1
    else
        echo "[$sample] dev-mode boot (no runnable packaged artifact — see coverage map)"
    fi
    ensure_node_deps "$ROOT/modules/integration-tests"
    local args=() p
    IFS=',' read -ra parr <<< "$projects"
    for p in "${parr[@]}"; do args+=("--project=$p"); done
    echo "[$sample] playwright: ${args[*]}"
    (cd "$ROOT/modules/integration-tests" && \
        LLM_MODE="${LLM_MODE:-fake}" INCLUDE_FLAKY=false \
        npx playwright test "${args[@]}" --reporter=list)
}

run_fnd() {
    local sample="$1" spec="$2" port="$3" method="$4" path="$5"
    ensure_packaged "$sample" boot || return 1
    ensure_node_deps "$ROOT/e2e"
    local jar pid rc=0
    jar="$(sample_jar "$sample" boot)"
    pid="$(boot_jar "$sample" "$jar" "$port" "$method" "$path")" || return 1
    echo "[$sample] foundation spec: e2e/tests/$spec"
    (cd "$ROOT/e2e" && \
        ATMO_E2E_BASE_URL="http://127.0.0.1:$port" \
        npx playwright test "tests/$spec" --reporter=list) || rc=1
    kill_pid "$pid"
    return "$rc"
}

run_smoke() {
    local sample="$1" port="$2" path="$3"
    ensure_packaged "$sample" boot || return 1
    local jar pid
    jar="$(sample_jar "$sample" boot)"
    pid="$(boot_jar "$sample" "$jar" "$port" GET "$path")" || return 1
    echo "[$sample] SMOKE OK — packaged boot + GET $path == 200 (no spec exists: listed gap)"
    kill_pid "$pid"
    return 0
}

run_sample() {
    local sample="$1" entry tier rest
    entry="$(coverage_of "$sample")"
    if [[ -z "$entry" ]]; then
        echo "ERROR: unknown sample '$sample' (not in coverage map)" >&2
        return 1
    fi
    tier="${entry%%:*}"
    rest="${entry#*:}"
    case "$tier" in
        skip)
            echo "[$sample] EXCLUDED — $rest"
            return 0
            ;;
        pw)
            local kind="${rest%%:*}" projects="${rest#*:}"
            run_pw "$sample" "$kind" "$projects"
            ;;
        fnd)
            local spec port method path
            IFS='|' read -r spec port method path <<< "$rest"
            run_fnd "$sample" "$spec" "$port" "$method" "$path"
            ;;
        smoke)
            local port path
            IFS='|' read -r port path <<< "$rest"
            run_smoke "$sample" "$port" "$path"
            ;;
        *)
            echo "ERROR [$sample] unknown tier '$tier'" >&2
            return 1
            ;;
    esac
}

# ───────────────────────────────────────────────────────────────────────────
# Main
# ───────────────────────────────────────────────────────────────────────────

case "${1:-}" in
    --list)
        verify_map
        print_map
        exit 0
        ;;
    --list-shards)
        echo "$SHARDS"
        exit 0
        ;;
esac

TARGETS=()
if [[ "${1:-}" == "--shard" ]]; then
    [[ -n "${2:-}" ]] || { echo "ERROR: --shard requires a name (one of: $SHARDS)" >&2; exit 2; }
    read -ra TARGETS <<< "$(shard_samples "$2")"
    [[ ${#TARGETS[@]} -gt 0 ]] || { echo "ERROR: unknown shard '$2' (one of: $SHARDS)" >&2; exit 2; }
elif [[ $# -gt 0 ]]; then
    TARGETS=("$@")
else
    echo "Usage: $0 --list | --list-shards | --shard <name> | <sample>..." >&2
    exit 2
fi

verify_map
mkdir -p "$LOG_DIR"

# Keyless-first: scrub LLM keys so every sample takes its demo/keyless path,
# matching what the CI runners see (empty == absent to the key resolvers —
# same convention as scripts/sample-startup-smoke.sh). Fixture-level env
# (e.g. quarkus-ai-chat's dummy key) still applies on top.
if [[ "${RG_KEEP_KEYS:-0}" != "1" ]]; then
    export LLM_API_KEY='' OPENAI_API_KEY='' GEMINI_API_KEY='' ANTHROPIC_API_KEY='' COHERE_API_KEY=''
    echo "[env] LLM keys scrubbed (RG_KEEP_KEYS=1 to keep)"
fi
# The coding-agent sandbox spec needs Docker; skip it honestly when absent.
if ! docker info >/dev/null 2>&1; then
    export SKIP_SANDBOX_E2E=1
    echo "[env] Docker unavailable — SKIP_SANDBOX_E2E=1 (coding-agent sandbox specs will skip)"
fi

fail=0
FAILED=()
for sample in "${TARGETS[@]}"; do
    echo ""
    echo "════ $sample ════"
    if ! run_sample "$sample"; then
        fail=1
        FAILED+=("$sample")
        echo "[$sample] FAILED" >&2
    fi
done

echo ""
if ((fail != 0)); then
    echo "Release gate FAILED for: ${FAILED[*]}" >&2
    exit 1
fi
echo "Release gate passed for: ${TARGETS[*]}"

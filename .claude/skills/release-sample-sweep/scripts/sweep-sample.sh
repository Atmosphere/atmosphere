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
# sweep-sample.sh — boot ONE sample from its PACKAGED artifact on a sweep port,
# leave it running, and hand back the PID + log path so a human (or an agent
# driving chrome-devtools) can exercise it in a real browser.
#
# This is the manual counterpart to scripts/release-gate-samples.sh: that
# script boots + asserts + tears down in one shot for CI; this one boots and
# STAYS UP so the browser layer can be driven interactively.
#
# Deliberate properties:
#   * Packaged-artifact boot only (java -jar / quarkus-run.jar), never
#     spring-boot:run or quarkus:dev — the artifact level is where the
#     2026-06 and 2026-07 sweeps found their bugs.
#   * Refuses to start if something already answers on the port. A green
#     probe against someone else's process is a false pass (Invariant #5).
#   * Kills by PID only, never pkill. Every started JVM is recorded in a
#     pidfile so `stop` / `stop-all` are exact.
#   * Readiness = TCP listen + an HTTP probe with a hard timeout on a path
#     you choose. Never probe a suspended long-poll endpoint.
#
# Usage:
#   sweep-sample.sh start <sample> [--port N] [--ready-path /p] [--env K=V]...
#                                  [--jvm-arg -Dx=y]... [--timeout SECONDS]
#   sweep-sample.sh stop  <sample>
#   sweep-sample.sh stop-all
#   sweep-sample.sh status
#   sweep-sample.sh log   <sample> [lines]
#   sweep-sample.sh warnings <sample>     # WARN/ERROR/exception lines only
#
# Example:
#   sweep-sample.sh start spring-boot-ai-chat --port 9101 \
#       --ready-path /atmosphere/console/ \
#       --env LLM_MODE=local --env LLM_MODEL=qwen2.5:3b
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The skill lives at <repo>/.claude/skills/release-sample-sweep/scripts, so the
# repo root is four levels up. Prefer git so the script also works from a
# worktree checkout, and fall back to the relative walk if git is unavailable.
if ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" && [[ -n "$ROOT" ]]; then
    :
else
    ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
fi
STATE_DIR="${SWEEP_STATE_DIR:-$ROOT/target/sweep}"
MVNW="$ROOT/mvnw"

# Ambient LLM configuration is SCRUBBED from every sample we boot, mirroring
# scripts/release-gate-samples.sh (RG_KEEP_KEYS). A maintainer's shell profile
# routinely exports LLM_API_KEY / LLM_BASE_URL / LLM_MODEL, and the sweep must
# produce the same result on every machine.
#
# This is not hypothetical: the 2026-08-07 shakedown run inherited
# LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/ from the
# operator's profile, so samples launched with `--env LLM_MODE=local` called
# Gemini instead of Ollama. The framework was correct — an explicit base URL
# outranks the mode's default — but the sweep silently tested the wrong provider
# and the divergence was briefly misread as a framework defect.
#
# Note the ordering guarantee this relies on: `env -u FOO FOO=bar` unsets first
# and then applies the assignment, so an explicit --env always outranks a scrub.
# Set SWEEP_KEEP_ENV=1 to inherit the caller's values instead.
SCRUB_VARS=(LLM_API_KEY LLM_BASE_URL LLM_MODEL LLM_MODE
            OPENAI_API_KEY GEMINI_API_KEY GOOGLE_API_KEY ANTHROPIC_API_KEY
            COHERE_API_KEY AZURE_OPENAI_API_KEY)

mkdir -p "$STATE_DIR"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "[sweep] $*" >&2; }

# --- boot-type detection ----------------------------------------------------
# quarkus     target/quarkus-app/quarkus-run.jar exists
# war         pom.xml declares <packaging>war</packaging>  -> mvn jetty:run
# spring-boot boot jar (manifest carries Spring-Boot-Classes) -> --server.port
# main-jar    shaded jar with a plain Main-Class            -> -Dserver.port
boot_type() {
    local sample="$1" dir="$ROOT/samples/$1"
    [[ -d "$dir" ]] || die "no such sample: samples/$1"
    if [[ -f "$dir/target/quarkus-app/quarkus-run.jar" ]]; then
        echo quarkus; return
    fi
    if grep -q '<packaging>war</packaging>' "$dir/pom.xml" 2>/dev/null; then
        echo war; return
    fi
    local jar; jar="$(find_jar "$sample")" || jar=""
    if [[ -z "$jar" ]]; then
        # No jar at all is still an exec candidate; otherwise it is a build gap.
        if grep -q 'exec-maven-plugin' "$dir/pom.xml" 2>/dev/null; then
            echo exec; return
        fi
        die "no packaged jar in samples/$sample/target — run: ./mvnw package -pl samples/$sample -DskipTests"
    fi
    if unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -qi '^Spring-Boot-Classes'; then
        echo spring-boot; return
    fi
    if unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -qi '^Main-Class'; then
        echo main-jar; return
    fi
    # No runnable artifact: the sample is started through the exec plugin.
    # sample-matrix.md calls this boot type `exec` and grpc-chat uses it; before
    # this branch existed the script died here and the sample had to be booted
    # by hand, which also meant scrubbing the ambient LLM env by hand.
    if grep -q 'exec-maven-plugin' "$dir/pom.xml" 2>/dev/null; then
        echo exec; return
    fi
    die "samples/$sample: $(basename "$jar") has no Main-Class, is not a Spring Boot jar, and declares no exec-maven-plugin — it is not runnable (check cli/samples.json runnable flag)"
}

find_jar() {
    local dir="$ROOT/samples/$1/target"
    [[ -d "$dir" ]] || return 1
    local jar
    jar="$(find "$dir" -maxdepth 1 -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
        ! -name 'original-*.jar' ! -name '*-tests.jar' 2>/dev/null | sort -r | head -1)"
    [[ -n "$jar" ]] || return 1
    echo "$jar"
}

port_answers() {
    # 0 when something on this port completes a TCP connect.
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3<&- 3>&- && return 0
    return 1
}

http_ready() {
    # 0 when the path answers with any status below 500. --max-time keeps a
    # suspended long-poll from hanging the probe (it just reads as not-ready).
    local url="$1" code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || echo 000)"
    [[ "$code" != "000" && "$code" -lt 500 ]]
}

cmd_start() {
    local sample="${1:-}"; shift || true
    [[ -n "$sample" ]] || die "usage: $0 start <sample> [--port N] ..."

    # 300s, not 180s: on macOS the first load of the extracted SQLite JDBC
    # native library goes through Gatekeeper provenance evaluation, and
    # spring-boot-ai-chat was measured at 126s to Tomcat-up because of it
    # (a cold TMPDIR can be far worse). A timeout shorter than the slowest
    # legitimate boot turns a slow sample into a phantom failure.
    local port="" ready_path="/" timeout=300
    local -a envs=() jvm_args=()
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --port)       port="$2"; shift 2 ;;
            --ready-path) ready_path="$2"; shift 2 ;;
            --env)        envs+=("$2"); shift 2 ;;
            --jvm-arg)    jvm_args+=("$2"); shift 2 ;;
            --timeout)    timeout="$2"; shift 2 ;;
            *)            die "unknown flag: $1" ;;
        esac
    done
    [[ -n "$port" ]] || die "--port is required (use the sweep block 9101+, see references/sample-matrix.md)"

    local pidfile="$STATE_DIR/$sample.pid" log="$STATE_DIR/$sample.log"
    if [[ -f "$pidfile" ]]; then
        local old; old="$(cut -d' ' -f1 <"$pidfile")"
        kill -0 "$old" 2>/dev/null && die "$sample already running as PID $old (stop it first)"
        rm -f "$pidfile"
    fi
    # Runtime truth: a probe that goes green against a process we did not
    # start proves nothing about this build.
    port_answers "$port" && die "port $port already answers — pick another port; do NOT kill a process you did not start"

    local kind; kind="$(boot_type "$sample")"
    local dir="$ROOT/samples/$sample"
    info "$sample: boot type=$kind port=$port log=${log#"$ROOT"/}"

    # ${arr[@]+"${arr[@]}"} — expanding an EMPTY array as "${arr[@]}" aborts
    # under `set -u` on bash 3.2, which is still /bin/bash on macOS.
    local -a cmd=()
    case "$kind" in
        quarkus)
            envs+=("QUARKUS_HTTP_PORT=$port")
            cmd=(java ${jvm_args[@]+"${jvm_args[@]}"} -jar "$dir/target/quarkus-app/quarkus-run.jar") ;;
        spring-boot)
            cmd=(java ${jvm_args[@]+"${jvm_args[@]}"} -jar "$(find_jar "$sample")" "--server.port=$port") ;;
        main-jar)
            cmd=(java "-Dserver.port=$port" ${jvm_args[@]+"${jvm_args[@]}"} -jar "$(find_jar "$sample")") ;;
        war)
            cmd=("$MVNW" -B jetty:run "-Djetty.port=$port") ;;
        exec)
            # -Dhttp.port is what grpc-chat reads; -Dserver.port is passed too so
            # a differently-named property does not silently boot on the default
            # port and let a sweep drive the wrong process.
            # No -pl: the command runs with cwd already set to the sample
            # directory, exactly as the war branch above relies on.
            cmd=("$MVNW" -B exec:java "-Dhttp.port=$port" "-Dserver.port=$port" \
                 ${jvm_args[@]+"${jvm_args[@]}"}) ;;
    esac

    # Three things matter here, each learned the hard way:
    #  1. Redirect the whole subshell (not just the inner command) and detach
    #     stdin — if the child keeps the caller's stdout open, any pipeline
    #     reading this script (`… | tail`) never sees EOF and hangs forever.
    #  2. `exec` so the JVM REPLACES the subshell: without it $! is the
    #     subshell's PID and `stop` kills a shell while the server keeps
    #     listening (a leaked JVM the next boot then trips over).
    #  3. `set -m` puts the job in its own process group, so teardown can
    #     signal the whole group — macOS has no setsid, and the war/exec:java
    #     boots fork a second JVM under the Maven wrapper.
    local -a scrub=()
    if [[ "${SWEEP_KEEP_ENV:-0}" != "1" ]]; then
        local v
        for v in "${SCRUB_VARS[@]}"; do scrub+=(-u "$v"); done
        info "$sample: ambient LLM env scrubbed (SWEEP_KEEP_ENV=1 to inherit)"
    fi

    set -m
    ( cd "$dir" && exec env ${scrub[@]+"${scrub[@]}"} ${envs[@]+"${envs[@]}"} "${cmd[@]}" ) \
        >"$log" 2>&1 </dev/null &
    local pid=$!
    set +m
    echo "$pid $port $kind" >"$pidfile"

    local deadline=$((SECONDS + timeout))
    while ((SECONDS < deadline)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "--- last 60 log lines ---" >&2; tail -60 "$log" >&2 || true
            rm -f "$pidfile"
            die "$sample exited before becoming ready (see $log)"
        fi
        if port_answers "$port" && http_ready "http://127.0.0.1:$port$ready_path"; then
            info "$sample ready — http://localhost:$port$ready_path (PID $pid)"
            echo "$pid"
            return 0
        fi
        sleep 2
    done
    echo "--- last 60 log lines ---" >&2; tail -60 "$log" >&2 || true
    cmd_stop "$sample" >/dev/null 2>&1 || true
    die "$sample did not answer $ready_path within ${timeout}s"
}

kill_pid() {
    # Signal the process GROUP first (covers the Maven-wrapper boots that fork
    # a second JVM), then the leader. By PID/PGID only — never pkill -f, which
    # would reach processes this sweep does not own.
    local pid="$1"
    kill -0 "$pid" 2>/dev/null || return 0
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 15); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 1
    done
    kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
}

cmd_stop() {
    local sample="${1:?usage: $0 stop <sample>}"
    local pidfile="$STATE_DIR/$sample.pid"
    [[ -f "$pidfile" ]] || { info "$sample: not running (no pidfile)"; return 0; }
    local pid port; read -r pid port _ <"$pidfile"
    kill_pid "$pid"
    rm -f "$pidfile"
    # Terminal path: the port must actually be free before the next sample
    # claims it, or the next boot inherits a false pass.
    for _ in $(seq 1 10); do
        port_answers "$port" || { info "$sample stopped (PID $pid, port $port released)"; return 0; }
        sleep 1
    done
    die "$sample: PID $pid killed but port $port is still answering — investigate before continuing"
}

cmd_stop_all() {
    local f
    for f in "$STATE_DIR"/*.pid; do
        [[ -e "$f" ]] || continue
        cmd_stop "$(basename "$f" .pid)"
    done
}

cmd_status() {
    local f any=0
    for f in "$STATE_DIR"/*.pid; do
        [[ -e "$f" ]] || continue
        any=1
        local pid port kind; read -r pid port kind <"$f"
        if kill -0 "$pid" 2>/dev/null; then
            printf '  %-40s PID %-8s port %-6s %-12s RUNNING\n' "$(basename "$f" .pid)" "$pid" "$port" "$kind"
        else
            printf '  %-40s PID %-8s port %-6s %-12s DEAD (stale pidfile)\n' "$(basename "$f" .pid)" "$pid" "$port" "$kind"
        fi
    done
    [[ "$any" == 1 ]] || echo "  (nothing running)"
}

cmd_log() {
    local sample="${1:?usage: $0 log <sample> [lines]}" lines="${2:-120}"
    tail -"$lines" "$STATE_DIR/$sample.log"
}

cmd_warnings() {
    # The server-side half of the evidence: everything the sweep must record
    # alongside the browser console. Empty output is a real result — say so.
    local sample="${1:?usage: $0 warnings <sample>}"
    local log="$STATE_DIR/$sample.log"
    [[ -f "$log" ]] || die "no log for $sample"
    if ! grep -nE 'WARN|ERROR|SEVERE|Exception|Caused by|SLF4J' "$log"; then
        echo "(no WARN/ERROR/exception lines in $log)"
    fi
}

case "${1:-}" in
    start)    shift; cmd_start "$@" ;;
    stop)     shift; cmd_stop "$@" ;;
    stop-all) cmd_stop_all ;;
    status)   cmd_status ;;
    log)      shift; cmd_log "$@" ;;
    warnings) shift; cmd_warnings "$@" ;;
    *) sed -n '17,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac

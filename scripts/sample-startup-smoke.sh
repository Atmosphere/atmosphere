#!/usr/bin/env bash
#
# Sample-startup smoke test — boots the most dependency- and packaging-sensitive
# samples from their PACKAGED jars and asserts each is really healthy (HTTP 200
# for the AI samples; an accepted WebSocket upgrade plus a clean log for the
# shaded/BOM samples), then kills each JVM by PID.
#
# Why this exists: dependency bumps can compile and pass every unit test yet
# still break sample startup (7e04f919c0 bumped opentelemetry-api 1.62->1.63
# and quarkus-ai-chat died at boot with NoClassDefFoundError; the pin fix is
# f5aa862feb). "CI: Samples" builds and unit-tests samples but never boots
# one, so nothing gated that merge. This script IS the boot gate — CI runs it
# from ci.yml (sample-startup-smoke job) and it can be run locally as-is
# after: ./mvnw install -DskipTests -Dgpg.skip=true \
#            -pl samples/quarkus-ai-chat,samples/spring-boot-ai-chat,modules/quarkus-extension/deployment,modules/quarkus-admin-extension/deployment,modules/quarkus-grpc/deployment,modules/quarkus-langchain4j/deployment -am
# (the quarkus-*/deployment modules must be in the reactor: -am only follows
# dependencies, nothing depends on a deployment module, and without it the
# extension-descriptor goal validates against a stale/absent repo artifact)
#
# Keyless by design: both samples fall back to their demo LLM provider when
# no real API key is configured — startup is what we're gating, not LLM
# round-trips.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Seconds to wait for the HTTP endpoint before declaring the boot hung.
BOOT_TIMEOUT="${BOOT_TIMEOUT:-120}"
LOG_DIR="${SMOKE_LOG_DIR:-$ROOT/target/sample-startup-smoke}"
mkdir -p "$LOG_DIR"

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

# --- artifact selection: pinned to the current reactor version ---------------
#
# samples/*/target is never cleaned between builds, so it holds one jar per
# version ever packaged there. The previous selector here was a bare
# `find … | head -1` with NO sort at all — i.e. whatever the filesystem
# happened to return first. Measured on a working tree at 4.0.66-SNAPSHOT it
# picked atmosphere-spring-boot-ai-chat-4.0.64-SNAPSHOT.jar and
# atmosphere-jetty-embedded-websocket-4.0.63-SNAPSHOT.jar: this smoke gate was
# certifying the startup of two releases ago while reporting a pass for HEAD.
#
# Pin to the reactor version and fail loudly when it is absent. A smoke test
# that boots an arbitrary artifact proves nothing about the build under test
# (Invariant #5, runtime truth).
reactor_version() {
  # First <version> in the root pom is the project's own: atmosphere-project
  # declares no <parent>, and "<version>" is not a substring of "<modelVersion>".
  local v
  v="$(sed -n 's:^[[:space:]]*<version>\([^<]*\)</version>.*:\1:p' "$ROOT/pom.xml" 2>/dev/null | head -1)"
  if [[ ! "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
    echo "ERROR cannot read the reactor version from $ROOT/pom.xml (got '${v:-<empty>}')" >&2
    return 1
  fi
  echo "$v"
}
REACTOR_VERSION="$(reactor_version)"
echo "[smoke] reactor version: $REACTOR_VERSION — artifacts pinned to it"

# sample_jar <sample> — echoes the CURRENT-version boot jar, or explains
# exactly which stale artifacts were rejected and exits non-zero.
sample_jar() {
  local sample="$1" dir="$ROOT/samples/$1/target" jar
  # `original-*.jar` is the shade plugin's pre-shade copy and carries the same
  # version suffix, so excluding it is load-bearing, not cosmetic.
  jar="$(find "$dir" -maxdepth 1 -name "*-$REACTOR_VERSION.jar" \
    ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    ! -name '*-tests.jar' 2>/dev/null | sort | head -1)"
  if [[ -n "$jar" ]]; then
    echo "$jar"
    return 0
  fi
  {
    echo "ERROR [$sample] no $REACTOR_VERSION artifact in samples/$sample/target"
    local other
    other="$(find "$dir" -maxdepth 1 -name '*-[0-9]*.jar' ! -name 'original-*' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' 2>/dev/null | sort)"
    if [[ -n "$other" ]]; then
      echo "      stale artifacts present (deliberately NOT booted):"
      while IFS= read -r o; do [[ -n "$o" ]] && echo "        $(basename "$o")"; done <<<"$other"
    fi
    echo "      run: ./mvnw package -pl samples/$sample -DskipTests"
  } >&2
  return 1
}

# quarkus_app_is_current <sample> — quarkus-run.jar carries no version in its
# name, so a quarkus-app/ left behind by an earlier build boots silently and
# looks identical. The application jar Quarkus copies into quarkus-app/app/ is
# the only honest freshness signal.
quarkus_app_is_current() {
  local sample="$1" appdir="$ROOT/samples/$1/target/quarkus-app/app"
  if [[ -n "$(find "$appdir" -maxdepth 1 -name "*-$REACTOR_VERSION.jar" 2>/dev/null | head -1)" ]]; then
    return 0
  fi
  {
    echo "ERROR [$sample] target/quarkus-app is stale — no $REACTOR_VERSION application jar."
    echo "      present in quarkus-app/app/ (deliberately NOT booted):"
    find "$appdir" -maxdepth 1 -name '*.jar' 2>/dev/null \
      | while IFS= read -r j; do echo "        $(basename "$j")"; done
    echo "      run: ./mvnw package -pl samples/$sample -DskipTests"
  } >&2
  return 1
}

# boot_and_probe <name> <jar> <url> [ENV=VALUE...]
# Boots `java -jar <jar>` with the given env, polls <url> until it returns
# HTTP 200 or BOOT_TIMEOUT elapses, then tears the JVM down by PID.
boot_and_probe() {
  local name="$1" jar="$2" url="$3"
  shift 3

  if [[ ! -f "$jar" ]]; then
    echo "ERROR [$name] jar not found: $jar — run the package step first" >&2
    return 1
  fi

  # Runtime truth: if something already answers on this URL, a green probe
  # would prove nothing about OUR boot. Refuse to run instead of reporting
  # a false pass (CI runners are clean; this guards local/dev runs).
  if curl -s -o /dev/null --max-time 5 "$url" 2>/dev/null; then
    echo "ERROR [$name] $url already responds before boot — port in use, refusing a false pass" >&2
    return 1
  fi

  local log="$LOG_DIR/$name.log"
  echo "[$name] booting $jar (log: $log)"
  env "$@" java -jar "$jar" >"$log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")

  local status="" deadline=$((SECONDS + BOOT_TIMEOUT)) ok=1
  while ((SECONDS < deadline)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ERROR [$name] process exited before $url came up" >&2
      break
    fi
    status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" || true)"
    if [[ "$status" == "200" ]]; then
      ok=0
      break
    fi
    sleep 2
  done

  if ((ok == 0)); then
    echo "[$name] OK — $url returned 200"
  else
    echo "ERROR [$name] $url did not return 200 within ${BOOT_TIMEOUT}s (last status: ${status:-none})" >&2
    echo "--- [$name] last 100 log lines ---" >&2
    tail -100 "$log" >&2 || true
  fi

  # Kill this sample's JVM now (PID-scoped) so the next sample boots on a
  # clean slate; the EXIT trap remains the backstop for aborted runs.
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 15); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
  return "$ok"
}

# boot_ws_and_check <name> <jar> <port> <ws_path> [ENV=VALUE...]
# Boots a PACKAGED jar on <port> (port passed as a JVM system property via
# JAVA_TOOL_OPTIONS so it reaches plain-main and Spring Boot samples alike),
# waits until a WebSocket upgrade to <ws_path> is accepted (HTTP 101), lets a
# span-export window elapse, then asserts the log shows none of the packaging
# regressions that only surface in the shaded/BOM-resolved artifact:
#   * WS upgrade != 101 => the shade filter dropped the jakarta.websocket
#     ServerContainer and the endpoint answers 501 (kotlin-dsl-chat regression)
#   * SLF4J NOP fallback => the shade plugin bundled logback-classic without
#     logback-core, so logging silently no-ops (kotlin-dsl-chat regression)
#   * NoClassDefFoundError => a version-skewed/absent class, e.g. the OTLP
#     exporter's InstrumentationUtil under an OpenTelemetry BOM skew (otel-chat)
# "CI: Samples" compiles and unit-tests these but never boots the jar, and
# `spring-boot:run`/`exec:java` run from exploded classes, so neither catches a
# packaging break. This does. JVM is torn down by PID on every exit path.
boot_ws_and_check() {
  local name="$1" jar="$2" port="$3" ws_path="$4"
  shift 4

  if [[ ! -f "$jar" ]]; then
    echo "ERROR [$name] jar not found: $jar — run the package step first" >&2
    return 1
  fi

  local url="http://127.0.0.1:$port$ws_path"
  # Runtime truth: refuse a false pass if something already holds the port.
  if curl -s -o /dev/null --max-time 5 "http://127.0.0.1:$port/" 2>/dev/null; then
    echo "ERROR [$name] port $port already answers before boot — refusing a false pass" >&2
    return 1
  fi

  local log="$LOG_DIR/$name.log"
  echo "[$name] booting packaged jar $jar (log: $log)"
  env JAVA_TOOL_OPTIONS="-Dserver.port=$port" "$@" java -jar "$jar" >"$log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")

  local code="" deadline=$((SECONDS + BOOT_TIMEOUT)) ok=1
  while ((SECONDS < deadline)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ERROR [$name] process exited before the WebSocket endpoint came up" >&2
      break
    fi
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
      -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
      -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "$url" || true)"
    if [[ "$code" == "101" ]]; then
      ok=0
      break
    fi
    sleep 2
  done

  if ((ok == 0)); then
    echo "[$name] WebSocket upgrade accepted (101) at $ws_path"
    # Let a connect span reach the BatchSpanProcessor export window (catches a
    # BOM skew that only throws at export, not at SDK init).
    sleep 6
  else
    echo "ERROR [$name] WS upgrade to $ws_path not accepted within ${BOOT_TIMEOUT}s (last: ${code:-none}) — shade may have dropped the jakarta.websocket container" >&2
    tail -100 "$log" >&2 || true
  fi

  # Packaging-health log assertions (run even on a failed handshake so the log
  # names the real cause).
  if grep -qiE 'No SLF4J providers were found|NOP(Logger| .*logger)|Failed to load class .*StaticLoggerBinder' "$log"; then
    echo "ERROR [$name] SLF4J fell back to NOP — the shade plugin dropped a logging backend (logback-core?)" >&2
    ok=1
  fi
  if grep -qE 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError' "$log"; then
    echo "ERROR [$name] a class is missing/skewed in the packaged jar:" >&2
    grep -E 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError' "$log" | head -3 >&2
    ok=1
  fi

  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 15); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
  return "$ok"
}

fail=0

# quarkus-ai-chat: the sample the opentelemetry bump broke. Env mirrors the
# e2e fixture (modules/integration-tests/e2e/fixtures/sample-server.ts) — a
# dummy key lets the Quarkus LangChain4j StreamingChatModel bean materialise
# without a real provider. GET / serves the chat page (META-INF/resources).
if quarkus_app_is_current quarkus-ai-chat; then
  boot_and_probe quarkus-ai-chat \
    "$ROOT/samples/quarkus-ai-chat/target/quarkus-app/quarkus-run.jar" \
    "http://127.0.0.1:18810/" \
    LLM_API_KEY=dummy-not-real QUARKUS_HTTP_PORT=18810 || fail=1
else
  fail=1
fi

# spring-boot-ai-chat: Spring Boot startup canary. Empty LLM_API_KEY forces
# the keyless demo provider regardless of the caller's shell env, matching
# what CI runners see. GET / is a 302 to the bundled Atmosphere Console
# (AiChatApplication.addRedirectViewController), so probe the console
# directly — it's the sample's real UI.
if SPRING_JAR="$(sample_jar spring-boot-ai-chat)"; then
  boot_and_probe spring-boot-ai-chat \
    "$SPRING_JAR" \
    "http://127.0.0.1:8080/atmosphere/console/" \
    LLM_API_KEY= GEMINI_API_KEY= || fail=1
else
  fail=1
fi

# kotlin-dsl-chat: maven-shade fat jar. Regressions the exploded run can't see —
# the WS upgrade answered 501 (jakarta.websocket container missing) and SLF4J
# no-oped (logback-core dropped). Reads -Dserver.port; WS at /chat.
if KOTLIN_JAR="$(sample_jar kotlin-dsl-chat)"; then
  boot_ws_and_check kotlin-dsl-chat \
    "$KOTLIN_JAR" \
    18099 /chat || fail=1
else
  fail=1
fi

# embedded-jetty-websocket-chat: maven-shade fat jar serving Atmosphere on an
# embedded Jetty with a classpath /webapp/. WS at /chat; same shade-drop risks.
if JETTY_JAR="$(sample_jar embedded-jetty-websocket-chat)"; then
  boot_ws_and_check embedded-jetty-websocket-chat \
    "$JETTY_JAR" \
    18080 /chat || fail=1
else
  fail=1
fi

# spring-boot-otel-chat: Spring Boot fat jar whose OTLP exporter dies at export
# time on an OpenTelemetry BOM skew (NoClassDefFoundError InstrumentationUtil).
# The WS connect emits a span; the post-101 settle window lets it export so a
# skew surfaces in the log. Empty keys force the keyless demo provider.
if OTEL_JAR="$(sample_jar spring-boot-otel-chat)"; then
  boot_ws_and_check spring-boot-otel-chat \
    "$OTEL_JAR" \
    18090 /atmosphere/ai-chat \
    LLM_API_KEY= GEMINI_API_KEY= || fail=1
else
  fail=1
fi

if ((fail != 0)); then
  echo "Sample startup smoke FAILED" >&2
  exit 1
fi
echo "Sample startup smoke passed"

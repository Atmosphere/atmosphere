#!/usr/bin/env bash
#
# Sample-startup smoke test — boots the two most dependency-sensitive samples
# and asserts a real HTTP endpoint returns 200, then kills each JVM by PID.
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

fail=0

# quarkus-ai-chat: the sample the opentelemetry bump broke. Env mirrors the
# e2e fixture (modules/integration-tests/e2e/fixtures/sample-server.ts) — a
# dummy key lets the Quarkus LangChain4j StreamingChatModel bean materialise
# without a real provider. GET / serves the chat page (META-INF/resources).
boot_and_probe quarkus-ai-chat \
  "$ROOT/samples/quarkus-ai-chat/target/quarkus-app/quarkus-run.jar" \
  "http://127.0.0.1:18810/" \
  LLM_API_KEY=dummy-not-real QUARKUS_HTTP_PORT=18810 || fail=1

# spring-boot-ai-chat: Spring Boot startup canary. Empty LLM_API_KEY forces
# the keyless demo provider regardless of the caller's shell env, matching
# what CI runners see. GET / is a 302 to the bundled Atmosphere Console
# (AiChatApplication.addRedirectViewController), so probe the console
# directly — it's the sample's real UI.
SPRING_JAR="$(find "$ROOT/samples/spring-boot-ai-chat/target" -maxdepth 1 -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | head -1 || true)"
boot_and_probe spring-boot-ai-chat \
  "${SPRING_JAR:-$ROOT/samples/spring-boot-ai-chat/target/missing.jar}" \
  "http://127.0.0.1:8080/atmosphere/console/" \
  LLM_API_KEY= GEMINI_API_KEY= || fail=1

if ((fail != 0)); then
  echo "Sample startup smoke FAILED" >&2
  exit 1
fi
echo "Sample startup smoke passed"

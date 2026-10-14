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
# Regression test for STALE SAMPLE ARTIFACT SELECTION.
#
# What went wrong
# ---------------
# samples/*/target is never cleaned between builds, so it accumulates one jar
# per version ever packaged there. On the working tree where this test was
# written, 23 of 29 sample target/ directories held 2-5 versions at once
# (spring-boot-rag-chat: 4.0.60, 4.0.62, 4.0.64, 4.0.66-SNAPSHOT; 2.59 GB of
# leftovers overall). Three separate boot paths then chose an artifact by
# filename glob:
#
#   .claude/skills/release-sample-sweep/scripts/sweep-sample.sh  sort -r | head -1
#   scripts/release-gate-samples.sh                              sort -r | head -1
#   scripts/sample-startup-smoke.sh                              head -1  (NO sort)
#
# Both shapes boot the wrong build:
#
#   * `sort -r` is LEXICOGRAPHIC, not version-aware. 4.0.7, 4.0.8 and 4.0.9 are
#     real released versions of this project, and "9" sorts above "6", so a
#     leftover 4.0.9-SNAPSHOT jar outranks the current 4.0.66-SNAPSHOT one. The
#     trap re-arms at every 10x boundary (4.0.99 vs 4.0.100).
#   * "newest present" is not "current". When the sample was simply not
#     repackaged for this build, the glob silently returns the newest OLD jar
#     and the run records a pass for code that is not in the artifact it booted.
#   * bare `head -1` has no ordering at all — it takes whatever the filesystem
#     lists first. Measured on the real tree at 4.0.66-SNAPSHOT, the smoke gate
#     selected 4.0.64-SNAPSHOT and 4.0.63-SNAPSHOT artifacts.
#   * quarkus-run.jar carries no version in its name, so a stale quarkus-app/
#     directory is invisible to any filename check.
#
# What this test pins
# -------------------
# Each case builds a synthetic repo root (its own pom.xml + samples/*/target)
# and runs the REAL script against it. Following scripts/lib/prove_gate.sh:
# every case asserts the EXPECTED message or the EXPECTED artifact, never
# merely a non-zero exit — a script that dies for an unrelated reason must not
# be able to score a pass here.
#
# Usage: scripts/test-sample-jar-selection.sh [case-name ...]   (default: all)
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SWEEP="$ROOT/.claude/skills/release-sample-sweep/scripts/sweep-sample.sh"
GATE="$ROOT/scripts/release-gate-samples.sh"
SMOKE="$ROOT/scripts/sample-startup-smoke.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
PASSED=0; FAILED=0

pass() { printf "  ${GREEN}PASS${NC} %s\n" "$1"; PASSED=$((PASSED + 1)); }
fail() { printf "  ${RED}FAIL${NC} %s\n     %s\n" "$1" "$2"; FAILED=$((FAILED + 1)); }

# make_root <name> <version> — synthetic repo root with a root pom at <version>.
# The scripts resolve ROOT relative to their own location, so each fixture gets
# its own copy of the script under test at the same relative depth.
make_root() {
    local name="$1" version="$2" fake="$TMP/$1"
    mkdir -p "$fake/scripts" "$fake/.claude/skills/release-sample-sweep/scripts" "$fake/samples"
    cat >"$fake/pom.xml" <<EOF
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-project</artifactId>
    <packaging>pom</packaging>
    <version>$version</version>
</project>
EOF
    cp "$SWEEP" "$fake/.claude/skills/release-sample-sweep/scripts/sweep-sample.sh"
    cp "$GATE" "$fake/scripts/release-gate-samples.sh"
    cp "$SMOKE" "$fake/scripts/sample-startup-smoke.sh"
    echo "$fake"
}

# Real jars, not touched empties: boot_type reads META-INF/MANIFEST.MF to
# classify the artifact, so a zero-byte stand-in would be rejected as
# "not runnable" and the selection under test would never be exercised.
MANIFEST_DIR="$TMP/_manifest"; mkdir -p "$MANIFEST_DIR/empty"
printf 'Manifest-Version: 1.0\nMain-Class: org.atmosphere.samples.Demo\n' \
    >"$MANIFEST_DIR/MANIFEST.MF"
mkjar() { jar cfm "$1" "$MANIFEST_DIR/MANIFEST.MF" -C "$MANIFEST_DIR/empty" . ; }

# jars <root> <sample> <file>... — plant artifacts in samples/<sample>/target.
jars() {
    local fake="$1" sample="$2"; shift 2
    mkdir -p "$fake/samples/$sample/target"
    local f
    for f in "$@"; do mkjar "$fake/samples/$sample/target/$f"; done
    # A pom so boot_type's <packaging>/exec-maven-plugin greps have a file.
    [[ -f "$fake/samples/$sample/pom.xml" ]] || \
        echo '<project><artifactId>x</artifactId></project>' >"$fake/samples/$sample/pom.xml"
}

# sweep_probe <root> <fn> <args...> — call a function from sweep-sample.sh
# against the synthetic root. The source stops before the dispatch `case`, so
# nothing runs on load and ROOT resolves to the fixture (the copy sits at the
# same relative depth as the real script).
#
# This drives the SAME function names that exist in the pre-fix script on
# purpose. Probing the new `jar` subcommand instead would make every reverted
# case fail with "unknown subcommand" — a green-to-red flip that proves the
# subcommand is new, not that the selection was wrong. Compare prove_gate.sh:
# assert the expected symptom, never merely a non-zero exit.
sweep_probe() {
    local fake="$1" fn="$2"; shift 2
    local dir="$fake/.claude/skills/release-sample-sweep/scripts"
    awk '/^case "\$\{1:-\}" in/{exit} {print}' "$dir/sweep-sample.sh" >"$dir/lib.sh"
    bash -c 'set -uo pipefail; source "$0/lib.sh"; "$@"' "$dir" "$fn" "$@" 2>&1
}

# gate_jar <root> <sample> — release-gate-samples.sh's selection, via its own
# find_boot_jar. Sourcing stops at the dispatch/main body so nothing executes.
gate_jar() {
    local fake="$1" sample="$2"
    bash -c '
        set -uo pipefail
        ROOT="'"$fake"'"
        eval "$(awk "/^reactor_version\(\)/,/^}/" "$ROOT/scripts/release-gate-samples.sh")"
        eval "$(awk "/^find_boot_jar\(\)/,/^}/" "$ROOT/scripts/release-gate-samples.sh")"
        eval "$(awk "/^quarkus_app_is_current\(\)/,/^}/" "$ROOT/scripts/release-gate-samples.sh")"
        find_boot_jar "'"$sample"'" 2>&1 || echo "__NO_JAR__"
    '
}

# ---------------------------------------------------------------------------
# CASE 1 [BITES] — lexicographic ordering: a real 4.0.9-era leftover must not
# outrank the current 4.0.66 artifact. `sort -r | head -1` returns 4.0.9.
# ---------------------------------------------------------------------------
case_lexicographic() {
    local fake; fake="$(make_root lexicographic 4.0.66-SNAPSHOT)"
    jars "$fake" demo \
        atmosphere-demo-4.0.66-SNAPSHOT.jar \
        atmosphere-demo-4.0.9-SNAPSHOT.jar

    local got; got="$(sweep_probe "$fake" find_jar demo)"
    if [[ "$(basename "$got")" == "atmosphere-demo-4.0.66-SNAPSHOT.jar" ]]; then
        pass "lexicographic/sweep: picked 4.0.66-SNAPSHOT over leftover 4.0.9-SNAPSHOT"
    else
        fail "lexicographic/sweep" "expected atmosphere-demo-4.0.66-SNAPSHOT.jar, got: $got"
    fi

    got="$(gate_jar "$fake" demo)"
    if [[ "$(basename "$got")" == "atmosphere-demo-4.0.66-SNAPSHOT.jar" ]]; then
        pass "lexicographic/gate: picked 4.0.66-SNAPSHOT over leftover 4.0.9-SNAPSHOT"
    else
        fail "lexicographic/gate" "expected atmosphere-demo-4.0.66-SNAPSHOT.jar, got: $got"
    fi
}

# ---------------------------------------------------------------------------
# CASE 2 [BITES] — the false-pass shape: the current version was never
# packaged, only older ones are present. Boot-type resolution must REFUSE,
# naming the missing version and the stale artifacts, not quietly classify the
# newest leftover as bootable.
# ---------------------------------------------------------------------------
case_stale_only() {
    local fake; fake="$(make_root stale_only 4.0.66-SNAPSHOT)"
    jars "$fake" notpackaged \
        atmosphere-notpackaged-4.0.64-SNAPSHOT.jar \
        atmosphere-notpackaged-4.0.62-SNAPSHOT.jar

    local out rc
    out="$(sweep_probe "$fake" boot_type notpackaged)"; rc=$?
    if [[ $rc -eq 0 ]]; then
        fail "stale_only/sweep" \
             "expected refusal, but boot_type accepted it as '$out' (would boot $(basename "$(sweep_probe "$fake" find_jar notpackaged)"))"
    elif [[ "$out" == *"NO 4.0.66-SNAPSHOT artifact"* && "$out" == *"4.0.64-SNAPSHOT"* \
          && "$out" == *"false pass"* ]]; then
        pass "stale_only/sweep: refused, named the missing version and the stale jars"
    else
        fail "stale_only/sweep" "died, but not with the stale-artifact message: $out"
    fi

    out="$(gate_jar "$fake" notpackaged)"
    if [[ "$out" == "__NO_JAR__" ]]; then
        pass "stale_only/gate: no artifact returned (ensure_packaged repackages instead of reusing)"
    else
        fail "stale_only/gate" "expected no artifact, got: $out"
    fi
}

# ---------------------------------------------------------------------------
# CASE 3 [GUARD] — the shade plugin's original-*.jar carries the SAME version
# suffix as the real artifact, so the version pin alone does not separate them;
# booting it would run an unshaded jar missing every relocated dependency. The
# pre-fix script also excluded it, so this case does not bite on revert — it
# exists to stop the version pin from dropping that exclusion later.
# ---------------------------------------------------------------------------
case_original_excluded() {
    local fake; fake="$(make_root original_excluded 4.0.66-SNAPSHOT)"
    jars "$fake" shaded \
        atmosphere-shaded-4.0.66-SNAPSHOT.jar \
        original-atmosphere-shaded-4.0.66-SNAPSHOT.jar

    local got; got="$(sweep_probe "$fake" find_jar shaded)"
    if [[ "$(basename "$got")" == "atmosphere-shaded-4.0.66-SNAPSHOT.jar" ]]; then
        pass "original_excluded/sweep: skipped the shade plugin's original-*.jar"
    else
        fail "original_excluded/sweep" "expected the shaded jar, got: $got"
    fi
}

# ---------------------------------------------------------------------------
# CASE 4 — a stale quarkus-app/. quarkus-run.jar is unversioned, so only the
# application jar under quarkus-app/app/ reveals which build it launches.
# ---------------------------------------------------------------------------
case_quarkus_stale() {
    local fake; fake="$(make_root quarkus_stale 4.0.66-SNAPSHOT)"
    mkdir -p "$fake/samples/qk/target/quarkus-app/app"
    : >"$fake/samples/qk/target/quarkus-app/quarkus-run.jar"
    : >"$fake/samples/qk/target/quarkus-app/app/atmosphere-qk-4.0.64-SNAPSHOT.jar"
    echo '<project><artifactId>qk</artifactId></project>' >"$fake/samples/qk/pom.xml"

    local out rc
    out="$(sweep_probe "$fake" boot_type qk)"; rc=$?
    if [[ $rc -eq 0 ]]; then
        fail "quarkus_stale/sweep" \
             "expected refusal, but boot_type accepted the stale quarkus-app as '$out'"
    elif [[ "$out" == *"quarkus-app is stale"* && "$out" == *"4.0.64-SNAPSHOT"* ]]; then
        pass "quarkus_stale/sweep: refused a quarkus-app/ built at another version"
    else
        fail "quarkus_stale/sweep" "died, but not with the stale-quarkus-app message: $out"
    fi

    # And the fresh case still boots.
    : >"$fake/samples/qk/target/quarkus-app/app/atmosphere-qk-4.0.66-SNAPSHOT.jar"
    out="$(sweep_probe "$fake" boot_type qk)"
    if [[ "$out" == "quarkus" ]]; then
        pass "quarkus_stale/sweep: a current quarkus-app/ is accepted"
    else
        fail "quarkus_stale/sweep(fresh)" "expected boot type 'quarkus', got: $out"
    fi
}

# ---------------------------------------------------------------------------
# CASE 5 — sample-startup-smoke.sh, which had no ordering at all. Its selector
# must resolve the current version and reject a stale-only target/ by name.
# ---------------------------------------------------------------------------
case_smoke_selector() {
    local fake; fake="$(make_root smoke_selector 4.0.66-SNAPSHOT)"
    jars "$fake" fresh atmosphere-fresh-4.0.66-SNAPSHOT.jar atmosphere-fresh-4.0.9-SNAPSHOT.jar
    jars "$fake" stale atmosphere-stale-4.0.64-SNAPSHOT.jar

    # The pre-fix script selected inline (`find … | head -1`) with no function to
    # call, so state the contract first and fail with THAT symptom rather than a
    # bare "command not found" from the probe below.
    if ! grep -q '^sample_jar()' "$fake/scripts/sample-startup-smoke.sh" \
       || ! grep -q '^reactor_version()' "$fake/scripts/sample-startup-smoke.sh"; then
        local inline
        inline="$(grep -n "find \"\$ROOT/samples/.*-name '\*\.jar'" \
                    "$fake/scripts/sample-startup-smoke.sh" | head -1)"
        fail "smoke_selector" \
             "no version-pinned selector: the script picks jars inline with a version-blind glob -> ${inline:-<no inline find found>}"
        fail "smoke_selector(stale)" \
             "same root cause: without a reactor-version pin there is nothing to refuse a stale-only target/"
        return
    fi

    local probe="$TMP/smoke_probe.sh"
    cat >"$probe" <<'EOF'
set -uo pipefail
eval "$(awk '/^reactor_version\(\)/,/^}/' "$ROOT/scripts/sample-startup-smoke.sh")"
REACTOR_VERSION="$(reactor_version)"
eval "$(awk '/^sample_jar\(\)/,/^}/' "$ROOT/scripts/sample-startup-smoke.sh")"
sample_jar "$1" 2>&1 || echo "__REFUSED__"
EOF

    local got
    got="$(ROOT="$fake" bash "$probe" fresh)"
    if [[ "$(basename "$got")" == "atmosphere-fresh-4.0.66-SNAPSHOT.jar" ]]; then
        pass "smoke_selector: picked 4.0.66-SNAPSHOT over leftover 4.0.9-SNAPSHOT"
    else
        fail "smoke_selector" "expected atmosphere-fresh-4.0.66-SNAPSHOT.jar, got: $got"
    fi

    got="$(ROOT="$fake" bash "$probe" stale)"
    if [[ "$got" == *"__REFUSED__"* && "$got" == *"no 4.0.66-SNAPSHOT artifact"* \
       && "$got" == *"atmosphere-stale-4.0.64-SNAPSHOT.jar"* ]]; then
        pass "smoke_selector: refused stale-only target/ and listed the rejected jar"
    else
        fail "smoke_selector(stale)" "expected a refusal naming the stale jar, got: $got"
    fi
}

# ---------------------------------------------------------------------------
# CASE 6 [GUARD] — an unversioned artifact (grpc-chat ships
# target/atmosphere-grpc-chat.jar) must NOT be mistaken for a stale jar; that
# sample boots via exec-maven-plugin. This case does not bite on revert; it
# stops the version pin from breaking a legitimate boot path.
# ---------------------------------------------------------------------------
case_unversioned_exec() {
    local fake; fake="$(make_root unversioned_exec 4.0.66-SNAPSHOT)"
    mkdir -p "$fake/samples/grpcish/target"
    : >"$fake/samples/grpcish/target/atmosphere-grpcish.jar"
    echo '<project><build><plugins><plugin><artifactId>exec-maven-plugin</artifactId></plugin></plugins></build></project>' \
        >"$fake/samples/grpcish/pom.xml"

    local out; out="$(sweep_probe "$fake" boot_type grpcish)"
    if [[ "$out" == "exec" ]]; then
        pass "unversioned_exec: unversioned artifact still resolves to the exec boot path"
    else
        fail "unversioned_exec" "expected boot type 'exec', got: $out"
    fi
}

# ---------------------------------------------------------------------------
# CASE 7 [BITES] — static gate over all three boot paths. The defect
# fingerprint is a version-blind glob reduced to one line: `-name '*.jar'`
# feeding `head -1` (with or without a `sort`). Diagnostic listings that
# enumerate what was rejected also use `-name '*.jar'` but never `head -1`, so
# the fingerprint separates selection from reporting without needing an
# allowlist — an allowlisted gate is one edit away from certifying itself.
#
# Line continuations are joined first: every real occurrence spans 2-3 lines,
# and a per-line grep would silently match none of them (a check that cannot
# fail is indistinguishable from one that always passes).
# ---------------------------------------------------------------------------
case_no_version_blind_glob() {
    local f rel hits any=0
    for f in "$SWEEP" "$GATE" "$SMOKE"; do
        rel="${f#"$ROOT"/}"
        hits="$(awk '{ line = line $0
                       if (/\\$/) { sub(/\\$/, "", line); next }
                       print NR": "line; line = "" }' "$f" \
                | grep -E "name '\*\.jar'" | grep -E 'head -1')"
        if [[ -n "$hits" ]]; then
            any=1
            fail "no_version_blind_glob: $rel" \
                 "version-blind selection still present -> $(echo "$hits" | head -1 | cut -c1-160)"
        fi
    done
    [[ "$any" == 1 ]] || pass "no_version_blind_glob: all three boot paths select by reactor version"
}

# ---------------------------------------------------------------------------
# CASE 8 [BITES] — the Playwright fixture is the fourth boot path and had the
# same defect in TypeScript: `jars.sort().reverse()[0]`, with `original-*.jar`
# not excluded. Measured on the real tree it returned
# original-atmosphere-jetty-embedded-websocket-4.0.66-SNAPSHOT.jar — the
# UNSHADED pre-shade copy — for the samples that exist to catch shade
# regressions. Only the exec:java boot types kept that off the live path.
#
# Asserted statically: the fixture is TypeScript with Playwright/node imports,
# so it cannot be executed standalone here without a transpile step this test
# has no business owning. Three properties, each tied to one failure mode.
# ---------------------------------------------------------------------------
case_playwright_fixture() {
    local f="$ROOT/modules/integration-tests/e2e/fixtures/sample-server.ts"
    local rel="${f#"$ROOT"/}"
    if [[ ! -f "$f" ]]; then
        fail "playwright_fixture" "missing $rel — update this test if the fixture moved"
        return
    fi
    # Comments are stripped before matching. The doc block above the fixture's
    # findJar QUOTES the old `jars.sort().reverse()[0]` to explain what went
    # wrong, and a bare grep scored that prose as a live defect on the first
    # run of this case. The mirror-image failure is worse: a check looking for
    # a REQUIRED construct that a comment mention silently satisfies. Same
    # class as EvidenceConsumerGrepPinTest certifying citation-only evidence.
    local code; code="$(sed -E '/^[[:space:]]*(\*|\/\/|\/\*)/d' "$f")"

    if grep -qE '\.sort\(\)\.reverse\(\)' <<<"$code"; then
        fail "playwright_fixture: ordering" \
             "$rel still selects with .sort().reverse() (string order, not version order) -> $(grep -nE '\.sort\(\)\.reverse\(\)' <<<"$code" | head -1)"
    else
        pass "playwright_fixture: no .sort().reverse() 'newest jar wins' selection (comments stripped)"
    fi
    if grep -q "startsWith('original-')" <<<"$code"; then
        pass "playwright_fixture: excludes the shade plugin's original-*.jar"
    else
        fail "playwright_fixture: original-*" \
             "$rel does not exclude original-*.jar, which sorts above the real artifact and is UNSHADED"
    fi
    if grep -q 'reactorVersion()' <<<"$code" && grep -q 'quarkus-app is stale' <<<"$code"; then
        pass "playwright_fixture: pins the reactor version and checks quarkus-app freshness"
    else
        fail "playwright_fixture: version pin" \
             "$rel does not resolve the reactor version / does not verify quarkus-app/app/"
    fi
}

# ---------------------------------------------------------------------------
# Case: the Playwright fixtures boot the PACKAGED artifact, never a Maven goal.
#
# Three boot paths used to be `./mvnw exec:java` / `./mvnw jetty:run` spawned
# from the fixture: AiFeatureTestServer, DualTransportChatServer and the WAR
# sample. In CI the E2E job restores a Maven cache but never runs a build, and a
# plugin PREFIX like `exec:` makes Maven resolve the descriptor of every plugin
# the project declares — release, eclipse, m2e-lifecycle — none of which
# `install -DskipTests` ever downloads. Every boot therefore went to a live
# Maven Central, and one HTTP 429 became an opaque "port not ready" timeout
# (ai-ux-flows SB3, run 33464934108; grpc-browser before it). Offline mode was
# tried and reverted (3b6373a7c7): the plugins were never cached to begin with.
#
# The fixtures now run `java` over what the build produced (target/e2e-lib,
# the shaded jar, target/e2e-runner/jetty-runner.jar). This case pins that:
# no fixture may spawn the wrapper or name a Maven goal in live code. Error
# hints such as "Run: ./mvnw package ..." are strings, not spawns, and stay.
# ---------------------------------------------------------------------------
case_fixture_boots_artifact() {
    local dir="$ROOT/modules/integration-tests/e2e/fixtures"
    if [[ ! -d "$dir" ]]; then
        fail "fixture_boots_artifact" "missing ${dir#"$ROOT"/} — update this test if the fixtures moved"
        return
    fi
    local f rel code hit found=0
    for f in "$dir"/*.ts; do
        rel="${f#"$ROOT"/}"
        code="$(sed -E '/^[[:space:]]*(\*|\/\/|\/\*)/d' "$f")"
        hit="$(grep -nE "spawn(Sync)?\([^)]*mvnw|[a-z-]+:(java|run|dev)\b" <<<"$code" | grep -vE '^[0-9]+:[^`]*Run: ' | head -1)"
        if [[ -n "$hit" ]]; then
            found=1
            fail "fixture_boots_artifact" "$rel boots through Maven at test time -> $hit"
        fi
    done
    if [[ $found -eq 0 ]]; then
        pass "fixture_boots_artifact: no fixture spawns mvnw or names a Maven goal (comments stripped)"
    fi
}

# ---------------------------------------------------------------------------
# sweep-sample.sh boots — the launcher end to end, against a synthetic sample.
#
# A `java` shim on PATH stands in for the JVM: it records the environment it
# was started with, then serves HTTP on the -Dserver.port the launcher passed,
# so `start` sees a ready sample and `stop` has a real PID to kill. Nothing
# here touches a real sample, port block or the operator's sweep state.
# ---------------------------------------------------------------------------
free_port() {
    local p
    for _ in $(seq 1 50); do
        p=$((20000 + RANDOM % 20000))
        (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null || { echo "$p"; return 0; }
    done
    return 1
}

# boot_fixture <name> — a fake root with a main-jar sample "chan" and a Spring
# Boot jar sample "boot", plus the java shim. Prints the fake root; the shim
# writes $fake/env.<port> and $fake/args.<port>.
boot_fixture() {
    local fake; fake="$(make_root "$1" 4.0.66-SNAPSHOT)"
    jars "$fake" chan atmosphere-chan-4.0.66-SNAPSHOT.jar
    mkdir -p "$fake/samples/boot/target"
    printf 'Manifest-Version: 1.0\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\nSpring-Boot-Classes: BOOT-INF/classes/\n' \
        >"$fake/boot-manifest.mf"
    jar cfm "$fake/samples/boot/target/atmosphere-boot-4.0.66-SNAPSHOT.jar" "$fake/boot-manifest.mf" \
        -C "$MANIFEST_DIR/empty" .
    echo '<project><artifactId>boot</artifactId></project>' >"$fake/samples/boot/pom.xml"
    mkdir -p "$fake/bin"
    cat >"$fake/bin/java" <<EOF
#!/usr/bin/env bash
port=""
for a in "\$@"; do
    case "\$a" in -Dserver.port=*) port="\${a#-Dserver.port=}" ;; --server.port=*) port="\${a#--server.port=}" ;; esac
done
env >"$fake/env.\$port"
printf '%s\n' "\$@" >"$fake/args.\$port"
exec python3 -m http.server "\$port" --bind 127.0.0.1
EOF
    chmod +x "$fake/bin/java"
    echo "$fake"
}

sweep_cli() {
    local fake="$1"; shift
    PATH="$fake/bin:$PATH" bash "$fake/.claude/skills/release-sample-sweep/scripts/sweep-sample.sh" "$@" 2>&1
}

# ---------------------------------------------------------------------------
# CASE [BITES] — the operator's messaging-channel credentials must not reach
# a booted sample. The dentist sample inherited SLACK_BOT_TOKEN and
# TELEGRAM_BOT_TOKEN from the operator's shell and registered live Slack and
# Telegram channels; SCRUB_VARS only named LLM and provider keys.
# ---------------------------------------------------------------------------
case_channel_env_scrubbed() {
    if ! command -v python3 >/dev/null 2>&1; then
        fail "channel_env_scrubbed" "python3 is required for the boot fixture"
        return
    fi
    local fake port out
    fake="$(boot_fixture channel_env)"
    port="$(free_port)" || { fail "channel_env_scrubbed" "no free port"; return; }
    out="$(SLACK_BOT_TOKEN=xoxb-operator TELEGRAM_BOT_TOKEN=tg-operator \
           ATMOSPHERE_CHANNELS_DISCORD_BOT_TOKEN=dc-operator \
           sweep_cli "$fake" start chan --port "$port" --timeout 30)"
    sweep_cli "$fake" stop chan >/dev/null
    if [[ ! -f "$fake/env.$port" ]]; then
        fail "channel_env_scrubbed" "the sample never started: $out"
        return
    fi
    local leaked
    leaked="$(grep -E '^(SLACK_BOT_TOKEN|TELEGRAM_BOT_TOKEN|ATMOSPHERE_CHANNELS_DISCORD_BOT_TOKEN)=' "$fake/env.$port" | cut -d= -f1 | paste -sd, -)"
    if [[ -z "$leaked" ]]; then
        pass "channel_env_scrubbed: SLACK_/TELEGRAM_/ATMOSPHERE_CHANNELS_ credentials did not reach the sample"
    else
        fail "channel_env_scrubbed" "the booted sample inherited the operator's channel credentials: $leaked"
    fi

    # The opt-in and an explicit --env still deliver a token to a lane that
    # deliberately tests a real channel.
    port="$(free_port)" || { fail "channel_env_scrubbed(opt-in)" "no free port"; return; }
    SLACK_BOT_TOKEN=xoxb-operator SWEEP_KEEP_CHANNEL_ENV=1 \
        sweep_cli "$fake" start chan --port "$port" --timeout 30 >/dev/null
    sweep_cli "$fake" stop chan >/dev/null
    if grep -q '^SLACK_BOT_TOKEN=xoxb-operator$' "$fake/env.$port" 2>/dev/null; then
        pass "channel_env_scrubbed: SWEEP_KEEP_CHANNEL_ENV=1 inherits the channel credentials"
    else
        fail "channel_env_scrubbed(opt-in)" "SWEEP_KEEP_CHANNEL_ENV=1 did not pass SLACK_BOT_TOKEN through"
    fi
    port="$(free_port)" || { fail "channel_env_scrubbed(--env)" "no free port"; return; }
    SLACK_BOT_TOKEN=xoxb-operator \
        sweep_cli "$fake" start chan --port "$port" --timeout 30 --env SLACK_BOT_TOKEN=xoxb-lane >/dev/null
    sweep_cli "$fake" stop chan >/dev/null
    if grep -q '^SLACK_BOT_TOKEN=xoxb-lane$' "$fake/env.$port" 2>/dev/null; then
        pass "channel_env_scrubbed: an explicit --env outranks the scrub"
    else
        fail "channel_env_scrubbed(--env)" "--env SLACK_BOT_TOKEN=xoxb-lane did not reach the sample"
    fi
}

# ---------------------------------------------------------------------------
# CASE [BITES] — two lanes booting the same sample. State was one pidfile and
# log per sample name, so the second `start` died "already running" and a
# `stop` from either lane killed the other's JVM. Per sample+port state lets
# both run; an unqualified stop refuses while two instances exist; a --port
# stop stops only that one. WebTransport's fixed UDP port collided the same
# way, so a Spring Boot boot is handed its own HTTP port as the UDP port.
# ---------------------------------------------------------------------------
case_two_lanes_same_sample() {
    if ! command -v python3 >/dev/null 2>&1; then
        fail "two_lanes_same_sample" "python3 is required for the boot fixture"
        return
    fi
    local fake a b out
    fake="$(boot_fixture two_lanes)"
    a="$(free_port)" && b="$(free_port)" || { fail "two_lanes_same_sample" "no free port"; return; }
    [[ "$a" != "$b" ]] || b=$((a + 1))
    sweep_cli "$fake" start chan --port "$a" --timeout 30 >/dev/null
    out="$(sweep_cli "$fake" start chan --port "$b" --timeout 30)"
    local up=0
    port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
    port_open "$a" && port_open "$b" && up=1
    if [[ "$up" == 1 ]]; then
        pass "two_lanes_same_sample: the same sample runs on two ports at once"
    else
        fail "two_lanes_same_sample" "second start did not come up beside the first: $(tail -3 <<<"$out")"
    fi

    out="$(sweep_cli "$fake" stop chan)"
    if port_open "$a" && port_open "$b" && [[ "$out" == *"pass --port"* ]]; then
        pass "two_lanes_same_sample: an unqualified stop refuses to pick one of two instances"
    else
        fail "two_lanes_same_sample(stop)" "an unqualified stop acted on a guess: $out"
    fi

    sweep_cli "$fake" stop chan --port "$a" >/dev/null
    if ! port_open "$a" && port_open "$b"; then
        pass "two_lanes_same_sample: stop --port stops that lane's instance only"
    else
        fail "two_lanes_same_sample(stop --port)" "stop --port $a did not leave port $b running alone"
    fi
    sweep_cli "$fake" stop chan --port "$b" >/dev/null

    local c; c="$(free_port)" || { fail "two_lanes_same_sample(wt)" "no free port"; return; }
    sweep_cli "$fake" start boot --port "$c" --timeout 30 >/dev/null
    sweep_cli "$fake" stop boot >/dev/null
    if grep -qx -- "-Datmosphere.web-transport.port=$c" "$fake/args.$c" 2>/dev/null; then
        pass "two_lanes_same_sample: a Spring Boot boot gets its HTTP port as its WebTransport UDP port"
    else
        fail "two_lanes_same_sample(wt)" "no -Datmosphere.web-transport.port=$c in: $(paste -sd' ' "$fake/args.$c" 2>/dev/null)"
    fi
}

CASES=(case_lexicographic case_stale_only case_original_excluded
       case_quarkus_stale case_smoke_selector case_unversioned_exec
       case_no_version_blind_glob case_playwright_fixture
       case_fixture_boots_artifact case_channel_env_scrubbed
       case_two_lanes_same_sample)

echo "Sample artifact selection — stale-jar regression"
echo "==============================================="
if [[ $# -gt 0 ]]; then
    for c in "$@"; do "case_$c"; done
else
    for c in "${CASES[@]}"; do "$c"; done
fi

echo
echo "passed=$PASSED failed=$FAILED"
[[ "$FAILED" -eq 0 ]]

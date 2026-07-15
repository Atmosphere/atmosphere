#!/usr/bin/env bash
# Proves that each check in architectural-validation.sh actually fails on a
# violation — and that the FAIL that appears is the one under test.
#
# Why this exists
# ---------------
# Four checks in that script were discovered to be unconditional PASSes: a path
# variable that was never defined (rg then read stdin and scanned nothing), a
# missing `rg` binary whose stderr was suppressed, an empty allowlist turning
# `grep -vE ""` into a match-everything filter, and a `python3 - <<'PY'` heredoc
# in a pipeline stealing stdin from the data it was meant to read. All four
# printed PASS. None was noticed, because a check that has only ever passed is
# indistinguishable from a check that cannot fail.
#
# An earlier attempt to prove the gate "bites" injected a stub string, saw the
# build go red, and credited the mock check — the *placeholder* check had caught
# it, and the mock check was a no-op that would have missed it. So every case
# here asserts the EXPECTED check's own message, never merely a non-zero exit.
#
# Usage: scripts/lib/prove_gate.sh [case-name ...]     (default: all)

set -uo pipefail

cd "$(git rev-parse --show-toplevel)"
GATE=./scripts/architectural-validation.sh
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

CPR_CLASS=modules/cpr/src/main/java/org/atmosphere/util/SimpleBroadcaster.java
AI_CLASS=modules/ai/src/main/java/org/atmosphere/ai/AiConfig.java
TEST_CLASS=modules/cpr/src/test/java/org/atmosphere/inject/InjectableObjectFactoryTest.java
CONTRACT=modules/anthropic/src/test/java/org/atmosphere/ai/anthropic/AnthropicRuntimeContractTest.java
JETTY=samples/grpc-chat/src/main/java/org/atmosphere/samples/grpc/GrpcChatServer.java

PASSED=0; FAILED=0; RESULTS=""

# Fingerprint of the code under test, taken before any case runs.
scripts_fingerprint() { find scripts -type f -exec shasum {} + 2>/dev/null | shasum | cut -d' ' -f1; }
SCRIPTS_SNAPSHOT=$(scripts_fingerprint)

# Insert a line just inside the first class body of $1.
inject_in_class() {
    python3 - "$1" "$2" <<'PY'
import sys
path, snippet = sys.argv[1], sys.argv[2]
s = open(path).read()
i = s.index('{', s.index('class '))
open(path, 'w').write(s[:i+1] + '\n' + snippet + '\n' + s[i+1:])
PY
}

# Restore ONLY what the cases touch. A blanket `git checkout -- .` here reverted
# every uncommitted change in the tree — including in-progress fixes to the very
# script under test, so a run silently graded the old code and reported the fix
# as a no-op. Never let a test harness revert paths it did not write.
INJECT_TRACKED=("$CPR_CLASS" "$AI_CLASS" "$TEST_CLASS" "$CONTRACT" "$JETTY" .github/workflows/e2e.yml)
INJECT_CREATED=(
    modules/ai/src/main/java/org/atmosphere/ai/ZzzDeadIface.java
    modules/ai/src/main/resources/META-INF/services/org.atmosphere.ai.ZzzSpi
    modules/cpr/zzz-scratch.bak
    .github/workflows/zzz-notests.yml
)
restore() {
    git checkout -- "${INJECT_TRACKED[@]}" 2>/dev/null
    rm -f "${INJECT_CREATED[@]}" 2>/dev/null
    # Compare scripts/ against the snapshot taken at startup — not against HEAD,
    # because the gate legitimately carries uncommitted work-in-progress while
    # being proved. Any drift from the snapshot means a case mutated the code
    # under test, so the results would be grading something other than what the
    # author is editing.
    local now
    now=$(scripts_fingerprint)
    if [ "$now" != "$SCRIPTS_SNAPSHOT" ]; then
        echo "prove_gate.sh: BUG — scripts/ changed during the run; results are" >&2
        echo "  untrustworthy (a case mutated the code under test)." >&2
        exit 2
    fi
}

# run_case <name> <expected-FAIL-substring> <setup-fn>
run_case() {
    local name="$1" expect="$2" setup="$3"
    if [ "$#" -ge 4 ] && [ -n "${SELECTED:-}" ]; then :; fi
    restore
    "$setup"
    local out
    out=$("$GATE" < /dev/null 2>&1 | sed -e 's/\x1b\[[0-9;]*m//g' | grep '^  FAIL' || true)
    restore
    if echo "$out" | grep -qF "$expect"; then
        PASSED=$((PASSED+1))
        RESULTS="${RESULTS}$(printf "${GREEN}  BITES${NC}   %s" "$name")\n"
    else
        FAILED=$((FAILED+1))
        local got="${out:-<no FAIL at all — check is a NO-OP>}"
        RESULTS="${RESULTS}$(printf "${RED}  NO-OP${NC}   %s\n            expected: %s\n            got     : %s" \
            "$name" "$expect" "$(echo "$got" | head -1)")\n"
    fi
}

# ---- setups -----------------------------------------------------------------
s_noop()        { inject_in_class "$CPR_CLASS" '    public static final String NOOP = "x";'; }
# `_` is a word char, so \b(NOOP|NO_OP)\b never matched an affixed name even
# though the extractor is [A-Z_]*NOOP[A-Z_]*. Pin the spelling that evaded it.
s_noop_affixed(){ inject_in_class "$CPR_CLASS" '    public static final String ZZZ_NOOP = "x";'; }
s_dead_iface()  { printf 'package org.atmosphere.ai;\npublic interface ZzzDeadIface { void x(); }\n' \
                    > modules/ai/src/main/java/org/atmosphere/ai/ZzzDeadIface.java; }
s_empty_spi()   { : > modules/ai/src/main/resources/META-INF/services/org.atmosphere.ai.ZzzSpi; }
s_placeholder() { inject_in_class "$CPR_CLASS" '    private String p = "stub implementation of x";'; }
s_todo()        { inject_in_class "$CPR_CLASS" '    // TODO: finish later'; }
s_mock()        { inject_in_class "$CPR_CLASS" '    private String mock = "x";'; }
s_suppress()    { inject_in_class "$CPR_CLASS" '    @SuppressWarnings("zzzbogus") private String q = "x";'; }
s_offer()       { inject_in_class "$CPR_CLASS" '    void zzz(java.util.Queue<String> q) {
        q.offer("x");
    }'; }
s_noop_test()   { inject_in_class "$TEST_CLASS" '    @org.junit.jupiter.api.Test void zzz() { org.junit.jupiter.api.Assertions.assertTrue(true); }'; }
s_disabled()    { inject_in_class "$TEST_CLASS" '    @Disabled
    @org.junit.jupiter.api.Test void zzzD() { }'; }
# A fully-qualified annotation evaded '@Disabled\b' entirely. Pin it.
s_disabled_fqn(){ inject_in_class "$TEST_CLASS" '    @org.junit.jupiter.api.Disabled
    @org.junit.jupiter.api.Test void zzzQ() { }'; }
s_ignore()      { inject_in_class "$TEST_CLASS" '    @Ignore void zzzI() { }'; }
s_ignore_fqn()  { inject_in_class "$TEST_CLASS" '    @org.junit.Ignore void zzzIQ() { }'; }
s_assume()      { inject_in_class "$CONTRACT" '    void zzzA() { org.junit.jupiter.api.Assumptions.assumeTrue(false); }'; }
s_backup()      { : > modules/cpr/zzz-scratch.bak; }
s_sysout()      { inject_in_class "$CPR_CLASS" '    static { System.out.println("x"); }'; }
s_stack()       { inject_in_class "$CPR_CLASS" '    static { try { if (true) throw new RuntimeException(); } catch (Exception e) { e.printStackTrace(); } }'; }
s_fluent()      { inject_in_class "$AI_CLASS" '    void zzzF(Object o) {
        promptSpec.system("x");
    }'; }
s_di()          { inject_in_class "$AI_CLASS" '    void zzzR(Class<?> c) throws Exception {
        c.getDeclaredConstructor().newInstance();
    }'; }
s_coe()         { printf '\n# zzz\njobs:\n  zzz:\n    continue-on-error: true\n' >> .github/workflows/e2e.yml; }
s_skiptests()   { printf 'name: zzz\non:\n  push:\njobs:\n  b:\n    runs-on: ubuntu-latest\n    steps:\n    - run: ./mvnw install -DskipTests\n' \
                    > .github/workflows/zzz-notests.yml; }
s_experimental(){ inject_in_class "$CPR_CLASS" '    @Experimental private String e2 = "x";'; }
s_secret()      { inject_in_class "$CPR_CLASS" '    private String k = "sk-abcdefghij0123456789abcdefghij";'; }
s_jetty()       { python3 - "$JETTY" <<'PY'
import sys, re
p = sys.argv[1]; s = open(p).read()
s = re.sub(r'^.*setProtectedTargets.*$', '', s, count=1, flags=re.MULTILINE)
open(p, 'w').write(s)
PY
}

# ---- cases ------------------------------------------------------------------
run_case "noop-constants"      "NOOP constants declared but never referenced"        s_noop
run_case "noop-affixed"        "NOOP constants declared but never referenced"        s_noop_affixed
run_case "dead-ai-interface"   "interfaces with zero implementations"                s_dead_iface
run_case "empty-spi"           "Empty META-INF/services"                             s_empty_spi
run_case "critical-placeholder" "critical placeholder patterns"                      s_placeholder
run_case "todo-marker"         "deferral marker"                                     s_todo
run_case "mock-in-prod"        "mock/stub/fake reference"                            s_mock
run_case "suppresswarnings"    "unauthorized @SuppressWarnings"                      s_suppress
run_case "unchecked-offer"     "Unchecked offer()"                                   s_offer
run_case "noop-test-assert"    "no-op test assertions"                               s_noop_test
run_case "bare-disabled"       "@Disabled"                                           s_disabled
run_case "disabled-fqn"        "@Disabled"                                           s_disabled_fqn
run_case "junit4-ignore"       "@Ignore"                                             s_ignore
run_case "ignore-fqn"          "@Ignore"                                             s_ignore_fqn
run_case "contract-assume"     "contract-test skip patterns"                         s_assume
run_case "backup-files"        "backup/temporary files"                              s_backup
run_case "system-out"          "System.out/err call(s)"                              s_sysout
run_case "printstacktrace"     ".printStackTrace() call(s)"                          s_stack
run_case "fluent-builder"      "fluent builder calls with discarded"                 s_fluent
run_case "di-bypass"           "raw reflection instantiations"                       s_di
run_case "continue-on-error"   "continue-on-error"                                   s_coe
run_case "skiptests-no-tests"  "skip tests without running any"                      s_skiptests
run_case "experimental"        "@Experimental marker not allowed"                    s_experimental
run_case "hardcoded-secret"    "hardcoded API keys"                                  s_secret
run_case "jetty-protected"     "without setProtectedTargets"                         s_jetty

echo ""
echo "==== Gate bite-proof ===="
printf "%b" "$RESULTS"
echo "-------------------------"
echo "  bites: $PASSED   no-op: $FAILED"
[ "$FAILED" -eq 0 ] || exit 1

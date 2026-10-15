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
# AI application state (check 15): one plain Java target, one Kotlin source and
# one nested Quarkus module, the two places the modules/*/src/main/java glob
# misses, plus an allowlisted site whose removal must turn its entry stale.
AI_PROC=modules/ai/src/main/java/org/atmosphere/ai/processor/AiEndpointProcessor.java
AI_KT=modules/kotlin/src/main/kotlin/org/atmosphere/kotlin/ai/AgentRegistration.kt
AI_QUARKUS=modules/quarkus-extension/runtime/src/main/java/org/atmosphere/quarkus/runtime/AtmosphereBootstrap.java
AI_RERANKER=modules/ai/src/main/java/org/atmosphere/ai/LlmReranker.java

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
INJECT_TRACKED=("$CPR_CLASS" "$AI_CLASS" "$TEST_CLASS" "$CONTRACT" "$JETTY" .github/workflows/e2e.yml
                "$AI_PROC" "$AI_KT" "$AI_QUARKUS" "$AI_RERANKER")
INJECT_CREATED=(
    modules/ai/src/main/java/org/atmosphere/ai/ZzzDeadIface.java
    modules/ai/src/main/resources/META-INF/services/org.atmosphere.ai.ZzzSpi
    modules/cpr/zzz-scratch.bak
    .github/workflows/zzz-notests.yml
    e2e/ZzzUncovered.java
)
# Files a case stages with `git add -N` (the coverage check reads the index).
INJECT_INDEXED=(e2e/ZzzUncovered.java)
restore() {
    git checkout -- "${INJECT_TRACKED[@]}" 2>/dev/null
    git rm -q --cached --ignore-unmatch -- "${INJECT_INDEXED[@]}" >/dev/null 2>&1
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
            "$name" "$expect" "$(echo "$got" | head -3 | tr '\n' '|')")\n"
        # Diagnostics: a CI-only failure with no evidence is unactionable.
        {
            echo "---- diagnostics for failing case: $name ----"
            echo "  rg: $(rg --version 2>&1 | head -1)  bash: $BASH_VERSION"
            echo "  injected diff:"; git diff --unified=1 -- "${INJECT_TRACKED[@]}" 2>/dev/null | grep '^+' | grep -v '^+++' | head -6 | sed 's/^/    /'
            echo "  untracked created:"; for f in "${INJECT_CREATED[@]}"; do [ -e "$f" ] && echo "    $f"; done
            echo "  full gate FAIL output:"; echo "${out:-<none>}" | sed 's/^/    /'
        } >&2
    fi
}

# Insert a line after the first line of $1 matching the regex $2 (a class
# declaration ending in '{'). inject_in_class keys off the first 'class ' in
# the text, which a class Javadoc mentioning a "class " would capture, putting
# the snippet inside the comment, where a code-mode check rightly ignores it.
inject_after_line() {
    python3 - "$1" "$2" "$3" <<'PY'
import re, sys
path, pattern, snippet = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path).read().split('\n')
i = next(n for n, line in enumerate(lines) if re.search(pattern, line))
lines.insert(i + 1, snippet)
open(path, 'w').write('\n'.join(lines))
PY
}

# run_clean_case <name> <check-FAIL-substring> <check-PASS-substring> <setup-fn>
# The negative twin of run_case: the setup must NOT make the check fail, and the
# check must still print its PASS line, so a check that stopped running is not
# mistaken for a clean result.
run_clean_case() {
    local name="$1" fail="$2" pass="$3" setup="$4"
    restore
    "$setup"
    local out
    out=$("$GATE" < /dev/null 2>&1 | sed -e 's/\x1b\[[0-9;]*m//g' || true)
    restore
    if ! echo "$out" | grep '^  FAIL' | grep -qF "$fail" \
            && echo "$out" | grep '^  PASS' | grep -qF "$pass"; then
        PASSED=$((PASSED+1))
        RESULTS="${RESULTS}$(printf "${GREEN}  CLEAN${NC}   %s" "$name")\n"
    else
        FAILED=$((FAILED+1))
        RESULTS="${RESULTS}$(printf "${RED}  WRONG${NC}   %s\n            expected no FAIL '%s' and a PASS '%s'\n            got     : %s" \
            "$name" "$fail" "$pass" "$(echo "$out" | grep -E '^  (FAIL|PASS)' | grep -iF 'AI state' | head -3 | tr '\n' '|')")\n"
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
# A Java file outside every Spotless include, staged as intent-to-add.
s_uncovered()   { printf 'public class ZzzUncovered {\n}\n' > e2e/ZzzUncovered.java
                  git add -N e2e/ZzzUncovered.java; }
# The current release version, typed into a workflow. Derived here, never
# spelled out: this file is itself under scripts/, which the check scans.
s_hardcoded_ver(){ local v
                   v=$(python3 -c 'import re; print(re.search(r"atmosphere-project</artifactId>.*?<version>(\d+\.\d+\.\d+)", open("pom.xml").read(), re.S).group(1))')
                   printf '\n# zzz\nenv:\n  ZZZ_RELEASE: "%s"\n' "$v" >> .github/workflows/e2e.yml; }

# AI state read through the process-wide statics instead of the application.
CLASS_DECL='^public (final )?class '
s_ai_static()   { inject_after_line "$AI_PROC" "$CLASS_DECL" \
                    '    private Object zzzAi() { return org.atmosphere.ai.AiConfig.get(); }'; }
s_ai_methodref(){ inject_after_line "$AI_PROC" "$CLASS_DECL" \
                    '    private final java.util.function.Supplier<?> zzzAi = org.atmosphere.ai.AgentRuntimeResolver::resolve;'; }
s_ai_ambient()  { inject_after_line "$AI_PROC" "$CLASS_DECL" \
                    '    private Object zzzAi() { return org.atmosphere.ai.AiEnvironment.current().settings(); }'; }
s_ai_kotlin()   { printf '\nprivate fun zzzAi() = org.atmosphere.ai.AiConfig.knob(org.atmosphere.ai.AiConfig.RUNTIME_PROPERTY)\n' >> "$AI_KT"; }
s_ai_quarkus()  { inject_after_line "$AI_QUARKUS" "$CLASS_DECL" \
                    '    static Object zzzAi() { return org.atmosphere.ai.AgentRuntimeResolver.resolveAll(); }'; }
s_ai_noarg()    { inject_after_line "$AI_PROC" "$CLASS_DECL" \
                    '    private Object zzzAi() { return org.atmosphere.ai.EmbeddingRuntimeResolver.resolve(); }'; }
# An allowlisted read that goes away must take its allowlist entry with it.
s_ai_stale()    { python3 - "$AI_RERANKER" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "AiEnvironment.current().runtimes().resolve()"
assert old in s, "the allowlisted LlmReranker read moved; update s_ai_stale"
open(p, 'w').write(s.replace(old, "AiEnvironment.processDefault().runtimes().resolve()"))
PY
}
# A comment or Javadoc naming the static API reads nothing, and neither does a
# resolver overload that is handed the application.
s_ai_comment()  { inject_after_line "$AI_PROC" "$CLASS_DECL" \
                    '    // AiConfig.get() and AgentRuntimeResolver::resolve read the process default.
    /** Not {@link org.atmosphere.ai.AiEnvironment#current()}: AiEnvironment.current() is the facade. */
    private static Object zzzAi(org.atmosphere.ai.AiEnvironment e) { return org.atmosphere.ai.decision.DecisionModelResolver.resolve(e); }'; }

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
run_case "bare-disabled"       "@Disabled test(s) with no reason string"             s_disabled
run_case "disabled-fqn"        "@Disabled test(s) with no reason string"             s_disabled_fqn
run_case "junit4-ignore"       "@Ignore annotations (JUnit 4 legacy"                 s_ignore
run_case "ignore-fqn"          "@Ignore annotations (JUnit 4 legacy"                 s_ignore_fqn
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
run_case "spotless-coverage"   "outside every Spotless include"                      s_uncovered
run_case "hardcoded-version"   "hardcoded Atmosphere version literal"                s_hardcoded_ver
run_case "ai-state-static"     "process-wide AI state read"                          s_ai_static
run_case "ai-state-methodref"  "process-wide AI state read"                          s_ai_methodref
run_case "ai-state-ambient"    "process-wide AI state read"                          s_ai_ambient
run_case "ai-state-kotlin"     "process-wide AI state read"                          s_ai_kotlin
run_case "ai-state-quarkus"    "process-wide AI state read"                          s_ai_quarkus
run_case "ai-state-no-arg"     "process-wide AI state read"                          s_ai_noarg
run_case "ai-state-stale"      "Stale [[ai_application_state.allowed]]"              s_ai_stale
run_clean_case "ai-state-comment" "process-wide AI state read" \
                                  "AI state read through the application"            s_ai_comment

echo ""
echo "==== Gate bite-proof ===="
printf "%b" "$RESULTS"
echo "-------------------------"
echo "  bites: $PASSED   no-op: $FAILED"
[ "$FAILED" -eq 0 ] || exit 1

#!/usr/bin/env bash
# Proves that each doc/consistency gate run by .github/workflows/doc-gates.yml
# fails on a real violation, names the violation it caught, and passes on a
# clean tree.
#
# The companion of prove_gate.sh (which proves architectural-validation.sh).
# These gates read the tree through git (ls-files, grep, diff against a base
# ref), so a violation written into the working tree of the real checkout would
# either be invisible to them or would have to be reverted afterwards. Every
# case therefore runs in a throwaway clone under $TMPDIR: the clone takes the
# current scripts/ and .harness/ from the working tree (so uncommitted edits to
# a gate are what gets graded), commits them as the baseline, points its
# origin/main at that baseline for the diff-aware gates, and each case commits
# its injection on top and is reset afterwards. The real checkout is never
# written to.
#
# Each bite case asserts the gate's own message for the injected violation,
# never merely a non-zero exit: a gate failing for an unrelated reason would
# otherwise be credited with catching it.
#
# Usage: scripts/lib/prove_doc_gates.sh [case-name ...]     (default: all)

set -uo pipefail

SRC=$(git rev-parse --show-toplevel)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/prove-doc-gates.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
CLONE="$WORK/repo"

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
PASSED=0; FAILED=0; RESULTS=""

SELECTED=("$@")
selected() {
    [ "${#SELECTED[@]}" -eq 0 ] && return 0
    local s
    for s in "${SELECTED[@]}"; do
        [ "$s" = "$1" ] && return 0
    done
    return 1
}

# A fixed identity for the clone's commits: CI runners have none configured.
export GIT_AUTHOR_NAME="prove-doc-gates" GIT_AUTHOR_EMAIL="prove-doc-gates@invalid"
export GIT_COMMITTER_NAME="prove-doc-gates" GIT_COMMITTER_EMAIL="prove-doc-gates@invalid"
# The sibling atmosphere.github.io checkout exists on some dev machines and not
# in CI; point the site-scanning gates at a directory that does not exist so a
# local run grades exactly what CI grades. The advisory case sets its own.
export ATMOSPHERE_SITE_DIR="$WORK/no-site"
unset BASE_REF

git clone -q --shared --no-checkout "$SRC" "$CLONE"
git -C "$CLONE" checkout -q --detach "$(git -C "$SRC" rev-parse HEAD)"
git -C "$CLONE" config core.hooksPath /dev/null
cp -R "$SRC/scripts/." "$CLONE/scripts/"
cp -R "$SRC/.harness/." "$CLONE/.harness/"
git -C "$CLONE" add -A scripts .harness
git -C "$CLONE" commit -q --allow-empty -m "baseline: working-tree gates"
BASE=$(git -C "$CLONE" rev-parse HEAD)
git -C "$CLONE" update-ref refs/remotes/origin/main "$BASE"

reset_clone() {
    git -C "$CLONE" reset -q --hard "$BASE"
    git -C "$CLONE" clean -qfd
}

commit_clone() {
    git -C "$CLONE" add -A
    git -C "$CLONE" commit -q --allow-empty -m "case: $1"
}

# Write $2 to the clone-relative path $1, creating parent directories.
put() {
    mkdir -p "$(dirname "$CLONE/$1")"
    printf '%s\n' "$2" > "$CLONE/$1"
}

GATE_OUT=""; GATE_RC=0
run_gate() {
    GATE_OUT=$(cd "$CLONE" && "./scripts/validate-$1.sh" < /dev/null 2>&1)
    GATE_RC=$?
}

# bites <case> <gate> <expected-message-substring> <setup-fn>
bites() {
    local name="$1" gate="$2" expect="$3" setup="$4"
    selected "$name" || return 0
    reset_clone
    "$setup"
    commit_clone "$name"
    run_gate "$gate"
    reset_clone
    if [ "$GATE_RC" -ne 0 ] && printf '%s\n' "$GATE_OUT" | grep -qF -- "$expect"; then
        PASSED=$((PASSED+1))
        RESULTS="${RESULTS}$(printf "${GREEN}  BITES${NC}   %-34s (validate-%s.sh)" "$name" "$gate")\n"
    else
        FAILED=$((FAILED+1))
        RESULTS="${RESULTS}$(printf "${RED}  NO-OP${NC}   %-34s (validate-%s.sh, exit %s)\n            expected: %s" \
            "$name" "$gate" "$GATE_RC" "$expect")\n"
        { echo "---- output of validate-$gate.sh for failing case: $name ----"
          printf '%s\n' "$GATE_OUT" | head -20 | sed 's/^/    /'; } >&2
    fi
}

# clean <case> <gate> <expected-OK-substring> [setup-fn]
# The gate must exit 0 AND print its OK line, so a gate that stopped running is
# not mistaken for a clean result.
clean() {
    local name="$1" gate="$2" expect="$3" setup="${4:-}"
    selected "$name" || return 0
    reset_clone
    if [ -n "$setup" ]; then
        "$setup"
        commit_clone "$name"
    fi
    run_gate "$gate"
    reset_clone
    if [ "$GATE_RC" -eq 0 ] && printf '%s\n' "$GATE_OUT" | grep -qF -- "$expect"; then
        PASSED=$((PASSED+1))
        RESULTS="${RESULTS}$(printf "${GREEN}  CLEAN${NC}   %-34s (validate-%s.sh)" "$name" "$gate")\n"
    else
        FAILED=$((FAILED+1))
        RESULTS="${RESULTS}$(printf "${RED}  WRONG${NC}   %-34s (validate-%s.sh, exit %s)\n            expected exit 0 and: %s" \
            "$name" "$gate" "$GATE_RC" "$expect")\n"
        { echo "---- output of validate-$gate.sh for failing case: $name ----"
          printf '%s\n' "$GATE_OUT" | head -20 | sed 's/^/    /'; } >&2
    fi
}

# ---- Values derived from the tree, never hardcoded ---------------------------

# The private handle is read from the gate: this file is in that gate's scope,
# so it must never carry the literal.
HANDLE=$(sed -n "s/^HANDLE='\(.*\)'$/\1/p" "$CLONE/scripts/validate-no-private-handle.sh")
if [ -z "$HANDLE" ]; then
    echo "prove_doc_gates.sh: cannot read HANDLE= from validate-no-private-handle.sh" >&2
    exit 2
fi
# The no-beta markers are assembled at run time for the same reason: a literal
# added here would trip that gate on the push that adds it.
BETA_TAG="@""Beta"
# The Atmosphere version band the third-party gate guards, from pom.xml; a
# hardcoded version would leave the band at the next release decade.
ATMO_VERSION=$(python3 - "$CLONE/pom.xml" <<'PY'
import re, sys
pom = open(sys.argv[1]).read()
m = re.search(r"atmosphere-project</artifactId>.*?<version>(\d+\.\d+\.\d+)", pom, re.S)
print(m.group(1) if m else "")
PY
)
if [ -z "$ATMO_VERSION" ]; then
    echo "prove_doc_gates.sh: cannot read the atmosphere-project version from pom.xml" >&2
    exit 2
fi

PROBE_PKG_DIR=modules/cpr/src/main/java/org/atmosphere/util

# ---- Setups ------------------------------------------------------------------

s_doc_symbol() {
    put docs/zzz-probe-symbols.md 'Annotate the handler with `@ZzzPhantomAnnotation`.'
}
s_phantom_javadoc() {
    put "$PROBE_PKG_DIR/ZzzJavadocProbe.java" "package org.atmosphere.util;

/** Hands off to {@link ZzzPhantomDispatcher}. */
final class ZzzJavadocProbe {
}"
}
s_orphan() {
    put "$PROBE_PKG_DIR/ZzzOrphanProbe.java" "package org.atmosphere.util;

public class ZzzOrphanProbe {
}"
}
s_orphan_wired() {
    s_orphan
    put "$PROBE_PKG_DIR/ZzzOrphanProbeUser.java" "package org.atmosphere.util;

final class ZzzOrphanProbeUser {
    private final ZzzOrphanProbe probe = new ZzzOrphanProbe();
}"
}
s_backend() {
    put docs/zzz-probe-backend.md 'Long-term memory is backed by RedisZzzProbeStore.'
}
s_facts_date() {
    put docs/zzz-probe-facts.md 'Atmosphere has been open source since 1999.'
}
s_facts_superlative() {
    put docs/zzz-probe-facts.md "Atmosphere is the world's first framework of its kind."
}
s_thirdparty() {
    put docs/zzz-probe-thirdparty.md "\`\`\`xml
<dependency>
    <groupId>com.example.zzz</groupId>
    <artifactId>zzz-probe</artifactId>
    <version>${ATMO_VERSION}</version>
</dependency>
\`\`\`"
}
s_doc_version() {
    put docs/zzz-probe-version.md 'This guide was written against Quarkus 0.0.1.'
}
s_handle() {
    put docs/zzz-probe-handle.md "Thanks to ${HANDLE} for the review."
}
s_enumeration() {
    put docs/zzz-probe-enumeration.md 'Streaming works on 3 of 99 runtimes.'
}
s_drift_future() {
    printf '\n## 2099-12-31\n' >> "$CLONE/.harness/drift-log.md"
}
s_drift_order() {
    printf '\n## 2000-01-01\n' >> "$CLONE/.harness/drift-log.md"
}
s_drift_edit_past() {
    # Rewrite the oldest date header; origin/main (the baseline) still has it.
    python3 - "$CLONE/.harness/drift-log.md" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
m = re.search(r"^## [0-9]{4}-[0-9]{2}-[0-9]{2}[^\n]*", s, re.M)
open(p, "w").write(s[:m.end()] + " (rewritten)" + s[m.end():])
PY
}
s_no_beta() {
    put docs/zzz-probe-beta.md "Status: ${BETA_TAG} until the matrix closes."
}
# The removed .harness/no-beta-allowlist.txt alternative must no longer exempt
# that path: a file committed there is scanned like any other.
s_no_beta_former_allowlist() {
    put .harness/no-beta-allowlist.txt "${BETA_TAG}"
}
s_no_beta_benign() {
    put docs/zzz-probe-beta.md 'Status: shipped and documented.'
}
s_site_advisory() {
    mkdir -p "$WORK/site/src/content"
    printf '%s\n' "Atmosphere is the world's first framework of its kind." \
        > "$WORK/site/src/content/zzz-probe.md"
}

# ---- Cases -------------------------------------------------------------------

bites doc-symbols-phantom-annotation doc-symbols \
    "references @ZzzPhantomAnnotation" s_doc_symbol
clean doc-symbols-clean doc-symbols "validate-doc-symbols.sh: OK"

bites phantom-javadoc-ref no-phantom-javadoc-refs \
    "Phantom Javadoc class ref: {@code/@link ZzzPhantomDispatcher}" s_phantom_javadoc
clean phantom-javadoc-clean no-phantom-javadoc-refs "validate-no-phantom-javadoc-refs.sh: OK"

bites orphan-class-added no-orphan-classes \
    "org.atmosphere.util.ZzzOrphanProbe" s_orphan
clean orphan-class-wired no-orphan-classes \
    "validate-no-orphan-classes.sh: OK" s_orphan_wired

bites backend-class-ref backend-class-refs \
    "Unresolved backend-class reference: RedisZzzProbeStore" s_backend
clean backend-class-clean backend-class-refs "validate-backend-class-refs.sh: OK"

bites facts-business-date facts-registry \
    "says 'open source since 1999' but facts.json pins" s_facts_date
bites facts-superlative facts-registry \
    "uses unverified superlative" s_facts_superlative
clean facts-clean facts-registry "validate-facts-registry.sh: OK"
# A site-only finding is advisory: the gate prints it and still exits 0, so the
# CI runner (no sibling checkout) and a dev box (with one) agree on the verdict.
if selected facts-site-advisory; then
    s_site_advisory
    ATMOSPHERE_SITE_DIR="$WORK/site" run_gate facts-registry
    if [ "$GATE_RC" -eq 0 ] && printf '%s\n' "$GATE_OUT" | grep -qF "[advisory/site]"; then
        PASSED=$((PASSED+1))
        RESULTS="${RESULTS}$(printf "${GREEN}  CLEAN${NC}   %-34s (validate-facts-registry.sh)" facts-site-advisory)\n"
    else
        FAILED=$((FAILED+1))
        RESULTS="${RESULTS}$(printf "${RED}  WRONG${NC}   %-34s (exit %s; expected 0 and an [advisory/site] line)" \
            facts-site-advisory "$GATE_RC")\n"
        printf '%s\n' "$GATE_OUT" | head -20 | sed 's/^/    /' >&2
    fi
fi

bites thirdparty-atmosphere-version doc-thirdparty-versions \
    "third-party dep com.example.zzz:zzz-probe carries Atmosphere version '${ATMO_VERSION}'" s_thirdparty
clean thirdparty-clean doc-thirdparty-versions "validate-doc-thirdparty-versions.sh: OK"

bites doc-version-stale doc-version-alignment \
    "names 'Quarkus 0.0.1' but the pinned version is" s_doc_version
clean doc-version-clean doc-version-alignment "validate-doc-version-alignment.sh: OK"

bites private-handle-leak no-private-handle \
    "docs/zzz-probe-handle.md:1:" s_handle
clean private-handle-clean no-private-handle "validate-no-private-handle.sh: OK"

bites enumeration-denominator capability-claims \
    "says '3 of 99 runtimes' but the snapshot has" s_enumeration
clean capability-claims-clean capability-claims "validate-capability-claims.sh: OK"

bites drift-log-future-section drift-log \
    "future-dated section '2099-12-31'" s_drift_future
bites drift-log-out-of-order drift-log \
    "section '2000-01-01' precedes" s_drift_order
bites drift-log-past-header-edited drift-log \
    "append-only violation" s_drift_edit_past
clean drift-log-clean drift-log "validate-drift-log.sh: OK"

bites no-beta-added no-beta-on-main \
    "docs/zzz-probe-beta.md: Status: ${BETA_TAG}" s_no_beta
bites no-beta-former-allowlist-path no-beta-on-main \
    ".harness/no-beta-allowlist.txt: ${BETA_TAG}" s_no_beta_former_allowlist
clean no-beta-benign-addition no-beta-on-main \
    "validate-no-beta-on-main.sh: OK" s_no_beta_benign

# gate-selftest.yml is path-filtered: it must run when a gate, this proof or a
# file a case edits changes, on push and on pull_request alike, or a change
# that turns a case into a NO-OP lands unproven.
if selected selftest-path-filter; then
    WATCHED_FILES=(scripts/lib/prove_doc_gates.sh .harness/drift-log.md .harness/facts.json
                   .harness/capabilities.snapshot.json pom.xml)
    for g in doc-symbols no-phantom-javadoc-refs no-orphan-classes backend-class-refs \
             facts-registry doc-thirdparty-versions doc-version-alignment no-private-handle \
             capability-claims drift-log no-beta-on-main; do
        WATCHED_FILES+=("scripts/validate-$g.sh")
    done
    if UNWATCHED=$(python3 - "$SRC/.github/workflows/gate-selftest.yml" "${WATCHED_FILES[@]}" <<'PY'
import re, sys
workflow, files = sys.argv[1], sys.argv[2:]
TRIGGERS = ("push", "pull_request")
globs, trigger, in_on = {t: [] for t in TRIGGERS}, None, False
for line in open(workflow).read().split("\n"):
    if re.match(r"^on:\s*$", line):
        in_on = True
        continue
    if in_on and re.match(r"^\S", line):
        break
    key = re.match(r"^  (\w+):", line)
    if in_on and key:
        trigger = key.group(1)
        continue
    item = re.match(r"^\s+-\s*'([^']+)'\s*$", line)
    if in_on and item and trigger in globs:
        globs[trigger].append(item.group(1))

def to_regex(glob):
    out, i = "", 0
    while i < len(glob):
        if glob.startswith("**", i):
            out += ".*"; i += 2
        elif glob[i] == "*":
            out += "[^/]*"; i += 1
        else:
            out += re.escape(glob[i]); i += 1
    return re.compile(out + r"\Z")

missing = []
for t in TRIGGERS:
    patterns = [to_regex(g) for g in globs[t]]
    if not patterns:
        missing.append(f"<{t}: no paths parsed>")
        continue
    missing += [f"{f} ({t})" for f in files if not any(p.match(f) for p in patterns)]
print("\n".join(missing))
sys.exit(1 if missing else 0)
PY
    ); then
        PASSED=$((PASSED+1))
        RESULTS="${RESULTS}$(printf "${GREEN}  WATCHED${NC} %s" selftest-path-filter)\n"
    else
        FAILED=$((FAILED+1))
        RESULTS="${RESULTS}$(printf "${RED}  UNWATCHED${NC} %s\n            gate-selftest.yml does not run on a change to: %s" \
            selftest-path-filter "$(echo "$UNWATCHED" | tr '\n' ' ')")\n"
    fi
fi

echo ""
echo "==== Doc gate bite-proof ===="
printf "%b" "$RESULTS"
echo "-----------------------------"
echo "  passed: $PASSED   failed: $FAILED"
[ "$FAILED" -eq 0 ] || exit 1

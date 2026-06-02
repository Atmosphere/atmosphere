#!/usr/bin/env bash
#
# Catch detached / dangling Javadoc comments locally, before they reach the
# Native Image CI lane.
#
# Drift #80 (2026-05-28): an edit inserted a new class and its Javadoc between
# an existing class's Javadoc and that class's declaration, detaching the
# older doc comment. The project compiles with `-Xlint:all -Werror`, but the
# `dangling-doc-comments` lint category only exists on JDK 23+, so JDK 21
# (the project's pinned release and most devs' local JDK) cannot see it.
# Only the GraalVM JDK 25 Native Image lane caught it — after push — and the
# project maintainer had to push the fix. `scripts/pre-push-validate.sh` builds
# with whatever `mvnw` resolves locally, so the break was invisible pre-push.
#
# This gate runs a parse-only `javac -Xlint:dangling-doc-comments` under a
# JDK >= 23 (the dangling-doc warning is emitted at parse time, before symbol
# resolution, so no classpath is needed) and fails if any source carries a
# detached doc comment. It degrades gracefully: if no JDK >= 23 is found it
# prints a notice and exits 0 — the Native Image lane remains the backstop.
#
# Usage:
#   scripts/validate-dangling-doc-comments.sh            # changed *.java vs BASE_REF
#   scripts/validate-dangling-doc-comments.sh --all      # every tracked *.java
#   DOCLINT_JAVAC=/path/to/javac scripts/validate-dangling-doc-comments.sh
#
# Run from repo root. Exits 0 on success (or graceful skip), 1 on any dangling
# doc comment.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

BASE_REF="${BASE_REF:-origin/main}"
MODE="changed"
[ "${1:-}" = "--all" ] && MODE="all"

# ── Locate a javac with the dangling-doc-comments lint (JDK >= 23) ──
find_javac() {
    # 1. Explicit override.
    if [ -n "${DOCLINT_JAVAC:-}" ] && [ -x "${DOCLINT_JAVAC}" ]; then
        echo "${DOCLINT_JAVAC}"; return 0
    fi
    # 2. macOS java_home — ask for the newest >= 23.
    if [ -x /usr/libexec/java_home ]; then
        for v in 26 25 24 23; do
            home="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
            if [ -n "$home" ] && [ -x "$home/bin/javac" ]; then
                echo "$home/bin/javac"; return 0
            fi
        done
    fi
    # 3. JAVA_HOME / PATH javac, if it is new enough.
    for cand in "${JAVA_HOME:+$JAVA_HOME/bin/javac}" "$(command -v javac 2>/dev/null || true)"; do
        [ -n "$cand" ] && [ -x "$cand" ] || continue
        major="$("$cand" -version 2>&1 | grep -oE '[0-9]+' | head -1 || echo 0)"
        if [ "${major:-0}" -ge 23 ] 2>/dev/null; then
            echo "$cand"; return 0
        fi
    done
    return 1
}

JAVAC="$(find_javac || true)"
if [ -z "$JAVAC" ]; then
    echo "validate-dangling-doc-comments.sh: no JDK >= 23 found (dangling-doc-comments" \
         "lint needs JDK 23+); skipping local check — the Native Image CI lane is the backstop." >&2
    exit 0
fi

# ── Collect target sources ──
# Scope to reactor-compiled sources only (modules|samples .../src/...). The
# Native Image lane this gate mirrors compiles exactly these; top-level jbang
# scripts (generator/*.java) carry a `///usr/bin/env jbang` shebang that JDK
# 23+ parses as a `///` Markdown doc comment, which is a false positive here.
REACTOR_RE='^(modules|samples)/.*/src/(main|test)/.*\.java$'
if [ "$MODE" = "all" ]; then
    mapfile -t FILES < <(git ls-files '*.java' | grep -E "$REACTOR_RE" || true)
else
    # Changed vs BASE_REF; fall back to the working-tree diff if the ref is
    # unavailable (e.g. a fresh worktree without the remote fetched).
    if git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
        mapfile -t FILES < <({ git diff --name-only --diff-filter=ACMR "$BASE_REF"...HEAD -- '*.java'; git diff --name-only --diff-filter=ACMR --cached -- '*.java'; git diff --name-only --diff-filter=ACMR -- '*.java'; } | grep -E "$REACTOR_RE" || true)
    else
        mapfile -t FILES < <({ git diff --name-only --diff-filter=ACMR --cached -- '*.java'; git diff --name-only --diff-filter=ACMR -- '*.java'; } | grep -E "$REACTOR_RE" || true)
    fi
    # De-dupe and keep only files that still exist.
    mapfile -t FILES < <(printf '%s\n' "${FILES[@]}" | sort -u | while read -r f; do [ -f "$f" ] && echo "$f"; done)
fi

if [ "${#FILES[@]}" -eq 0 ]; then
    echo "validate-dangling-doc-comments.sh: no Java sources to check."
    exit 0
fi

# ── Parse-only compile; isolate the dangling-doc-comments category ──
# We do NOT use -Werror or -Xlint:all here: only the dangling category is
# enabled, and unrelated symbol-resolution errors (expected without a
# classpath) are ignored. The dangling warnings are emitted at parse time
# regardless, so grepping the diagnostic stream is sufficient and accurate.
TMP_OUT="$(mktemp -d)"
trap 'rm -rf "$TMP_OUT"' EXIT

DIAG="$("$JAVAC" -Xlint:dangling-doc-comments -proc:none \
        -d "$TMP_OUT/classes" "${FILES[@]}" 2>&1 || true)"

HITS="$(printf '%s\n' "$DIAG" | grep -E '\[dangling-doc-comments\]' || true)"

if [ -n "$HITS" ]; then
    echo "validate-dangling-doc-comments.sh: detached/dangling Javadoc comment(s) found" >&2
    echo "(a doc comment not immediately attached to a declaration — would fail the JDK 25 Native Image lane):" >&2
    # Print the file:line lines plus the offending comment for context.
    printf '%s\n' "$DIAG" | grep -B0 -A2 -E '\[dangling-doc-comments\]' >&2
    echo "" >&2
    echo "Fix: move the doc comment to immediately precede the declaration it documents" >&2
    echo "(no blank line / no other comment in between), or delete it if orphaned." >&2
    exit 1
fi

checked="${#FILES[@]}"
echo "validate-dangling-doc-comments.sh: OK ($checked Java file(s) checked with $("$JAVAC" -version 2>&1) — no dangling doc comments)"

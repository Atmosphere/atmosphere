#!/usr/bin/env bash
#
# Catch phantom annotation references in Markdown — symbols documented as
# Atmosphere API that do not exist anywhere in the source tree.
#
# Drift #72 (2026-05-27): agui.md / a2a.md ran handler examples on
# @AgUiEndpoint, @Param, ActionResult, TaskResult — none of which exist in
# the codebase. The prose was self-consistent and idiomatic, so it survived
# review; no automated check verified that the symbols quoted in docs are
# real. (Javadoc doclint only warns on unresolved {@link}, never on prose.)
#
# This gate scans every @Annotation referenced inside a Markdown code span or
# fenced block and fails if it is neither (a) declared as `@interface <Name>`
# under modules|samples/**/src/main, nor (b) listed in
# .harness/doc-symbol-allowlist.txt (the curated set of external-framework /
# JDK / deliberate-non-existent references). A new phantom annotation in a doc
# is therefore caught the moment it is committed.
#
# Scope is intentionally limited to @Annotations: they are an unambiguous,
# low-false-positive token. Free-prose type names (e.g. drift #82's "ADK
# Embedder") are not checked here — that class is covered by review + the
# verify-before-quote discipline. The sibling atmosphere.github.io repo is
# out of scope (separate repo); this gate covers in-repo Markdown only.
#
# Run from repo root. Exits 0 on success, 1 on any phantom annotation.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/doc-symbol-allowlist.txt"

python3 - "$ALLOWLIST" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

# Declared annotations in this repo.
declared = set()
out = subprocess.check_output(
    ["git", "grep", "-hoE", r"@interface [A-Z][A-Za-z0-9]+", "--",
     "modules/*/src/main/*.java", "samples/*/src/main/*.java"]
).decode()
declared = set(re.findall(r"@interface ([A-Z][A-Za-z0-9]+)", out))

# Allowlist (external / JDK / deliberate-non-existent).
allow = set()
ap = Path(sys.argv[1])
if ap.exists():
    for line in ap.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            allow.add(line)

known = declared | allow

# Annotation tokens inside inline code or fenced code blocks. The negative
# lookbehind rejects tokens glued to a preceding identifier char (e.g.
# "toolkit@April-2026") — those are tags/handles, not annotations.
tok = re.compile(r"(?<![A-Za-z0-9_])@([A-Z][A-Za-z0-9]+)")
inline = re.compile(r"`([^`\n]+)`")
fenced = re.compile(r"```[a-zA-Z0-9]*\n(.*?)```", re.S)
SKIP = (".harness/drift-log.md", "node_modules")

files = subprocess.check_output(["git", "ls-files", "*.md"]).decode().split()

failed = False
for f in files:
    if any(s in f for s in SKIP):
        continue
    try:
        text = Path(f).read_text()
    except (OSError, UnicodeDecodeError):
        continue
    # Build a set of (annotation, line_no) so we can report a location. We
    # re-scan line by line but only count tokens that fall inside a code span
    # or fenced block (checked against the whole-file span set).
    code_regions = set()
    for m in inline.finditer(text):
        code_regions.add((m.start(1), m.end(1)))
    for m in fenced.finditer(text):
        code_regions.add((m.start(1), m.end(1)))

    def in_code(pos):
        return any(a <= pos < b for a, b in code_regions)

    for m in tok.finditer(text):
        if not in_code(m.start()):
            continue
        name = m.group(1)
        if name in known:
            continue
        line_no = text.count("\n", 0, m.start()) + 1
        print(
            f"validate-doc-symbols.sh: {f}:{line_no} references @{name}, which is "
            f"not declared under modules|samples/**/src/main and is not allowlisted",
            file=sys.stderr,
        )
        failed = True

if failed:
    print("", file=sys.stderr)
    print("If @<Name> is a real Atmosphere annotation, ensure it is declared in source.", file=sys.stderr)
    print(f"If it is an external/framework annotation, add its name to {sys.argv[1]}.", file=sys.stderr)
    print("If it is a phantom (documented but never implemented), fix the doc.", file=sys.stderr)
    sys.exit(1)

print(f"validate-doc-symbols.sh: OK ({len(declared)} in-tree annotations, "
      f"{len(allow)} allowlisted — no phantom annotation references in *.md)")
PY

#!/usr/bin/env bash
#
# Validate the ungreppable doc-claim classes against the pinned source of
# truth in .harness/facts.json:
#
#   1. Business-date assertions — every prose copy of a pinned date
#      ("enterprise support since 2013", "open source since 2008", ...)
#      must carry the registry's year. Catches drift-log #22, where the
#      website said "support since 2014" when the verified year is 2013.
#
#   2. Forbidden superlatives — unverifiable marketing claims ("first Java
#      framework", "world's first", ...) must NOT appear unless they are
#      explicitly registered in facts.json `allowed_superlatives` with an
#      evidence note. Catches drift-log #21, the "first Java framework with
#      native WebTransport support" claim shipped to the public site with
#      no competitive survey behind it.
#
# Scope of enforcement (mirrors drift-log #20's "sibling coupling is loose
# by design" decision):
#   - MAIN repo findings are HARD failures (exit 1).
#   - SIBLING atmosphere.github.io findings are ADVISORY warnings (exit 0):
#     the site has its own publish lifecycle, and a local checkout has the
#     sibling while CI does not, so a hard gate would diverge local-vs-CI.
#     Strict cross-repo enforcement is the doc-drift auditor's job.
# The sibling is located via $ATMOSPHERE_SITE_DIR, else ../atmosphere.github.io,
# else ~/workspace/atmosphere/atmosphere.github.io; absent → main-repo only.
#
# Append-only / historical surfaces (.harness/drift-log.md, CHANGELOG.md,
# docs/audits/, the site's whats-new.md) are skipped: they legitimately
# quote superseded facts.
#
# Run from repo root. Exits 0 on success (or sibling-only advisories), 1 on
# any main-repo drift.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

FACTS=".harness/facts.json"
if [ ! -f "$FACTS" ]; then
    echo "validate-facts-registry.sh: $FACTS not found" >&2
    exit 1
fi

python3 - "$FACTS" <<'PY'
import json
import os
import re
import subprocess
import sys
from pathlib import Path

facts = json.loads(Path(sys.argv[1]).read_text())

# ── Build the file set: main-repo tracked Markdown + sibling site sources ──
# (path, is_site) tuples. Historical/append-only surfaces are skipped.
SKIP_SUBSTR = (".harness/drift-log.md", "CHANGELOG.md", "docs/audits/",
               "whats-new.md", "node_modules")

def skipped(path_str):
    return any(s in path_str.replace("\\", "/") for s in SKIP_SUBSTR)

entries = []  # (Path, is_site)
for f in subprocess.check_output(["git", "ls-files", "*.md"]).decode().split():
    if not skipped(f):
        entries.append((Path(f), False))

site_dir = os.environ.get("ATMOSPHERE_SITE_DIR")
if not site_dir:
    for cand in ("../atmosphere.github.io",
                 os.path.expanduser("~/workspace/atmosphere/atmosphere.github.io")):
        if Path(cand).is_dir():
            site_dir = cand
            break

if site_dir and Path(site_dir).is_dir():
    site_root = Path(site_dir)
    SITE_PRUNE = {"node_modules", ".git", "dist", ".astro", "build", ".output"}
    for ext in ("*.astro", "*.md", "*.mdx"):
        for p in site_root.rglob(ext):
            if any(part in SITE_PRUNE for part in p.parts) or skipped(str(p)):
                continue
            entries.append((p, True))
    print(f"validate-facts-registry.sh: scanning sibling site at {site_dir} "
          "(advisory)", file=sys.stderr)
else:
    print("validate-facts-registry.sh: sibling atmosphere.github.io not found — "
          "main-repo scan only (set ATMOSPHERE_SITE_DIR to include it)", file=sys.stderr)

contents = []  # (Path, is_site, text)
for p, is_site in entries:
    try:
        contents.append((p, is_site, p.read_text()))
    except (OSError, UnicodeDecodeError):
        continue

YEAR = re.compile(r"[0-9]{4}")
hard_fail = False

def report(is_site, msg, line):
    global hard_fail
    prefix = "validate-facts-registry.sh:"
    if is_site:
        print(f"{prefix} [advisory/site] {msg}", file=sys.stderr)
    else:
        print(f"{prefix} {msg}", file=sys.stderr)
        hard_fail = True
    print(f"    line: {line.strip()}", file=sys.stderr)

# ── 1. Business-date assertions ──
for a in facts.get("date_assertions", []):
    rx = re.compile(a["pattern"], re.IGNORECASE)
    expect = str(a["expect"])
    label = a.get("label", a["pattern"])
    for p, is_site, text in contents:
        for line_no, line in enumerate(text.splitlines(), 1):
            for m in rx.finditer(line):
                ym = YEAR.search(m.group(0))
                if ym and ym.group(0) != expect:
                    report(is_site, f"{p}:{line_no} says '{label} {ym.group(0)}' "
                           f"but facts.json pins {expect}", line)

# ── 2. Forbidden superlatives ──
allowed = facts.get("allowed_superlatives", [])

def is_allowed(line):
    return any(re.search(a["phrase"], line, re.IGNORECASE)
               for a in allowed if "phrase" in a)

for pat in facts.get("forbidden_superlatives", []):
    rx = re.compile(pat, re.IGNORECASE)
    for p, is_site, text in contents:
        for line_no, line in enumerate(text.splitlines(), 1):
            if rx.search(line) and not is_allowed(line):
                report(is_site, f"{p}:{line_no} uses unverified superlative /{pat}/ "
                       "— soften it, OR register it in facts.json "
                       "`allowed_superlatives` with an evidence note", line)

if hard_fail:
    sys.exit(1)

print("validate-facts-registry.sh: OK (business dates + superlatives match "
      ".harness/facts.json)")
PY

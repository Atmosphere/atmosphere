#!/usr/bin/env bash
#
# Catch third-party dependency versions in Markdown prose that have drifted
# away from the version actually pinned in pom.xml / package.json.
#
# Drift #12 (Quarkus 3.31.3 in prose after the pom bumped to 3.35.2),
# #18 (Spring AI 2.0.0-M2 across four READMEs after the bump to M6),
# #75 (Embabel 0.3.4 + atmosphere.js 5.0.24 after their bumps): the same
# recurring class — a `<x>.version` property (or package.json version) moves,
# the pom/code is updated, but narrative prose naming the old full version is
# not swept. No existing gate correlates prose version strings with the
# source of truth (scripts/update-doc-versions.sh only rewrites Atmosphere's
# OWN release version, not third-party dependency versions).
#
# This gate reads the current version from its source of truth and fails when
# a DIFFERENT full (three-component) version of the same dependency appears in
# any tracked *.md. It deliberately:
#   - matches only full X.Y.Z[-q] versions (unambiguous "this exact version"
#     claims); fuzzy minimums like "3.21+" are out of scope to avoid noise;
#   - skips .harness/drift-log.md, CHANGELOG.md and the site's whats-new.md
#     (append-only history that legitimately quotes superseded versions);
#   - honours .harness/doc-version-allowlist.txt for documented exceptions.
#
# The sibling atmosphere.github.io site (located via $ATMOSPHERE_SITE_DIR,
# else ../atmosphere.github.io, else ~/workspace/atmosphere/atmosphere.github.io)
# is ALSO scanned when present, but its findings are ADVISORY only (exit 0):
# per drift-log #20 the cross-repo coupling is loose by design, and a hard
# gate would diverge local-vs-CI since CI has no sibling checkout. Main-repo
# findings remain hard failures.
#
# Spring AI and Spring AI Alibaba are intentionally NOT tracked: the tree
# legitimately carries two Spring AI lines at once (2.0.0-M6 on the main
# classpath, 1.1.x pulled transitively by the Alibaba adapter), so a single
# "current version" comparison would false-positive. Their drift is covered
# by review + the dual-version note in the allowlist.
#
# Run from repo root. Exits 0 on success, 1 on any stale version string.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/doc-version-allowlist.txt"

python3 - "$ALLOWLIST" <<'PY'
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# ── Sources of truth ──
pom = Path("pom.xml").read_text()

def pom_prop(name):
    m = re.search(rf"<{re.escape(name)}>([^<]+)</{re.escape(name)}>", pom)
    return m.group(1) if m else None

pkg_version = None
pkg = Path("atmosphere.js/package.json")
if pkg.exists():
    pkg_version = json.loads(pkg.read_text()).get("version")

# label_regex -> current version. The label is matched case-sensitively,
# immediately followed by whitespace and an optional ` or v, then a full
# semver. (?!...) guards keep "Spring AI Alibaba" from matching "Spring AI"
# — though both are excluded here, the pattern style is kept for clarity.
TRACKED = {
    r"Quarkus": pom_prop("quarkus.version"),
    r"Embabel(?: Agent(?: API)?)?": pom_prop("embabel.version"),
    r"Koog": pom_prop("koog.version"),
    r"LangChain4j": pom_prop("langchain4j.version"),
    r"atmosphere\.js": pkg_version,
}

allowlist_path = Path(sys.argv[1])
allow = []
if allowlist_path.exists():
    for line in allowlist_path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            allow.append(line)

def allowed(record):
    return any(token in record for token in allow)

SEMVER = r"[0-9]+\.[0-9]+\.[0-9]+(?:[.-][A-Za-z0-9.]+)?"
SKIP_SUBSTR = (".harness/drift-log.md", "CHANGELOG.md", "whats-new.md", "node_modules")

# (path, is_site) — main-repo tracked Markdown is strict; sibling site advisory.
entries = [(f, False) for f in
           subprocess.check_output(["git", "ls-files", "*.md"]).decode().split()]

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
            if any(part in SITE_PRUNE for part in p.parts):
                continue
            entries.append((str(p), True))
    print("validate-doc-version-alignment.sh: scanning sibling site at "
          f"{site_dir} (advisory)", file=sys.stderr)

hard_fail = False
for label, current in TRACKED.items():
    if not current:
        print(f"validate-doc-version-alignment.sh: no source-of-truth version for /{label}/ — skipping", file=sys.stderr)
        continue
    rx = re.compile(rf"{label}\s+`?v?({SEMVER})")
    for f, is_site in entries:
        if any(s in f for s in SKIP_SUBSTR):
            continue
        try:
            text = Path(f).read_text()
        except (OSError, UnicodeDecodeError):
            continue
        for line_no, line in enumerate(text.splitlines(), 1):
            for m in rx.finditer(line):
                found = m.group(1)
                if found == current:
                    continue
                record = f"{f}:{line_no}: {m.group(0)}"
                if allowed(record):
                    continue
                clean = label.replace("\\", "")
                tag = "[advisory/site] " if is_site else ""
                print(
                    f"validate-doc-version-alignment.sh: {tag}{f}:{line_no} names "
                    f"'{clean} {found}' but the pinned version is {current}",
                    file=sys.stderr,
                )
                print(f"    line: {line.strip()}", file=sys.stderr)
                if not is_site:
                    hard_fail = True

if hard_fail:
    print("", file=sys.stderr)
    print("Update the prose to the pinned version, or (if the mention is intentional, e.g. a", file=sys.stderr)
    print(f"transitive/historical version) add a substring to {sys.argv[1]}.", file=sys.stderr)
    sys.exit(1)

print("validate-doc-version-alignment.sh: OK (tracked dependency versions in *.md match the pinned source of truth)")
PY

#!/usr/bin/env bash
#
# Catch Atmosphere's OWN <version> in documentation drifting away from the
# current released version.
#
# The sibling gates pin THIRD-PARTY versions:
#   - validate-doc-thirdparty-versions.sh — a non-org.atmosphere dependency must
#     NOT carry an Atmosphere version (catches release-bump over-reach).
#   - validate-doc-version-alignment.sh   — Quarkus/Embabel/Koog/LangChain4j/
#     atmosphere.js prose versions must match the pinned source of truth.
#
# Neither pins Atmosphere's own release version, so an org.atmosphere
# <dependency>/<parent> example could name a stale Atmosphere version and no
# gate would fire. That is exactly how
# samples/spring-boot-rag-chat/src/main/resources/docs/atmosphere-getting-started.md
# carried <version>4.0.14</version> for dozens of releases: it lives under
# samples/, which scripts/update-doc-versions.sh never swept and the toothless
# ci.yml "doc-version-check" step (a ::warning:: that never failed and only
# scanned docs/ + module READMEs) never reported.
#
# This gate is the complement: inside a Markdown <dependency>/<parent> block
# whose <groupId> is org.atmosphere (or, for groupId-less BOM snippets, whose
# <artifactId> is atmosphere-*), every literal <version> MUST equal the current
# released version. ${...} property placeholders are ignored. The is_atmosphere
# test mirrors scripts/update-doc-versions.sh so the generator (which rewrites
# these tags at release time) and this validator (which proves they were
# rewritten) stay in lockstep.
#
# Source of truth for "the released version": cli/samples.json `.version`. The
# release workflow bumps that field and every doc <version> in the SAME commit
# via update-doc-versions.sh, so they are always consistent post-release; a
# hand-edited doc that jumps ahead of (or lags) samples.json is precisely the
# drift this gate rejects.
#
# Scope: ALL tracked *.md (git ls-files), so samples/ and any future doc path
# are covered without an allowlist of directories. Skipped as legitimate
# version history: MIGRATION.md (a migration guide whose examples name the
# versions you upgrade FROM), CHANGELOG.md, .harness/drift-log.md, and the
# site's whats-new.md. .harness/atmosphere-doc-version-allowlist.txt holds any
# other documented exception.
#
# MAIN repo findings are hard failures; the sibling atmosphere.github.io site
# (located via $ATMOSPHERE_SITE_DIR / ../atmosphere.github.io / ~/workspace/...)
# is scanned as ADVISORY only (drift-log #20: cross-repo coupling is loose and
# CI has no sibling checkout).
#
# Run from repo root. Exits 0 on success (or sibling-only advisories), 1 on any
# main-repo drift.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/atmosphere-doc-version-allowlist.txt"

python3 - "$ALLOWLIST" <<'PY'
import json
import os
import re
import subprocess
import sys
from pathlib import Path

allow = []
ap = Path(sys.argv[1])
if ap.exists():
    for line in ap.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            allow.append(line)

# ── Source of truth: the current released version. cli/samples.json is what
#    update-doc-versions.sh bumps alongside the doc <version> tags. ──
samples = Path("cli/samples.json")
if not samples.exists():
    print("validate-atmosphere-doc-version.sh: cli/samples.json not found — cannot "
          "determine the released version", file=sys.stderr)
    sys.exit(1)
released = json.loads(samples.read_text()).get("version")
if not released:
    print("validate-atmosphere-doc-version.sh: cli/samples.json has no .version",
          file=sys.stderr)
    sys.exit(1)

# A <dependency>/<parent> block (xml fenced or inline), non-greedy.
BLOCK = re.compile(r"<(dependency|parent)>(.*?)</\1>", re.DOTALL | re.IGNORECASE)
GROUP = re.compile(r"<groupId>\s*([^<]+?)\s*</groupId>", re.IGNORECASE)
ARTIFACT = re.compile(r"<artifactId>\s*([^<]+?)\s*</artifactId>", re.IGNORECASE)
# A literal release version only — never flag ${...} property placeholders.
VER = re.compile(r"<version>\s*(\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?)\s*</version>",
                 re.IGNORECASE)

# MIGRATION.md legitimately names the versions you upgrade FROM; the others are
# append-only history that quotes superseded versions.
SKIP_SUBSTR = ("MIGRATION.md", ".harness/drift-log.md", "CHANGELOG.md",
               "whats-new.md", "node_modules")


def skipped(p):
    return any(s in p.replace("\\", "/") for s in SKIP_SUBSTR)


def is_atmosphere(block):
    g = GROUP.search(block)
    if g:
        return g.group(1).strip().startswith("org.atmosphere")
    a = ARTIFACT.search(block)
    return bool(a and a.group(1).strip().startswith("atmosphere-"))


entries = [(f, False) for f in
           subprocess.check_output(["git", "ls-files", "*.md"]).decode().split()
           if not skipped(f)]

site_dir = os.environ.get("ATMOSPHERE_SITE_DIR")
if not site_dir:
    for cand in ("../atmosphere.github.io",
                 os.path.expanduser("~/workspace/atmosphere/atmosphere.github.io")):
        if Path(cand).is_dir():
            site_dir = cand
            break
if site_dir and Path(site_dir).is_dir():
    root = Path(site_dir)
    PRUNE = {"node_modules", ".git", "dist", ".astro", "build", ".output"}
    for ext in ("*.md", "*.mdx", "*.astro"):
        for p in root.rglob(ext):
            if any(part in PRUNE for part in p.parts) or skipped(str(p)):
                continue
            entries.append((str(p), True))
    print(f"validate-atmosphere-doc-version.sh: scanning sibling site at {site_dir} (advisory)",
          file=sys.stderr)


def line_of(text, idx):
    return text.count("\n", 0, idx) + 1


hard_fail = False
for f, is_site in entries:
    try:
        text = Path(f).read_text()
    except (OSError, UnicodeDecodeError):
        continue
    for m in BLOCK.finditer(text):
        block = m.group(2)
        if not is_atmosphere(block):
            continue
        for vm in VER.finditer(block):
            version = vm.group(1)
            if version == released:
                continue
            ln = line_of(text, m.start(2) + vm.start())
            record = f"{f}:{ln}: <version>{version}</version>"
            if any(tok in record for tok in allow):
                continue
            tag = "[advisory/site] " if is_site else ""
            print(f"validate-atmosphere-doc-version.sh: {tag}{f}:{ln} names Atmosphere "
                  f"<version>{version}</version> but the released version is {released}",
                  file=sys.stderr)
            if not is_site:
                hard_fail = True

if hard_fail:
    print("", file=sys.stderr)
    print(f"An org.atmosphere <version> in docs drifted from the released version "
          f"({released}).", file=sys.stderr)
    print(f"Run ./scripts/update-doc-versions.sh {released} to resync, or — if the "
          "mention is", file=sys.stderr)
    print(f"intentionally historical — add a substring to {sys.argv[1]}.", file=sys.stderr)
    sys.exit(1)

print(f"validate-atmosphere-doc-version.sh: OK (every Atmosphere <version> in *.md "
      f"matches the released version {released})")
PY

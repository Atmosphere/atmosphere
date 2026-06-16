#!/usr/bin/env bash
#
# Catch third-party Maven dependency examples in docs whose <version> has been
# stamped with an Atmosphere release version (the 4.0.x line) — the recurring
# "release bump rewrote a non-Atmosphere dependency's version" bug.
#
# A release script (or a careless find/replace) bumps every <version>4.0.x</version>
# in the docs to the new Atmosphere version, including the ones inside
# third-party <dependency> blocks (google-adk, grpc-netty-shaded,
# spring-ai-*, langchain4j-*, semantickernel-*, jackson, jakarta.inject, ...).
# A reader then copies `com.google.adk:google-adk:4.0.54`, which does not exist.
# The aaf1fbf2fb commit fixed one such instance (a Z3 binding) by hand; this
# gate catches the whole class.
#
# Rule: inside a Markdown ```xml <dependency> block, a <version> on the
# Atmosphere 4.0.x line is only legal when the <groupId> is org.atmosphere.
# Any other groupId carrying a 4.0.x version is flagged.
#
# MAIN repo findings are hard failures; the sibling atmosphere.github.io site
# (located via $ATMOSPHERE_SITE_DIR / ../atmosphere.github.io / ~/workspace/...)
# is scanned as ADVISORY (drift-log #20: cross-repo coupling is loose). Append-
# only history (drift-log.md, CHANGELOG.md, the site's whats-new.md) is skipped.
# .harness/thirdparty-version-allowlist.txt holds documented exceptions.
#
# Run from repo root. Exits 0 on success (or sibling-only advisories), 1 on any
# main-repo drift.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/thirdparty-version-allowlist.txt"

python3 - "$ALLOWLIST" <<'PY'
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

# Atmosphere's own version line, derived from pom.xml so the band tracks the
# current release decade (e.g. 4.0.55-SNAPSHOT -> match 4.0.5x). This is tight
# enough to exclude real upstream versions that merely share a 4.0 prefix
# (e.g. javax.servlet-api:4.0.1, a build plugin at 4.0.3). A third-party dep
# must never carry a version on this band.
pom = Path("pom.xml").read_text()
pm = re.search(r"atmosphere-project</artifactId>.*?<version>([^<]+)</version>", pom, re.DOTALL)
proj_ver = pm.group(1).strip() if pm else "4.0.55-SNAPSHOT"
vm = re.match(r"(\d+)\.(\d+)\.(\d+)", proj_ver)
maj, minr, patch = (vm.group(1), vm.group(2), vm.group(3)) if vm else ("4", "0", "55")
decade = patch[0]
ATMO_VER = re.compile(rf"^{maj}\.{minr}\.{decade}[0-9](-SNAPSHOT)?$")
# A <dependency>...</dependency> block (xml fenced or inline), non-greedy.
DEP = re.compile(r"<dependency>(.*?)</dependency>", re.DOTALL | re.IGNORECASE)
GROUP = re.compile(r"<groupId>\s*([^<]+?)\s*</groupId>", re.IGNORECASE)
ARTIFACT = re.compile(r"<artifactId>\s*([^<]+?)\s*</artifactId>", re.IGNORECASE)
VERSION = re.compile(r"<version>\s*([^<]+?)\s*</version>", re.IGNORECASE)

SKIP_SUBSTR = (".harness/drift-log.md", "CHANGELOG.md", "whats-new.md", "node_modules")

def skipped(p):
    return any(s in p.replace("\\", "/") for s in SKIP_SUBSTR)

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
    print(f"validate-doc-thirdparty-versions.sh: scanning sibling site at {site_dir} (advisory)",
          file=sys.stderr)

def line_of(text, idx):
    return text.count("\n", 0, idx) + 1

hard_fail = False
for f, is_site in entries:
    try:
        text = Path(f).read_text()
    except (OSError, UnicodeDecodeError):
        continue
    for m in DEP.finditer(text):
        block = m.group(1)
        gid = GROUP.search(block)
        ver = VERSION.search(block)
        if not gid or not ver:
            continue
        group = gid.group(1).strip()
        version = ver.group(1).strip()
        if group.startswith("org.atmosphere"):
            continue
        if not ATMO_VER.match(version):
            continue
        art = ARTIFACT.search(block)
        artifact = art.group(1).strip() if art else "?"
        record = f"{f}: {group}:{artifact}:{version}"
        if any(tok in record for tok in allow):
            continue
        ln = line_of(text, m.start() + ver.start(1))
        tag = "[advisory/site] " if is_site else ""
        print(f"validate-doc-thirdparty-versions.sh: {tag}{f}:{ln} "
              f"third-party dep {group}:{artifact} carries Atmosphere version "
              f"'{version}' — use the real upstream version", file=sys.stderr)
        if not is_site:
            hard_fail = True

if hard_fail:
    print("", file=sys.stderr)
    print("A non-org.atmosphere dependency example was stamped with an Atmosphere", file=sys.stderr)
    print("4.0.x version (likely a release-bump find/replace). Replace it with the", file=sys.stderr)
    print(f"real upstream version, or allowlist a true exception in {sys.argv[1]}.", file=sys.stderr)
    sys.exit(1)

print("validate-doc-thirdparty-versions.sh: OK (no third-party dependency carries an Atmosphere version)")
PY

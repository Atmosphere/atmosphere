#!/usr/bin/env bash
#
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
# Fail when the CURRENT Atmosphere or atmosphere.js version is spelled out as a
# literal in release/build tooling instead of being derived.
#
# A version typed into a script is correct for exactly one release and wrong
# from then on: release-preflight.sh took the version as a required argument
# and its usage line named a release from years ago, and
# validate-doc-thirdparty-versions.sh fell back to "4.0.55-SNAPSHOT" whenever
# it could not parse pom.xml — silently aiming its scan at a stale release
# decade. Tooling must read the version from its source of truth instead.
#
# The literals are derived at run time, never listed here:
#   - pom.xml atmosphere-project <version> (X.Y.Z-SNAPSHOT) and its release
#     X.Y.Z (qualifier stripped) — the version about to ship;
#   - cli/atmosphere VERSION= — the last released version (release-4x.yml
#     rewrites that line on every release, so it is always the previous cut);
#   - atmosphere.js/package.json "version" — the next atmosphere.js to publish.
#
# Scanned paths and the narrow allowlist live in scripts/validation-patterns.toml
# under [hardcoded_atmosphere_version]. Only files in the git index are read
# (git ls-files), so the result does not depend on scratch files in the tree.
#
# Usage: scripts/validate-no-hardcoded-atmosphere-version.sh [repo-root]
#   repo-root defaults to the enclosing git checkout (the test harness points it
#   at a throwaway repository).
# Exit: 0 clean, 1 hardcoded literal found, 2 a version source could not be read.

set -euo pipefail

ROOT="${1:-$(git rev-parse --show-toplevel)}"
cd "$ROOT"

python3 - "$ROOT" <<'PY'
import fnmatch
import json
import re
import subprocess
import sys
import tomllib
from pathlib import Path

NAME = "validate-no-hardcoded-atmosphere-version.sh"


def die(msg):
    print(f"{NAME}: {msg}", file=sys.stderr)
    sys.exit(2)


root = Path(sys.argv[1])

toml_path = root / "scripts" / "validation-patterns.toml"
try:
    with open(toml_path, "rb") as f:
        cfg = tomllib.load(f).get("hardcoded_atmosphere_version", {})
except (OSError, tomllib.TOMLDecodeError) as e:
    die(f"cannot read {toml_path}: {e}")
scan_paths = cfg.get("scan_paths", [])
allow_globs = cfg.get("allow_globs", [])
# An empty scan list would scan nothing and pass: refuse instead.
if not scan_paths:
    die("[hardcoded_atmosphere_version].scan_paths is empty or missing in "
        "scripts/validation-patterns.toml; the gate would scan nothing.")

# ── Version sources. Every one is required: a source that cannot be read means
#    the gate does not know what to look for, and guessing is how the stale
#    fallback this gate exists to prevent got in.
try:
    pom = (root / "pom.xml").read_text()
except OSError as e:
    die(f"cannot read pom.xml: {e}")
pm = re.search(r"atmosphere-project</artifactId>.*?<version>([^<]+)</version>", pom, re.DOTALL)
if not pm:
    die("cannot read the atmosphere-project <version> from pom.xml.")
project_version = pm.group(1).strip()
rm = re.match(r"(\d+\.\d+\.\d+)(?:-[A-Za-z0-9.]+)?$", project_version)
if not rm:
    die(f"pom.xml atmosphere-project version {project_version!r} is not X.Y.Z[-qualifier].")
release_version = rm.group(1)

try:
    cli = (root / "cli" / "atmosphere").read_text()
except OSError as e:
    die(f"cannot read cli/atmosphere (last released version): {e}")
cm = re.search(r'^VERSION="(\d+\.\d+\.\d+)"', cli, re.MULTILINE)
if not cm:
    die('cli/atmosphere has no VERSION="X.Y.Z" line (last released version).')
last_released = cm.group(1)

try:
    js_version = json.loads((root / "atmosphere.js" / "package.json").read_text())["version"]
except (OSError, ValueError, KeyError) as e:
    die(f"cannot read the version from atmosphere.js/package.json: {e}")
if not re.match(r"^\d+\.\d+\.\d+$", js_version):
    die(f"atmosphere.js/package.json version {js_version!r} is not X.Y.Z.")

literals = []
for v in (project_version, release_version, last_released, js_version):
    if v not in literals:
        literals.append(v)
# Not preceded by a digit or dot (so X.Y.Z never matches inside 1X.Y.Z or
# 1.X.Y.Z), not followed by a digit (so X.Y.Z never matches X.Y.Z0). A letter
# before is fine: atmosphere-X.Y.Z and js-vX.Y.Z are exactly the tag forms a
# script would hardcode. X.Y.Z also covers X.Y.Z-SNAPSHOT. (Examples stay
# symbolic: this file is in the scan, so a real current version here fails.)
patterns = [(v, re.compile(r"(?<![0-9.])" + re.escape(v) + r"(?![0-9])")) for v in literals]

files = subprocess.run(["git", "ls-files", "-z", "--", *scan_paths],
                       check=True, capture_output=True).stdout.decode().split("\0")

findings = []
for path in filter(None, files):
    if any(fnmatch.fnmatch(path, g) for g in allow_globs):
        continue
    try:
        data = (root / path).read_bytes()
    except OSError:
        continue  # deleted in the work tree but still indexed
    if b"\0" in data:
        continue  # binary
    text = data.decode("utf-8", errors="replace")
    if not any(v in text for v in literals):
        continue
    for n, line in enumerate(text.splitlines(), 1):
        for v, rx in patterns:
            if rx.search(line):
                findings.append(f"{path}:{n}: [{v}] {line.strip()[:160]}")
                break

print("literals: " + ", ".join(literals))
print("scanned:  " + ", ".join(scan_paths))
if findings:
    print(f"{len(findings)} hardcoded current Atmosphere/atmosphere.js version literal(s):")
    for f in findings:
        print("  " + f)
    print("Derive the version from its source of truth (pom.xml, cli/atmosphere, "
          "atmosphere.js/package.json) instead of typing it. A comment recording an "
          "incident can name the PR or commit instead of the version; a genuine fixture "
          "goes in [hardcoded_atmosphere_version].allow_globs with a reason.")
    sys.exit(1)
PY

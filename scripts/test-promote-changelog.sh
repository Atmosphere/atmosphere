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
# Pins the commit-subject selection in scripts/promote-changelog.py.
#
# The release workflow auto-generates CHANGELOG bullets from commit subjects
# when [Unreleased] is empty. Without this gate a `Revert "fix(x): ..."` pair
# ships a Fixed bullet for code that is not in the tree — 4.0.69 would have
# credited "run fixture Maven boots offline in CI", reverted in 3b6373a7c7.

set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import importlib.util, sys

spec = importlib.util.spec_from_file_location("pc", "scripts/promote-changelog.py")
pc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pc)

# git log is newest-first; a revert is always seen before the commit it reverts.
CASES = [
    (
        "a Revert and its target cancel out",
        ['Revert "fix(e2e): boot offline"', "fix(e2e): boot offline", "feat(a): keep"],
        ["feat(a): keep"],
    ),
    (
        "an unrelated revert does not eat other commits",
        ['Revert "fix(a): gone"', "fix(b): stays"],
        ["fix(b): stays"],
    ),
    (
        "release plumbing is not user-visible change",
        [
            "chore: prepare for next development iteration 4.0.70-SNAPSHOT",
            "chore(js): prepare next development version 5.0.45",
            "feat(a): keep",
        ],
        ["feat(a): keep"],
    ),
    (
        "an ordinary log passes through untouched",
        ["fix(a): one", "feat(b): two"],
        ["fix(a): one", "feat(b): two"],
    ),
    (
        "a reverted subject reverted twice is kept once re-landed",
        ['Revert "fix(a): x"', "fix(a): x", "fix(a): x"],
        ["fix(a): x"],
    ),
]

failed = 0
for name, log, want in CASES:
    got = pc.select_subjects(log)
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}\n        want={want}\n        got ={got}")
        failed += 1

# The generator must not fold a subject and an unseparated body into one bullet.
if "\n" in "".join(pc.select_subjects(["fix(a): one line"])):
    print("  FAIL  subjects must be single-line")
    failed += 1
else:
    print("  PASS  subjects are single-line")

if failed:
    print(f"\n{failed} promote-changelog check(s) failed", file=sys.stderr)
    sys.exit(1)
print("\nAll promote-changelog checks passed")
PY

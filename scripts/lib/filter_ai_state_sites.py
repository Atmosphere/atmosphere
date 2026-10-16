#!/usr/bin/env python3
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
"""Drop the AI-state findings that an allowlisted site explains.

Reads ``path:line:text`` findings on stdin (``source_scan.py --mode code``
output for the ``[ai_application_state]`` regex) and writes:

  * every finding no ``[[ai_application_state.allowed]]`` entry matches, as is;
  * one ``STALE-ALLOWLIST <file> <pattern>`` line per entry that matched no
    finding at all;
  * one ``SITES-MISMATCH <file> <pattern> sites=<n> found=<m>: <path:line>...``
    line per entry that matched some findings, but not the number its
    ``sites`` declares.

An allowlist entry asserts a fact ("this file reads the static API at these
sites, for this reason"). An entry that no longer matches anything asserts
nothing, and left in place it would quietly excuse the next violation that
happens to match it, so the gate fails on it until it is removed.

Entries are file + regex + site count, never file alone: a bare file exclusion
would let a new static read land in an allowlisted file unnoticed, and so would
a regex broad enough to match a second read there (the façade's
``AiEnvironment\.current\(\)``) or an alternation of several sites, which goes
stale only when the last of them is gone. ``sites`` pins how many findings the
entry explains, so a read added to or removed from an allowlisted file fails
the gate until the entry is updated. ``file`` is matched as a path suffix, so a
basename covers a file of that name in any module (the two Spring Boot starters
mirror each other), and ``sites`` then counts both.

It is a file, not a ``python3 - <<'PY'`` heredoc in a pipeline, because the
heredoc rebinds stdin and the findings would never arrive (see
filter_wiring_sites.py).
"""

import re
import sys
import tomllib


def main():
    if len(sys.argv) < 2:
        print("usage: filter_ai_state_sites.py <validation-patterns.toml>", file=sys.stderr)
        return 2

    with open(sys.argv[1], "rb") as f:
        entries = tomllib.load(f).get("ai_application_state", {}).get("allowed", [])
    if not entries:
        print("filter_ai_state_sites.py: [[ai_application_state.allowed]] parsed empty",
              file=sys.stderr)
        return 2

    compiled = []
    for entry in entries:
        try:
            if not entry.get("reason", "").strip():
                raise KeyError("reason")
            sites = entry["sites"]
            if not isinstance(sites, int) or isinstance(sites, bool) or sites < 1:
                raise ValueError("sites must be a positive integer")
            compiled.append((entry["file"], entry["pattern"], re.compile(entry["pattern"]), sites))
        except (KeyError, ValueError, re.error) as e:
            print(f"filter_ai_state_sites.py: bad allowed entry {entry!r}: {e}", file=sys.stderr)
            return 2

    matched = [[] for _ in compiled]
    for line in sys.stdin:
        path, _, rest = line.partition(":")
        line_no, _, text = rest.partition(":")
        allowed = False
        for i, (file, _pattern, rx, _sites) in enumerate(compiled):
            if (path == file or path.endswith("/" + file)) and rx.search(text):
                matched[i].append(f"{path}:{line_no}")
                allowed = True
        if not allowed:
            sys.stdout.write(line if line.endswith("\n") else line + "\n")

    for (file, pattern, _rx, sites), hits in zip(compiled, matched):
        if not hits:
            print(f"STALE-ALLOWLIST {file} {pattern}")
        elif len(hits) != sites:
            print(f"SITES-MISMATCH {file} {pattern} sites={sites} found={len(hits)}: {' '.join(hits)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

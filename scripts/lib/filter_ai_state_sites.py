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
    finding at all.

An allowlist entry asserts a fact ("this file reads the static API here, for
this reason"). An entry that no longer matches anything asserts nothing, and
left in place it would quietly excuse the next violation that happens to match
it, so the gate fails on it until it is removed.

Entries are file + regex, never file alone: a bare file exclusion would let a
new static read land in an allowlisted file unnoticed. ``file`` is matched as a
path suffix, so a basename covers a file of that name in any module (the two
Spring Boot starters mirror each other).

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
            compiled.append((entry["file"], entry["pattern"], re.compile(entry["pattern"])))
        except (KeyError, re.error) as e:
            print(f"filter_ai_state_sites.py: bad allowed entry {entry!r}: {e}", file=sys.stderr)
            return 2

    used = [False] * len(compiled)
    for line in sys.stdin:
        path, _, rest = line.partition(":")
        _line_no, _, text = rest.partition(":")
        allowed = False
        for i, (file, _pattern, rx) in enumerate(compiled):
            if (path == file or path.endswith("/" + file)) and rx.search(text):
                used[i] = True
                allowed = True
        if not allowed:
            sys.stdout.write(line if line.endswith("\n") else line + "\n")

    for (file, pattern, _rx), hit in zip(compiled, used):
        if not hit:
            print(f"STALE-ALLOWLIST {file} {pattern}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

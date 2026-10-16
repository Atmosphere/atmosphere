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

Reads ``path:line:start:end:text`` findings on stdin (``source_scan.py --mode
code --multiline --spans`` output for the ``[ai_application_state]`` regex,
``text[start:end]`` being the read itself) and writes:

  * every finding no ``[[ai_application_state.allowed]]`` entry explains, as
    ``path:line:text``;
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
the gate until the entry is updated. The scanner reports one finding per read,
not per line, and an entry explains a finding only when one of its pattern's
matches on the finding's text covers the read itself, ``text[start:end]``: the
text is the whole line, with every other read on it and any comment naming one,
so a pattern merely matching somewhere on the line would explain a
``processDefault()`` read swapped in for, or added beside, the ``current()`` read
it names. A pattern that matches the whole line (``^...$``) covers every read
on it, and a second read added there changes the line it must match. ``file``
is matched as a path suffix, so a
basename covers a file of that name in any module (the two Spring Boot starters
mirror each other), and ``sites`` then counts both.

It is a file, not a ``python3 - <<'PY'`` heredoc in a pipeline, because the
heredoc rebinds stdin and the findings would never arrive (see
filter_wiring_sites.py).
"""

import re
import sys
import tomllib


def covers(rx, text, start, end):
    """Whether a match of ``rx`` on ``text`` covers ``text[start:end]``.

    Tries the leftmost match from every start position up to ``start``, not
    only the non-overlapping ones ``finditer`` yields, so an earlier match
    ending short of the read does not hide a later one that covers it.
    """
    pos = 0
    while pos <= start:
        m = rx.search(text, pos)
        if m is None or m.start() > start:
            return False
        if m.end() >= end:
            return True
        pos = m.start() + 1
    return False


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
        line = line.rstrip("\n")
        path, line_no, start, end, text = (line.split(":", 4) + [""] * 5)[:5]
        try:
            start, end = int(start), int(end)
            if not 0 <= start <= end <= len(text):
                raise ValueError("span outside the text")
        except ValueError as e:
            # A finding without the read's span would have to be matched against
            # the whole line, which is what lets a read hide behind another.
            print(f"filter_ai_state_sites.py: finding without a read span ({e}): {line!r}",
                  file=sys.stderr)
            return 2
        allowed = False
        for i, (file, _pattern, rx, _sites) in enumerate(compiled):
            if (path == file or path.endswith("/" + file)) and covers(rx, text, start, end):
                matched[i].append(f"{path}:{line_no}")
                allowed = True
        if not allowed:
            print(f"{path}:{line_no}:{text}")

    for (file, pattern, _rx, sites), hits in zip(compiled, matched):
        if not hits:
            print(f"STALE-ALLOWLIST {file} {pattern}")
        elif len(hits) != sites:
            print(f"SITES-MISMATCH {file} {pattern} sites={sites} found={len(hits)}: {' '.join(hits)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

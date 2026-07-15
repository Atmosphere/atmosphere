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
"""Drop findings that match an allowlisted wiring site.

Reads ``path:line:text`` findings on stdin, writes the ones that survive.

Why this is a file and not a heredoc
------------------------------------
It began as ``... | python3 - "$TOML" <<'PY' ... PY`` inside the validation
script. That is silently broken: ``python3 -`` reads the *program* from stdin,
and the heredoc rebinds stdin to the program text, so the piped findings never
arrive. ``for line in sys.stdin`` saw EOF, the filter emitted nothing, and the
mock check passed unconditionally — a no-op wearing a PASS, which is the exact
failure this gate exists to catch. A real file keeps stdin free for the data.

Allowlist entries are scoped to file + regex, never file alone: a bare file
exclusion would let a genuine stub land in an allowlisted file unnoticed. A hit
in AiConfig.java that does not match its `mode=fake` wiring pattern still fails.
"""

import re
import sys
import tomllib


def main():
    if len(sys.argv) < 2:
        print("usage: filter_wiring_sites.py <validation-patterns.toml>",
              file=sys.stderr)
        return 2

    with open(sys.argv[1], "rb") as f:
        sites = tomllib.load(f).get("mock_exclusions", {}).get("wiring_sites", [])

    compiled = []
    for s in sites:
        try:
            compiled.append((s["file"], re.compile(s["pattern"])))
        except (KeyError, re.error) as e:
            print(f"filter_wiring_sites.py: bad wiring_sites entry {s!r}: {e}",
                  file=sys.stderr)
            return 2

    for line in sys.stdin:
        path, _, rest = line.partition(":")
        if any(path.endswith("/" + f) or path == f
               for f, rx in compiled
               if rx.search(rest)):
            continue
        sys.stdout.write(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())

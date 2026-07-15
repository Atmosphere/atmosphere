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
"""Comment-aware source scanner for the architectural validation gate.

Why this exists
---------------
The gate used to grep raw source text, so it could not tell code from prose.
Two false-positive classes followed, and both shipped:

  * Javadoc usage examples counted as code violations. wasync's API docs show
    callers how to use the library --- ``* socket.on(Event.MESSAGE, m ->
    System.out.println(m))`` --- and the gate reported five "System.out in
    production code" hits plus one ``printStackTrace()`` hit against
    documentation that contains no executable statement at all.

  * Enum constants counted as deferral markers. AgentScope's ``SubTaskState``
    has a constant literally named ``TODO``; ``case TODO -> PlanStatus.PENDING``
    is a switch arm, not unfinished work. Eight of eleven reported TODO markers
    were this.

A gate that cries wolf over its own documentation teaches people to skim past
it, which is worse than having no gate. So each check must ask the question it
actually means:

  * "does the *code* call System.out?"      -> ``--mode code``
  * "does a *comment* carry a TODO marker?" -> ``--mode comment``

Model
-----
Every character is classified as code or comment, and each line is projected
onto both. String, char, and text-block *contents* count as code --- a literal
``"fake"`` is a real token the mock check must still see, so it is allowlisted
in the open rather than hidden by the scanner.

Output is ``path:line:text`` (ripgrep-compatible), where ``text`` is the
original untouched line so humans read what they wrote, not the projection.

Usage:
    source_scan.py --mode code    --regex 'System\\.out' [--ignore-case]
                   [--strip-javadoc-tags] [--exclude GLOB] FILE_OR_DIR...
"""

import argparse
import fnmatch
import pathlib
import re
import sys

SUFFIXES = (".java", ".kt")

# Javadoc inline tags name code, they do not narrate it. `{@code TODO}` in
# PlanStatus documents an enum constant round-trip; treating it as a deferral
# marker is the same category error as flagging `case TODO ->`. Stripped only
# when a check asks for it (--strip-javadoc-tags), never silently.
INLINE_TAG = re.compile(r"\{@(?:code|link|linkplain|literal|value)\b[^{}]*\}")


def project(text):
    """Split source into per-line (code, comment) projections.

    Returns a list of ``(line_no, original, code, comment)`` tuples. A single
    forward pass tracks lexer state so that comment markers inside strings
    (and quotes inside comments) are read the way javac reads them.
    """
    lines = text.split("\n")
    code = [[] for _ in lines]
    comment = [[] for _ in lines]

    CODE, LINE_C, BLOCK_C, STR, CHAR, TEXT = range(6)
    state = CODE
    row = 0
    i = 0
    n = len(text)

    while i < n:
        c = text[i]
        if c == "\n":
            row += 1
            # A line comment dies at the newline; every other state survives it.
            if state == LINE_C:
                state = CODE
            i += 1
            continue

        nxt = text[i + 1] if i + 1 < n else ""

        if state == CODE:
            if c == "/" and nxt == "/":
                state = LINE_C
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = BLOCK_C
                comment[row].append("  ")
                i += 2
                continue
            if text.startswith('"""', i):
                state = TEXT
                code[row].append('"""')
                i += 3
                continue
            if c == '"':
                state = STR
            elif c == "'":
                state = CHAR
            code[row].append(c)
            i += 1
            continue

        if state == STR:
            code[row].append(c)
            if c == "\\":
                if nxt and nxt != "\n":
                    code[row].append(nxt)
                    i += 2
                    continue
            elif c == '"':
                state = CODE
            i += 1
            continue

        if state == CHAR:
            code[row].append(c)
            if c == "\\":
                if nxt and nxt != "\n":
                    code[row].append(nxt)
                    i += 2
                    continue
            elif c == "'":
                state = CODE
            i += 1
            continue

        if state == TEXT:
            if text.startswith('"""', i):
                state = CODE
                code[row].append('"""')
                i += 3
                continue
            code[row].append(c)
            i += 1
            continue

        if state == LINE_C:
            comment[row].append(c)
            i += 1
            continue

        if state == BLOCK_C:
            if c == "*" and nxt == "/":
                state = CODE
                comment[row].append("  ")
                i += 2
                continue
            comment[row].append(c)
            i += 1
            continue

    return [
        (idx + 1, lines[idx], "".join(code[idx]), "".join(comment[idx]))
        for idx in range(len(lines))
    ]


def iter_files(roots, excludes):
    seen = set()
    for root in roots:
        p = pathlib.Path(root)
        if not p.exists():
            continue
        candidates = [p] if p.is_file() else sorted(p.rglob("*"))
        for f in candidates:
            if f.suffix not in SUFFIXES or not f.is_file():
                continue
            s = str(f)
            if any(fnmatch.fnmatch(s, g) for g in excludes):
                continue
            if s not in seen:
                seen.add(s)
                yield f


# Fixtures pinned from the real false positives this scanner was written to
# kill, plus the real violations it must keep catching. The validation gate runs
# this before trusting the instrument: a scanner that silently stopped stripping
# comments would turn every check green, which is the most dangerous way for a
# gate to fail.
SELF_TEST = [
    # (name, source, mode, regex, strip_tags, expected_matching_line_numbers)
    (
        "javadoc example is not code",
        '/**\n * socket.on(Event.MESSAGE, m -> System.out.println(m))\n */\nvoid f() {}\n',
        "code", r"System\.out", False, [],
    ),
    (
        "real call is code",
        'void f() {\n    System.out.println("x");\n}\n',
        "code", r"System\.out", False, [2],
    ),
    (
        "trailing comment does not hide the call",
        'void f() {\n    System.out.println("x"); // debug\n}\n',
        "code", r"System\.out", False, [2],
    ),
    (
        "enum constant named TODO is not a marker",
        "switch (s) {\n    case TODO -> PlanStatus.PENDING;\n}\n",
        "comment", r"\bTODO\b", True, [],
    ),
    (
        "{@code TODO} documents a constant, not a deferral",
        "/**\n * ({@code TODO, IN_PROGRESS}) round-trips to native.\n */\n",
        "comment", r"\bTODO\b", True, [],
    ),
    (
        "real TODO marker in a line comment is caught",
        "// TODO: wire the token check\nint x = 1;\n",
        "comment", r"\bTODO\b", True, [1],
    ),
    (
        "real TODO marker in javadoc prose is caught",
        "/**\n * TODO: parameterize the provider count.\n */\n",
        "comment", r"\bTODO\b", True, [2],
    ),
    (
        "comment marker inside a string literal is code, not a comment",
        'String s = "// TODO not a real marker";\n',
        "comment", r"\bTODO\b", True, [],
    ),
    (
        "string literal stays visible to code-mode checks",
        'boolean b = "fake".equalsIgnoreCase(mode);\n',
        "code", r"\bfake\b", False, [1],
    ),
    (
        "block comment spanning lines is not code",
        'void f() {\n/* System.out.println("x");\n   more */\n}\n',
        "code", r"System\.out", False, [],
    ),
    (
        "escaped quote does not leak string state into code",
        'String s = "he said \\"hi\\"";\nSystem.out.println(s);\n',
        "code", r"System\.out", False, [2],
    ),
    (
        "text block content is not parsed as comment",
        'String s = """\n    // TODO inside a text block\n    """;\n',
        "comment", r"\bTODO\b", True, [],
    ),
]


def self_test():
    failures = []
    for name, src, mode, regex, strip, expected in SELF_TEST:
        rx = re.compile(regex)
        got = []
        for line_no, _original, code, comment in project(src):
            hay = code if mode == "code" else comment
            if not hay.strip():
                continue
            if strip:
                hay = INLINE_TAG.sub(" ", hay)
            if rx.search(hay):
                got.append(line_no)
        if got != expected:
            failures.append(f"  {name}: expected lines {expected}, got {got}")

    if failures:
        print("source_scan.py SELF-TEST FAILED:", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
        return 1
    print(f"source_scan.py self-test: {len(SELF_TEST)} cases passed")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=("code", "comment"), required=True)
    ap.add_argument("--regex", required=True)
    ap.add_argument("--ignore-case", action="store_true")
    ap.add_argument("--strip-javadoc-tags", action="store_true")
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("roots", nargs="+")
    args = ap.parse_args()

    flags = re.IGNORECASE if args.ignore_case else 0
    try:
        rx = re.compile(args.regex, flags)
    except re.error as e:
        print(f"source_scan.py: bad --regex: {e}", file=sys.stderr)
        return 2

    hits = 0
    for f in iter_files(args.roots, args.exclude):
        try:
            text = f.read_text(errors="ignore")
        except OSError as e:
            print(f"source_scan.py: cannot read {f}: {e}", file=sys.stderr)
            return 2
        for line_no, original, code, comment in project(text):
            hay = code if args.mode == "code" else comment
            if not hay.strip():
                continue
            if args.strip_javadoc_tags:
                hay = INLINE_TAG.sub(" ", hay)
            if rx.search(hay):
                print(f"{f}:{line_no}:{original.strip()}")
                hits += 1

    # 0 = found (grep convention), 1 = clean. Callers branch on output, but the
    # exit code keeps the script usable under `if ...; then` without inversion.
    return 0 if hits else 1


if __name__ == "__main__":
    sys.exit(main())

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
                   [--strip-javadoc-tags] [--multiline [--spans]] [--exclude GLOB] FILE_OR_DIR...

``--multiline`` matches the regex against a file's whole projection rather than
one line at a time, so a call split across lines (``AiEnvironment`` on one line,
``.current()`` on the next) is still found. Each match is reported once, at the
line it starts on, so a line holding two matches is reported twice; when a match
spans lines, ``text`` is those original lines joined by a space.

``--spans`` (with ``--multiline``) prints ``path:line:start:end:text`` instead,
``text[start:end]`` being the stretch of ``text`` the match itself covers, so a
caller can tell one read on a line from another, and a read from a comment
naming it (see ``multiline_spans``).
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
    return [(line_no, original, code, comment)
            for line_no, original, code, _code_cols, comment, _comment_cols in project_columns(text)]


def project_columns(text):
    """``project`` that also says where each projected character came from.

    Returns ``(line_no, original, code, code_cols, comment, comment_cols)``
    tuples: ``code_cols[k]`` is the column in ``original`` of ``code[k]`` (and
    likewise for the comment), so a match found in a projection can be located
    in the line a human reads.
    """
    lines = text.split("\n")
    code = [[] for _ in lines]
    comment = [[] for _ in lines]
    code_cols = [[] for _ in lines]
    comment_cols = [[] for _ in lines]
    line_start = 0

    def put(buf, cols, at_row, chars, col):
        buf[at_row].append(chars)
        cols[at_row].extend(range(col, col + len(chars)))

    CODE, LINE_C, BLOCK_C, STR, CHAR, TEXT = range(6)
    state = CODE
    row = 0
    i = 0
    n = len(text)

    while i < n:
        c = text[i]
        if c == "\n":
            row += 1
            line_start = i + 1
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
                put(comment, comment_cols, row, "  ", i - line_start)
                i += 2
                continue
            if text.startswith('"""', i):
                state = TEXT
                put(code, code_cols, row, '"""', i - line_start)
                i += 3
                continue
            if c == '"':
                state = STR
            elif c == "'":
                state = CHAR
            put(code, code_cols, row, c, i - line_start)
            i += 1
            continue

        if state == STR:
            put(code, code_cols, row, c, i - line_start)
            if c == "\\":
                if nxt and nxt != "\n":
                    put(code, code_cols, row, nxt, i + 1 - line_start)
                    i += 2
                    continue
            elif c == '"':
                state = CODE
            i += 1
            continue

        if state == CHAR:
            put(code, code_cols, row, c, i - line_start)
            if c == "\\":
                if nxt and nxt != "\n":
                    put(code, code_cols, row, nxt, i + 1 - line_start)
                    i += 2
                    continue
            elif c == "'":
                state = CODE
            i += 1
            continue

        if state == TEXT:
            if text.startswith('"""', i):
                state = CODE
                put(code, code_cols, row, '"""', i - line_start)
                i += 3
                continue
            put(code, code_cols, row, c, i - line_start)
            i += 1
            continue

        if state == LINE_C:
            put(comment, comment_cols, row, c, i - line_start)
            i += 1
            continue

        if state == BLOCK_C:
            if c == "*" and nxt == "/":
                state = CODE
                put(comment, comment_cols, row, "  ", i - line_start)
                i += 2
                continue
            put(comment, comment_cols, row, c, i - line_start)
            i += 1
            continue

    return [
        (idx + 1, lines[idx], "".join(code[idx]), code_cols[idx],
         "".join(comment[idx]), comment_cols[idx])
        for idx in range(len(lines))
    ]


def multiline_hits(rx, rows, mode, strip):
    """Match ``rx`` across the projected lines of one file.

    Returns ``(first_line_no, text)`` per match, in source order, ``text`` being
    the original lines the match spans, stripped and joined by a space. Two
    matches on one line are two hits: a caller counting reads (the AI-state
    allowlist's ``sites``) must see a second read added to a line that already
    holds one, which one hit per line would hide. Lines are
    projected exactly as the per-line scan projects them, then joined by newlines,
    so ``\\s`` in the regex spans a line break and a comment between two tokens
    is already gone.
    """
    hays = []
    for _line_no, _original, code, comment in rows:
        hay = code if mode == "code" else comment
        if strip:
            hay = INLINE_TAG.sub(" ", hay)
        hays.append(hay)
    matches, _locate = _matches(rx, hays)
    return [(rows[first][0], _joined(rows, first, last)) for _m, first, last in matches]


def multiline_spans(rx, rows, mode):
    """``multiline_hits`` that also says where in ``text`` the match lies.

    ``rows`` comes from ``project_columns``. Returns ``(first_line_no, text,
    start, end)`` per match: ``text[start:end]`` is the stretch of the original
    lines the match covers, from its first projected character to its last, so
    a comment inside a call split across lines lies inside it, and the same
    text elsewhere on the line (a second read, a comment naming the call) lies
    outside it. A caller deciding which allowlist entry explains a read must
    look there, not at the whole line: the line also carries every other read
    on it. Javadoc inline tags are never stripped here, since replacing one
    would move every column after it.
    """
    hays = [row[2] if mode == "code" else row[4] for row in rows]
    cols = [row[3] if mode == "code" else row[5] for row in rows]
    matches, locate = _matches(rx, hays)
    hits = []
    for m, first, last in matches:
        text = _joined(rows, first, last)
        base, offset = {}, 0
        for r in range(first, last + 1):
            base[r] = offset
            offset += len(rows[r][1].strip()) + 1

        def located(r, k):
            """Offset in ``text`` of projected character ``k`` of row ``r``."""
            original = rows[r][1]
            lead = len(original) - len(original.lstrip())
            return min(max(base[r] + cols[r][k] - lead, base[r]), base[r] + len(original.strip()))

        # The separators joining the projections are no source character; a
        # match made of nothing else names no stretch of the line, so it is
        # given the whole of it and only an entry matching all of it covers it.
        chars = [(r, k) for r, k in (locate(pos) for pos in range(m.start(), m.end()))
                 if k < len(hays[r])]
        if chars:
            start = located(*chars[0])
            end = located(*chars[-1]) + 1
        else:
            start, end = 0, len(text)
        hits.append((rows[first][0], text, start, end))
    return hits


def _joined(rows, first, last):
    return " ".join(rows[i][1].strip() for i in range(first, last + 1))


def _matches(rx, hays):
    """Match ``rx`` over the projections joined by newlines.

    Returns ``(matches, locate)``: ``matches`` holds ``(match, first_row,
    last_row)`` in source order, and ``locate(pos)`` gives the ``(row, index)``
    of a position in the joined text, ``index == len(hays[row])`` being the
    newline after that row.
    """
    starts, offset = [], 0
    for hay in hays:
        starts.append(offset)
        offset += len(hay) + 1

    def row_of(pos):
        lo, hi = 0, len(starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if starts[mid] <= pos:
                lo = mid
            else:
                hi = mid - 1
        return lo

    def locate(pos):
        r = row_of(pos)
        return r, pos - starts[r]

    matches = []
    for m in rx.finditer("\n".join(hays)):
        first = row_of(m.start())
        last = row_of(max(m.start(), m.end() - 1))
        matches.append((m, first, last))
    return matches, locate


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

# --multiline fixtures: (name, source, regex, expected_start_line_numbers)
MULTILINE_SELF_TEST = [
    (
        "a call split across lines is one hit, at its first line",
        "Object o = AiEnvironment\n        .current()\n        .settings();\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [1],
    ),
    (
        "a comment between the tokens does not hide the call",
        "Object o = AiEnvironment // the application's\n        .current();\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [1],
    ),
    (
        "a call named across a javadoc is not code",
        "/**\n * AiEnvironment\n * .current()\n */\nvoid f() {}\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [],
    ),
    (
        "single-line hits keep their own line numbers",
        "int a;\nObject o = AiEnvironment.current();\nObject p = AiEnvironment.current();\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [2, 3],
    ),
    (
        "two reads on one line are two hits",
        "int a;\nObject o = AiEnvironment.current() != null ? AiEnvironment.current() : null;\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [2, 2],
    ),
]


# --multiline --spans fixtures: (name, source, regex, expected (line, text[start:end]) per hit)
SPAN_SELF_TEST = [
    (
        "a read's span is the read, not the line naming another in a comment",
        "    Object o = AiEnvironment.processDefault().settings(); // was AiEnvironment.current()\n",
        r"\bAiEnvironment\s*\.\s*(?:current|processDefault)\s*\(", [(1, "AiEnvironment.processDefault(")],
    ),
    (
        "two reads on one line have two spans",
        "\tvar a = AiEnvironment.current() != null ? null : AiEnvironment.processDefault();\n",
        r"\bAiEnvironment\s*\.\s*(?:current|processDefault)\s*\(",
        [(1, "AiEnvironment.current("), (1, "AiEnvironment.processDefault(")],
    ),
    (
        "a split read spans its lines, the comment between its tokens included",
        "int a;\n    Object o = AiEnvironment // the application's\n            .current();\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [(2, "AiEnvironment // the application's .current(")],
    ),
    (
        "a block comment before the read does not shift its span",
        "    /* AiEnvironment.current() */ Object o = AiEnvironment.current();\n",
        r"\bAiEnvironment\s*\.\s*current\s*\(", [(1, "AiEnvironment.current(")],
    ),
    (
        "a string before the read does not shift its span",
        '    String s = "a\\"b/* x */"; Object o = AiEnvironment.current();\n',
        r"\bAiEnvironment\s*\.\s*current\s*\(", [(1, "AiEnvironment.current(")],
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

    for name, src, regex, expected in MULTILINE_SELF_TEST:
        got = [line_no for line_no, _text in multiline_hits(re.compile(regex), project(src), "code", False)]
        if got != expected:
            failures.append(f"  {name} (--multiline): expected lines {expected}, got {got}")

    for name, src, regex, expected in SPAN_SELF_TEST:
        got = [(line_no, text[start:end])
               for line_no, text, start, end in multiline_spans(re.compile(regex), project_columns(src), "code")]
        if got != expected:
            failures.append(f"  {name} (--multiline --spans): expected {expected}, got {got}")

    if failures:
        print("source_scan.py SELF-TEST FAILED:", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
        return 1
    total = len(SELF_TEST) + len(MULTILINE_SELF_TEST) + len(SPAN_SELF_TEST)
    print(f"source_scan.py self-test: {total} cases passed")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=("code", "comment"), required=True)
    ap.add_argument("--regex", required=True)
    ap.add_argument("--ignore-case", action="store_true")
    ap.add_argument("--strip-javadoc-tags", action="store_true")
    ap.add_argument("--multiline", action="store_true")
    ap.add_argument("--spans", action="store_true")
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("roots", nargs="+")
    args = ap.parse_args()

    if args.spans and (not args.multiline or args.strip_javadoc_tags):
        print("source_scan.py: --spans needs --multiline and no --strip-javadoc-tags", file=sys.stderr)
        return 2

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
        if args.spans:
            for line_no, joined, start, end in multiline_spans(rx, project_columns(text), args.mode):
                print(f"{f}:{line_no}:{start}:{end}:{joined}")
                hits += 1
            continue
        if args.multiline:
            for line_no, joined in multiline_hits(rx, project(text), args.mode, args.strip_javadoc_tags):
                print(f"{f}:{line_no}:{joined}")
                hits += 1
            continue
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

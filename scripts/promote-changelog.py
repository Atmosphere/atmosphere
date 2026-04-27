#!/usr/bin/env python3
# ──────────────────────────────────────────────────────────────
# promote-changelog.py — Promote [Unreleased] to a versioned heading
#
# Usage: scripts/promote-changelog.py <release-version> [<from-ref>]
#
# Behavior:
# - If [Unreleased] has hand-curated content, transcribes it under a new
#   "## [VERSION] - YYYY-MM-DD" heading and leaves [Unreleased] empty.
# - If [Unreleased] is empty, generates Added/Fixed/Changed bullets from
#   conventional commits between <from-ref> (or the previous atmosphere-*
#   tag) and HEAD. Subjects are transcribed verbatim — no inference, to
#   match the project's no-hallucination rule.
# - Idempotent: if a "## [VERSION]" heading already exists, exits 0.
#
# Designed to run from the release workflow before "Commit release version";
# CHANGELOG.md is staged into the same release commit.
# ──────────────────────────────────────────────────────────────
from __future__ import annotations

import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

CHANGELOG = Path("CHANGELOG.md")

CONVENTIONAL = re.compile(
    r"^(feat|fix|refactor|perf|chore|docs|ci|test|build|style)"
    r"(\([^)]+\))?: (.+)$"
)
ADDED_KINDS = {"feat"}
FIXED_KINDS = {"fix"}
CHANGED_KINDS = {"refactor", "perf", "chore", "docs", "ci", "test", "build", "style"}


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def previous_tag() -> str:
    try:
        return run("git", "describe", "--tags", "--abbrev=0", "--match=atmosphere-*")
    except subprocess.CalledProcessError:
        return run("git", "rev-list", "--max-parents=0", "HEAD").splitlines()[0]


def generate_from_commits(from_ref: str) -> str:
    print(f"[promote-changelog] generating from {from_ref}..HEAD", file=sys.stderr)
    log = subprocess.check_output(
        ["git", "log", "--no-merges", f"{from_ref}..HEAD", "--format=%s"],
        text=True,
    ).splitlines()

    feats: list[str] = []
    fixes: list[str] = []
    changes: list[str] = []
    for subject in log:
        m = CONVENTIONAL.match(subject)
        if not m:
            continue
        kind, _scope, desc = m.groups()
        bullet = f"- {desc}"
        if kind in ADDED_KINDS:
            feats.append(bullet)
        elif kind in FIXED_KINDS:
            fixes.append(bullet)
        elif kind in CHANGED_KINDS:
            changes.append(bullet)

    sections: list[str] = []
    for heading, bullets in (("Added", feats), ("Fixed", fixes), ("Changed", changes)):
        if bullets:
            sections.append(f"### {heading}\n\n" + "\n".join(bullets))

    if not sections:
        return "_No notable changes._"
    return "\n\n".join(sections)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: promote-changelog.py <release-version> [<from-ref>]", file=sys.stderr)
        return 2

    version = sys.argv[1]
    from_ref = sys.argv[2] if len(sys.argv) > 2 else None
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    if not CHANGELOG.is_file():
        print(f"::error::{CHANGELOG} not found", file=sys.stderr)
        return 1

    text = CHANGELOG.read_text()

    if re.search(rf"^## \[{re.escape(version)}\]", text, re.MULTILINE):
        print(f"[promote-changelog] {CHANGELOG} already has [{version}] — nothing to do")
        return 0

    unreleased = re.search(r"^## \[Unreleased\][ \t]*\n", text, re.MULTILINE)
    if not unreleased:
        print(f"::error::no '## [Unreleased]' heading in {CHANGELOG}", file=sys.stderr)
        return 1

    after = text[unreleased.end():]
    next_heading = re.search(r"^## \[", after, re.MULTILINE)
    body = after[: next_heading.start()] if next_heading else after
    tail = after[next_heading.start():] if next_heading else ""

    if body.strip():
        section_body = body.strip("\n")
        print(f"[promote-changelog] promoting curated [Unreleased] body to [{version}]")
    else:
        section_body = generate_from_commits(from_ref or previous_tag())

    head = text[: unreleased.end()]
    new_text = (
        f"{head}\n"
        f"## [{version}] - {today}\n\n"
        f"{section_body}\n\n"
        f"{tail}"
    )

    CHANGELOG.write_text(new_text)
    print(f"[promote-changelog] {CHANGELOG} promoted: [Unreleased] → [{version}] - {today}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

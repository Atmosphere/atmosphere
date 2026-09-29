---
name: obsidian-writer
description: Write well-formatted notes to the atmosphere-vault Obsidian knowledge base.
  Use this skill whenever creating or updating an ADR, runbook, plan, API doc, guide,
  session output, or any structured document that should land in the vault — even when
  the user doesn't say "Obsidian" explicitly. Delegates to obsidian:obsidian-cli to
  write to the live vault and applies Atmosphere frontmatter and formatting standards.
user-invocable: true
metadata:
  version: "1.1.0"
  domain: documentation
  triggers: obsidian, vault, note, document, adr, runbook, plan, api-doc, guide, knowledge-base, architecture decision, session output
  role: specialist
  scope: implementation
  output-format: document
  related-skills: obsidian-vault-setup
---

# Obsidian Writer

## Role

Knowledge-base writer for the shared atmosphere-vault Obsidian vault. Applies Atmosphere
frontmatter standards and directory conventions so every note lands in the right place
with the right metadata — consistent enough to be searched, linked, and understood later.

## When to Use

Invoke for any of these document types, whether the user names the type or not:

- **ADR** — Architecture Decision Record (`Architecture/ADRs/`)
- **Runbook** — Operational procedure or on-call guide (`Development/Runbooks/`)
- **Plan** — Feature or project planning document (`Claude Plans/`)
- **API doc** — Endpoint reference, SDK documentation (`APIs/`)
- **Guide / how-to** — Developer setup, walkthrough, tutorial (`Development/`)
- **Session output** — Claude Code session artifact (`Claude Outputs/`)
- **Methodology** — Process, workflow, team practice (`Methodology/`)

## Workflow

### Step 1 — Identify doc type and target directory

Use the quick-reference table below. When ambiguous, ask the user which type fits best.

### Step 2 — Sync, then search for existing notes

Pull the remote first so the search sees every note that has already been pushed, then
check whether a relevant note already exists to avoid duplicates:

```
.claude/skills/obsidian-writer/vault-sync.sh pull
obsidian search query="<keywords from the topic>" limit=5
```

If `pull` exits non-zero, stop and report what it printed instead of reading or writing the
vault. Either a conflict it could not auto-resolve moved the unpushed local commits to a
`vault-sync/backup-<ts>` branch (main now matches origin, so those notes stay missing until
that branch is merged by hand), or the rebase did not run and nothing was changed (retry).

If a match is found, read it first (`obsidian read file="<name>"`) and decide whether
to update the existing note or create a new one.

### Step 3 — Compose content

Read `references/vault-structure.md` for the complete frontmatter field table.

Start every note with YAML frontmatter, then a level-1 heading matching the filename:

```markdown
---
date: YYYY-MM-DD
tags: [atmosphere, <type-tag>]
status: <value>        # only for ADR and Plan
service: <name>        # only for Runbook and API doc
severity: <P0–P3>      # only for Runbook
---

# Note Title
```

Use `[[wikilinks]]` to link related vault notes — never absolute file paths.

### Step 4 — Write to the live vault

Obsidian must be running and the atmosphere-vault must be the focused vault.

```
# Create a new note
obsidian create name="<Note Title>" path="<Directory/Filename.md>"

# Append a section to an existing note
obsidian append file="<Note Title>" content="## New Section\n<content>"
```

### Step 5 — Verify and link

After writing, read the note back to confirm content landed correctly:

```
obsidian read file="<Note Title>"
```

Then update any related notes with a `[[wikilink]]` to the new document.

### Step 6 — Publish

Commit and push only the notes you wrote or edited, through the sync script (paths are
relative to the vault — never the whole tree):

```
.claude/skills/obsidian-writer/vault-sync.sh push -m "docs(adr): <what>" \
  "Architecture/ADRs/ADR-0042 Adopt Virtual Threads.md"
```

Exit 0 means published: after the sync and push, origin/main holds each of your notes
exactly as committed. Your notes are the files under the paths that the push's commit
changed, and those the vault holds differently from the origin/main it forked from (an
earlier unpushed commit, such as the timer's, changed them). Every other file under the
paths (a directory's other notes, a note the timer already pushed) was already on
origin/main as the vault holds it, and must still be there or have changed since only by
edits made on top of that version. The output lists those under "already on origin/main …
then changed there by later edits" without failing the push: someone edited or deleted the
note after it was published, so re-read it before you report it. Any other exit means not
published, and the output names each file origin/main does not hold as committed, and why.
Most often someone edited the same note at the same time and the remote's side won or was
merged in, whether over your push or over a timer push that got there first; the vault then
holds origin's version, so re-read those notes and redo your change if it is gone. A backup
branch, a rebase that did not run and a push still rejected are named as such. Never report
a note as published on a non-zero exit.

The vault's history uses `docs(adr)`, `docs(plan)` and `docs(claude-output)` prefixes for
these commits; the timers' commits are `vault: auto-save`.

## Quick Reference

| Doc Type | Target Directory | Template | Required Tags |
|----------|-----------------|----------|---------------|
| ADR | `Architecture/ADRs/` | `Templates/ADR.md` | `atmosphere, adr` |
| Runbook | `Development/Runbooks/` | `Templates/Runbook.md` | `atmosphere, runbook, sre` |
| Plan | `Claude Plans/` | `Templates/Plan.md` | `atmosphere, plan` |
| API doc | `APIs/` | — | `atmosphere, api` |
| Session output | `Claude Outputs/` | — | `atmosphere, claude-output` |
| Guide / how-to | `Development/` | — | `atmosphere, guide, <domain>` |
| Methodology | `Methodology/` | — | `atmosphere, methodology` |

## Frontmatter Standards

**ADR** — required: `date`, `status`, `tags: [atmosphere, adr]`
- Status values: `proposed` → `accepted` → `deprecated` / `superseded`
- Filename convention: `ADR-NNNN Short Title.md` (zero-padded, four digits)

**Runbook** — required: `date`, `severity`, `service`, `tags: [atmosphere, runbook, sre]`
- Severity values: `P0` (critical), `P1` (high), `P2` (medium), `P3` (low)

**Plan** — required: `date`, `status`, `tags: [atmosphere, plan]`
- Status values: `draft`, `active`, `completed`

**API doc** — required: `date`, `service`, `tags: [atmosphere, api]`

**Session output** — required: `date`, `tags: [atmosphere, claude-output]`

**Guide / how-to** — required: `date`, `tags: [atmosphere, guide, <domain>]`
- Replace `<domain>` with the relevant area (e.g., `runtime`, `spring`, `quarkus`)

## Key obsidian-cli Patterns

```
# Search before creating to avoid duplicates
obsidian search query="WebSocket backpressure" limit=5

# Read an existing note before editing
obsidian read file="ADR-0042 Adopt Virtual Threads"

# Create a new note (Obsidian must be open and vault focused)
obsidian create name="ADR-0042 Adopt Virtual Threads" \
  path="Architecture/ADRs/ADR-0042 Adopt Virtual Threads.md"

# Append a section to an existing note
obsidian append file="ADR-0042 Adopt Virtual Threads" \
  content="## Update 2026-03-14\nApproved in team review."
```

## Constraints

- Obsidian must be running and the atmosphere-vault must be the active vault before using
  `obsidian create` or `obsidian append` — the CLI communicates with the open app.
- The `obsidian` command MUST be the first-party app CLI
  (`/Applications/Obsidian.app/Contents/MacOS/obsidian`). It needs **no API key**.
  If `obsidian` errors with "An API key must be provided via OBSIDIAN_API_KEY", a stray
  global npm package (`obsidian-cli`, the unrelated ObsidianQA tool) is shadowing it on
  PATH — fix with `npm uninstall -g obsidian-cli`, do NOT fall back to `claude_docs/`.
  Verify resolution with `command -v obsidian`.
- Always search first to avoid duplicate notes on the same topic.
- Always use `[[wikilinks]]` for internal vault references, not relative or absolute
  paths, and link by bare basename (`[[ADR-0042 Adopt Virtual Threads]]`): a basename link
  survives the note moving into a subfolder, a path link breaks on the first move.
- Never put `#` in the filename of a note you intend to wikilink. Obsidian splits
  `[[Note#Heading]]` at the first `#`, so `[[Risk #5 gate]]` resolves to a note called "Risk".
- Frontmatter `date` must be ISO 8601 format (`YYYY-MM-DD`).
- ADR filenames carry a zero-padded four-digit number (`ADR-0001`, `ADR-0002`, …) so they
  sort chronologically. Check the highest existing number first (`ls Architecture/ADRs/`)
  and take the next one.
- **`claude_docs/` is not a general-purpose route.** It is a gitignored symlink to the
  vault's `Claude Outputs/`, so a note written through it always lands there, must carry
  its own frontmatter (`date`, `tags: [atmosphere, claude-output]`) — nothing downstream
  adds it — and is the wrong place for an ADR, runbook or plan. Create those with
  `obsidian create` into their own folder.
- **The vault's remote wins — sync through `vault-sync.sh`, never by hand.** Run
  `.claude/skills/obsidian-writer/vault-sync.sh pull` before reading or writing the vault,
  and publish with `vault-sync.sh push -m "docs(<kind>): <what>" <path>…` (paths relative to
  the vault, never the whole tree). It refuses to touch a vault that is mid-merge, mid-rebase
  or off `main`; otherwise it fetches, stashes local uncommitted work, rebases onto
  `origin/main` with the remote side winning every conflicting hunk, re-applies the stash (a
  stash that does not re-apply stays in `git stash list`), and retries a rejected push against
  the new tip. A conflict the rebase cannot auto-resolve moves local main to a
  `vault-sync/backup-<ts>` branch and resets it to origin; a rebase that fails without a
  conflict changes nothing. Either way `pull` and `push` exit non-zero: stop and report.
  Never `git merge` origin into the vault or resolve a conflict in favour of local.
- **Commit explicitly** after writing — `vault-sync.sh push` does it. obsidian-git
  auto-commits every 10 minutes under a generic `vault: auto-save` message; an explicit
  commit is attributable and revertible.
- See `references/vault-structure.md` for the full directory map and field reference.

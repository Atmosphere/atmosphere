---
name: bilan
description: Measure whether this session actually finished — the 0-10 completion number, computed from git, the carnet ledger, LIMITATION markers and CI instead of narrated. Use before reporting a completion number, when asked "where are we", at the end of a piece of work, and to find what a session that died left behind.
argument-hint: "[sweep] [scope] [--cheap] [--json]"
user-invocable: true
---

# Bilan

**The completion number is this script's number, never yours.**

It used to be narrated: it came from the session's account of itself, so a session that
*believed* it was done reported 8 or 10 while it still held open carnet issues, had commits
sitting unpushed, or had background work still running. Every one of those facts is
machine-checkable, and `bilan.sh` checks them.

```bash
.agents/skills/bilan/bilan.sh            # full: local facts, one fetch, one CI query
.agents/skills/bilan/bilan.sh --cheap    # local only, no network
.agents/skills/bilan/bilan.sh sweep      # what a session that DIED left behind
.agents/skills/bilan/bilan.sh scope      # the files the limitation register scans, as bilan resolves them
```

Exit code: `0` = 10/10 · `1` = incomplete · `2` = the script itself failed.

**`--cheap` is the status-line run**, and it gives the full run's number wherever local evidence
can settle the fact. Where only the network can, the cheap run states what it cannot know instead
of guessing: commits that look unpushed against a fetch more than five minutes old (it caps at 9,
"may already be on the remote", where the full run fetches first), a filed issue someone else may
have closed, or a `limitation` label applied outside `carnet.sh` (cheap keeps the cap on both), and
whether the issue a new marker names is live (cheap does not judge it). CI is printed, never
scored, so skipping it changes what is shown, not the score.

Every scoring run also writes `score<TAB>epoch<TAB>top cap` to
`${CLAUDE_CONFIG_DIR:-~/.claude}/bilan/<session-id>.score`, so a status-line command can show the
number without running anything: it only has to refresh the file with `bilan.sh --cheap --json`
when it ages.

## The rules

1. **Run it before you give a number.** Report the score it prints. If you believe a cap is
   wrong, say so in words *and still report the script's number* — arguing with the measurement
   is a conversation, overriding it silently is the failure this exists to stop.
2. **A cap is a fact, not an opinion.** Each one prints its own evidence and its own remedy.
   The score is `min()` over the caps, so one open carnet issue holds the whole session at 6 no
   matter how much else landed.
3. **Failures never deduct.** A red that is now green, a mistake found and fixed, a rough path —
   none of it lowers the number. The score measures *completion*, and only completion.
4. **Friction is printed, never scored.** Tool errors, interrupts and denials are counted from
   the transcript and shown for context. They do not cap anything.

## What caps the score

| Evidence | Caps at |
|---|---|
| carnet issue claimed by this session, neither closed nor released | **6** |
| carnet issue **filed** by this session and still open (unless a registered limitation) — or, in the full run, whose state the tracker would not give | **6** |
| `LIMITATION(registre#…)` marker added in a file the register scans, naming no live issue — missing, closed, a pull request, or not labelled `limitation` (full run only), or no issue number at all | **6** |
| `LIMITATION(` marker added in a file with an extension the register scans, while the register cannot say what it scans (`.registre` not checked out) | **6** |
| background task still running | **7** |
| tracked files modified and uncommitted | **7** |
| todo still `pending` or `in_progress` | **7** |
| commits not pushed — to the upstream, or, on a branch without one, not on `origin/main` | **8** |
| nothing measurable — no commit, no todo | **9** |
| untracked files, a stash made on this branch during this session, a local branch whose upstream is gone | **9** |
| commits to push but the validation marker is missing, older than the pre-push hook's TTL, or for another sha | **9** |
| commits that look unpushed against a stale fetch (`--cheap` only) | **9** |
| dev stack this session started still up — only in a checkout that ships `bin/dev-processes.sh`; this repo has none, so here it never fires | **9** |

## This repo's shape

**Worktrees and branches without an upstream.** Work here commonly happens in
`.claude/worktrees/*`, on branches that have no upstream at all: a branch is typically landed on
main with `git push origin <branch>:main` rather than published. So a branch without an upstream
is measured against `origin/main` — its commits that are not there are unpushed work, and the
remedy says how this repo lands it. Measured against an upstream it does not have, a worktree's
work would never be reported at all.

**The validation marker** is `scripts/pre-push-validate.sh`'s: one line, `<epoch> <sha>`, in the
checkout's own git dir. In a worktree that is `.git/worktrees/<name>/validation-passed`, so a
marker stamped from the main checkout never validates a worktree's HEAD — the pre-push hook would
refuse that push, and bilan says so first. The TTL is read from `.githooks/pre-push` rather than
restated, so the two cannot disagree about when a marker has expired.

**The stash is one stack.** Every worktree pushes onto the same `refs/stash`, so a stash made on
another branch was made in another checkout: it is stated and never counted. For this session's
own stash the remedy is to apply or drop it by its exact ref — a bare `git stash pop` in a shared
stack can take a peer's work.

**The ledger directory is machine-wide.** `${CLAUDE_CONFIG_DIR:-~/.claude}/carnet-claims/` holds
the ledgers of every repo whose sessions use carnet, and each line names the register its issue
lives in. An issue is a (number, tracker) pair: a question about it goes to its own tracker, and
an issue on another repo's register is named as `owner/repo#n`, never as this repo's `carnet#n`.
Every question goes over REST (`gh api`), never `gh issue`: that is GraphQL, which a cloud
session's proxy refuses, and a refusal read as an answer kept a closed issue's cap. A tracker that
does not answer at all keeps the cap and says so, rather than reporting the issue as open.

## Uncommitted files that are not this session's

Several sessions can share one checkout. A peer's mid-edit file used to hold every other session
at 7 — and those sessions were right to refuse to touch it, so they could not reach 10 no matter
what they did. That was a category error: the score measures *this session's* completion, and a
peer's in-flight file is not this session's incompleteness.

Two mechanisms fix it, and the first needs nothing from you.

**The baseline.** The SessionStart hook records which tracked files were already dirty, and which
commits were already unpushed, when the session opened. A path dirty before the session existed
is definitionally not its work — that much *is* machine-decidable. Those are stated as a note and
never scored. The hook records it once per session: SessionStart fires again on resume and after
a compaction, and re-recording there would declare the session's own uncommitted work inherited.
It describes the checkout the session opened in and no other: in a worktree the session reaches
afterwards, nothing is inherited — a path dirty in the main checkout says nothing about the
same-named file there, and a peer's pre-existing worktree is what `ack` is for.

**`ack`, for what goes dirty afterwards.** A peer editing during your session is not covered by
the baseline, so you say so once:

```bash
bilan.sh ack --why "a peer's version bump, written into this shared checkout at 10:16"
```

That **clears** the cap rather than softening it, and carries the reason into every later
report. It is keyed to the exact set of paths the cap names — what the baseline already calls
inherited is not in it — so dirtying one more file brings the cap straight back, and ownership is
a property of the files rather than their contents, so a peer changing those same files again
stays covered.

**Why not attribute automatically?** It was tried and it does not work. Claude Code records the
paths a session touched under `file-history-snapshot.trackedFileBackups`, but only for the
Edit/Write tools, so a session that writes its files through Bash leaves that map empty.
Attributing on it would call a session's own work a peer's and stop blocking — the worst
direction to be wrong in.

The dev-stack cap follows the same rule where a checkout has one: a stack already running under
the same pid when the session opened is a peer's, stated and never scored, because the only way
to clear such a cap would be to take the peer's servers down.

## The three call sites

**`/bilan`** — on demand, the full measurement including CI.

**The startup sweep** (`hooks/session-start-sweep.sh`) — records the baseline, then looks for
what a dead session left behind. A `kill -9`, a closed terminal or an exhausted context fires no
exit hook, so the dead session can never report on itself; the session that opens after it looks
instead, across every worktree of this repo and every ledger on the machine. It reports only this
repo's register, and drops a dead session's claim once the tracker says the issue is closed, so a
long-resolved issue does not keep reappearing as abandoned.

The ledger directory is shared, and not every sweep that reads it keeps to its own register. This
one asks each claim's own tracker and leaves another register's lines alone. The sweeps of
dravr-platform and mirroir-mcp, as they stand, do not: each asks its own tracker about every claim
number in a ledger whose session it takes for ended, whatever register the line names. A claim held
here can then be reported there as that repo's own `carnet#n`, or be deleted — and the ledger with
it, once nothing is left — because that repo's issue of the same number is closed, before this sweep
ever sees it. The claim marker on the tracker is untouched: `carnet.sh status <n>` still shows who
holds the issue and whether that session has ended.

A ledger belongs to its session, so the sweep rewrites or deletes one only when that session has
certainly ended. A session is judged by its id, not by the pid its ledger recorded — carnet writes
that pid once, and `claude --resume` keeps the id under a new one — from the session files Claude
Code keeps under its config dir (`$CLAUDE_CONFIG_DIR` and every `~/.claude*`): a file naming the id
with a live pid is a running session, and the sweep leaves it alone. When the sweep cannot tell —
the ledger was written on another host, or its recorded pid still runs with no session file to say
whose it is — it reports the claims as possibly still held, and clears nothing.

**The Stop gate** (`hooks/stop-gate.sh`) — **disarmed, and deliberately not wired.** It would
refuse a stop while the score was 8 or below. That is only as good as the number: a gate grading
something the session did not do — a shared checkout's HEAD, a peer's red CI — blocks every
session in that checkout over a commit none of them made, and each block spends a session's last
turn arguing with a number about somebody else's work. A wrong number costs a paragraph; a wrong
number that can *stop the session* costs every turn it had left, and fixing one misattribution
does not remove the class. The script is kept, and its tests run, so re-arming it would be a
decision about a script that works.

## Work that leaves no trace

Two kinds of incompleteness are invisible to git, the ledger and CI, and both have produced a
false 10:

**Background tasks.** Claude Code writes each one's stream to the session's `tasks/` directory,
beside its scratchpad (`…/<session-id>/tasks/<id>.output`), and closes it with
`[exited with code N]` or `[killed]`; no marker and a live holder means it never ended. The
directory is per session, so each terminal answers only for its own work. A session working in a
worktree has it keyed by the directory the session was launched from — usually the main
checkout — and bilan looks there too. Closing a session with a task running throws that work
away, so it caps at **7**. bilan runs *inside* one of those streams itself, so a file held by
anything in its own process ancestry is this invocation, not a task.

**Issues the session filed.** `carnet.sh create` writes a `filed` line to the ledger and `close`
removes it, so what remains is what this session opened and did not fix. That caps at **6** —
level with an issue still held, because filing instead of fixing is the same unfinished work
wearing a label. The standing rule is *fix first, file only the residue*; if something genuinely
cannot be fixed here, that is a decision to put in front of the project maintainer, not a cap to
slip past.

**Except a registered limitation, which is the one filed issue that is not work owed.** The
LIMITATION procedure *requires* an open issue for as long as a marker names it, so this cap would
punish a session for obeying it — and there would be nothing it could do, because the fix is to
close an issue the rules say must stay open.

So the exemption needs **both** halves: the issue carries the `limitation` label, *and* a
`LIMITATION(registre#n)` marker names that issue **in a file the register scans**. A label alone
still caps, so a bug cannot be relabelled out of the score; a marker naming a dead issue is caught
by the marker check from the other side. Both together mean the issue is a register entry rather
than deferred work, and it prints as a **note** — the gap stays visible instead of disappearing
into a clean pass, which is the entire point of registering it. The exemption only speaks for this
repo's register: the marker half is this checkout's scope.

**Both halves are local, so `--cheap` credits it too.** The full run reads the label from the
tracker, falling back to the ledger only when the tracker cannot be asked at all. `--cheap` reads
the line `carnet.sh` writes to the session ledger when it applies the label — `create --label
limitation` or `label <n> +limitation` — and removes on `-limitation`. A label applied any other
way (the web UI) reaches only the full run.

## Where a marker counts

**It is the register's decision, not bilan's.** The register is llm-registre, a git submodule at
`.registre`; its gate scans the directories its callers name, for the extensions `registre.toml`
configures, minus test, bench, example and generated trees. bilan asks the gate for that set
instead of keeping its own copy of the exclusions: a copy drifts from the gate both ways, and a
marker one tool honoured would be invisible to the other.

- **The files.** A gate new enough to have `--list-files` is asked for them. An older gate is
  probed: it runs once with a recording ripgrep first on `PATH`, and bilan lists files with exactly
  the directories and globs the gate composed — which is what `--list-files` itself runs. Either
  way nothing is copied. `bilan.sh scope` prints the answer and how it was reached.
- **The directories.** `scan_dirs` in `registre.toml` when it declares them (`REGISTRE_SCAN_DIRS`
  wins, as it does for the gate); otherwise the directories the CI lane passes the gate
  (`.github/workflows/limitation-register.yml`), read out of the workflow rather than restated. The
  suite checks that bilan resolves the same directories `scripts/pre-push-validate.sh` runs the
  gate on, so the callers cannot drift apart without a failing test.
- **Test trees are outside it.** A gap in test *coverage* is therefore marked on the production
  item the tests leave uncovered, not on the test that fails to cover it.
- **Only what the session added.** Markers are read from the session's diff against the merge
  base, committed and not, so a checkout that is merely behind is neither credited with nor blamed
  for a line it has not merged.
- **When the register cannot say** — `.registre` is not checked out, which is the usual state of a
  fresh worktree (`git submodule update --init .registre`), or the gate listed nothing — a marker
  this session wrote cannot be verified, and it caps at 6. Only a marker in a file whose extension
  the register scans: prose, scripts and this skill's own tree are outside every register's scope,
  so a session that merely documents the convention is not held to it. Once the submodule is
  populated in a worktree, a plain `git worktree remove` refuses it (git will not remove a
  worktree containing submodules): check it holds nothing uncommitted, then `--force`.
- **The tracker is private.** A token that cannot read it leaves a new marker unverified; the cap
  then says the tracker was unreadable (`gh auth status`) rather than blaming the marker.

## When there is nothing to measure

A score of 10 means *everything I checked is done*. When nothing was checkable, 10 means nothing
at all — and that is how a session reported 10/10 with its artifact unwritten. bilan reads the
repo, the register and CI; a session whose work is research, a design, a document or a published
artifact touches none of the three, so every check came back clean because every check came back
empty.

So a session that made no commit and declared no todo caps at **9**, labelled *nothing
measurable*. It caps rather than blocks, because answering a question really is a complete
session; what it must not do is issue a verdict it never earned. Declaring the work as a todo,
or committing something, makes it measurable again.

The verdict is measured against an ask, so it waits for one. A status line renders before the
first prompt arrives, and without that condition every session would open at "9/10 nothing
measurable" for having done nothing in its first second. A session nobody has asked anything of
scores clean.

Every report also carries the **ask**, verbatim: `asked:` is the latest prompt the project
maintainer typed, and `opened:` the first, when they differ. bilan cannot judge whether the work
satisfies it — that would be narration again, the thing it exists to treat — but it can refuse to
let a session claim completion without the request in view. The latest counts because a long
session is not what it opened with. A scheduled wakeup, a task notification or a peer message is
text the session or the harness wrote, not an ask, and the transcript marks it so.

## CI, and one thing it cannot see

**CI is printed, never scored.** A shared checkout has one HEAD and many sessions, all committing
as the same author, so whose commit the tip is cannot be recovered from git. Grading the tip caps
every session in the checkout over one peer's red; grading it only when HEAD moved since the
session opened is no better, because HEAD moves when peers push, and a session is then graded on
a tip that landed after its own push. The verdict is shown; the number stays about work the
session can act on. For CI on your own commit, ask for that sha by name.

It is read from the check-runs API by full sha, every page: `gh run list --commit` with a short sha
answers with an empty list, and a failure on the second page of a commit's check runs is still a
failure. A sha with no check run prints nothing — absent, not green.

**Background work is scanned in the session's `tasks/` directory only.** Whether every kind of
in-flight work can be detected there — a Monitor, a subagent, whose entry is a link to its
transcript and never carries an exit marker — has not been confirmed. Treat the running-work cap
as covering background Bash tasks; a session that knows it has other work in flight should say
so rather than trust the absence of a cap.

## What it does not do

It cannot tell you whether the work is *good*, only whether it is *finished*. A green bilan on
a wrong implementation is still a wrong implementation — that is what `/code-review` is for.

It cannot see a deliverable the session never declared. A todo, a commit or an issue makes work
visible to it; an artifact written and published with none of those does not. The *nothing
measurable* cap is how it says so out loud instead of scoring an empty check clean.

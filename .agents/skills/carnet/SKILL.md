---
name: carnet
description: Work the private register (Atmosphere/atmosphere-carnet) from a session — claim an issue before touching it so peers see who holds it and which session, release or close it when done, file new issues in the one canonical shape. Use whenever a carnet#N or registre#N issue is mentioned, when you start or stop work that an issue tracks, or when you are about to run gh issue against the tracker.
argument-hint: <claim|release|status|mine|create|close|label|tracker> [args]
user-invocable: true
---

# Carnet

The register is `Atmosphere/atmosphere-carnet`, named by `registre.toml`. It is **private**
because this repo is public and an entry states precisely where a defence or a capability is
incomplete. Many Claude Code sessions work it at once, and nothing on a GitHub issue says a
session is on it, so peers collide: two sessions pick up the same issue and build the same fix
twice, or find the same gap and write it up twice. A **claim** fixes that. It is three native
GitHub facts that move together:

| Carrier | Meaning |
|---|---|
| assignee | the human accountable (gh login) |
| label `in-progress` | a live session holds the issue |
| newest `carnet-claim` marker comment | **which** session — id, name, user, host, pid, repo, branch, time |

One script does everything: `.agents/skills/carnet/carnet.sh` (also reachable through the
`.claude/skills/carnet` link). Never run `gh issue` against the tracker by hand — the script is
the one path that keeps the three carriers consistent and the title shape uniform.

## The rules

1. **Claim before the first edit — and it happens without you.** The PreToolUse hook claims
   every issue the prompt named as soon as you touch a write tool, so an issue you were told
   about is held before your first edit lands. Run `claim <n>` yourself when the number never
   appeared in a prompt (you found the issue by searching, or you are picking up work
   mid-session). Not after the commit: a claim made after the work is done protects nobody.
2. **A refusal is a peer, not an obstacle.** Exit code 2 means another *live* session holds
   the issue, or a session on another host does. Tell the user who and which session, and
   stop. The auto-claim hook enforces this once: it blocks your first edit and names the
   holder. It does not block again — after that you are accountable, not the hook. Do not
   `--steal` on your own judgement — stealing is the user's call, and the stolen-from session
   is warned on the issue. A claim whose session has *ended* is not a refusal: `release` and
   `close` exit 1 on it and say so, and a plain `claim` takes it over.
3. **Release when you stop, close when it is fixed.** `release <n>` when you abandon or hand
   off; `close <n> --why "…" --commit <sha>` when the work landed. Both drop the label and
   the assignee and post a marker. If another session took the issue over, `release` posts
   nothing and only drops it from this session's ledger. A session that ends still holding
   claims is released by the SessionEnd hook, so a forgotten release is not fatal — but do
   not rely on it.
4. **Unreadable is not unclaimed.** When the issue's comments cannot be read in full, `claim`,
   `release`, `close` and `status` exit 1 and change nothing: the newest marker is the only
   record of who holds the issue, and deciding without it is how two sessions end up on one.
5. **Every close says why.** `--why` is mandatory and is what the next reader sees first.
   Add `--commit <sha>` whenever a commit resolved it: the tracker is a different repository,
   so `carnet#N` in a commit message is plain text to GitHub and never closes anything there.
   `--commit` posts the commit's URL on `origin`.
6. **File through `create`.** It reads the tracker from `registre.toml`, refuses a tracker that
   is not private (or whose visibility it cannot read), prefixes the title with the project —
   `[atmosphere] ` — unless the title already starts with `[`, and always adds the
   `atmosphere` label. Titles are `[<project>] <Thing>` — one shape, no variants, capitalised
   first word unless it is an identifier. The body says where it is (file + symbol), what is
   incomplete, and what the fix looks like. The `limitation` label goes only on an issue that a
   `LIMITATION(registre#n)` marker in source will point at; plain findings get the project
   label alone.

## Commands

```bash
C=.agents/skills/carnet/carnet.sh

$C claim 12                       # hold it: assign me, label, marker comment, local ledger
$C claim 12 --steal               # take it from a live or remote session — the user's decision only
$C release 12 --reason "handed to the transport session"
$C status 12                      # who holds it, is that session alive, which branch
$C status                         # every in-progress issue in the tracker
$C mine                           # what this session holds (no API call, works with gh logged out); --verify to check the tracker
$C create --title "<Thing that is incomplete>" --label bug --body-file /tmp/body.md --claim
$C close 12 --why "<what resolved it>" --commit <sha>
$C label 12 +critical -bug
$C tracker                        # the register this checkout files into (no API call)
```

Add `--dry-run` to any of them to see the `gh` calls without making them.

Read the thread before you implement — comments carry requirements the body does not:
`gh api "repos/$($C tracker)/issues/12/comments" --paginate -q '.[].body'`. The script reaches
the tracker through `gh api` (REST) only, because `gh issue` is GraphQL and a cloud session's
agent proxy refuses every GraphQL query outside a pinned set of PR-review operations.

## What the hooks do for you

- **UserPromptSubmit** (`hooks/prompt-status.sh`): when a prompt names `carnet#N`,
  `registre#N`, or a carnet issue URL, one status line per issue lands in your context
  before you answer — `carnet#12 · held by @alice · session Wiring (a3f9c2d1) on build-host
  [running] · feature/wiring · since …`. Read it. If it says `unclaimed`, claim before
  editing. If it says `[session ended — stale]`, a plain `claim` takes it over. A peer
  message or a background task result gets the status line **plus a note saying it armed
  nothing** — see *A peer naming an issue is not assigning it* below. Lines are cached for a
  minute under the tracker's name, so a checkout that files into another register never
  reads this one's.
- **PreToolUse** (`hooks/auto-claim.sh`): claims those issues for you, on the first
  write-shaped tool call after the prompt that named them. Reading is not working — a
  question about an issue never reaches a write tool and never claims. A `Bash` call counts
  as an edit only when the command looks like one (a redirect into a file, `sed -i`, `mv`,
  `git commit`, …), because a session that edits through bash would otherwise never claim.
  If a live peer holds the issue it blocks that one tool call and names them. Before
  claiming it asks the transcript **who wrote the prompt** that named the issue: a
  `/loop` or ScheduleWakeup re-fire is text the model wrote for itself, and a peer message
  or task result is text no human wrote, so a list armed by any of those claims nothing and
  prints `carnet: NOT claimed — carnet#N came from a scheduled wakeup …` instead. Take it
  deliberately if it is yours: `carnet.sh claim <n>`.
- **SessionEnd** (`hooks/session-end-release.sh`): releases everything this session still
  holds, from its ledger under `$CLAUDE_CONFIG_DIR/carnet-claims/`. Zero calls when nothing
  is held.

All three are wired in `.claude/settings.json`; the exact entry is at the top of each hook
file, and `test.sh` fails when one is missing or when the PreToolUse wiring would swallow the
hook's exit code. The auto-claim hook costs one `stat` when nothing is pending, which is
almost always — it runs before every edit in every session.

**What it deliberately does not do.** It never claims from a prompt alone, so asking about an
issue is free. It never steals. It forgets a pending list an hour old, so an issue mentioned
long ago is not claimed by an unrelated edit. It never claims from a peer message, a
background task result, a wakeup prompt the session scheduled for itself, or terminal output
pasted into the prompt. And it never blocks twice for the same issue: a permanent block would
deadlock a session over an issue that was only mentioned in passing.

**A wakeup you write is not an assignment you received.** When you schedule a wakeup
(`/loop`, `ScheduleWakeup`), the prompt that comes back is yours, and the hooks know it: the
prompt hook cannot tell at submit time (as of Claude Code 2.1.276 the payload carries no
`source` yet, and the transcript entry is written after the hook runs), so it records the
prompt id, and the claim hook reads that entry — `promptSource: "system"`, `isMeta: true`,
`scheduledTaskId` — before claiming. Without that, a session that wrote "comment on carnet#N"
into its own wakeup would hold #N for as long as it ran, the user never having typed the
number. Do not put issue numbers into a wakeup prompt as a to-do list for yourself; and if you
are told a claim was refused because the prompt was a wakeup, that is the hook working.

## A peer naming an issue is not assigning it

Sessions message each other, and many of them are working on something else entirely — another
repo, another product, a goal that has nothing to do with the register. When one of those is
asked a question, the whole correct answer is **to answer it**. Do not claim the issue the
sender mentioned, do not assign it to yourself, do not comment on it, do not start fixing it.
If the message does not concern you, one line saying so is a complete reply.

That is enforced, not just asked for. A peer message arrives in the prompt hook as
`<cross-session-message from="…">`, and a background result as `<task-notification>` — both
byte-identical to something the user typed. Arming the claim list from them goes wrong in both
directions:

| | what goes wrong |
|---|---|
| **false claim** | a peer writes *"do **not** put this in carnet#N"*, or replies *"not mine"*, and the recipient's next edit claims #N — label, assignee and marker comment all assigned to a session that was never going to work it. |
| **false block** | one FYI (*"I hold carnet#N, stay off these files"*) blocks a tool call in every session it reaches, each told *"Do not do this work twice"* about work it never started — some of them in another repo entirely. |

Both vectors print the status line and arm nothing: knowing who holds `#N` is exactly what you
need in order to answer the sender, and a peer cannot redirect your session's claim onto an
issue it happened to mention. The same goes for a peer's terminal output the user pastes: if
the prompt carries transcript glyphs (`⏺ ⎿ ✻ ✢ ⏵ ❯ ───`) anywhere, nothing arms. Taking work a
peer hands over is still fine — it is just explicit: `carnet.sh claim <n>`.

**When you are the sender**, say which of the three you mean, in the first line:

| Intent | Write it as |
|---|---|
| FYI, no action | `FYI only — I hold carnet#N and am editing <files>. Nothing for you to do; ignore if you are in another repo.` |
| A question | `Question, no action on the issue: <question>. carnet#N is mine and stays mine.` |
| A real handoff | `Handing off carnet#N — I have released it. If you take it, claim it first.` |

The first line is all the recipient's human sees as a preview, and it is what stops an
unrelated session from adopting your work out of helpfulness.

## How liveness is decided

Claude Code writes `sessions/<pid>.json` under the config dir for every running session and
removes it on exit. A claim on this host is **running** when that file exists with the same
session id and the pid answers `kill -0`; otherwise it **ended** and `claim` takes it over
with a "took over" line. A claim from another host cannot be checked, so it is refused
without `--steal`. Outside Claude Code (`session=manual`) a claim is advisory: it records the
human, and nothing auto-releases it. It is that human's alone — every shell outside Claude Code
records the same `manual` session, so a manual claim counts as the caller's own only when it
also names the caller's gh login.

## Where things are

| | |
|---|---|
| Tracker | `registre.toml` → `tracker` = `Atmosphere/atmosphere-carnet` (PRIVATE); `REGISTRE_TRACKER` overrides it, as it does for the limitation gates |
| Title prefix + label | the repo name from `origin` — `[atmosphere]` and `atmosphere` — never the checkout's basename, which in a worktree is the branch or agent name |
| Ledger | `${CLAUDE_CONFIG_DIR:-~/.claude}/carnet-claims/<session-id>.jsonl`; every line names its tracker. Subagents share their session's ledger, so every change to it holds `<session-id>.jsonl.lock` (a directory) while it is written. `carnet-claims/` is kept at 0700: its cache holds private titles |
| Status cache | `${CLAUDE_CONFIG_DIR:-~/.claude}/carnet-claims/cache/<tracker>/<n>`, one minute |
| Tests | `.agents/skills/carnet/test.sh` — stub `gh`, every refusal path fires; CI runs it from `.github/workflows/carnet.yml` |

## Related

- **Limitation markers.** The limitation register gates (`.registre/limitation-gates.sh`, run
  at pre-push and by `.github/workflows/limitation-register.yml`) ban deferral prose unless
  the line carries a `LIMITATION(registre#n)` marker naming the limited item and pointing at
  an issue in this tracker. File that issue with `create --label limitation` first — it prints
  the marker line to fill in — then write the marker.
- An open decision in a plan or audit is filed the same way — one issue per decision, so each
  gets its own close.
- **bilan** (`.agents/skills/bilan/`) reads this skill's ledger: a claim still held, or an issue
  filed this session and still open (unless it is a registered limitation), caps the session's
  completion score at 6.

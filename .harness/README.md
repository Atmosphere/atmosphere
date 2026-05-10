# `.harness/` — AI-Assisted Engineering Instrumentation

Atmosphere's instance of Reock's DX impact-metric framework
(InfoQ, *AI-Assisted Engineering*, 2026-05). The point of this directory is
**not** utilization metrics — that's Goodhart's-Law-bait. The point is to
keep prose claims honest against running code, and to record every agent
claim that diverged from ground truth so the *rate* of divergence over time
is a diff-reviewable curve.

If you've never heard the framing, the one-line summary is: **the model is
the engine; the harness is the rails.** The orgs that get +20% from AI
have an instrumented feedback loop on claim quality. The orgs that get −20%
don't. This directory is our loop.

---

## Files

| Path | Purpose | Source of truth | Append-only? |
|------|---------|-----------------|---------------|
| `capabilities.snapshot.json` | Canonical aggregate of `AiCapability` enum (currently 20 entries) and each runtime's pinned `expectedCapabilities()` (currently 9 runtimes). Used to validate aggregate count claims in `modules/ai/README.md`. | `modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java` + every `*RuntimeContractTest.{java,kt}` | No — regenerated whenever a capability or runtime is added/removed. |
| `drift-log.md` | Structured record of every agent claim that diverged from ground truth (claim, truth, slip path, gate added). The diff-reviewable curve of hallucinations-over-time. | Appended by the agent during sessions; reviewed by the maintainer. | **Yes** — pre-existing date sections are immutable. New entries go in today's section (or a new section at the bottom). |

Adding new files here means adding a new instrument. If you can't articulate
what failure mode it catches and what gate enforces it, don't add it.

---

## Validators (pre-push Tier 1, gating)

Both run from `scripts/pre-push-validate.sh` Tier 1 alongside
`architectural-validation.sh`. Each is fast (~1s on a clean tree) so they
don't add meaningful pre-push latency; they're the cheapest gate that
catches the highest-leverage drift class.

### `scripts/validate-capability-claims.sh`
1. Re-derives the snapshot from source (calls `regen-capability-snapshot.sh --check`).
2. Greps `modules/ai/README.md` for tight count patterns (`\bAll \d+ runtimes?\b`,
   `\b\d+ AiCapability\b`, `\b\d+ capabilities total\b`).
3. Asserts every match equals the snapshot count.

Loose patterns (e.g. "the other N runtimes" — a subset count that depends
on which runtimes opt into a feature) are deliberately not validated;
those need human review.

### `scripts/validate-drift-log.sh`
Structural hygiene only — the validator does **not** enforce that drift
gets *added* (the Stop hook does that). It enforces:

1. File exists and parses.
2. At least one `## YYYY-MM-DD` section present.
3. No future-dated section headers.
4. Sections in chronological order (oldest top, newest bottom).
5. Pre-existing date sections (older than today) match `origin/main`
   verbatim — append-only against the upstream baseline.

### `modules/ai-test/.../CapabilitySnapshotTest`
Pure-Java mirror of the bash validator, so `mvn test` catches the same
drift the pre-push hook would. Re-derives the snapshot from source,
deep-equals against the committed JSON, then greps `modules/ai/README.md`
for `\bAll \d+ runtimes?\b` and asserts against the snapshot count.

---

## Hooks (Claude Code, behavioral)

Registered in `.claude/settings.json` under the project-level `hooks`
block. Adds to (does not replace) any hooks defined in
`~/.claude/settings.json`.

### `Stop` hook → `.claude/hooks/check-drift-log.sh`
Fires at the end of every Claude Code session in this repo.

1. Reads the session transcript path from the hook input JSON.
2. Greps the transcript for **high-precision** drift-correction patterns
   (`stale memory`, `\boff-by-one\b`, `I (was wrong|claimed)…(but|actual|truth)`,
   `memor… was/is wrong/stale/out of date`, `fabricated rule/stat/count/claim`,
   `verified by grep…disagree/contradict/wrong/stale`).
3. If a pattern matches **and** `.harness/drift-log.md` was not modified
   this session (no working-tree diff, no untracked add, not in last 3 commits),
   emits `{"decision": "block", "reason": "..."}` to force the agent to
   either append an entry or explicitly state the correction was trivial.
4. `stop_hook_active=true` short-circuits to a no-op so deliberate skips
   don't loop.

The patterns are deliberately narrow to minimize false positives. If a
recurring real correction shape isn't matching, add a new pattern with
concrete real-session evidence — don't loosen existing ones.

---

## Protocols

### Regenerating the capability snapshot

```bash
./scripts/regen-capability-snapshot.sh           # write the snapshot
./scripts/regen-capability-snapshot.sh --check   # exit 1 if it would change
./scripts/regen-capability-snapshot.sh --stdout  # write to stdout
```

`LC_ALL=C` is forced inside the script so bash `sort` matches Java's
`String.compareTo` (ASCII code-point order, not locale-aware collation
that treats `_` differently from `I`). Without that the snapshot's
capability list and the JUnit test's `TreeSet<String>` view diverge.

### Appending a drift entry

When ChefFamille catches an agent claim that diverged from ground truth
(or you self-catch via `git grep` / `find` / file read after spotting
memory↔code disagreement):

1. **Verify against current code first** — re-read the source. Don't
   trust memory files older than the most recent CHANGELOG bump.
2. State the correction transparently in conversation — quote the false
   claim verbatim, quote the ground truth verbatim.
3. Append a structured entry to `drift-log.md` in today's date section
   (start one if today doesn't have one yet). Required columns: claim,
   truth, slip path, gate added.
4. Add a regression-class gate where feasible (validator, JUnit test,
   memory update, prose-grep). Writing `none` is a legitimate value when
   no automated check makes sense — don't fabricate a gate.
5. Bundle log update + gate + prose fix in **a single commit**. That
   commit is the review unit.

The log is **append-only**. Don't edit older entries to "improve" them;
write a new entry pointing back if context changes. Per the Reock framing,
the signal is the *rate* of entries over time, not the cleanliness of any
single one.

---

## Memory anchor

`feedback_drift_log.md` in the user's auto-memory codifies the protocol
above so it loads at every session start. If that memory ever drifts
from this README, the README wins (this directory is the source of truth
for the harness shape; the memory is a pointer).

---

## Why a `.harness/` directory at all

The directory is self-contained and clearly scoped — anyone seeing
`.harness/` knows it's project-engineering plumbing, not runtime code.
That's intentional: it keeps the boundary visible so:

- The pre-push gates know what to validate (everything under `.harness/`
  is fair game for hygiene checks).
- A future contributor doesn't have to grep a dozen places to understand
  the AI-engineering feedback loop — they read this README.
- If we decide to share the pattern publicly, the directory exports
  cleanly.

If you propose adding a file outside `.harness/` that does similar work,
ask first whether it should live here instead.

---

## Further reading

- Justin Reock, *AI-Assisted Engineering* — InfoQ, 2026-05. The DX
  measurement framework (utilization vs. impact vs. cost) and the
  Goodhart's-Law warning on naive utilization metrics.
- `walkinglabs/learn-harness-engineering` — the five-subsystem framework
  (Instructions, State, Verification, Scope, Lifecycle) that motivates
  treating the harness as engineering work, not just configuration.
- `juliusbrussee/caveman` — the snapshot-as-source-of-truth + CI-sync
  pattern that inspired the snapshot file's diff-reviewable shape.
